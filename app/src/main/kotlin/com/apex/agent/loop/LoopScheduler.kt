package com.apex.agent.loop

import android.content.Context
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.notify.ApexNotifications
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══ S2 — Loop 循环调度器（单例，tick 轮询模型）═══
 *
 * 宿主与生命周期：
 * - ApexCoreService onCreate start / onDestroy stop（服务宿主：Keep Alive 开启
 *   时循环跨会话存活）；
 * - AgentChatViewModel init 也会 start（幂等）——会话屏打开即保证调度器活着，
 *   这是 LOOP 模式真正的驱动源；VM 侧另有 60s 心跳复活（服务被停后接管）。
 *
 * **设计决策（验收留档）**：
 * 1. 切走 LOOP 模式**不停止**循环——activeLoop 独立于 AgentMode 存活，只有
 *    runsDone 达到 maxRuns、ONCE 到点跑完或用户显式停止才结束；mode != LOOP
 *    时到期的轮次只记 runLog（notifyOnRun 且无人消费时补通知），不注入会话。
 * 2. 前后台判定用「收集者存活」语义：dueEvents 有订阅者（VM 活着）→ 事件
 *    投递给 VM 执行；无订阅者 / 缓冲满 → 通知路径（ONCE 恒发提醒；
 *    INTERVAL/CRON 看 notifyOnRun 发「本轮已跳过」）。
 * 3. persist-then-emit：轮次标记（runsDone++/lastRunAt）先落盘（LoopStore
 *    .mutateAwait），再发射 dueEvent——崩溃窗口内不会双跑。
 * 4. tick 用 Mutex 防重入（15s 心跳 + 手动触发并发时串行化）。
 */
