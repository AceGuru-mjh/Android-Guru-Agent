package com.apex.agent.core.engine.branch

/**
 * ═══ 会话树纯操作（4-c）═══
 *
 * RikkaHub ChatService 全部分支变更路径的纯函数移植（rikkahub 仓库
 * service 包 ChatService.kt）：
 *
 * | RikkaHub ChatService 操作            | 本文件函数                        |
 * |---------------------------------------|-----------------------------------|
 * | 发送用户消息（新节点）                | [appendUserMessage]              |
 * | 生成新回复（append 且选中，updateCurrentMessages） | [appendAssistantCandidate] |
 * | regenerateAtMessage（预创建空消息占位）| [regenerateSlot] + [upsertCandidate] |
 * | editMessage（编辑 = 新分支）           | [editMessage]                    |
 * | buildConversationAfterMessageDelete   | [deleteCandidate]                |
 * | selectMessageNode（◀ ▶ 切换）          | [switchBranch] / [switchBranchRelative] |
 * | forkConversationAtMessage             | [forkAt]                         |
 * | （再生成前截断上下文）                 | [trimToMessage]                  |
 * | checkInvalidMessages（无效分支清理）   | [sanitize]                       |
 *
 * ## 不可变纪律
 *
 * 全部操作返回新 [ConversationTree]（写时复制；节点列表只替换受影响
 * 节点）。旧实例可被并发读者继续持有——UI 快照、落盘协程捕获不可变
 * 快照（fire-and-forget 纪律）都因此安全。**失败路径（找不到 id /
 * 非法参数）不抛异常：返回原实例（同一引用，可用 === 判定「无操作」）
 * 或 null（fork/regenerate 这类「必须命中才有产物」的语义）**——
 * 防御式 IO 纪律在纯函数层的对位。
 *
 * ## 时间戳纪律
 *
 * 操作层不盖 updatedAt（结构变更 ≠ 内容定稿）；持久化层
 * （[ConversationTreeStore.save]）统一盖章。唯一例外：[forkAt] 显式
 * 注入 clock（新树诞生时刻）；[regenerateSlot] 的占位消息 createdAt
 * 也取注入 clock（默认 0，测试确定性）。
 *
 * ## 再生成流（RikkaHub「预创建消息 id」技巧的移植）
 *
 * ```
 * 1. regenerateSlot(tree, nodeId)            → 树尾部追加空占位候选并选中
 *                                             （返回占位 id，见 RegenerateInfo）
 * 2. LLM 流式生成 → 分片累计到某条 BranchMessage（id = 占位 id）
 * 3. upsertCandidate(tree, partialMessage)   → 按 id 原位替换（不新增候选）
 * 4. 重复 3（重试/续流）                      → 仍然原位替换（幂等，无重复分支）
 * ```
 *
 * 崩溃后重入同一流程：regenerateSlot 发现选中候选已是空占位 → 直接
 * 复用其 id（不追加第二个占位）——与 rikkahub「重试也不产生重复
 * 分支」的保证一致。
 */
// ═══════════════════════════ 结果类型 ═══════════════════════════

/** 再生成占位信息：调用方拿着 [placeholderMessageId] 走 upsert 回填。 */
data class RegenerateInfo(
    /** 被标记再生成的节点 id。 */
    val nodeId: String,

    /** 新建的空占位消息 id（或复用的既有占位 id）。 */
    val placeholderMessageId: String
)

/** regenerateSlot 的返回：新树 + 占位信息。 */
data class RegenerateResult(
    /** 追加了占位候选（或复用既有占位）的新树。 */
    val tree: ConversationTree,
    /** 占位信息。 */
    val info: RegenerateInfo
)

// ═══════════════════════════ 追加 ═══════════════════════════

/**
 * 追加用户消息：新节点（单候选，选中）。
 *
 * RikkaHub 语义：user 消息永远是新槽位（不可再生成、不可多候选）。
 * 消息 id 由调用方保证树内唯一（迁移/新消息皆然）。角色不强制校验
 * USER（SYSTEM 开场白同样走单节点路径——防御宽容，调用方语义自管）。
 *
 * 节点 id 派生："{treeId}-n{nodes.size}"，撞车时追加序号（确定性）。
 */
