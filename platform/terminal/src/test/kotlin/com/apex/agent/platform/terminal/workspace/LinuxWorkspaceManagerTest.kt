package com.apex.agent.platform.terminal.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T75: LinuxWorkspaceManager 单元测试 —— 生命周期/懒创建/绑定计数/删除门禁/
 * legacy 迁移/元数据持久化。纯 JVM（TemporaryFolder）。
 */
class LinuxWorkspaceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(legacy: File? = null) =
        LinuxWorkspaceManager(rootDir = File(tmp.root, "workspaces"), legacyDir = legacy)

    // ─── resolve（懒创建）───

    @Test
    fun `resolve null creates default workspace with metadata`() {
        val m = manager()
        val dir = m.resolve(null).getOrThrow()

        assertEquals(File(tmp.root, "workspaces/default"), dir)
        assertTrue(dir.isDirectory)
        // 元数据文件存在
        assertTrue(File(tmp.root, "workspaces/default.json").exists())
        // list 可见
        val ws = m.list()
        assertEquals(1, ws.size)
        assertEquals("default", ws[0].id.value)
        assertEquals(WorkspaceState.READY, ws[0].state)
        assertEquals(WorkspaceSharing.PERSISTENT, ws[0].sharing)
    }

    @Test
    fun `resolve blank id falls back to default`() {
        val m = manager()
        val dir = m.resolve("   ").getOrThrow()
        assertEquals(File(tmp.root, "workspaces/default"), dir)
    }

    @Test
    fun `resolve creates arbitrary valid id lazily`() {
        val m = manager()
        val dir = m.resolve("task-42").getOrThrow()
        assertEquals(File(tmp.root, "workspaces/task-42"), dir)
        assertTrue(m.list().any { it.id.value == "task-42" })
    }

    @Test
    fun `resolve rejects invalid ids`() {
        val m = manager()
        // 注：空串/blank → default（有意回落，见 resolve blank 测试）；此处仅拒非法形态
        for (bad in listOf("Bad", "1 SPACE", "-lead", "_lead", "a".repeat(65), "UPPER", "斜杠/x")) {
            val r = m.resolve(bad)
            assertTrue("'$bad' should be rejected", r.isFailure)
            assertTrue(r.exceptionOrNull()!!.message!!.contains("WorkspaceError:InvalidId"))
        }
    }

    // ─── create（显式，幂等）───

    @Test
    fun `create is idempotent and preserves original metadata`() {
        val m = manager()
        val first = m.create("alpha", name = "Alpha Task").getOrThrow()
        Thread.sleep(5) // 保证 createdAt 可区分（防御时钟粒度）
        val second = m.create("alpha").getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("Alpha Task", m.list().first { it.id.value == "alpha" }.name)
    }

    @Test
    fun `create without id slugifies name`() {
        val m = manager()
        val snap = m.create(null, name = "My Build Pipeline 42!").getOrThrow()
        assertEquals("my-build-pipeline-42", snap.id.value)
        assertTrue(File(tmp.root, "workspaces/my-build-pipeline-42").isDirectory)
    }

    @Test
    fun `create requires id or name`() {
        val m = manager()
        val r = m.create(null, null)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("WorkspaceError:InvalidInput"))
    }

    @Test
    fun `create rejects invalid slug from name`() {
        val m = manager()
        // slugify 产生空（name 全符号）→ 兜底 default —— 合法；非法显式 id 才拒绝
        val r = m.create("!!!", null)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("WorkspaceError:InvalidId"))
    }

    // ─── bind/unbind（活跃计数 → delete 门禁）───

    @Test
    fun `bind counts active sessions and unbind releases`() {
        val m = manager()
        m.resolve("alpha").getOrThrow()

        m.bind(1, "alpha")
        m.bind(2, "alpha")
        assertEquals(2, m.activeSessionCount("alpha"))

        m.unbind(1)
        assertEquals(1, m.activeSessionCount("alpha"))
        // snapshot 的 sessionCount 反映活跃绑定
        assertEquals(1, m.list().first { it.id.value == "alpha" }.sessionCount)

        m.unbind(2)
        assertEquals(0, m.activeSessionCount("alpha"))
    }

    @Test
    fun `unbind unknown session is a no-op`() {
        val m = manager()
        m.unbind(999) // 不抛
    }

    @Test
    fun `delete refuses while sessions are bound`() {
        val m = manager()
        m.resolve("alpha").getOrThrow()
        m.bind(7, "alpha")

        val r = m.delete("alpha")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("WorkspaceError:Busy"))
        // 目录仍在
        assertTrue(File(tmp.root, "workspaces/alpha").isDirectory)

        // unbind 后可删
        m.unbind(7)
        m.delete("alpha").getOrThrow()
        assertFalse(File(tmp.root, "workspaces/alpha").exists())
        assertFalse(File(tmp.root, "workspaces/alpha.json").exists())
        assertTrue(m.list().none { it.id.value == "alpha" })
    }

    @Test
    fun `delete unknown workspace fails with NotFound`() {
        val m = manager()
        val r = m.delete("ghost")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("WorkspaceError:NotFound"))
    }

    @Test
    fun `deleted default is recreated empty on next resolve`() {
        val m = manager()
        val dir = m.resolve(null).getOrThrow()
        File(dir, "junk.txt").writeText("x")
        m.delete("default").getOrThrow()

        val dir2 = m.resolve(null).getOrThrow()
        assertEquals(dir, dir2)
        assertEquals(0, dir2.listFiles()!!.size)
    }

    // ─── inspect ───

    @Test
    fun `inspect reports sizeBytes and unknown fails`() {
        val m = manager()
        val dir = m.resolve("data").getOrThrow()
        File(dir, "file.bin").writeText("0123456789")

        val snap = m.inspect("data").getOrThrow()
        assertEquals(10L, snap.detailSizeBytes)
        // list 不算 size
        assertNull(m.list().first { it.id.value == "data" }.detailSizeBytes)

        val r = m.inspect("ghost")
        assertTrue(r.isFailure)
    }

    // ─── legacy 迁移 ───

    @Test
    fun `legacy single workspace dir is atomically migrated into default`() {
        val legacy = File(tmp.root, "old-workspace")
        legacy.mkdirs()
        File(legacy, "user-file.txt").writeText("precious")

        val m = manager(legacy = legacy)
        val dir = m.resolve(null).getOrThrow()

        // 文件随目录一起迁移（rename 原子性）
        assertEquals("precious", File(dir, "user-file.txt").readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun `legacy migration skipped when default already exists`() {
        val legacy = File(tmp.root, "old-workspace")
        legacy.mkdirs()
        File(legacy, "legacy.txt").writeText("legacy")
        // default 已存在（上一次运行留下的状态）→ 迁移跳过，legacy 保留供人工 salvage
        val root = File(tmp.root, "workspaces")
        File(root, "default").mkdirs()
        File(root, "default/new.txt").writeText("new")

        val m = manager(legacy = legacy)
        val dir = m.resolve(null).getOrThrow()

        assertTrue(File(legacy, "legacy.txt").exists())
        assertFalse(File(dir, "legacy.txt").exists())
        assertEquals("new", File(dir, "new.txt").readText())
    }

    // ─── 元数据持久化（跨实例重启）───

    @Test
    fun `metadata survives manager restart`() {
        val root = File(tmp.root, "workspaces")
        val m1 = LinuxWorkspaceManager(root)
        m1.create("beta", name = "Beta").getOrThrow()
        m1.bind(5, "beta")
        m1.bind(6, "beta")

        // 新实例（进程重启语义）—— 绑定计数在内存，重启后归零（保守方向）
        val m2 = LinuxWorkspaceManager(root)
        val list = m2.list()
        assertEquals(1, list.size)
        assertEquals("beta", list[0].id.value)
        assertEquals("Beta", list[0].name)
        assertEquals(0, list[0].sessionCount) // 计数不跨实例
        assertTrue(File(root, "beta").isDirectory)
    }

    @Test
    fun `bind updates lastUsedAt`() {
        val m = manager()
        m.resolve("gamma").getOrThrow()
        assertNull(m.list().first { it.id.value == "gamma" }.lastUsedAt)

        m.bind(1, "gamma")
        assertNotNull(m.list().first { it.id.value == "gamma" }.lastUsedAt)
    }
}
