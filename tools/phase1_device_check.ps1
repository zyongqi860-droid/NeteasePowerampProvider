# Phase 1 real-device verification helper (PROJECT_STATUS.md section 5).
# Automates everything ADB can do: install, launch, SAF queries against the
# provider, openDocument stream read, and logcat event verification.
# The Poweramp-side manual checks are printed at the end.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\phase1_device_check.ps1
#
# Optional parameters:
#   -ApkPath  path to the debug APK (default: app\build\outputs\apk\debug\app-debug.apk)
#   -Adb      path to adb.exe      (default: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe)

param(
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'

$Package     = 'com.yczz.neteasepoweramp'
$Authority   = 'com.yczz.neteasepoweramp.provider'
$LogTag      = 'NeteasePowerampProvider'
$RootsUri    = "content://$Authority/root"
$FolderUri   = "content://$Authority/tree/root%2Ftest_folder/children"
$TrackOpenUri = "content://$Authority/tree/root/document/root%2Ftest_folder%2Ftrack_test_001"

$script:pass = 0
$script:fail = 0

function Step($name, [scriptblock]$body) {
    # Steps are sequential and dependent (install needs a device, queries need an install...),
    # so the first failure aborts the whole run instead of hanging on later ADB calls.
    Write-Host ""
    Write-Host "=== [$name] ===" -ForegroundColor Cyan
    try {
        & $body
        $script:pass++
        Write-Host "[PASS] $name" -ForegroundColor Green
    } catch {
        $script:fail++
        Write-Host "[FAIL] $name : $_" -ForegroundColor Red
        exit 1
    }
}

function RequireFile($path, $label) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "$label not found: $path"
    }
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

Step 'clear logcat' {
    & $Adb logcat -c
}

Step 'launch MainActivity' {
    & $Adb shell am start -n "$Package/com.yczz.neteasepoweramp.MainActivity"
    Start-Sleep -Seconds 2
    & $Adb shell pidof $Package | ForEach-Object { Write-Host "process pid=$_" }
    if (-not (& $Adb shell pidof $Package)) { throw "app process not running after launch" }
}

Step 'queryRoots via SAF' {
    # Routed by DocumentsProvider to queryRoots(); expect root id + display names.
    $out = & $Adb shell content query --uri $RootsUri
    Write-Host $out
    if ($out -notmatch 'netease_root') { throw 'queryRoots did not return root_id=netease_root' }
}

Step 'queryChildDocuments(root)' {
    # Expect the Test Music folder row.
    $out = & $Adb shell content query --uri $FolderUri.Replace('root%2Ftest_folder', 'root')
    Write-Host $out
    if ($out -notmatch 'test_folder') { throw 'root children did not contain test_folder' }
}

Step 'queryChildDocuments(test_folder)' {
    # Expect the single test track row with metadata columns.
    $out = & $Adb shell content query --uri $FolderUri
    Write-Host $out
    if ($out -notmatch 'track_test_001') { throw 'folder children did not contain track_test_001' }
    if ($out -notmatch 'audio/mpeg') { throw 'track row missing audio/mpeg mime type' }
}

Step 'openDocument stream read' {
    # Routed to openDocument(track_test_001); writes the mp3 bytes to stdout.
    $tmp = Join-Path $env:TEMP 'npe_track_read.bin'
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    # Redirect via cmd so binary output is not re-encoded by PowerShell.
    cmd /c "`"$Adb`" shell content read --uri $TrackOpenUri > `"$tmp`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "content read failed (exit $LASTEXITCODE)" }
    $size = (Get-Item -LiteralPath $tmp).Length
    Write-Host "streamed bytes: $size"
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    if ($size -lt 100000) { throw "expected >100KB mp3 payload, got $size bytes" }
}

Step 'logcat provider events' {
    Start-Sleep -Seconds 1
    $log = & $Adb logcat -d -s $LogTag
    $log | Select-String -SimpleMatch $LogTag | ForEach-Object { Write-Host $_.Line }
    foreach ($event in @('Provider initialized', 'queryRoots', 'queryDocument', 'queryChildDocuments', 'openDocument', 'test audio opened')) {
        if (-not ($log | Select-String -SimpleMatch $event)) { throw "missing logcat event: $event" }
    }
}

Write-Host ""
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "Automated checks: $($script:pass) passed, $($script:fail) failed" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })

if ($script:fail -gt 0) {
    Write-Host "Fix failures above before running Poweramp-side manual tests." -ForegroundColor Yellow
    exit 1
}

# Remaining items require touching the device with Poweramp installed (task doc sections 25/26).
Write-Host ""
Write-Host "Remaining MANUAL checks (Poweramp must be installed):" -ForegroundColor Yellow
Write-Host "  1. Poweramp > Settings > Library > Music Folders: add this provider;"
Write-Host "     confirm hierarchy Netease Poweramp -> Test Music -> Poweramp Provider Test."
Write-Host "  2. Play the track: pause/resume, next/prev, no crash; leave and re-enter Poweramp."
Write-Host "  3. Seek test: drag progress 0:10 -> 0:30, waveform seekbar usable."
Write-Host "  4. Play twice in a row; rapid play/pause taps."
Write-Host "  5. Reboot phone: provider still listed and playable."
Write-Host "  6. Force-stop this app (Settings > Apps): Poweramp reports an error instead of crashing."
Write-Host "  7. Tick section 27 completion checklist in PROJECT_STATUS.md once all pass."
exit 0
