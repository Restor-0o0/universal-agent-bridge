# Test distributions

Local output is written to the ignored `dist/` directory by:

```powershell
.\scripts\build-release.ps1
```

Use `-SkipLinux` when WSL Ubuntu is unavailable. GitHub Actions builds the same
Windows packages, Android packages and Linux DEB on native runners and uploads
them as workflow artifacts.

## Windows x64

- `AgentBridge-Windows-x64-0.1.0.exe` is an installer with a private Java runtime.
- `AgentBridge-Windows-portable-x64-0.1.0.zip` is the same app without installation;
  extract the whole directory and start `AgentBridge.exe` inside it.
- The desktop app starts its loopback Relay and Host itself. Gradle and a separate
  Java installation are not required. Codex, Claude Code or OpenCode must already
  be installed for that Windows user.

The package is not code-signed yet, so Windows SmartScreen can identify it as an
unknown publisher. This is a test build, not a production release.

## Linux amd64

The DEB was built and smoke-tested on Ubuntu 24.04 x86_64. Install it with:

```bash
sudo apt install ./AgentBridge-Linux-amd64-0.1.0.deb
```

It includes its Java runtime and starts the same embedded loopback Relay and Host.
One of the supported agent CLIs must be installed for the current Linux user.
The package is not repository-signed yet.

## Android

`AgentBridge-Android-debug-0.1.0.apk` is a debug-signed test APK. Android may ask
the user to allow installation from the chosen file manager.

For the simplest end-to-end test from a Windows computer, use
`AgentBridge-Android-Host-Windows-x64-0.1.0.zip`:

1. Install Android SDK Platform Tools so that `adb.exe` is in `PATH`, then enable
   USB debugging on the phone and accept its authorization prompt. Keep exactly
   one phone or emulator connected.
2. Install and sign in to Codex, Claude Code or OpenCode on the computer.
3. Extract the whole ZIP, connect the phone and double-click `start-android.cmd`.

The launcher installs the included APK, starts the included Relay and Host with
its private Java runtime, configures `adb reverse`, and transfers temporary
credentials to the app. Gradle, project sources and manual URL/key entry are not
required. Keep its terminal open while using the app and press Enter to stop it.

The standalone APK can also connect to an explicitly started Host/Relay;
production pairing and public WSS are still pending. From the source repository,
the equivalent USB launcher is:

```powershell
.\scripts\start-local-router.ps1 -Android -NoDesktop
```

`SHA256SUMS.txt` contains hashes for checking copied files.

## VPS preview

- `AgentBridge-Relay-JVM-0.1.0.zip` contains the platform-neutral Relay,
  optional Linux Host, installer, systemd unit and Caddy templates. Java 17 is
  required on the VPS.
- `AgentBridge-VPS-Client-Windows-x64-0.1.0.zip` contains Desktop, Host, a private
  runtime and `connect-vps.cmd`. The launcher asks for URL/token/key once and then
  starts both Windows processes. Pass `-ClientOnly` when Host already runs on VPS.

See [VPS quickstart](vps-quickstart.md) for the complete setup.
See [connection methods](connections.md) for every supported topology.

Tags named `v*` publish the built artifacts as a GitHub Release. A VPS can keep a
sparse checkout of only `deployment/vps` and run `install-from-git.sh` to install
or update from the latest release without Gradle or Android SDK.
