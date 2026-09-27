param(
    [string]$AdbPath = 'D:\codex\手机agent制作\.build-tools\android-sdk\platform-tools\adb.exe',
    [string]$PythonPath = 'C:\Python314\python.exe',
    [string]$Serial = 'ab1a4481',
    [Parameter(Mandatory)]
    [ValidateSet('com.jingdong.app.mall', 'com.heytap.browser', 'com.taobao.taobao', 'com.xunmeng.pinduoduo', 'com.sankuai.meituan', 'me.rerere.rikkahub.debug.test')]
    [string]$Package,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateRange(1000,120000)][int]$WaitForServiceMillis = 8000,
    [ValidateSet('DEFAULT','INCLUDE_UNIMPORTANT','REFRESH_PARENTS','INCLUDE_UNIMPORTANT_REFRESH_PARENTS')]
    [string]$DiagnosticVariant
)
$ErrorActionPreference = 'Stop'
# Explicit invocation only. This does not install APKs, launch the target, or change permissions.
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw 'Use a new output directory to preserve earlier evidence.' }
New-Item -ItemType Directory -Path $destination | Out-Null
$log = Join-Path $destination 'instrumentation.log'
$jsonl = Join-Path $destination 'dynamic-ui-diagnostic.jsonl'
Write-Output 'Starting read-only foreground diagnosis; instrumentation may require the already-authorized service to reconnect.'
$variantArguments = @()
if ($DiagnosticVariant) { $variantArguments = @('-e', 'diagnosticVariant', $DiagnosticVariant) }
& $AdbPath -s $Serial shell am instrument -w -r `
    -e class 'me.rerere.rikkahub.data.mobileagent.PhoneDynamicUiDiagnosticTest#captureCurrentForegroundWithoutActions' `
    -e diagnostic true -e targetPackage $Package -e waitForServiceMillis $WaitForServiceMillis `
    @variantArguments `
    me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Set-Content -LiteralPath $log -Encoding utf8
$adbExit = $LASTEXITCODE
& $PythonPath -B -X utf8 (Join-Path $PSScriptRoot 'decode-dynamic-ui.py') $log $jsonl
$decodeExit = $LASTEXITCODE
if ($adbExit -ne 0 -or $decodeExit -ne 0) {
    throw "Diagnosis did not export a complete run. Preserve local log: $log"
}
# A successful transport may contain PAGE_UNSTABLE or a paused session. It is not page acceptance.
$runLog = Get-Content -LiteralPath $log -Raw
if ($runLog -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or $runLog -notmatch 'OK \(1 test\)') {
    throw "Diagnostic evidence was decoded, but the runner did not pass. Preserve local log: $log"
}
Write-Output "Metadata: $jsonl"