fun appendUserMessage(tree: ConversationTree, message: BranchMessage): ConversationTree {
    val nodeId = freshNodeId(tree, "${tree.id}-n${tree.nodes.size}")
    val node = MessageBranchNode(id = nodeId, candidates = listOf(message), selectIndex = 0)
    return tree.copy(nodes = tree.nodes + node)
}

/**
 * 追加 assistant 候选（RikkaHub updateCurrentMessages 的「append 且
 * selectIndex 指向新消息」路径）：
 *
 * - 最后一个节点是 assistant 节点 → 追加为该节点新候选**并选中**；
 * - 否则（user 节点结尾 / 空树 / tool 节点结尾）→ 新建 assistant 节点
 *   （单候选，选中）。
 *
 * 流式场景请改用 [upsertCandidate]（按 id 原位替换，幂等），本函数
 * 只用于「回合定稿入树」。
 */
fun appendAssistantCandidate(tree: ConversationTree, message: BranchMessage): ConversationTree {
    val lastNode = tree.nodes.lastOrNull()
    if (lastNode != null && lastNode.role == MessageRole.ASSISTANT) {
        val newCandidates = lastNode.candidates + message
        val newNode = lastNode.copy(candidates = newCandidates, selectIndex = newCandidates.lastIndex)
        return tree.copy(nodes = tree.nodes.toMutableList().apply { set(tree.nodes.lastIndex, newNode) })
    }
    val nodeId = freshNodeId(tree, "${tree.id}-n${tree.nodes.size}")
    val node = MessageBranchNode(id = nodeId, candidates = listOf(message), selectIndex = 0)
    return tree.copy(nodes = tree.nodes + node)
}

// ═══════════════════════════ 再生成 ═══════════════════════════

/**
 * 标记一个 assistant 节点再生成：追加空占位候选（content 空、角色
 * ASSISTANT、createdAt 取 [clock]）并选中它。
 *
 * RikkaHub regenerateAtMessage 对位（rikkahub ChatService）：真实生成
 * 前预创建空 assistant 消息并复用同一 ID，之后按 id 原位替换——本函数
 * 即「预创建」步骤，返回的 [RegenerateInfo.placeholderMessageId] 就是
 * 回填句柄：
 *
 * - **幂等重入**：若该节点选中候选已是空占位（上次流程中断），直接
 *   复用其 id 返回原树——重试不产生重复分支；
 * - [placeholderMessageId] 由调用方显式提供（推荐，跨进程恢复场景
 *   稳定）或留空自动派生（"{nodeId}_regen"，撞车加序号）；提供的 id
 *   若已存在于树中（冲突）→ 回退自动派生（防御）。
 *
 * @return null：节点不存在，或非 assistant 节点（user 节点不可再生成——
 *   调用方应改用 trimToMessage + appendAssistantCandidate 组合）。
 */
fun regenerateSlot(
    tree: ConversationTree,
    nodeId: String,
    placeholderMessageId: String? = null,
    clock: () -> Long = { 0L }
): RegenerateResult? {
    val nodeIndex = tree.indexOfNode(nodeId)
    if (nodeIndex < 0) return null
    val node = tree.nodes[nodeIndex]
    if (node.role != MessageRole.ASSISTANT) return null

    // 幂等重入：选中候选已是空占位 → 复用（原树原样返回）
    val selected = node.currentMessage
    if (selected != null && selected.isEmptyPlaceholder()) {
        return RegenerateResult(tree, RegenerateInfo(nodeId, selected.id))
    }

    val existingIds = allMessageIds(tree)
    val resolvedId = when {
        placeholderMessageId != null && placeholderMessageId !in existingIds -> placeholderMessageId
        else -> freshMessageId(existingIds, "${nodeId}_regen")
    }
    val placeholder = BranchMessage(
        id = resolvedId,
        role = MessageRole.ASSISTANT,
        content = "",
        createdAt = clock()
    )
    val newCandidates = node.candidates + placeholder
    val newNode = node.copy(candidates = newCandidates, selectIndex = newCandidates.lastIndex)
    val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
    return RegenerateResult(tree.copy(nodes = newNodes), RegenerateInfo(nodeId, resolvedId))
}

