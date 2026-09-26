package com.apex.agent.core.code.longtask

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.UUID

/**
 * # 任务复制引擎——「再来一遍，而且比上次更聪明」
 *
 * ## 复制的语义
 *
 * `copy` = 从一份历史记录派生一条**新的 RUNNING 记录**：goal 原样沿用
 * （这是「复制任务再跑一遍」的锚点——目标不变），运行统计归零（副本是
 * 新一次运行的起点，不是源记录的续传），血缘挂到 [LongTaskRecord.parentTaskId]
 * （复制链可溯源可对比）。可选地携带上下文素材（todo / 文件清单 /
 * 检查点）进副本记录，并经 [buildRelaunchPrompt] 生成发给引擎的重跑指令
 * ——模型拿到的不是干巴巴的原始 goal，而是「目标 + 上次跑到哪了 + 哪些
 * 文件动过 + 上次错在哪」的完整作战地图。
 *
 * ## 典型接线（VM 侧）
 *
 * ```
 * val copy = engine.copy(sourceId, options).getOrElse { ... }
 * val prompt = engine.buildRelaunchPrompt(copy, options)
 * // 把 prompt 作为新用户消息发给引擎；tracker.beginRun(prompt, ...)
 * ```
 *
 * 注意 [buildRelaunchPrompt] 对传入记录不做假设：副本记录默认不带
 * summary（copy 语义清空），上下文最完整的形态是**源记录**（summary 与
 * 检查点俱在）——需要「带完整对话摘要重跑」时，用 includeCheckpoints =
 * true 复制（素材随检查点进副本），或直接以源记录调用本方法（签名相同，
 * 参数名 copy 只是习惯叫法）。两种用法测试均已覆盖。
 *
 * ## 一致性取舍
 *
 * copy 的两步写库（新记录 upsert + 源 copyCount 自增）不是事务：中间崩溃
 * 可能出现「副本已入库、源计数少 1」。后果是副本标题编号可能重复一次——
 * 纯展示层瑕疵，不值得为此引入两阶段提交（历史记录是尽力持久的增强数据，
 * 见 [LongTaskStore] 的防御式 IO 取舍）。
 */
class TaskCopyEngine(private val store: LongTaskStore) {

    // ═══════════════════════ 复制 ═══════════════════════

    /**
     * 从 [sourceId] 复制出一条新的 RUNNING 记录并入库。
     *
     * 字段映射（完整契约）：
     * - **新值**：id = 新 UUID；createdAt/updatedAt = 当前时刻；
     * - **归零**（新运行的起点）：iterations / toolCalls / durationMs = 0、
     *   endedAt = null、errorMessage = null、summary = null、copyCount = 0
     *   （副本有自己的复制计数）、toolsUsed = 空（统计随运行态归零——
     *   「上次用了哪些工具」经 relaunch prompt 的摘要传递，不进统计字段）；
     * - **血缘**：parentTaskId = 源 id；源记录 copyCount +1（驱动后续
     *   副本编号 n）；
     * - **沿用**：goal（重跑语义锚点）、agentMode、tags；
     * - **可覆盖**：title（newTitle ?: 源标题 +「（副本 n）」，n = 源
     *   copyCount + 1）；thinkingLevel（override ?: 源档位）；workspaceId
     *   （target ?: 源工作区）；
     * - **按开关**：todoSnapshot（includeTodos）、filesTouched
     *   （includeFilesList）、checkpoints（includeCheckpoints）；
     * - workspaceName 恒沿用源记录——选项只带 id 不带名，跨工作区复制时
     *   显示名可能滞后一拍（UI 以 workspaceId 解析实时名为准，见
     *   [LongTaskRecord.workspaceName] 的冗余存储说明）；
     * - isTemplate 恒 false——模板被复制即成为一次真实运行的起点。
     *
     * @return 成功 → 已入库的副本记录；源不存在 →
     *   [Result.failure]（NoSuchElementException）。
     */
    suspend fun copy(sourceId: String, options: LongTaskCopyOptions): Result<LongTaskRecord> {
        val source = store.get(sourceId)
            ?: return Result.failure(NoSuchElementException("未找到长任务记录: $sourceId"))

        val now = System.currentTimeMillis()
        val copyNumber = source.copyCount + 1
        val record = LongTaskRecord(
            id = UUID.randomUUID().toString(),
            title = options.newTitle ?: "${source.title}${COPY_TITLE_SUFFIX_PREFIX}${copyNumber}${COPY_TITLE_SUFFIX_SUFFIX}",
            goal = source.goal,
            workspaceId = options.targetWorkspaceId ?: source.workspaceId,
            workspaceName = source.workspaceName,
            thinkingLevel = options.thinkingLevelOverride ?: source.thinkingLevel,
            agentMode = source.agentMode,
            status = LongTaskStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            endedAt = null,
            iterations = 0,
            toolCalls = 0,
            durationMs = 0,
            filesTouched = if (options.includeFilesList) source.filesTouched else emptyList(),
            toolsUsed = emptyMap(),
            errorMessage = null,
            summary = null,
            checkpoints = if (options.includeCheckpoints) source.checkpoints else emptyList(),
            todoSnapshot = if (options.includeTodos) source.todoSnapshot else emptyList(),
            parentTaskId = source.id,
            copyCount = 0,
            isTemplate = false,
            tags = source.tags
        )
        store.upsert(record)
        store.incrementCopyCount(source.id)
        return Result.success(record)
    }

