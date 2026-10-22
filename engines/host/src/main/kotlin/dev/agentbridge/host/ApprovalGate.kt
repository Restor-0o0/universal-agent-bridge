package dev.agentbridge.host

import dev.agentbridge.domain.*

/** Process-local M0 policy primitive. Caller must provide authenticated identity,
 * current grants and lease. Persistent transaction semantics are required for M1.
 */
class ApprovalGate {
    private val pending = mutableMapOf<String, ApprovalRequest>()
    private val seen = mutableSetOf<String>()

    @Synchronized
    fun register(request: ApprovalRequest) {
        require(request.id.isNotBlank() && request.sessionId.isNotBlank())
        check(seen.add(request.id)) { "Approval already pending" }
        pending[request.id] = request
    }

    @Synchronized
    fun consume(
        approvalId: String,
        sessionId: String,
        deviceId: String,
        grants: Set<Permission>,
        lease: InputLease,
        nowMillis: Long,
        online: Boolean,
    ): ApprovalRequest {
        check(online) { "Approvals cannot be sent offline" }
        check(Permission.APPROVE_ACTION in grants) { "Missing approval grant" }
        check(lease.permits(sessionId, deviceId, nowMillis)) { "No valid input lease" }
        val request = checkNotNull(pending[approvalId]) { "Approval unknown or consumed" }
        check(request.sessionId == sessionId) { "Wrong session" }
        if (nowMillis >= request.expiresAtMillis) {
            pending.remove(approvalId)
            error("Approval expired")
        }
        pending.remove(approvalId)
        return request
    }
}
