package com.smarttools.netguard.agent

import org.junit.Assert.*
import org.junit.Test

class WbAgentMigrationTest {
    @Test fun existingAgentsMustReceiveAuditedReleaseFixes() {
        assertTrue(WbStreamUpdater.needsUpdate(0))
        assertTrue(WbStreamUpdater.needsUpdate(13))
        assertTrue(WbStreamUpdater.needsUpdate(14))
        assertFalse(WbStreamUpdater.needsUpdate(15))
    }
}
