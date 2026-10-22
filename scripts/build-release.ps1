[CmdletBinding()]
param([switch]$SkipLinux)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectRoot 'gradlew.bat'
$dist = Join-Path $projectRoot 'dist'

& $gradle :desktopApp:packageExe :desktopApp:createDistributable :androidApp:assembleDebug `
    :relayHeadless:installDist :hostApp:installDist
if ($LASTEXITCODE -ne 0) { throw "Windows/Android build failed with exit code $LASTEXITCODE" }

if (-not $SkipLinux) {
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if ($null -eq $wsl) { throw 'WSL Ubuntu is required for a local Linux DEB build. Use -SkipLinux or CI.' }
    $windowsSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    function ConvertTo-WslPath([string]$windowsPath) {
        $resolved = [IO.Path]::GetFullPath($windowsPath)
        $root = [IO.Path]::GetPathRoot($resolved)
        if ($root.Length -lt 2 -or $root[1] -ne ':') { throw "Expected a drive-qualified Windows path: $resolved" }
        $drive = $root.Substring(0, 1).ToLowerInvariant()
        $relative = $resolved.Substring($root.Length).Replace('\', '/')
        return "/mnt/$drive/$relative"
    }
    $linuxProjectRoot = ConvertTo-WslPath $projectRoot
    $linuxAndroidHome = ConvertTo-WslPath $windowsSdk
    if ($linuxProjectRoot.Contains("'") -or $linuxAndroidHome.Contains("'")) {
        throw 'Release build paths cannot contain a single quote.'
    }
    $linuxBuild = "cd '$linuxProjectRoot' && export GRADLE_USER_HOME=/tmp/agentbridge-gradle-home " +
        "&& export ANDROID_HOME='$linuxAndroidHome' && bash ./gradlew :desktopApp:packageDeb " +
        "-PagentBridgeBuildRoot=.dev/build-linux --project-cache-dir .dev/project-cache-linux"
    & $wsl.Source -d Ubuntu -- bash -lc $linuxBuild
    if ($LASTEXITCODE -ne 0) { throw "Linux DEB build failed with exit code $LASTEXITCODE" }
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\desktop\build\compose\binaries\main\exe\AgentBridge-0.1.0.exe') `
    -Destination (Join-Path $dist 'AgentBridge-Windows-x64-0.1.0.exe') -Force
$portableDirectory = Join-Path $projectRoot 'apps\desktop\build\compose\binaries\main\app\AgentBridge'
Compress-Archive -LiteralPath $portableDirectory `
    -DestinationPath (Join-Path $dist 'AgentBridge-Windows-portable-x64-0.1.0.zip') -CompressionLevel Optimal -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\android\build\outputs\apk\debug\androidApp-debug.apk') `
    -Destination (Join-Path $dist 'AgentBridge-Android-debug-0.1.0.apk') -Force
$bundleStage = Join-Path $projectRoot '.dev\android-host-windows'
$allowedStageRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.dev'))
$resolvedStage = [IO.Path]::GetFullPath($bundleStage)
if (-not $resolvedStage.StartsWith($allowedStageRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "Unsafe bundle staging path: $resolvedStage"
}
if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
New-Item -ItemType Directory -Path $resolvedStage | Out-Null
Copy-Item -LiteralPath (Join-Path $portableDirectory 'runtime') -Destination $resolvedStage -Recurse
$javaLauncherPath = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    (Get-Command java.exe -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $javaLauncherPath -PathType Leaf)) {
    throw "The Gradle Java launcher was not found: $javaLauncherPath"
}
Copy-Item -LiteralPath $javaLauncherPath `
    -Destination (Join-Path $resolvedStage 'runtime\bin\java.exe') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\relay\build\install\relayHeadless') `
    -Destination (Join-Path $resolvedStage 'relay') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\host\build\install\hostApp') `
    -Destination (Join-Path $resolvedStage 'host') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\android\build\outputs\apk\debug\androidApp-debug.apk') `
    -Destination (Join-Path $resolvedStage 'AgentBridge.apk')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\android-host-windows\start-android.ps1') `
    -Destination $resolvedStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\android-host-windows\start-android.cmd') `
    -Destination $resolvedStage
