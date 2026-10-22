package dev.agentbridge.host

import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.protocol.BridgePayload
import dev.agentbridge.protocol.BridgeJson
import dev.agentbridge.protocol.SavedAgentTab
import dev.agentbridge.protocol.WireAgent
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.ManagedStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.name

interface HostRelayLink {
    val incoming: Flow<BridgePayload>
    suspend fun send(payload: BridgePayload, sessionId: String = "control")
}

/** Normalized command router shared by every built-in harness provider. */
class ManagedAgentHost(
    private val registry: HarnessRegistry,
    private val link: HostRelayLink,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val defaultWorkingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    private val savedTabsPath: Path = Path.of(System.getProperty("user.home"), ".agentbridge", "tabs.json"),
    private val externalScanner: ExternalSessionScanner = ExternalSessionScanner(clockMillis = clockMillis),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapters = ConcurrentHashMap<String, AgentAdapter>()
    private val sessionAdapters = ConcurrentHashMap<String, AgentAdapter>()
    private val startedRequests = ConcurrentHashMap<String, BridgePayload.SessionStarted>()
    private val externalSources = ConcurrentHashMap<String, ExternalSessionSource>()
    private val externalWatchers = ConcurrentHashMap<String, Job>()
    private val savedTabsLock = Any()
    private val savedTabs = loadSavedTabs().associateByTo(LinkedHashMap(), SavedAgentTab::tabId)

    suspend fun run() {
        link.incoming.collect { payload ->
            // Catalog commands are cheap and ordered so a later list cannot overtake a save/delete.
            if (payload.isCatalogCommand()) handleSafely(payload)
            else scope.launch { handleSafely(payload) }
        }
    }

    suspend fun dispose() {
        adapters.values.toSet().forEach { runCatching { it.dispose() } }
        adapters.clear()
        sessionAdapters.clear()
        externalWatchers.values.forEach { it.cancel() }
        externalWatchers.clear()
        scope.cancel()
    }

    private suspend fun handleSafely(payload: BridgePayload) {
        runCatching { handle(payload) }.onFailure { failure ->
            link.send(
                BridgePayload.Failure(
                    requestId = payload.requestIdOrNull(),
                    sessionId = payload.sessionIdOrNull(),
                    code = (failure.message ?: failure::class.simpleName ?: "HOST_ERROR").take(240),
                ),
                payload.sessionIdOrNull() ?: "control",
            )
        }
    }

    private suspend fun handle(payload: BridgePayload) {
        when (payload) {
            is BridgePayload.ListExternalSessions -> listExternalSessions(payload.requestId)
            is BridgePayload.WatchExternalSession -> watchExternalSession(payload)
            is BridgePayload.StopWatchingExternalSession -> stopWatchingExternalSession(payload)
            is BridgePayload.ListSavedTabs -> sendSavedTabs(payload.requestId)
            is BridgePayload.SaveTab -> saveTab(payload)
            is BridgePayload.DeleteTab -> deleteTab(payload)
            is BridgePayload.ListAgents -> listAgents(payload)
            is BridgePayload.ListDirectories -> listDirectories(payload)
            is BridgePayload.StartSession -> startSession(payload)
            is BridgePayload.ResumeSession -> resumeSession(payload)
            is BridgePayload.CloseSession -> closeSession(payload)
            is BridgePayload.SendText -> {
                link.send(
                    BridgePayload.AgentActivity(
                        payload.sessionId,
                        "working",
                        "Agent is processing a message",
                        clockMillis(),
                    ),
                    payload.sessionId,
                )
                requireAdapter(payload.sessionId)
                    .sendText(payload.sessionId, payload.text, payload.idempotencyKey)
            }
            is BridgePayload.RespondApproval -> {
                requireAdapter(payload.sessionId).respondApproval(
                    payload.sessionId,
                    payload.approvalId,
                    if (payload.allow) ApprovalDecision.ALLOW_ONCE else ApprovalDecision.DENY,
                )
                link.send(
                    BridgePayload.AgentActivity(
                        payload.sessionId,
                        "working",
                        if (payload.allow) "Approval granted; agent resumed" else "Approval denied; agent resumed",
                        clockMillis(),
                    ),
                    payload.sessionId,
                )
            }
            is BridgePayload.Interrupt -> requireAdapter(payload.sessionId).interrupt(payload.sessionId)
            is BridgePayload.SavedTabList,
            is BridgePayload.ExternalSessionList,
            is BridgePayload.ExternalSessionSnapshot,
            is BridgePayload.AgentList,
            is BridgePayload.DirectoryList,
            is BridgePayload.SessionStarted,
            is BridgePayload.SessionClosed,
            is BridgePayload.TextDelta,
            is BridgePayload.ApprovalRequested,
            is BridgePayload.TurnFinished,
            is BridgePayload.AgentActivity,
            is BridgePayload.HelpNeeded,
            is BridgePayload.Failure -> error("Host received client-ineligible payload")
        }
    }

    private suspend fun listExternalSessions(requestId: String) {
        val sources = externalScanner.scan()
        externalSources.clear()
        sources.forEach { externalSources[externalKey(it.wire.agentId, it.wire.id)] = it }
        link.send(BridgePayload.ExternalSessionList(requestId, sources.map(ExternalSessionSource::wire)))
    }

    private suspend fun watchExternalSession(request: BridgePayload.WatchExternalSession) {
        val key = externalKey(request.agentId, request.sessionId)
        if (externalSources[key] == null) listExternalSessions(request.requestId)
        val source = checkNotNull(externalSources[key]) { "External session is no longer available" }
        externalWatchers.remove(key)?.cancel()
        val initial = externalScanner.refresh(source)
        externalSources[key] = initial
        link.send(
            BridgePayload.ExternalSessionSnapshot(
                requestId = request.requestId,
                session = initial.wire,
                transcript = externalScanner.transcript(initial),
            ),
            request.sessionId,
        )
        externalWatchers[key] = scope.launch {
            var lastModified = initial.wire.updatedAtMillis
            var lastActive = initial.wire.active
            while (true) {
                delay(1_000)
                val refreshed = externalScanner.refresh(source)
                if (refreshed.wire.updatedAtMillis != lastModified || refreshed.wire.active != lastActive) {
                    lastModified = refreshed.wire.updatedAtMillis
                    lastActive = refreshed.wire.active
                    externalSources[key] = refreshed
                    link.send(
                        BridgePayload.ExternalSessionSnapshot(
                            session = refreshed.wire,
                            transcript = externalScanner.transcript(refreshed),
                        ),
                        request.sessionId,
                    )
                }
            }
        }
    }

    private fun stopWatchingExternalSession(request: BridgePayload.StopWatchingExternalSession) {
        externalWatchers.remove(externalKey(request.agentId, request.sessionId))?.cancel()
    }

    private fun externalKey(agentId: String, sessionId: String) = "$agentId:$sessionId"

    private suspend fun sendSavedTabs(requestId: String) {
        val tabs = synchronized(savedTabsLock) { savedTabs.values.toList() }
        link.send(BridgePayload.SavedTabList(requestId, tabs))
    }

    private suspend fun saveTab(request: BridgePayload.SaveTab) {
        val tab = request.tab.copy(
            title = request.tab.title.trim().take(80).ifBlank { "Agent" },
            workingDirectory = request.tab.workingDirectory.trim(),
        )
        require(tab.tabId.isNotBlank() && tab.workingDirectory.isNotBlank()) { "Invalid saved tab" }
        synchronized(savedTabsLock) {
            require(savedTabs.containsKey(tab.tabId) || savedTabs.size < 100) { "Saved tab limit reached" }
            savedTabs[tab.tabId] = tab
            persistSavedTabs()
        }
        sendSavedTabs(request.requestId)
    }

    private suspend fun deleteTab(request: BridgePayload.DeleteTab) {
        require(request.tabId.isNotBlank()) { "Invalid tab id" }
        synchronized(savedTabsLock) {
            savedTabs.remove(request.tabId)
            persistSavedTabs()
        }
        sendSavedTabs(request.requestId)
    }

    private fun loadSavedTabs(): List<SavedAgentTab> = runCatching {
        if (!Files.exists(savedTabsPath)) emptyList()
        else BridgeJson.decodeFromString<List<SavedAgentTab>>(Files.readString(savedTabsPath))
    }.getOrDefault(emptyList()).take(100)

    private fun persistSavedTabs() {
        Files.createDirectories(savedTabsPath.parent)
        val temporary = savedTabsPath.resolveSibling("${savedTabsPath.fileName}.tmp")
        Files.writeString(temporary, BridgeJson.encodeToString(savedTabs.values.toList()))
        runCatching {
            Files.move(temporary, savedTabsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, savedTabsPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun closeSession(request: BridgePayload.CloseSession) {
        val adapter = checkNotNull(sessionAdapters.remove(request.sessionId)) { "Unknown managed session" }
        adapter.close(request.sessionId)
        link.send(BridgePayload.SessionClosed(request.requestId, request.sessionId), request.sessionId)
    }

    private suspend fun resumeSession(request: BridgePayload.ResumeSession) {
        startedRequests[request.requestId]?.let {
            link.send(it, it.sessionId)
            return
        }
        val installations = registry.discover().filterIsInstance<HarnessDiscovery.Available>()
            .map(HarnessDiscovery.Available::installation)
        val installation = selectInstallation(installations, request.agentId)
        val adapter = adapters.computeIfAbsent(installation.agentId) { registry.createAdapter(installation) }
        val resumed = runCatching { adapter.resume(request.sessionId, request.workingDirectory) }
        val session = resumed.getOrElse { adapter.startManaged(ManagedStart(request.workingDirectory)) }
        val existingAdapter = sessionAdapters.putIfAbsent(session.id, adapter)
        check(existingAdapter == null || existingAdapter === adapter) { "Duplicate managed session id" }
        publishStarted(
            requestId = request.requestId,
            session = session,
            adapter = adapter,
            resumed = resumed.isSuccess,
            collectEvents = existingAdapter == null,
        )
    }

    private suspend fun listDirectories(request: BridgePayload.ListDirectories) {
        val directory = (request.parent?.let(Path::of) ?: defaultWorkingDirectory)
            .toAbsolutePath().normalize()
        require(directory.isDirectory()) { "Directory does not exist: $directory" }
        val children = Files.list(directory).use { stream ->
            stream.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }
                .sorted(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() })
                .limit(200)
                .map { child -> dev.agentbridge.protocol.WireDirectory(child.name, child.toString()) }
                .toList()
        }
        link.send(
            BridgePayload.DirectoryList(
                requestId = request.requestId,
                current = directory.toString(),
                parent = directory.parent?.toString(),
                directories = children,
            ),
        )
    }

    private suspend fun listAgents(request: BridgePayload.ListAgents) {
        val results = registry.discover()
        link.send(
            BridgePayload.AgentList(
                request.requestId,
                results.map { result ->
                    when (result) {
                        is HarnessDiscovery.Available -> result.installation.toWireAgent()
                        is HarnessDiscovery.Unavailable -> WireAgent(
                            id = result.agentId,
                            name = result.displayName,
                            available = false,
                            detail = result.reason,
                        )
                    }
                },
            ),
        )
    }

    private suspend fun startSession(request: BridgePayload.StartSession) {
        startedRequests[request.requestId]?.let {
            link.send(it, it.sessionId)
            return
        }
        val installations = registry.discover().filterIsInstance<HarnessDiscovery.Available>()
            .map(HarnessDiscovery.Available::installation)
        val installation = selectInstallation(installations, request.preferredAgentId)
        val adapter = adapters.computeIfAbsent(installation.agentId) {
            registry.createAdapter(installation)
        }
        val session = adapter.startManaged(ManagedStart(request.workingDirectory))
        check(sessionAdapters.putIfAbsent(session.id, adapter) == null) { "Duplicate managed session id" }
        publishStarted(request.requestId, session, adapter, resumed = false)
    }

    private suspend fun publishStarted(
        requestId: String,
        session: dev.agentbridge.domain.SessionDescriptor,
        adapter: AgentAdapter,
        resumed: Boolean,
        collectEvents: Boolean = true,
    ) {
        val started = BridgePayload.SessionStarted(
            requestId = requestId,
            sessionId = session.id,
            agentId = session.agentId,
            title = session.title,
            capabilities = session.capabilities.mapTo(linkedSetOf()) { it.name },
            resumed = resumed,
        )
        startedRequests[requestId] = started
        link.send(started, session.id)
        link.send(
            BridgePayload.AgentActivity(
                session.id,
                "ready",
                if (resumed) "Managed session resumed" else "Managed session started",
                clockMillis(),
            ),
            session.id,
        )
        if (collectEvents) {
            scope.launch {
                adapter.streamEvents(session.id).collect { event ->
                    event.toPayloads().forEach { link.send(it, session.id) }
                }
            }
        }
    }

    private fun selectInstallation(
        installations: List<AgentInstallation>,
        preferredAgentId: String?,
    ): AgentInstallation {
        require(installations.isNotEmpty()) { "No supported agent CLI is available" }
        if (preferredAgentId != null) {
            return installations.singleOrNull { it.agentId == preferredAgentId }
                ?: error("Requested agent is unavailable: $preferredAgentId")
        }
        val priority = listOf("codex", "claude", "opencode")
        return installations.minBy { priority.indexOf(it.agentId).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    }

    private fun requireAdapter(sessionId: String): AgentAdapter =
        checkNotNull(sessionAdapters[sessionId]) { "Unknown managed session" }

    private fun AgentInstallation.toWireAgent() = WireAgent(
        id = agentId,
        name = displayName,
        version = version,
        available = true,
        capabilities = capabilities.mapTo(linkedSetOf()) { it.name },
    )

    private fun AgentEvent.toPayloads(): List<BridgePayload> = when (this) {
        is AgentEvent.MessageDelta -> listOf(BridgePayload.TextDelta(sessionId, text))
        is AgentEvent.Approval -> listOf(
            BridgePayload.ApprovalRequested(
                sessionId = request.sessionId,
                approvalId = request.id,
                command = request.command,
                cwd = request.cwd,
                expiresAtMillis = request.expiresAtMillis,
            ),
            BridgePayload.HelpNeeded(
                helpId = request.id,
                sessionId = request.sessionId,
                reason = request.command,
                expiresAtMillis = request.expiresAtMillis,
            ),
            BridgePayload.AgentActivity(
                request.sessionId,
                "waiting_for_approval",
                request.command,
                clockMillis(),
            ),
        )
        is AgentEvent.TurnFinished -> listOf(
            BridgePayload.TurnFinished(sessionId, successful),
            BridgePayload.AgentActivity(
                sessionId,
                if (successful) "idle" else "failed",
                if (successful) "Turn completed" else "Turn failed",
                clockMillis(),
            ),
        )
        is AgentEvent.Failure -> listOf(
            BridgePayload.Failure(sessionId = sessionId, code = code),
            BridgePayload.AgentActivity(sessionId ?: "control", "failed", code, clockMillis()),
        )
    }

    private fun BridgePayload.requestIdOrNull(): String? = when (this) {
        is BridgePayload.ListExternalSessions -> requestId
        is BridgePayload.ExternalSessionList -> requestId
        is BridgePayload.WatchExternalSession -> requestId
        is BridgePayload.ExternalSessionSnapshot -> requestId
        is BridgePayload.StopWatchingExternalSession -> requestId
        is BridgePayload.ListSavedTabs -> requestId
        is BridgePayload.SavedTabList -> requestId
        is BridgePayload.SaveTab -> requestId
        is BridgePayload.DeleteTab -> requestId
        is BridgePayload.ListAgents -> requestId
        is BridgePayload.AgentList -> requestId
        is BridgePayload.ListDirectories -> requestId
        is BridgePayload.DirectoryList -> requestId
        is BridgePayload.StartSession -> requestId
        is BridgePayload.ResumeSession -> requestId
        is BridgePayload.SessionStarted -> requestId
        is BridgePayload.CloseSession -> requestId
        is BridgePayload.SessionClosed -> requestId
        is BridgePayload.SendText -> requestId
        is BridgePayload.RespondApproval -> requestId
        is BridgePayload.Interrupt -> requestId
        is BridgePayload.Failure -> requestId
        else -> null
    }

    private fun BridgePayload.sessionIdOrNull(): String? = when (this) {
        is BridgePayload.WatchExternalSession -> sessionId
        is BridgePayload.ExternalSessionSnapshot -> session.id
        is BridgePayload.StopWatchingExternalSession -> sessionId
        is BridgePayload.SessionStarted -> sessionId
        is BridgePayload.ResumeSession -> sessionId
        is BridgePayload.CloseSession -> sessionId
        is BridgePayload.SessionClosed -> sessionId
        is BridgePayload.SendText -> sessionId
        is BridgePayload.TextDelta -> sessionId
        is BridgePayload.ApprovalRequested -> sessionId
        is BridgePayload.RespondApproval -> sessionId
        is BridgePayload.Interrupt -> sessionId
        is BridgePayload.TurnFinished -> sessionId
        is BridgePayload.AgentActivity -> sessionId
        is BridgePayload.HelpNeeded -> sessionId
        is BridgePayload.Failure -> sessionId
        else -> null
    }

    private fun BridgePayload.isCatalogCommand(): Boolean =
        this is BridgePayload.ListSavedTabs || this is BridgePayload.SaveTab ||
            this is BridgePayload.DeleteTab || this is BridgePayload.ListExternalSessions
}
