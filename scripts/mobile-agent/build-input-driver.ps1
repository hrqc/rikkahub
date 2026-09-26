param(
    [Parameter(Mandatory)][string]$SdkPath,
    [Parameter(Mandatory)][string]$JdkPath,
    [Parameter(Mandatory)][string]$DebugKeystore,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$BuildToolsVersion = '37.0.0',
    [string]$Platform = 'android-37.2'
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'input-driver'
$output = [IO.Path]::GetFullPath($OutputDirectory)
$buildTools = Join-Path $SdkPath "build-tools/$BuildToolsVersion"
$androidJar = Join-Path $SdkPath "platforms/$Platform/android.jar"
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
New-Item -ItemType Directory -Force -Path $output,$classes,$dex | Out-Null
$env:JAVA_HOME = $JdkPath
function Assert-Command { if ($LASTEXITCODE -ne 0) { throw "Build command failed ($LASTEXITCODE)" } }
& (Join-Path $JdkPath 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $androidJar -d $classes (Join-Path $source 'PhoneTestInputDriver.java')
Assert-Command
$compiledClasses = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $buildTools 'd8.bat') --min-api 26 --lib $androidJar --output $dex @compiledClasses
Assert-Command
$unsigned = Join-Path $output 'input-driver-unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link --manifest (Join-Path $source 'AndroidManifest.xml') -I $androidJar -o $unsigned
Assert-Command
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dex 'classes.dex'), 'classes.dex') | Out-Null
} finally { $archive.Dispose() }
$apk = Join-Path $output 'mobile-agent-input-driver.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $unsigned
Assert-Command
& (Join-Path $buildTools 'apksigner.bat') verify $apk
Assert-Command
Write-Output $apk
