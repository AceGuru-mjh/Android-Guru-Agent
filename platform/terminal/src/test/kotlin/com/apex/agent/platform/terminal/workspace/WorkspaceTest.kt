package com.apex.agent.platform.terminal.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTypesTest {

    @Test fun `WorkspaceId is type-safe`() {
        val a = WorkspaceId("w1")
        val b = WorkspaceId("w2")
        assertNotEquals(a.value, b.value)
    }

    @Test fun `WorkspaceState has full lifecycle`() {
        val states = WorkspaceState.values().map { it.name }
        assertTrue(states.contains("CREATED"))
        assertTrue(states.contains("INITIALIZING"))
        assertTrue(states.contains("READY"))
        assertTrue(states.contains("DEGRADED"))
        assertTrue(states.contains("CLEANING"))
        assertTrue(states.contains("CLOSED"))
        assertTrue(states.contains("FAILED"))
    }

    @Test fun `WorkspaceSharing has 4 modes`() {
        assertEquals(4, WorkspaceSharing.values().size)
        assertTrue(WorkspaceSharing.values().any { it.name == "SHARED" })
        assertTrue(WorkspaceSharing.values().any { it.name == "ISOLATED" })
        assertTrue(WorkspaceSharing.values().any { it.name == "EPHEMERAL" })
        assertTrue(WorkspaceSharing.values().any { it.name == "PERSISTENT" })
    }

    @Test fun `WorkspaceLayout has 6 dirs`() {
        val layout = WorkspaceLayout()
        assertEquals(6, layout.allDirs().size)
        assertTrue(layout.allDirs().contains("home"))
        assertTrue(layout.allDirs().contains("tmp"))
        assertTrue(layout.allDirs().contains("work"))
        assertTrue(layout.allDirs().contains("cache"))
        assertTrue(layout.allDirs().contains("state"))
        assertTrue(layout.allDirs().contains("logs"))
    }

    @Test fun `WorkspacePath has helpers`() {
        assertEquals("workspace:/home", WorkspacePath.home().value)
        assertEquals("workspace:/tmp", WorkspacePath.tmp().value)
        assertEquals("workspace:/work", WorkspacePath.work().value)
        assertEquals("workspace:/cache", WorkspacePath.cache().value)
    }

    @Test fun `WorkspaceSnapshot is immutable`() {
        val snap = WorkspaceSnapshot(
            id = WorkspaceId("w1"),
            root = AbsolutePath("/data/workspace"),
            state = WorkspaceState.READY,
            sharing = WorkspaceSharing.SHARED,
            layout = WorkspaceLayout(),
            sessionCount = 3,
            createdAt = System.currentTimeMillis()
        )
        assertEquals(WorkspaceState.READY, snap.state)
        assertEquals(3, snap.sessionCount)
    }

    @Test fun `WorkspaceOwnership tracks sessions`() {
        val ownership = WorkspaceOwnership(
            workspaceId = WorkspaceId("w1"),
            runtimeId = "rt1",
            sessionIds = listOf("s1", "s2"),
            managed = true
        )
        assertEquals(2, ownership.sessionIds.size)
        assertTrue(ownership.managed)
    }
}
