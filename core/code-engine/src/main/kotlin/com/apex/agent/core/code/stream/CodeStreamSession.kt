package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Code Stream Session — 胶囊时间轴事件状态机（渲染通道的数据源）
 *
 * ## 定位（VM 双通道架构的「渲染半边」）
 *
 * CodeViewModel 的 collect 循环把每个 AgentEvent 喂给本会话
 * （[onEvent]，纯归约 + 置脏），**副作用通道**（长任务追踪 / 深水区
 * 升级观察器 / 会话落盘）留在 VM 原有 reduce——渲染与副作用彻底分流，
 * 引擎与既有功能零改动。
 *
 * ## 25ms ≤40Hz 攒批
 *
 * 事件到来只置脏，**不立刻重组 UI**；VM 的渲染 ticker 每拍调 [tick]：
 * 脏了才重建一次不可变 [CodeStreamSnapshot]（时间轴 + 终端尾窗 + 派生
 * 统计）。流式风暴下 UI 重组频率被钉死在 25ms 一档。
 *
 * ## 幂等（规格书【1】）
 *
 * callId 即幂等键：重复的 ToolCallStart/ToolOutputChunk/ToolCallComplete
 * 对同一 callId 天然合并（首次创建、后续更新），重放安全——检查点恢复
 * （Last-Event-ID 续传）语义建立在这一点上。
 *
 * ## 验证闭环聚合
 *
 * edit/write 落盘后紧跟的 lint/test 归入同一 [VerifyCycle]；下一轮 edit
 * 开始时上一轮闭环收口为可折叠 [StreamEntry.VerifyCycleEntry]（轮内胶囊
 * 从时间轴折叠进卡片，展开态由 UI 自持）——闭环自延长时时间轴不刷屏。
 *
 * 线程模型：与 VM collect 协程同线程串行调用；快照只读共享。
 */
class CodeStreamSession {

    // ═══ 内部状态（全部只在 onEvent/inject 变更）═══

    private val entries = mutableListOf<StreamEntry>()
    private val toolCalls = LinkedHashMap<String, StreamToolCall>()
    private val terminalBuffers = LinkedHashMap<String, TerminalPulseBuffer>()

    /** 时间轴条目 id 稳定自增（会话生命周期内唯一）。 */
    private var entrySeq = 0L


    /** 当前流式思考条目（ThinkingStart..ThinkingComplete 窗口内非空）。 */
    private var streamingThinking: StreamEntry.ThinkingEntry? = null

    /** 当前流式助手条目。 */
    private var streamingAssistant: StreamEntry.AssistantEntry? = null

    /** 活跃 BASH 胶囊（终端面板跟随；run 结束或下个 BASH 到来时切换）。 */
    private var activeTerminalCallId: String? = null

    private val affectedFiles = LinkedHashSet<String>()
    private var lastError: CodeStreamSnapshot.ErrorInfo? = null
    private var toolCallCount = 0
    private var failedToolCallCount = 0

    // ═══ 验证闭环追踪 ═══
    private var cycleRound = 0
    private var openCycleEdits = mutableListOf<String>()
    private var openCycleVerifies = mutableListOf<String>()

    private var dirty = false
    private var cachedSnapshot: CodeStreamSnapshot = CodeStreamSnapshot()

    // ═══ 公开 API ═══

    /** 新 run 开始：用户气泡入轴 + 运行态归零（时间轴跨 run 累积）。 */
    fun beginRun(userText: String) {
        append(StreamEntry.UserEntry(nextId(), userText))
        activeTerminalCallId = null
    }

    /** VM 注入系统说明行（AUTO 预检决策 / 深水区升级等）。 */
    fun injectSystem(text: String) {
        append(StreamEntry.SystemEntry(nextId(), text))
    }

    /** 事件归约入口（纯状态机，无 IO 无回调）。 */
    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.IterationStart -> {
                if (event.iteration > 1) {
                    append(StreamEntry.StatusEntry(nextId(), "第 ${event.iteration} 轮迭代"))
                }
            }

            is AgentEvent.ThinkingStart -> Unit // 思考条目由首个 Chunk 创建

            is AgentEvent.ThinkingChunk -> {
                val cur = streamingThinking
                if (cur == null) {
                    streamingThinking = StreamEntry.ThinkingEntry(nextId(), event.text, true)
                    append(streamingThinking!!)
                } else {
                    replaceEntry(cur.id, cur.copy(text = cur.text + event.text, isStreaming = true))
                }
            }

