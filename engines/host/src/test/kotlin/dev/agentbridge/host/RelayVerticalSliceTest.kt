package dev.agentbridge.host

import dev.agentbridge.client.EncryptedRelayConnection
import dev.agentbridge.crypto.DevelopmentPresharedCipher
import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.domain.Capability
import dev.agentbridge.domain.SessionDescriptor
import dev.agentbridge.domain.SessionMode
import dev.agentbridge.protocol.BridgePayload
import dev.agentbridge.relay.RelayDevelopmentAccess
import dev.agentbridge.relay.bridgeRelay
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.Attachment
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedStart
import dev.agentbridge.spi.ManagedTransport
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayVerticalSliceTest {
    @Test
    fun desktopRelayHostManagedAgentRoundTripIsEncrypted() = testApplication {
        val token = "0123456789abcdef0123456789abcdef"
        val key = ByteArray(32) { it.toByte() }
        application { bridgeRelay(RelayDevelopmentAccess(token, setOf("host", "desktop"))) }
        val http = createClient { install(WebSockets) }
        val hostConnection = EncryptedRelayConnection(
            http, "http://localhost", token, "host", DevelopmentPresharedCipher(key),
        )
        val desktopConnection = EncryptedRelayConnection(
            http, "http://localhost", token, "desktop", DevelopmentPresharedCipher(key),
        )
        hostConnection.connect()
        desktopConnection.connect()

        val adapter = SliceAdapter()
        val registry = HarnessRegistry(
            listOf(SliceProvider(adapter)),
            ExecutableResolver { "/test/codex" },
        )
        val host = ManagedAgentHost(registry, object : HostRelayLink {
            override val incoming: Flow<BridgePayload> = hostConnection.incoming
            override suspend fun send(payload: BridgePayload, sessionId: String) {
                hostConnection.send("desktop", sessionId, payload)
            }
        })
        val hostJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { host.run() }

        desktopConnection.send(
            "host",
            "control",
            BridgePayload.StartSession("start-1", "."),
        )
        val started = withTimeout(3_000) {
            desktopConnection.incoming.filterIsInstance<BridgePayload.SessionStarted>().first()
        }
        desktopConnection.send(
            "host",
            started.sessionId,
            BridgePayload.SendText("send-1", started.sessionId, "hello", "once-1"),
        )
        val delta = withTimeout(3_000) {
            desktopConnection.incoming.filterIsInstance<BridgePayload.TextDelta>().first()
        }
        assertEquals("echo:hello", delta.text)
        val finished = withTimeout(3_000) {
            desktopConnection.incoming.filterIsInstance<BridgePayload.TurnFinished>().first()
        }
        assertTrue(finished.successful)

        host.dispose()
        hostJob.cancel()
        desktopConnection.close()
        hostConnection.close()
    }

    private class SliceProvider(private val adapter: SliceAdapter) : HarnessProvider {
        override val agentId = "codex"
        override val displayName = "Codex"
        override val executableNames = listOf("codex")
        override suspend fun probe(executable: String) = adapter.installation
        override fun createAdapter(installation: AgentInstallation): AgentAdapter = adapter
    }

    private class SliceAdapter : AgentAdapter {
        val installation = AgentInstallation(
            "codex", "test", "/test/codex", "Codex", ManagedTransport.JSON_RPC_STDIO,
            setOf(Capability.STREAMING),
        )
        private val events = Channel<AgentEvent>(Channel.UNLIMITED)
        private val session = SessionDescriptor(
            "session-1", "local", "codex", "Slice", SessionMode.MANAGED_LIVE,
            setOf(Capability.STREAMING),
        )
        override val agentId = "codex"
        override suspend fun detect() = installation
        override suspend fun listSessions() = listOf(session)
        override fun capabilities(session: SessionDescriptor) = session.capabilities
        override suspend fun startManaged(request: ManagedStart) = session
        override suspend fun attach(sessionId: String) = error("unsupported")
        override suspend fun resume(sessionId: String, workingDirectory: String?) = session
        override suspend fun sendText(sessionId: String, text: String, idempotencyKey: String) {
            events.send(AgentEvent.MessageDelta(sessionId, "echo:$text"))
            events.send(AgentEvent.TurnFinished(sessionId, true))
        }
        override suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>) = Unit
        override fun streamEvents(sessionId: String): Flow<AgentEvent> = events.receiveAsFlow()
        override suspend fun respondApproval(sessionId: String, approvalId: String, decision: ApprovalDecision) = Unit
        override suspend fun interrupt(sessionId: String) = Unit
        override suspend fun close(sessionId: String) = Unit
        override suspend fun dispose() { events.close() }
    }
}
