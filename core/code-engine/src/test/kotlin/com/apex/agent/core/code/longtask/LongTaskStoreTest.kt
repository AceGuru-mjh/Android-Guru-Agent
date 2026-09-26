package com.apex.agent.core.code.longtask

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LongTaskStore] 持久化测试。
 *
 * 覆盖：全字段 roundtrip（含检查点嵌套）、list 排序与过滤、delete、
 * incrementCopyCount 原子读改写、prune 保留最新与模板豁免、损坏 JSON
 * 隔离、非法 id 防御、跨实例重载、snapshotBlocking、并发 upsert 一致性
 * （runTest + 多协程）。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder + runTest），无 Android 依赖——与
 * agent-engine 模块既有测试（FileTaskStoreTest 等）同构。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LongTaskStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: LongTaskStore

    @Before
    fun setup() {
        baseDir = tmp.newFolder("longtask")
        store = LongTaskStore(baseDir)
    }

    /** 全字段满配样本（roundtrip 的最大信息量形态）。 */
    private fun fullRecord(
        id: String = "task-1000-abcd",
        updatedAt: Long = 2000L,
        status: LongTaskStatus = LongTaskStatus.FAILED,
        workspaceId: String = "ws-alpha-1a2b3c"
    ) = LongTaskRecord(
        id = id,
        title = "修复登录崩溃问题",
        goal = "在工作区里修复登录页崩溃：\n1. 复现崩溃\n2. 定位根因\n3. 修复并验证",
        workspaceId = workspaceId,
        workspaceName = "Alpha 项目",
        thinkingLevel = "DEEP",
        agentMode = "BUILD",
        status = status,
        createdAt = 1000L,
        updatedAt = updatedAt,
        endedAt = 2000L,
        iterations = 18,
        toolCalls = 33,
        durationMs = 372_500L,
        filesTouched = listOf("src/Main.kt", "src/ui/Login.kt", "src/ui/Theme.kt"),
        toolsUsed = mapOf("code_edit" to 12, "code_read" to 15, "code_check" to 6),
        errorMessage = "编译失败：未解析的引用 LoginViewModel",
        summary = "已完成根因定位（空指针来自延迟初始化），修复提交在 src/ui/Login.kt。",
        checkpoints = listOf(
            LongTaskCheckpoint(
                id = "cp-1",
                atIteration = 5,
                timestamp = 1500L,
                toolCallCount = 9,
                filesTouchedCount = 1,
                recentExchange = listOf("用户: 修复登录崩溃", "助手: 我来定位问题", "工具 code_edit ✓"),
                todoDigest = listOf("☐ 定位根因", "☐ 修复")
            ),
            LongTaskCheckpoint(
                id = "cp-2",
                atIteration = 10,
                timestamp = 1800L,
                toolCallCount = 20,
                filesTouchedCount = 2,
                recentExchange = listOf("工具 code_check ✓", "助手: 边界情况已覆盖"),
                todoDigest = listOf("☑ 定位根因", "☐ 修复")
            )
        ),
        todoSnapshot = listOf("☑ 定位根因", "☐ 修复", "☐ 回归验证"),
        parentTaskId = "task-parent-0000",
        copyCount = 3,
        isTemplate = false,
        tags = listOf("bugfix", "urgent")
    )

    // ═══ Roundtrip ══════════════════════════════════════════════

    @Test
    fun `upsert-get roundtrip preserves all fields including nested checkpoints`() = runTest {
        val record = fullRecord()
        store.upsert(record)
        val loaded = store.get(record.id)

        assertNotNull(loaded)
        assertEquals(record, loaded)
        // 关键字段抽查（防 data class equals 掩盖序列化丢字段）
        loaded!!
        assertEquals(LongTaskStatus.FAILED, loaded.status)
        assertEquals(3, loaded.filesTouched.size)
        assertEquals(mapOf("code_edit" to 12, "code_read" to 15, "code_check" to 6), loaded.toolsUsed)
        assertEquals(2, loaded.checkpoints.size)
        assertEquals(listOf("用户: 修复登录崩溃", "助手: 我来定位问题", "工具 code_edit ✓"),
            loaded.checkpoints[0].recentExchange)
        assertEquals(listOf("☐ 定位根因", "☐ 修复"), loaded.checkpoints[0].todoDigest)
        assertEquals(10, loaded.checkpoints[1].atIteration)
        assertEquals("task-parent-0000", loaded.parentTaskId)
        assertEquals(3, loaded.copyCount)
        assertFalse(loaded.isTemplate)
        assertEquals(listOf("bugfix", "urgent"), loaded.tags)
        assertEquals("修复登录崩溃问题", loaded.title)
    }

    @Test
    fun `get missing record returns null`() = runTest {
        assertNull(store.get("task-does-not-exist"))
    }

    @Test
    fun `overwriting upsert replaces previous snapshot atomically`() = runTest {
        val record = fullRecord()
        store.upsert(record)
        store.upsert(record.copy(status = LongTaskStatus.COMPLETED, updatedAt = 3000L))
        val loaded = store.get(record.id)!!
        assertEquals(LongTaskStatus.COMPLETED, loaded.status)
        assertEquals(3000L, loaded.updatedAt)
        // 单记录单文件：无残留多版本
        val files = File(baseDir, "records").listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(1, files.size)
    }

    @Test
    fun `unknown json fields are tolerated for forward compatibility`() = runTest {
        val record = fullRecord(id = "task-future-0001")
        store.upsert(record)
        // 模拟未来版本给 schema 加了新字段：尾部追加 unknown key 再读回
        val file = File(File(baseDir, "records"), "task-future-0001.json")
        val patched = file.readText().dropLast(1) + ",\"futureField\":42}"
        file.writeText(patched)
        // 新 store 实例强制重扫磁盘（绕过内存缓存）
        val fresh = LongTaskStore(baseDir)
        val loaded = fresh.get("task-future-0001")
        assertNotNull(loaded)
        assertEquals(record.goal, loaded!!.goal)
        assertEquals(record.iterations, loaded.iterations)
    }

    // ═══ list 排序与过滤 ════════════════════════════════════════

    @Test
    fun `list sorts by updatedAt descending`() = runTest {
        store.upsert(fullRecord(id = "task-old", updatedAt = 1000L))
        store.upsert(fullRecord(id = "task-new", updatedAt = 3000L))
        store.upsert(fullRecord(id = "task-mid", updatedAt = 2000L))

        val list = store.list()
        assertEquals(listOf("task-new", "task-mid", "task-old"), list.map { it.id })
    }

    @Test
    fun `list filters by workspace when id provided`() = runTest {
        store.upsert(fullRecord(id = "task-a", updatedAt = 1000L))
        store.upsert(fullRecord(id = "task-b", updatedAt = 2000L,
            workspaceId = "ws-beta-4d5e6f"))

        assertEquals(setOf("task-b"), store.list("ws-beta-4d5e6f").map { it.id }.toSet())
        assertEquals(setOf("task-a"), store.list("ws-alpha-1a2b3c").map { it.id }.toSet())
        // null = 全部工作区
        assertEquals(setOf("task-a", "task-b"), store.list().map { it.id }.toSet())
        assertEquals(emptyList<String>(), store.list("ws-none").map { it.id })
    }

    @Test
    fun `empty store lists empty`() = runTest {
        assertTrue(store.list().isEmpty())
        assertTrue(store.snapshotBlocking().isEmpty())
    }

    // ═══ delete ═════════════════════════════════════════════════

    @Test
    fun `delete removes record and returns true once`() = runTest {
        val record = fullRecord()
        store.upsert(record)
        assertTrue(store.delete(record.id))
        assertNull(store.get(record.id))
        assertFalse(store.delete(record.id)) // 二次删除：已不存在
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete with illegal id returns false without touching disk`() = runTest {
        assertFalse(store.delete("../escape"))
        assertFalse(store.delete(""))
    }

    @Test
    fun `get with illegal id returns null`() = runTest {
        assertNull(store.get("../../etc/passwd"))
        assertNull(store.get("a b c"))
    }

    // ═══ incrementCopyCount ═════════════════════════════════════

    @Test
    fun `incrementCopyCount bumps count and updatedAt`() = runTest {
        val record = fullRecord(updatedAt = 1000L).copy(copyCount = 0)
        store.upsert(record)

        store.incrementCopyCount(record.id)
        assertEquals(1, store.get(record.id)!!.copyCount)

        store.incrementCopyCount(record.id)
        store.incrementCopyCount(record.id)
        val loaded = store.get(record.id)!!
        assertEquals(3, loaded.copyCount)
        assertTrue("updatedAt 应被刷新", loaded.updatedAt >= 1000L)
    }

    @Test
    fun `incrementCopyCount on missing record is a no-op`() = runTest {
        store.incrementCopyCount("task-ghost")
        assertTrue(store.list().isEmpty())
    }

    // ═══ prune ══════════════════════════════════════════════════

    @Test
    fun `prune keeps newest non-template records and returns deleted count`() = runTest {
        // 60 条非模板记录，updatedAt 递增编号
        repeat(60) { i ->
            store.upsert(fullRecord(id = "task-${i.toString().padStart(3, '0')}",
                updatedAt = 1000L + i, status = LongTaskStatus.COMPLETED))
        }
        val deleted = store.prune(keep = 50)
        assertEquals(10, deleted)
        val remaining = store.list()
        assertEquals(50, remaining.size)
        // 保留的是最新的 50 条（编号 10..59），最老的 10 条（000..009）被裁
        val ids = remaining.map { it.id }.toSet()
        assertFalse(ids.contains("task-000"))
        assertFalse(ids.contains("task-009"))
        assertTrue(ids.contains("task-010"))
        assertTrue(ids.contains("task-059"))
    }

    @Test
    fun `prune exempts templates even when they are the oldest`() = runTest {
        // 模板记录最老（updatedAt=1）
        store.upsert(fullRecord(id = "template-bugfix-ws-alpha-1a2b3c", updatedAt = 1L)
            .copy(isTemplate = true, parentTaskId = null, copyCount = 0))
        repeat(5) { i ->
            store.upsert(fullRecord(id = "task-${i}", updatedAt = 100L + i))
        }
        val deleted = store.prune(keep = 2)
        assertEquals(3, deleted) // 5 条非模板裁掉最老的 3 条
        val remaining = store.list()
        assertEquals(3, remaining.size)
        // 模板幸存且不占 keep 名额（2 条最新非模板 + 1 条模板）
        assertTrue(remaining.any { it.isTemplate })
        assertTrue(remaining.none { it.id == "task-0" || it.id == "task-1" || it.id == "task-2" })
    }

    @Test
    fun `prune with zero keep clears all non-template records`() = runTest {
        store.upsert(fullRecord(id = "task-a"))
        store.upsert(fullRecord(id = "task-b"))
        store.upsert(fullRecord(id = "template-docs-ws-alpha-1a2b3c").copy(isTemplate = true))
        val deleted = store.prune(keep = 0)
        assertEquals(2, deleted)
        assertEquals(1, store.list().size)
        assertTrue(store.list().first().isTemplate)
    }

    @Test
    fun `prune with no surplus deletes nothing`() = runTest {
        repeat(3) { i -> store.upsert(fullRecord(id = "task-$i", updatedAt = 100L + i)) }
        assertEquals(0, store.prune(keep = 10))
        assertEquals(3, store.list().size)
    }

    // ═══ 损坏隔离 ═══════════════════════════════════════════════

    @Test
    fun `corrupt json file is skipped without affecting other records`() = runTest {
        val good = fullRecord(id = "task-good")
        store.upsert(good)
        // 手工写一个坏文件（合法 id 命名，内容非 JSON）
        val recordsDir = File(baseDir, "records")
        File(recordsDir, "task-corrupt.json").writeText("{this is not valid json at all")

        val fresh = LongTaskStore(baseDir) // 新实例强制重扫
        val list = fresh.list()
        assertEquals(1, list.size)
        assertEquals("task-good", list[0].id)
        assertNotNull(fresh.get("task-good"))
        assertNull(fresh.get("task-corrupt"))
    }

    @Test
    fun `record whose id differs from file name is indexed by content id`() = runTest {
        // 手工搬运/改名场景：文件名叫 A，内容里的 id 是 B——以内容为准
        store.upsert(fullRecord(id = "task-real"))
        val recordsDir = File(baseDir, "records")
        File(recordsDir, "task-real.json").renameTo(File(recordsDir, "task-renamed.json"))

        val fresh = LongTaskStore(baseDir)
        assertNotNull(fresh.get("task-real"))
        assertNull(fresh.get("task-renamed"))
    }

    // ═══ 跨实例持久化与 snapshotBlocking ════════════════════════

    @Test
    fun `records persist across store instances`() = runTest {
        val record = fullRecord()
        store.upsert(record)
        val fresh = LongTaskStore(baseDir)
        assertEquals(record, fresh.get(record.id))
    }

    @Test
    fun `snapshotBlocking reads persisted records without any coroutine`() = runTest {
        store.upsert(fullRecord(id = "task-a", updatedAt = 1000L))
        store.upsert(fullRecord(id = "task-b", updatedAt = 2000L,
            workspaceId = "ws-beta-4d5e6f"))

        // 全新实例（未发生过任何 suspend 调用）：snapshotBlocking 自行触发首次扫盘
        val fresh = LongTaskStore(baseDir)
        val snapshot = fresh.snapshotBlocking()
        assertEquals(listOf("task-b", "task-a"), snapshot.map { it.id })

        val filtered = fresh.snapshotBlocking("ws-beta-4d5e6f")
        assertEquals(listOf("task-b"), filtered.map { it.id })
    }

    // ═══ 并发 ═══════════════════════════════════════════════════

    @Test
    fun `concurrent upserts of distinct records converge to a consistent state`() = runTest {
        val jobs = (0 until 24).map { i ->
            async {
                store.upsert(fullRecord(id = "task-concurrent-$i", updatedAt = 1000L + i))
            }
        }
        jobs.awaitAll()

        val list = store.list()
        assertEquals(24, list.size)
        assertEquals(24, list.map { it.id }.toSet().size) // 无覆盖丢失
    }

    @Test
    fun `concurrent copy count increments never lose an update`() = runTest {
        val record = fullRecord(id = "task-counter", updatedAt = 1000L).copy(copyCount = 0)
        store.upsert(record)

        val jobs = (0 until 16).map { async { store.incrementCopyCount("task-counter") } }
        jobs.awaitAll()

        assertEquals(16, store.get("task-counter")!!.copyCount)
    }

    @Test
    fun `concurrent upsert and read never expose a torn snapshot`() = runTest {
        val record = fullRecord(id = "task-racy")
        store.upsert(record)
        val jobs = (0 until 8).map { i ->
            async {
                store.upsert(record.copy(updatedAt = 2000L + i, iterations = 100 + i))
                store.get("task-racy")
            }
        }
        jobs.awaitAll()
        // 最终状态 = 某次完整 upsert 的结果（不撕裂：iterations 与 updatedAt 同源）
        val final = store.get("task-racy")!!
        assertEquals(final.iterations - 100, (final.updatedAt - 2000L).toInt())
    }
}
