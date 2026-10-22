param([int]$Port = 18787)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$lib = Join-Path $projectRoot 'apps/relay/build/install/relayHeadless/lib'
if (-not (Test-Path -LiteralPath $lib)) { throw 'First run ./gradlew :relayHeadless:installDist' }
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $java
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.ArgumentList.Add('-cp')
$start.ArgumentList.Add((Join-Path $lib '*'))
$start.ArgumentList.Add('dev.agentbridge.relay.MainKt')
$start.ArgumentList.Add([string]$Port)
$child = [Diagnostics.Process]::Start($start)
$stdout = $child.StandardOutput.ReadToEndAsync()
$stderr = $child.StandardError.ReadToEndAsync()
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($child.HasExited) { throw "Relay exited with code $($child.ExitCode)" }
        try {
            $health = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1
            if ($health.StatusCode -eq 200 -and $health.Content -eq 'ok') { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ready) { throw 'Relay did not become healthy' }
    $info = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/.well-known/agent-bridge" -TimeoutSec 5
    if ($info.stage -ne 'M0' -or $info.enrollmentEnabled -or $info.encryptedTransportReady -or $info.protocolVersions[0] -ne 1) {
        throw 'Unexpected Relay discovery contract'
    }
    $events = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/v1/events" -SkipHttpErrorCheck -TimeoutSec 5
    if ($events.StatusCode -ne 503) { throw 'Event transport must stay disabled in M0' }
    Write-Output 'PASS: packaged Relay process, health, discovery, and disabled events'
} finally {
    if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit(5000) | Out-Null }
    $child.Dispose()
}
