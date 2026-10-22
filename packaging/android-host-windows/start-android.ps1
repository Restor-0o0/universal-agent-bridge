[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$bundleRoot = $PSScriptRoot
$env:JAVA_HOME = Join-Path $bundleRoot 'runtime'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$tokenBytes = New-Object byte[] 32
$rawKey = New-Object byte[] 32
$random.GetBytes($tokenBytes)
$random.GetBytes($rawKey)
$random.Dispose()
$env:AGENT_BRIDGE_DEV_TOKEN = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })
$env:AGENT_BRIDGE_DEV_KEY = [Convert]::ToBase64String($rawKey).TrimEnd('=').Replace('+', '-').Replace('/', '_')

function Stop-ProcessTree {
    param([int]$RootProcessId)
    Get-CimInstance Win32_Process -Filter "ParentProcessId = $RootProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree -RootProcessId $_.ProcessId }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if ($null -eq $adb) {
    throw 'adb.exe not found. Install Android SDK Platform Tools and add it to PATH.'
}
$devices = @(
    & $adb.Source devices |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { ([regex]::Match($_, '^(\S+)')).Groups[1].Value }
)
if ($devices.Count -eq 0) {
    throw 'No authorized Android device found. Connect one device, enable USB debugging and accept its authorization prompt.'
}
if ($devices.Count -gt 1) {
    throw 'More than one Android device is connected. Leave exactly one device or emulator connected.'
}
$deviceSerial = $devices[0]

$relay = $null
$hostProcess = $null
try {
    $relay = Start-Process -FilePath (Join-Path $bundleRoot 'relay\bin\relayHeadless.bat') `
        -ArgumentList '--dev' -WorkingDirectory $bundleRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $bundleRoot 'relay.log') `
        -RedirectStandardError (Join-Path $bundleRoot 'relay-error.log') -PassThru
    $ready = $false
    foreach ($attempt in 1..30) {
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8787/health' -TimeoutSec 1
            if ($health.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ready) { throw 'Relay did not start. See relay-error.log.' }

    $hostProcess = Start-Process -FilePath (Join-Path $bundleRoot 'host\bin\hostApp.bat') `
        -ArgumentList '--connect-relay-dev', 'http://127.0.0.1:8787' `
        -WorkingDirectory $bundleRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $bundleRoot 'host.log') `
        -RedirectStandardError (Join-Path $bundleRoot 'host-error.log') -PassThru

    & $adb.Source -s $deviceSerial install -r (Join-Path $bundleRoot 'AgentBridge.apk')
    if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE" }
    & $adb.Source -s $deviceSerial reverse tcp:8787 tcp:8787
    if ($LASTEXITCODE -ne 0) { throw "adb reverse failed with exit code $LASTEXITCODE" }
    & $adb.Source -s $deviceSerial shell am start -n 'dev.agentbridge.android/.MainActivity' `
        --es agent_bridge_url 'http://127.0.0.1:8787' `
        --es agent_bridge_token $env:AGENT_BRIDGE_DEV_TOKEN `
        --es agent_bridge_key $env:AGENT_BRIDGE_DEV_KEY | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Android launch failed with exit code $LASTEXITCODE" }

    Write-Host "Android Agent Bridge is connected to $deviceSerial. Press Enter to stop Host and Relay." -ForegroundColor Green
    Read-Host
} finally {
    if ($null -ne $hostProcess -and -not $hostProcess.HasExited) { Stop-ProcessTree $hostProcess.Id }
    if ($null -ne $relay -and -not $relay.HasExited) { Stop-ProcessTree $relay.Id }
}
