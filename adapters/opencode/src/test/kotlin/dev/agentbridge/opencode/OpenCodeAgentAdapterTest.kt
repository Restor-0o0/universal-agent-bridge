package dev.agentbridge.opencode

import dev.agentbridge.acp.AcpConnectionFactory
import dev.agentbridge.acp.AcpPeer
import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.domain.Capability
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.ManagedStart
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCodeAgentAdapterTest {
    @Test
    fun startsManagedSessionAndDeduplicatesText() = runBlocking {
        val fake = FakeAcpPeer()
        val adapter = adapter(fake)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))

        assertEquals("session-1", session.id)
        assertTrue(Capability.IMAGE_INPUT in session.capabilities)
        assertFalse(Capability.RESUME in session.capabilities)

        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                adapter.streamEvents(session.id).filterIsInstance<AgentEvent.TurnFinished>().first()
            }
        }
        yield()
        adapter.sendText(session.id, "hello", "message-1")
        adapter.sendText(session.id, "hello", "message-1")
        assertTrue(finished.await().successful)
        assertEquals(1, fake.requests.count { it.first == "session/prompt" })
        adapter.dispose()
    }

    @Test
    fun mapsAndConsumesAllowOncePermission() = runBlocking {
        val fake = FakeAcpPeer()
        val adapter = adapter(fake)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                adapter.streamEvents(session.id).filterIsInstance<AgentEvent.Approval>().first()
            }
        }
        yield()
        fake.incomingChannel.send(
            json("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"session-1","toolCall":{"toolCallId":"call-1","title":"Run tests","rawInput":{"command":"gradle test","cwd":"/work"}},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}"""),
        )

        val request = approval.await().request
        assertEquals("gradle test", request.command)
        adapter.respondApproval(session.id, request.id, ApprovalDecision.ALLOW_ONCE)
        assertEquals(
            "once",
            fake.responses.single().second.jsonObject.getValue("outcome")
                .jsonObject.getValue("optionId").jsonPrimitive.content,
        )
        assertFailsWith<IllegalStateException> {
            adapter.respondApproval(session.id, request.id, ApprovalDecision.ALLOW_ONCE)
        }
        adapter.dispose()
    }

    @Test
    fun interruptCancelsPendingPermissionBeforeTurn() = runBlocking {
        val fake = FakeAcpPeer()
        val adapter = adapter(fake)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                adapter.streamEvents(session.id).filterIsInstance<AgentEvent.Approval>().first()
            }
        }
        yield()
        fake.incomingChannel.send(
            json("""{"jsonrpc":"2.0","id":"p1","method":"session/request_permission","params":{"sessionId":"session-1","toolCall":{"toolCallId":"call-1","title":"Write"},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"}]}}"""),
        )
        approval.await()

        adapter.interrupt(session.id)

        assertEquals(
            "cancelled",
            fake.responses.single().second.jsonObject.getValue("outcome")
                .jsonObject.getValue("outcome").jsonPrimitive.content,
        )
        assertTrue(fake.notifications.any { it.first == "session/cancel" })
        adapter.dispose()
    }

    @Test
    fun expiredPermissionIsCancelled() = runBlocking {
        var now = 1_000L
        val fake = FakeAcpPeer()
        val adapter = adapter(fake) { now }
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        fake.incomingChannel.send(
            json("""{"jsonrpc":"2.0","id":11,"method":"session/request_permission","params":{"sessionId":"session-1","toolCall":{"toolCallId":"call-1","title":"Run"},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"}]}}"""),
        )
        val request = withTimeout(2_000) {
            adapter.streamEvents(session.id).filterIsInstance<AgentEvent.Approval>().first().request
        }
        now = request.expiresAtMillis

        assertFailsWith<IllegalStateException> {
            adapter.respondApproval(session.id, request.id, ApprovalDecision.ALLOW_ONCE)
        }
        assertEquals(
            "cancelled",
            fake.responses.single().second.jsonObject.getValue("outcome")
                .jsonObject.getValue("outcome").jsonPrimitive.content,
        )
        adapter.dispose()
    }

    private fun adapter(peer: FakeAcpPeer, clock: () -> Long = { 1_000L }) = OpenCodeAgentAdapter(
        installation = AgentInstallation(
            agentId = "opencode",
            version = "test",
            executable = "/test/opencode",
            displayName = "OpenCode",
            transport = ManagedTransport.AGENT_CLIENT_PROTOCOL,
        ),
        connectionFactory = AcpConnectionFactory { _, args, _ ->
            assertEquals(listOf("acp"), args)
            peer
        },
        clockMillis = clock,
    )

    private class FakeAcpPeer : AcpPeer {
        val incomingChannel = Channel<JsonObject>(Channel.UNLIMITED)
        override val incoming: Flow<JsonObject> = incomingChannel.receiveAsFlow()
        val requests = mutableListOf<Pair<String, JsonElement>>()
        val notifications = mutableListOf<Pair<String, JsonElement>>()
        val responses = mutableListOf<Pair<JsonElement, JsonElement>>()

        override suspend fun request(method: String, params: JsonElement): JsonObject {
            requests += method to params
            return when (method) {
                "initialize" -> json("""{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"promptCapabilities":{"image":true}},"agentInfo":{"name":"OpenCode","version":"test"}}""")
                "session/new" -> buildJsonObject { put("sessionId", "session-1") }
                "session/prompt" -> buildJsonObject { put("stopReason", "end_turn") }
                else -> error("Unexpected request: $method")
            }
        }

        override suspend fun notify(method: String, params: JsonElement) {
            notifications += method to params
        }

        override suspend fun respond(id: JsonElement, result: JsonElement) {
            responses += id to result
        }

        override suspend fun shutdown() {
            incomingChannel.close()
        }
    }

    private companion object {
        fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    }
}
