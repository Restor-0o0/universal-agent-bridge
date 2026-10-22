package dev.agentbridge.claude

import dev.agentbridge.domain.ApprovalDecision
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal sealed interface ClaudeInbound {
    data class Delta(val text: String) : ClaudeInbound
    data class Result(val successful: Boolean, val code: String) : ClaudeInbound
    data class Permission(
        val requestId: String,
        val toolName: String,
        val input: JsonObject,
        val title: String?,
        val description: String?,
    ) : ClaudeInbound
}

internal object ClaudeProtocol {
    fun launchArguments(sessionId: String, resume: Boolean): List<String> = buildList {
        add("--print")
        add("--input-format")
        add("stream-json")
        add("--output-format")
        add("stream-json")
        add("--verbose")
        add("--include-partial-messages")
        add("--permission-mode")
        add("manual")
        add("--permission-prompts")
        add("host")
        add(if (resume) "--resume" else "--session-id")
        add(sessionId)
    }

    fun userMessage(text: String): JsonObject = buildJsonObject {
        put("type", "user")
        put("message", buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        })
    }

    fun interrupt(requestId: String): JsonObject = controlRequest(
        requestId,
        buildJsonObject { put("subtype", "interrupt") },
    )

    fun permissionResponse(
        requestId: String,
        input: JsonObject,
        decision: ApprovalDecision,
    ): JsonObject = buildJsonObject {
        put("type", "control_response")
        put("response", buildJsonObject {
            put("subtype", "success")
            put("request_id", requestId)
            put("response", when (decision) {
                ApprovalDecision.ALLOW_ONCE -> buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", input)
                }
                ApprovalDecision.DENY -> buildJsonObject {
                    put("behavior", "deny")
                    put("message", "Denied by Agent Bridge user")
                }
            })
        })
    }

    fun decode(message: JsonObject): ClaudeInbound? = when (message.string("type")) {
        "stream_event" -> decodeStreamEvent(message)
        "result" -> {
            val success = message["is_error"]?.jsonPrimitive?.booleanOrNull != true &&
                message.string("subtype") == "success"
            ClaudeInbound.Result(success, message.string("subtype") ?: "CLAUDE_RESULT")
        }
        "control_request" -> decodePermission(message)
        else -> null
    }

    private fun decodeStreamEvent(message: JsonObject): ClaudeInbound? {
        val event = message["event"]?.jsonObject ?: return null
        if (event.string("type") != "content_block_delta") return null
        val delta = event["delta"]?.jsonObject ?: return null
        if (delta.string("type") != "text_delta") return null
        return delta.string("text")?.takeIf(String::isNotEmpty)?.let(ClaudeInbound::Delta)
    }

    private fun decodePermission(message: JsonObject): ClaudeInbound? {
        val request = message["request"]?.jsonObject ?: return null
        if (request.string("subtype") != "can_use_tool") return null
        return ClaudeInbound.Permission(
            requestId = message.string("request_id") ?: return null,
            toolName = request.string("tool_name") ?: "Tool",
            input = request["input"] as? JsonObject ?: JsonObject(emptyMap()),
            title = request.string("title"),
            description = request.string("description"),
        )
    }

    private fun controlRequest(requestId: String, request: JsonElement): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        put("request", request)
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull
}
