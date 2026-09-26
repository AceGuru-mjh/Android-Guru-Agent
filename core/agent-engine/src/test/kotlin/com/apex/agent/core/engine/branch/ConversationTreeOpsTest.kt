package com.apex.agent.core.engine.branch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4-c — 会话树纯操作测试（RikkaHub ChatService 分支路径语义对位）。
 *
 * 覆盖：append 用户/assistant 语义、regenerateSlot 占位 + upsertCandidate
 * 幂等重试（同 id 两次 upsert → 单候选无重复）、editMessage 新分支/
 * 原位改写、deleteCandidate 下标钳制与节点丢弃、switchBranchRelative
 * 边界、forkAt 全新 id + 前缀截取、trimToMessage、sanitize 四类修复、
 * 每步操作后的 currentMessages 线性化，以及操作不改动源树的不可变性。
 */
class ConversationTreeOpsTest {

    private fun msg(id: String, role: MessageRole, content: String, createdAt: Long = 0) =
        BranchMessage(id = id, role = role, content = content, createdAt = createdAt)

    private fun node(id: String, vararg candidates: BranchMessage, selectIndex: Int = 0) =
        MessageBranchNode(id = id, candidates = candidates.toList(), selectIndex = selectIndex)

    /** 标准两节点树：user 问 + assistant 单候选答。 */
    private fun baseTree(): ConversationTree = ConversationTree(
        id = "t1",
        title = "会话",
        createdAt = 100,
        updatedAt = 100,
        nodes = listOf(
            node("t1-n0", msg("m-user", MessageRole.USER, "问题")),
            node("t1-n1", msg("m-a1", MessageRole.ASSISTANT, "第一版回复"))
        )
    )

    // ═══ appendUserMessage ═══

    @Test
    fun `appendUserMessage creates new single-candidate node and appends to linear view`() {
        val tree = appendUserMessage(baseTree(), msg("m-u2", MessageRole.USER, "追问"))
        assertEquals(3, tree.nodes.size)
        val last = tree.nodes.last()
        assertEquals(1, last.candidateCount)
        assertEquals(0, last.selectIndex)
        assertEquals("追问", last.currentMessage?.content)
        assertEquals("问题", tree.currentMessages[0].content)
        assertEquals(listOf("问题", "第一版回复", "追问"), tree.currentMessages.map { m -> m.content })
        assertEquals(1, tree.assistantTurnCount) // assistant 节点数不变（追加的是 user 节点）
    }

    @Test
    fun `appendUserMessage works on empty tree with derived node id`() {
        val tree = appendUserMessage(ConversationTree(id = "t1"), msg("m-u1", MessageRole.USER, "首问"))
        assertEquals(1, tree.nodes.size)
        assertEquals("t1-n0", tree.nodes[0].id)
        assertEquals("首问", tree.currentMessages.single().content)
    }

    @Test
    fun `appendUserMessage consecutive users land in separate nodes`() {
        var tree = appendUserMessage(ConversationTree(id = "t1"), msg("m-u1", MessageRole.USER, "一"))
        tree = appendUserMessage(tree, msg("m-u2", MessageRole.USER, "二"))
        assertEquals(2, tree.nodes.size)
        assertEquals(listOf("一", "二"), tree.currentMessages.map { m -> m.content })
        assertEquals(listOf("t1-n0", "t1-n1"), tree.nodes.map { n -> n.id })
    }

    @Test
    fun `appendUserMessage leaves updatedAt untouched - ops do not stamp time`() {
        val tree = appendUserMessage(baseTree(), msg("m-u2", MessageRole.USER, "追问"))
        assertEquals(100L, tree.updatedAt)
        assertEquals(100L, tree.createdAt)
        assertEquals("会话", tree.title)
    }

    // ═══ appendAssistantCandidate ═══

    @Test
    fun `appendAssistantCandidate after user node creates new assistant node`() {
        val tree = appendUserMessage(ConversationTree(id = "t1"), msg("m-u1", MessageRole.USER, "问"))
        val next = appendAssistantCandidate(tree, msg("m-a1", MessageRole.ASSISTANT, "答"))
        assertEquals(2, next.nodes.size)
        assertEquals(MessageRole.ASSISTANT, next.nodes[1].role)
        assertEquals(1, next.nodes[1].candidateCount)
        assertEquals("答", next.currentMessages.last().content)
    }

