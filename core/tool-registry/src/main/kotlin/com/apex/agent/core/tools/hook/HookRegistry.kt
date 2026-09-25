package com.apex.agent.core.tools.hook

import com.apex.agent.core.tools.ToolArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * # Issue #165 — Hooks 钩子系统：注册表与声明式钩子
 *
 * [HookRegistry] 是钩子的唯一装配点：
 *  - **声明式（持久化）**：`hooks.json` 里的 [HookConfig] 列表，升级后
 *    保留、可经设置 UI 启停（[setEnabled]）；每条配置实例化为一个
 *    [DeclarativeHook]（LOG 追加审计日志 / BLOCK 拦截）。
 *  - **编程式（进程内）**：[register] 注入任意 [ToolHook]（不持久化，
 *    进程结束即消失），用于测试与宿主侧高级定制。
 *
 * 持久化惯例对齐 [com.apex.agent.core.tools.mcp.McpManager] 的
 * mcp_servers.json：kotlinx.serialization 真序列化（天然转义）+ 临时文件
 * 原子重命名 + 损坏备份 `.corrupt` 后重建 + 内置条目幂等合并（用户改过
 * enabled 的保留用户值，同名用户自建条目绝不劫持）。
 *
 * ## dispatch 的异常隔离（刻意设计）
 *
 * 单钩子 [ToolHook.onEvent] 抛异常会被捕获并记入 [errorLog]，链继续走完
 * ——与「绝不吞异常」的仓库纪律相反，但这是钩子系统的立身之本：一条坏
 * 钩子（第三方/用户手写）不得瘫痪工具执行主路径。例外：调用方协程已
 * 取消时，CancellationException 照常传播（保持结构化并发语义）。
 *
 * ## fire-and-forget
 *
 * [dispatchFireAndForget] 供非挂起调用方（引擎 clearHistory、装配层
 * SubagentStop）使用：注入了 [scope] 时异步派发；未注入时丢弃事件并记
 * errorLog（无作用域就没有安全执行去处——runBlocking 在主线程装配路径
 * 上不可接受）。
 */