    // ═══════════════════════ 重跑指令 ═══════════════════════

    /**
     * 构造发给引擎的**用户消息文本**（重跑指令）。
     *
     * 结构：
     * ```
     * 重跑任务：<title>
     *
     * <goal 原文>
     *
     * ## 上次运行上下文
     * <buildContextDigest 产物>
     *
     * 请基于以上上下文重新执行该任务，注意上次的错误与未竟事项。
     * ```
     *
     * - 上下文段在摘要为空（素材缺失或开关全关）时整体省略；
     * - 收尾指令二选一：源记录 FAILED →「注意上次的错误与未竟事项」；
     *   其余 →「请重新完成该任务」；
     * - 总长 ≤[PROMPT_MAX_CHARS]（3000）字符，超限**截断保头**——首行的
     *   任务标题与 goal 开头是模型的定向锚点，必须活着。
     */
    fun buildRelaunchPrompt(copy: LongTaskRecord, options: LongTaskCopyOptions): String {
        val tail = if (copy.status == LongTaskStatus.FAILED) {
            FAILED_TAIL
        } else {
            RERUN_TAIL
        }
        val digest = buildContextDigest(copy, options)
        val prompt = buildString {
            append("重跑任务：")
            append(copy.title)
            append("\n\n")
            append(copy.goal)
            if (digest.isNotEmpty()) {
                append("\n\n")
                append(CONTEXT_SECTION_HEADER)
                append('\n')
                append(digest)
            }
            append("\n\n")
            append(tail)
        }
        return if (prompt.length > PROMPT_MAX_CHARS) prompt.take(PROMPT_MAX_CHARS) else prompt
    }

    /**
     * 从记录生成 markdown 摘要块（纯函数，不碰库不碰时钟）。
     *
     * 段落顺序（素材非空才渲染对应段）：
     * 1. **运行概览行**（只要摘要非空就置顶）：状态 · 迭代 · 工具调用 ·
     *    时长——让模型一眼校准「上次干到什么程度」；
     * 2. **对话摘要**（includeConversation）：最近检查点的 recentExchange
     *    行 + 记录 summary（末次结论）；
     * 3. **待办快照**（includeTodos）：todoSnapshot 逐行——「接着做」的
     *    直接依据；
     * 4. **文件清单**（includeFilesList）：触碰文件 ≤[DIGEST_FILES_MAX]
     *    （30）条，超出聚合为「…等 N 个」（防巨清单挤爆 prompt）。
     *
     * 全部素材缺失或开关全关 → 空字符串（调用方据此省略上下文段）。
     */
    fun buildContextDigest(source: LongTaskRecord, options: LongTaskCopyOptions): String {
        val conversationLines = if (options.includeConversation) {
            conversationDigestLines(source)
        } else {
            emptyList()
        }
        val todoLines = if (options.includeTodos && source.todoSnapshot.isNotEmpty()) {
            source.todoSnapshot.map { "$DIGEST_LIST_BULLET$it" }
        } else {
            emptyList()
        }
        val fileLines = if (options.includeFilesList && source.filesTouched.isNotEmpty()) {
            filesDigestLines(source.filesTouched)
        } else {
            emptyList()
        }
        if (conversationLines.isEmpty() && todoLines.isEmpty() && fileLines.isEmpty()) {
            return ""
        }

        return buildString {
            append("- 上次运行：")
            append(source.status.name)
            append(" · ")
            append(source.iterations)
            append(" 次迭代 · ")
            append(source.toolCalls)
            append(" 次工具调用 · ")
            append(LongTaskDiff.formatDuration(source.durationMs))
            append('\n')
            if (conversationLines.isNotEmpty()) {
                append(DIGEST_CONVERSATION_HEADER)
                append('\n')
                conversationLines.forEach { append(it).append('\n') }
            }
            if (todoLines.isNotEmpty()) {
                append(DIGEST_TODOS_HEADER)
                append('\n')
                todoLines.forEach { append(it).append('\n') }
            }
            if (fileLines.isNotEmpty()) {
                append(DIGEST_FILES_HEADER)
                append('\n')
                fileLines.forEach { append(it).append('\n') }
            }
        }.trimEnd('\n')
    }

