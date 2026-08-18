package com.apex.agent.platform.terminal.api

import org.junit.Assert.*
import org.junit.Test

class ApiHardeningTest {

    @Test fun `DetachPolicy default is KEEP_RUNNING`() {
        assertEquals(DetachPolicy.KEEP_RUNNING, DetachPolicy.valueOf("KEEP_RUNNING"))
        assertEquals(2, DetachPolicy.values().size)
    }

    @Test fun `TerminalMode has 3 modes`() {
        assertEquals(3, TerminalMode.values().size)
        assertTrue(TerminalMode.values().any { it.name == "PTY" })
        assertTrue(TerminalMode.values().any { it.name == "PIPE" })
        assertTrue(TerminalMode.values().any { it.name == "AUTO" })
    }

    @Test fun `SnapshotVersion defaults to 1`() {
        assertEquals(1, SnapshotVersion.CURRENT.version)
    }

    @Test fun `VersionedTerminalDelta detects gap`() {
        val delta = VersionedTerminalDelta(baseSequence = 100, targetSequence = 105, changes = emptyList())
        assertTrue("gap when agent has 99", delta.hasGap(99))
        assertFalse("no gap when agent has 100", delta.hasGap(100))
    }

    @Test fun `DisconnectPolicy defaults to KEEP_RUNNING`() {
        val policy = DisconnectPolicy()
        assertEquals(DetachPolicy.KEEP_RUNNING, policy.detachPolicy)
        assertNull(policy.sessionTimeoutMs)
    }

    @Test fun `ApiCompatibility version is 1.0.0`() {
        assertEquals("1.0.0", ApiCompatibility.version)
    }

    @Test fun `ApiCompatibility isCompatible with same major`() {
        assertTrue(ApiCompatibility.isCompatible(1, 0))
        assertTrue(ApiCompatibility.isCompatible(1, 1))
        assertFalse(ApiCompatibility.isCompatible(2, 0))
    }
}
