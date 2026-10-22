package dev.agentbridge.domain

import kotlin.test.*

class SessionTest {
    private fun session(mode: SessionMode) = SessionDescriptor("s", "pc", "codex", "Project", mode, Capability.entries.toSet())
    @Test fun externalSessionCannotSendOrApproveEvenIfCapabilitiesClaimIt() {
        for (mode in listOf(SessionMode.RESUMABLE, SessionMode.READ_ONLY_EXTERNAL, SessionMode.UNSUPPORTED)) {
            assertFalse(session(mode).canSendText)
            assertFalse(session(mode).canApprove)
        }
        assertTrue(session(SessionMode.RESUMABLE).canResume)
        assertFalse(session(SessionMode.READ_ONLY_EXTERNAL).canResume)
    }
    @Test fun liveSessionRequiresDeclaredApprovalCapability() {
        assertTrue(session(SessionMode.MANAGED_LIVE).canApprove)
        assertFalse(session(SessionMode.MANAGED_LIVE).copy(capabilities = emptySet()).canApprove)
    }
    @Test fun leaseExpiresAtBoundaryAndIsScopedToSessionAndDevice() {
        val lease = InputLease("s", "phone", 100)
        assertTrue(lease.permits("s", "phone", 99))
        assertFalse(lease.permits("s", "phone", 100))
        assertFalse(lease.permits("other", "phone", 99))
        assertFalse(lease.permits("s", "other", 99))
    }
}