    // ═══════════════════════ 检查点续跑 ═══════════════════════

    /**
     * 从指定检查点构建**续跑**提示词（顶级优化：不从头重跑，接着干）。
     *
     * 与 [buildRelaunchPrompt] 的语义差异：重跑 = 「重新完成该任务」（
     * 默认不带进度偏见，适合结果不对想换思路）；续跑 = 「从第 N 轮检查点
     * 继续」（保留已做工作，先回看再接着干，适合长任务被打断/失败后
     * 想沿着原路线继续）。
     *
     * 文本结构：
     * ```
     * 续跑任务：<title>
     *
     * <goal 原文>
     *
     * ## 进度快照（第 N 轮检查点）
     * - 已进行：N 次迭代 · M 次工具调用 · 已触碰 K 个文件
     * - 最近进展：
     *   - <recentExchange 摘要行…>
     * - 待办状态：
     *   - <todoDigest 行…>
     *
     * 请从上述进度继续：先回看已触碰的关键文件与待办状态，已完成项不要
     * 重做，接着未竟事项继续执行。
     * ```
     *
     * @param checkpointId 指定检查点 id；null = 取最后一个检查点。
     * @return 组装好的续跑提示词；记录无检查点时返回 **空串**（调用方应
     *   回退 [buildRelaunchPrompt]——无进度可续，只能重跑）。
     */
    fun buildResumePrompt(record: LongTaskRecord, checkpointId: String? = null): String {
        val checkpoint = if (checkpointId != null) {
            record.checkpoints.firstOrNull { it.id == checkpointId }
        } else {
            record.checkpoints.lastOrNull()
        } ?: return ""

        val prompt = buildString {
            append("续跑任务：")
            append(record.title)
            append("\n\n")
            append(record.goal)
            append("\n\n")
            append(RESUME_SECTION_HEADER)
            append("（第 ")
            append(checkpoint.atIteration)
            append(" 轮检查点）\n")
            append("- 已进行：")
            append(checkpoint.atIteration)
            append(" 次迭代 · ")
            append(checkpoint.toolCallCount)
            append(" 次工具调用 · 已触碰 ")
            append(checkpoint.filesTouchedCount)
            append(" 个文件\n")
            if (checkpoint.recentExchange.isNotEmpty()) {
                append("- 最近进展：\n")
                checkpoint.recentExchange.takeLast(LongTaskCheckpoint.RECENT_EXCHANGE_MAX)
                    .forEach { append(DIGEST_LIST_BULLET).append(it).append('\n') }
            }
            if (checkpoint.todoDigest.isNotEmpty()) {
                append("- 待办状态：\n")
                checkpoint.todoDigest.take(RESUME_TODO_LINES_MAX)
                    .forEach { append(DIGEST_LIST_BULLET).append(it).append('\n') }
            }
            append("\n")
            append(RESUME_TAIL)
        }
        return if (prompt.length > PROMPT_MAX_CHARS) prompt.take(PROMPT_MAX_CHARS) else prompt
    }

    // ═══════════════════════ 复制链 ═══════════════════════

