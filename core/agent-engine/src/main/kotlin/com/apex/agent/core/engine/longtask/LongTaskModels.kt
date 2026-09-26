package com.apex.agent.core.engine.longtask

import kotlinx.serialization.Serializable

/**
 * # 长任务中心 · 数据模型（v1.2「长任务复制顶级优化」）
 *
 * ## 这个包解决什么问题
 *
 * Coding/Agent 模式下有一类任务天然是「长任务」：几十轮迭代、几十次工具
 * 调用、持续数分钟到数十分钟的深度重构 / 修 Bug / 评审。这类任务当前的
 * 痛点：
 *
 * 1. **跑完即散**——一旦会话被清理或 App 重启，本次运行的完整画像
 *    （目标、迭代数、工具分布、碰过的文件、最后的结论）就永远丢了，
 *    用户想「再来一遍」只能从头描述；
 * 2. **无法对比**——同一目标用不同思考档位 / 不同提示词跑两次，哪次更
 *    好？没有可比较的结构化数据；
 * 3. **上下文白白积累**——上次运行踩过的坑（失败的命令、走过的弯路）
 *    在重跑时全部重新踩一遍。
 *
 * 长任务中心的答案：**把达到规模阈值（见 [LongTaskDetector]）的运行聚合成
 * 一份 [LongTaskRecord] 持久化**，在其上提供复制重跑（[TaskCopyEngine]）、
 * 模板化（[LongTaskTemplates]）与运行对比（[LongTaskDiff]）三类顶级操作。
 *
 * ## 数据流
 *
 * ```
 * AgentEvent 流 ──collect──▶ LongTaskTracker（内存聚合）
 *                                  │ endRun 时判定规模
 *                                  ▼ 达阈值
 *                            LongTaskStore（records 目录 JSON 持久化）
 *                                  │
 *              ┌───────────────────┼──────────────────┐
 *              ▼                   ▼                  ▼
 *        TaskCopyEngine      LongTaskTemplates    LongTaskDiff
 *        （复制/重跑）        （内置模板）         （两次运行对比）
 * ```
 *
 * ## 序列化与兼容
 *
 * [LongTaskRecord] / [LongTaskCheckpoint] 使用 kotlinx.serialization：
 * 一个记录一个 JSON 文件（store 负责原子写），读取侧 `ignoreUnknownKeys`
 * ——未来给模型加字段，旧文件仍可读；新字段的默认值经 `encodeDefaults`
 * 显式落盘。**所有带默认值的字段都是「可选演进位」**：加字段时优先给
 * 默认值而不是破坏构造调用方。
 *
 * ## 生产者约束（不变量）
 *
 * 模型本身是纯数据（无校验逻辑，序列化友好），但字段文档标注的截断上限
 * （title ≤60、goal ≤4000、filesTouched ≤200、checkpoints ≤10、summary
 * ≤500）由生产者 [LongTaskTracker] 在组记录时保证；[LongTaskStore] 忠实
 * 持久化不二次裁剪——**上限是防内存/磁盘膨胀的防线，不是业务规则**。
 */

/**
 * 长任务的生命周期状态。
 *
 * 与引擎侧的会话状态解耦：Tracker 在 VM 调用 [LongTaskTracker.endRun]
 * 时由 VM 传入（VM 是「这轮运行最终算成功还是失败」的裁判——引擎的
 * Complete/Error/Aborted 事件都只是素材）。ABORTED 还承担「未收尾」
 * 语义：新一轮 beginRun 发起时旧 run 若未显式收尾，自动按 ABORTED 落库。
 */
enum class LongTaskStatus {
    /** 运行中（含：刚被复制、即将重跑的副本记录的初始态）。 */
    RUNNING,

    /** 正常完成（引擎 Complete，目标达成）。 */
    COMPLETED,

    /** 被用户中止 / 会话未收尾被新 run 顶替。 */
    ABORTED,

    /** 以错误收场（引擎 Error 或 VM 判定失败）。errorMessage 承载原因。 */
    FAILED
}

/**
 * 长任务的规模档位。
 *
 * 档位不是「给用户看的标签」而是**决定 UI 呈现强度与重跑价值提示**的
 * 信号：MEDIUM 是「值得留档」的门槛，EPIC 是「值得郑重对待、建议开高
 * 档位重跑」的信号。判定阈值与设计依据见 [fromSignals] 与
 * [LongTaskDetector]。
 */
enum class LongTaskMagnitude {
    /** 中等规模：刚过留档门槛，列表里常规展示。 */
    MEDIUM,

    /** 长任务：多轮深度执行，值得在重跑时携带完整上下文。 */
    LONG,

    /** 史诗级：大规模深度执行，重跑前建议检视检查点与错误历史。 */
    EPIC;

