package dev.agentbridge.domain

enum class SessionMode { MANAGED_LIVE, RESUMABLE, READ_ONLY_EXTERNAL, UNSUPPORTED }
enum class Capability {
    LIVE_ATTACH, RESUME, APPROVAL, IMAGE_INPUT, IMAGE_OUTPUT,
    FILE_OUTBOX, SCREEN_CAPTURE, STREAMING, STOP, TERMINAL_VIEW
}
enum class Permission { VIEW_SESSION, SEND_MESSAGE, APPROVE_ACTION, UPLOAD_ARTIFACT, CAPTURE_SCREEN, MANAGE_DEVICE, MANAGE_HOST }
data class SessionDescriptor(
    val id: String,
    val computerId: String,
    val agentId: String,
    val title: String,
    val mode: SessionMode,
    val capabilities: Set<Capability>,
) {
    val canSendText: Boolean get() = mode == SessionMode.MANAGED_LIVE
    val canResume: Boolean get() = mode == SessionMode.RESUMABLE && Capability.RESUME in capabilities
    val canApprove: Boolean get() = mode == SessionMode.MANAGED_LIVE && Capability.APPROVAL in capabilities
}
data class InputLease(val sessionId: String, val deviceId: String, val expiresAtMillis: Long) {
    fun permits(session: String, device: String, nowMillis: Long): Boolean =
        sessionId == session && deviceId == device && nowMillis < expiresAtMillis
}
enum class ApprovalDecision { ALLOW_ONCE, DENY }
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val computerId: String,
    val command: String,
    val cwd: String,
    val expiresAtMillis: Long,
)