/**
 * 按 id 插入或原位替换候选（RikkaHub updateCurrentMessages 的「按
 * message.id 匹配则替换，否则 append 且选中」全流程对位）：
 *
 * - 树中任意节点已有同 id 候选 → **原位替换**（selectIndex 不动：被替换
 *   的若是选中候选则保持选中，否则选中原样）——流式分片/重试幂等；
 * - 无同 id 候选 → 走 [appendAssistantCandidate]（新候选且选中）。
 *
 * 这是流式生成落树的唯一入口：首片经 append 路径入树，后续分片全走
 * 原位替换——候选数量稳定为「回合数 + 再生成次数」，永无重复分支。
 */
fun upsertCandidate(tree: ConversationTree, message: BranchMessage): ConversationTree {
    for ((nodeIndex, node) in tree.nodes.withIndex()) {
        val candidateIndex = node.candidates.indexOfFirst { it.id == message.id }
        if (candidateIndex < 0) continue
        val newCandidates = node.candidates.toMutableList().apply { set(candidateIndex, message) }
        val newNode = node.copy(candidates = newCandidates)
        val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
        return tree.copy(nodes = newNodes)
    }
    return appendAssistantCandidate(tree, message)
}

// ═══════════════════════════ 编辑 ═══════════════════════════

/**
 * 编辑消息（RikkaHub editMessage 对位）：
 *
 * - **keepHistory = true（默认，RikkaHub 语义）**：编辑 = 新分支——
 *   同节点追加新候选（**全新 id** "{messageId}_edit" 派生、撞车加序号；
 *   角色/createdAt/toolName/thinking/meta 继承原消息，仅 content 换新）
 *   并选中它；原消息保留为另一分支；
 * - **keepHistory = false**：原位改写——同 id 同下标仅替换 content，
 *   不留历史（selectIndex 不动）。
 *
 * 消息不存在 → 返回原实例（无操作，=== 可判定）。
 */
fun editMessage(
    tree: ConversationTree,
    messageId: String,
    newContent: String,
    keepHistory: Boolean = true
): ConversationTree {
    for ((nodeIndex, node) in tree.nodes.withIndex()) {
        val candidateIndex = node.candidates.indexOfFirst { it.id == messageId }
        if (candidateIndex < 0) continue
        val original = node.candidates[candidateIndex]
        if (keepHistory) {
            val newId = freshMessageId(allMessageIds(tree), "${messageId}_edit")
            val edited = original.copy(id = newId, content = newContent)
            val newCandidates = node.candidates + edited
            val newNode = node.copy(candidates = newCandidates, selectIndex = newCandidates.lastIndex)
            val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
            return tree.copy(nodes = newNodes)
        }
        val edited = original.copy(content = newContent)
        val newCandidates = node.candidates.toMutableList().apply { set(candidateIndex, edited) }
        val newNode = node.copy(candidates = newCandidates)
        val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
        return tree.copy(nodes = newNodes)
    }
    return tree
}

// ═══════════════════════════ 删除 ═══════════════════════════

/**
 * 删除一个候选（RikkaHub buildConversationAfterMessageDelete 对位）：
 *
 * - 从其所在节点过滤掉该 id；**selectIndex 钳入缩小后的区间**
 *   （coerceAtMost(lastIndex)——删除的是选中分支时落到其后继或末位
 *   候选，rikkahub 同款）；
 * - 节点候选清空 → **整节点移除**（空槽位不占线性位置）；
 * - 消息不存在 → 返回原实例（无操作）。
 */
fun deleteCandidate(tree: ConversationTree, messageId: String): ConversationTree {
    for ((nodeIndex, node) in tree.nodes.withIndex()) {
        val candidateIndex = node.candidates.indexOfFirst { it.id == messageId }
        if (candidateIndex < 0) continue
        val remaining = node.candidates.filterIndexed { index, _ -> index != candidateIndex }
        if (remaining.isEmpty()) {
            val newNodes = tree.nodes.filterIndexed { index, _ -> index != nodeIndex }
            return tree.copy(nodes = newNodes)
        }
        val newSelect = node.selectIndex.coerceIn(0, remaining.lastIndex)
        val newNode = node.copy(candidates = remaining, selectIndex = newSelect)
        val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
        return tree.copy(nodes = newNodes)
    }
    return tree
}

// ═══════════════════════════ 分支切换 ═══════════════════════════

