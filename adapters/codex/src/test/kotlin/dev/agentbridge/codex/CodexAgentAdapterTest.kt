package dev.agentbridge.codex

import dev.agentbridge.acp.AcpConnectionFactory
import dev.agentbridge.acp.AcpPeer
import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.ManagedStart
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexAgentAdapterTest {
    @Test
    fun startsThreadAndDeduplicatesTurnStart() = runBlocking {
        val peer = FakePeer()
        val adapter = adapter(peer)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        adapter.sendText(session.id, "hello", "key-1")
        adapter.sendText(session.id, "hello", "key-1")

        assertEquals("thread-1", session.id)
        assertEquals(1, peer.requests.count { it.first == "turn/start" })
        val startParams = peer.requests.first { it.first == "thread/start" }.second.jsonObject
        assertEquals("on-request", startParams.getValue("approvalPolicy").jsonPrimitive.content)
        assertEquals("workspace-write", startParams.getValue("sandbox").jsonPrimitive.content)
        adapter.dispose()
    }

    @Test
    fun streamsDeltaCompletionAndConsumesApproval() = runBlocking {
        val peer = FakePeer()
        val adapter = adapter(peer)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        peer.incomingChannel.send(json("""{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"i","delta":"hello"}}"""))
        assertEquals(
            "hello",
            withTimeout(2_000) {
                adapter.streamEvents(session.id).filterIsInstance<AgentEvent.MessageDelta>().first().text
            },
        )
        peer.incomingChannel.send(json("""{"id":12,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"i","command":"gradle test","cwd":"/work","startedAtMs":1}}"""))
        val approval = withTimeout(2_000) {
            adapter.streamEvents(session.id).filterIsInstance<AgentEvent.Approval>().first().request
        }
        adapter.respondApproval(session.id, approval.id, ApprovalDecision.ALLOW_ONCE)
        assertEquals("accept", peer.responses.single().second.jsonObject.getValue("decision").jsonPrimitive.content)
        assertFailsWith<IllegalStateException> {
            adapter.respondApproval(session.id, approval.id, ApprovalDecision.ALLOW_ONCE)
        }

        peer.incomingChannel.send(json("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","items":[],"status":"completed"}}}"""))
        assertTrue(
            withTimeout(2_000) {
                adapter.streamEvents(session.id).filterIsInstance<AgentEvent.TurnFinished>().first().successful
            },
        )
        adapter.dispose()
    }

    @Test
    fun interruptUsesAcceptedTurnId() = runBlocking {
        val peer = FakePeer()
        val adapter = adapter(peer)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        adapter.sendText(session.id, "hello", "key-1")
        adapter.interrupt(session.id)
        val params = peer.requests.single { it.first == "turn/interrupt" }.second.jsonObject
        assertEquals("thread-1", params.getValue("threadId").jsonPrimitive.content)
        assertEquals("turn-1", params.getValue("turnId").jsonPrimitive.content)
        adapter.dispose()
    }

    private fun adapter(peer: FakePeer) = CodexAgentAdapter(
        AgentInstallation(
            agentId = "codex",
            version = "test",
            executable = "/test/codex",
            displayName = "Codex",
            transport = ManagedTransport.JSON_RPC_STDIO,
        ),
        connectionFactory = AcpConnectionFactory { _, arguments, _ ->
            assertEquals(listOf("app-server", "--listen", "stdio://"), arguments)
            peer
        },
        clockMillis = { 1_000L },
    )

    private class FakePeer : AcpPeer {
        val incomingChannel = Channel<JsonObject>(Channel.UNLIMITED)
        override val incoming: Flow<JsonObject> = incomingChannel.receiveAsFlow()
        val requests = mutableListOf<Pair<String, JsonElement>>()
        val responses = mutableListOf<Pair<JsonElement, JsonElement>>()

        override suspend fun request(method: String, params: JsonElement): JsonObject {
            requests += method to params
            return when (method) {
                "initialize", "turn/interrupt" -> JsonObject(emptyMap())
                "thread/start", "thread/resume" -> buildJsonObject {
                    put("thread", buildJsonObject { put("id", "thread-1") })
                }
                "turn/start" -> buildJsonObject {
                    put("turn", buildJsonObject { put("id", "turn-1") })
                }
                else -> error("Unexpected request: $method")
            }
        }

        override suspend fun notify(method: String, params: JsonElement) = Unit
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
