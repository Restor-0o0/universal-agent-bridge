[CmdletBinding()]
param(
    [switch]$Reset,
    [switch]$ClientOnly
)

$ErrorActionPreference = 'Stop'
$bundleRoot = $PSScriptRoot
$configDirectory = Join-Path $env:APPDATA 'AgentBridge'
$configPath = Join-Path $configDirectory 'vps-connection.conf'

if ($Reset -and (Test-Path -LiteralPath $configPath)) {
    Remove-Item -LiteralPath $configPath -Force
}

function Read-SecretConfig {
    if (Test-Path -LiteralPath $configPath) {
        $values = @{}
        Get-Content -LiteralPath $configPath | ForEach-Object {
            $separator = $_.IndexOf('=')
            if ($separator -gt 0) { $values[$_.Substring(0, $separator)] = $_.Substring($separator + 1) }
        }
        return $values
    }

    Write-Host 'First connection to your Agent Bridge VPS.' -ForegroundColor Cyan
    $values = @{
        URL = (Read-Host 'Relay HTTPS URL, for example https://agents.example.com').TrimEnd('/')
        WORKSPACE = (Read-Host 'Workspace name, for example alex').Trim()
        PASSWORD = (Read-Host 'Router password').Trim()
        KEY = (Read-Host 'Encryption key').Trim()
        HOST_ID = ((Read-Host "Computer ID [$env:COMPUTERNAME]").Trim())
    }
    if ([string]::IsNullOrWhiteSpace($values.HOST_ID)) { $values.HOST_ID = $env:COMPUTERNAME }
    if ($values.URL -notmatch '^https://[^/]+$') { throw 'Enter a root HTTPS URL without a path.' }
    if ($values.WORKSPACE -notmatch '^[A-Za-z0-9._-]{1,64}$') { throw 'Workspace contains invalid characters.' }
    if ($values.HOST_ID -notmatch '^[A-Za-z0-9._-]{1,64}$') { throw 'Computer ID contains invalid characters.' }
    if ($values.PASSWORD.Length -lt 12) { throw 'Router password must contain at least 12 characters.' }
    try { $decoded = [Convert]::FromBase64String(($values.KEY.Replace('-', '+').Replace('_', '/') + '===').Substring(0, [Math]::Ceiling($values.KEY.Length / 4.0) * 4)) } catch { throw 'Encryption key must be base64url.' }
    if ($decoded.Length -ne 32) { throw 'Encryption key must decode to exactly 32 bytes.' }
    New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
    [IO.File]::WriteAllLines(
        $configPath,
        [string[]]@(
            "URL=$($values.URL)",
            "WORKSPACE=$($values.WORKSPACE)",
            "PASSWORD=$($values.PASSWORD)",
            "KEY=$($values.KEY)",
            "HOST_ID=$($values.HOST_ID)"
        ),
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host "Settings saved to: $configPath" -ForegroundColor DarkGray
    return $values
}

$config = Read-SecretConfig
$workspace = if ($config.WORKSPACE) { $config.WORKSPACE } else { 'default' }
$password = if ($config.PASSWORD) { $config.PASSWORD } else { $config.TOKEN }
$hostId = if ($config.HOST_ID) { $config.HOST_ID } else { $env:COMPUTERNAME }
$env:AGENT_BRIDGE_RELAY_URL = $config.URL
$env:AGENT_BRIDGE_DEV_TOKEN = $password
$env:AGENT_BRIDGE_DEV_KEY = $config.KEY
$env:AGENT_BRIDGE_WORKSPACE = $workspace
$env:AGENT_BRIDGE_HOST_ID = $hostId
$env:JAVA_HOME = Join-Path $bundleRoot 'desktop\runtime'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$hostLog = Join-Path $configDirectory 'vps-host.log'
$hostErrorLog = Join-Path $configDirectory 'vps-host-error.log'
$hostProcess = $null

function Stop-ProcessTree {
    param([int]$RootProcessId)
    Get-CimInstance Win32_Process -Filter "ParentProcessId = $RootProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree -RootProcessId $_.ProcessId }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

try {
    if (-not $ClientOnly) {
        $hostProcess = Start-Process -FilePath (Join-Path $bundleRoot 'host\bin\hostApp.bat') `
            -ArgumentList '--connect-relay-dev', $config.URL -WorkingDirectory $bundleRoot `
            -WindowStyle Hidden -RedirectStandardOutput $hostLog -RedirectStandardError $hostErrorLog -PassThru
        Start-Sleep -Seconds 2
        if ($hostProcess.HasExited) { throw "Host failed to connect. Read: $hostErrorLog" }
        Write-Host "Host connected to $($config.URL)" -ForegroundColor Green
    } else {
        Write-Host 'Client-only mode: using the Host already connected to the VPS.' -ForegroundColor Green
    }
    Write-Host 'Phone connection code:' -ForegroundColor Cyan
    Write-Host "$($config.URL)|$workspace|$password|$($config.KEY)|$hostId"
    Write-Host 'Use -Reset to change the VPS or secrets; use -ClientOnly when Host runs on the VPS.' -ForegroundColor DarkGray

    $desktop = Start-Process -FilePath (Join-Path $bundleRoot 'desktop\AgentBridge.exe') `
        -ArgumentList '--dev' -WorkingDirectory $bundleRoot -PassThru
    $desktop.WaitForExit()
} finally {
    if ($null -ne $hostProcess -and -not $hostProcess.HasExited) {
        Stop-ProcessTree -RootProcessId $hostProcess.Id
    }
}
