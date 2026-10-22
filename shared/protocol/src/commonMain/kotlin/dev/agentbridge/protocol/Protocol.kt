package dev.agentbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
val BridgeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class RelayInfo(
    val name: String = "Universal Agent Bridge",
    val protocolVersions: List<Int>,
    val stage: String = "M0",
    val enrollmentEnabled: Boolean = false,
    val encryptedTransportReady: Boolean = false,
)

@Serializable
data class WireHost(
    val id: String,
    val name: String,
    val connectedAtMillis: Long,
)

@Serializable
data class RelayHostList(
    val workspaceId: String,
    val hosts: List<WireHost>,
)

@Serializable
data class SavedAgentTab(
    val tabId: String,
    val title: String,
    val workingDirectory: String,
    val agentId: String? = null,
    val lastSessionId: String? = null,
    val autoMode: Boolean = false,
)

@Serializable
data class WireExternalSession(
    val id: String,
    val agentId: String,
    val title: String,
    val workingDirectory: String,
    val updatedAtMillis: Long,
    val active: Boolean,
    val canResume: Boolean = true,
)

/** Routing metadata only. Plaintext must never be placed in ciphertext. */
@Serializable
data class EventEnvelope(
    val v: Int = PROTOCOL_VERSION,
    val eventId: String,
    val kind: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    val seq: Long,
    val createdAt: String,
    val ciphertext: String,
    val nonce: String,
    val keyEpoch: Int,
) {
    fun validate() {
        require(v == PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(eventId.isNotBlank() && sessionId.isNotBlank()) { "Missing identity" }
        require(senderDeviceId.isNotBlank() && recipientDeviceId.isNotBlank()) { "Missing device" }
        require(kind == "session.event") { "Unsupported envelope kind" }
        require(seq > 0 && keyEpoch > 0) { "Invalid sequence or epoch" }
        require(createdAt.isNotBlank()) { "Missing timestamp" }
        require(ciphertext.isNotBlank() && ciphertext.length <= 1_048_576) { "Invalid payload size" }
        require(nonce.isNotBlank()) { "Missing nonce" }
    }
}

fun negotiateVersion(local: Set<Int>, remote: Set<Int>): Int =
    local.intersect(remote).maxOrNull() ?: error("No compatible protocol version")

/** Encrypted payloads shared by Desktop/Android and Host. Relay never decodes these. */
@Serializable
sealed interface BridgePayload {
    @Serializable
    @SerialName("list_external_sessions")
    data class ListExternalSessions(val requestId: String) : BridgePayload

    @Serializable
    @SerialName("external_session_list")
    data class ExternalSessionList(
        val requestId: String,
        val sessions: List<WireExternalSession>,
    ) : BridgePayload

    @Serializable
    @SerialName("watch_external_session")
    data class WatchExternalSession(
        val requestId: String,
        val agentId: String,
        val sessionId: String,
    ) : BridgePayload

    @Serializable
    @SerialName("external_session_snapshot")
    data class ExternalSessionSnapshot(
        val requestId: String? = null,
        val session: WireExternalSession,
        val transcript: String,
    ) : BridgePayload

    @Serializable
    @SerialName("stop_watching_external_session")
    data class StopWatchingExternalSession(
        val requestId: String,
        val agentId: String,
        val sessionId: String,
    ) : BridgePayload

    @Serializable
    @SerialName("list_saved_tabs")
    data class ListSavedTabs(val requestId: String) : BridgePayload

    @Serializable
    @SerialName("saved_tab_list")
    data class SavedTabList(val requestId: String, val tabs: List<SavedAgentTab>) : BridgePayload

    @Serializable
    @SerialName("save_tab")
    data class SaveTab(val requestId: String, val tab: SavedAgentTab) : BridgePayload

    @Serializable
    @SerialName("delete_tab")
    data class DeleteTab(val requestId: String, val tabId: String) : BridgePayload

    @Serializable
    @SerialName("list_agents")
    data class ListAgents(val requestId: String) : BridgePayload

    @Serializable
    @SerialName("agent_list")
    data class AgentList(val requestId: String, val agents: List<WireAgent>) : BridgePayload

    @Serializable
    @SerialName("list_directories")
    data class ListDirectories(val requestId: String, val parent: String? = null) : BridgePayload

    @Serializable
    @SerialName("directory_list")
    data class DirectoryList(
        val requestId: String,
        val current: String,
        val parent: String? = null,
        val directories: List<WireDirectory>,
    ) : BridgePayload

    @Serializable
    @SerialName("start_session")
    data class StartSession(
        val requestId: String,
        val workingDirectory: String,
        val preferredAgentId: String? = null,
    ) : BridgePayload

    @Serializable
    @SerialName("resume_session")
    data class ResumeSession(
        val requestId: String,
        val sessionId: String,
        val agentId: String,
        val workingDirectory: String,
    ) : BridgePayload

    @Serializable
    @SerialName("session_started")
    data class SessionStarted(
        val requestId: String,
        val sessionId: String,
        val agentId: String,
        val title: String,
        val capabilities: Set<String>,
        val resumed: Boolean = false,
    ) : BridgePayload

    @Serializable
    @SerialName("close_session")
    data class CloseSession(val requestId: String, val sessionId: String) : BridgePayload

    @Serializable
    @SerialName("session_closed")
    data class SessionClosed(val requestId: String, val sessionId: String) : BridgePayload

    @Serializable
    @SerialName("send_text")
    data class SendText(
        val requestId: String,
        val sessionId: String,
        val text: String,
        val idempotencyKey: String,
    ) : BridgePayload

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val sessionId: String, val text: String) : BridgePayload

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        val sessionId: String,
        val approvalId: String,
        val command: String,
        val cwd: String,
        val expiresAtMillis: Long,
    ) : BridgePayload

    @Serializable
    @SerialName("respond_approval")
    data class RespondApproval(
        val requestId: String,
        val sessionId: String,
        val approvalId: String,
        val allow: Boolean,
    ) : BridgePayload

    @Serializable
    @SerialName("interrupt")
    data class Interrupt(val requestId: String, val sessionId: String) : BridgePayload

    @Serializable
    @SerialName("turn_finished")
    data class TurnFinished(val sessionId: String, val successful: Boolean) : BridgePayload

    @Serializable
    @SerialName("agent_activity")
    data class AgentActivity(
        val sessionId: String,
        val state: String,
        val detail: String? = null,
        val createdAtMillis: Long,
    ) : BridgePayload

    @Serializable
    @SerialName("help_needed")
    data class HelpNeeded(
        val helpId: String,
        val sessionId: String,
        val reason: String,
        val expiresAtMillis: Long,
    ) : BridgePayload

    @Serializable
    @SerialName("failure")
    data class Failure(val requestId: String? = null, val sessionId: String? = null, val code: String) : BridgePayload
}

@Serializable
data class WireAgent(
    val id: String,
    val name: String,
    val version: String? = null,
    val available: Boolean,
    val capabilities: Set<String> = emptySet(),
    val detail: String? = null,
)

@Serializable
data class WireDirectory(val name: String, val path: String)
