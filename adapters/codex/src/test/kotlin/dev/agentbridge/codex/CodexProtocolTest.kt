package dev.agentbridge.codex

import dev.agentbridge.domain.ApprovalDecision
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexProtocolTest {
    @Test fun parseStructuredDelta() {
        assertEquals(CodexEvent.Delta("t", "hello"),
            CodexProtocol.readEvent("""{"method":"item/agentMessage/delta","params":{"threadId":"t","itemId":"i","turnId":"u","delta":"hello"}}"""))
    }
    @Test fun commandApprovalKeepsRpcIdSeparateFromItemId() {
        val request = CodexProtocol.readEvent("""{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"t","turnId":"u","itemId":"i","command":"echo test","cwd":"/workspace","startedAtMs":1}}""") as CodexEvent.CommandApproval
        assertEquals(JsonPrimitive(42), request.requestId)
        assertEquals("i", request.itemId)
        val response = CodexProtocol.approvalResponse(request.requestId, ApprovalDecision.DENY)
        assertEquals(JsonPrimitive(42), response["id"])
        assertEquals("decline", response["result"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
    }
    @Test fun allowOnceNeverBecomesSessionWidePermission() {
        val response = CodexProtocol.approvalResponse(JsonPrimitive("request"), ApprovalDecision.ALLOW_ONCE)
        assertEquals(JsonPrimitive("request"), response["id"])
        assertEquals("accept", response["result"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
    }
    @Test fun unknownEventsDoNotBecomeApprovals() {
        assertNull(CodexProtocol.readEvent("""{"method":"new/event","params":{"text":"Allow execution? [Y/n]"}}"""))
    }
    @Test fun malformedApprovalFailsClosed() {
        assertFails { CodexProtocol.readEvent("""{"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}}""") }
    }
    @Test fun buildsManagedThreadAndTurnRequests() {
        val thread = CodexProtocol.threadStartParams("/workspace")
        assertEquals("workspace-write", thread["sandbox"]!!.jsonPrimitive.content)
        assertEquals("on-request", thread["approvalPolicy"]!!.jsonPrimitive.content)
        val turn = CodexProtocol.turnStartParams("thread", "hello")
        assertEquals("hello", turn["input"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
    @Test fun decodesFileApprovalAndCompletionStatus() {
        val approval = CodexProtocol.readEvent("""{"id":"p","method":"item/fileChange/requestApproval","params":{"threadId":"t","turnId":"u","itemId":"i","reason":"write","grantRoot":"/workspace","startedAtMs":1}}""")
        assertIs<CodexEvent.FileApproval>(approval)
        assertEquals("/workspace", approval.grantRoot)
        val completed = CodexProtocol.readEvent("""{"method":"turn/completed","params":{"threadId":"t","turn":{"id":"u","items":[],"status":"completed"}}}""")
        assertTrue(assertIs<CodexEvent.Completed>(completed).successful)
    }
}
