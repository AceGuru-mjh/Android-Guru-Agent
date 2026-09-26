package com.apex.agent.core.llm.keypool

import com.apex.agent.core.llm.KeyRotationMode
import kotlinx.serialization.Serializable

/**
 * # API Key Pool — 数据模型（Task 4-a）
 *
 * 设计来源（对标分析结论，详见 worklog 2-a / 2-b / 2-c）：
 *
 * - **Operit** `MultiApiKeyProvider` + `ApiKeyPoolAvailabilityTester`：
 *   - 三态可用性（UNTESTED / AVAILABLE / UNAVAILABLE）——池内只要有**任何**一把 key
 *     被标记过，就只从 AVAILABLE 的 key 里选（opt-in 语义：没测过 ≠ 可用）；
 *   - 可用性测试器并发测 key，结果回写池并持久化；
 *   - 轮询游标（currentKeyIndex）持久化，跨进程续轮。
 * - **RikkaHub** `LruKeyRoulette`：单个 apiKey 字段切多 key，优先"从未用过"的，
 *   其次最久未用（LRU）——天然负载分摊。
 * - **apex-agent 现状**：`ProviderConfig.apiKeys: List<String>` 与
 *   [KeyRotationMode] 数据早已预埋（ModelProfile.kt 注释明说"客户端轮换逻辑由
 *   LLM client 层后续接入"），本包补上真正的池逻辑。
 *
 * 与 Operit 的差异（刻意）：
 * - 增加 `COOLDOWN` 状态：429 / 5xx / 网络错误进入指数退避冷却（参考各家 SDK
 *   的 retry-after 语义），到点自动恢复，避免"测一次失败就永久出局"；
 * - 401 / 403（鉴权失败）才判 UNAVAILABLE——key 大概率已死，只有人工重测
 *   （[ApiKeyPool.markTested]）或 [ApiKeyPool.resetKey] 能复活；
 * - 池本体不碰 HTTP、不碰 Android——纯 JVM 可单测（假钟注入驱动时间分支）。
 *
 * 持久化约定：所有可序列化字段**全部带默认值**，旧版本 JSON（缺字段）可无损
 * 加载（kotlinx-serialization 缺省回退），对齐 ModelProfile.kt 的演进纪律。
 */
@Serializable
enum class KeyStatus {
    /** 从未测试 / 从未使用过——池内无任何标记时视为可用候选。 */
    UNTESTED,

    /** 已确认可用（成功调用过，或可用性测试器标记通过）。 */
    AVAILABLE,

    /** 鉴权失败（401/403）或人工标记不可用——不再参与选择，需显式复活。 */
    UNAVAILABLE,

    /** 限流 / 服务端错误冷却中——cooldownUntilMs 到点自动回归候选。 */
    COOLDOWN
}

/**
 * 池中单把 key 的完整健康档案。
 *
 * 对标 Operit `ApiKeyInfo`（id/key/name/isEnabled/availabilityStatus/usageCount/
 * lastUsed/errorCount），扩展了连续失败数（指数退避的指数项）、最近错误码、
 * 冷却截止时间。所有字段带默认值——旧 JSON 反序列化安全。
 */
