package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # MCP 连接健康监督器 —— 本地运行容错能力的补全件
 *
 * ## 解决的问题
 *
 * [McpManager.connect] 成功后连接就躺在 `clients` 表里无人看护：
 * - **STDIO 子进程死亡**（Android 低内存 OOM kill、npx 崩溃、PRoot 会话
 *   异常）后，条目仍是「已连接」——`getConnectedServers()` 照常列出它，
 *   每次 `tools/call` 都报「MCP 本地进程已退出」，直到用户手动重连；
 * - 没有任何自动恢复：本地服务器（沙箱 npx 形态）恰恰是最容易意外退出的
 *   一类，却是唯一没有守护的一类；
 * - 死亡不可见：市场页「已连接」徽标与真实进程状态脱节。
 *
 * ## 双通道检测（事件 + 看门狗）
 *
 * 1. **看门狗循环**（[start] 起，每 [checkIntervalMs]）：对每台已连接服务
 *    器做 [McpManager.isClientHealthy] 探测（HTTP 传输恒健康，探针只对
 *    STDIO 子进程与真实传输有分辨力）——死亡即进入补救流程；
 * 2. **会话监听**（onServerConnected）：连接（含用户手动重连）成功后
 *    清零该服务器的失败计数——**刻意不订阅 onServerDisconnected**：
 *    断开可能是用户主动行为（意图就是不连），监听它会把用户断开自动
 *    拉回来，劫持用户操作。只有「配置启用 + 传输死亡」这种非用户意图
 *    的状态才触发重连。
 *
 * ## 补救流程（每服务器一把 Mutex 串行）
 *
 * 1. 复核仍不健康（拿锁期间可能已被手动重连救活）；
 * 2. [McpManager.disconnect] 收尸：移除僵尸条目、关闭残留句柄、触发
 *    SessionListener（McpToolRegistrar 顺带清掉幽灵工具）；
 * 3. 配置仍 `enabled` → 按指数退避重连 [BACKOFF_SCHEDULE_MS]
 *    （2s→8s→30s→120s→300s→600s，覆盖 npx 冷启动下载 + rootfs 迟到
 *    就绪等真实恢复窗口）；每次失败经 [states] 对外可见；
 * 4. 连续 [MAX_RECONNECT_ATTEMPTS] 次失败 → 放弃（gaveUp 态），等用户
 *    手动处理——无限重试会变成日志洪水与电量黑洞；手动重连成功后
 *    计数清零、自动恢复监护。
 *
 * ## 语义边界
 *
 * - 用户主动 `disconnect`/`setEnabled(false)`/`removeServer` 永不触发重连
 *   （看门狗只看「clients 表里还在 + 传输已死」的自相矛盾状态）；
 * - BUILTIN 进程内传输的 isHealthy 由实现自报（内置五台恒真，不受影响）；
 * - 重连走 [McpManager.connect] 全链路（真实启动事件、McpToolRegistrar
 *   重新发现工具），与手动连接零差别。
 *
 * ## 线程模型
 *
 * 全部协程跑在注入的 [scope]（建议 SupervisorJob + Dispatchers.IO）。
 * [perServerLocks] 保证同一服务器的补救/手动重启不并发；[stop] 只取消
 * 看门狗 Job 与在途补救 Job，不动宿主 scope。
 */