    @Test
    fun `appendAssistantCandidate after assistant node adds candidate and selects it`() {
        val next = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        assertEquals(2, next.nodes.size) // 不新建节点
        val last = next.nodes[1]
        assertEquals(2, last.candidateCount)
        assertEquals(1, last.selectIndex)
        assertEquals(listOf("问题", "第二版"), next.currentMessages.map { m -> m.content })
        assertTrue(last.isSwitchable())
    }

    @Test
    fun `appendAssistantCandidate on empty tree creates assistant node`() {
        val tree = appendAssistantCandidate(ConversationTree(id = "t1"), msg("m-a1", MessageRole.ASSISTANT, "开场"))
        assertEquals(1, tree.nodes.size)
        assertEquals(MessageRole.ASSISTANT, tree.nodes[0].role)
        assertEquals("t1-n0", tree.nodes[0].id)
    }

    @Test
    fun `appendAssistantCandidate after tool node creates new node - migrated tail`() {
        // 迁移产生的 tool 节点结尾：新 assistant 不并入（不同槽位语义）
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(node("t1-n0", msg("m-t1", MessageRole.TOOL, "工具输出", createdAt = 5)))
        )
        val next = appendAssistantCandidate(tree, msg("m-a1", MessageRole.ASSISTANT, "总结"))
        assertEquals(2, next.nodes.size)
        assertEquals(MessageRole.ASSISTANT, next.nodes[1].role)
    }

    // ═══ regenerateSlot ═══

    @Test
    fun `regenerateSlot appends empty placeholder candidate and selects it`() {
        val clockValue = 555L
        val result = regenerateSlot(baseTree(), "t1-n1", clock = { clockValue })
        assertNotNull(result)
        val tree = result!!.tree
        assertEquals(2, tree.nodes.size)
        val slot = tree.nodes[1]
        assertEquals(2, slot.candidateCount)
        val placeholder = slot.currentMessage!!
        assertEquals("", placeholder.content)
        assertTrue(placeholder.isAssistant())
        assertEquals(clockValue, placeholder.createdAt)
        assertEquals("t1-n1_regen", placeholder.id)
        assertEquals("t1-n1", result.info.nodeId)
        assertEquals("t1-n1_regen", result.info.placeholderMessageId)
        // 原候选仍在（保留历史）
        assertEquals("第一版回复", slot.candidates[0].content)
        assertEquals(1, slot.selectIndex) // 占位成为新选中候选
    }

    @Test
    fun `regenerateSlot honors caller-provided placeholder id`() {
        val result = regenerateSlot(baseTree(), "t1-n1", placeholderMessageId = "pre-created-42")
        assertNotNull(result)
        assertEquals("pre-created-42", result!!.info.placeholderMessageId)
        assertEquals("pre-created-42", result.tree.nodes[1].currentMessage?.id)
    }

    @Test
    fun `regenerateSlot reuses existing blank placeholder on retry - idempotent`() {
        val first = regenerateSlot(baseTree(), "t1-n1")!!
        // 中断后重入：选中候选已是空占位 → 复用其 id，不追加第二个占位
        val second = regenerateSlot(first.tree, "t1-n1")
        assertNotNull(second)
        assertSame(first.tree, second!!.tree) // 原树原样返回
        assertEquals(first.info.placeholderMessageId, second.info.placeholderMessageId)
        assertEquals(2, second.tree.nodes[1].candidateCount)
    }

    @Test
    fun `regenerateSlot on unknown node returns null`() {
        assertNull(regenerateSlot(baseTree(), "no-such-node"))
    }

    @Test
    fun `regenerateSlot on user node returns null - only assistant slots regenerate`() {
        assertNull(regenerateSlot(baseTree(), "t1-n0"))
    }

    @Test
    fun `regenerateSlot provided id conflicting with existing message falls back to fresh id`() {
        // "m-user" 已存在 → 提供的 id 被防御性拒绝，回退派生 id
        val result = regenerateSlot(baseTree(), "t1-n1", placeholderMessageId = "m-user")
        assertNotNull(result)
        assertEquals("t1-n1_regen", result!!.info.placeholderMessageId)
        assertNotEquals("m-user", result.info.placeholderMessageId)
    }

    // ═══ upsertCandidate（流式落树 + 幂等重试）═══

    @Test
    fun `upsertCandidate replaces existing candidate in place keeping selection`() {
        // 两候选、选中第二个：替换第二个 → 数量不变、仍选中
        var tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        tree = upsertCandidate(tree, msg("m-a2", MessageRole.ASSISTANT, "第二版（流式更新）"))
        val slot = tree.nodes[1]
        assertEquals(2, slot.candidateCount)
        assertEquals(1, slot.selectIndex)
        assertEquals("第二版（流式更新）", slot.currentMessage?.content)
        assertEquals("第一版回复", slot.candidates[0].content)
    }

    @Test
    fun `upsertCandidate on non-selected candidate keeps selection pointing elsewhere`() {
        var tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        // 替换未选中的第一候选 → 选择仍停在第二候选
        tree = upsertCandidate(tree, msg("m-a1", MessageRole.ASSISTANT, "第一版（修订）"))
        val slot = tree.nodes[1]
        assertEquals(2, slot.candidateCount)
        assertEquals(1, slot.selectIndex)
        assertEquals("第二版", slot.currentMessage?.content)
        assertEquals("第一版（修订）", slot.candidates[0].content)
    }

    @Test
    fun `regenerate flow - upsert twice with same placeholder id yields single candidate`() {
        // RikkaHub「预创建消息 id」全流程：占位 → 分片替换 → 重试替换 → 无重复分支
        val regen = regenerateSlot(baseTree(), "t1-n1")!!
        val placeholderId = regen.info.placeholderMessageId

        val partial = upsertCandidate(
            regen.tree,
            msg(placeholderId, MessageRole.ASSISTANT, "半截回答", createdAt = 7)
        )
        assertEquals(2, partial.nodes[1].candidateCount)
        assertEquals("半截回答", partial.nodes[1].currentMessage?.content)
        assertEquals(7L, partial.nodes[1].currentMessage?.createdAt)

        // 重试/续流：同 id 再 upsert → 仍是原位替换（幂等）
        val complete = upsertCandidate(
            partial,
            msg(placeholderId, MessageRole.ASSISTANT, "完整回答")
        )
        assertEquals(2, complete.nodes[1].candidateCount)
        assertEquals(1, complete.nodes[1].selectIndex)
        assertEquals("完整回答", complete.nodes[1].currentMessage?.content)
        assertEquals(listOf("问题", "完整回答"), complete.currentMessages.map { m -> m.content })
    }

    @Test
    fun `upsertCandidate with unknown id appends as new assistant candidate`() {
        // 结尾是 assistant 节点 → 追加候选并选中
        val tree = upsertCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "新候选"))
        assertEquals(2, tree.nodes.size)
        assertEquals(2, tree.nodes[1].candidateCount)
        assertEquals("新候选", tree.currentMessages.last().content)

        // 结尾是 user 节点 → 新建 assistant 节点
        val userOnly = appendUserMessage(ConversationTree(id = "t1"), msg("m-u1", MessageRole.USER, "问"))
        val next = upsertCandidate(userOnly, msg("m-a1", MessageRole.ASSISTANT, "答"))
        assertEquals(2, next.nodes.size)
        assertEquals("答", next.currentMessages.last().content)
    }

    // ═══ editMessage ═══

    @Test
    fun `editMessage keepHistory adds new candidate with fresh id and selects it`() {
        val tree = editMessage(baseTree(), "m-a1", "改写后的回复")
        assertEquals(2, tree.nodes.size)
        val slot = tree.nodes[1]
        assertEquals(2, slot.candidateCount) // 分支数 +1
        assertEquals(1, slot.selectIndex)
        assertEquals("m-a1_edit", slot.currentMessage?.id)
        assertEquals("改写后的回复", slot.currentMessage?.content)
        // 原消息保留为另一分支
        assertEquals("第一版回复", slot.candidates[0].content)
        assertEquals("m-a1", slot.candidates[0].id)
        assertEquals(listOf("问题", "改写后的回复"), tree.currentMessages.map { m -> m.content })
    }

    @Test
    fun `editMessage keeps role toolName thinking and meta on the new candidate`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node(
                    "t1-n0",
                    BranchMessage(
                        id = "m-a1", role = MessageRole.ASSISTANT, content = "原",
                        createdAt = 99, toolName = "web_search", thinking = "想",
                        meta = mapOf("model" to "m-x")
                    )
                )
            )
        )
        val edited = editMessage(tree, "m-a1", "新")
        val candidate = edited.nodes[0].currentMessage!!
        assertEquals(MessageRole.ASSISTANT, candidate.role)
        assertEquals(99L, candidate.createdAt)
        assertEquals("web_search", candidate.toolName)
        assertEquals("想", candidate.thinking)
        assertEquals("m-x", candidate.meta["model"])
    }

    @Test
    fun `editMessage fresh id bumps on collision`() {
        // 树中已存在 "m-a1_edit" → 新候选取 "m-a1_edit2"
        val tree = appendAssistantCandidate(baseTree(), msg("m-a1_edit", MessageRole.ASSISTANT, "占位名"))
        val edited = editMessage(tree, "m-a1", "改写")
        val slot = edited.nodes[1]
        assertEquals(3, slot.candidateCount)
        assertEquals("m-a1_edit2", slot.currentMessage?.id)
    }

    @Test
    fun `editMessage in-place mode replaces content keeping id position and selection`() {
        var tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        tree = editMessage(tree, "m-a2", "静默修订", keepHistory = false)
        val slot = tree.nodes[1]
        assertEquals(2, slot.candidateCount) // 分支数不变
        assertEquals(1, slot.selectIndex)
        assertEquals("m-a2", slot.currentMessage?.id)
        assertEquals("静默修订", slot.currentMessage?.content)
        assertEquals("第一版回复", slot.candidates[0].content)
    }

    @Test
    fun `editMessage unknown id returns same instance`() {
        val tree = baseTree()
        assertSame(tree, editMessage(tree, "missing", "新内容"))
    }

    // ═══ deleteCandidate ═══

    @Test
    fun `deleteCandidate clamps selectIndex to lastIndex - delete selected last candidate`() {
        // [a,b,c] 选中 c(下标2)：删 c → [a,b]，下标钳到 1 → 选中 b
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node(
                    "t1-n0",
                    msg("a", MessageRole.ASSISTANT, "A"),
                    msg("b", MessageRole.ASSISTANT, "B"),
                    msg("c", MessageRole.ASSISTANT, "C"),
                    selectIndex = 2
                )
            )
        )
        val next = deleteCandidate(tree, "c")
        val slot = next.nodes[0]
        assertEquals(2, slot.candidateCount)
        assertEquals(1, slot.selectIndex)
        assertEquals("B", slot.currentMessage?.content)
    }

    @Test
    fun `deleteCandidate on middle candidate keeps successor at coerced index`() {
        // [a,b,c] 选中 b(下标1)：删 b → [a,c]，下标 1 → 选中 c（后继）
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node(
                    "t1-n0",
                    msg("a", MessageRole.ASSISTANT, "A"),
                    msg("b", MessageRole.ASSISTANT, "B"),
                    msg("c", MessageRole.ASSISTANT, "C"),
                    selectIndex = 1
                )
            )
        )
        val next = deleteCandidate(tree, "b")
        assertEquals(2, next.nodes[0].candidateCount)
        assertEquals(1, next.nodes[0].selectIndex)
        assertEquals("C", next.nodes[0].currentMessage?.content)
    }

    @Test
    fun `deleteCandidate drops whole node when candidates run empty`() {
        val tree = deleteCandidate(baseTree(), "m-a1")
        assertEquals(1, tree.nodes.size)
        assertEquals("t1-n0", tree.nodes[0].id)
        assertEquals(listOf("问题"), tree.currentMessages.map { m -> m.content })
        assertEquals(0, tree.assistantTurnCount)
    }

    @Test
    fun `deleteCandidate removes only the targeted candidate in multi-node tree`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("t1-n0", msg("m-u1", MessageRole.USER, "问")),
                node("t1-n1", msg("m-a1", MessageRole.ASSISTANT, "答"), msg("m-a2", MessageRole.ASSISTANT, "答2"), selectIndex = 1),
                node("t1-n2", msg("m-u2", MessageRole.USER, "问2"))
            )
        )
        val next = deleteCandidate(tree, "m-a1")
        assertEquals(3, next.nodes.size)
        assertEquals(listOf("m-a2"), next.nodes[1].candidates.map { m -> m.id })
        assertEquals(0, next.nodes[1].selectIndex)
        assertEquals(listOf("问", "答2", "问2"), next.currentMessages.map { m -> m.content })
    }

    @Test
    fun `deleteCandidate unknown id returns same instance`() {
        val tree = baseTree()
        assertSame(tree, deleteCandidate(tree, "missing"))
    }

    // ═══ switchBranch / switchBranchRelative ═══

    @Test
    fun `switchBranch changes selection and linear view`() {
        var tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        tree = switchBranch(tree, "t1-n1", 0)
        assertEquals(0, tree.nodes[1].selectIndex)
        assertEquals(listOf("问题", "第一版回复"), tree.currentMessages.map { m -> m.content })
        tree = switchBranch(tree, "t1-n1", 1)
        assertEquals(listOf("问题", "第二版"), tree.currentMessages.map { m -> m.content })
    }

    @Test
    fun `switchBranch invalid index or unknown node returns same instance`() {
        val tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        assertSame(tree, switchBranch(tree, "t1-n1", 2))
        assertSame(tree, switchBranch(tree, "t1-n1", -1))
        assertSame(tree, switchBranch(tree, "nope", 0))
    }

    @Test
    fun `switchBranchRelative moves forward and backward`() {
        var tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "第二版"))
        tree = switchBranchRelative(tree, "t1-n1", -1)
        assertEquals(0, tree.nodes[1].selectIndex)
        assertEquals("第一版回复", tree.currentMessages.last().content)
        tree = switchBranchRelative(tree, "t1-n1", 1)
        assertEquals(1, tree.nodes[1].selectIndex)
        assertEquals("第二版", tree.currentMessages.last().content)
    }

    @Test
    fun `switchBranchRelative respects bounds - no wraparound`() {
        // 0 号位不能再 ◀
        val base = baseTree()
        assertSame(base, switchBranchRelative(base, "t1-n1", -1))

        // 末位不能再 ▶
        val tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "v2"))
        val atLast = switchBranch(tree, "t1-n1", 1)
        assertSame(atLast, switchBranchRelative(atLast, "t1-n1", 1))

        // 越界大步长同样无效
        assertSame(tree, switchBranchRelative(tree, "t1-n1", 9))
        // delta 0 无操作
        assertSame(tree, switchBranchRelative(tree, "t1-n1", 0))
        // 未知节点无操作
        assertSame(tree, switchBranchRelative(tree, "nope", 1))
    }

    // ═══ forkAt ═══

    private fun threeTurnTree(): ConversationTree = ConversationTree(
        id = "t1",
        title = "原会话",
        createdAt = 100,
        updatedAt = 300,
        nodes = listOf(
            node("t1-n0", msg("m-u1", MessageRole.USER, "问1", createdAt = 100)),
            node(
                "t1-n1",
                msg("m-a1", MessageRole.ASSISTANT, "答1-A", createdAt = 110),
                msg("m-a2", MessageRole.ASSISTANT, "答1-B", createdAt = 120),
                selectIndex = 1
            ),
            node("t1-n2", msg("m-u2", MessageRole.USER, "问2", createdAt = 200)),
            node("t1-n3", msg("m-a3", MessageRole.ASSISTANT, "答2", createdAt = 210))
        )
    )

    @Test
    fun `forkAt copies prefix only with fresh node and message ids`() {
        val fork = forkAt(threeTurnTree(), "m-a2", "t-fork", clock = { 999L })!!
        assertEquals("t-fork", fork.id)
        assertEquals(2, fork.nodes.size) // 前缀：n0 + n1（含）
        assertEquals("原会话", fork.title)
        // 节点 id 全新（默认 _fork 后缀）
        assertEquals(listOf("t1-n0_fork", "t1-n1_fork"), fork.nodes.map { n -> n.id })
        // 消息 id 全新
        val forkedIds = fork.nodes.flatMap { n -> n.candidates.map { m -> m.id } }
        assertEquals(listOf("m-u1_fork", "m-a1_fork", "m-a2_fork"), forkedIds)
        val sourceIds = threeTurnTree().nodes.take(2).flatMap { n -> n.candidates.map { m -> m.id } }
        assertTrue(forkedIds.none { it in sourceIds })
        // 内容与选择保留
        assertEquals(listOf("问1", "答1-B"), fork.currentMessages.map { m -> m.content })
        assertEquals(1, fork.nodes[1].selectIndex)
    }

    @Test
    fun `forkAt carries all candidates of the boundary node`() {
        val fork = forkAt(threeTurnTree(), "m-u2", "t-fork", clock = { 1L })!!
        assertEquals(3, fork.nodes.size) // 前缀含 n2（m-u2 所在节点）
        assertEquals(2, fork.nodes[1].candidateCount) // 边界节点的全部候选都带过去
        assertEquals(listOf("m-a1_fork", "m-a2_fork"), fork.nodes[1].candidates.map { m -> m.id })
    }

    @Test
    fun `forkAt stamps clock and appends title suffix`() {
        val fork = forkAt(threeTurnTree(), "m-a1", "t-fork2", titleSuffix = " (2)", clock = { 888L })!!
        assertEquals("原会话 (2)", fork.title)
        assertEquals(888L, fork.createdAt)
        assertEquals(888L, fork.updatedAt)
    }

    @Test
    fun `forkAt with unknown message returns null`() {
        assertNull(forkAt(threeTurnTree(), "missing", "t-fork", clock = { 0L }))
    }

    @Test
    fun `forkAt honors custom idGenerator`() {
        val fork = forkAt(
            threeTurnTree(), "m-a2", "t-fork3",
            clock = { 0L },
            idGenerator = { oldId -> "x-" + oldId }
        )!!
        assertEquals(listOf("x-t1-n0", "x-t1-n1"), fork.nodes.map { n -> n.id })
        assertEquals("x-m-u1", fork.nodes[0].candidates[0].id)
    }

    @Test
    fun `forkAt dedupes generated ids when idGenerator is non-injective`() {
        // 非单射生成器（所有旧 id 映射到同一值）→ fork 内生成结果撞车，加序号去重
        val fork = forkAt(
            threeTurnTree(), "m-a2", "t-fork",
            clock = { 0L },
            idGenerator = { _ -> "dup" }
        )!!
        assertEquals(listOf("dup", "dup2"), fork.nodes.map { n -> n.id })
        assertEquals(
            listOf("dup", "dup2", "dup3"),
            fork.nodes.flatMap { n -> n.candidates.map { m -> m.id } }
        )
        // 节点 id 与消息 id 命名空间互不干扰：n0 节点 id "dup" 与其消息 id "dup" 共存合法
        assertEquals("dup", fork.nodes[0].id)
        assertEquals("dup", fork.nodes[0].candidates[0].id)
    }

    // ═══ trimToMessage ═══

    @Test
    fun `trimToMessage keeps prefix inclusive of the message node`() {
        val tree = trimToMessage(threeTurnTree(), "m-u2")
        assertEquals(3, tree.nodes.size)
        assertEquals(listOf("t1-n0", "t1-n1", "t1-n2"), tree.nodes.map { n -> n.id })
        assertEquals(listOf("问1", "答1-B", "问2"), tree.currentMessages.map { m -> m.content })
    }

    @Test
    fun `trimToMessage on tail node or unknown message returns same instance`() {
        val tree = threeTurnTree()
        assertSame(tree, trimToMessage(tree, "m-a3")) // 已是尾部
        assertSame(tree, trimToMessage(tree, "missing"))
    }

    // ═══ sanitize ═══

    @Test
    fun `sanitize drops empty nodes`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("t1-n0", msg("m-u1", MessageRole.USER, "问")),
                node("t1-n1"), // 空
                node("t1-n2", msg("m-a1", MessageRole.ASSISTANT, "答"))
            )
        )
        val clean = sanitize(tree)
        assertEquals(2, clean.nodes.size)
        assertEquals(listOf("t1-n0", "t1-n2"), clean.nodes.map { n -> n.id })
        assertEquals(listOf("问", "答"), clean.currentMessages.map { m -> m.content })
    }

    @Test
    fun `sanitize clamps bad selectIndex into range`() {
        val overflow = node("t1-n0", msg("a", MessageRole.ASSISTANT, "A"), msg("b", MessageRole.ASSISTANT, "B"), selectIndex = 7)
        val negative = node("t1-n1", msg("c", MessageRole.ASSISTANT, "C"), selectIndex = -3)
        val tree = ConversationTree(id = "t1", nodes = listOf(overflow, negative))
        val clean = sanitize(tree)
        assertEquals(1, clean.nodes[0].selectIndex)
        assertEquals(0, clean.nodes[1].selectIndex)
        assertEquals(listOf("B", "C"), clean.currentMessages.map { m -> m.content })
    }

    @Test
    fun `sanitize dedupes repeated message ids keeping first occurrence`() {
        // 同 id 出现在两个节点：保留首现，后现节点因此清空被整节点丢弃
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("t1-n0", msg("m-u1", MessageRole.USER, "问")),
                node(
                    "t1-n1",
                    msg("dup", MessageRole.ASSISTANT, "首现"),
                    msg("m-a2", MessageRole.ASSISTANT, "保留")
                ),
                node("t1-n2", msg("dup", MessageRole.ASSISTANT, "重复"))
            )
        )
        val clean = sanitize(tree)
        assertEquals(2, clean.nodes.size)
        assertEquals("首现", clean.findMessage("dup")?.content)
        assertEquals(2, clean.nodes[1].candidateCount) // 非重复候选全部保留
        assertEquals(listOf("首现", "保留"), clean.nodes[1].candidates.map { m -> m.content })
        // currentMessages 只含各节点选中候选（选中下标 0 → 首现）
        assertEquals(listOf("问", "首现"), clean.currentMessages.map { m -> m.content })
        assertEquals("保留", clean.findMessage("m-a2")?.content)
    }

    @Test
    fun `sanitize sorts strictly out-of-order nodes stably by createdAt`() {
        // 时间戳 [30, 10, 20]（严格逆序存在）→ 排序为 [10, 20, 30]；
        // 相等时间戳保持原序。
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("n-late", msg("m3", MessageRole.USER, "晚", createdAt = 30)),
                node("n-early", msg("m1", MessageRole.USER, "早", createdAt = 10)),
                node("n-mid", msg("m2", MessageRole.USER, "中", createdAt = 20)),
                node("n-tie-a", msg("m4", MessageRole.USER, "同甲", createdAt = 20)),
                node("n-tie-b", msg("m5", MessageRole.USER, "同乙", createdAt = 20))
            )
        )
        val clean = sanitize(tree)
        assertEquals(
            listOf("n-early", "n-mid", "n-tie-a", "n-tie-b", "n-late"),
            clean.nodes.map { n -> n.id }
        )
    }

    @Test
    fun `sanitize leaves monotonic order untouched even with equal timestamps`() {
        // 全 0（缺省时间戳）：无严格逆序 → 原序不动
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("n0", msg("a", MessageRole.USER, "一")),
                node("n1", msg("b", MessageRole.USER, "二")),
                node("n2", msg("c", MessageRole.USER, "三"))
            )
        )
        assertSame(tree, sanitize(tree))
    }

    @Test
    fun `sanitize on already-clean tree returns same instance`() {
        val tree = appendAssistantCandidate(baseTree(), msg("m-a2", MessageRole.ASSISTANT, "v2"))
        assertSame(tree, sanitize(tree))
    }

    @Test
    fun `sanitize repairs combined damage`() {
        val tree = ConversationTree(
            id = "t1",
            nodes = listOf(
                node("n0", msg("m1", MessageRole.USER, "一", createdAt = 50)),
                node("n1"), // 空节点
                node("n2", msg("m2", MessageRole.USER, "二", createdAt = 10), selectIndex = -1),
                node("n3", msg("m1", MessageRole.USER, "一（重复）", createdAt = 60)),
                node("n4", msg("m3", MessageRole.USER, "三", createdAt = 20), selectIndex = 99)
            )
        )
        val clean = sanitize(tree)
        assertEquals(3, clean.nodes.size)
        // 乱序修复：10 → 20 → 50
        assertEquals(listOf("二", "三", "一"), clean.currentMessages.map { m -> m.content })
        assertTrue(clean.nodes.all { n -> n.selectIndex == 0 })
        assertEquals(3, clean.messageCount)
    }

    // ═══ 不可变性 ═══

    @Test
    fun `operations never mutate the source tree`() {
        val source = baseTree()
        appendUserMessage(source, msg("m-u2", MessageRole.USER, "追问"))
        appendAssistantCandidate(source, msg("m-a2", MessageRole.ASSISTANT, "v2"))
        regenerateSlot(source, "t1-n1")
        editMessage(source, "m-a1", "改写")
        deleteCandidate(source, "m-a1")
        switchBranch(source, "t1-n1", 5)
        trimToMessage(source, "m-a1")
        // 源树原样：两节点、单候选、原选择
        assertEquals(2, source.nodes.size)
        assertEquals(1, source.nodes[1].candidateCount)
        assertEquals(0, source.nodes[1].selectIndex)
        assertEquals(listOf("问题", "第一版回复"), source.currentMessages.map { m -> m.content })
    }
}
