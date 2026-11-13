param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$ApkName = 'ConnectDiagnostics-0.1.2-public.apk'
)
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
if (!$Jdk) { throw 'Set JAVA_HOME or pass -Jdk.' }
$Sdk = (Resolve-Path -LiteralPath $Sdk).Path
$Jdk = (Resolve-Path -LiteralPath $Jdk).Path
Set-Location -LiteralPath $PSScriptRoot
$bt = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH
$out = Join-Path $PSScriptRoot 'build'
$apk = Join-Path $out $ApkName
New-Item -ItemType Directory -Force -Path $out,(Join-Path $out 'classes'),(Join-Path $out 'tests'),(Join-Path $out 'dex'),(Join-Path $out 'res') | Out-Null
function Check { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
$sources = @(Get-ChildItem -LiteralPath 'src' -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath '../hardware-bridge/src' -Recurse -Filter '*.java' | Where-Object { $_.Name -in 'RgbBridge.java', 'RgbLed.java', 'CardBridge.java' } | ForEach-Object FullName)
& "$Jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d "$out\classes" @sources
Check
& "$Jdk\bin\jar.exe" "-J-Djava.io.tmpdir=$out" cf "$out\classes.jar" -C "$out\classes" .
Check
& "$bt\d8.bat" --min-api 28 --lib $androidJar --output "$out\dex" "$out\classes.jar"
Check
& "$bt\aapt2.exe" compile --dir res -o "$out\resources.zip"
Check
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest AndroidManifest.xml -A assets "$out\resources.zip"
Check
Copy-Item -LiteralPath "$out\base.apk" -Destination "$out\unsigned.apk" -Force
# Windows AAPT2 can write nested asset paths with backslashes, which AssetManager treats as literal names.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $nestedAssets = @($archive.Entries | Where-Object { $_.FullName.StartsWith('assets/') -and $_.FullName.Contains('\') })
    foreach ($entry in $nestedAssets) {
        $normalized = $archive.CreateEntry($entry.FullName.Replace('\', '/'), [System.IO.Compression.CompressionLevel]::NoCompression)
        $source = $entry.Open()
        try {
            $target = $normalized.Open()
            try { $source.CopyTo($target) } finally { $target.Dispose() }
        } finally { $source.Dispose() }
        $entry.Delete()
    }
} finally { $archive.Dispose() }
Push-Location "$out\dex"
try { & "$bt\aapt.exe" add "$out\unsigned.apk" classes.dex; Check } finally { Pop-Location }
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
Check
$key = Join-Path $out 'local-dev.jks'
if (-not (Test-Path -LiteralPath $key)) {
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore $key -storepass android -keypass android -alias console -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Connect Diagnostics Local Development'
    Check
}
& "$bt\apksigner.bat" sign --ks $key --ks-pass pass:android --ks-key-alias console --out $apk "$out\aligned.apk"
Check
& "$bt\apksigner.bat" verify --verbose $apk
Check
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