class HookRegistry(
    /** 钩子配置目录（hooks.json 与 hooks.log 都落在这里）。 */
    private val configDir: File,
    /** 异步派发作用域（dispatchFireAndForget 用）；null = 不支持异步派发。 */
    private val scope: CoroutineScope? = null,
    /**
     * 诊断日志出口：core:tool-registry 无 core:logging 依赖（刻意保持
     * 纯 JVM），钩子隔离/持久化失败等异常现场经此回调外送。宿主注入
     * AppLogger，测试注入捕获列表。
     */
    private val errorLog: (String) -> Unit = {}
) {

    /** 声明式钩子的动作。 */
    enum class HookAction { LOG, BLOCK }

    /**
     * 声明式钩子配置（hooks.json 的一条）。
     *
     * @param id 稳定标识（内置钩子以 `builtin-` 前缀占用）。
     * @param name 显示名（设置 UI）。
     * @param event 订阅的事件类型；pattern 仅对工具事件生效。
     * @param pattern 工具匹配模式：精确 id / 前缀+尾 `*` / 单独 `*` 全匹配
     *   （语义对齐 app 层 PermissionRuleMatcher，因 core 不可依赖 app 而
     *   在本包重实现；不支持中间通配）。
     * @param action 匹配后的动作。
     * @param reason BLOCK 动作透传给模型的拦截原因。
     * @param enabled 禁用的钩子不参与派发（firedCount 也不计）。
     * @param system 内置系统钩子标记：升级时定义可刷新但保留用户 enabled；
     *   设置 UI 显示「内置」徽标且不提供删除。
     */
    @kotlinx.serialization.Serializable
    data class HookConfig(
        val id: String,
        val name: String,
        val event: HookEventType,
        val pattern: String = "*",
        val action: HookAction = HookAction.LOG,
        val reason: String = "",
        val enabled: Boolean = true,
        val system: Boolean = false
    )

    /** 派发链上的一个条目：钩子实例 + 排序键 + 声明式配置（编程式为 null）。 */
    private class Entry(
        val hook: ToolHook,
        val order: Int,
        val seq: Long,
        val config: HookConfig?
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 声明式配置（id → config，LinkedHashMap 保持文件序/追加序）。 */
    private val declarativeConfigs = LinkedHashMap<String, HookConfig>()

    /** 声明式条目快照（配置变更时重建）。 */
    private var declarativeEntries: List<Entry> = emptyList()

    /** 编程式条目（进程内，register/unregister 维护）。 */
    private val programmaticEntries = mutableListOf<Entry>()

    private var seqCounter = 0L

    private val logLock = Any()

    /** 配置/启停变更通知（设置 UI 订阅后自动刷新，无需轮询）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        runCatching { configDir.mkdirs() }
        loadConfigs()
        runCatching { ensureBuiltinHooks() }
            .onFailure { errorLog("内置钩子预置失败（不影响已加载配置）: ${it.message}") }
    }

    // ═══ 派发 ═════════════════════════════════════════════════════════

    /**
     * 按注册顺序派发事件给匹配的钩子。
     *
     * 顺序规则：`(order asc, seq asc)`——编程式钩子按 [register] 的
     * order（默认 0）与注册先后；声明式钩子 order 恒 0，按文件序取 seq。
     * 同 order 时编程式与声明式的相对顺序以快照时 seq 为准。
     *
     * 合并语义：首个 Blocked（仅 PreToolUse）胜出并短路；Modified 链式
     * 传递（后续钩子看到改写后的参数），最终改写以最后一个为准；单钩子
     * 异常隔离（见类 KDoc）。
     */
    suspend fun dispatch(event: HookEvent): HookDispatchResult {
        val snapshot = synchronized(lock) { (programmaticEntries + declarativeEntries).sortedWith(compareBy({ it.order }, { it.seq })) }
        val eventType = event.eventType()
        val toolId = when (event) {
            is HookEvent.PreToolUse -> event.toolId
            is HookEvent.PostToolUse -> event.toolId
            else -> null
        }

        var firedCount = 0
        var blockReason: String? = null
        var modifiedArgs: ToolArguments? = null

        for (entry in snapshot) {
            // 过滤配置：持久化条目自带；编程式 DeclarativeHook 也携带
            // （register() 注册的声明式钩子与持久化同语义，见 KDoc）。
            val filter = entry.config ?: (entry.hook as? DeclarativeHook)?.config
            if (filter != null) {
                // 注册表层过滤（DeclarativeHook 自身还有一道防线）：
                // 禁用 / 事件类型不匹配 / 工具 pattern 不匹配的条目不算「触发」
                // （firedCount 也不计 —— 对齐 HookConfig.enabled KDoc 的承诺）。
                if (!filter.enabled) continue
                if (filter.event != eventType) continue
                if (toolId != null && !matches(filter.pattern, toolId)) continue
            }
            firedCount++
            val effectiveEvent = if (modifiedArgs != null && event is HookEvent.PreToolUse) {
                event.copy(args = modifiedArgs!!)
            } else {
                event
            }
            val outcome = try {
                entry.hook.onEvent(effectiveEvent)
            } catch (e: CancellationException) {
                // 调用方协程已取消 → 取消必须传播；仅钩子内部取消/超时 → 隔离。
                if (!currentCoroutineContext().isActive) throw e
                errorLog("钩子 '${entry.hook.id}' 内部取消/超时（已隔离）: ${e.message}")
                continue
            } catch (e: Throwable) {
                errorLog("钩子 '${entry.hook.id}' 异常（已隔离，链继续）: ${e::class.simpleName}: ${e.message}")
                continue
            }
            when (outcome) {
                HookOutcome.Pass -> Unit
                is HookOutcome.Blocked -> {
                    if (event is HookEvent.PreToolUse && blockReason == null) {
                        blockReason = outcome.reason
                    }
                    // 非 PreToolUse 的 Blocked 按设计视为 Pass（见 HookOutcome KDoc）。
                }
                is HookOutcome.Modified -> {
                    if (event is HookEvent.PreToolUse) {
                        modifiedArgs = outcome.args
                    }
                    // 非 PreToolUse 的 Modified 无载荷可改写，忽略。
                }
            }
            if (blockReason != null) break
        }

        return HookDispatchResult(
            blocked = blockReason != null,
            blockReason = blockReason,
            modifiedArgs = modifiedArgs,
            firedCount = firedCount
        )
    }

    /**
     * 非阻断派发：注入了 [scope] 时异步执行（SessionEnd 等生命周期事件的
     * 调用方多为非挂起上下文）；未注入时丢弃并记 errorLog。
     */
    fun dispatchFireAndForget(event: HookEvent) {
        val target = scope
        if (target == null) {
            errorLog("dispatchFireAndForget 丢弃 ${event.eventType()} 事件：HookRegistry 未注入 CoroutineScope")
            return
        }
        target.launch {
            runCatching { dispatch(event) }
                .onFailure { errorLog("fire-and-forget 派发失败: ${it.message}") }
        }
    }

    // ═══ 编程式注册（进程内，不持久化）════════════════════════════════

    /**
     * 注册一个进程内钩子。同 id 重复注册按替换处理。
     *
     * @param order 排序权重：小的先派发（负数可插到声明式钩子之前）。
     */
    fun register(hook: ToolHook, order: Int = 0) {
        synchronized(lock) {
            programmaticEntries.removeAll { it.hook.id == hook.id }
            programmaticEntries += Entry(hook, order, nextSeqLocked(), null)
        }
    }

    /** 注销编程式钩子（声明式持久化钩子不在本方法管辖内，v1.1 无删除 API）。 */
    fun unregister(id: String) {
        synchronized(lock) { programmaticEntries.removeAll { it.hook.id == id } }
    }

    // ═══ 声明式配置（持久化）══════════════════════════════════════════

    /** 声明式配置快照（文件序；设置 UI 数据源）。 */
    fun getConfigs(): List<HookConfig> = synchronized(lock) { declarativeConfigs.values.toList() }

    /**
     * 启用/禁用一条声明式钩子（设置 UI 开关）。立即落盘并广播 [changes]。
     */
    fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        val error = synchronized(lock) {
            val existing = declarativeConfigs[id]
                ?: return Result.failure(Exception("钩子 '$id' 不存在"))
            runCatching {
                declarativeConfigs[id] = existing.copy(enabled = enabled)
                rebuildEntriesLocked()
                saveLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * 幂等预置内置系统钩子（init 已调用；升级流程可再调）。
     *
     * - 无同名条目 → 写入内置定义；
     * - 同名条目是系统钩子 → 按内置定义刷新（升级下发新文案），但**保留
     *   用户 enabled 偏好**（手动禁用的不会被升级重新打开）；
     * - 同名条目是用户自建（system=false）→ 不动它（绝不劫持）。
     */
    fun ensureBuiltinHooks(): Result<Unit> {
        val error = synchronized(lock) {
            var dirty = false
            for (builtin in BUILTIN_HOOKS) {
                val existing = declarativeConfigs[builtin.id]
                when {
                    existing == null -> {
                        declarativeConfigs[builtin.id] = builtin
                        dirty = true
                    }
                    existing.system -> {
                        val merged = builtin.copy(enabled = existing.enabled)
                        if (merged != existing) {
                            declarativeConfigs[builtin.id] = merged
                            dirty = true
                        }
                    }
                    else -> { /* 用户自建同名条目：不劫持 */ }
                }
            }
            if (dirty) {
                runCatching {
                    rebuildEntriesLocked()
                    saveLocked()
                }.exceptionOrNull()
            } else {
                null
            }
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    // ═══ 内部：加载 / 落盘 / 日志 ═════════════════════════════════════

    private fun loadConfigs() {
        val file = File(configDir, HOOKS_FILE)
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<HookConfig>>(content)
            synchronized(lock) {
                loaded.forEach { declarativeConfigs[it.id] = it }
                rebuildEntriesLocked()
            }
        } catch (e: Exception) {
            // 损坏：备份现场后回到空态，交由 ensureBuiltinHooks 重建（用户自建条目
            // 随 .corrupt 留档，可手工恢复）。绝不静默吞掉——现场保留 + 重建留痕。
            runCatching {
                file.copyTo(File(configDir, "$HOOKS_FILE.corrupt"), overwrite = true)
            }
            synchronized(lock) {
                declarativeConfigs.clear()
                rebuildEntriesLocked()
            }
            errorLog("hooks.json 损坏（已备份 .corrupt 并重建内置钩子）: ${e.message}")
        }
    }

    /** 临时文件 + 原子重命名落盘（rename 失败退化为直写，同 McpManager）。 */
    private fun saveLocked() {
        val file = File(configDir, HOOKS_FILE)
        val text = json.encodeToString(declarativeConfigs.values.toList())
        val tmp = File(configDir, "$HOOKS_FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    /** 由配置快照重建声明式条目（配置变更时调用，锁内）。 */
    private fun rebuildEntriesLocked() {
        declarativeEntries = declarativeConfigs.values.map { config ->
            Entry(
                hook = DeclarativeHook(config) { line -> appendLogLine(line) },
                order = 0,
                seq = nextSeqLocked(),
                config = config
            )
        }
    }

    private fun nextSeqLocked(): Long = ++seqCounter

    /**
     * 追加一行审计日志；超过 [LOG_MAX_BYTES] 时滚动（现文件 → `.1`，
     * 旧 `.1` 覆盖删除——磁盘占用上限 ~2×256KB）。IO 失败记 errorLog，
     * 绝不向上抛（日志写坏不能打断工具管线）。
     */
    private fun appendLogLine(line: String) {
        synchronized(logLock) {
            try {
                val file = File(configDir, HOOKS_LOG)
                if (file.exists() && file.length() > LOG_MAX_BYTES) {
                    val rolled = File(configDir, "$HOOKS_LOG.1")
                    runCatching { if (rolled.exists()) rolled.delete() }
                    if (!file.renameTo(rolled)) file.delete()
                }
                file.appendText(line + "\n")
            } catch (e: Throwable) {
                errorLog("hooks.log 追加失败: ${e.message}")
            }
        }
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        private const val HOOKS_FILE = "hooks.json"
        private const val HOOKS_LOG = "hooks.log"

        /** 审计日志滚动阈值（256KB）。 */
        const val LOG_MAX_BYTES: Long = 256L * 1024

        /** 日志行内结果/参数预览的截断长度。 */
        const val LOG_PREVIEW_LIMIT = 200

        /** builtin-prompt-guard 的超长提示阈值（100KB）。 */
        const val PROMPT_HINT_THRESHOLD = 100L * 1024

        const val BUILTIN_ID_TOOL_USAGE_LOG = "builtin-tool-usage-log"
        const val BUILTIN_ID_SESSION_SUMMARY = "builtin-session-summary"
        const val BUILTIN_ID_PROMPT_GUARD = "builtin-prompt-guard"

        /**
         * 工具匹配模式：单独 `*` 全匹配；前缀+尾 `*` 前缀匹配（如
         * `code_git_*`、`mcp__github__*`）；其余按精确 id（大小写敏感）；
         * 空模式不匹配任何工具；不支持中间通配。语义对齐 app 层
         * PermissionRuleMatcher（core 不可依赖 app，故本地重实现）。
         */
        fun matches(pattern: String, toolId: String): Boolean = when {
            pattern == "*" -> true
            pattern.isEmpty() -> false
            pattern.endsWith("*") -> toolId.startsWith(pattern.dropLast(1))
            else -> pattern == toolId
        }

        /** 内置系统钩子定义（幂等预置进 hooks.json）。 */
        private val BUILTIN_HOOKS = listOf(
            HookConfig(
                id = BUILTIN_ID_TOOL_USAGE_LOG,
                name = "工具调用审计",
                event = HookEventType.POST_TOOL_USE,
                pattern = "*",
                action = HookAction.LOG,
                system = true
            ),
            HookConfig(
                id = BUILTIN_ID_SESSION_SUMMARY,
                name = "会话结束摘要",
                event = HookEventType.SESSION_END,
                pattern = "*",
                action = HookAction.LOG,
                system = true
            ),
            HookConfig(
                id = BUILTIN_ID_PROMPT_GUARD,
                name = "输入长度提醒",
                event = HookEventType.USER_PROMPT_SUBMIT,
                pattern = "*",
                action = HookAction.LOG,
                system = true
            )
        )
    }
}

/**
 * 声明式钩子：[HookRegistry.HookConfig] 的运行时化身。
 *
 * 职责单一：收到**已匹配**的事件后执行配置动作——LOG 追加一行格式化
 * 审计（经 [logSink] 注入，注册表接 hooks.log，测试接捕获列表）；BLOCK
 * 返回 [HookOutcome.Blocked]（[HookConfig.reason] 空时给默认文案）。
 *
 * 自带事件类型 + pattern 双重防线：注册表层已过滤，这里再守一道，防止
 * 该类被编程式误注册到错误的派发位。
 */
class DeclarativeHook(
    /** 声明式配置（公开只读：dispatch 据此对编程式注册的实例做同款过滤）。
     * 需要可变拷贝时用 config.copy —— data class 不可变，无泄漏面。 */
    val config: HookRegistry.HookConfig,
    private val logSink: (String) -> Unit
) : ToolHook {

    override val id: String get() = config.id

    override suspend fun onEvent(event: HookEvent): HookOutcome {
        if (config.event != event.eventType()) return HookOutcome.Pass
        when (event) {
            is HookEvent.PreToolUse -> {
                if (!HookRegistry.matches(config.pattern, event.toolId)) return HookOutcome.Pass
            }
            is HookEvent.PostToolUse -> {
                if (!HookRegistry.matches(config.pattern, event.toolId)) return HookOutcome.Pass
            }
            else -> Unit // 非工具事件：pattern 无从匹配，按事件类型即匹配（配置惯例填 *）
        }
        return when (config.action) {
            HookRegistry.HookAction.LOG -> {
                logSink(formatLogLine(event))
                HookOutcome.Pass
            }
            HookRegistry.HookAction.BLOCK -> HookOutcome.Blocked(
                config.reason.ifBlank { "钩子 '${config.id}'（${config.name}）拦截了本次调用" }
            )
        }
    }

    /** 单行格式化审计：时间戳 + 事件 + 主体 + 预览（换行压平，预览截断）。 */
    private fun formatLogLine(event: HookEvent): String {
        val ts = LocalDateTime.now().format(LOG_TIME_FORMAT)
        return buildString {
            append('[').append(ts).append("] ").append(event.eventType().name).append(' ')
            when (event) {
                is HookEvent.PreToolUse -> {
                    append("tool=").append(event.toolId)
                        .append(" args=").append(preview(event.args.raw))
                }
                is HookEvent.PostToolUse -> {
                    append("tool=").append(event.toolId)
                        .append(" ok=").append(!event.isError)
                        .append(" durationMs=").append(event.durationMs)
                        .append(" result=").append(preview(event.result))
                }
                is HookEvent.UserPromptSubmit -> {
                    append("length=").append(event.prompt.length)
                        .append(" preview=").append(preview(event.prompt))
                    if (event.prompt.length > HookRegistry.PROMPT_HINT_THRESHOLD) {
                        append(" [提示超过 100KB，建议精简输入或改用文件附件]")
                    }
                }
                is HookEvent.SessionStart -> {
                    append("sessionId=").append(event.sessionId)
                        .append(" mode=").append(event.mode)
                }
                is HookEvent.SessionEnd -> append("sessionId=").append(event.sessionId)
                is HookEvent.Stop -> append("sessionId=").append(event.sessionId)
                is HookEvent.SubagentStop -> {
                    append("sessionId=").append(event.sessionId)
                        .append(" subagentId=").append(event.subagentId)
                }
                is HookEvent.PreCompact -> append("sessionId=").append(event.sessionId)
            }
        }
    }

    private fun preview(text: String): String = text
        .take(HookRegistry.LOG_PREVIEW_LIMIT)
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private companion object {
        private val LOG_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
