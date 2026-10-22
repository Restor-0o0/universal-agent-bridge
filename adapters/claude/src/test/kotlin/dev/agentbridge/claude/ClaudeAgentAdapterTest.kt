package dev.agentbridge.claude

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeAgentAdapterTest {
    @Test
    fun startsManagedSessionAndDeduplicatesText() = runBlocking {
        val factory = FakeFactory()
        val adapter = adapter(factory)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        adapter.sendText(session.id, "hello", "key-1")
        adapter.sendText(session.id, "hello", "key-1")

        assertEquals("00000000-0000-0000-0000-000000000001", session.id)
        assertEquals(1, factory.peer.sent.count { it["type"]?.jsonPrimitive?.content == "user" })
        assertTrue("--include-partial-messages" in factory.arguments)
        assertEquals(session.id, factory.arguments[factory.arguments.indexOf("--session-id") + 1])
        adapter.dispose()
    }

    @Test
    fun streamsTextAndCompletesTurn() = runBlocking {
        val factory = FakeFactory()
        val adapter = adapter(factory)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))

        factory.peer.incomingChannel.send(json("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}}"""))
        assertEquals("hello", withTimeout(2_000) {
            adapter.streamEvents(session.id).filterIsInstance<AgentEvent.MessageDelta>().first().text
        })

        factory.peer.incomingChannel.send(json("""{"type":"result","subtype":"success","is_error":false,"session_id":"${session.id}"}"""))
        assertTrue(withTimeout(2_000) {
            adapter.streamEvents(session.id).filterIsInstance<AgentEvent.TurnFinished>().first().successful
        })
        adapter.dispose()
    }

    @Test
    fun relaysOneShotPermissionAndInterrupt() = runBlocking {
        val factory = FakeFactory()
        val adapter = adapter(factory)
        val session = adapter.startManaged(ManagedStart(Path.of(".").toAbsolutePath().toString()))
        factory.peer.incomingChannel.send(json("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"gradle test","cwd":"/work"},"title":"Run tests"}}"""))

        val approval = withTimeout(2_000) {
            adapter.streamEvents(session.id).filterIsInstance<AgentEvent.Approval>().first().request
        }
        assertEquals("Run tests", approval.command)
        adapter.respondApproval(session.id, approval.id, ApprovalDecision.ALLOW_ONCE)
        val response = factory.peer.sent.last().getValue("response").jsonObject
        assertEquals("success", response.getValue("subtype").jsonPrimitive.content)
        assertEquals("allow", response.getValue("response").jsonObject.getValue("behavior").jsonPrimitive.content)
        assertFailsWith<IllegalStateException> {
            adapter.respondApproval(session.id, approval.id, ApprovalDecision.ALLOW_ONCE)
        }

        adapter.interrupt(session.id)
        val interrupt = factory.peer.sent.last()
        assertEquals("control_request", interrupt.getValue("type").jsonPrimitive.content)
        assertEquals("interrupt", interrupt.getValue("request").jsonObject.getValue("subtype").jsonPrimitive.content)
        adapter.dispose()
    }

    private fun adapter(factory: FakeFactory) = ClaudeAgentAdapter(
        installation = AgentInstallation(
            agentId = "claude",
            version = "test",
            executable = "/test/claude",
            displayName = "Claude Code",
            transport = ManagedTransport.JSON_LINES_STDIO,
        ),
        connectionFactory = factory,
        clockMillis = { 1_000L },
        idFactory = { "00000000-0000-0000-0000-000000000001" },
    )

    private class FakeFactory : ClaudeConnectionFactory {
        val peer = FakePeer()
        lateinit var arguments: List<String>

        override suspend fun start(executable: String, arguments: List<String>, workingDirectory: Path): ClaudePeer {
            this.arguments = arguments
            return peer
        }
    }

    private class FakePeer : ClaudePeer {
        val incomingChannel = Channel<JsonObject>(Channel.UNLIMITED)
        override val incoming: Flow<JsonObject> = incomingChannel.receiveAsFlow()
        val sent = mutableListOf<JsonObject>()

        override suspend fun send(message: JsonObject) {
            sent += message
        }

        override suspend fun shutdown() {
            incomingChannel.close()
        }
    }

    private companion object {
        fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    }
}
