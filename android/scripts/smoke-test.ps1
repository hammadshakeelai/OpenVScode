[CmdletBinding()]
param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'emulator-5554',
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Package = 'com.openvscode.mobile.debug',
    [string]$OutputDirectory = "$PSScriptRoot\..\app\build\reports\device-smoke",
    [switch]$AllowPhysicalDevice
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Adb)) { throw "adb was not found: $Adb" }
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "Build the APK first: $ApkPath" }
if (-not $Serial.StartsWith('emulator-') -and -not $AllowPhysicalDevice) {
    throw 'Physical device testing requires -AllowPhysicalDevice and an explicit -Serial.'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $Arguments failed: $result" }
    return ($result -join "`n")
}

function Save-DeviceState {
    param([string]$Name)
    $deviceXml = "/sdcard/openvscode-smoke-$Name.xml"
    $devicePng = "/sdcard/openvscode-smoke-$Name.png"
    Invoke-Adb @('shell', 'uiautomator', 'dump', $deviceXml) | Out-Null
    Invoke-Adb @('pull', $deviceXml, (Join-Path $OutputDirectory "$Name.xml")) | Out-Null
    Invoke-Adb @('shell', 'screencap', '-p', $devicePng) | Out-Null
    Invoke-Adb @('pull', $devicePng, (Join-Path $OutputDirectory "$Name.png")) | Out-Null
    [xml]$snapshot = Get-Content -LiteralPath (Join-Path $OutputDirectory "$Name.xml") -Raw
    if (-not $snapshot.SelectSingleNode("//node[@package='$Package']")) {
        throw "App was not visible during '$Name'. Inspect $OutputDirectory."
    }
}

$booted = Invoke-Adb @('shell', 'getprop', 'sys.boot_completed')
if ($booted.Trim() -ne '1') { throw 'The selected Android device has not finished booting.' }
$deviceInfo = [ordered]@{
    serial = $Serial
    android = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')).Trim()
    api = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    abi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
    apk = (Resolve-Path -LiteralPath $ApkPath).Path
    apkSha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    startedAt = [DateTime]::UtcNow.ToString('o')
}

# This installs only the named test APK. Termux setup, data, and permissions
# remain user controlled; actual provisioning is a separate integration test.
Invoke-Adb @('install', '-r', $ApkPath) | Write-Output
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
$start = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/com.openvscode.mobile.MainActivity")
if ($start -match 'Error:|Exception') { throw $start }
Start-Sleep -Seconds 2
Save-DeviceState 'launch'

Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Seconds 1
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/com.openvscode.mobile.MainActivity") | Out-Null
Start-Sleep -Seconds 1
Save-DeviceState 'resume'

$appPid = (Invoke-Adb @('shell', 'pidof', $Package)).Trim()
if (-not $appPid) { throw 'App process did not survive launch and background/resume.' }
$appLog = Invoke-Adb @('logcat', '-d', '-v', 'threadtime', "--pid=$appPid", '-t', '500')
Set-Content -LiteralPath (Join-Path $OutputDirectory 'app-logcat.txt') -Value $appLog -Encoding utf8
if ($appLog -match 'FATAL EXCEPTION|Unable to start activity|ForegroundServiceDidNotStartInTimeException') {
    throw "App reported a fatal error. Inspect $OutputDirectory\app-logcat.txt."
}
$deviceInfo.completedAt = [DateTime]::UtcNow.ToString('o')
$deviceInfo.result = 'PASS: APK install, visible launch, Home/resume, process survival, no fatal app log'
$deviceInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8
Write-Output $deviceInfo.result
Write-Output "Screenshots, UI trees, logcat and APK hash: $OutputDirectory"
