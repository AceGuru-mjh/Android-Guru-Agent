package com.apex.agent.platform.terminal.service

import com.apex.agent.platform.terminal.events.StateKind
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * T91（D2-D4）：Terminal IPC 控制器 —— AIDL 契约与 [TerminalRuntime] 之间的纯 JVM 桥。
 *
 * ## 分层（薄壳原则）
 *
 * ```
 * ITerminalService.Stub（TerminalService.kt，Android 壳，零逻辑）
 *   └─ TerminalIpcController（本类，纯 JVM —— 语义/编码/事件桥全部在此，可单测）
 *        └─ TerminalRuntime（既有 9 操作门面，PR #60 冻结契约）
 * ```
 *
 * Android 壳只做「binder 参数 → 控制器调用」的机械转发 —— 逻辑零下放，
 * 与 T88「View 只做事件清洗与动作转发，决策进纯状态机」同一纪律。
 *
 * ## 语义契约（与 ITerminalService.aidl 注释一一对应）
 *
 *  - **createSession**：成功 → 十进制 sessionId 字符串；失败 → `"ERR:<message>"`
 *    （单往返携带完整错误信息，不依赖 binder 异常传播 —— 运行时 Result 的
 *    message 原样透传，TerminalError 编码保留）；
 *  - **write/writeText**：owner 恒为 [InputOwner.USER]（IPC 桥是用户侧通道 ——
 *    AIDL 客户端**不可能**伪造 owner=AGENT，与 Spec §14「Runtime 注入 owner」同构）；
 *  - **listSessions**：JSON 数组（[SessionSummaryDto]）；
 *  - **回调流**：事件驱动（[TerminalRuntime.terminalEventFlow] —— TerminalEventBus
 *    的 crash-safe 增量订阅），**非轮询**。会话创建锚点 = create 返回的初始游标；
 *    中途 register 的已知会话：若该会话已有收集器（本控制器创建过），新回调
 *    从收集器的既有锚点续流（**不回放历史 transcript** —— 单收集器多播架构
 *    下按回调独立重放会把历史重复推给在场客户端）；若无收集器，从诞生
 *    （anchor 0）重放。T92 审计修正：原 KDoc 声称「从诞生起全量重放」与
 *    putIfAbsent 单收集器实现不符 —— 本条按实际语义声明，per-callback 锚点
 *    重构见后续路线（零消费者阶段不做）。
 *
 * ## 防御性纪律（AGENTS.md「防御式 IO」）
 *
 *  - env 赋值 `"K=V"` 形态非法（无 `=` / 空 key）→ **跳过该条**，不炸整次 create；
 *  - 回调派发失败**计数与剔除**（T92 审计修正）：oneway binder 事务在异步
 *    缓冲耗尽时**直接失败而非阻塞**（「天然背压」是错误认知），旧实现
 *    runCatching 全吞会把输出块静默丢弃且不可检测 —— 现在连续失败达到
 *    [CALLBACK_EVICTION_THRESHOLD] 的回调被剔除（死客户端/严重积压者，
 *    重新 register 可再接入），成功一次即清零；
 *  - 事件收集协程 per-session 独立 Job（SupervisorJob 域内互不传染）。
 */
