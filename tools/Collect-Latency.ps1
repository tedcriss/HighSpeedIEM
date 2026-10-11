param(
    [string]$Serial = 'emulator-5554',
    [string]$OutputDirectory = (Join-Path 'C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa' ('latency-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [string]$Scenarios = 'native-selftest,audio-latency,forward-background,forward-start-stop,error-stop'
)
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$packageName = 'cn.xiaotao29.HighSpeedIEM'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& $adbPath -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw "adb target unavailable: $Serial" }
$instrumentation = & $adbPath -s $Serial shell pm list instrumentation
if (($instrumentation -join "`n") -notmatch 'cn\.xiaotao29\.HighSpeedIEM/\.test\.UnifiedTestInstrumentation') {
    throw 'Install the project debug APK before collecting latency comparisons.'
}
& $adbPath -s $Serial shell pm grant $packageName android.permission.RECORD_AUDIO
& $adbPath -s $Serial shell pm grant $packageName android.permission.POST_NOTIFICATIONS
& $adbPath -s $Serial shell am start -n "$packageName/.MainActivity"
$result = & $adbPath -s $Serial shell am instrument -w -e scenarios $Scenarios "$packageName/.test.UnifiedTestInstrumentation" 2>&1
$result | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'scenarios.txt')
$result | Write-Output
foreach ($entry in @(@(0,0), @(1,0), @(2,0), @(2,1), @(2,2))) {
    $fileName = "latency-policy-$($entry[0])-format-$($entry[1]).csv"
    $csv = & $adbPath -s $Serial shell run-as $packageName cat "files/$fileName" 2>&1
    if ($LASTEXITCODE -eq 0) { $csv | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory $fileName) }
}
& $adbPath -s $Serial shell dumpsys media.audio_flinger | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'audio-flinger-after.txt')
& $adbPath -s $Serial shell dumpsys media.audio_policy | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'audio-policy-after.txt')
& $adbPath -s $Serial logcat -d -b crash | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'crashes.txt')
& $adbPath -s $Serial logcat -d -s Monitor AAudio UnifiedTest | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'audio-logcat.txt')
& $adbPath -s $Serial shell am start -n "$packageName/.MainActivity"
if (($result -join "`n") -notmatch '=== SUMMARY: pass=\d+ fail=0') {
    throw "One or more scenarios failed; evidence preserved in $OutputDirectory"
}
Write-Output "Evidence: $OutputDirectory"