/**
 * 切换分支（RikkaHub selectMessageNode 对位）：把 [nodeId] 节点的
 * selectIndex 设为 [candidateIndex]。
 *
 * 守卫：节点不存在或下标越界 → 返回原实例（UI 的 ◀ ▶ 按钮在边界
 * 处天然不可点，这里是数据层兜底）。
 */
fun switchBranch(tree: ConversationTree, nodeId: String, candidateIndex: Int): ConversationTree {
    val nodeIndex = tree.indexOfNode(nodeId)
    if (nodeIndex < 0) return tree
    val node = tree.nodes[nodeIndex]
    if (candidateIndex !in node.candidates.indices) return tree
    val newNode = node.copy(selectIndex = candidateIndex)
    val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
    return tree.copy(nodes = newNodes)
}

/**
 * 相对切换分支（UI「◀ n/total ▶」按钮的直接后端）：selectIndex += delta。
 *
 * 守卫：节点不存在 / 越界 / delta == 0 → 返回原实例（边界不回绕——
 * RikkaHub 分支选择器同款线性行为）。
 */
fun switchBranchRelative(tree: ConversationTree, nodeId: String, delta: Int): ConversationTree {
    if (delta == 0) return tree
    val nodeIndex = tree.indexOfNode(nodeId)
    if (nodeIndex < 0) return tree
    val node = tree.nodes[nodeIndex]
    val target = node.selectIndex + delta
    if (target !in node.candidates.indices) return tree
    val newNode = node.copy(selectIndex = target)
    val newNodes = tree.nodes.toMutableList().apply { set(nodeIndex, newNode) }
    return tree.copy(nodes = newNodes)
}

// ═══════════════════════════ Fork ═══════════════════════════

/**
 * 从指定消息处 fork 会话（RikkaHub forkConversationAtMessage 对位）：
 *
 * - 深拷贝「包含该消息的节点（含）」之前的全部节点（**所有候选**都
 *   复制——分支历史完整带过去，rikkahub 同款）；
 * - **全新节点 id + 全新消息 id**：[idGenerator] 把旧 id 映射为新 id
 *   （默认 "{oldId}_fork"）；生成结果互撞时追加序号保证树内唯一；
 * - 各节点 selectIndex 原样保留（fork 时的线性视图与源前缀一致）；
 * - 标题 = 源标题 + [titleSuffix]（调用方做「title(2)」去重）；
 * - createdAt / updatedAt = clock()（新会话诞生时刻）。
 *
 * @return null：消息不存在。
 */
fun forkAt(
    tree: ConversationTree,
    messageId: String,
    newTreeId: String,
    titleSuffix: String = "",
    clock: () -> Long,
    idGenerator: (String) -> String = { oldId -> oldId + "_fork" }
): ConversationTree? {
    val nodeIndex = tree.nodes.indexOfFirst { node -> node.candidates.any { it.id == messageId } }
    if (nodeIndex < 0) return null
    val prefix = tree.nodes.subList(0, nodeIndex + 1).toList()

    val usedNodeIds = mutableSetOf<String>()
    val usedMessageIds = mutableSetOf<String>()
    val newNodes = prefix.map { node ->
        val newNodeId = freshGeneratedId(usedNodeIds, idGenerator(node.id))
        val newCandidates = node.candidates.map { message ->
            message.copy(id = freshGeneratedId(usedMessageIds, idGenerator(message.id)))
        }
        node.copy(id = newNodeId, candidates = newCandidates)
    }
    val now = clock()
    return ConversationTree(
        id = newTreeId,
        title = tree.title + titleSuffix,
        nodes = newNodes,
        createdAt = now,
        updatedAt = now
    )
}

// ═══════════════════════════ 截断 ═══════════════════════════

/**
 * 截断到指定消息（再生成前的上下文裁剪）：保留「包含该消息的节点
 * （含）」的前缀，其余节点丢弃。RikkaHub regenerateAtMessage 内部
 * handleMessageComplete(messageRange = 0..<nodeIndex) 只传前缀的对位。
 *
 * 消息不存在 / 已是尾部 → 返回原实例（无操作）。
 */
fun trimToMessage(tree: ConversationTree, messageId: String): ConversationTree {
    val nodeIndex = tree.nodes.indexOfFirst { node -> node.candidates.any { it.id == messageId } }
    if (nodeIndex < 0 || nodeIndex == tree.nodes.lastIndex) return tree
    return tree.copy(nodes = tree.nodes.subList(0, nodeIndex + 1).toList())
}

