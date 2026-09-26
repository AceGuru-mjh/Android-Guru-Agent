package com.apex.agent.core.engine.branch

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4-c — 分支核心模型测试。
 *
 * 覆盖：节点 select 钳制、currentMessage 空安全、角色便捷判断、
 * 序列化 roundtrip（字段逐一抽查——防 equals 掩盖丢字段）、旧 JSON
 * 缺字段的前向兼容、树级线性化与查找。
 */
class BranchModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun msg(
        id: String,
        role: MessageRole,
        content: String,
        createdAt: Long = 0
    ) = BranchMessage(id = id, role = role, content = content, createdAt = createdAt)

    private fun node(id: String, vararg candidates: BranchMessage, selectIndex: Int = 0) =
        MessageBranchNode(id = id, candidates = candidates.toList(), selectIndex = selectIndex)

    // ═══ 节点：select 钳制与空安全 ═══

    @Test
    fun `node select clamps out-of-range index into valid range`() {
        val n = node("n1", msg("a", MessageRole.ASSISTANT, "v1"), msg("b", MessageRole.ASSISTANT, "v2"))
        assertEquals(0, n.select(-1).selectIndex)
        assertEquals(0, n.select(-100).selectIndex)
        assertEquals(1, n.select(1).selectIndex)
        assertEquals(1, n.select(2).selectIndex)
        assertEquals(1, n.select(999).selectIndex)
        // 钳制不改变候选
        assertEquals(n.candidates, n.select(999).candidates)
        assertEquals("n1", n.select(999).id)
    }

    @Test
    fun `node select on empty node returns same instance`() {
        val n = node("n1")
        assertSame(n, n.select(3))
        assertSame(n, n.select(0))
        assertSame(n, n.select(-1))
    }

    @Test
    fun `currentMessage is null for empty node and out-of-range selectIndex`() {
        assertNull(node("n1").currentMessage)
        // selectIndex 超上限（损坏数据）：守卫式折叠 null，不抛
        val corrupted = node("n1", msg("a", MessageRole.ASSISTANT, "v1"), selectIndex = 5)
        assertNull(corrupted.currentMessage)
        assertNull(corrupted.selected())
        // 负下标同款
        val negative = node("n1", msg("a", MessageRole.ASSISTANT, "v1"), selectIndex = -2)
        assertNull(negative.currentMessage)
        // 正常路径
        assertEquals("v1", node("n1", msg("a", MessageRole.ASSISTANT, "v1")).currentMessage?.content)
    }

    @Test
    fun `isSwitchable reflects candidate count`() {
        assertFalse(node("n1").isSwitchable())
        assertFalse(node("n1", msg("a", MessageRole.USER, "q")).isSwitchable())
        assertTrue(
            node("n1", msg("a", MessageRole.ASSISTANT, "v1"), msg("b", MessageRole.ASSISTANT, "v2")).isSwitchable()
        )
    }

    @Test
    fun `node role falls back to USER when empty`() {
        assertEquals(MessageRole.USER, node("n1").role)
        assertEquals(MessageRole.USER, node("n1", msg("u", MessageRole.USER, "q")).role)
        assertEquals(MessageRole.ASSISTANT, node("n1", msg("a", MessageRole.ASSISTANT, "v")).role)
        assertEquals(MessageRole.TOOL, node("n1", msg("t", MessageRole.TOOL, "out", createdAt = 1)).role)
    }

    // ═══ 消息：便捷判断 ═══

    @Test
    fun `message role helpers`() {
        val u = msg("u", MessageRole.USER, "q")
        val a = msg("a", MessageRole.ASSISTANT, "v")
        val s = msg("s", MessageRole.SYSTEM, "sys")
        val t = msg("t", MessageRole.TOOL, "out")
        assertTrue(u.isUser() && !u.isAssistant() && !u.isSystem() && !u.isTool())
        assertTrue(a.isAssistant() && !a.isUser())
        assertTrue(s.isSystem() && !s.isAssistant())
        assertTrue(t.isTool() && !t.isAssistant())
    }

    @Test
    fun `isEmptyPlaceholder only for blank assistant messages`() {
        assertTrue(msg("p", MessageRole.ASSISTANT, "").isEmptyPlaceholder())
        assertTrue(msg("p", MessageRole.ASSISTANT, "   ").isEmptyPlaceholder())
        assertFalse(msg("p", MessageRole.ASSISTANT, "内容").isEmptyPlaceholder())
        assertFalse(msg("p", MessageRole.USER, "").isEmptyPlaceholder())
        assertFalse(msg("p", MessageRole.TOOL, "").isEmptyPlaceholder())
    }

    // ═══ 序列化：roundtrip 字段级抽查 ═══

    @Test
    fun `message serialization roundtrip preserves all fields`() {
        val message = BranchMessage(
            id = "msg-1",
            role = MessageRole.ASSISTANT,
            content = "回答内容",
            createdAt = 1735689600000L,
            toolName = "web_search",
            thinking = "先分析再回答",
            meta = mapOf("model" to "model-x", "latencyMs" to "1200", "provider" to "openai")
        )
        val text = json.encodeToString(BranchMessage.serializer(), message)
        val back = json.decodeFromString(BranchMessage.serializer(), text)
        assertEquals(message, back)
        // 字段逐一抽查（防 equals 掩盖序列化丢字段）
        assertEquals("msg-1", back.id)
        assertEquals(MessageRole.ASSISTANT, back.role)
        assertEquals("回答内容", back.content)
        assertEquals(1735689600000L, back.createdAt)
        assertEquals("web_search", back.toolName)
        assertEquals("先分析再回答", back.thinking)
        assertEquals(3, back.meta.size)
        assertEquals("model-x", back.meta["model"])
        assertEquals("1200", back.meta["latencyMs"])
        assertEquals("openai", back.meta["provider"])
    }

    @Test
    fun `node serialization roundtrip preserves candidates and selectIndex`() {
        val n = node(
            "node-7",
            msg("c1", MessageRole.ASSISTANT, "第一版", createdAt = 100),
            msg("c2", MessageRole.ASSISTANT, "第二版", createdAt = 200),
            msg("c3", MessageRole.ASSISTANT, "第三版", createdAt = 300),
            selectIndex = 2
        )
        val back = json.decodeFromString(
            MessageBranchNode.serializer(),
            json.encodeToString(MessageBranchNode.serializer(), n)
        )
        assertEquals(n, back)
        assertEquals("node-7", back.id)
        assertEquals(3, back.candidateCount)
        assertEquals(2, back.selectIndex)
        assertEquals("第三版", back.currentMessage?.content)
        assertEquals(200L, back.candidates[1].createdAt)
    }

    @Test
    fun `tree serialization roundtrip preserves full shape`() {
        val tree = ConversationTree(
            id = "tree-1",
            title = "标题",
            nodes = listOf(
                node("n0", msg("u1", MessageRole.USER, "问题", createdAt = 10)),
                node(
                    "n1",
                    msg("a1", MessageRole.ASSISTANT, "回复一", createdAt = 20),
                    msg("a2", MessageRole.ASSISTANT, "回复二", createdAt = 21),
                    selectIndex = 1
                )
            ),
            createdAt = 10,
            updatedAt = 21
        )
        val back = json.decodeFromString(
            ConversationTree.serializer(),
            json.encodeToString(ConversationTree.serializer(), tree)
        )
        assertEquals(tree, back)
        assertEquals("tree-1", back.id)
        assertEquals("标题", back.title)
        assertEquals(2, back.nodes.size)
        assertEquals("n0", back.nodes[0].id)
        assertEquals(2, back.nodes[1].candidateCount)
        assertEquals(1, back.nodes[1].selectIndex)
        assertEquals(10L, back.createdAt)
        assertEquals(21L, back.updatedAt)
        assertEquals("回复二", back.currentMessages.last().content)
    }

    // ═══ 序列化：旧 JSON 前向兼容（缺字段 → 默认值）═══

    @Test
    fun `message json with missing defaulted fields loads with defaults`() {
        val raw = """{"id":"m1","role":"ASSISTANT","content":"hi"}"""
        val back = json.decodeFromString(BranchMessage.serializer(), raw)
        assertEquals("m1", back.id)
        assertEquals(MessageRole.ASSISTANT, back.role)
        assertEquals("hi", back.content)
        assertEquals(0L, back.createdAt)
        assertNull(back.toolName)
        assertEquals("", back.thinking)
        assertTrue(back.meta.isEmpty())
    }

    @Test
    fun `node json with missing fields loads with defaults`() {
        val withCandidates = """{"id":"node-1","candidates":[{"id":"m1","role":"USER","content":"q"}]}"""
        val back = json.decodeFromString(MessageBranchNode.serializer(), withCandidates)
        assertEquals("node-1", back.id)
        assertEquals(1, back.candidateCount)
        assertEquals(0, back.selectIndex)
        assertEquals("q", back.currentMessage?.content)

        val bare = """{"id":"node-2"}"""
        val emptyNode = json.decodeFromString(MessageBranchNode.serializer(), bare)
        assertEquals("node-2", emptyNode.id)
        assertTrue(emptyNode.candidates.isEmpty())
        assertEquals(0, emptyNode.selectIndex)
    }

    @Test
    fun `tree json with missing fields loads with defaults`() {
        val bare = """{"id":"t1"}"""
        val back = json.decodeFromString(ConversationTree.serializer(), bare)
        assertEquals("t1", back.id)
        assertEquals("", back.title)
        assertTrue(back.nodes.isEmpty())
        assertEquals(0L, back.createdAt)
        assertEquals(0L, back.updatedAt)
        assertEquals(0, back.messageCount)

        val partial = """{"id":"t2","title":"T","createdAt":5}"""
        val partialTree = json.decodeFromString(ConversationTree.serializer(), partial)
        assertEquals("t2", partialTree.id)
        assertEquals("T", partialTree.title)
        assertEquals(5L, partialTree.createdAt)
        assertEquals(0L, partialTree.updatedAt)
        assertTrue(partialTree.nodes.isEmpty())
    }

    @Test
    fun `unknown json fields are tolerated`() {
        val raw = """{"id":"m1","role":"USER","content":"q","futureFieldA":1,"futureFieldB":"x"}"""
        val back = json.decodeFromString(BranchMessage.serializer(), raw)
        assertEquals("m1", back.id)
        assertEquals("q", back.content)
    }

    // ═══ 树：线性化与查找 ═══

    @Test
    fun `currentMessages linearizes selected messages and skips empty or corrupted nodes`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("n0", msg("m0", MessageRole.USER, "q1")),
                node(
                    "n1",
                    msg("m1", MessageRole.ASSISTANT, "a1"),
                    msg("m2", MessageRole.ASSISTANT, "a2"),
                    selectIndex = 1
                ),
                node("n2"), // 空节点：跳过
                node("n3", msg("m3", MessageRole.ASSISTANT, "孤儿"), selectIndex = 9) // 坏下标：跳过
            )
        )
        assertEquals(listOf("q1", "a2"), tree.currentMessages.map { m -> m.content })
        assertEquals(listOf("m0", "m2"), tree.currentMessages.map { m -> m.id })
        assertEquals(2, tree.messageCount)
        assertEquals(2, tree.assistantTurnCount)
        assertEquals(1, tree.branchNodeCount)
    }

    @Test
    fun `messageCount and assistantTurnCount on empty tree`() {
        val tree = ConversationTree(id = "t1")
        assertEquals(0, tree.messageCount)
        assertEquals(0, tree.assistantTurnCount)
        assertEquals(0, tree.branchNodeCount)
        assertTrue(tree.currentMessages.isEmpty())
    }

    @Test
    fun `nodeOf findMessage and indexOfNode locate and miss`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("n0", msg("u1", MessageRole.USER, "q")),
                node(
                    "n1",
                    msg("a1", MessageRole.ASSISTANT, "v1"),
                    msg("a2", MessageRole.ASSISTANT, "v2"),
                    selectIndex = 1
                )
            )
        )
        assertEquals("n1", tree.nodeOf("a1")?.id)
        assertEquals("n1", tree.nodeOf("a2")?.id)
        assertEquals("n0", tree.nodeOf("u1")?.id)
        assertNull(tree.nodeOf("missing"))

        assertEquals("v2", tree.findMessage("a2")?.content)
        assertNull(tree.findMessage("missing"))

        assertEquals(0, tree.indexOfNode("n0"))
        assertEquals(1, tree.indexOfNode("n1"))
        assertEquals(-1, tree.indexOfNode("nope"))
    }

    @Test
    fun `equality is structural - copy differs only in changed field`() {
        val a = node("n0", msg("u1", MessageRole.USER, "q"))
        val b = node("n0", msg("u1", MessageRole.USER, "q"))
        assertEquals(a, b)
        val c = a.copy(selectIndex = 3)
        assertNotEquals(a, c)
        assertEquals(a.candidates, c.candidates)
    }
}