@Singleton
class LoopScheduler @Inject constructor(
    private val store: LoopStore,
    private val notifications: ApexNotifications,
    private val lang: LanguageManager,
    @ApplicationContext private val context: Context
) {
    /** tick 协程域（stop 后可复活：start 检测已取消则重建）。 */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tickJob: Job? = null

    /** tick 防重入（tickOnce 与 markRunNow 共享：轮次标记串行化）。 */
    private val tickMutex = Mutex()

    private val _dueEvents = MutableSharedFlow<LoopConfig>(
        replay = 0,
        extraBufferCapacity = DUE_BUFFER_CAPACITY
    )

    /** 到期轮次事件（载荷 = 已标记并落盘后的最新配置）。 */
    val dueEvents: SharedFlow<LoopConfig> = _dueEvents

    /** store 状态流透传（UI 状态卡 / 会话恢复消费）。 */
    val schedules: StateFlow<LoopState> get() = store.state

    /**
     * 幂等启动 tick 循环；scope 被 [stop] 取消过则重建（VM 心跳复活通道）。
     * 多宿主并发调用安全（tickJob 活跃即 no-op）。
     */
    fun start() {
        if (tickJob?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tickJob = scope.launch {
            while (isActive) {
                runCatching { tickOnce() }.onFailure { e ->
                    // 协程取消必须穿透（不吞 CancellationException）；其余单轮
                    // tick 失败不终止循环（下一跳照常），只留痕。
                    if (e is CancellationException) throw e
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, TAG,
                        "tick failed (${e::class.simpleName}: ${e.message})"
                    )
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** 停止调度（服务宿主销毁时）。已发射事件与 store 状态不受影响。 */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
        scope.cancel()
    }

    /**
     * 下一轮到期时刻（epoch ms）：
     * - INTERVAL：(lastRunAt==0 ? createdAt : lastRunAt) + intervalMs（间隔钳制
     *   下限 MIN_INTERVAL_MS 防打爆；过期即视为到期——补跑一轮后重基 lastRunAt，
     *   最多补一轮，不爆 N 连发）；
     * - CRON：CronSchedule.nextAfter(expr, max(now, lastRunAt))；解析失败 →
     *   Long.MAX_VALUE（永不到期，静默躺平等用户改表达式）；
     * - ONCE：triggerAt（已过且从未跑 → 立即到期补跑；跑过 → enabled=false
     *   被 tick 过滤，不会复活）。
     */
    fun nextRunAt(config: LoopConfig, now: Long): Long = when (config.kind) {
        LoopKind.INTERVAL ->
            (if (config.lastRunAt == 0L) config.createdAt else config.lastRunAt) +
                config.intervalMs.coerceAtLeast(LoopModels.MIN_INTERVAL_MS)
        LoopKind.CRON ->
            CronSchedule.nextAfter(config.cronExpr, maxOf(now, config.lastRunAt)) ?: Long.MAX_VALUE
        LoopKind.ONCE -> config.triggerAt
    }

    /** upsert 一条循环（新建/更新同 id 覆盖）。 */
    fun upsert(config: LoopConfig) {
        store.mutate { ms ->
            val idx = ms.loops.indexOfFirst { it.id == config.id }
            if (idx >= 0) ms.loops[idx] = config else ms.loops.add(config)
        }
    }

    /** 删除一条循环（连带 runLog；保留历史请用 [setEnabled]）。 */
    fun remove(id: String) {
        store.mutate { ms ->
            ms.loops.removeAll { it.id == id }
            ms.runLogs.remove(id)
        }
    }

    /** 启停一条循环（false = 停用但保留配置与 runLog）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        store.mutate { ms ->
            val idx = ms.loops.indexOfFirst { it.id == id }
            if (idx >= 0) ms.loops[idx] = ms.loops[idx].copy(enabled = enabled)
        }
    }

    /**
     * 手动立即触发一轮（UI「立即触发」按钮）：标记 + 落盘后返回最新配置
     * （null = 循环已不存在/已停用/已到 maxRuns）。**不发射 dueEvent**——
     * 用户显式点击，由 VM 直接注入会话（避开 mode 过滤把点击吞掉）。
     */
    suspend fun markRunNow(id: String): LoopConfig? = tickMutex.withLock {
        var marked: LoopConfig? = null
        store.mutateAwait { ms ->
            val idx = ms.loops.indexOfFirst { it.id == id }
            if (idx < 0) return@mutateAwait
            val cur = ms.loops[idx]
            if (!cur.enabled || cur.runsDone >= cur.maxRuns) return@mutateAwait
            marked = markOne(ms, idx, cur, nowMs())
        }
        marked
    }

    /** 单次扫描：到期轮次标记 → 落盘 → 投递/通知。 */
    private suspend fun tickOnce() = tickMutex.withLock {
        val now = nowMs()
        val due = store.state.value.loops.filter {
            it.enabled && it.runsDone < it.maxRuns && nextRunAt(it, now) <= now
        }
        for (config in due) {
            var marked: LoopConfig? = null
            store.mutateAwait { ms ->
                val idx = ms.loops.indexOfFirst { it.id == config.id }
                if (idx < 0) return@mutateAwait
                val cur = ms.loops[idx]
                // 复核（手动触发可能已抢先标记这一轮）
                if (!cur.enabled || cur.runsDone >= cur.maxRuns || nextRunAt(cur, now) > now) {
                    return@mutateAwait
                }
                marked = markOne(ms, idx, cur, now)
            }
            marked?.let { dispatch(it) }
        }
    }

    /** 就地标记一轮（runsDone++ / lastRunAt / 终态停用 / runLog 追加 + FIFO 截断）。 */
    private fun markOne(ms: MutableLoopState, idx: Int, cur: LoopConfig, now: Long): LoopConfig {
        val runsDone = cur.runsDone + 1
        val finished = cur.kind == LoopKind.ONCE || runsDone >= cur.maxRuns
        val updated = cur.copy(
            runsDone = runsDone,
            lastRunAt = now,
            enabled = if (finished) false else cur.enabled
        )
        ms.loops[idx] = updated
        ms.runLogs.getOrPut(cur.id) { mutableListOf() }.apply {
            add(
                LoopRunLog(
                    at = now,
                    ok = true,
                    summary = "round ${runsDone}/${cur.maxRuns} ${cur.kind.name.lowercase()}"
                )
            )
            while (size > LoopModels.MAX_RUN_LOGS) removeAt(0)
        }
        return updated
    }

    /**
     * 投递一期到期轮次：有收集者（会话屏 VM 活着）→ tryEmit 缓冲投递；
     * 无收集者 / 缓冲满 → 通知路径（apex_general 渠道，静默失败语义见
     * ApexNotifications：未授权即丢弃，绝不崩主流程）。
     */
    private fun dispatch(config: LoopConfig) {
        val hasCollector = _dueEvents.subscriptionCount.value > 0
        if (hasCollector && _dueEvents.tryEmit(config)) return
        // 无人消费（或缓冲满）：后台触发路径
        if (config.kind == LoopKind.ONCE) {
            notifications.notifyGeneral(
                context,
                lang.getString(R.string.loop_notif_once_title),
                config.prompt.take(NOTIF_PROMPT_MAX)
            )
        } else if (config.notifyOnRun) {
            notifications.notifyGeneral(
                context,
                lang.getString(R.string.loop_notif_bg_title),
                lang.getString(R.string.loop_notif_bg_text)
            )
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "LoopScheduler"

        /** tick 心跳（15s：分钟级调度语义下足够实时，电量友好）。 */
        internal const val TICK_INTERVAL_MS: Long = 15_000L

        /** dueEvents 缓冲（突发多循环到期时 VM 慢消费不丢事件）。 */
        private const val DUE_BUFFER_CAPACITY = 16

        /** 通知正文截断（对齐 ApexNotifications 预览 60 字纪律）。 */
        private const val NOTIF_PROMPT_MAX = 60
    }
}
