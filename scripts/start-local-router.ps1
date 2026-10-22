[CmdletBinding()]
param(
    [switch]$Android,
    [switch]$NoDesktop,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectRoot 'gradlew.bat'
$devDirectory = Join-Path $projectRoot '.dev'
$relayLog = Join-Path $devDirectory 'relay.log'
$relayErrorLog = Join-Path $devDirectory 'relay-error.log'
$hostLog = Join-Path $devDirectory 'host.log'
$hostErrorLog = Join-Path $devDirectory 'host-error.log'

New-Item -ItemType Directory -Force -Path $devDirectory | Out-Null

if (-not $SkipBuild) {
    & $gradle :relayHeadless:installDist :hostApp:installDist :desktopApp:classes :androidApp:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
}

$env:AGENT_BRIDGE_DEV_TOKEN = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
).ToLowerInvariant()
$rawKey = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$env:AGENT_BRIDGE_DEV_KEY = [Convert]::ToBase64String($rawKey).TrimEnd('=').Replace('+', '-').Replace('/', '_')

$relayExecutable = Join-Path $projectRoot 'apps\relay\build\install\relayHeadless\bin\relayHeadless.bat'
$hostExecutable = Join-Path $projectRoot 'apps\host\build\install\hostApp\bin\hostApp.bat'
$relayProcess = $null
$hostProcess = $null

function Stop-ProcessTree {
    param([int]$RootProcessId)
    Get-CimInstance Win32_Process -Filter "ParentProcessId = $RootProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree -RootProcessId $_.ProcessId }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

try {
    $relayProcess = Start-Process -FilePath $relayExecutable -ArgumentList '--dev' -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -RedirectStandardOutput $relayLog -RedirectStandardError $relayErrorLog -PassThru

    $relayReady = $false
    foreach ($attempt in 1..30) {
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8787/health' -TimeoutSec 1
            if ($health.StatusCode -eq 200) { $relayReady = $true; break }
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    if (-not $relayReady) { throw "Relay did not start. See $relayErrorLog" }

    $hostProcess = Start-Process -FilePath $hostExecutable `
        -ArgumentList '--connect-relay-dev', 'http://127.0.0.1:8787' -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -RedirectStandardOutput $hostLog -RedirectStandardError $hostErrorLog -PassThru

    Write-Host ''
    Write-Host 'Unified local router is running at http://127.0.0.1:8787' -ForegroundColor Green
    Write-Host "Dev token: $env:AGENT_BRIDGE_DEV_TOKEN"
    Write-Host "Dev key:   $env:AGENT_BRIDGE_DEV_KEY"
    Write-Host "Logs:      $devDirectory"

    if ($Android) {
        $adb = Get-Command adb -ErrorAction SilentlyContinue
        if ($null -eq $adb) {
            Write-Warning 'adb is not in PATH. Run: adb reverse tcp:8787 tcp:8787'
        } else {
            $apk = Join-Path $projectRoot 'apps\android\build\outputs\apk\debug\androidApp-debug.apk'
            & $adb.Source install -r $apk
            if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE" }
            & $adb.Source reverse tcp:8787 tcp:8787
            if ($LASTEXITCODE -ne 0) { throw "adb reverse failed with exit code $LASTEXITCODE" }
            & $adb.Source shell am start -n 'dev.agentbridge.android/.MainActivity' `
                --es agent_bridge_url 'http://127.0.0.1:8787' `
                --es agent_bridge_token $env:AGENT_BRIDGE_DEV_TOKEN `
                --es agent_bridge_key $env:AGENT_BRIDGE_DEV_KEY | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Android app launch failed with exit code $LASTEXITCODE" }
            Write-Host 'Android APK installed, local route configured, and the bridge connected automatically.'
        }
    }

    if ($NoDesktop) {
        Read-Host 'Press Enter to stop Relay and Host'
    } else {
        & $gradle :desktopApp:run '--args=--dev'
        if ($LASTEXITCODE -ne 0) { throw "Desktop app failed with exit code $LASTEXITCODE" }
    }
} finally {
    if ($null -ne $hostProcess -and -not $hostProcess.HasExited) {
        Stop-ProcessTree -RootProcessId $hostProcess.Id
    }
    if ($null -ne $relayProcess -and -not $relayProcess.HasExited) {
        Stop-ProcessTree -RootProcessId $relayProcess.Id
    }
}
