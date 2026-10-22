package dev.agentbridge.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.agentbridge.claude.ClaudeHarnessProvider
import dev.agentbridge.client.RelayDiscovery
import dev.agentbridge.codex.CodexHarnessProvider
import dev.agentbridge.host.HarnessDiscovery
import dev.agentbridge.host.HarnessRegistry
import dev.agentbridge.opencode.OpenCodeHarnessProvider
import dev.agentbridge.relay.RelayEngine
import dev.agentbridge.ui.BridgeApp
import dev.agentbridge.ui.LocalHarnessView
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import javax.swing.JFileChooser
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    require(args.all { it in setOf("--local", "--dev", "--relay-headless") }) {
        "Options: --local | --dev | --relay-headless"
    }
    if ("--relay-headless" in args) {
        val engine = RelayEngine()
        Runtime.getRuntime().addShutdownHook(Thread { engine.close() })
        engine.start()
        java.util.concurrent.CountDownLatch(1).await()
        return
    }
    val local = args.isEmpty() || "--local" in args
    val dev = local || "--dev" in args
    val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
        install(WebSockets)
    }
    val discovery = RelayDiscovery(http, allowLoopbackHttp = dev)
    val localRuntime = if (local) runBlocking { LocalDesktopRuntime.start(http) } else null
    val remoteBridge = localRuntime?.remoteBridge ?: if (dev) {
        val token = System.getenv("AGENT_BRIDGE_DEV_TOKEN")
        val key = System.getenv("AGENT_BRIDGE_DEV_KEY")
        if (token != null && key != null) DesktopRemoteBridge(
            http,
            token,
            key,
            defaultWorkspaceId = System.getenv("AGENT_BRIDGE_WORKSPACE") ?: "default",
            defaultHostId = System.getenv("AGENT_BRIDGE_HOST_ID") ?: "host",
        ) else null
    } else null
    application {
        DisposableEffect(Unit) {
            onDispose {
                if (localRuntime != null) localRuntime.close() else remoteBridge?.close()
                http.close()
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "Agent Bridge", state = rememberWindowState(width = 920.dp, height = 800.dp)) {
            var harnesses by remember { mutableStateOf<List<LocalHarnessView>>(emptyList()) }
            var scanning by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                val registry = HarnessRegistry(
                    listOf(CodexHarnessProvider(), ClaudeHarnessProvider(), OpenCodeHarnessProvider()),
                )
                harnesses = registry.discover().map { result ->
                    when (result) {
                        is HarnessDiscovery.Available -> LocalHarnessView(
                            id = result.agentId,
                            name = result.displayName,
                            version = result.installation.version,
                            transport = result.installation.transport?.name,
                            available = true,
                        )
                        is HarnessDiscovery.Unavailable -> LocalHarnessView(
                            id = result.agentId,
                            name = result.displayName,
                            available = false,
                            detail = result.reason,
                        )
                    }
                }
                scanning = false
            }
            val defaultDirectory = System.getProperty("user.dir")
            BridgeApp(
                discovery = discovery,
                development = dev,
                localHarnesses = harnesses,
                scanningHarnesses = scanning,
                remoteBridge = remoteBridge,
                defaultWorkingDirectory = defaultDirectory,
                chooseLocalDirectory = {
                    JFileChooser(File(defaultDirectory)).run {
                        dialogTitle = "Выберите папку проекта"
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        isAcceptAllFileFilterUsed = false
                        if (showOpenDialog(window) == JFileChooser.APPROVE_OPTION) selectedFile.absolutePath else null
                    }
                },
                connectUsbAndroid = localRuntime?.let { runtime -> { runtime.connectAndroidUsb() } },
                autoConnect = dev && !local,
                defaultRelayUrl = System.getenv("AGENT_BRIDGE_RELAY_URL"),
                defaultLanguage = Locale.getDefault().language,
            )
        }
    }
}
