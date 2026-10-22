package dev.agentbridge.desktop

import dev.agentbridge.claude.ClaudeHarnessProvider
import dev.agentbridge.client.EncryptedRelayConnection
import dev.agentbridge.codex.CodexHarnessProvider
import dev.agentbridge.crypto.DevelopmentPresharedCipher
import dev.agentbridge.host.HarnessRegistry
import dev.agentbridge.host.HostRelayLink
import dev.agentbridge.host.ManagedAgentHost
import dev.agentbridge.opencode.OpenCodeHarnessProvider
import dev.agentbridge.protocol.BridgePayload
import dev.agentbridge.relay.RelayConfig
import dev.agentbridge.relay.RelayDevelopmentAccess
import dev.agentbridge.relay.RelayEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Self-contained loopback stack used by the packaged desktop application. */
class LocalDesktopRuntime private constructor(
    val remoteBridge: DesktopRemoteBridge,
    private val relay: RelayEngine,
    private val hostHttp: HttpClient,
    private val hostConnection: EncryptedRelayConnection,
    private val host: ManagedAgentHost,
    private val scope: CoroutineScope,
    private val token: String,
    private val keyText: String,
) : AutoCloseable {
    fun connectAndroidUsb(): String {
        val devicesOutput = runAdb("devices")
        val devices = devicesOutput.lineSequence()
            .map(String::trim)
            .filter { it.endsWith("\tdevice") }
            .map { it.substringBefore('\t') }
            .toList()
        require(devices.size == 1) {
            if (devices.isEmpty()) "ADB не видит телефон. Включите USB debugging и подтвердите доступ."
            else "Подключите только один телефон или эмулятор."
        }
        runAdb("-s", devices.single(), "reverse", "tcp:8787", "tcp:8787")
        val launch = runAdb(
            "-s", devices.single(), "shell", "am", "start", "-n",
            "dev.agentbridge.android/.MainActivity",
            "--es", "agent_bridge_url", "http://127.0.0.1:8787",
            "--es", "agent_bridge_token", token,
            "--es", "agent_bridge_key", keyText,
        )
        require(!launch.contains("Error", ignoreCase = true)) {
            "AgentBridge APK не найден на телефоне. Сначала установите APK из dist."
        }
        return "Телефон подключён к этому Router через USB."
    }

    private fun runAdb(vararg arguments: String): String {
        val process = try {
            ProcessBuilder(listOf("adb") + arguments).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            error("adb не найден. Установите Android Platform Tools и добавьте adb в PATH.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output.trim().ifBlank { "Команда adb завершилась с ошибкой." } }
        return output
    }

    override fun close() {
        remoteBridge.close()
        hostConnection.close()
        runBlocking { host.dispose() }
        scope.cancel()
        hostHttp.close()
        relay.close()
    }

    companion object {
        suspend fun start(desktopHttp: HttpClient, port: Int = 8787): LocalDesktopRuntime {
            val random = SecureRandom()
            val tokenBytes = ByteArray(32).also(random::nextBytes)
            val key = ByteArray(32).also(random::nextBytes)
            val token = tokenBytes.joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            val keyText = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            val relay = RelayEngine(
                RelayConfig(port = port),
                RelayDevelopmentAccess(token, emptySet()),
            )
            relay.start()
            val hostHttp = HttpClient(CIO) {
                install(WebSockets)
                install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = null }
            }
            return try {
                val hostConnection = EncryptedRelayConnection(
                    hostHttp,
                    "http://127.0.0.1:$port",
                    token,
                    "host",
                    DevelopmentPresharedCipher(key),
                    deviceRole = "host",
                ).also { it.connect() }
                val host = ManagedAgentHost(
                    HarnessRegistry(listOf(CodexHarnessProvider(), ClaudeHarnessProvider(), OpenCodeHarnessProvider())),
                    DesktopHostRelayLink(hostConnection),
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch { host.run() }
                LocalDesktopRuntime(
                    DesktopRemoteBridge(desktopHttp, token, keyText),
                    relay,
                    hostHttp,
                    hostConnection,
                    host,
                    scope,
                    token,
                    keyText,
                )
            } catch (failure: Throwable) {
                hostHttp.close()
                relay.close()
                throw failure
            }
        }
    }
}

private class DesktopHostRelayLink(
    private val connection: EncryptedRelayConnection,
) : HostRelayLink {
    private val requestOwners = ConcurrentHashMap<String, String>()
    private val sessionOwners = ConcurrentHashMap<String, String>()

    override val incoming: Flow<BridgePayload> = connection.routedIncoming
        .onEach { routed ->
            routed.payload.requestId()?.let { requestOwners[it] = routed.senderDeviceId }
            routed.payload.sessionId()?.let { sessionOwners[it] = routed.senderDeviceId }
        }
        .map { it.payload }

    override suspend fun send(payload: BridgePayload, sessionId: String) {
        val recipient = sessionOwners[sessionId]
            ?: payload.requestId()?.let(requestOwners::get)
            ?: error("No client owns bridge session $sessionId")
        if (payload is BridgePayload.SessionStarted) sessionOwners[payload.sessionId] = recipient
        connection.send(recipient, sessionId, payload)
    }

    private fun BridgePayload.requestId(): String? = when (this) {
        is BridgePayload.ListExternalSessions -> requestId
        is BridgePayload.ExternalSessionList -> requestId
        is BridgePayload.WatchExternalSession -> requestId
        is BridgePayload.ExternalSessionSnapshot -> requestId
        is BridgePayload.StopWatchingExternalSession -> requestId
        is BridgePayload.ListSavedTabs -> requestId
        is BridgePayload.SavedTabList -> requestId
        is BridgePayload.SaveTab -> requestId
        is BridgePayload.DeleteTab -> requestId
        is BridgePayload.ListAgents -> requestId
        is BridgePayload.AgentList -> requestId
        is BridgePayload.ListDirectories -> requestId
        is BridgePayload.DirectoryList -> requestId
        is BridgePayload.StartSession -> requestId
        is BridgePayload.ResumeSession -> requestId
        is BridgePayload.SessionStarted -> requestId
        is BridgePayload.CloseSession -> requestId
        is BridgePayload.SessionClosed -> requestId
        is BridgePayload.SendText -> requestId
        is BridgePayload.RespondApproval -> requestId
        is BridgePayload.Interrupt -> requestId
        is BridgePayload.Failure -> requestId
        else -> null
    }

    private fun BridgePayload.sessionId(): String? = when (this) {
        is BridgePayload.WatchExternalSession -> sessionId
        is BridgePayload.ExternalSessionSnapshot -> session.id
        is BridgePayload.StopWatchingExternalSession -> sessionId
        is BridgePayload.SessionStarted -> sessionId
        is BridgePayload.ResumeSession -> sessionId
        is BridgePayload.CloseSession -> sessionId
        is BridgePayload.SessionClosed -> sessionId
        is BridgePayload.SendText -> sessionId
        is BridgePayload.TextDelta -> sessionId
        is BridgePayload.ApprovalRequested -> sessionId
        is BridgePayload.RespondApproval -> sessionId
        is BridgePayload.Interrupt -> sessionId
        is BridgePayload.TurnFinished -> sessionId
        is BridgePayload.AgentActivity -> sessionId
        is BridgePayload.HelpNeeded -> sessionId
        is BridgePayload.Failure -> sessionId
        else -> null
    }
}
