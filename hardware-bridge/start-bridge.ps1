param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,
    [string]$Adb = 'adb',
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$Adb = (Get-Command $Adb -CommandType Application | Select-Object -First 1).Source
$main = 'local.son.connecthardware.RgbBridgeServer'
$socket = '@local.son.connecthardware.rgb.v1'
$remoteDir = '/data/system/connect-rgb-bridge'
$remoteJar = "$remoteDir/rgb-bridge.jar"
$remoteLog = "$remoteDir/bridge.log"

function Invoke-Device {
    $output = & $Adb -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join [Environment]::NewLine) }
    ($output -join [Environment]::NewLine).Trim()
}

function Get-BridgeProcess {
    $pattern = '^(\d+)\s+0\s+((?:/system/bin/)?app_process(?:32|64)?)\s+/system/bin\s+' +
        [regex]::Escape($main) + '((?:\s+\d+)+)$'
    foreach ($line in (Invoke-Device shell 'ps -A -o PID,UID,ARGS') -split '\r?\n') {
        if ($line.Trim() -cmatch $pattern) {
            [pscustomobject]@{ Id = $Matches[1]; Executable = $Matches[2]; Uids = @($Matches[3].Trim() -split '\s+') }
        }
    }
}

function Test-BridgeListener([string]$ProcessId, [string]$SocketName = $socket) {
    $command = 'for f in /proc/{0}/fd/*; do readlink "$f"; done; cat /proc/net/unix' -f $ProcessId
    $lines = (Invoke-Device shell $command) -split '\r?\n'
    foreach ($line in $lines) {
        $columns = $line.Trim() -split '\s+'
        if ($columns.Count -eq 8 -and $columns[7] -ceq $SocketName -and $columns[3] -eq '00010000') {
            if ($lines -contains ('socket:[{0}]' -f $columns[6])) { return $true }
        }
    }
    return $false
}

if ((Invoke-Device shell 'getprop ro.product.model') -cne 'Npay_connect') {
    throw 'The selected device model is not Npay_connect.'
}
if ((Invoke-Device shell 'id -u') -cne '0') {
    throw 'Root ADB is required. Run adb root on the selected device, then retry with its current ADB serial.'
}

$active = @(Get-BridgeProcess)
if ($active.Count -gt 1) { throw 'Multiple RGB bridge processes found.' }
if ($Stop) {
    if ($active.Count -eq 0) { Write-Output 'RGB bridge is stopped.'; return }
    $processId = $active[0].Id
    if (!(Test-BridgeListener $processId)) { throw 'Could not verify the RGB bridge listener owner.' }
    $actualExecutable = (Invoke-Device shell ('tr ''\000'' '' '' </proc/{0}/cmdline' -f $processId)) -split '\s+' | Select-Object -First 1
    if ($actualExecutable -cnotmatch '^(?:/system/bin/)?app_process(?:32|64)?$') { throw 'Unexpected bridge executable.' }
    $expected = "$actualExecutable /system/bin $main $($active[0].Uids -join ' ') "
    $stopCommand = 'cmd=$(tr ''\000'' '' '' </proc/{0}/cmdline) && test "$cmd" = "{1}" && kill -TERM {0}' -f $processId,$expected
    Invoke-Device shell $stopCommand | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        if (!(@(Get-BridgeProcess | Where-Object Id -eq $processId).Count)) {
            Write-Output 'RGB bridge is stopped.'
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'The RGB bridge is still running.'
}

$packages = 'local.son.connectconsole', 'local.son.connectdiagnostics'
$packageList = (Invoke-Device shell 'cmd package list packages -U --user 0') -split '\r?\n'
$uids = foreach ($package in $packages) {
    $pattern = '^package:' + [regex]::Escape($package) + '\s+uid:(\d+)$'
    $found = @(
        foreach ($line in $packageList) {
            if ($line.Trim() -cmatch $pattern) { [int]$Matches[1] }
        }
    )
    if ($found.Count -eq 0 -and $package -eq 'local.son.connectconsole') { continue }
    if ($found.Count -ne 1 -or $found[0] -lt 10000 -or $found[0] -ge 20000) {
        throw "Could not resolve the application UID for $package."
    }
    $uid = $found[0]
    $owners = @($packageList | Where-Object { $_ -match ('\s+uid:' + $uid + '\s*$') })
    if ($owners.Count -ne 1) { throw "Application UID $uid is shared or could not be verified." }
    $uid
}

if ($active.Count -eq 1) {
    $existing = @($active[0].Uids | ForEach-Object { [int]$_ } | Sort-Object)
    if (Compare-Object $existing @($uids | Sort-Object)) {
        throw 'The running RGB bridge has a different UID list. Stop it before starting a new instance.'
    }
    if (!(Test-BridgeListener $active[0].Id) -or !(Test-BridgeListener $active[0].Id '@local.son.connecthardware.card.v1')) { throw 'The running helper does not provide RGB and IC. Stop it with -Stop, then start this diagnostics helper.' }
    Write-Output "RGB bridge is running (PID $($active[0].Id))."
    return
}

$jar = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'build/rgb-bridge.jar')).Path
$upload = "$remoteDir/upload-$([Guid]::NewGuid().ToString('N')).jar"
$dirCheck = 'umask 077; mkdir {0} 2>/dev/null || true; test ! -L {0} && test -d {0} && test "$(stat -c %u:%a {0})" = 0:700' -f $remoteDir
Invoke-Device shell $dirCheck | Out-Null
Invoke-Device push $jar $upload | Out-Null
$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash.ToLowerInvariant()
$remoteHash = (Invoke-Device shell "sha256sum $upload") -split '\s+'
if ($remoteHash[0] -cne $sha) {
    Invoke-Device shell "rm -f $upload" | Out-Null
    throw 'RGB bridge transfer hash mismatch.'
}

$uidArgs = $uids -join ' '
$launch = 'umask 077; mv -f {0} {1} || exit 1; CLASSPATH={1} nohup /system/bin/app_process /system/bin {2} {3} </dev/null >{4} 2>&1 &' -f $upload,$remoteJar,$main,$uidArgs,$remoteLog
Invoke-Device shell $launch | Out-Null
for ($i = 0; $i -lt 25; $i++) {
    $active = @(Get-BridgeProcess)
    if ($active.Count -eq 1 -and (Test-BridgeListener $active[0].Id) -and (Test-BridgeListener $active[0].Id '@local.son.connecthardware.card.v1')) {
        Write-Output "RGB bridge is running (PID $($active[0].Id))."
        $log = Invoke-Device shell "tail -n 12 $remoteLog"
        if ($log) { Write-Output $log }
        return
    }
    Start-Sleep -Milliseconds 120
}
$log = Invoke-Device shell "tail -n 12 $remoteLog 2>/dev/null; true"
throw "Could not verify the RGB bridge listener. $log"
