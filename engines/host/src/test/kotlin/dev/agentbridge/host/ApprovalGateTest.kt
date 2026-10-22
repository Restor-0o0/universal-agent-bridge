package dev.agentbridge.host

import dev.agentbridge.domain.*
import kotlin.test.*

class ApprovalGateTest {
    private val request = ApprovalRequest("a", "s", "pc", "echo test", "/workspace", 100)
    private val lease = InputLease("s", "phone", 150)
    private fun consume(gate: ApprovalGate, time: Long = 10, device: String = "phone", online: Boolean = true,
        grants: Set<Permission> = setOf(Permission.APPROVE_ACTION)) =
        gate.consume("a", "s", device, grants, lease, time, online)

    @Test fun consumeExactlyOnce() {
        val gate = ApprovalGate(); gate.register(request)
        assertEquals(request, consume(gate))
        assertFails { consume(gate) }
        assertFails { gate.register(request) }
    }
    @Test fun invalidActorOrOfflineCannotConsumeRequest() {
        val gate = ApprovalGate(); gate.register(request)
        assertFails { consume(gate, device = "attacker") }
        assertFails { consume(gate, online = false) }
        assertFails { consume(gate, grants = emptySet()) }
        assertEquals(request, consume(gate))
    }
    @Test fun expiredApprovalAndLeaseAreRejected() {
        val gate = ApprovalGate(); gate.register(request)
        assertFails { consume(gate, time = 100) }
        assertFails { consume(gate) }
        val other = ApprovalGate(); other.register(request.copy(expiresAtMillis = 200))
        assertFails { consume(other, time = 150) }
    }
    @Test fun approvalCannotBeUsedForAnotherSession() {
        val gate = ApprovalGate(); gate.register(request.copy(sessionId = "other"))
        assertFails { consume(gate) }
    }
}
