package dev.agentbridge.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AcpInboundCodecTest {
    @Test
    fun decodesV1MessageChunk() {
        val event = AcpInboundCodec.decode(
            json("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}}}"""),
            1,
        )
        val chunk = assertIs<AcpInbound.MessageChunk>(event)
        assertEquals("s1", chunk.sessionId)
        assertEquals("hello", chunk.text)
    }

    @Test
    fun decodesV1PermissionWithoutGuessingPersistentApproval() {
        val event = AcpInboundCodec.decode(
            json("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"call1","title":"Run tests","rawInput":{"command":["gradle","test"],"cwd":"/work"}},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}"""),
            1,
        )
        val permission = assertIs<AcpInbound.PermissionRequest>(event)
        assertEquals("Run tests", permission.title)
        assertEquals("[\"gradle\",\"test\"]", permission.command)
        assertEquals("/work", permission.cwd)
        assertEquals(listOf("allow_once", "reject_once"), permission.options.map { it.kind })
    }

    @Test
    fun decodesV2CommandPermission() {
        val event = AcpInboundCodec.decode(
            json("""{"jsonrpc":"2.0","id":"p1","method":"session/request_permission","params":{"sessionId":"s2","title":"Run?","subject":{"type":"command","command":"./test","cwd":"/work"},"options":[{"optionId":"a","name":"Once","kind":"allow_once"}]}}"""),
            2,
        )
        val permission = assertIs<AcpInbound.PermissionRequest>(event)
        assertEquals("./test", permission.command)
        assertEquals("/work", permission.cwd)
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