    companion object {
        // ═══ MEDIUM 门槛（「值得留档」的最低规模）═══
        /** 迭代数 ≥8：一个「想清楚再做」的多步任务大约从 8 轮起步。 */
        const val MEDIUM_ITERATIONS = 8

        /** 工具调用 ≥12：读写改查各若干轮，超出单发问答的信息量。 */
        const val MEDIUM_TOOL_CALLS = 12

        /** 时长 ≥120s：两分钟是用户「去干了别的再回来看」的分界。 */
        const val MEDIUM_DURATION_MS = 120_000L

        /** 触碰文件 ≥3：开始形成「一次改动集」，而非单文件编辑。 */
        const val MEDIUM_FILES = 3

        // ═══ LONG 门槛（真正的「长任务」）═══
        /** 迭代数 ≥15：引擎默认上限 25 的一多半，深水区作业。 */
        const val LONG_ITERATIONS = 15

        /** 工具调用 ≥25：与默认 maxIterations 同量级，几乎每轮都在动手。 */
        const val LONG_TOOL_CALLS = 25

        /** 时长 ≥300s：五分钟，用户大概率已切换走再回来。 */
        const val LONG_DURATION_MS = 300_000L

        /** 触碰文件 ≥8：跨模块改动，改动集开始有 review 价值。 */
        const val LONG_FILES = 8

        // ═══ EPIC 门槛（史诗级）═══
        /** 迭代数 ≥25：引擎默认上限打满，通常是升档后的极限作业。 */
        const val EPIC_ITERATIONS = 25

        /** 工具调用 ≥40：重度工具作业，任何微小低效都会被放大。 */
        const val EPIC_TOOL_CALLS = 40

        /** 时长 ≥600s：十分钟量级，重跑前值得先看对比报告。 */
        const val EPIC_DURATION_MS = 600_000L

        /** 触碰文件 ≥15：全项目级改动。 */
        const val EPIC_FILES = 15

        /**
         * 由聚合信号推断规模档位；**四维任一达标即算，取满足的最高档**。
         *
         * 设计依据：长任务的四个信号维度（迭代数 / 工具调用数 / 时长 /
         * 触碰文件数）各自独立地刻画「规模」——一个 8 分钟但只改了 1 个
         * 文件的等待型任务（长编译）和一个 20 秒改了 15 个文件的批量操作
         * 都是长任务，只是「长」在不同的维度上。OR 语义保证不漏判；
         * 取最高档保证 EPIC 信号（如文件数 15）不被低维信号（迭代数 2）
         * 稀释。
         *
         * 判不出任何档位（四维全部低于 MEDIUM 门槛）返回 null = 不是
         * 长任务，不入库。判定阈值本身集中在上方常量区，调参不动逻辑。
         */
        fun fromSignals(s: LongTaskDetector.Signals): LongTaskMagnitude? = when {
            s.iterations >= EPIC_ITERATIONS ||
                s.toolCalls >= EPIC_TOOL_CALLS ||
                s.durationMs >= EPIC_DURATION_MS ||
                s.filesTouched >= EPIC_FILES -> EPIC

            s.iterations >= LONG_ITERATIONS ||
                s.toolCalls >= LONG_TOOL_CALLS ||
                s.durationMs >= LONG_DURATION_MS ||
                s.filesTouched >= LONG_FILES -> LONG

            s.iterations >= MEDIUM_ITERATIONS ||
                s.toolCalls >= MEDIUM_TOOL_CALLS ||
                s.durationMs >= MEDIUM_DURATION_MS ||
                s.filesTouched >= MEDIUM_FILES -> MEDIUM

            else -> null
        }
    }
}

/**
 * 长任务运行中期的检查点快照。
 *
 * ## 为什么需要检查点
 *
 * EPIC 级任务动辄十分钟以上，用户中途看到的只有「正在跑」；检查点把
 * 运行切成可回看的段落——每 5 次迭代（或距上一点 60 秒）拍一张**廉价
 * 快照**：只存计数与最近对话摘要行，不存全量上下文（那是会话记忆的
 * 职责）。重跑前翻一遍检查点序列，能看出「上次卡在哪、绕了哪些弯」。
 *
 * ## 字段语义
 *
 * @property id 检查点唯一标识（UUID）。
 * @property atIteration 拍摄时的累计迭代数（注意是**计数**而非引擎的
 *   iteration 序号——Plan 多步执行会从 1 重新编号，计数才单调）。
 * @property timestamp 拍摄时刻（epoch ms）。
 * @property toolCallCount 拍摄时累计工具调用数。
 * @property filesTouchedCount 拍摄时触碰文件数（只存数量不存清单——
 *   清单在记录级 [LongTaskRecord.filesTouched]，检查点存增量无意义）。
 * @property recentExchange 最近对话摘要行，≤[RECENT_EXCHANGE_MAX]（6）条，
 *   每条形如「用户: …」「助手: …」「工具 code_edit ✓」——给人看的回看
 *   素材，不是给模型的上下文（后者走 relaunch prompt 的完整摘要）。
 * @property todoDigest 拍摄时的 todo 行快照（Tracker 最后一次
 *   noteTodos 的内容），呈现「当时以为还要做什么」。
 */
