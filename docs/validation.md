# Validation — 2026-10-19

Environment: Windows, Oracle JDK 17.0.12, Gradle 9.5.1, Android SDK 37.0,
Codex CLI 0.163.0; Linux package build/smoke in WSL Ubuntu 24.04 OpenJDK 17;
physical Realme RMX3760 running Android 15 over USB. No separate Linux host was used.

## Actual results

| Check | Result |
|---|---|
| Gradle wrapper generation | PASS; official distribution SHA-256 pinned |
| protocol JVM tests | 4 passed |
| domain JVM tests | 3 passed |
| client-core JVM tests | 5 passed |
| host-engine tests | 14 passed, including encrypted vertical slice, help signal, Host directory picker and independent concurrent sessions |
| relay-engine integration/config tests | 4 passed |
| codex-adapter fixture/provider/lifecycle tests | 12 passed |
| claude-adapter protocol/provider/lifecycle tests | 8 passed |
| opencode-adapter provider/lifecycle/approval tests | 7 passed |
| acp-client protocol/session/event tests | 8 passed |
| crypto JVM tests | 2 passed |
| Total automated tests | **67 passed, 0 failures, 0 errors** |
| crypto JVM + Android compilation | PASS |
| agent-spi Android compilation | PASS |
| desktopApp classes | PASS |
| relayHeadless / hostApp installDist | PASS |
| Android assembleDebug | PASS |
| Android lintDebug | PASS; non-blocking version/target API warnings remain |
| Packaged Relay process smoke | PASS: health, discovery, disabled event transport |
| Real Codex app-server handshake | PASS: initialize / initialized over stdio |
| Automatic harness discovery | PASS: Codex 0.163.0, Claude Code 2.1.302, OpenCode 1.18.26 |
| Real OpenCode ACP handshake | PASS: protocol negotiation selected ACP v1 over stdio |
| Real Claude control handshake | PASS: initialize control request/response over JSONL, no inference |
| Local encrypted vertical integration | PASS: Desktop client → Relay → Host → fake managed adapter → streamed result |
| Packaged encrypted Host/Relay runtime smoke | PASS: separate distributions connected over loopback WebSocket with ephemeral env key/token |
| Windows EXE package | PASS: 71 MB installer; bundled runtime started embedded Relay and returned health=ok |
| Windows portable package | PASS: 70 MB ZIP with bundled runtime |
| Linux amd64 DEB | PASS: 65 MB; dpkg metadata inspected and extracted package returned health=ok in WSL Ubuntu 24.04 |
| Android distributable | PASS: 47 MB debug-signed APK |
| Android + Windows Host bundle | PASS: 57 MB ZIP contains private Java runtime, APK, Relay, Host and launcher; packaged Relay health=ok and packaged Host remained connected |
| Physical Android install/start | PASS: debug APK installed over adb and cold launcher activity started |
| Multi-session Android UI | PASS: connected UI displayed `Сессии · 0/10`, created an OpenCode tab, and routed its ready activity independently |
| Real managed turns | PASS: Codex created a disposable hello-world file after phone approval; Claude returned `whoami`; OpenCode created a disposable desktop screenshot and completed the turn |

Commands used (PowerShell, JDK 17):

```powershell
.\gradlew.bat test :protocol:allTests :crypto:allTests :client-core:allTests :ui:allTests :desktopApp:classes :androidApp:assembleDebug :androidApp:lintDebug :hostApp:installDist :relayHeadless:installDist
.\gradlew.bat :crypto:jvmJar :crypto:compileAndroidMain :agent-spi:compileAndroidMain :desktopApp:classes :relayHeadless:installDist :hostApp:installDist
.\gradlew.bat :androidApp:assembleDebug :androidApp:lintDebug
.\scripts\smoke-relay.ps1
.\apps\host\build\install\hostApp\bin\hostApp.bat --probe-codex C:\Users\komar\AppData\Local\Programs\OpenAI\Codex\bin\codex.exe
.\apps\host\build\install\hostApp\bin\hostApp.bat --list-agents
.\apps\host\build\install\hostApp\bin\hostApp.bat --probe-opencode-acp
```

The initial build identified Compose's compileSdk >=37 requirement and Android
lint found an unescaped drive separator in local.properties. Both were fixed.
APK and lint were rerun successfully after the final manifest/resource changes.
The SDK XML-version warning is local tooling metadata; the build succeeds.
Compose Gradle dependency-accessor deprecation warnings remain.

Artifacts:
- apps/android/build/outputs/apk/debug/androidApp-debug.apk
- apps/android/build/reports/lint-results-debug.html
- shared/{protocol,domain,client-core}/build/reports/tests/jvmTest/index.html
- engines/{host,relay}/build/reports/tests/test/index.html
- adapters/codex/build/reports/tests/test/index.html
- apps/relay/build/install/relayHeadless/
- apps/host/build/install/hostApp/

Temporary raw build logs and exported Codex schemas are in ignored .dev/.
Smoke checks create hidden child processes and terminate them afterward. The
encrypted runtime smoke used port 8791 and fresh random environment-only
credentials. No service was installed and no public endpoint was opened.

## Not tested / not complete

- Windows service lifecycle and Android emulator. The physical Android app and
  Compose UI were exercised over USB. Linux DEB runtime was smoke-tested in WSL,
  but not on a separate Linux computer.
- TLS MITM rejection against a real server (URL policy tests are not TLS tests).
- Production pairing/E2EE, revocation, WSS reconnect, attachments, screen capture.
- Long-running reconnect behavior and delivery of a wake alarm after the phone
  has entered deep sleep.
- CI workflow has been written but not executed on a remote CI service.

M0 remains open for native Linux lifecycle evidence. A local, explicitly
development-only M1 vertical slice is implemented; public M1 security and
persistence work has not been completed.
