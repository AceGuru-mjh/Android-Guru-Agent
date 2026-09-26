package com.apex.agent.core.engine.branch

import kotlinx.serialization.Serializable

/**
 * ═══ 消息分支核心模型（4-c，学习 RikkaHub MessageNode 设计）═══
 *
 * RikkaHub（rikkahub 仓库 data 模型 Conversation.kt + MessageNode）的分支
 * 数据结构是本次对比分析中「最值得复制的特性」：
 *
 * ```
 * Conversation.messageNodes: List<MessageNode>
 * MessageNode(messages: List<UIMessage>, selectIndex: Int)
 *   └─ 同一「消息槽」的多个候选回复 = 节点内多分支
 * currentMessages = messageNodes.map { it.messages[it.selectIndex] }
 *   └─ 选中分支组成的线性消息（UI 零成本线性渲染）
 * ```
 *
 * **设计本质：不是树，而是「节点列表 + 每节点候选数组 + 选中下标」**。
 * 比真正的树（parentId 图）简单一个量级，且对话 UI 天然线性——
 * LazyColumn 按节点渲染 node.currentMessage，分支切换只改 selectIndex。
 *
 * 本包（com.apex.agent.core.engine.branch）将其语义移植为 apex 命名：
 *
 * | RikkaHub        | apex（本包）      | 说明                                |
 * |-----------------|-------------------|-------------------------------------|
 * | UIMessage       | [BranchMessage]  | 消息（纯 JVM，无 Compose 依赖）     |
 * | MessageNode     | [MessageBranchNode] | 消息槽（candidates + selectIndex）|
 * | Conversation    | [ConversationTree] | 会话（节点列表线性化 currentMessages）|
 *
 * 分层纪律（对齐 FileTaskStore / LongTaskStore）：
 * - 本文件：纯数据模型（@Serializable + 计算属性 + 守卫帮助函数），零 IO；
 * - [ConversationTreeOps]：纯不可变操作（全部返回新树）；
 * - [ConversationTreeStore]：文件式持久化（原子写 + 损坏隔离 + 内存缓存）。
 *
 * 序列化纪律：除 id/role/content 外全部字段带默认值——旧版 JSON（缺字段）
 * 可直接加载（前向兼容），配套 ignoreUnknownKeys 容忍未来字段。
 */
@Serializable
enum class MessageRole {
    /** 用户输入。 */
    USER,

    /** 模型回复（一个 assistant 回合的代表消息）。 */
    ASSISTANT,

    /** 系统注入（会话级上下文/横幅）。 */
    SYSTEM,

    /** 工具调用结果（迁移自线性历史的工具输出卡）。 */
    TOOL
}

/**
 * 分支消息（RikkaHub UIMessage 的纯 JVM 对位）。
 *
 * 与 apex 现有 ChatHistoryMessage 的差异：带稳定 [id]（分支槽位配对的
 * 关键——RikkaHub 的「按 message.id 匹配则替换，否则 append 为新候选」
 * 幂等重试语义依赖它）；role 用类型安全枚举而非字符串。
 *
 * 全部可选字段带默认值：旧 JSON / 迁移输入缺字段时安全加载。
 */
@Serializable
data class BranchMessage(
    /** 消息唯一 id（树内唯一；占位/编辑/fork 时生成新 id）。 */
    val id: String,

    /** 消息角色。 */
    val role: MessageRole,

    /** 消息正文（空串 = 再生成占位符，见 [ConversationTreeOps.regenerateSlot]）。 */
    val content: String,

    /** 创建时间（epoch ms；假钟注入驱动测试）。 */
    val createdAt: Long = 0,

    /** 仅 role == TOOL：产生该结果的工具名。 */
    val toolName: String? = null,

    /** 思考过程（RikkaHub UIMessage thinking 段对位；迁移路径可留空）。 */
    val thinking: String = "",

    /** 自由元数据（如模型名 / 耗时 / 引用），字符串到字符串。 */
    val meta: Map<String, String> = emptyMap()
) {
    /** 角色便捷判断（调用方避免直接比较枚举）。 */
    fun isUser(): Boolean = role == MessageRole.USER

    /** 角色便捷判断。 */
    fun isAssistant(): Boolean = role == MessageRole.ASSISTANT

    /** 角色便捷判断。 */
    fun isSystem(): Boolean = role == MessageRole.SYSTEM

    /** 角色便捷判断。 */
    fun isTool(): Boolean = role == MessageRole.TOOL

    /**
     * 是否为「空的 assistant 占位消息」——再生成流程（rikkahub
     * GenerationLoop 预创建空 UIMessage 复用同一 ID 的技巧）中，
     * 占位符以空 content 出现，生成内容随后按 id 原位替换。
     */
    fun isEmptyPlaceholder(): Boolean = role == MessageRole.ASSISTANT && content.isBlank()
}

/**
 * 消息分支节点（RikkaHub MessageNode 的 apex 对位）。
 *
 * 语义：一个节点 = 对话中的一个「消息槽」；[candidates] 是该槽位的
 * 全部候选（分支）——第一次生成的回复、每次再生成的回复、每条
 * 「编辑 = 新分支」的改写；[selectIndex] 指向当前选中候选，UI 的
 * 分支选择器（RikkaHub ChatMessageBranchSelector 的「◀ 2/3 ▶」）
 * 只改这个下标。
 *
 * 节点角色（[role]）取首个候选的角色（RikkaHub MessageNode.role
 * 同款）：user 节点、assistant 节点由首个候选决定；assistant 节点
 * 是唯一可再生成/可多候选的槽位（见 [ConversationTreeOps]）。
 */
