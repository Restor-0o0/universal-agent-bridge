package dev.agentbridge.host

import dev.agentbridge.acp.ProcessAcpProbe
import dev.agentbridge.claude.ClaudeHarnessProvider
import dev.agentbridge.codex.CodexHarnessProvider
import dev.agentbridge.codex.probeCodex
import dev.agentbridge.opencode.OpenCodeHarnessProvider
import dev.agentbridge.client.EncryptedRelayConnection
import dev.agentbridge.crypto.DevelopmentPresharedCipher
import dev.agentbridge.protocol.BridgePayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--probe-codex" -> {
            require(args.size <= 2) { "Usage: --probe-codex [executable]" }
            check(probeCodex(args.getOrNull(1) ?: "codex", Path.of(".")))
            println("PASS: Codex app-server initialize/initialized over stdio. No managed turn was started.")
        }
        "--probe-opencode-acp" -> runBlocking {
            require(args.size <= 2) { "Usage: --probe-opencode-acp [executable]" }
            val executable = args.getOrNull(1)
                ?: PathExecutableResolver().resolve(OpenCodeHarnessProvider().executableNames)
                ?: error("OpenCode CLI not found")
            val info = ProcessAcpProbe().probe(executable, listOf("acp"))
            check(info.supportsSessions)
            println("PASS: OpenCode ACP v${info.protocolVersion} initialize over stdio; agent=${info.name ?: "unknown"}.")
        }
        "--connect-relay-dev" -> runBlocking {
            require(args.size <= 2) { "Usage: --connect-relay-dev [relay-url]" }
            connectDevelopmentRelay(args.getOrNull(1) ?: "http://127.0.0.1:8787")
        }
        null, "--list-agents" -> runBlocking { printDiscoveredAgents() }
        "--help" -> println(
            "Agent Bridge Host.\n" +
                "  --list-agents            Auto-detect supported local harnesses (default)\n" +
                "  --probe-codex [path]     Verify Codex app-server handshake\n" +
                "  --probe-opencode-acp [path] Verify OpenCode ACP handshake\n" +
                "  --connect-relay-dev [url]  Connect all auto-detected harnesses to loopback dev Relay",
        )
        else -> error("Unknown Host option")
    }
}

private suspend fun connectDevelopmentRelay(url: String) {
    val token = requireNotNull(System.getenv("AGENT_BRIDGE_DEV_TOKEN")) {
        "AGENT_BRIDGE_DEV_TOKEN is required"
    }
    val key = requireNotNull(System.getenv("AGENT_BRIDGE_DEV_KEY")) {
        "AGENT_BRIDGE_DEV_KEY is required (base64url 32-byte key)"
    }.let { Base64.getUrlDecoder().decode(it) }
    val http = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = null
        }
    }
    val connection = EncryptedRelayConnection(
        http = http,
        baseUrl = url,
        bearerToken = token,
        deviceId = "host",
        cipher = DevelopmentPresharedCipher(key),
    )
    connection.connect()
    val registry = HarnessRegistry(
        listOf(CodexHarnessProvider(), ClaudeHarnessProvider(), OpenCodeHarnessProvider()),
    )
    val host = ManagedAgentHost(registry, EncryptedHostRelayLink(connection))
    Runtime.getRuntime().addShutdownHook(Thread {
        connection.close()
        http.close()
    })
    println("Agent Bridge Host connected to local development Relay; harnesses are auto-detected.")
    try {
        host.run()
    } finally {
        host.dispose()
        connection.close()
        http.close()
    }
}

private class EncryptedHostRelayLink(
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

private suspend fun printDiscoveredAgents() {
    val registry = HarnessRegistry(
        listOf(
            CodexHarnessProvider(),
            ClaudeHarnessProvider(),
            OpenCodeHarnessProvider(),
        ),
    )
    println("Agent Bridge harness discovery")
    registry.discover().forEach { result ->
        when (result) {
            is HarnessDiscovery.Available -> {
                val found = result.installation
                println(
                    "FOUND  ${found.displayName} | ${found.version} | ${found.transport} | " +
                        found.capabilities.map { it.name }.sorted().joinToString(","),
                )
            }
            is HarnessDiscovery.Unavailable -> println("MISSING ${result.displayName} | ${result.reason}")
        }
    }
}
