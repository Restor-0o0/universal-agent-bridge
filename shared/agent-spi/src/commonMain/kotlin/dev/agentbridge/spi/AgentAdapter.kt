package dev.agentbridge.spi

import dev.agentbridge.domain.*
import kotlinx.coroutines.flow.Flow

enum class ManagedTransport { AGENT_CLIENT_PROTOCOL, JSON_RPC_STDIO, JSON_LINES_STDIO, LOCAL_HTTP_SSE }

data class AgentInstallation(
    val agentId: String,
    val version: String,
    val executable: String,
    val displayName: String = agentId,
    val transport: ManagedTransport? = null,
    val capabilities: Set<Capability> = emptySet(),
)

/** A JVM Host supplies providers and discovers them as a group. Users do not
 * need to configure one command or network bridge per supported harness. */
interface HarnessProvider {
    val agentId: String
    val displayName: String
    val executableNames: List<String>
    suspend fun probe(executable: String): AgentInstallation
    fun createAdapter(installation: AgentInstallation): AgentAdapter =
        error("$displayName session adapter is not implemented")
}
data class ManagedStart(val workingDirectory: String)
data class Attachment(val localPath: String, val mediaType: String)
sealed interface AgentEvent {
    data class MessageDelta(val sessionId: String, val text: String) : AgentEvent
    data class Approval(val request: ApprovalRequest) : AgentEvent
    data class TurnFinished(val sessionId: String, val successful: Boolean) : AgentEvent
    data class Failure(val sessionId: String?, val code: String) : AgentEvent
}

/** Implementations must reject unsupported operations; capability claims require contract tests. */
interface AgentAdapter {
    val agentId: String
    suspend fun detect(): AgentInstallation?
    suspend fun listSessions(): List<SessionDescriptor>
    fun capabilities(session: SessionDescriptor): Set<Capability>
    suspend fun startManaged(request: ManagedStart): SessionDescriptor
    suspend fun attach(sessionId: String): SessionDescriptor
    suspend fun resume(sessionId: String, workingDirectory: String? = null): SessionDescriptor
    suspend fun sendText(sessionId: String, text: String, idempotencyKey: String)
    suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>)
    fun streamEvents(sessionId: String): Flow<AgentEvent>
    suspend fun respondApproval(sessionId: String, approvalId: String, decision: ApprovalDecision)
    suspend fun interrupt(sessionId: String)
    suspend fun close(sessionId: String)
    suspend fun dispose()
}
