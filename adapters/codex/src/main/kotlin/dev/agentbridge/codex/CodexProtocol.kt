package dev.agentbridge.codex

import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.host.ProcessCommandRunner
import dev.agentbridge.host.firstVersionLine
import dev.agentbridge.host.requireSuccess
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedTransport
import dev.agentbridge.spi.AgentAdapter
import kotlinx.serialization.json.*

class CodexHarnessProvider(
    private val runner: CommandRunner = ProcessCommandRunner(),
) : HarnessProvider {
    override val agentId = "codex"
    override val displayName = "Codex"
    override val executableNames = listOf("codex", "codex.exe")

    override suspend fun probe(executable: String): AgentInstallation {
        val version = firstVersionLine(runner.requireSuccess(executable, listOf("--version"), "Codex version probe"))
        val help = runner.requireSuccess(executable, listOf("app-server", "--help"), "Codex app-server probe")
        check("--listen" in help && "stdio://" in help) { "Codex app-server stdio transport is unavailable" }
        return AgentInstallation(
            agentId = agentId,
            version = version,
            executable = executable,
            displayName = displayName,
            transport = ManagedTransport.JSON_RPC_STDIO,
            capabilities = setOf(
                Capability.RESUME,
                Capability.APPROVAL,
                Capability.IMAGE_INPUT,
                Capability.STREAMING,
                Capability.STOP,
            ),
        )
    }

    override fun createAdapter(installation: AgentInstallation): AgentAdapter = CodexAgentAdapter(installation)
}

/** Narrow spike, not a production AgentAdapter. JSON-RPC ids retain their JSON type. */
object CodexProtocol {
    fun initializeParams(): JsonObject = buildJsonObject {
        putJsonObject("clientInfo") {
            put("name", "universal_agent_bridge")
            put("title", "Universal Agent Bridge")
            put("version", "0.1.0-dev")
        }
    }
    fun initialize(id: Int = 1): JsonObject = buildJsonObject {
        put("id", id); put("method", "initialize")
        put("params", initializeParams())
    }
    fun initialized(): JsonObject = buildJsonObject { put("method", "initialized") }
    fun threadStartParams(cwd: String): JsonObject = buildJsonObject {
        put("cwd", cwd)
        put("approvalPolicy", "on-request")
        put("approvalsReviewer", "user")
        put("sandbox", "workspace-write")
        put("ephemeral", false)
    }
    fun threadResumeParams(threadId: String): JsonObject = buildJsonObject { put("threadId", threadId) }
    fun turnStartParams(threadId: String, text: String): JsonObject = buildJsonObject {
        put("threadId", threadId)
        putJsonArray("input") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }
    fun turnInterruptParams(threadId: String, turnId: String): JsonObject = buildJsonObject {
        put("threadId", threadId)
        put("turnId", turnId)
    }
    fun parseThreadId(result: JsonObject): String =
        requireNotNull(result["thread"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull) {
            "Codex response has no thread id"
        }
    fun parseTurnId(result: JsonObject): String =
        requireNotNull(result["turn"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull) {
            "Codex response has no turn id"
        }
    fun approvalResult(decision: ApprovalDecision): JsonObject = buildJsonObject {
        put("decision", if (decision == ApprovalDecision.ALLOW_ONCE) "accept" else "decline")
    }
    fun approvalResponse(requestId: JsonPrimitive, decision: ApprovalDecision): JsonObject {
        require(requestId != JsonNull && (requestId.isString || requestId.longOrNull != null))
        return buildJsonObject {
            put("id", requestId)
            put("result", approvalResult(decision))
        }
    }
    fun readEvent(line: String): CodexEvent? = readEvent(Json.parseToJsonElement(line).jsonObject)
    fun readEvent(json: JsonObject): CodexEvent? {
        val method = json["method"]?.jsonPrimitive?.content ?: return null
        val params = json["params"]?.jsonObject ?: return null
        fun required(key: String) = requireNotNull(params[key]?.jsonPrimitive?.contentOrNull) { "Missing $key" }
        return when (method) {
            "item/agentMessage/delta" -> CodexEvent.Delta(required("threadId"), required("delta"))
            "item/commandExecution/requestApproval" -> CodexEvent.CommandApproval(
                requireNotNull(json["id"]?.jsonPrimitive),
                required("threadId"), required("turnId"), required("itemId"),
                params["command"]?.jsonPrimitive?.contentOrNull,
                params["cwd"]?.jsonPrimitive?.contentOrNull,
            )
            "item/fileChange/requestApproval" -> CodexEvent.FileApproval(
                requireNotNull(json["id"]?.jsonPrimitive),
                required("threadId"), required("turnId"), required("itemId"),
                params["reason"]?.jsonPrimitive?.contentOrNull,
                params["grantRoot"]?.jsonPrimitive?.contentOrNull,
            )
            "turn/completed" -> CodexEvent.Completed(
                required("threadId"),
                params["turn"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull == "completed",
            )
            "error" -> CodexEvent.Failed(
                params["threadId"]?.jsonPrimitive?.contentOrNull,
                params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "CODEX_ERROR",
            )
            else -> null // Future/unknown events must not be guessed into approval requests.
        }
    }
}
sealed interface CodexEvent {
    data class Delta(val threadId: String, val text: String) : CodexEvent
    data class CommandApproval(
        val requestId: JsonPrimitive, val threadId: String, val turnId: String,
        val itemId: String, val command: String?, val cwd: String?,
    ) : CodexEvent
    data class FileApproval(
        val requestId: JsonPrimitive, val threadId: String, val turnId: String,
        val itemId: String, val reason: String?, val grantRoot: String?,
    ) : CodexEvent
    data class Completed(val threadId: String, val successful: Boolean) : CodexEvent
    data class Failed(val threadId: String?, val code: String) : CodexEvent
}
