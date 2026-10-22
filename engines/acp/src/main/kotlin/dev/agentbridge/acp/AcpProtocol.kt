package dev.agentbridge.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AcpAgentInfo(
    val protocolVersion: Int,
    val name: String?,
    val title: String?,
    val version: String?,
    val supportsSessions: Boolean,
    val supportsResume: Boolean,
    val supportsImages: Boolean,
    val rawCapabilities: JsonObject,
    val supportsLoadSession: Boolean = false,
)

object AcpProtocol {
    const val LATEST_VERSION = 2
    val supportedVersions = setOf(1, 2)

    /** Sends both versioned identity keys; ACP peers ignore unknown object fields. */
    fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", LATEST_VERSION)
        put("capabilities", buildJsonObject {})
        put("info", clientInfo())
        put("clientCapabilities", buildJsonObject {})
        put("clientInfo", clientInfo())
    }

    fun parseInitializeResult(result: JsonObject): AcpAgentInfo {
        val protocolVersion = result.getValue("protocolVersion").jsonPrimitive.int
        require(protocolVersion in supportedVersions) { "Unsupported ACP version: $protocolVersion" }
        val capabilitiesKey = if (protocolVersion >= 2) "capabilities" else "agentCapabilities"
        val infoKey = if (protocolVersion >= 2) "info" else "agentInfo"
        val capabilities = result[capabilitiesKey]?.jsonObject ?: JsonObject(emptyMap())
        val info = result[infoKey]?.jsonObject
        val prompt = if (protocolVersion >= 2) {
            capabilities["session"]?.jsonObject?.get("prompt")?.jsonObject
        } else {
            capabilities["promptCapabilities"]?.jsonObject
        }
        return AcpAgentInfo(
            protocolVersion = protocolVersion,
            name = info?.get("name")?.jsonPrimitive?.contentOrNull,
            title = info?.get("title")?.jsonPrimitive?.contentOrNull,
            version = info?.get("version")?.jsonPrimitive?.contentOrNull,
            supportsSessions = protocolVersion == 1 || capabilities["session"] != null,
            supportsResume = if (protocolVersion >= 2) {
                capabilities["session"] != null
            } else {
                capabilities["loadSession"]?.jsonPrimitive?.booleanOrNull == true ||
                    capabilities["sessionCapabilities"]?.jsonObject?.get("resume") != null
            },
            supportsImages = if (protocolVersion >= 2) {
                prompt?.get("image") != null
            } else {
                prompt?.get("image")?.jsonPrimitive?.booleanOrNull == true
            },
            rawCapabilities = capabilities,
            supportsLoadSession = protocolVersion == 1 && capabilities["loadSession"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    fun newSessionParams(workingDirectory: String): JsonObject = buildJsonObject {
        put("cwd", workingDirectory)
        put("mcpServers", JsonArray(emptyList()))
    }

    fun resumeSessionParams(sessionId: String, workingDirectory: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("cwd", workingDirectory)
        put("mcpServers", JsonArray(emptyList()))
    }

    fun textPromptParams(protocolVersion: Int, sessionId: String, text: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        val blocks = JsonArray(
            listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
            }),
        )
        put(if (protocolVersion >= 2) "content" else "prompt", blocks)
    }

    fun sessionIdParams(sessionId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
    }

    fun permissionResponse(optionId: String): JsonObject = buildJsonObject {
        put("outcome", buildJsonObject {
            put("outcome", "selected")
            put("optionId", optionId)
        })
    }

    fun cancelledPermissionResponse(): JsonObject = buildJsonObject {
        put("outcome", buildJsonObject { put("outcome", "cancelled") })
    }

    private fun clientInfo(): JsonObject = buildJsonObject {
        put("name", "universal-agent-bridge")
        put("title", "Universal Agent Bridge")
        put("version", "0.1.0")
    }
}