// ═══════════════════════════ 健全性清理 ═══════════════════════════

/**
 * 健全性清理（RikkaHub checkInvalidMessages 的泛化升级）：修复损坏
 * 数据（手改 JSON / 崩溃残留 / 迁移输入）的四类问题：
 *
 * 1. **空节点丢弃**：candidates 为空的节点直接移除（不占线性位置）；
 * 2. **selectIndex 钳制**：越界（负数/超上限）→ coerceIn 到合法区间；
 * 3. **重复消息 id 去重**：全树首现保留，后现丢弃（节点因此清空则
 *    连节点一起丢）——findMessage/upsertCandidate 的「首个命中」语义
 *    依赖 id 唯一；
 * 4. **createdAt 乱序修复**：仅当节点时间戳**严格逆序**（存在
 *    keys[i] > keys[i+1]）时稳定排序（相等保持原序）；时间戳取各节点
 *    选中消息的 createdAt。已单调 → 原顺序不动。
 *
 * 已经健全 → 返回原实例（=== 判定，避免无谓复制）。updatedAt 不动
 * （结构修复非内容定稿；落盘时 [ConversationTreeStore.save] 盖章）。
 */
fun sanitize(tree: ConversationTree): ConversationTree {
    var changed = false
    val seenMessageIds = mutableSetOf<String>()
    val keptNodes = mutableListOf<MessageBranchNode>()
    for (node in tree.nodes) {
        val keptCandidates = mutableListOf<BranchMessage>()
        for (candidate in node.candidates) {
            if (candidate.id in seenMessageIds) {
                changed = true
                continue
            }
            seenMessageIds.add(candidate.id)
            keptCandidates.add(candidate)
        }
        if (keptCandidates.isEmpty()) {
            changed = true
            continue
        }
        val clamped = node.selectIndex.coerceIn(0, keptCandidates.lastIndex)
        if (clamped != node.selectIndex) changed = true
        keptNodes.add(node.copy(candidates = keptCandidates, selectIndex = clamped))
    }

    // createdAt 严格乱序才稳定排序（相等保持原序——TimSort 稳定）。
    // 时间戳取各节点选中消息的 createdAt（守卫：钳制后必非空）。
    val keys = keptNodes.map { node -> node.currentMessage?.createdAt ?: 0L }
    var strictlyOutOfOrder = false
    for (i in 0 until keys.size - 1) {
        if (keys[i] > keys[i + 1]) {
            strictlyOutOfOrder = true
            break
        }
    }
    if (!changed && !strictlyOutOfOrder) return tree

    val finalNodes = if (strictlyOutOfOrder) {
        keptNodes.zip(keys).sortedBy { pair -> pair.second }.map { pair -> pair.first }
    } else {
        keptNodes
    }
    return tree.copy(nodes = finalNodes)
}

// ═══════════════════════════ 内部工具 ═══════════════════════════

/** 全树消息 id 集合（去重口径：候选级遍历）。 */
private fun allMessageIds(tree: ConversationTree): Set<String> {
    val ids = mutableSetOf<String>()
    for (node in tree.nodes) {
        for (candidate in node.candidates) ids.add(candidate.id)
    }
    return ids
}

/** 全树节点 id 集合。 */
private fun allNodeIds(tree: ConversationTree): Set<String> =
    tree.nodes.mapTo(mutableSetOf()) { it.id }

/** 派生不冲突的 id：base 可用直接用；否则追加递增序号。 */
private fun freshMessageId(existingIds: Set<String>, base: String): String {
    if (base !in existingIds) return base
    var counter = 2
    while ("$base$counter" in existingIds) counter++
    return "$base$counter"
}

/** freshMessageId 的节点 id 版（append 派生节点 id 用）。 */
private fun freshNodeId(tree: ConversationTree, base: String): String =
    freshMessageId(allNodeIds(tree), base)

/** fork 路径的 id 派生：基于已生成集合去重（源树不限制——跨树命名空间独立）。 */
private fun freshGeneratedId(usedIds: MutableSet<String>, generated: String): String {
    if (generated !in usedIds) {
        usedIds.add(generated)
        return generated
    }
    var counter = 2
    while ("$generated$counter" in usedIds) counter++
    val resolved = "$generated$counter"
    usedIds.add(resolved)
    return resolved
}
