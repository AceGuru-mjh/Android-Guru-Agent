package com.apex.agent.platform.terminal.ubuntu.lifecycle

/**
 * T93: 降级注记的自动重试口径 + 冷却闸门（纯 Kotlin，JVM 直测）。
 *
 * 背景（用户反馈「Ubuntu 总是显示 apt 未引导」的第 5 洞 —— 前四洞见 T87 §10）：
 * bootstrap 降级 READY（READY + [UbuntuLifecycleCoordinator.LifecycleState.bootstrapNote]）
 * 此前只有三个清除出口 —— 启动自动预备、网络离线→在线跃迁、环境中心手动重试。
 * **设备全程在线的瞬时失败**（引导瞬间 proot 二进制尚未就绪 / dpkg 锁 /
 * DNS 抖动）三个出口一个都不触发：没重启、没断网、用户没点 → 注记一直挂到
 * 下次冷启动。
 *
 * 本类给出唯一口径：该不该重试（[UbuntuBootstrapRetryPolicy.decide]）+ 重试节流
 * （冷却窗口 + 在途去重，[UbuntuRetryThrottle]）。app 层两个出口（网络恢复跃迁 /
 * 冷却轮询）以及将来的工具/服务层都消费同一份决策，不各自拍脑袋。
 *
 * 设计纪律：
 * - **纯决策**：无副作用、时钟由调用方注入 → 假钟矩阵测试；
 * - **绝不伪造成功**：重试失败只是又回到降级态，注记照旧（UI 语义不变）；
 * - **DISK_FULL 例外**：磁盘不足不会自己好 —— 腾空间之前重试只会重复失败，
 *   纯噪音，直接不动作；
 * - **网络恢复出口不吃冷却**：跃迁本身就是「条件已变」的信号（冷启动时离线、
 *   两分钟后联网，此时离上次尝试很近也该立刻重试）；冷却只约束轮询出口；
 * - **FAILED 不自动重试** —— 与启动策略一致，保留失败现场给用户手动重试。
 */
data class UbuntuRetryDecision(
    val shouldRetry: Boolean,
    /** 决策依据（`false` 时说明被哪条规则拦下，进日志便于事后追溯）。 */
    val reason: String
)

object UbuntuBootstrapRetryPolicy {

    /** 降级期两次**轮询**重试的最小间隔（15 分钟 ≈ 一次 apt 镜像链失败的量级）。 */
    const val DEFAULT_COOLDOWN_MS: Long = 15 * 60 * 1000L

    /** 冷却轮询的检查间隔（1 分钟 —— 只读状态，成本可忽略）。 */
    const val POLL_INTERVAL_MS: Long = 60 * 1000L

    /** 磁盘不足标记（apt 包操作层结构化错误码，会出现在 bootstrapNote 文本里）。 */
    private const val DISK_FULL_MARKER = "DISK_FULL"

    /**
     * 唯一决策入口（纯函数）。
     *
     * @param phase 编排层当前 phase
     * @param bootstrapNote 降级注记（READY 时非 null 即「引导未完成」）
     * @param online 宿主网络是否在线
     * @param lastAttemptAtMs 上次自动重试起点（null = 从未重试）
     * @param nowMs 当前时刻（假钟注入）
     * @param cooldownMs 冷却窗口
     * @param retryInFlight 已有重试在途（幂等单飞）
     * @param respectCooldown 是否受冷却约束（轮询 true / 网络恢复跃迁 false）
     */
    fun decide(
        phase: UbuntuLifecycleCoordinator.Phase,
        bootstrapNote: String?,
        online: Boolean,
        lastAttemptAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        retryInFlight: Boolean = false,
        respectCooldown: Boolean = true
    ): UbuntuRetryDecision = when {
        retryInFlight ->
            UbuntuRetryDecision(false, "已有重试在途（幂等单飞）")
        !online ->
            UbuntuRetryDecision(false, "离线 —— 等网络恢复跃迁出口")
        phase == UbuntuLifecycleCoordinator.Phase.FAILED ->
            UbuntuRetryDecision(false, "FAILED 失败现场 —— 保留给用户手动重试")
        bootstrapNote != null && bootstrapNote.contains(DISK_FULL_MARKER) ->
            UbuntuRetryDecision(false, "磁盘不足（DISK_FULL）—— 腾出空间前重试无意义")
        !hasPendingBootstrap(phase, bootstrapNote) ->
            UbuntuRetryDecision(false, "phase=$phase 且无降级注记 —— 环境已完整 READY")
        respectCooldown && lastAttemptAtMs != null &&
            nowMs - lastAttemptAtMs < cooldownMs ->
            UbuntuRetryDecision(
                false,
                "冷却中（距上次重试 ${nowMs - lastAttemptAtMs}ms < ${cooldownMs}ms）"
            )
        else ->
            UbuntuRetryDecision(
                true,
                "引导未完成（phase=$phase）且冷却已过 —— 自动补一次引导"
            )
    }

