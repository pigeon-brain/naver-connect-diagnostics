param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
if (!$Jdk) { throw 'Set JAVA_HOME or pass -Jdk.' }
$Sdk = (Resolve-Path -LiteralPath $Sdk).Path
$Jdk = (Resolve-Path -LiteralPath $Jdk).Path
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$d8 = Join-Path $Sdk 'build-tools\36.0.0\d8.bat'
$env:JAVA_HOME = $Jdk
$out = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $out 'classes'
$artifact = Join-Path $out 'rgb-bridge.jar'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$Jdk\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $androidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }
$compiled = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& $d8 --min-api 28 --lib $androidJar --output $artifact @compiled
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }
Get-FileHash -Algorithm SHA256 -LiteralPath $artifact