@Serializable
data class LongTaskCheckpoint(
    val id: String,
    val atIteration: Int,
    val timestamp: Long,
    val toolCallCount: Int,
    val filesTouchedCount: Int,
    val recentExchange: List<String> = emptyList(),
    val todoDigest: List<String> = emptyList()
) {
    companion object {
        /** 单检查点对话摘要行上限：6 行足够呈现「一个段落」的走向。 */
        const val RECENT_EXCHANGE_MAX = 6
    }
}

/**
 * 一次长任务运行的完整画像（长任务中心的中心实体）。
 *
 * 由 [LongTaskTracker] 在运行结束时组装（阈值判定通过才落库）、由
 * [LongTaskStore] 持久化、被 [TaskCopyEngine] 复制派生、被 [LongTaskDiff]
 * 两两对比。[LongTaskTemplates.instantiate] 产出的模板记录是它的特例
 * （[isTemplate] = true）。
 *
 * @property id 记录唯一标识。真实运行 = UUID 字符串（Tracker 生成）；
 *   模板记录 = 稳定 id（`template-<key>-<workspaceId>`，同工作区重复
 *   实例化幂等覆盖，见 [LongTaskTemplates.instantiate]）。
 * @property title 列表展示标题 = goal 首行截断 ≤[TITLE_MAX_CHARS]（60）
 *   字符。多行 goal 只取第一行；首行超 60 字符截断保头。副本由
 *   [TaskCopyEngine] 加「（副本 n）」后缀。
 * @property goal 本次任务的**首条用户消息全文**（复制重跑的语义锚点），
 *   超过 [GOAL_MAX_CHARS]（4000）字符截断保头。注意这是纯文本：图片等
 *   多模态输入不在留档范围（文本足以重建指令）。
 * @property workspaceId 归属工作区 id（复制可跨工作区，见
 *   [LongTaskCopyOptions.targetWorkspaceId]）。
 * @property workspaceName 归属工作区显示名（冗余存储：列表渲染不想
 *   反查工作区注册表；跨工作区复制时沿用源名称，UI 以 workspaceId
 *   解析为准——见 [TaskCopyEngine.copy] 的取舍说明）。
 * @property thinkingLevel 运行时思考档位名（
 *   [com.apex.agent.core.engine.ThinkingLevel].name 的字符串快照）。
 *   为什么存字符串而非枚举：跨版本持久化的记录可能带着已改名/已删除
 *   的档位名，字符串不因枚举演进而反序列化失败。
 * @property agentMode 运行时执行模式名（
 *   [com.apex.agent.core.engine.AgentMode].name）。同为字符串快照，理由同上。
 * @property status 生命周期状态（见 [LongTaskStatus]）。
 * @property createdAt 运行开始时刻（beginRun 时刻）。
 * @property updatedAt 记录最后更新时刻（endRun / 副本计数 bump）。
 * @property endedAt 运行结束时刻；RUNNING 态为 null。
 * @property iterations 累计迭代数（IterationStart 事件计数）。
 * @property toolCalls 累计工具调用数（ToolCallStart 事件计数）。
 * @property durationMs 运行时长（endRun − beginRun，毫秒）。
 * @property filesTouched 触碰过的文件路径（去重、字典序、≤[FILES_MAX]
 *   （200）条）。来源：code_edit / code_write 成功调用的 path 参数。
 *   只记成功调用——失败的编辑不保证落盘，计入会污染「改动集」语义
 *   （diff 对比与重跑上下文都依赖这个语义）。
 * @property toolsUsed 工具名 → 调用次数的分布（含失败调用：了解
 *   「哪些工具在反复尝试」本身就是重跑价值信号）。
 * @property errorMessage FAILED 时的原因文本（Tracker 截断 ≤500 字符）。
 * @property summary 收尾摘要：末条助手消息尾段（Tracker 的 200 字符
 *   尾环缓冲产物）或 VM 显式传入的 Complete 摘要，≤[SUMMARY_MAX_CHARS]
 *   （500）字符。为 null = 无可用摘要（如纯工具型运行）。
 * @property checkpoints 检查点序列（≤[CHECKPOINTS_MAX]（10）个，超限丢
 *   最旧保最新——末段进程比开头更有回看价值）。
 * @property todoSnapshot 可渲染的 todo 行快照（「☐ 文本」「☑ 文本」），
 *   Tracker 取最后一次 noteTodos。FAILED 重跑时这是「未竟事项」的来源。
 * @property parentTaskId **复制链**：本记录复制自哪个记录。存在意义：
 *   ① 溯源——[TaskCopyEngine.forkChain] 沿它回溯整条复制谱系，回答
 *   「这个任务是从哪次运行一代代重跑来的」；② 对比入口——同 parent
 *   的兄弟记录天然是「同一目标的不同尝试」，是 [LongTaskDiff] 的主要
 *   使用场景；③ 审计——副本的统计归零了，只有链能还原真实总工作量。
 * @property copyCount 本记录**作为复制源**被复制过的次数（驱动副本标题
 *   的「（副本 n）」编号；副本自身 copyCount 归零重新计数）。
 * @property isTemplate 是否为模板记录（[LongTaskTemplates] 产出的起点
 *   记录）。模板豁免 prune，且不出现在真实运行的统计口径里。
 * @property tags 标签（模板自带；真实运行目前为空，预留给 UI 分组）。
 */
