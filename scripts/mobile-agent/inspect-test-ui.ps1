param(
    [string]$AdbPath = 'D:\codex\手机agent制作\.build-tools\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'ab1a4481',
    [ValidateSet('me.rerere.rikkahub.debug', 'com.heytap.browser', 'com.jingdong.app.mall', 'com.taobao.taobao', 'com.xunmeng.pinduoduo', 'com.sankuai.meituan')]
    [string]$Package = 'me.rerere.rikkahub.debug',
    [switch]$ToolDetailText
)
$ErrorActionPreference = 'Stop'
# Uses the separate USB fixture and preserves already-running accessibility services.
# The fixture enforces this same package allowlist and omits editable/password text.
if ($ToolDetailText -and $Package -ne 'me.rerere.rikkahub.debug') {
    throw 'Extended tool text is only available for the local Mobile Agent app.'
}
$options = @('-e', 'operation', 'inspect_test_ui', '-e', 'package', $Package)
if ($ToolDetailText) { $options += @('-e', 'tool_detail_text', 'true') }
$result = & $AdbPath -s $Serial shell am instrument -w -r @options me.rerere.rikkahub.debug.inputdriver/me.rerere.rikkahub.data.mobileagent.fixture.PhoneTestInputDriver
if ($LASTEXITCODE -ne 0) { throw 'Test UI inspection failed.' }
$encoded = ($result | Select-String '^INSTRUMENTATION_RESULT: ui_b64=').Line
if ($encoded) {
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded.Substring($encoded.IndexOf('=') + 1)))
    $result | Select-String '^INSTRUMENTATION_RESULT: (partial|text_truncated)='
} else { $result }