    /**
     * 「有引导工作待补」的 phase 判定：
     * - READY + 注记 → 引导降级（T83 语义：环境可用但 apt 引导未完成）；
     * - ROOTFS_READY → rootfs 就绪但引导从未跑完（启动/崩溃残留）；
     * - BOOTSTRAPPING → 引导被打断（进程终止/整体超时）后停在进行态。
     *
     * NOT_INSTALLED（根fs 都没有）、INSTALLING（正在解包）、RECOVERING
     * （reconcile/repair 进行中）一律不算 —— 这三态正由别的流程负责。
     */
    internal fun hasPendingBootstrap(
        phase: UbuntuLifecycleCoordinator.Phase,
        bootstrapNote: String?
    ): Boolean = when (phase) {
        UbuntuLifecycleCoordinator.Phase.READY -> bootstrapNote != null
        UbuntuLifecycleCoordinator.Phase.ROOTFS_READY,
        UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING -> true
        else -> false
    }
}

/**
 * 冷却闸门：在途去重 + 冷却起点记账（两个出口共享一份状态）。
 *
 * 线程模型：只在 app 主线程创建、后台协程读写；`begin`/`end` 用 `synchronized`
 * 保证「抢占 + 打冷却起点」原子（与 NetworkMonitor 的 arbitrationLock 同一哲学：
 * 锁只护读-判-写原子性，不护业务）。
 */
class UbuntuRetryThrottle(
    private val cooldownMs: Long = UbuntuBootstrapRetryPolicy.DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    private var lastAttemptAtMs: Long? = null

    @Volatile
    private var inFlight: Boolean = false

    /** 纯读决策（不抢占；抢占用 [begin]）。 */
    fun decide(
        phase: UbuntuLifecycleCoordinator.Phase,
        bootstrapNote: String?,
        online: Boolean,
        respectCooldown: Boolean = true
    ): UbuntuRetryDecision = UbuntuBootstrapRetryPolicy.decide(
        phase = phase,
        bootstrapNote = bootstrapNote,
        online = online,
        lastAttemptAtMs = lastAttemptAtMs,
        nowMs = clock(),
        cooldownMs = cooldownMs,
        retryInFlight = inFlight,
        respectCooldown = respectCooldown
    )

    /**
     * 抢占重试名额：成功即把「此刻」记为冷却起点（重试耗时长短不计入冷却 ——
     * 冷却约束的是「两次尝试的发起间隔」，不是执行时长）。
     *
     * @return false = 已有重试在途，调用方放弃本次。
     */
    fun begin(): Boolean {
        synchronized(this) {
            if (inFlight) return false
            inFlight = true
            lastAttemptAtMs = clock()
        }
        return true
    }

    /** 释放名额（finally 调用）。 */
    fun end() {
        synchronized(this) {
            inFlight = false
        }
    }

    /** 当前冷却起点（null = 从未重试；诊断/测试用）。 */
    fun lastAttemptAt(): Long? = lastAttemptAtMs
}