class McpSupervisor(
    private val manager: McpManager,
    private val scope: CoroutineScope,
    /** 诊断日志出口（core 模块不依赖 logging，同 McpManager.errorLog 约定）。 */
    private val logger: (String) -> Unit = {},
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS
) {

    /** 单台服务器的监督态（UI 诊断 / 市场页徽标数据源）。 */
    data class SupervisedState(
        val serverName: String,
        /** 连续重连失败次数（连接成功后清零）。 */
        val consecutiveFailures: Int = 0,
        /** 下次自动重连的墙钟时间戳；null = 无排期（健康 / 已放弃 / 补救中）。 */
        val nextRetryAtMs: Long? = null,
        /** 连续失败达到上限后的放弃标记；手动重连成功即复位。 */
        val gaveUp: Boolean = false
    )

    private val watchLock = Any()
    private var watchdogJob: Job? = null
    private val remediationJobs = mutableMapOf<String, Job>()

    /** 每服务器互斥：补救流程与手动 [restartServer] 串行化。 */
    private val perServerLocks = mutableMapOf<String, Mutex>()

    private val _states = MutableStateFlow<Map<String, SupervisedState>>(emptyMap())
    val states: StateFlow<Map<String, SupervisedState>> = _states.asStateFlow()

    /** 已启动（幂等；[stop] 后可再次 start）。 */
    private var started = false

    init {
        // 事件通道：连接成功清零失败计数（含用户手动重连 —— 手动救活后
        // 自动恢复监护）。不订阅 onServerDisconnected（见类 KDoc 语义边界）。
        manager.addSessionListener(object : McpManager.SessionListener {
            override fun onServerConnected(serverName: String) {
                clearFailure(serverName)
            }

            override fun onServerDisconnected(serverName: String) = Unit
        })
    }

    /** 启动看门狗（幂等）。 */
    fun start() {
        synchronized(watchLock) {
            if (started) return
            started = true
            watchdogJob = scope.launch { watchdogLoop() }
        }
        logger("McpSupervisor 已启动（巡检间隔 ${checkIntervalMs}ms）")
    }

    /** 停止：取消看门狗与在途补救，不动宿主 scope。 */
    fun stop() {
        val jobs: List<Job> = synchronized(watchLock) {
            started = false
            val all = listOfNotNull(watchdogJob) + remediationJobs.values.toList()
            watchdogJob = null
            remediationJobs.clear()
            all
        }
        jobs.forEach { it.cancel() }
    }

    /**
     * 手动重启一台服务器（市场页「重启」入口 / 诊断操作）：
     * 断开 → 重连，失败计数清零。与补救流程共用同一把服务器锁，
     * 并发调用自然排队。返回 connect 的握手结果。
     */
    suspend fun restartServer(name: String): Result<McpCapabilities> =
        lockFor(name).withLock {
            manager.disconnect(name)
            manager.connect(name).also { result ->
                if (result.isSuccess) clearFailure(name)
                else recordFailure(name)
            }
        }

    /** 看门狗主循环：探测 → 死亡派发补救（每台独立 Job，互不拖累）。 */
    private suspend fun watchdogLoop() {
        while (true) {
            delay(checkIntervalMs)
            // 快照后逐一探测；派发在锁外（launch 不持锁等待）
            for (name in manager.getConnectedServers()) {
                if (manager.isClientHealthy(name)) continue
                dispatchRemediation(name)
            }
        }
    }

    /** 派发一台死传输服务器的补救（幂等：在途则跳过）。 */
    private fun dispatchRemediation(name: String) {
        val job = synchronized(watchLock) {
            if (!started) return
            if (remediationJobs.containsKey(name)) return
            scope.launch { remediate(name) }.also { remediationJobs[name] = it }
        }
        job.invokeOnCompletion {
            synchronized(watchLock) { remediationJobs.remove(name) }
        }
    }

    /**
     * 补救主体（持服务器锁执行）：
     * 复核 → 收尸 → （配置仍启用则）退避重连 → 达上限放弃。
     */
    private suspend fun remediate(name: String) {
        lockFor(name).withLock {
            // 复核：拿锁期间可能已被手动重连救活（restartServer 与本流程同锁）
            if (!manager.getConnectedServers().contains(name)) return
            if (manager.isClientHealthy(name)) return

            logger("检测到 MCP 服务器 '$name' 传输已死亡（进程退出/通道关闭），开始收尸与恢复")
            // 收尸：移除僵尸条目 + 关闭残留句柄 + 触发 SessionListener
            //（McpToolRegistrar 顺带清掉幽灵工具）
            manager.disconnect(name)

            // 用户意图检查：配置已删 / 已禁用 → 顺势收场，绝不复活用户关掉的东西
            val config = manager.getConfigs().firstOrNull { it.name == name }
            if (config == null || !config.enabled) {
                updateState(name) { it.copy(consecutiveFailures = 0, nextRetryAtMs = null) }
                logger("服务器 '$name' 配置已禁用或删除，不自动重连")
                return
            }

            // 指数退避重连（BUILTIN/STDIO/HTTP 全走同一 connect 全链路）
            var attempt = 0
            while (attempt < MAX_RECONNECT_ATTEMPTS) {
                val backoffMs = BACKOFF_SCHEDULE_MS[attempt.coerceAtMost(BACKOFF_SCHEDULE_MS.lastIndex)]
                updateState(name) {
                    it.copy(
                        consecutiveFailures = attempt,
                        nextRetryAtMs = System.currentTimeMillis() + backoffMs
                    )
                }
                logger("服务器 '$name' 将在 ${backoffMs / 1000}s 后第 ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS 次重连")
                delay(backoffMs)

                // 退避期间配置可能被用户关掉/删除——每轮重验用户意图
                val cfg = manager.getConfigs().firstOrNull { it.name == name }
                if (cfg == null || !cfg.enabled) {
                    updateState(name) { it.copy(nextRetryAtMs = null) }
                    logger("服务器 '$name' 在退避期间被禁用/删除，停止重连")
                    return
                }

                val result = manager.connect(name)
                if (result.isSuccess) {
                    clearFailure(name)
                    logger("服务器 '$name' 自动重连成功（第 ${attempt + 1} 次尝试）")
                    return
                }
                logger(
                    "服务器 '$name' 自动重连失败（第 ${attempt + 1} 次）：" +
                        "${result.exceptionOrNull()?.message ?: "unknown"}"
                )
                attempt++
            }

            // 达上限：放弃自动恢复，等用户手动处理
            updateState(name) { it.copy(consecutiveFailures = attempt, nextRetryAtMs = null, gaveUp = true) }
            logger(
                "服务器 '$name' 连续 $MAX_RECONNECT_ATTEMPTS 次重连失败，已停止自动重连" +
                    "——请检查网络/沙箱环境后在市场页手动重连"
            )
        }
    }

    private fun lockFor(name: String): Mutex = synchronized(watchLock) {
        perServerLocks.getOrPut(name) { Mutex() }
    }

    private fun clearFailure(name: String) {
        _states.update { current ->
            val had = current[name]
            if (had != null && (had.consecutiveFailures != 0 || had.gaveUp || had.nextRetryAtMs != null)) {
                current + (name to SupervisedState(serverName = name))
            } else {
                current
            }
        }
    }

    private fun recordFailure(name: String) {
        updateState(name) { it.copy(consecutiveFailures = it.consecutiveFailures + 1) }
    }

    private inline fun updateState(name: String, transform: (SupervisedState) -> SupervisedState) {
        _states.update { current ->
            val base = current[name] ?: SupervisedState(serverName = name)
            current + (name to transform(base))
        }
    }

    companion object {
        /** 看门狗巡检间隔：10s（isClientHealthy 是纯内存/进程存活探测，代价可忽略）。 */
        const val DEFAULT_CHECK_INTERVAL_MS = 10_000L

        /** 最大连续自动重连次数（超过即放弃，等用户手动处理）。 */
        const val MAX_RECONNECT_ATTEMPTS = 6

        /**
         * 指数退避表（下标 = 已失败次数）：
         * 2s → 8s → 30s → 120s → 300s → 600s。
         *
         * 覆盖的真实恢复窗口：npx 冷启动重新下载包（移动网络分钟级）、
         * PRoot rootfs 迟到就绪（用户正在装 Ubuntu）、瞬时 OOM 后的内存
         * 回收。600s 封顶防止重连风暴；总跨度 ~17 分钟后放弃。
         */
        val BACKOFF_SCHEDULE_MS = longArrayOf(
            2_000L, 8_000L, 30_000L, 120_000L, 300_000L, 600_000L
        )
    }
}
