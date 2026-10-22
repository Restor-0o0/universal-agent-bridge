package dev.agentbridge.ui

import dev.agentbridge.protocol.WireAgent
import dev.agentbridge.protocol.WireDirectory
import dev.agentbridge.protocol.WireExternalSession
import dev.agentbridge.protocol.WireHost
import kotlinx.coroutines.flow.StateFlow

data class RemoteApprovalView(
    val id: String,
    val command: String,
    val cwd: String,
)

data class AgentActivityView(
    val state: String,
    val detail: String?,
    val createdAtMillis: Long,
)

const val MAX_SAVED_TABS = 100
const val MAX_LIVE_SESSIONS = 20

data class RemoteSessionView(
    val tabId: String,
    val sessionId: String? = null,
    val lastSessionId: String? = null,
    val title: String,
    val workingDirectory: String,
    val requestedAgentId: String? = null,
    val agentId: String? = null,
    val capabilities: Set<String> = emptySet(),
    val autoMode: Boolean = false,
    val resumed: Boolean = false,
    val transcript: String = "",
    val approvals: List<RemoteApprovalView> = emptyList(),
    val activity: List<AgentActivityView> = emptyList(),
    val helpNeeded: String? = null,
    val busy: Boolean = true,
    val closing: Boolean = false,
    val error: String? = null,
)

data class RemoteBridgeState(
    val connected: Boolean = false,
    val workspaceId: String = "default",
    val hosts: List<WireHost> = emptyList(),
    val selectedHostId: String? = null,
    val agents: List<WireAgent> = emptyList(),
    val directoryCurrent: String? = null,
    val directoryParent: String? = null,
    val directories: List<WireDirectory> = emptyList(),
    val externalSessions: List<WireExternalSession> = emptyList(),
    val watchedExternalSession: WireExternalSession? = null,
    val externalTranscript: String = "",
    val sessions: List<RemoteSessionView> = emptyList(),
    val activeTabId: String? = null,
    val wakeAlarmEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val activeSession: RemoteSessionView?
        get() = sessions.firstOrNull { it.tabId == activeTabId }
    val liveSessionCount: Int
        get() = sessions.count { it.sessionId != null }
}

interface RemoteBridgeActions {
    val state: StateFlow<RemoteBridgeState>
    val requiresCredentials: Boolean get() = false
    fun discoverHosts(relayUrl: String, workspaceId: String, password: String)
    fun connect(
        relayUrl: String,
        bearerToken: String? = null,
        keyBase64Url: String? = null,
        workspaceId: String = "default",
        hostId: String? = null,
    )
    fun browseDirectories(parent: String? = null)
    fun refreshExternalSessions()
    fun watchExternalSession(session: WireExternalSession)
    fun stopWatchingExternalSession()
    fun resumeExternalSession(session: WireExternalSession)
    fun startSession(workingDirectory: String, preferredAgentId: String?, title: String? = null)
    fun selectSession(tabId: String?)
    fun closeSession(tabId: String)
    fun deleteSession(tabId: String)
    fun renameSession(tabId: String, title: String)
    fun setSessionAutoMode(tabId: String, enabled: Boolean)
    fun clearSession(tabId: String)
    fun sendText(text: String)
    fun respondApproval(approvalId: String, allow: Boolean)
    fun interrupt()
    fun setWakeAlarmEnabled(enabled: Boolean)
}
