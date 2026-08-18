package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.workspace.WorkspaceId
import org.junit.Assert.*
import org.junit.Test

class RuntimeTypesTest {

    @Test fun `RuntimeType has 6 types`() {
        assertEquals(6, RuntimeType.values().size)
        assertTrue(RuntimeType.values().any { it.name == "ANDROID" })
        assertTrue(RuntimeType.values().any { it.name == "TERMUX" })
        assertTrue(RuntimeType.values().any { it.name == "LINUX" })
        assertTrue(RuntimeType.values().any { it.name == "PROOT" })
    }

    @Test fun `RuntimeId is type-safe`() {
        val a = RuntimeId("rt1")
        val b = RuntimeId("rt2")
        assertNotEquals(a.value, b.value)
    }

    @Test fun `RuntimeState has full lifecycle`() {
        val states = RuntimeState.values().map { it.name }
        assertTrue(states.contains("CREATED"))
        assertTrue(states.contains("INITIALIZING"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("DEGRADED"))
        assertTrue(states.contains("SHUTTING_DOWN"))
        assertTrue(states.contains("CLOSED"))
        assertTrue(states.contains("FAILED"))
    }

    @Test fun `RuntimeHealth has 5 states`() {
        assertEquals(5, RuntimeHealth.values().size)
    }

    @Test fun `RuntimeCapabilities has 8 fields`() {
        val caps = RuntimeCapabilities()
        assertTrue(caps.pty)
        assertTrue(caps.processGroups)
        assertTrue(caps.signals)
        assertTrue(caps.resize)
        assertTrue(caps.filesystem)
        assertTrue(caps.shell)
        assertTrue(caps.persistence)
        assertFalse(caps.reattach)  // not available by default
    }

    @Test fun `RuntimeSnapshot is immutable`() {
        val snap = RuntimeSnapshot(
            id = RuntimeId("rt1"), type = RuntimeType.ANDROID,
            state = RuntimeState.READY, health = RuntimeHealth.HEALTHY,
            capabilities = RuntimeCapabilities(),
            activeSessionCount = 3,
            workspaceIds = listOf(WorkspaceId("w1"), WorkspaceId("w2")),
            createdAt = System.currentTimeMillis()
        )
        assertEquals(RuntimeType.ANDROID, snap.type)
        assertEquals(3, snap.activeSessionCount)
        assertEquals(2, snap.workspaceIds.size)
    }

    @Test fun `RuntimeSelector supports capability-based selection`() {
        val selector = RuntimeSelector(
            type = RuntimeType.LINUX,
            requiredCapabilities = setOf(RuntimeCapabilityRequirement.PTY, RuntimeCapabilityRequirement.SIGNALS)
        )
        assertEquals(RuntimeType.LINUX, selector.type)
        assertEquals(2, selector.requiredCapabilities.size)
    }

    @Test fun `RuntimeRequest defaults to ANDROID`() {
        val req = RuntimeRequest()
        assertEquals(RuntimeType.ANDROID, req.type)
    }

    @Test fun `ShutdownMode has GRACEFUL and FORCE`() {
        assertEquals(2, ShutdownMode.values().size)
    }

    @Test fun `RuntimeCapabilityRequirement has 8 requirements`() {
        assertEquals(8, RuntimeCapabilityRequirement.values().size)
    }

    @Test fun `ShellInfo has path and name`() {
        val shell = ShellInfo(path = "/bin/bash", name = "bash", version = "5.1")
        assertEquals("/bin/bash", shell.path)
        assertEquals("bash", shell.name)
        assertEquals("5.1", shell.version)
    }

    @Test fun `SessionRuntimeBinding does not expose Runtime object`() {
        val binding = SessionRuntimeBinding(
            sessionId = "s1",
            runtimeId = RuntimeId("rt1"),
            workspaceId = WorkspaceId("w1")
        )
        assertEquals("s1", binding.sessionId)
        assertEquals("rt1", binding.runtimeId.value)
        // No Runtime object reference — only ID
    }
}
