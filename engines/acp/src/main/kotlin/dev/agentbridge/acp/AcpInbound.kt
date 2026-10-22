package dev.agentbridge.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AcpPermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
)

sealed interface AcpInbound {
    val sessionId: String

    data class MessageChunk(
        override val sessionId: String,
        val text: String,
    ) : AcpInbound

    data class PermissionRequest(
        override val sessionId: String,
        val rpcId: JsonElement,
        val title: String,
        val command: String?,
        val cwd: String?,
        val toolCallId: String?,
        val options: List<AcpPermissionOption>,
    ) : AcpInbound

    data class TurnFinished(
        override val sessionId: String,
        val stopReason: String,
    ) : AcpInbound
}

object AcpInboundCodec {
    private val json = Json

    fun decode(message: JsonObject, protocolVersion: Int): AcpInbound? {
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return null
        val params = message["params"]?.jsonObject ?: return null
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (method) {
            "session/update" -> decodeUpdate(sessionId, params["update"]?.jsonObject ?: return null)
            "session/request_permission" -> decodePermission(
                protocolVersion,
                sessionId,
                message["id"] ?: return null,
                params,
            )
            else -> null
        }
    }

    private fun decodeUpdate(sessionId: String, update: JsonObject): AcpInbound? {
        return when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            "agent_message_chunk" -> update["content"]?.jsonObject
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?.let { AcpInbound.MessageChunk(sessionId, it) }

            "agent_message" -> update["content"]?.jsonArray
                ?.mapNotNull { block ->
                    block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                }
                ?.joinToString("")
                ?.takeIf(String::isNotEmpty)
                ?.let { AcpInbound.MessageChunk(sessionId, it) }

            "state_update" -> update.takeIf {
                it["state"]?.jsonPrimitive?.contentOrNull == "idle"
            }?.get("stopReason")?.jsonObject
                ?.get("type")?.jsonPrimitive?.contentOrNull
                ?.let { AcpInbound.TurnFinished(sessionId, it) }

            else -> null
        }
    }

    private fun decodePermission(
        protocolVersion: Int,
        sessionId: String,
        rpcId: JsonElement,
        params: JsonObject,
    ): AcpInbound.PermissionRequest {
        val v1ToolCall = params["toolCall"]?.jsonObject
        val subject = params["subject"]?.jsonObject
        val v2Command = subject?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "command" }
        val v2ToolCall = subject?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "tool_call" }
            ?.get("toolCall")?.jsonObject
        val toolCall = v1ToolCall ?: v2ToolCall
        val rawInput = toolCall?.get("rawInput") as? JsonObject
        val title = if (protocolVersion >= 2) {
            params["title"]?.jsonPrimitive?.contentOrNull
        } else {
            toolCall?.get("title")?.jsonPrimitive?.contentOrNull
        } ?: "Agent permission request"
        val options = params["options"]?.jsonArray.orEmpty().map { optionElement ->
            val option = optionElement.jsonObject
            AcpPermissionOption(
                optionId = requireNotNull(option["optionId"]?.jsonPrimitive?.contentOrNull),
                name = option["name"]?.jsonPrimitive?.contentOrNull ?: "",
                kind = option["kind"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            )
        }
        require(options.isNotEmpty()) { "ACP permission request has no options" }
        return AcpInbound.PermissionRequest(
            sessionId = sessionId,
            rpcId = rpcId,
            title = title,
            command = v2Command?.get("command")?.jsonPrimitive?.contentOrNull
                ?: rawInput?.get("command")?.let(::renderCommand),
            cwd = v2Command?.get("cwd")?.jsonPrimitive?.contentOrNull
                ?: rawInput?.get("cwd")?.jsonPrimitive?.contentOrNull,
            toolCallId = v2Command?.get("toolCallId")?.jsonPrimitive?.contentOrNull
                ?: toolCall?.get("toolCallId")?.jsonPrimitive?.contentOrNull,
            options = options,
        )
    }

    private fun renderCommand(value: JsonElement): String = (value as? JsonPrimitive)?.contentOrNull
        ?: json.encodeToString(JsonElement.serializer(), value)
}
