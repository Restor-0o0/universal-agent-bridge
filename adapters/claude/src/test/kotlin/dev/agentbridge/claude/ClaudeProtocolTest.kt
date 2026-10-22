package dev.agentbridge.claude

import dev.agentbridge.domain.ApprovalDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeProtocolTest {
    @Test
    fun buildsStreamingLaunchAndUserMessage() {
        val args = ClaudeProtocol.launchArguments("session-1", resume = false)
        assertTrue("--print" in args)
        assertTrue("--include-partial-messages" in args)
        assertEquals("host", args[args.indexOf("--permission-prompts") + 1])
        assertEquals("session-1", args[args.indexOf("--session-id") + 1])

        val message = ClaudeProtocol.userMessage("hello")
        assertEquals("user", message.getValue("type").jsonPrimitive.content)
        assertTrue(message.toString().contains("hello"))
    }

    @Test
    fun decodesTextResultAndPermission() {
        val delta = ClaudeProtocol.decode(json("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}}"""))
        assertEquals("hi", assertIs<ClaudeInbound.Delta>(delta).text)

        val result = ClaudeProtocol.decode(json("""{"type":"result","subtype":"error_during_execution","is_error":true}"""))
        assertFalse(assertIs<ClaudeInbound.Result>(result).successful)

        val permission = ClaudeProtocol.decode(json("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"gradle test"},"title":"Run command"}}"""))
        assertEquals("gradle test", assertIs<ClaudeInbound.Permission>(permission).input.getValue("command").jsonPrimitive.content)
    }

    @Test
    fun permissionAllowPreservesOriginalInput() {
        val input = json("""{"command":"gradle test"}""")
        val response = ClaudeProtocol.permissionResponse("r1", input, ApprovalDecision.ALLOW_ONCE)
        val payload = response.getValue("response").jsonObject.getValue("response").jsonObject
        assertEquals("allow", payload.getValue("behavior").jsonPrimitive.content)
        assertEquals(input, payload.getValue("updatedInput"))
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