            is AgentEvent.ThinkingComplete -> {
                streamingThinking?.let {
                    replaceEntry(it.id, it.copy(text = event.fullThought.ifBlank { it.text }, isStreaming = false))
                }
                streamingThinking = null
            }

            is AgentEvent.ToolCallStart -> onToolStart(event)

            is AgentEvent.ToolOutputChunk -> {
                toolCalls[event.callId]?.let { call ->
                    terminalBuffers[event.callId]?.append(event.chunk)
                    // 通用 logTail（非 BASH 族的输出尾窗，DetailSheet 用）
                    val merged = call.logTail + event.chunk
                    updateCall(call.copy(logTail = merged.takeLast(NON_BASH_LOG_TAIL_CHARS)))
                    if (call.kind == ToolKind.BASH) activeTerminalCallId = event.callId
                }
            }

            is AgentEvent.ToolProgress -> {
                toolCalls[event.callId]?.let { call ->
                    updateCall(call.copy(progressPercent = event.percent, progressMessage = event.message))
                }
            }

            is AgentEvent.ToolCallComplete -> onToolComplete(event)

            is AgentEvent.ResponseChunk -> {
                val cur = streamingAssistant
                if (cur == null) {
                    streamingAssistant = StreamEntry.AssistantEntry(nextId(), event.text, true)
                    append(streamingAssistant!!)
                } else {
                    replaceEntry(cur.id, cur.copy(text = cur.text + event.text, isStreaming = true))
                }
            }

            is AgentEvent.ResponseComplete -> {
                streamingAssistant?.let {
                    replaceEntry(it.id, it.copy(text = event.fullText.ifBlank { it.text }, isStreaming = false))
                }
                streamingAssistant = null
            }

            is AgentEvent.ContextCompressed -> {
                append(
                    StreamEntry.StatusEntry(
                        nextId(),
                        "上下文压缩 ${event.beforeTokens} → ${event.afterTokens}（${event.strategy}）"
                    )
                )
            }

            is AgentEvent.Error -> {
                lastError = CodeStreamSnapshot.ErrorInfo(event.message, event.recoverable)
                val related = activeTerminalCallId
                append(
                    StreamEntry.ErrorEntry(
                        id = nextId(),
                        message = event.message,
                        recoverable = event.recoverable,
                        hint = if (event.recoverable) "可重试：检查参数或换一个改法" else null,
                        relatedCallId = related
                    )
                )
            }

            is AgentEvent.Complete -> {
                closeOpenCycle()
                append(StreamEntry.StatusEntry(nextId(), "完成 · ${event.totalIterations} 轮 · ${event.totalToolCalls} 次工具 · ${event.totalDurationMs / 1000}s"))
                if (affectedFiles.isNotEmpty()) {
                    append(StreamEntry.FileChipsEntry(nextId(), affectedFiles.toList()))
                }
            }

            is AgentEvent.Aborted -> {
                closeOpenCycle()
                append(StreamEntry.StopEntry(nextId(), "已中止"))
            }

            is AgentEvent.UserInputRequired -> {
                append(StreamEntry.StatusEntry(nextId(), "等待输入：${event.prompt.take(60)}"))
            }

            is AgentEvent.PlanGenerated ->
                append(StreamEntry.StatusEntry(nextId(), "计划已生成（${event.plan.steps.size} 步）"))

            is AgentEvent.PlanAwaitingConfirmation ->
                append(StreamEntry.StatusEntry(nextId(), "等待计划确认"))

            is AgentEvent.SpecGenerated ->
                append(StreamEntry.StatusEntry(nextId(), "规格已生成（${event.spec.requirements.size} 项需求）"))

            is AgentEvent.ReflectionReview ->
                append(StreamEntry.StatusEntry(nextId(), "评审：${event.reviewText.take(60)}"))

            is AgentEvent.StepStart ->
                append(StreamEntry.StatusEntry(nextId(), event.description.take(80)))

            is AgentEvent.PlanConfirmed, is AgentEvent.SpecConfirmed,
            is AgentEvent.SpecAwaitingConfirmation -> Unit

