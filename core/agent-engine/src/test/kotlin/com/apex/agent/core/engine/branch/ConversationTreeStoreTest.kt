package com.apex.agent.core.engine.branch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
 * 4-c — 会话树文件仓库测试。
 *
 * 覆盖：save/load roundtrip 字段抽样（防 equals 掩盖丢字段）、损坏
 * 隔离（.corrupt 备份 + 其余树不受影响）、delete 移除文件、listIds 与
 * snapshots 摘要元数据（messageCount / branchCount）、id 校验拒绝路径
 * 穿越、fromLinearMessages 分组（user → 节点；assistant+tool 回合 →
 * 单节点首消息为唯一候选）、runTest 并发 save 最终一致。
 */
class ConversationTreeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1000L
    private val logs = mutableListOf<String>()

    private lateinit var store: ConversationTreeStore

    @Before
    fun setup() {
        dir = tmp.newFolder("conversations")
        store = ConversationTreeStore(dir, clock = { now }, logger = { logs.add(it) })
    }

    private fun msg(id: String, role: MessageRole, content: String, createdAt: Long = 0) =
        BranchMessage(id = id, role = role, content = content, createdAt = createdAt)

    private fun node(id: String, vararg candidates: BranchMessage, selectIndex: Int = 0) =
        MessageBranchNode(id = id, candidates = candidates.toList(), selectIndex = selectIndex)

    /** 全字段样本树：两节点，assistant 节点双候选选中第二。 */
    private fun sampleTree(id: String = "t-sample", title: String = "样本会话"): ConversationTree =
        ConversationTree(
            id = id,
            title = title,
            nodes = listOf(
                node("n0", msg("m-u1", MessageRole.USER, "问题", createdAt = 111)),
                node(
                    "n1",
                    BranchMessage(
                        id = "m-a1", role = MessageRole.ASSISTANT, content = "回复一",
                        createdAt = 222, thinking = "推理", meta = mapOf("model" to "model-x")
                    ),
                    BranchMessage(
                        id = "m-a2", role = MessageRole.ASSISTANT, content = "回复二",
                        createdAt = 223, toolName = null
                    ),
                    selectIndex = 1
                )
            ),
            createdAt = 111,
            updatedAt = 111
        )

    /** 断言挂起函数抛 IllegalArgumentException（assertThrows 的 lambda 不是协程体，需手写）。 */
    private suspend fun assertIllegalArgument(action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期异常
        }
    }

    // ═══ Roundtrip ═══

    @Test
    fun `save-load roundtrip preserves all fields with field sampling`() = runTest {
        now = 5000L
        store.save(sampleTree())
        // 新实例走真实磁盘加载（不是同一内存缓存）
        val fresh = ConversationTreeStore(dir)
        val loaded = fresh.load("t-sample")

        assertNotNull(loaded)
        assertEquals("t-sample", loaded!!.id)
        assertEquals("样本会话", loaded.title)
        assertEquals(111L, loaded.createdAt)
        assertEquals(5000L, loaded.updatedAt) // save 盖假钟戳
        assertEquals(2, loaded.nodes.size)

        val userNode = loaded.nodes[0]
        assertEquals("n0", userNode.id)
        assertEquals(1, userNode.candidateCount)
        assertEquals("问题", userNode.currentMessage?.content)
        assertEquals(111L, userNode.currentMessage?.createdAt)

        val assistantNode = loaded.nodes[1]
        assertEquals("n1", assistantNode.id)
        assertEquals(2, assistantNode.candidateCount)
        assertEquals(1, assistantNode.selectIndex)
        assertEquals("回复二", assistantNode.currentMessage?.content)
        val first = assistantNode.candidates[0]
        assertEquals("回复一", first.content)
        assertEquals("推理", first.thinking)
        assertEquals("model-x", first.meta["model"])
        assertEquals(1, first.meta.size)
        // 线性化与摘要口径
        assertEquals(listOf("问题", "回复二"), loaded.currentMessages.map { m -> m.content })
        assertEquals(1, loaded.branchNodeCount)
    }

    @Test
    fun `save bumps updatedAt but preserves createdAt`() = runTest {
        now = 100L
        store.save(sampleTree())
        now = 200L
        store.save(store.load("t-sample")!!.copy(title = "改标题"))
        val loaded = store.load("t-sample")!!
        assertEquals(111L, loaded.createdAt)
        assertEquals(200L, loaded.updatedAt)
        assertEquals("改标题", loaded.title)
        // 单文件：无残留多版本
        val jsonFiles = dir.listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(1, jsonFiles.size)
    }

    @Test
    fun `load missing id returns null`() = runTest {
        assertNull(store.load("no-such-tree"))
    }

    // ═══ id 校验（路径穿越防御）═══

    @Test
    fun `load and delete reject illegal ids without throwing`() = runTest {
        assertNull(store.load("../evil"))
        assertNull(store.load("a.b"))
        assertNull(store.load(""))
        assertNull(store.load("带空格 id"))
        assertFalse(store.delete("../evil"))
        assertFalse(store.delete("a/b"))
    }

    @Test
    fun `save rejects path traversal and oversized ids`() = runTest {
        assertIllegalArgument { store.save(sampleTree(id = "../evil")) }
        assertIllegalArgument { store.save(sampleTree(id = "a.b")) }
        assertIllegalArgument { store.save(sampleTree(id = "x".repeat(65))) }
        assertIllegalArgument { store.save(sampleTree(id = "")) }
        // 合法边界：64 位、连字符、下划线
        store.save(sampleTree(id = "a".repeat(64)))
        store.save(sampleTree(id = "Abc-XYZ_123"))
        assertNotNull(store.load("a".repeat(64)))
        assertNotNull(store.load("Abc-XYZ_123"))
    }

    // ═══ 损坏隔离 ═══

    @Test
    fun `corrupt file is quarantined to corrupt backup and returns null`() = runTest {
        store.save(sampleTree())
        // 直接制造损坏文件（另一个会话的文件坏了）
        val garbage = "this is definitely not json"
        File(dir, "t-bad.json").writeText(garbage)

        val fresh = ConversationTreeStore(dir, logger = { logs.add(it) })
        assertNull(fresh.load("t-bad"))

        // 原位置不再有损坏文件；.corrupt 备份保留原文（不删除）
        assertFalse(File(dir, "t-bad.json").exists())
        val backup = File(dir, "t-bad.json.corrupt")
        assertTrue(backup.exists())
        assertEquals(garbage, backup.readText())

        // 其余会话不受影响
        assertNotNull(fresh.load("t-sample"))
        // 留痕
        assertTrue(logs.any { line -> line.contains("corrupt") && line.contains("t-bad.json") })
    }

    @Test
    fun `unknown json fields are tolerated on load`() = runTest {
        now = 100L
        store.save(sampleTree())
        // 模拟未来版本写入新字段：尾部追加 unknown 键
        val file = File(dir, "t-sample.json")
        val patched = file.readText().dropLast(1) + ",\"futureEnvelopeField\":true}"
        file.writeText(patched)
        val fresh = ConversationTreeStore(dir)
        val loaded = fresh.load("t-sample")
        assertNotNull(loaded)
        assertEquals("样本会话", loaded!!.title)
        assertEquals(2, loaded.nodes.size)
    }

    // ═══ 删除 ═══

    @Test
    fun `delete removes file and cache entry`() = runTest {
        store.save(sampleTree())
        assertTrue(File(dir, "t-sample.json").exists())
        assertTrue(store.delete("t-sample"))
        assertFalse(File(dir, "t-sample.json").exists())
        assertNull(store.load("t-sample"))
        // 幂等：再删返回 false
        assertFalse(store.delete("t-sample"))
        assertFalse(store.delete("never-existed"))
    }

    // ═══ listIds 与 snapshots ═══

    @Test
    fun `listIds and snapshots sort by updatedAt desc with deterministic tiebreak`() = runTest {
        now = 100L
        store.save(sampleTree(id = "t-old", title = "旧"))
        now = 300L
        store.save(sampleTree(id = "t-new", title = "新"))
        now = 200L
        store.save(sampleTree(id = "t-mid", title = "中"))

        assertEquals(listOf("t-new", "t-mid", "t-old"), store.listIds())

        val snapshots = store.snapshots()
        assertEquals(3, snapshots.size)
        assertEquals("t-new", snapshots[0].id)
        assertEquals("新", snapshots[0].title)
        assertEquals(300L, snapshots[0].updatedAt)
        assertEquals(2, snapshots[0].messageCount) // 线性化消息数
        assertEquals(1, snapshots[0].branchCount) // 双候选 assistant 节点
        assertEquals(200L, snapshots[1].updatedAt)
        assertEquals("t-mid", snapshots[1].id)
    }

    @Test
    fun `snapshots branchCount counts only multi-candidate nodes`() = runTest {
        // 单候选树：branchCount 0
        now = 10L
        store.save(
            ConversationTree(
                id = "t-plain",
                nodes = listOf(
                    node("n0", msg("m-u1", MessageRole.USER, "问")),
                    node("n1", msg("m-a1", MessageRole.ASSISTANT, "答"))
                )
            )
        )
        // 三候选节点 + 单候选节点：branchCount 1（按节点口径，非候选总数）
        now = 20L
        store.save(
            ConversationTree(
                id = "t-branchy",
                nodes = listOf(
                    node("n0", msg("m-u1", MessageRole.USER, "问")),
                    node(
                        "n1",
                        msg("a1", MessageRole.ASSISTANT, "一"),
                        msg("a2", MessageRole.ASSISTANT, "二"),
                        msg("a3", MessageRole.ASSISTANT, "三"),
                        selectIndex = 2
                    )
                )
            )
        )
        val byId = store.snapshots().associateBy { it.id }
        assertEquals(0, byId.getValue("t-plain").branchCount)
        assertEquals(1, byId.getValue("t-branchy").branchCount)
        assertEquals(2, byId.getValue("t-branchy").messageCount)
    }

    // ═══ fromLinearMessages（迁移分组）═══

    @Test
    fun `fromLinearMessages wraps user and system messages into own nodes`() {
        val tree = ConversationTreeStore.fromLinearMessages(
            id = "t-mig",
            title = "迁移会话",
            messages = listOf(
                msg("s1", MessageRole.SYSTEM, "系统开场", createdAt = 1),
                msg("u1", MessageRole.USER, "问一", createdAt = 2),
                msg("u2", MessageRole.USER, "问二", createdAt = 3)
            ),
            clock = { 777L }
        )
        assertEquals("t-mig", tree.id)
        assertEquals("迁移会话", tree.title)
        assertEquals(3, tree.nodes.size)
        assertEquals(listOf("t-mig-n0", "t-mig-n1", "t-mig-n2"), tree.nodes.map { n -> n.id })
        assertTrue(tree.nodes.all { n -> n.candidateCount == 1 && n.selectIndex == 0 })
        assertEquals(listOf("系统开场", "问一", "问二"), tree.currentMessages.map { m -> m.content })
        assertEquals(777L, tree.createdAt)
        assertEquals(777L, tree.updatedAt)
        assertEquals(0, tree.assistantTurnCount)
    }

    @Test
    fun `fromLinearMessages groups assistant plus tool run into one node with first message only`() {
        // 一个 agent 回合 = [assistant 片段, tool 输出, assistant 收尾] → 单节点，
        // 回合首条消息为唯一候选（工具链不是可切换的分支候选）
        val tree = ConversationTreeStore.fromLinearMessages(
            id = "t-mig2",
            title = "",
            messages = listOf(
                msg("u1", MessageRole.USER, "帮我查天气", createdAt = 1),
                msg("a1", MessageRole.ASSISTANT, "我先查一下", createdAt = 2),
                msg("tool1", MessageRole.TOOL, "工具输出甲", createdAt = 3),
                msg("tool2", MessageRole.TOOL, "工具输出乙", createdAt = 4),
                msg("a2", MessageRole.ASSISTANT, "查到了：晴", createdAt = 5),
                msg("u2", MessageRole.USER, "谢谢", createdAt = 6),
                msg("a3", MessageRole.ASSISTANT, "不客气", createdAt = 7)
            )
        )
        // 节点：[u1] [a1 回合] [u2] [a3 回合] —— assistant+tool 连续段合并为同一节点
        assertEquals(4, tree.nodes.size)
        assertEquals(listOf("t-mig2-n0", "t-mig2-n1", "t-mig2-n2", "t-mig2-n3"), tree.nodes.map { n -> n.id })
        val turnNode = tree.nodes[1]
        assertEquals(1, turnNode.candidateCount)
        assertEquals("a1", turnNode.candidates[0].id)
        assertEquals(MessageRole.ASSISTANT, turnNode.role)
        assertFalse(turnNode.isSwitchable()) // 无假分支
        assertEquals(2, tree.assistantTurnCount)
        // 线性视图：user + 回合首条 assistant
        assertEquals(listOf("帮我查天气", "我先查一下", "谢谢", "不客气"), tree.currentMessages.map { m -> m.content })
        assertEquals(4, tree.messageCount)
    }

    @Test
    fun `fromLinearMessages handles tool-only run and empty input`() {
        // 回合不以 USER 开头（工具链在会话头部）同样按连续段合并
        val toolFirst = ConversationTreeStore.fromLinearMessages(
            id = "t-mig3",
            title = "",
            messages = listOf(
                msg("tool1", MessageRole.TOOL, "输出一"),
                msg("tool2", MessageRole.TOOL, "输出二")
            )
        )
        assertEquals(1, toolFirst.nodes.size)
        assertEquals("tool1", toolFirst.nodes[0].candidates[0].id)
        assertEquals(MessageRole.TOOL, toolFirst.nodes[0].role)

        // 空输入
        val empty = ConversationTreeStore.fromLinearMessages("t-mig4", "", emptyList(), clock = { 9L })
        assertEquals(0, empty.nodes.size)
        assertEquals(0, empty.messageCount)
        assertEquals(9L, empty.createdAt)
    }

    // ═══ 并发 ═══

    @Test
    fun `concurrent saves serialize into a consistent final state`() = runTest {
        val titles = (0 until 5).map { i -> "并发标题-$i" }
        coroutineScope {
            titles.map { title ->
                async { store.save(sampleTree(id = "t-conc", title = title)) }
            }.awaitAll()
        }
        // 内存缓存与磁盘文件一致：直接解析磁盘内容比对
        val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val diskTree = diskJson.decodeFromString(
            ConversationTree.serializer(),
            File(dir, "t-conc.json").readText()
        )
        val cached = store.load("t-conc")
        assertNotNull(cached)
        assertEquals(diskTree, cached)
        // 最终标题是五个之一（最后持有写锁者胜出）
        assertTrue(titles.contains(cached!!.title))
        // 单文件无残留版本
        val jsonFiles = dir.listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(1, jsonFiles.size)
    }

    // ═══ 分支操作 × 持久化集成 ═══

    @Test
    fun `branch operations survive save-load roundtrip`() = runTest {
        now = 10L
        // 完整分支流：再生成 → 流式回填 → 编辑新分支 → 切换
        val regen = regenerateSlot(sampleTree(), "n1")!!
        var tree = upsertCandidate(regen.tree, msg(regen.info.placeholderMessageId, MessageRole.ASSISTANT, "再生成结果"))
        tree = editMessage(tree, "m-a2", "编辑后的回复")
        now = 20L
        tree = switchBranchRelative(tree, "n1", -1)
        store.save(tree)

        val fresh = ConversationTreeStore(dir)
        val loaded = fresh.load("t-sample")!!
        assertEquals(2, loaded.nodes.size)
        val slot = loaded.nodes[1]
        // 候选产生序：回复一（原始）、回复二（原始）、再生成结果（占位回填）、
        // 编辑后的回复（编辑 = 新分支，m-a2 原文保留）
        assertEquals(
            listOf("回复一", "回复二", "再生成结果", "编辑后的回复"),
            slot.candidates.map { m -> m.content }
        )
        assertEquals(4, slot.candidateCount)
        // 相对切换 -1：从末位回到「再生成结果」
        assertEquals(2, slot.selectIndex)
        assertEquals("再生成结果", slot.currentMessage?.content)
        assertEquals(1, loaded.branchNodeCount) // 双候选以上节点数：仅 assistant 槽
        val summary = fresh.snapshots().single()
        assertEquals(1, summary.branchCount)
        assertEquals(2, summary.messageCount)
        assertEquals(20L, summary.updatedAt)
    }
}
