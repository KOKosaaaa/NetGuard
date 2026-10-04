package com.smarttools.netguard.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRestartVerificationTest {
    private val old = HealthResponse(true, "0.5.12", "2026-10-04T20:10:44Z")

    @Test fun oldProcessOrUnhealthyReplyCannotConfirmAnUpdate() {
        assertFalse(old.confirmsRestartOf(old))
        assertFalse(old.copy(version = "0.5.13").confirmsRestartOf(old))
        assertFalse(old.copy(ok = false, startedAt = "2026-10-04T20:20:00Z").confirmsRestartOf(old))
        assertFalse(old.copy(startedAt = "").confirmsRestartOf(old))
        assertFalse(old.confirmsRestartOf(old.copy(startedAt = "")))
    }

    @Test fun sameVersionHotfixRequiresHealthyNewProcess() {
        assertTrue(old.copy(startedAt = "2026-10-04T20:20:00Z").confirmsRestartOf(old))
    }
}