class TerminalIpcController(
    /** runtime 来源（Registry / 测试注入）。null = 未安装 → ERR:RUNTIME_NOT_INSTALLED。 */
    private val runtimeProvider: () -> TerminalRuntime?,
    /** 事件收集域（SupervisorJob —— 单会话收集失败不传染其它会话）。 */
    private val scope: CoroutineScope,
    /** create 的有界预算（冷启动 rootfs 场景的防御；无界挂起会钉死 binder 线程）。 */
    private val createTimeoutMs: Long = 15_000L,
    /**
     * onOutput 单次回调的最大字节。T92 审计修正：256KB → 64KB —— binder
     * oneway 异步事务缓冲（约 512KB~1MB/进程）在多回调场景下会被 256KB
     * 大分片×N 个客户端放大至耗尽（失败即静默丢块）；64KB 在终端吞吐与
     * 缓冲占用间取平衡（termux-emulator 常规刷新远小于此）。
     */
    private val maxOutputChunkBytes: Int = 64 * 1024,
    /**
     * T92：回调连续失败剔除阈值（测试可注入以确定性收敛；生产用默认 16 ——
     * 64KB 分片下 ≈ 1MB 输出未送达 = 客户端事实死亡/严重积压）。
     */
    private val callbackEvictionThreshold: Int = CALLBACK_EVICTION_THRESHOLD,
    /**
     * T94：write/writeText/resize 的 binder 线程预算。同步 AIDL 在同进程
     * binder 下直接占用调用方线程 —— 无超时的 runBlocking 在 PTY 写入路径
     * 挂起（writer 协程异常/背压）时会把 binder 线程无限钉死；超时后
     * runCatching 静默收敛（与旧「永久阻塞」相比是严格改进）。
     */
    private val opTimeoutMs: Long = 5_000L,
    /**
     * T94：closeSession 的 binder 线程预算 —— close 持全局 session mutex
     * + HUP→TERM→KILL 串行序列，与冷启动 create(15s) 互斥时可长时间阻塞，
     * 预算对齐 create。
     */
    private val closeTimeoutMs: Long = 15_000L
) {

    /** AIDL ITerminalCallback 的 Kotlin 镜像（壳层把 binder 代理适配到本接口）。 */
    interface Callback {
        fun onOutput(sessionId: Long, data: ByteArray)
        fun onExit(sessionId: Long, exitCode: Int, cause: String)
        fun onSessionStateChanged(sessionId: Long, state: String)
    }

    @Serializable
    data class SessionSummaryDto(
        val id: Long,
        val state: String,
        val alive: Boolean
    )

    private val json = Json { encodeDefaults = true }

    private val callbacks = CopyOnWriteArraySet<Callback>()

    /** per-session 事件收集 Job（onExit/SessionClosed 后主动收割）。 */
    private val collectors = ConcurrentHashMap<Long, Job>()

    /** onExit 只派发一次（ProcessExited 与 SessionClosed 双源合并）。 */
    private val exitAnnounced = ConcurrentHashMap.newKeySet<Long>()

    // ─── AIDL 操作实现 ───

    /** 成功 → 十进制 sessionId；失败 → "ERR:<message>"。 */
    fun createSession(
        backendId: String,
        rows: Int,
        cols: Int,
        cwd: String?,
        envAssignments: List<String>?
    ): String {
        val runtime = runtimeProvider() ?: return ERR_RUNTIME_NOT_INSTALLED
        val safeRows = rows.coerceIn(2, 512)
        val safeCols = cols.coerceIn(2, 1024)
        val env = parseEnvAssignments(envAssignments)
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() }?.trim()
            ?: if (backendId == LOCAL_BACKEND_ID) "/sdcard" else "/workspace"
        return try {
            runBlocking {
                withTimeout(createTimeoutMs) {
                    runtime.create(
                        cwd = effectiveCwd,
                        rows = safeRows,
                        cols = safeCols,
                        env = env,
                        backendId = backendId
                    )
                }
            }.fold(
                onSuccess = { created ->
                    // 事件流锚点 = 会话初始游标（此后全部输出/状态事件推给回调）
                    startEventStream(created.sessionId, created.cursor)
                    created.sessionId.toString()
                },
                onFailure = { e -> "ERR:${e.message ?: e::class.simpleName}" }
            )
        } catch (e: Exception) {
            // withTimeout / runBlocking 异常（含取消）—— 有界预算兑现为 ERR
            "ERR:${e.message ?: e::class.simpleName}"
        }
    }

    /** 原始 UTF-8 字节直通 PTY（T85 纪律：不经 String↔charset 往返）。 */
    fun write(sessionId: Long, data: ByteArray) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking {
                withTimeout(opTimeoutMs) {
                    runtime.write(sessionId, owner = InputOwner.USER, kind = TerminalRuntime.WriteKind.RAW, bytes = data)
                }
            }
        }
    }

    /** 原始文本便捷路径（换行由调用方决定）。 */
    fun writeText(sessionId: Long, text: String) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking {
                withTimeout(opTimeoutMs) {
                    runtime.write(sessionId, owner = InputOwner.USER, kind = TerminalRuntime.WriteKind.RAW, text = text)
                }
            }
        }
    }

    fun resize(sessionId: Long, rows: Int, cols: Int) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking {
                withTimeout(opTimeoutMs) {
                    runtime.resize(sessionId, rows.coerceIn(2, 512), cols.coerceIn(2, 1024))
                }
            }
        }
    }

    fun closeSession(sessionId: Long, force: Boolean) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking {
                withTimeout(closeTimeoutMs) {
                    runtime.close(sessionId, force)
                }
            }
        }
        // 注意：不在此处收割事件收集器 —— SessionClosed 事件尚需经收集器派发
        // onExit（announceExit 收到事件后自会收割）。显式 close 的 onExit 语义
        // 由事件链兑现，与外部死亡（BROKEN）同一条路径。
    }

    /** JSON 数组：[{"id":1,"state":"RUNNING","alive":true}, …]。 */
    fun listSessions(): String {
        val runtime = runtimeProvider() ?: return "[]"
        val summaries = runCatching {
            runBlocking {
                runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions
                    ?: emptyList()
            }
        }.getOrDefault(emptyList())
        return json.encodeToString(
            ListSerializer(SessionSummaryDto.serializer()),
            summaries.map { s ->
                SessionSummaryDto(
                    id = s.session.id,
                    state = s.session.state.name,
                    alive = s.session.state in ALIVE_STATES
                )
            }
        )
    }

    /** "pong:<TerminalApiVersion>"（bind 探活 + 契约版本协商）。 */
    fun ping(): String = "pong:${com.apex.agent.platform.terminal.api.TerminalApiVersion.versionString}"

    fun registerCallback(callback: Callback) {
        callbacks.add(callback)
        // 已知会话接入事件流：已有收集器的会话从其既有锚点续流（不回放历史，
        // 见类 KDoc「回调流」语义声明）；无收集器的会话从诞生（anchor 0）重放
        val runtime = runtimeProvider() ?: return
        val known = runCatching {
            runBlocking {
                runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions
                    ?: emptyList()
            }
        }.getOrDefault(emptyList())
        for (s in known) {
            if (s.session.state in ALIVE_STATES) startEventStream(s.session.id, anchorCursor = 0L)
        }
    }

    fun unregisterCallback(callback: Callback) {
        callbacks.remove(callback)
        // T92：同步清理失败计数（防止「注销后重注册仍带着旧 strike」的伪死客户端判定）
        callbackFailures.remove(callback)
    }

    /** 无回调订阅者时收割全部收集器（Service onDestroy 调用）。 */
    fun shutdown() {
        collectors.values.forEach { runCatching { it.cancel() } }
        collectors.clear()
        callbacks.clear()
        callbackFailures.clear()
        exitAnnounced.clear()
    }

    // ─── 事件流桥（event-driven，非轮询） ───

    private fun startEventStream(sessionId: Long, anchorCursor: Long) {
        val runtime = runtimeProvider() ?: return
        val flow: Flow<TerminalEvent> = runtime.terminalEventFlow(sessionId, anchorCursor) ?: return
        // 已有收集器不重复启动（首个回调注册或首个会话创建 —— 单订阅多播给全部回调）
        collectors.putIfAbsent(
            sessionId,
            scope.launch {
                // T94：dispatchEvent/forwardOutput 为 suspend —— 收集协程直接
                // 挂起调用 runtime.observe（原实现在协程内 runBlocking，每 64KB
                // 输出块阻塞一个 Default worker 并新建嵌套事件循环，多会话
                // 高吞吐下有线程饥饿/死锁风险）。
                flow.collect { event -> dispatchEvent(runtime, sessionId, event) }
            }
        )
    }

    private fun stopEventStream(sessionId: Long) {
        collectors.remove(sessionId)?.let { runCatching { it.cancel() } }
    }

    private suspend fun dispatchEvent(runtime: TerminalRuntime, sessionId: Long, event: TerminalEvent) {
        when (event) {
            is TerminalEvent.OutputProduced -> forwardOutput(runtime, sessionId, event)
            is TerminalEvent.ProcessExited ->
                // jobId == null → shell 自身退出（会话终结信号）
                if (event.jobId == null) announceExit(sessionId, event.exitCode ?: -1, event.cause.name)
            is TerminalEvent.SessionClosed ->
                announceExit(sessionId, -1, event.cause.name)
            is TerminalEvent.StateChanged ->
                if (event.kind == StateKind.SESSION) {
                    dispatchToCallbacks { it.onSessionStateChanged(sessionId, event.to) }
                }
            else -> Unit
        }
    }

    private suspend fun forwardOutput(runtime: TerminalRuntime, sessionId: Long, event: TerminalEvent.OutputProduced) {
        if (event.byteCount <= 0 || event.endCursor <= event.startCursor) return
        // 游标驱动分段拉取：事件范围可能超单次回调上限 → 按 maxOutputChunkBytes
        // 逐段 observe(RAW) 派发。进度以游标推进为准（不按字节数算术 —— UTF-8
        // 转码可能使字节计数与环缓冲计数不同步，无进展即终止防死循环）。
        var cursor = event.startCursor
        while (cursor < event.endCursor) {
            val want = (event.endCursor - cursor).toInt().coerceAtMost(maxOutputChunkBytes)
            // T94：直接挂起调用 —— 本函数运行在事件收集协程内，runBlocking
            // 会阻塞 Default worker + 嵌套事件循环（高吞吐多会话时线程饥饿）。
            val result = runCatching {
                runtime.observe(
                    sessionId = sessionId,
                    mode = TerminalRuntime.ObserveMode.RAW,
                    afterCursor = cursor,
                    maxBytes = want
                ).getOrNull()
            }.getOrNull() ?: return
            val raw = result.raw ?: return
            if (raw.isEmpty()) return
            val next = result.cursor
            if (next <= cursor) return // 无进展防御（环缓冲边界/驱逐）—— 终止
            dispatchToCallbacks { it.onOutput(sessionId, raw.toByteArray(Charsets.UTF_8)) }
            cursor = next
        }
    }

    private fun announceExit(sessionId: Long, exitCode: Int, cause: String) {
        // ProcessExited(shell) 与 SessionClosed 双源合并 —— onExit 语义上只发一次
        if (!exitAnnounced.add(sessionId)) return
        dispatchToCallbacks { it.onExit(sessionId, exitCode, cause) }
        stopEventStream(sessionId)
    }

    /** T92：回调连续失败计数（达阈值剔除 —— 见类 KDoc「防御性纪律」）。 */
    private val callbackFailures = ConcurrentHashMap<Callback, java.util.concurrent.atomic.AtomicInteger>()

    private fun dispatchToCallbacks(block: (Callback) -> Unit) {
        for (cb in callbacks) {
            val failed = runCatching { block(cb) }.isFailure
            if (failed) {
                // 多收集协程并发派发 —— 计数原子化；达阈值剔除（重新 register
                // 可再接入），中间成功即清零（间歇性 binder 缓冲瞬满不误杀）
                val strikes = callbackFailures.computeIfAbsent(cb) {
                    java.util.concurrent.atomic.AtomicInteger()
                }.incrementAndGet()
                if (strikes >= callbackEvictionThreshold) {
                    callbacks.remove(cb)
                    callbackFailures.remove(cb)
                }
            } else {
                callbackFailures.remove(cb)
            }
        }
    }

    companion object {
        const val ERR_RUNTIME_NOT_INSTALLED = "ERR:RUNTIME_NOT_INSTALLED — host app has not installed the terminal runtime yet"
        const val LOCAL_BACKEND_ID = "local"

        /** 会话「可交互」状态集（listSessions 的 alive 投影）。 */
        private val ALIVE_STATES = setOf(
            com.apex.agent.platform.terminal.session.SessionState.CREATED,
            com.apex.agent.platform.terminal.session.SessionState.STARTING,
            com.apex.agent.platform.terminal.session.SessionState.READY,
            com.apex.agent.platform.terminal.session.SessionState.RUNNING,
            com.apex.agent.platform.terminal.session.SessionState.WAITING_INPUT,
            com.apex.agent.platform.terminal.session.SessionState.INTERRUPTED
        )

        /** AIDL "ERR:" 前缀（客户端判定契约 —— 壳层与测试共享）。 */
        const val ERROR_PREFIX = "ERR:"

        /**
         * T92：回调连续失败剔除阈值。16 次连续失败（64KB 分片下 ≈ 1MB
         * 输出未送达）= 客户端事实上死亡/严重积压；间歇性失败（binder
         * 缓冲瞬满）会被中间的成功清零，不触发剔除。
         */
        const val CALLBACK_EVICTION_THRESHOLD = 16

        /** 防注入（TM6 平移）：`K=V` 形态外的赋值整条跳过，不炸 create。 */
        fun parseEnvAssignments(assignments: List<String>?): Map<String, String> {
            if (assignments.isNullOrEmpty()) return emptyMap()
            val env = LinkedHashMap<String, String>()
            for (entry in assignments) {
                val eq = entry.indexOf('=')
                if (eq <= 0) continue // 无 '=' 或空 key —— 跳过（防御式 IO）
                val key = entry.substring(0, eq)
                if (key.isEmpty() || key.contains(' ') || key.contains('\n') || key.contains('\u0000')) continue
                env[key] = entry.substring(eq + 1)
            }
            return env
        }
    }
}
