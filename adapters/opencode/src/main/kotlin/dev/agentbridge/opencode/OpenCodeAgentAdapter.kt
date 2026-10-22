package dev.agentbridge.opencode

import dev.agentbridge.acp.AcpAgentInfo
import dev.agentbridge.acp.AcpConnectionFactory
import dev.agentbridge.acp.AcpInbound
import dev.agentbridge.acp.AcpInboundCodec
import dev.agentbridge.acp.AcpPeer
import dev.agentbridge.acp.AcpPermissionOption
import dev.agentbridge.acp.AcpProtocol
import dev.agentbridge.acp.AcpSessionClient
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private data class PendingPermission(
    val sessionId: String,
    val rpcId: JsonElement,
    val options: List<AcpPermissionOption>,
    val expiresAtMillis: Long,
)

/** Managed OpenCode sessions over its native ACP subprocess. */
class OpenCodeAgentAdapter(
    private val installation: AgentInstallation,
    private val connectionFactory: AcpConnectionFactory = ProcessAcpConnectionFactory,
    private val computerId: String = "local",
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AgentAdapter {
    override val agentId = "opencode"

    private val lifecycle = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventChannels = ConcurrentHashMap<String, Channel<AgentEvent>>()
    private val sessions = ConcurrentHashMap<String, SessionDescriptor>()
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val sentKeys = ConcurrentHashMap.newKeySet<String>()
    private val creatingSessions = AtomicInteger()

    @Volatile private var peer: AcpPeer? = null
    @Volatile private var agentInfo: AcpAgentInfo? = null
    @Volatile private var sessionClient: AcpSessionClient? = null

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
        val workingDirectory = Path.of(request.workingDirectory).toAbsolutePath().normalize()
        require(Files.isDirectory(workingDirectory)) { "Working directory does not exist" }
        val client = ensureConnected(workingDirectory)
        creatingSessions.incrementAndGet()
        try {
            val id = client.newSession(workingDirectory)
            val descriptor = SessionDescriptor(
                id = id,
                computerId = computerId,
                agentId = agentId,
                title = workingDirectory.fileName?.toString() ?: id,
                mode = SessionMode.MANAGED_LIVE,
                capabilities = negotiatedCapabilities(),
            )
            check(sessions.putIfAbsent(id, descriptor) == null) { "Duplicate ACP session id" }
            eventChannels.computeIfAbsent(id) { Channel(capacity = 64) }
            return descriptor
        } finally {
            creatingSessions.decrementAndGet()
        }
    }

    override suspend fun attach(sessionId: String): SessionDescriptor =
        error("OpenCode ACP cannot attach to an arbitrary live process")

    override suspend fun resume(sessionId: String, workingDirectory: String?): SessionDescriptor {
        require(sessionId.isNotBlank())
        sessions[sessionId]?.let { return it }
        val cwd = workingDirectory?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: error("OpenCode resume requires a working directory")
        require(Files.isDirectory(cwd)) { "Working directory does not exist" }
        val client = ensureConnected(cwd)
        val info = requireNotNull(agentInfo)
        check(info.supportsResume) { "OpenCode ACP does not support session resume" }
        client.resumeSession(sessionId, cwd)
        val descriptor = SessionDescriptor(
            sessionId, computerId, agentId, cwd.fileName?.toString() ?: sessionId,
            SessionMode.MANAGED_LIVE, negotiatedCapabilities(),
        )
        sessions[sessionId] = descriptor
        eventChannels.computeIfAbsent(sessionId) { Channel(capacity = 64) }
        return descriptor
    }

    override suspend fun sendText(sessionId: String, text: String, idempotencyKey: String) {
        requireSession(sessionId)
        require(idempotencyKey.isNotBlank())
        if (!sentKeys.add("$sessionId:$idempotencyKey")) return
        try {
            val info = requireNotNull(agentInfo)
            val result = requireNotNull(sessionClient).promptText(sessionId, text)
            if (info.protocolVersion == 1) {
                emitEvent(
                    AgentEvent.TurnFinished(
                        sessionId,
                        result.stopReason == "end_turn" || result.stopReason == "stop_sequence",
                    ),
                )
            }
        } catch (failure: Throwable) {
            emitEvent(AgentEvent.Failure(sessionId, failure::class.simpleName ?: "ACP_ERROR"))
            throw failure
        }
    }

    override suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>) {
        requireSession(sessionId)
        error("OpenCode attachment mapping is not implemented")
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
        val request = checkNotNull(pending.remove(approvalId)) { "Approval unknown or already consumed" }
        check(request.sessionId == sessionId) { "Wrong session" }
        if (clockMillis() >= request.expiresAtMillis) {
            requireNotNull(peer).respond(request.rpcId, AcpProtocol.cancelledPermissionResponse())
            error("Approval expired")
        }
        val acceptedKinds = when (decision) {
            ApprovalDecision.ALLOW_ONCE -> listOf("allow_once")
            ApprovalDecision.DENY -> listOf("reject_once", "reject_always")
        }
        val option = request.options.firstOrNull { it.kind in acceptedKinds }
        if (option == null) {
            requireNotNull(peer).respond(request.rpcId, AcpProtocol.cancelledPermissionResponse())
            error("ACP agent did not offer a safe ${decision.name.lowercase()} option")
        }
        requireNotNull(peer).respond(request.rpcId, AcpProtocol.permissionResponse(option.optionId))
    }

    override suspend fun interrupt(sessionId: String) {
        requireSession(sessionId)
        cancelPendingPermissions(sessionId)
        requireNotNull(sessionClient).cancel(sessionId)
    }

    override suspend fun close(sessionId: String) {
        requireSession(sessionId)
        cancelPendingPermissions(sessionId)
        val info = requireNotNull(agentInfo)
        if (info.protocolVersion >= 2) requireNotNull(sessionClient).close(sessionId)
        else requireNotNull(sessionClient).cancel(sessionId)
        sessions.remove(sessionId)
        eventChannels.remove(sessionId)?.close()
        sentKeys.removeIf { it.startsWith("$sessionId:") }
    }

    override suspend fun dispose() {
        lifecycle.withLock {
            pending.values.toList().forEach { request ->
                runCatching { peer?.respond(request.rpcId, AcpProtocol.cancelledPermissionResponse()) }
            }
            pending.clear()
            runCatching { peer?.shutdown() }
            peer = null
            sessionClient = null
            agentInfo = null
            sessions.clear()
            sentKeys.clear()
            eventChannels.values.forEach { it.close() }
            eventChannels.clear()
            scope.cancel()
        }
    }

    private suspend fun ensureConnected(workingDirectory: Path): AcpSessionClient = lifecycle.withLock {
        sessionClient?.let { return@withLock it }
        val newPeer = connectionFactory.start(installation.executable, listOf("acp"), workingDirectory)
        try {
            val info = AcpProtocol.parseInitializeResult(
                newPeer.request("initialize", AcpProtocol.initializeParams()),
            )
            check(info.supportsSessions) { "ACP agent does not support sessions" }
            val client = AcpSessionClient(newPeer, info)
            peer = newPeer
            agentInfo = info
            sessionClient = client
            scope.launch { collectIncoming(newPeer, info) }
            client
        } catch (failure: Throwable) {
            newPeer.shutdown()
            throw failure
        }
    }

    private suspend fun collectIncoming(activePeer: AcpPeer, info: AcpAgentInfo) {
        activePeer.incoming.collect { message ->
            when (val event = AcpInboundCodec.decode(message, info.protocolVersion)) {
                is AcpInbound.MessageChunk -> emitEvent(AgentEvent.MessageDelta(event.sessionId, event.text))
                is AcpInbound.TurnFinished -> emitEvent(
                    AgentEvent.TurnFinished(
                        event.sessionId,
                        event.stopReason in setOf("end_turn", "stop_sequence"),
                    ),
                )
                is AcpInbound.PermissionRequest -> publishPermission(event)
                null -> Unit
            }
        }
    }

    private suspend fun publishPermission(event: AcpInbound.PermissionRequest) {
        if (sessions[event.sessionId] == null && creatingSessions.get() == 0) {
            requireNotNull(peer).respond(event.rpcId, AcpProtocol.cancelledPermissionResponse())
            return
        }
        val rpcKey = event.rpcId.jsonPrimitive.contentOrNull
            ?: error("ACP permission request has invalid RPC id")
        val approvalId = "${event.sessionId}:$rpcKey"
        val expiresAtMillis = clockMillis() + APPROVAL_TTL_MILLIS
        check(
            pending.putIfAbsent(
                approvalId,
                PendingPermission(event.sessionId, event.rpcId, event.options, expiresAtMillis),
            ) == null,
        ) {
            "Duplicate ACP approval id"
        }
        emitEvent(
            AgentEvent.Approval(
                ApprovalRequest(
                    id = approvalId,
                    sessionId = event.sessionId,
                    computerId = computerId,
                    command = event.command ?: event.title,
                    cwd = event.cwd.orEmpty(),
                    expiresAtMillis = expiresAtMillis,
                ),
            ),
        )
    }

    private suspend fun cancelPendingPermissions(sessionId: String) {
        pending.entries.filter { it.value.sessionId == sessionId }.forEach { entry ->
            if (pending.remove(entry.key, entry.value)) {
                requireNotNull(peer).respond(entry.value.rpcId, AcpProtocol.cancelledPermissionResponse())
            }
        }
    }

    private fun negotiatedCapabilities(): Set<Capability> = buildSet {
        add(Capability.APPROVAL)
        add(Capability.STREAMING)
        add(Capability.STOP)
        if (agentInfo?.supportsImages == true) add(Capability.IMAGE_INPUT)
    }

    private fun requireSession(sessionId: String): SessionDescriptor =
        checkNotNull(sessions[sessionId]) { "Unknown OpenCode session" }

    private suspend fun emitEvent(event: AgentEvent) {
        val sessionId = when (event) {
            is AgentEvent.MessageDelta -> event.sessionId
            is AgentEvent.Approval -> event.request.sessionId
            is AgentEvent.TurnFinished -> event.sessionId
            is AgentEvent.Failure -> event.sessionId
        } ?: return
        val channel = eventChannels[sessionId]
            ?: if (sessions[sessionId] != null || creatingSessions.get() > 0) {
                eventChannels.computeIfAbsent(sessionId) { Channel(capacity = 64) }
            } else null
        channel?.send(event)
    }

    private companion object {
        const val APPROVAL_TTL_MILLIS = 5 * 60 * 1000L
    }
}