@Serializable
data class ApiKeyEntry(
    /** 池内唯一 id（稳定标识，轮换 / 上报 / 删除都以 id 寻址，不以 key 内容寻址）。 */
    val id: String,
    /** 展示用标签（设置页里用户起的名字），可为空。 */
    val label: String = "",
    /** 真实 key 材料。只在构造 delegate（如 StreamingOpenAiClient）时透出，
     *  绝不进入异常消息 / 日志 / 快照（脱敏见 [maskKey]）。 */
    val key: String,
    /** 是否启用——禁用的 key 完全不参与选择（用户手动下线某把 key）。 */
    val enabled: Boolean = true,
    /** 可用性三态 + 冷却态。 */
    val status: KeyStatus = KeyStatus.UNTESTED,
    /** 成功使用次数（仅 [ApiKeyPool.reportSuccess] 递增——"从未用过"判定依据）。 */
    val usageCount: Long = 0,
    /** 累计失败次数（任何 reportFailure 都计数，含不改变状态的请求级错误）。 */
    val totalFailures: Long = 0,
    /** 连续失败次数——指数退避的指数项；成功即清零。 */
    val consecutiveFailures: Int = 0,
    /** 最近一次成功使用的时间戳（ms）——LRU 排序键。0 = 从未成功用过。 */
    val lastUsedAt: Long = 0,
    /**
     * 最近一次失败的 HTTP 状态码。约定：0 = 网络层错误哨兵（无状态码，
     * 冷却退避）；-1 = 无码错误（如 Parse，仅计数）；正值为真实 HTTP 码。
     */
    val lastErrorCode: Int = 0,
    /** 最近一次失败的可读信息（截断存储，绝不含 key 材料）。 */
    val lastErrorMessage: String = "",
    /** 冷却截止时间戳（ms）。仅 status == COOLDOWN 时有意义。 */
    val cooldownUntilMs: Long = 0
) {
    /**
     * 是否正处于冷却窗口内。
     *
     * 同时校验状态与时间：status 必须是 COOLDOWN（[ApiKeyPool.reportSuccess]
     * 会把状态翻回 AVAILABLE，即使时间戳残留也视为不冷却——防御性双保险），
     * 且截止时间未过。到点（cooldownUntilMs <= nowMs）即视为可自动恢复。
     */
    fun isCoolingDown(nowMs: Long): Boolean =
        status == KeyStatus.COOLDOWN && cooldownUntilMs > nowMs
}

/**
 * Key 池整体状态——**可序列化持久化的唯一载体**（app 层落盘 / 恢复用，
 * 对标 Operit `ModelConfigData` 内嵌的 apiKeyPool + currentKeyIndex）。
 *
 * 池运行期对它做不可变克隆更新（copy + 新 List），[ApiKeyPool.snapshot]
 * 提供脱敏只读视图，[ApiKeyPool.currentState] / [ApiKeyPool.replaceState]
 * 是持久化层的读写口。
 */
@Serializable
data class KeyPoolState(
    /** 全部条目（含禁用），保持用户配置顺序——DISABLED 模式"永远第一个"依赖此顺序。 */
    val entries: List<ApiKeyEntry> = emptyList(),
    /**
     * 轮询游标——指向 entries 的**列表下标**。
     *
     * 语义按模式不同（详见 [ApiKeyPool.acquire] KDoc）：
     * - SEQUENTIAL：指向"下一次扫描的起点"（选中 idx 后置为 idx + 1，即越过后者）；
     * - ON_ERROR / ON_RATE_LIMIT：指向"当前粘住的 key"（选中即回写 idx），
     *   [ApiKeyPool.rotateCursor] 前进一格实现显式轮换；
     * - DISABLED：不使用（始终取第一个候选）。
     */
    val cursorIndex: Int = 0,
    /** 持久化的默认轮换模式（快照展示用；acquire 以参数传入的模式为准）。 */
    val rotationMode: KeyRotationMode = KeyRotationMode.DISABLED,
    /**
     * 粘性模式（ON_ERROR / ON_RATE_LIMIT）下是否启用 RikkaHub 式偏好：
     * 优先"从未用过"（usageCount == 0），其次最久未用（LRU）。
     * SEQUENTIAL 走游标轮询、DISABLED 固定首位——均忽略此开关。
     */
    val preferUnusedKeys: Boolean = true
) {
    /** 按 id 查找条目（app 层与池内部共用的寻址方式）。 */
    fun entryById(id: String): ApiKeyEntry? = entries.firstOrNull { it.id == id }
}

/**
 * 一次 acquire 的结果分类。
 *
 * 区分"池空" / "全禁用" / "全冷却" / "全不可用"四种失败原因，供
 * [KeyPoolLlmClient] 生成准确的 KeyPoolExhaustedException、供 UI 给出
 * 可操作的提示（"去可用性测试页重测" / "等待 N 秒冷却结束"）。
 */
enum class KeySelectionOutcome {
    /** 成功选中一把 key。 */
    SELECTED,

    /** 池里一条记录都没有。 */
    POOL_EMPTY,

    /** 有条目但全部 enabled == false。 */
    ALL_DISABLED,

    /** 有可用性标记且候选为空，但存在仍在冷却窗口内的 key（有救，等一会儿）。 */
    ALL_COOLING_DOWN,

    /** 候选为空且无冷却中的 key（鉴权死 key / 未测 key 被三态门排除）。 */
    ALL_UNAVAILABLE
}

