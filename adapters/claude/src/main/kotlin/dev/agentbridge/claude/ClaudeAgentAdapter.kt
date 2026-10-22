package dev.agentbridge.claude

import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.domain.ApprovalRequest
import dev.agentbridge.domain.Capability
import dev.agentbridge.domain.SessionDescriptor
import dev.agentbridge.domain.SessionMode
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.Attachment
import dev.agentbridge.spi.ManagedStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private data class ClaudeSession(
    val descriptor: SessionDescriptor,
    val peer: ClaudePeer,
    val events: Channel<AgentEvent>,
)

private data class PendingClaudeApproval(
    val sessionId: String,
    val requestId: String,
    val input: JsonObject,
    val expiresAtMillis: Long,
)

class ClaudeAgentAdapter(
    private val installation: AgentInstallation,
    private val connectionFactory: ClaudeConnectionFactory = ProcessClaudeConnectionFactory,
    private val computerId: String = "local",
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : AgentAdapter {
    override val agentId = "claude"

    private val lifecycle = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ClaudeSession>()
    private val pending = ConcurrentHashMap<String, PendingClaudeApproval>()
    private val sentKeys = ConcurrentHashMap.newKeySet<String>()
    private val requestIds = AtomicLong(1)

    init {
        require(installation.agentId == agentId)
    }

    override suspend fun detect(): AgentInstallation = installation

    override suspend fun listSessions(): List<SessionDescriptor> =
        sessions.values.map { it.descriptor }.sortedBy { it.id }

    override fun capabilities(session: SessionDescriptor): Set<Capability> {
        require(session.agentId == agentId)
        return session.capabilities
    }

    override suspend fun startManaged(request: ManagedStart): SessionDescriptor {
        val cwd = validateDirectory(request.workingDirectory)
        return openSession(idFactory(), cwd, resume = false)
    }

    override suspend fun attach(sessionId: String): SessionDescriptor =
        error("Claude cannot attach to an arbitrary live process")

    override suspend fun resume(sessionId: String, workingDirectory: String?): SessionDescriptor {
        require(sessionId.isNotBlank())
        sessions[sessionId]?.let { return it.descriptor }
        val cwd = workingDirectory?.let(::validateDirectory) ?: Path.of(".").toAbsolutePath().normalize()
        return openSession(sessionId, cwd, resume = true)
    }

    override suspend fun sendText(sessionId: String, text: String, idempotencyKey: String) {
        val session = requireSession(sessionId)
        require(text.isNotBlank() && idempotencyKey.isNotBlank())
        if (!sentKeys.add("$sessionId:$idempotencyKey")) return
        try {
            session.peer.send(ClaudeProtocol.userMessage(text))
        } catch (failure: Throwable) {
            emit(sessionId, AgentEvent.Failure(sessionId, failure::class.simpleName ?: "CLAUDE_ERROR"))
            throw failure
        }
    }

    override suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>) {
        requireSession(sessionId)
        error("Claude stream-json currently accepts text user messages only")
    }

    override fun streamEvents(sessionId: String): Flow<AgentEvent> =
        requireSession(sessionId).events.receiveAsFlow()

    override suspend fun respondApproval(
        sessionId: String,
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        val session = requireSession(sessionId)
        val approval = checkNotNull(pending.remove(approvalId)) { "Approval unknown or already consumed" }
        check(approval.sessionId == sessionId) { "Wrong session" }
        if (clockMillis() >= approval.expiresAtMillis) {
            session.peer.send(ClaudeProtocol.permissionResponse(approval.requestId, approval.input, ApprovalDecision.DENY))
            error("Approval expired")
        }
        session.peer.send(ClaudeProtocol.permissionResponse(approval.requestId, approval.input, decision))
    }

    override suspend fun interrupt(sessionId: String) {
        val session = requireSession(sessionId)
        denyPending(sessionId, session.peer)
        session.peer.send(ClaudeProtocol.interrupt("bridge-interrupt-${requestIds.getAndIncrement()}"))
    }

    override suspend fun close(sessionId: String) {
        val session = requireSession(sessionId)
        denyPending(sessionId, session.peer)
        sessions.remove(sessionId)
        sentKeys.removeIf { it.startsWith("$sessionId:") }
        session.peer.shutdown()
        session.events.close()
    }

    override suspend fun dispose() = lifecycle.withLock {
        sessions.values.toList().forEach { session ->
            runCatching { denyPending(session.descriptor.id, session.peer) }
            runCatching { session.peer.shutdown() }
            session.events.close()
        }
        sessions.clear()
        pending.clear()
        sentKeys.clear()
        scope.cancel()
    }

    private suspend fun openSession(sessionId: String, cwd: Path, resume: Boolean): SessionDescriptor =
        lifecycle.withLock {
            sessions[sessionId]?.let { return@withLock it.descriptor }
            val peer = connectionFactory.start(
                installation.executable,
                ClaudeProtocol.launchArguments(sessionId, resume),
                cwd,
            )
            val descriptor = SessionDescriptor(
                id = sessionId,
                computerId = computerId,
                agentId = agentId,
                title = cwd.fileName?.toString() ?: sessionId,
                mode = SessionMode.MANAGED_LIVE,
                capabilities = setOf(
                    Capability.RESUME,
                    Capability.APPROVAL,
                    Capability.STREAMING,
                    Capability.STOP,
                ),
            )
            val session = ClaudeSession(descriptor, peer, Channel(capacity = 64))
            check(sessions.putIfAbsent(sessionId, session) == null) { "Duplicate Claude session id" }
            scope.launch { collectIncoming(session) }
            descriptor
        }

    private suspend fun collectIncoming(session: ClaudeSession) {
        runCatching {
            session.peer.incoming.collect { message ->
                when (val event = ClaudeProtocol.decode(message)) {
                    is ClaudeInbound.Delta -> emit(
                        session.descriptor.id,
                        AgentEvent.MessageDelta(session.descriptor.id, event.text),
                    )
                    is ClaudeInbound.Result -> if (event.successful) {
                        emit(session.descriptor.id, AgentEvent.TurnFinished(session.descriptor.id, true))
                    } else {
                        emit(session.descriptor.id, AgentEvent.Failure(session.descriptor.id, event.code))
                        emit(session.descriptor.id, AgentEvent.TurnFinished(session.descriptor.id, false))
                    }
                    is ClaudeInbound.Permission -> publishApproval(session, event)
                    null -> Unit
                }
            }
        }.onFailure { failure ->
            if (sessions[session.descriptor.id] === session) {
                emit(
                    session.descriptor.id,
                    AgentEvent.Failure(session.descriptor.id, failure::class.simpleName ?: "CLAUDE_STREAM_ERROR"),
                )
            }
        }
    }

    private suspend fun publishApproval(session: ClaudeSession, event: ClaudeInbound.Permission) {
        val id = "${session.descriptor.id}:${event.requestId}"
        val expiresAt = clockMillis() + APPROVAL_TTL_MILLIS
        check(
            pending.putIfAbsent(
                id,
                PendingClaudeApproval(session.descriptor.id, event.requestId, event.input, expiresAt),
            ) == null,
        ) { "Duplicate Claude approval id" }
        val details = listOfNotNull(
            (event.input["command"] as? JsonPrimitive)?.contentOrNull,
            (event.input["file_path"] as? JsonPrimitive)?.contentOrNull,
            event.description,
        ).firstOrNull().orEmpty()
        emit(
            session.descriptor.id,
            AgentEvent.Approval(
                ApprovalRequest(
                    id = id,
                    sessionId = session.descriptor.id,
                    computerId = computerId,
                    command = event.title ?: "${event.toolName}: $details".trimEnd(),
                    cwd = (event.input["cwd"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    expiresAtMillis = expiresAt,
                ),
            ),
        )
    }

    private suspend fun denyPending(sessionId: String, peer: ClaudePeer) {
        pending.entries.filter { it.value.sessionId == sessionId }.forEach { entry ->
            if (pending.remove(entry.key, entry.value)) {
                peer.send(
                    ClaudeProtocol.permissionResponse(
                        entry.value.requestId,
                        entry.value.input,
                        ApprovalDecision.DENY,
                    ),
                )
            }
        }
    }

    private suspend fun emit(sessionId: String, event: AgentEvent) {
        sessions[sessionId]?.events?.send(event)
    }

    private fun requireSession(sessionId: String): ClaudeSession =
        checkNotNull(sessions[sessionId]) { "Unknown Claude session" }

    private fun validateDirectory(value: String): Path = Path.of(value).toAbsolutePath().normalize().also {
        require(Files.isDirectory(it)) { "Working directory does not exist" }
    }

    private companion object {
        const val APPROVAL_TTL_MILLIS = 5 * 60 * 1000L
    }
}