            // 真实用量更新（服务端 token 统计）—— 归 VM/仪表盘通道消费，
            // 胶囊时间轴不展示用量条目（低频元数据，非工作流事件）。
            is AgentEvent.UsageUpdated -> Unit
        }
    }

    /**
     * 渲染 ticker 每拍调用：脉冲缓冲过时间窗 + 脏则重建快照。
     *
     * @return 新快照（无变化 → null，VM 跳过 StateFlow 更新）
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): CodeStreamSnapshot? {
        var pulsed = false
        terminalBuffers.values.forEach { buf ->
            if (buf.tick(nowMs) != null) pulsed = true
        }
        if (!dirty && !pulsed) return null
        dirty = false
        cachedSnapshot = buildSnapshot()
        return cachedSnapshot
    }

    /** 强制重建（恢复/测试用）。 */
    fun snapshot(): CodeStreamSnapshot {
        dirty = false
        cachedSnapshot = buildSnapshot()
        return cachedSnapshot
    }

    /** 时间轴条目数（测试与落盘裁剪依据）。 */
    fun entryCount(): Int = entries.size

    /**
     * 整轴替换（检查点/旧档恢复用）：清空内部状态后灌入既有条目。
     *
     * 恢复的条目全部视为终态（isStreaming 强制 false 由调用方保证——
     * mapper 产出即终态）；工具表与终端缓冲不重建（历史 BASH 尾窗在
     * DetailSheet 不可回放是已声明的降级）。
     */
    fun replaceAll(restored: List<StreamEntry>) {
        entries.clear()
        entries.addAll(restored)
        toolCalls.clear()
        terminalBuffers.clear()
        affectedFiles.clear()
        streamingThinking = null
        streamingAssistant = null
        activeTerminalCallId = null
        lastError = null
        toolCallCount = restored.count { it is StreamEntry.ToolCapsuleEntry }
        failedToolCallCount = restored.count {
            (it as? StreamEntry.ToolCapsuleEntry)?.call?.status == ToolCallStatus.FAILED
        }
        restored.forEach { entry ->
            if (entry is StreamEntry.ToolCapsuleEntry) {
                toolCalls[entry.call.id] = entry.call
            }
        }
        dirty = true
    }

    /** 清空时间轴（新会话）。 */
    fun clear() {
        replaceAll(emptyList())
    }

    /** 只读条目访问（检查点序列化用）。 */
    fun entriesSnapshot(): List<StreamEntry> = entries.toList()

    /** 终端胶囊的尾窗内容（DetailSheet 查看历史 BASH 输出）。 */
    fun terminalContentOf(callId: String): String? =
        terminalBuffers[callId]?.content()?.takeIf { it.isNotEmpty() }

    // ═══ 内部：工具生命周期 ═══

    private fun onToolStart(event: AgentEvent.ToolCallStart) {
        // 幂等：同一 callId 的 Start 重放直接跳过（检查点恢复/重放安全）
        if (toolCalls.containsKey(event.callId)) return
        val kind = ToolKind.fromToolName(event.toolName)
        val args = parseArgs(event.arguments)
        val target = targetOf(kind, args, event.toolName)
        val call = StreamToolCall(
            id = event.callId,
            kind = kind,
            displayName = displayNameOf(kind, event.toolName),
            target = target,
            argsSummary = args.values.firstOrNull()?.toString()?.take(ARGS_SUMMARY_MAX) ?: "",
            status = ToolCallStatus.RUNNING,
            startAt = System.currentTimeMillis()
        )
        toolCalls[event.callId] = call
        toolCallCount++
        if (kind == ToolKind.BASH) {
            terminalBuffers[event.callId] = TerminalPulseBuffer()
            activeTerminalCallId = event.callId
        }
        trackVerifyCycleStart(kind, event.callId)
        append(StreamEntry.ToolCapsuleEntry(toolEntryId(event.callId), call))
    }

    private fun onToolComplete(event: AgentEvent.ToolCallComplete) {
        val existing = toolCalls[event.callId] ?: run {
            // 恢复/重放场景：Start 丢失时补建（幂等）
            onToolStart(
                AgentEvent.ToolCallStart(event.callId, event.toolName, event.arguments)
            )
            toolCalls[event.callId] ?: return
        }
        val buffer = terminalBuffers[event.callId]
        buffer?.flush()
        val logTail = buffer?.content()?.takeIf { it.isNotEmpty() }
            ?: (existing.logTail + event.output).takeLast(NON_BASH_LOG_TAIL_CHARS)

        val diff = if (existing.isEditFamily) UnifiedDiffParser.parse(event.fullOutput.ifBlank { event.output }) else null
        val hunksTotal = diff?.hunks?.size ?: 0
        val status = when {
            !event.success -> ToolCallStatus.FAILED
            existing.isEditFamily -> if (hunksTotal > 0) ToolCallStatus.APPLIED else ToolCallStatus.SUCCESS
            else -> ToolCallStatus.SUCCESS
        }
        val exitCode = exitCodeOf(event.output, existing.kind)

        val updated = existing.copy(
            status = status,
            endAt = System.currentTimeMillis(),
            durationMs = event.durationMs,
            hunksApplied = if (event.success) hunksTotal else 0,
            hunksTotal = hunksTotal,
            exitCode = exitCode,
            logTail = logTail,
            diffText = diff?.takeIf { it.hunks.isNotEmpty() }?.let { event.fullOutput.ifBlank { event.output } },
            progressPercent = null,
            summary = summaryOf(status, diff, exitCode, event.output)
        )
        updateCall(updated)
        if (status == ToolCallStatus.FAILED) failedToolCallCount++
        if (existing.isEditFamily) {
            existing.target.takeIf { it.isNotBlank() && it != UNKNOWN_TARGET }?.let { affectedFiles.add(it) }
        }
        if (activeTerminalCallId == event.callId) {
            // 面板保留最后一次 BASH 的内容（可翻看），直到下一个 BASH 开始
        }
        trackVerifyCycleEnd(existing.kind, event.callId, event.success)
    }

    // ═══ 内部：验证闭环 ═══

    private fun trackVerifyCycleStart(kind: ToolKind, callId: String) {
        when {
            kind == ToolKind.EDIT_FILE || kind == ToolKind.WRITE_FILE -> {
                if (openCycleVerifies.isNotEmpty()) closeOpenCycle()
                openCycleEdits.add(callId)
            }
            kind == ToolKind.LINT || kind == ToolKind.TEST -> {
                if (openCycleEdits.isNotEmpty()) openCycleVerifies.add(callId)
            }
            else -> Unit
        }
    }

    private fun trackVerifyCycleEnd(kind: ToolKind, callId: String, success: Boolean) {
        if ((kind == ToolKind.LINT || kind == ToolKind.TEST) && callId in openCycleVerifies) {
            // 记录该验证是否通过（轮次卡的红绿态）；轮次在下一轮 edit 或收尾时收口
            pendingVerifyPassed = pendingVerifyPassed && success
        }
    }

    private var pendingVerifyPassed = true

    /** 收口当前开着的验证轮次：把轮内胶囊折叠为 VerifyCycleEntry。 */
    private fun closeOpenCycle() {
        if (openCycleEdits.isEmpty() || openCycleVerifies.isEmpty()) {
            openCycleEdits.clear(); openCycleVerifies.clear(); pendingVerifyPassed = true
            return
        }
        cycleRound++
        val ids = openCycleEdits + openCycleVerifies
        val calls = ids.mapNotNull { toolCalls[it] }
        // 从时间轴移除轮内胶囊，原位插入轮次卡（保持时序）
        val firstIdx = entries.indexOfFirst { it.id == toolEntryId(openCycleEdits.first()) }
        val lastIdx = entries.indexOfLast { it.id == toolEntryId(openCycleVerifies.last()) }
        if (firstIdx >= 0 && lastIdx >= firstIdx) {
            val cycleEntry = StreamEntry.VerifyCycleEntry(
                id = "cycle-$cycleRound-${nextId()}",
                cycle = VerifyCycle(cycleRound, openCycleEdits.toList(), openCycleVerifies.toList(), pendingVerifyPassed),
                calls = calls
            )
            val removed = entries.subList(firstIdx, lastIdx + 1).toList()
            entries.subList(firstIdx, lastIdx + 1).clear()
            entries.add(firstIdx, cycleEntry)
            dirty = true
        }
        openCycleEdits = mutableListOf()
        openCycleVerifies = mutableListOf()
        pendingVerifyPassed = true
    }

    // ═══ 内部：参数与文案 ═══

    private fun append(entry: StreamEntry) {
        entries.add(entry)
        dirty = true
    }

    private fun replaceEntry(id: String, entry: StreamEntry) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) entries[idx] = entry
        dirty = true
    }

    private fun updateCall(call: StreamToolCall) {
        toolCalls[call.id] = call
        val idx = entries.indexOfFirst { it.id == toolEntryId(call.id) }
        if (idx >= 0) entries[idx] = StreamEntry.ToolCapsuleEntry(toolEntryId(call.id), call)
        dirty = true
    }

    private fun toolEntryId(callId: String) = "tool-$callId"

    private fun nextId(): String = "e-${++entrySeq}"

    private fun buildSnapshot(): CodeStreamSnapshot {
        val activeBuf = activeTerminalCallId?.let { terminalBuffers[it] }
        return CodeStreamSnapshot(
            entries = entries.toList(),
            toolCallCount = toolCallCount,
            failedToolCallCount = failedToolCallCount,
            lastError = lastError,
            activeTerminalCallId = activeTerminalCallId,
            terminalContent = activeBuf?.content() ?: "",
            affectedFiles = affectedFiles.toList()
        )
    }

    // ═══ 内部：参数解析 / 展示文案 ═══

    private fun parseArgs(raw: String): Map<String, kotlinx.serialization.json.JsonElement> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).let { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.toMap() ?: emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun targetOf(kind: ToolKind, args: Map<String, kotlinx.serialization.json.JsonElement>, toolName: String): String {
        val str = { key: String -> args[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() } }
        return when (kind) {
            ToolKind.READ_FILE, ToolKind.WRITE_FILE, ToolKind.EDIT_FILE ->
                str("path") ?: str("file") ?: str("file_path") ?: UNKNOWN_TARGET
            ToolKind.GREP_SEARCH -> str("pattern") ?: str("query") ?: str("keyword") ?: toolName
            ToolKind.BASH -> str("command")?.lineSequence()?.firstOrNull()?.take(40) ?: toolName
            ToolKind.GIT -> str("command") ?: str("subcommand") ?: toolName
            ToolKind.LINT, ToolKind.TEST -> str("path") ?: str("target") ?: toolName
            else -> str("name") ?: str("server") ?: toolName
        }
    }

    private fun displayNameOf(kind: ToolKind, toolName: String): String = when (kind) {
        ToolKind.READ_FILE -> "读取"
        ToolKind.WRITE_FILE -> "写入"
        ToolKind.EDIT_FILE -> "编辑"
        ToolKind.GREP_SEARCH -> "搜索"
        ToolKind.BASH -> "命令"
        ToolKind.GIT -> "Git"
        ToolKind.LINT -> "Lint"
        ToolKind.TEST -> "测试"
        ToolKind.PLAN -> "计划"
        ToolKind.MCP_CUSTOM -> toolName.take(16)
    }

    /** 命令退出码：terminal.exec 输出或通用 `exit code N` 尾缀。 */
    private fun exitCodeOf(output: String, kind: ToolKind): Int? {
        if (kind != ToolKind.BASH && kind != ToolKind.GIT) return null
        val m = EXIT_CODE_REGEX.find(output) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun summaryOf(
        status: ToolCallStatus,
        diff: UnifiedDiffParser.ParsedDiff?,
        exitCode: Int?,
        output: String
    ): String {
        if (status == ToolCallStatus.FAILED) {
            val line = output.lineSequence().firstOrNull { it.isNotBlank() } ?: "失败"
            return line.take(SUMMARY_MAX)
        }
        diff?.let { d ->
            if (d.hunks.isNotEmpty()) return "+${d.totalAdded} −${d.totalRemoved} · ${d.hunks.size} hunk"
        }
        exitCode?.let { return "exit $it" }
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }
        return line?.take(SUMMARY_MAX) ?: "完成"
    }

    /** JsonPrimitive content 的 null 安全读取（非 primitive 返回 null）。 */
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    private companion object {
        const val UNKNOWN_TARGET = "…"
        const val ARGS_SUMMARY_MAX = 80
        const val SUMMARY_MAX = 24
        const val NON_BASH_LOG_TAIL_CHARS = 2000
        val EXIT_CODE_REGEX = Regex("""(?:exit(?:\s+code)?[:\s]+|^Exit:\s*)(\d+)\b""", RegexOption.IGNORE_CASE)
    }
}
