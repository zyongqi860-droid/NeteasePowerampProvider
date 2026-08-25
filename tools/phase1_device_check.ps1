# Real-device verification helper (phase 1 + phase 2).
#
# What this script automates:
#   1. device detection, APK install, app launch
#   2. on-device provider contract tests via `am instrument` (6 tests: roots, hierarchy,
#      saved-song metadata, unknown-document handling, openDocument fd for test track AND
#      netease song placeholder)
#
# Why not `adb shell content query`? DocumentsProvider access from shell (uid 2000) is denied
# by Android design (MANAGE_DOCUMENTS); the instrumented tests run as the app uid and are the
# correct automation path. Poweramp-side manual checks are printed at the end.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\phase1_device_check.ps1

param(
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$TestApkPath = "$PSScriptRoot\..\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$Package = 'com.yczz.neteasepoweramp'

function Step($name, [scriptblock]$body) {
    # Steps are sequential and dependent; first failure aborts the run.
    Write-Host ""
    Write-Host "=== [$name] ===" -ForegroundColor Cyan
    try {
        & $body
        Write-Host "[PASS] $name" -ForegroundColor Green
    } catch {
        Write-Host "[FAIL] $name : $_" -ForegroundColor Red
        exit 1
    }
}

function RequireFile($path, $label) {
    if (-not (Test-Path -LiteralPath $path)) { throw "$label not found: $path" }
}

RequireFile $Adb 'adb.exe'
RequireFile $ApkPath 'APK'

Step 'device connected' {
    $out = & $Adb devices
    $lines = @($out | Select-Object -Skip 1 | Where-Object { $_ -match '\t(device)$' })
    if ($lines.Count -eq 0) { throw "no ADB device attached (run: adb devices)" }
    Write-Host ($lines -join ', ')
}

Step 'install APK' {
    & $Adb install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}

Step 'launch MainActivity' {
    & $Adb logcat -c
    & $Adb shell am start -n "$Package/com.yczz.neteasepoweramp.MainActivity"
    Start-Sleep -Seconds 2
    if (-not (& $Adb shell pidof $Package)) { throw "app process not running after launch" }
}

if (Test-Path -LiteralPath $TestApkPath) {
    Step 'provider contract tests (instrument)' {
        # Enlarging the log buffer helps on devices (e.g. vivo) whose default buffer rotates
        # within seconds; provider logs use tag NeteasePowerampProvider.
        & $Adb logcat -G 4M
        & $Adb install -r $TestApkPath
        if ($LASTEXITCODE -ne 0) { throw "androidTest APK install failed" }
        # NOTE: plain `am instrument` (not the gradle task) so the app is NOT uninstalled
        # afterwards - user-saved songs in the Room db survive the run.
        $res = & $Adb shell am instrument -w -e class com.yczz.neteasepoweramp.provider.ProviderDeviceTest "$Package.test/androidx.test.runner.AndroidJUnitRunner"
        $res | ForEach-Object { Write-Host $_ }
        if (($res | Select-String -SimpleMatch 'FAILURES').Count -gt 0) { throw 'instrument tests failed' }
        if (-not ($res | Select-String -SimpleMatch 'OK (')).Count) { throw 'instrument run did not report OK' }
    }
} else {
    Write-Host "androidTest APK not found at $TestApkPath - run :app:assembleDebugAndroidTest first; skipping contract tests." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "Automated checks passed." -ForegroundColor Green
Write-Host ""
Write-Host "In-app quick check: tap '检查 PROVIDER 配置' -> expect Registered + Poweramp detected." -ForegroundColor Yellow
Write-Host "Phase-2 flow: search keyword (e.g. 周杰伦 / jay) -> tap a result row -> saved to provider." -ForegroundColor Yellow
Write-Host ""
Write-Host "Remaining MANUAL checks (Poweramp side):" -ForegroundColor Yellow
Write-Host "  1. Poweramp > Settings > Library > Music Folders: add this provider;"
Write-Host "     confirm 网易云音乐 -> 搜索结果 (saved songs) + Test Music -> Poweramp Provider Test."
Write-Host "  2. Play a song: phase 2 serves the test audio placeholder for netease songs;"
Write-Host "     pause/resume, next/prev, seek 0:10 -> 0:30 must not crash."
Write-Host "  3. Reboot phone: provider still listed; Room db persists (phase-2 test 5)."
Write-Host "  4. Force-stop this app: Poweramp reports an error instead of crashing."
Write-Host "  5. Tick section 27 completion checklist once all pass."
exit 0