@Serializable
data class LongTaskRecord(
    val id: String,
    val title: String,
    val goal: String,
    val workspaceId: String,
    val workspaceName: String,
    val thinkingLevel: String,
    val agentMode: String,
    val status: LongTaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long? = null,
    val iterations: Int = 0,
    val toolCalls: Int = 0,
    val durationMs: Long = 0,
    val filesTouched: List<String> = emptyList(),
    val toolsUsed: Map<String, Int> = emptyMap(),
    val errorMessage: String? = null,
    val summary: String? = null,
    val checkpoints: List<LongTaskCheckpoint> = emptyList(),
    val todoSnapshot: List<String> = emptyList(),
    val parentTaskId: String? = null,
    val copyCount: Int = 0,
    val isTemplate: Boolean = false,
    val tags: List<String> = emptyList()
) {
    companion object {
        /** title 截断上限（goal 首行 ≤60 字符，列表单行不折行）。 */
        const val TITLE_MAX_CHARS = 60

        /** goal 截断上限（4000 字符：足够容纳认真写的多行指令）。 */
        const val GOAL_MAX_CHARS = 4000

        /** filesTouched 截断上限（200 条：大改动集的展示与 diff 足矣）。 */
        const val FILES_MAX = 200

        /** checkpoints 保留上限（最近 10 个，丢最旧）。 */
        const val CHECKPOINTS_MAX = 10

        /** summary 截断上限（500 字符）。 */
        const val SUMMARY_MAX_CHARS = 500

        /** errorMessage 截断上限（500 字符，与 summary 对称）。 */
        const val ERROR_MAX_CHARS = 500
    }
}

/**
 * 复制任务的选项——控制「复制什么进记录、什么进重跑指令」。
 *
 * 两层语义要分清：
 * 1. **记录层**（[TaskCopyEngine.copy]）：includeTodos / includeFilesList /
 *    includeCheckpoints 决定上下文素材是否进入**副本记录**；
 * 2. **指令层**（[TaskCopyEngine.buildRelaunchPrompt] /
 *    [TaskCopyEngine.buildContextDigest]）：includeConversation /
 *    includeTodos / includeFilesList 决定摘要段是否进入**发给引擎的
 *    重跑提示词**。
 *
 * includeConversation 默认 true 而 includeCheckpoints 默认 false 是刻意的：
 * 重跑最需要的是「上次的结论与未竟事项」（对话摘要 + todo），完整检查点
 * 序列是审计材料，默认不搬。
 *
 * @property includeConversation 对话摘要（检查点里的 recentExchange 行 +
 *    记录 summary）是否进 relaunch prompt。
 * @property includeTodos todo 快照是否复制/进 prompt（重跑时「接着做」
 *    的依据）。
 * @property includeFilesList 上次触碰的文件清单是否复制/进 prompt（模型
 *    重跑时优先复查这些文件）。
 * @property includeCheckpoints 源记录的检查点序列是否复制进副本记录
 *    （审计用：重跑后发现行为差异时回看上次的中途状态）。
 * @property newTitle 副本标题覆盖；null = 源标题 +「（副本 n）」自动编号。
 * @property targetWorkspaceId 目标工作区；null = 同工作区复制。跨工作区
 *    复制时 workspaceName 沿用源记录（UI 以 id 解析为准）。
 * @property thinkingLevelOverride 思考档位覆盖（如用 ULTRACODE 重跑一次
 *    DEEP 的任务做对比）；null = 沿用源档位。
 */
data class LongTaskCopyOptions(
    val includeConversation: Boolean = true,
    val includeTodos: Boolean = true,
    val includeFilesList: Boolean = true,
    val includeCheckpoints: Boolean = false,
    val newTitle: String? = null,
    val targetWorkspaceId: String? = null,
    val thinkingLevelOverride: String? = null
)