/**
 * 单次选 key 的结果（瞬时值，不持久化）。
 *
 * [entry] 可空：失败结局（非 SELECTED）没有选中条目，调用方必须走
 * [selectedEntry] / [keyOrNull] 这类 null 安全访问，不允许直接解引用。
 */
data class KeySelection(
    /** 选中的条目；仅 [KeySelectionOutcome.SELECTED] 时非空。 */
    val entry: ApiKeyEntry? = null,
    val outcome: KeySelectionOutcome,
    /**
     * 本次被过滤掉的条目数（entries.size - 候选数）——禁用、未过可用性门、
     * 冷却中的都计入。诊断用途（"这轮跳过了几把 key"）。
     */
    val skippedCount: Int = 0,
    /** 人读原因（含游标 / 偏好命中等细节；含 id 与 label，绝不含 key 材料）。 */
    val reason: String = ""
) {
    /** 是否真正选中（SELECTED 且 entry 非空——双保险）。 */
    val isSelected: Boolean
        get() = outcome == KeySelectionOutcome.SELECTED && entry != null

    /** null 安全的选中条目访问。 */
    val selectedEntry: ApiKeyEntry?
        get() = if (isSelected) entry else null

    /** null 安全的 key 材料访问（仅喂给 delegate 工厂，别处禁用）。 */
    fun keyOrNull(): String? = selectedEntry?.key

    /** null 安全的 id 访问（上报 / 断言用）。 */
    val selectedId: String?
        get() = selectedEntry?.id
}

/**
 * 单把 key 的脱敏健康信息（快照元素）。
 *
 * **不含 key 材料**——只有 [maskKey] 脱敏后的影子（secret 纪律，
 * 对齐 ModelRuntimeErrors.kt "绝不携带 API Key" 的约束）。
 */
data class KeyHealthInfo(
    val id: String,
    val label: String,
    /** 脱敏 key（如 `sk-…x9f2`），仅用于 UI 展示确认。 */
    val keyMasked: String,
    val enabled: Boolean,
    val status: KeyStatus,
    val usageCount: Long,
    val totalFailures: Long,
    val consecutiveFailures: Int,
    val lastUsedAt: Long,
    val lastErrorCode: Int,
    val lastErrorMessage: String,
    /** 距冷却结束还剩多少 ms（非冷却状态恒为 0；已过期为 0）。 */
    val cooldownRemainingMs: Long
)

/**
 * 池的不可变健康快照——UI / 诊断页的单一数据源。
 *
 * [ApiKeyPool.snapshot] 非 suspend、读 @Volatile 状态副本构建，
 * 可在任意线程安全调用（对齐 ModelRuntimeDiagnostics 的快照惯例）。
 */
data class KeyPoolSnapshot(
    /** 全部条目的脱敏健康信息（保持配置顺序）。 */
    val keys: List<KeyHealthInfo>,
    val cursorIndex: Int,
    val rotationMode: KeyRotationMode,
    val preferUnusedKeys: Boolean,
    val totalKeys: Int,
    /** enabled == true 的条目数。 */
    val enabledKeys: Int,
    // ── 以下状态计数只统计 enabled 条目（禁用 key 的状态无意义）──
    val untestedKeys: Int,
    val availableKeys: Int,
    val unavailableKeys: Int,
    val coolingDownKeys: Int,
    val totalUsageCount: Long,
    val totalFailures: Long
) {
    /** 单行诊断摘要（日志 / 测试断言友好，无敏感材料）。 */
    fun summary(): String =
        "KeyPool(total=$totalKeys enabled=$enabledKeys ok=$availableKeys " +
            "cool=$coolingDownKeys dead=$unavailableKeys untested=$untestedKeys " +
            "cursor=$cursorIndex usage=$totalUsageCount fail=$totalFailures)"
}

/**
 * key 材料脱敏——快照 / 异常 / 日志里唯一的 key 呈现形态。
 *
 * 规则：长度 <= 8 完全打码（短 key 泄一半等于泄全部）；否则保留前 3 + 后 4
 * 便于用户肉眼比对，中间以省略号折叠。
 */
fun maskKey(key: String): String = when {
    key.length <= 8 -> "••••"
    else -> key.take(3) + "…" + key.takeLast(4)
}