Compress-Archive -LiteralPath $resolvedStage `
    -DestinationPath (Join-Path $dist 'AgentBridge-Android-Host-Windows-x64-0.1.0.zip') `
    -CompressionLevel Optimal -Force

$vpsClientStage = Join-Path $projectRoot '.dev\vps-client-windows'
$resolvedVpsClientStage = [IO.Path]::GetFullPath($vpsClientStage)
if (-not $resolvedVpsClientStage.StartsWith($allowedStageRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "Unsafe VPS client staging path: $resolvedVpsClientStage"
}
if (Test-Path -LiteralPath $resolvedVpsClientStage) { Remove-Item -LiteralPath $resolvedVpsClientStage -Recurse -Force }
New-Item -ItemType Directory -Path $resolvedVpsClientStage | Out-Null
Copy-Item -LiteralPath $portableDirectory -Destination (Join-Path $resolvedVpsClientStage 'desktop') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\host\build\install\hostApp') `
    -Destination (Join-Path $resolvedVpsClientStage 'host') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\vps-windows\connect-vps.ps1') -Destination $resolvedVpsClientStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\vps-windows\connect-vps.cmd') -Destination $resolvedVpsClientStage
Compress-Archive -LiteralPath $resolvedVpsClientStage `
    -DestinationPath (Join-Path $dist 'AgentBridge-VPS-Client-Windows-x64-0.1.0.zip') `
    -CompressionLevel Optimal -Force

$relayVpsStage = Join-Path $projectRoot '.dev\relay-vps'
$resolvedRelayVpsStage = [IO.Path]::GetFullPath($relayVpsStage)
if (-not $resolvedRelayVpsStage.StartsWith($allowedStageRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "Unsafe Relay staging path: $resolvedRelayVpsStage"
}
if (Test-Path -LiteralPath $resolvedRelayVpsStage) { Remove-Item -LiteralPath $resolvedRelayVpsStage -Recurse -Force }
New-Item -ItemType Directory -Path $resolvedRelayVpsStage | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\relay\build\install\relayHeadless') `
    -Destination (Join-Path $resolvedRelayVpsStage 'relay') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps\host\build\install\hostApp') `
    -Destination (Join-Path $resolvedRelayVpsStage 'host') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'deployment\vps\agent-bridge-relay.service') -Destination $resolvedRelayVpsStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'deployment\vps\Caddyfile.example') -Destination $resolvedRelayVpsStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'deployment\vps\Caddyfile.restorkava.example') -Destination $resolvedRelayVpsStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'deployment\vps\install-relay.sh') -Destination $resolvedRelayVpsStage
Copy-Item -LiteralPath (Join-Path $projectRoot 'deployment\vps\install-from-git.sh') -Destination $resolvedRelayVpsStage
Compress-Archive -LiteralPath $resolvedRelayVpsStage `
    -DestinationPath (Join-Path $dist 'AgentBridge-Relay-JVM-0.1.0.zip') `
    -CompressionLevel Optimal -Force
if (-not $SkipLinux) {
    Copy-Item -LiteralPath (Join-Path $projectRoot '.dev\build-linux\desktopApp\compose\binaries\main\deb\agentbridge_0.1.0-1_amd64.deb') `
        -Destination (Join-Path $dist 'AgentBridge-Linux-amd64-0.1.0.deb') -Force
}

$checksums = Get-ChildItem -LiteralPath $dist -File |
    Where-Object Name -ne 'SHA256SUMS.txt' |
    Sort-Object Name |
    ForEach-Object {
        $sha256 = [Security.Cryptography.SHA256]::Create()
        $stream = [IO.File]::OpenRead($_.FullName)
        try {
            $hash = ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join ''
        } finally {
            $stream.Dispose()
            $sha256.Dispose()
        }
        "$hash  $($_.Name)"
    }
[IO.File]::WriteAllLines(
    (Join-Path $dist 'SHA256SUMS.txt'),
    [string[]]$checksums,
    [Text.UTF8Encoding]::new($false)
)
Get-ChildItem -LiteralPath $dist | Select-Object Name, Length
