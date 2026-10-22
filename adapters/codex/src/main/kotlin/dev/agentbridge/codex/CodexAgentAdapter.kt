package dev.agentbridge.codex

import dev.agentbridge.acp.AcpConnectionFactory
import dev.agentbridge.acp.AcpPeer
import dev.agentbridge.acp.ProcessAcpConnectionFactory
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private data class PendingCodexApproval(
    val threadId: String,
    val rpcId: JsonPrimitive,
    val expiresAtMillis: Long,
)

/** Managed Codex app-server adapter. It never attaches to an arbitrary CLI PID. */
class CodexAgentAdapter(
    private val installation: AgentInstallation,
    private val connectionFactory: AcpConnectionFactory = ProcessAcpConnectionFactory,
    private val computerId: String = "local",
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AgentAdapter {
    override val agentId = "codex"

    private val lifecycle = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, SessionDescriptor>()
    private val eventChannels = ConcurrentHashMap<String, Channel<AgentEvent>>()
    private val activeTurns = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, PendingCodexApproval>()
    private val sentKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var peer: AcpPeer? = null

    init {
        require(installation.agentId == agentId)
    }

    override suspend fun detect(): AgentInstallation = installation

    override suspend fun listSessions(): List<SessionDescriptor> = sessions.values.sortedBy { it.id }

    override fun capabilities(session: SessionDescriptor): Set<Capability> {
        require(session.agentId == agentId)
        return session.capabilities
    }

    override suspend fun startManaged(request: ManagedStart): SessionDescriptor {
        val cwd = Path.of(request.workingDirectory).toAbsolutePath().normalize()
        require(Files.isDirectory(cwd)) { "Working directory does not exist" }
        val activePeer = ensureConnected(cwd)
        val result = activePeer.request("thread/start", CodexProtocol.threadStartParams(cwd.toString()))
        return registerSession(CodexProtocol.parseThreadId(result), cwd.fileName?.toString())
    }

    override suspend fun attach(sessionId: String): SessionDescriptor =
        error("Codex cannot attach to an arbitrary live process")

    override suspend fun resume(sessionId: String, workingDirectory: String?): SessionDescriptor {
        require(sessionId.isNotBlank())
        sessions[sessionId]?.let { return it }
        val cwd = workingDirectory?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: Path.of(".").toAbsolutePath().normalize()
        require(Files.isDirectory(cwd)) { "Working directory does not exist" }
        val activePeer = ensureConnected(cwd)
        val result = activePeer.request("thread/resume", CodexProtocol.threadResumeParams(sessionId))
        return registerSession(CodexProtocol.parseThreadId(result), "Codex ${sessionId.take(8)}")
    }

    override suspend fun sendText(sessionId: String, text: String, idempotencyKey: String) {
        requireSession(sessionId)
        require(text.isNotBlank() && idempotencyKey.isNotBlank())
        if (!sentKeys.add("$sessionId:$idempotencyKey")) return
        try {
            val result = requireNotNull(peer).request(
                "turn/start",
                CodexProtocol.turnStartParams(sessionId, text),
            )
            val turnId = CodexProtocol.parseTurnId(result)
            check(activeTurns.putIfAbsent(sessionId, turnId) == null) { "Codex thread already has an active turn" }
        } catch (failure: Throwable) {
            emitEvent(AgentEvent.Failure(sessionId, failure::class.simpleName ?: "CODEX_ERROR"))
            throw failure
        }
    }

    override suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>) {
        requireSession(sessionId)
        error("Codex attachment turns are not implemented")
    }

    override fun streamEvents(sessionId: String): Flow<AgentEvent> {
        requireSession(sessionId)
        return checkNotNull(eventChannels[sessionId]).receiveAsFlow()
    }

    override suspend fun respondApproval(
        sessionId: String,
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        requireSession(sessionId)
        val approval = checkNotNull(pending.remove(approvalId)) { "Approval unknown or already consumed" }
        check(approval.threadId == sessionId) { "Wrong session" }
        if (clockMillis() >= approval.expiresAtMillis) {
            requireNotNull(peer).respond(approval.rpcId, CodexProtocol.approvalResult(ApprovalDecision.DENY))
            error("Approval expired")
        }
        requireNotNull(peer).respond(approval.rpcId, CodexProtocol.approvalResult(decision))
    }

    override suspend fun interrupt(sessionId: String) {
        requireSession(sessionId)
        denyPending(sessionId)
        val turnId = activeTurns.remove(sessionId) ?: return
        requireNotNull(peer).request("turn/interrupt", CodexProtocol.turnInterruptParams(sessionId, turnId))
    }

    override suspend fun close(sessionId: String) {
        requireSession(sessionId)
        interrupt(sessionId)
        sessions.remove(sessionId)
        eventChannels.remove(sessionId)?.close()
        sentKeys.removeIf { it.startsWith("$sessionId:") }
    }

    override suspend fun dispose() {
        lifecycle.withLock {
            pending.values.toList().forEach { approval ->
                runCatching {
                    peer?.respond(approval.rpcId, CodexProtocol.approvalResult(ApprovalDecision.DENY))
                }
            }
            pending.clear()
            runCatching { peer?.shutdown() }
            peer = null
            sessions.clear()
            eventChannels.values.forEach { it.close() }
            eventChannels.clear()
            activeTurns.clear()
            sentKeys.clear()
            scope.cancel()
        }
    }

    private suspend fun ensureConnected(workingDirectory: Path): AcpPeer = lifecycle.withLock {
        peer?.let { return@withLock it }
        val newPeer = connectionFactory.start(
            installation.executable,
            listOf("app-server", "--listen", "stdio://"),
            workingDirectory,
        )
        try {
            newPeer.request("initialize", CodexProtocol.initializeParams())
            newPeer.notify("initialized", JsonObject(emptyMap()))
            peer = newPeer
            scope.launch { collectIncoming(newPeer) }
            newPeer
        } catch (failure: Throwable) {
            newPeer.shutdown()
            throw failure
        }
    }

    private fun registerSession(id: String, title: String?): SessionDescriptor {
        val descriptor = SessionDescriptor(
            id = id,
            computerId = computerId,
            agentId = agentId,
            title = title ?: id,
            mode = SessionMode.MANAGED_LIVE,
            capabilities = setOf(
                Capability.RESUME,
                Capability.APPROVAL,
                Capability.STREAMING,
                Capability.STOP,
            ),
        )
        val existing = sessions.putIfAbsent(id, descriptor)
        eventChannels.computeIfAbsent(id) { Channel(capacity = 64) }
        return existing ?: descriptor
    }

    private suspend fun collectIncoming(activePeer: AcpPeer) {
        activePeer.incoming.collect { message ->
            when (val event = CodexProtocol.readEvent(message)) {
                is CodexEvent.Delta -> emitEvent(AgentEvent.MessageDelta(event.threadId, event.text))
                is CodexEvent.Completed -> {
                    activeTurns.remove(event.threadId)
                    emitEvent(AgentEvent.TurnFinished(event.threadId, event.successful))
                }
                is CodexEvent.CommandApproval -> publishApproval(
                    event.requestId,
                    event.threadId,
                    event.command ?: "Command execution",
                    event.cwd.orEmpty(),
                )
                is CodexEvent.FileApproval -> publishApproval(
                    event.requestId,
                    event.threadId,
                    event.reason ?: "File change",
                    event.grantRoot.orEmpty(),
                )
                is CodexEvent.Failed -> emitEvent(AgentEvent.Failure(event.threadId, event.code))
                null -> Unit
            }
        }
    }

    private suspend fun publishApproval(
        rpcId: JsonPrimitive,
        threadId: String,
        command: String,
        cwd: String,
    ) {
        if (sessions[threadId] == null) {
            requireNotNull(peer).respond(rpcId, CodexProtocol.approvalResult(ApprovalDecision.DENY))
            return
        }
        val approvalId = "$threadId:${rpcId.content}"
        val expiresAtMillis = clockMillis() + APPROVAL_TTL_MILLIS
        check(
            pending.putIfAbsent(
                approvalId,
                PendingCodexApproval(threadId, rpcId, expiresAtMillis),
            ) == null,
        ) { "Duplicate Codex approval id" }
        emitEvent(
            AgentEvent.Approval(
                ApprovalRequest(
                    id = approvalId,
                    sessionId = threadId,
                    computerId = computerId,
                    command = command,
                    cwd = cwd,
                    expiresAtMillis = expiresAtMillis,
                ),
            ),
        )
    }

    private suspend fun denyPending(threadId: String) {
        pending.entries.filter { it.value.threadId == threadId }.forEach { entry ->
            if (pending.remove(entry.key, entry.value)) {
                requireNotNull(peer).respond(
                    entry.value.rpcId,
                    CodexProtocol.approvalResult(ApprovalDecision.DENY),
                )
            }
        }
    }

    private suspend fun emitEvent(event: AgentEvent) {
        val sessionId = when (event) {
            is AgentEvent.MessageDelta -> event.sessionId
            is AgentEvent.Approval -> event.request.sessionId
            is AgentEvent.TurnFinished -> event.sessionId
            is AgentEvent.Failure -> event.sessionId
        } ?: return
        eventChannels[sessionId]?.send(event)
    }

    private fun requireSession(sessionId: String): SessionDescriptor =
        checkNotNull(sessions[sessionId]) { "Unknown Codex session" }

    private companion object {
        const val APPROVAL_TTL_MILLIS = 5 * 60 * 1000L
    }
}