@Serializable
data class MessageBranchNode(
    /** 节点唯一 id（树内唯一；树 id + 序号派生，fork 时换新）。 */
    val id: String,

    /** 该槽位的全部候选（分支）；顺序 = 产生顺序。 */
    val candidates: List<BranchMessage> = emptyList(),

    /** 当前选中候选下标（0 .. candidates.lastIndex；sanitize 负责钳制）。 */
    val selectIndex: Int = 0
) {
    /** 候选数量。 */
    val candidateCount: Int
        get() = candidates.size

    /**
     * 当前选中消息（守卫式）：节点为空或 selectIndex 越界（损坏数据）
     * → null 而非抛（RikkaHub 的 currentMessage 会抛 IllegalStateException，
     * apex 纪律是防御式折叠为 null + sanitize 修复）。空节点在线性化
     * [ConversationTree.currentMessages] 中被跳过。
     */
    val currentMessage: BranchMessage?
        get() = candidates.getOrNull(selectIndex)

    /**
     * 节点角色：首个候选的角色；空节点回退 [MessageRole.USER]
     * （RikkaHub MessageNode.role 同款回退）。
     */
    val role: MessageRole
        get() = candidates.firstOrNull()?.role ?: MessageRole.USER

    /** 是否可切换分支（UI 仅在多于 1 个候选时显示分支选择器）。 */
    fun isSwitchable(): Boolean = candidates.size > 1

    /** [currentMessage] 的显式函数形式（RikkaHub selected 语义）。 */
    fun selected(): BranchMessage? = currentMessage

    /**
     * 选中指定候选（守卫式钳制）：下标越界 → 钳入合法区间；
     * 空节点 → 返回原实例（无可选）。不抛异常（外部输入宽容语义）。
     */
    fun select(index: Int): MessageBranchNode {
        if (candidates.isEmpty()) return this
        return copy(selectIndex = index.coerceIn(0, candidates.lastIndex))
    }
}

/**
 * 会话树（RikkaHub Conversation 的 apex 对位）。
 *
 * 「树」是历史叫法——实际结构是「节点列表 + 每节点候选数组 + 选中
 * 下标」（见包级 KDoc）。线性视图 [currentMessages] = 各节点选中
 * 消息按序拼接（空节点/坏下标节点跳过），这就是发给 LLM 的上下文
 * 与 UI 渲染的对话。
 *
 * 本类只读：一切变更经 [ConversationTreeOps] 的不可变操作完成
 * （返回新实例，旧实例可被并发读者继续持有——写时复制，无锁读）。
 */
@Serializable
data class ConversationTree(
    /** 会话 id（同时是持久化文件名：{treeId}.json，见 [ConversationTreeStore]）。 */
    val id: String,

    /** 标题（默认空；fork 时叠加后缀实现「title(n)」去重语义）。 */
    val title: String = "",

    /** 节点列表（对话顺序）。 */
    val nodes: List<MessageBranchNode> = emptyList(),

    /** 创建时间（epoch ms）。 */
    val createdAt: Long = 0,

    /** 最后更新时间（epoch ms；由 [ConversationTreeStore.save] 盖章）。 */
    val updatedAt: Long = 0
) {
    /**
     * 线性化视图：各节点选中消息按节点顺序拼接；空节点与 selectIndex
     * 越界（损坏）节点跳过。等价 RikkaHub 的
     * messageNodes.map { it.messages[it.selectIndex] }（守卫版）。
     */
    val currentMessages: List<BranchMessage>
        get() = nodes.mapNotNull { it.currentMessage }

    /** 线性消息数（currentMessages.size；列表页摘要用）。 */
    val messageCount: Int
        get() = currentMessages.size

    /** assistant 回合数（assistant 节点数 = user→assistant 交换数）。 */
    val assistantTurnCount: Int
        get() = nodes.count { it.role == MessageRole.ASSISTANT }

    /** 有多候选（可切换分支）的节点数（[ConversationTreeStore.TreeSummary] 的 branchCount 口径）。 */
    val branchNodeCount: Int
        get() = nodes.count { it.candidateCount > 1 }

    /**
     * 找到包含指定消息 id 的节点（首个命中；RikkaHub
     * getMessageNodeByMessageId 同款）。
     */
    fun nodeOf(messageId: String): MessageBranchNode? =
        nodes.firstOrNull { node -> node.candidates.any { it.id == messageId } }

    /** 节点 id → 节点下标（不存在返回 -1）。 */
    fun indexOfNode(nodeId: String): Int =
        nodes.indexOfFirst { it.id == nodeId }

    /** 全树查找消息（按 id，首个命中；不存在返回 null）。 */
    fun findMessage(messageId: String): BranchMessage? {
        for (node in nodes) {
            for (candidate in node.candidates) {
                if (candidate.id == messageId) return candidate
            }
        }
        return null
    }
}
