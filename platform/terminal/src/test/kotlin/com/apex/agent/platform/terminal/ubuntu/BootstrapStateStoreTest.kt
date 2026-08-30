package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T76: BootstrapStateStore 单元测试 —— 持久化 + 崩溃恢复。
 */
class BootstrapStateStoreTest {

    private fun newStore(): BootstrapStateStore {
        val dir = Files.createTempDirectory("t76-bootstrap-").toFile()
        return BootstrapStateStore(File(dir, "bootstrap.json"))
    }

    @Test fun `load returns null when no state file`() = runBlocking {
        val store = newStore()
        assertNull(store.load())
    }

    @Test fun `save then load roundtrips`() = runBlocking {
        val store = newStore()
        val record = BootstrapStateStore.BootstrapStateRecord(
            state = BootstrapState.READY.name,
            stageEvidence = mapOf("CHECKING" to 1000L, "APT_UPDATE" to 2000L),
            startedAt = 1000L,
            finishedAt = 5000L,
            baseProfileName = "ubuntu-base-cli",
            installedPackages = listOf("git", "python3")
        )
        store.save(record)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(BootstrapState.READY.name, loaded!!.state)
        assertEquals(2, loaded.stageEvidence.size)
        assertEquals("ubuntu-base-cli", loaded.baseProfileName)
        assertTrue(loaded.installedPackages.contains("git"))
    }

    @Test fun `save FAILED state preserves failedStage`() = runBlocking {
        val store = newStore()
        store.save(
            BootstrapStateStore.BootstrapStateRecord(
                state = BootstrapState.FAILED.name,
                failedStage = "APT_UPDATE",
                failureReason = "network timeout",
                startedAt = 1000L
            )
        )
        val loaded = store.load()!!
        assertEquals(BootstrapState.FAILED.name, loaded.state)
        assertEquals("APT_UPDATE", loaded.failedStage)
        assertEquals("network timeout", loaded.failureReason)
    }

    @Test fun `delete removes state`() = runBlocking {
        val store = newStore()
        store.save(BootstrapStateStore.BootstrapStateRecord(state = BootstrapState.READY.name))
        assertTrue(store.exists())
        store.delete()
        assertFalse(store.exists())
        assertNull(store.load())
    }

    @Test fun `save is idempotent overwrite`() = runBlocking {
        val store = newStore()
        store.save(BootstrapStateStore.BootstrapStateRecord(state = BootstrapState.CHECKING.name))
        store.save(BootstrapStateStore.BootstrapStateRecord(state = BootstrapState.READY.name))
        val loaded = store.load()!!
        assertEquals(BootstrapState.READY.name, loaded.state)
    }

    @Test fun `IN_PROGRESS state is detectable for crash recovery`() = runBlocking {
        val store = newStore()
        // 模拟崩溃：写入 APT_UPDATE（进行中）状态
        store.save(
            BootstrapStateStore.BootstrapStateRecord(
                state = BootstrapState.APT_UPDATE.name,
                stageEvidence = mapOf("CHECKING" to 1L, "CONFIGURING" to 2L, "NETWORK_CHECK" to 3L),
                startedAt = 1000L
            )
        )
        val loaded = store.load()!!
        val st = BootstrapState.valueOf(loaded.state)
        assertTrue("APT_UPDATE must be detected as in-progress", st.isInProgress())
    }
}

/**
 * T76: BootstrapState + isInProgress() 测试。
 */
class BootstrapStateTest {

    @Test fun `state machine has 8 states`() {
        assertEquals(8, BootstrapState.values().size)
    }

    @Test fun `in-progress states are detected`() {
        assertTrue(BootstrapState.CHECKING.isInProgress())
        assertTrue(BootstrapState.CONFIGURING.isInProgress())
        assertTrue(BootstrapState.NETWORK_CHECK.isInProgress())
        assertTrue(BootstrapState.APT_UPDATE.isInProgress())
        assertTrue(BootstrapState.BASE_PACKAGES.isInProgress())
    }

    @Test fun `terminal states are not in-progress`() {
        assertFalse(BootstrapState.NOT_STARTED.isInProgress())
        assertFalse(BootstrapState.READY.isInProgress())
        assertFalse(BootstrapState.FAILED.isInProgress())
    }
}