    /**
     * 沿 [LongTaskRecord.parentTaskId] 从 [id] 回溯到复制链根，
     * **旧 → 新**排序返回（含 [id] 自身；根在前）。
     *
     * 防御：`visited` 集合检测环（数据被手工编辑可能出现 A←B←A）——命中
     * 环即截断 + warn 留痕；链长硬上限 [CHAIN_MAX]（64）兜底超长链
     * （防慢查询——每跳一次 get 都是一次缓存读，64 跳足够任何真实场景）。
     * [id] 自身不存在 → 空列表（查询语义，不抛）。
     */
    suspend fun forkChain(id: String): List<LongTaskRecord> {
        val chain = ArrayList<LongTaskRecord>()
        val visited = HashSet<String>()
        var currentId: String? = id
        while (currentId != null) {
            if (!visited.add(currentId)) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "复制链检测到环（$currentId 重复出现），已截断"
                )
                break
            }
            if (chain.size >= CHAIN_MAX) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, TAG,
                    "复制链超过 $CHAIN_MAX 跳，已截断"
                )
                break
            }
            val record = store.get(currentId) ?: break // 链上记录被删 = 链自然终止
            chain.add(record)
            currentId = record.parentTaskId
        }
        chain.reverse() // 收集时新→旧，返回旧→新
        return chain
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /** 对话摘要段：最近检查点的 exchange 行 + 末次结论（summary）。 */
    private fun conversationDigestLines(source: LongTaskRecord): List<String> {
        val lines = ArrayList<String>()
        source.checkpoints.lastOrNull()?.recentExchange?.let { exchange ->
            for (line in exchange.takeLast(LongTaskCheckpoint.RECENT_EXCHANGE_MAX)) {
                lines.add("$DIGEST_LIST_BULLET$line")
            }
        }
        if (!source.summary.isNullOrBlank()) {
            lines.add("${DIGEST_LIST_BULLET}上次结论：${source.summary.trim()}")
        }
        return lines
    }

    /** 文件清单段：≤30 条逐行列出，超出聚合计数。 */
    private fun filesDigestLines(files: List<String>): List<String> {
        val lines = ArrayList<String>()
        for (file in files.take(DIGEST_FILES_MAX)) {
            lines.add("$DIGEST_LIST_BULLET$file")
        }
        if (files.size > DIGEST_FILES_MAX) {
            lines.add("$DIGEST_LIST_BULLET…等共 ${files.size} 个文件")
        }
        return lines
    }

    private companion object {
        const val TAG = "TaskCopyEngine"

        /** 重跑指令总长上限（字符）：超限截断保头。 */
        const val PROMPT_MAX_CHARS = 3000

        /** 副本标题后缀（n = 源 copyCount + 1）。 */
        const val COPY_TITLE_SUFFIX_PREFIX = "（副本 "
        const val COPY_TITLE_SUFFIX_SUFFIX = "）"

        /** 上下文段标题（引擎侧 "## " 段落风格对齐）。 */
        const val CONTEXT_SECTION_HEADER = "## 上次运行上下文"

        const val FAILED_TAIL = "请基于以上上下文重新执行该任务，注意上次的错误与未竟事项。"
        const val RERUN_TAIL = "请重新完成该任务。"

        /** 续跑段标题与收尾指令（buildResumePrompt）。 */
        const val RESUME_SECTION_HEADER = "## 进度快照"
        const val RESUME_TAIL =
            "请从上述进度继续：先回看已触碰的关键文件与待办状态，已完成项不要重做，接着未竟事项继续执行。"

        /** 续跑提示里的 todo 行上限（检查点 todoDigest 防线）。 */
        const val RESUME_TODO_LINES_MAX = 30

        const val DIGEST_CONVERSATION_HEADER = "对话摘要："
        const val DIGEST_TODOS_HEADER = "待办快照："
        const val DIGEST_FILES_HEADER = "涉及文件："
        const val DIGEST_LIST_BULLET = "  - "

        /** 摘要里的文件清单上限（超长清单聚合为计数行）。 */
        const val DIGEST_FILES_MAX = 30

        /** 复制链回溯跳数硬上限（环之外的兜底防线）。 */
        const val CHAIN_MAX = 64
    }
}
