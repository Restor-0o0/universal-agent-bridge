package dev.agentbridge.acp

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

data class AcpPromptResult(
    val stopReason: String? = null,
    val messageId: String? = null,
)

/** Version-aware baseline session operations used by the Host and UI adapter. */
class AcpSessionClient(
    private val connection: AcpPeer,
    private val agent: AcpAgentInfo,
) {
    init {
        require(agent.supportsSessions) { "ACP agent does not support sessions" }
    }

    suspend fun newSession(workingDirectory: Path): String {
        val cwd = workingDirectory.toAbsolutePath().normalize()
        require(cwd.isAbsolute)
        val result = connection.request("session/new", AcpProtocol.newSessionParams(cwd.toString()))
        return result["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: error("ACP session/new response has no sessionId")
    }

    suspend fun promptText(sessionId: String, text: String): AcpPromptResult {
        require(sessionId.isNotBlank())
        require(text.isNotBlank())
        val result = connection.request(
            "session/prompt",
            AcpProtocol.textPromptParams(agent.protocolVersion, sessionId, text),
        )
        return AcpPromptResult(
            stopReason = result["stopReason"]?.jsonPrimitive?.contentOrNull,
            messageId = result["messageId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun resumeSession(sessionId: String, workingDirectory: Path) {
        require(agent.supportsResume) { "ACP agent does not support session resume" }
        val method = if (agent.protocolVersion >= 2 || !agent.supportsLoadSession) "session/resume" else "session/load"
        connection.request(method, AcpProtocol.resumeSessionParams(sessionId, workingDirectory.toAbsolutePath().normalize().toString()))
    }

    suspend fun cancel(sessionId: String) {
        require(sessionId.isNotBlank())
        connection.notify("session/cancel", AcpProtocol.sessionIdParams(sessionId))
    }

    suspend fun close(sessionId: String) {
        require(agent.protocolVersion >= 2) { "session/close is not a baseline ACP v1 method" }
        connection.request("session/close", AcpProtocol.sessionIdParams(sessionId))
    }
}
