package dev.agentbridge.desktop

import dev.agentbridge.client.EncryptedRelayConnection
import dev.agentbridge.crypto.DevelopmentPresharedCipher
import dev.agentbridge.protocol.*
import dev.agentbridge.ui.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

class DesktopRemoteBridge(private val http: HttpClient, private val token: String, keyBase64Url: String) : RemoteBridgeActions, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = Base64.getUrlDecoder().decode(keyBase64Url)
    private val preferences = Preferences.userNodeForPackage(DesktopRemoteBridge::class.java)
    private val mutableState = MutableStateFlow(RemoteBridgeState(sessions = loadTabs()))
    private val pendingStarts = ConcurrentHashMap<String, String>()
    private val pendingDeletes = ConcurrentHashMap.newKeySet<String>()
    private val pendingClears = ConcurrentHashMap.newKeySet<String>()
    private val autoApprovals = ConcurrentHashMap.newKeySet<String>()
    private var connection: EncryptedRelayConnection? = null
    override val state: StateFlow<RemoteBridgeState> = mutableState.asStateFlow()

    override fun connect(relayUrl: String, bearerToken: String?, keyBase64Url: String?) {
        if (connection != null) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching { EncryptedRelayConnection(http, relayUrl, token, "desktop", DevelopmentPresharedCipher(key)).also { it.connect() } }
                .onSuccess { connected ->
                    connection = connected
                    mutableState.update { it.copy(connected = true, busy = false) }
                    scope.launch { collectIncoming(connected) }
                    migrateLocalTabs(connected)
                    connected.send("host", "control", BridgePayload.ListSavedTabs(UUID.randomUUID().toString()))
                    connected.send("host", "control", BridgePayload.ListAgents(UUID.randomUUID().toString()))
                    connected.send("host", "control", BridgePayload.ListDirectories(UUID.randomUUID().toString()))
                    connected.send("host", "control", BridgePayload.ListExternalSessions(UUID.randomUUID().toString()))
                }.onFailure(::fail)
        }
    }

    override fun startSession(workingDirectory: String, preferredAgentId: String?, title: String?) {
        if (mutableState.value.sessions.size >= MAX_SAVED_TABS) return fail(IllegalStateException("Лимит сохранённых вкладок: $MAX_SAVED_TABS"))
        val tabId = UUID.randomUUID().toString()
        mutableState.update { it.copy(
            sessions = it.sessions + RemoteSessionView(
                tabId = tabId,
                title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
                    ?: workingDirectory.substringAfterLast('\\').substringAfterLast('/').ifBlank { preferredAgentId ?: "Agent" },
                workingDirectory = workingDirectory,
                requestedAgentId = preferredAgentId,
                busy = false,
            ),
            activeTabId = tabId,
            error = null,
        ) }
        persistTabs()
        syncTab(tabId)
        startTab(tabId)
    }

    override fun selectSession(tabId: String?) {
        mutableState.update { it.copy(activeTabId = tabId) }
        if (tabId != null) startTab(tabId)
    }

    private fun startTab(tabId: String) {
        val state = mutableState.value
        val tab = state.sessions.firstOrNull { it.tabId == tabId } ?: return
        if (!state.connected || tab.sessionId != null || tab.busy || tab.closing) return
        if (state.liveSessionCount >= MAX_LIVE_SESSIONS) return fail(IllegalStateException("Одновременно могут работать $MAX_LIVE_SESSIONS агентов"))
        val requestId = UUID.randomUUID().toString()
        pendingStarts[requestId] = tabId
        mutableState.update { it.updateTab(tabId) { current -> current.copy(busy = true, error = null) } }
        val agentId = tab.agentId ?: tab.requestedAgentId
        val previous = tab.lastSessionId
        if (previous != null && agentId != null) {
            send("control", BridgePayload.ResumeSession(requestId, previous, agentId, tab.workingDirectory))
        } else {
            send("control", BridgePayload.StartSession(requestId, tab.workingDirectory, agentId))
        }
    }

    override fun closeSession(tabId: String) {
        val tab = mutableState.value.sessions.firstOrNull { it.tabId == tabId } ?: return
        val sessionId = tab.sessionId ?: return
        mutableState.update { it.updateTab(tabId) { current -> current.copy(closing = true) } }
        send(sessionId, BridgePayload.CloseSession(UUID.randomUUID().toString(), sessionId))
    }

    override fun deleteSession(tabId: String) {
        val tab = mutableState.value.sessions.firstOrNull { it.tabId == tabId } ?: return
        if (tab.sessionId == null) removeTab(tabId) else {
            pendingDeletes += tabId
            closeSession(tabId)
        }
    }

    override fun renameSession(tabId: String, title: String) {
        mutableState.update { it.updateTab(tabId) { current -> current.copy(title = title.take(80)) } }
        persistTabs()
        syncTab(tabId)
    }

    override fun setSessionAutoMode(tabId: String, enabled: Boolean) {
        mutableState.update { it.updateTab(tabId) { current -> current.copy(autoMode = enabled) } }
        persistTabs()
        syncTab(tabId)
    }

    override fun clearSession(tabId: String) {
        val tab = mutableState.value.sessions.firstOrNull { it.tabId == tabId } ?: return
        mutableState.update { it.updateTab(tabId) { current -> current.copy(
            lastSessionId = null, transcript = "", activity = emptyList(), approvals = emptyList(),
            helpNeeded = null, error = null,
        ) } }
        persistTabs()
        syncTab(tabId)
        if (tab.sessionId == null) startTab(tabId) else {
            pendingClears += tabId
            closeSession(tabId)
        }
    }

    override fun browseDirectories(parent: String?) = send("control", BridgePayload.ListDirectories(UUID.randomUUID().toString(), parent))
    override fun refreshExternalSessions() =
        send("control", BridgePayload.ListExternalSessions(UUID.randomUUID().toString()))

    override fun watchExternalSession(session: WireExternalSession) {
        mutableState.update { it.copy(watchedExternalSession = session, externalTranscript = "", error = null) }
        send(
            "control",
            BridgePayload.WatchExternalSession(UUID.randomUUID().toString(), session.agentId, session.id),
        )
    }

    override fun stopWatchingExternalSession() {
        val session = mutableState.value.watchedExternalSession ?: return
        send(
            "control",
            BridgePayload.StopWatchingExternalSession(UUID.randomUUID().toString(), session.agentId, session.id),
        )
        mutableState.update { it.copy(watchedExternalSession = null, externalTranscript = "") }
    }

    override fun resumeExternalSession(session: WireExternalSession) {
        if (session.active) return fail(IllegalStateException("Дождитесь завершения текущего шага агента"))
        if (session.workingDirectory.isBlank()) return fail(IllegalStateException("Рабочая папка сессии неизвестна"))
        if (mutableState.value.sessions.size >= MAX_SAVED_TABS) return fail(IllegalStateException("Лимит сохранённых вкладок: $MAX_SAVED_TABS"))
        val tabId = UUID.randomUUID().toString()
        mutableState.update { state -> state.copy(
            sessions = state.sessions + RemoteSessionView(
                tabId = tabId,
                lastSessionId = session.id,
                title = session.title,
                workingDirectory = session.workingDirectory,
                requestedAgentId = session.agentId,
                agentId = session.agentId,
                busy = false,
            ),
            activeTabId = tabId,
            watchedExternalSession = null,
            externalTranscript = "",
            error = null,
        ) }
        persistTabs()
        syncTab(tabId)
        startTab(tabId)
    }
    override fun sendText(text: String) {
        val tab = mutableState.value.activeSession ?: return
        val sessionId = tab.sessionId ?: return
        mutableState.update { it.updateTab(tab.tabId) { current -> current.copy(busy = true, error = null) } }
        send(sessionId, BridgePayload.SendText(UUID.randomUUID().toString(), sessionId, text, UUID.randomUUID().toString()))
    }
    override fun respondApproval(approvalId: String, allow: Boolean) {
        val tab = mutableState.value.activeSession ?: return
        val sessionId = tab.sessionId ?: return
        mutableState.update { it.updateTab(tab.tabId) { current -> current.copy(approvals = current.approvals.filterNot { approval -> approval.id == approvalId }, helpNeeded = null) } }
        send(sessionId, BridgePayload.RespondApproval(UUID.randomUUID().toString(), sessionId, approvalId, allow))
    }
    override fun interrupt() {
        val sessionId = mutableState.value.activeSession?.sessionId ?: return
        send(sessionId, BridgePayload.Interrupt(UUID.randomUUID().toString(), sessionId))
    }
    override fun setWakeAlarmEnabled(enabled: Boolean) = mutableState.update { it.copy(wakeAlarmEnabled = enabled) }

    private fun send(sessionId: String, payload: BridgePayload) {
        val active = connection ?: return fail(IllegalStateException("Dev bridge is not connected"))
        scope.launch { runCatching { active.send("host", sessionId, payload) }.onFailure(::fail) }
    }

    private suspend fun collectIncoming(active: EncryptedRelayConnection) {
        val failure = runCatching { active.incoming.collect(::reduce) }.exceptionOrNull()
        if (connection === active) {
            connection = null
            pendingStarts.clear()
            mutableState.update { state -> state.copy(
                connected = false,
                busy = false,
                sessions = state.sessions.map { it.copy(sessionId = null, busy = false, closing = false, approvals = emptyList()) },
                watchedExternalSession = null,
                externalTranscript = "",
            ) }
        }
        failure?.let(::fail)
    }

    private fun reduce(payload: BridgePayload) {
        var restartTabId: String? = null
        if (payload is BridgePayload.ApprovalRequested) {
            val tab = mutableState.value.sessions.firstOrNull { it.sessionId == payload.sessionId }
            if (tab?.autoMode == true) {
                autoApprovals += payload.approvalId
                send(payload.sessionId, BridgePayload.RespondApproval(UUID.randomUUID().toString(), payload.sessionId, payload.approvalId, true))
                mutableState.update { it.updateSession(payload.sessionId) { current -> current.copy(
                    activity = (current.activity + AgentActivityView("auto_approved", payload.command, System.currentTimeMillis())).takeLast(50),
                ) } }
                return
            }
        }
        if (payload is BridgePayload.HelpNeeded && autoApprovals.remove(payload.helpId)) return
        if (payload is BridgePayload.HelpNeeded && mutableState.value.wakeAlarmEnabled) runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
        mutableState.update { state ->
            when (payload) {
                is BridgePayload.ExternalSessionList -> state.copy(externalSessions = payload.sessions)
                is BridgePayload.ExternalSessionSnapshot -> state.copy(
                    watchedExternalSession = payload.session,
                    externalTranscript = payload.transcript,
                    externalSessions = state.externalSessions.map {
                        if (it.agentId == payload.session.agentId && it.id == payload.session.id) payload.session else it
                    },
                )
                is BridgePayload.SavedTabList -> mergeSavedTabs(state, payload.tabs)
                is BridgePayload.AgentList -> state.copy(agents = payload.agents, busy = false)
                is BridgePayload.DirectoryList -> state.copy(directoryCurrent = payload.current, directoryParent = payload.parent, directories = payload.directories, busy = false)
                is BridgePayload.SessionStarted -> {
                    val tabId = pendingStarts.remove(payload.requestId) ?: return@update state
                    state.updateTab(tabId) { it.copy(
                        sessionId = payload.sessionId, lastSessionId = payload.sessionId, agentId = payload.agentId,
                        capabilities = payload.capabilities, resumed = payload.resumed, busy = false, closing = false, error = null,
                    ) }
                }
                is BridgePayload.SessionClosed -> {
                    val tabId = state.tabIdFor(payload.sessionId) ?: return@update state
                    if (pendingDeletes.remove(tabId)) {
                        send("control", BridgePayload.DeleteTab(UUID.randomUUID().toString(), tabId))
                        removeTabState(state, tabId)
                    } else state.updateTab(tabId) {
                        val cleared = pendingClears.remove(tabId)
                        if (cleared) restartTabId = tabId
                        it.copy(
                            sessionId = null, lastSessionId = if (cleared) null else it.lastSessionId,
                            busy = false, closing = false, approvals = emptyList(), helpNeeded = null,
                        )
                    }
                }
                is BridgePayload.TextDelta -> state.updateSession(payload.sessionId) { it.copy(transcript = it.transcript + payload.text) }
                is BridgePayload.ApprovalRequested -> state.updateSession(payload.sessionId) { it.copy(approvals = it.approvals + RemoteApprovalView(payload.approvalId, payload.command, payload.cwd)) }
                is BridgePayload.TurnFinished -> state.updateSession(payload.sessionId) { it.copy(busy = false, approvals = emptyList(), helpNeeded = null) }
                is BridgePayload.AgentActivity -> state.updateSession(payload.sessionId) { it.copy(activity = (it.activity + AgentActivityView(payload.state, payload.detail, payload.createdAtMillis)).takeLast(50)) }
                is BridgePayload.HelpNeeded -> state.updateSession(payload.sessionId) { it.copy(helpNeeded = payload.reason) }
                is BridgePayload.Failure -> {
                    val tabId = payload.sessionId?.let { state.tabIdFor(it) } ?: payload.requestId?.let { pendingStarts.remove(it) }
                    if (tabId == null) state.copy(busy = false, error = payload.code)
                    else state.updateTab(tabId) { it.copy(busy = false, closing = false, approvals = emptyList(), helpNeeded = null, error = payload.code) }
                }
                else -> state
            }
        }
        if (payload is BridgePayload.SessionStarted || payload is BridgePayload.SessionClosed) {
            persistTabs()
            if (payload is BridgePayload.SessionStarted) {
                mutableState.value.sessions.firstOrNull { it.sessionId == payload.sessionId }?.tabId?.let(::syncTab)
            }
        }
        restartTabId?.let { tabId -> syncTab(tabId); startTab(tabId) }
    }

    private fun loadTabs(): List<RemoteSessionView> = runCatching {
        BridgeJson.decodeFromString<List<SavedAgentTab>>(preferences.get(TABS_KEY, "[]")).map { tab ->
            RemoteSessionView(
                tabId = tab.tabId, title = tab.title, workingDirectory = tab.workingDirectory,
                requestedAgentId = tab.agentId, agentId = tab.agentId, lastSessionId = tab.lastSessionId,
                autoMode = tab.autoMode, busy = false,
            )
        }
    }.getOrDefault(emptyList())

    private fun persistTabs() {
        val tabs = mutableState.value.sessions.map { SavedAgentTab(
            it.tabId, it.title, it.workingDirectory, it.agentId ?: it.requestedAgentId, it.lastSessionId, it.autoMode,
        ) }
        preferences.put(TABS_KEY, BridgeJson.encodeToString(tabs))
    }

    private fun removeTab(tabId: String) {
        mutableState.update { removeTabState(it, tabId) }
        persistTabs()
        if (connection != null) send("control", BridgePayload.DeleteTab(UUID.randomUUID().toString(), tabId))
    }

    private suspend fun migrateLocalTabs(connected: EncryptedRelayConnection) {
        if (preferences.getBoolean(HOST_MIGRATION_KEY, false)) return
        mutableState.value.sessions.forEach { tab ->
            connected.send("host", "control", BridgePayload.SaveTab(UUID.randomUUID().toString(), tab.toSavedTab()))
        }
        preferences.putBoolean(HOST_MIGRATION_KEY, true)
    }

    private fun syncTab(tabId: String) {
        val tab = mutableState.value.sessions.firstOrNull { it.tabId == tabId } ?: return
        if (connection != null) send("control", BridgePayload.SaveTab(UUID.randomUUID().toString(), tab.toSavedTab()))
    }

    private fun RemoteSessionView.toSavedTab() = SavedAgentTab(
        tabId, title, workingDirectory, agentId ?: requestedAgentId, lastSessionId, autoMode,
    )

    private fun mergeSavedTabs(state: RemoteBridgeState, tabs: List<SavedAgentTab>): RemoteBridgeState {
        val local = state.sessions.associateBy(RemoteSessionView::tabId)
        val merged = tabs.take(MAX_SAVED_TABS).map { saved ->
            val runtime = local[saved.tabId]
            if (runtime == null) RemoteSessionView(
                tabId = saved.tabId, title = saved.title, workingDirectory = saved.workingDirectory,
                requestedAgentId = saved.agentId, agentId = saved.agentId, lastSessionId = saved.lastSessionId,
                autoMode = saved.autoMode, busy = false,
            ) else runtime.copy(
                title = saved.title, workingDirectory = saved.workingDirectory,
                requestedAgentId = saved.agentId, agentId = saved.agentId,
                lastSessionId = saved.lastSessionId ?: runtime.lastSessionId, autoMode = saved.autoMode,
            )
        }
        val active = state.activeTabId?.takeIf { id -> merged.any { it.tabId == id } } ?: merged.firstOrNull()?.tabId
        return state.copy(sessions = merged, activeTabId = active)
    }
    private fun removeTabState(state: RemoteBridgeState, tabId: String): RemoteBridgeState {
        val remaining = state.sessions.filterNot { it.tabId == tabId }
        return state.copy(sessions = remaining, activeTabId = if (state.activeTabId == tabId) remaining.lastOrNull()?.tabId else state.activeTabId)
    }
    private fun RemoteBridgeState.tabIdFor(sessionId: String) = sessions.firstOrNull { it.sessionId == sessionId }?.tabId
    private fun RemoteBridgeState.updateSession(sessionId: String, block: (RemoteSessionView) -> RemoteSessionView) = tabIdFor(sessionId)?.let { updateTab(it, block) } ?: this
    private fun RemoteBridgeState.updateTab(tabId: String, block: (RemoteSessionView) -> RemoteSessionView) = copy(sessions = sessions.map { if (it.tabId == tabId) block(it) else it })
    private fun fail(failure: Throwable) = mutableState.update { it.copy(busy = false, error = failure.message?.take(240) ?: "Bridge connection failed") }
    override fun close() { connection?.close(); connection = null; scope.cancel() }

    private companion object {
        const val TABS_KEY = "saved_agent_tabs_v1"
        const val HOST_MIGRATION_KEY = "saved_agent_tabs_host_migrated_v1"
    }
}
