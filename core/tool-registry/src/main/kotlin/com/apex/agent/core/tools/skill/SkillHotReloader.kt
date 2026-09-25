package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DuplicateToolIdPolicy
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/** 技能热同步日志级别：本组件只产生信息与警告两级。 */
enum class SkillHotReloadLogLevel { INFO, WARN }

/**
 * 日志出口。core:tool-registry 模块不依赖 core:logging，由宿主（app 层 DI）以
 * lambda 注入实现；不传时构造默认值为静默实现，纯 JVM 单测无需任何日志设施。
 */
fun interface SkillHotReloadLogSink {

    /** 接收一条日志：[level] 为级别，[message] 为已格式化的中文消息。 */
    fun log(level: SkillHotReloadLogLevel, message: String)
}

/**
 * 技能工具热注册器（Issue #151）。
 *
 * ## 解决的问题
 *
 * 旧链路（ToolModule 启动快照）只在 App 启动时对 [SkillRegistry.getActiveTools]
 * 做一次性快照注册，此后安装、卸载、启停技能都不生效，必须重启 App。prompt 注入
 * 本来就是热的（engine 每轮构建系统提示词都重读 getPromptInjections），本类补齐
 * 另一半：把启用技能声明的工具持续同步进 [ToolRegistry]，装、卸、开关即刻生效。
 *
 * ## 同步算法（增量 diff，不做全量重建）
 *
 * 每次 [resync] 只做差异同步，绝不先全部 unregister 再重新 register——那样会
 * 闪断正在执行的调用（差异间隙里的查找会落空，正在运行的调用步骤引用也会错乱）：
 *
 * 1. 期望集 desired = [SkillRegistry.getActiveTools]，不同技能重复声明的工具 id
 *    只保留首个声明，其余记 warn 跳过；
 * 2. 白名单内不再被期望的 id → unregister（先移除后注册，天然覆盖「卸旧装新」）；
 * 3. 期望集中注册表缺失的 id → 以 [SafeAgentTool] 包装 [SkillToolAdapter] 注册，
 *    策略 REPLACE；
 * 4. 已注册但定义（名称、描述、参数 schema）发生变化的 id → REPLACE 刷新包装
 *    实例，支持技能原地升级（不先卸载直接 install 新版本）。
 *
 * ## 防劫持机制（核心）
 *
 * REPLACE 策略意味着技能工具理论上能覆盖任意已注册工具——放任的话，一个声明了
 * 与核心工具同 id 的技能就能把它劫持掉。防线共三层：
 *
 * 1. skillOwnedIds 白名单：只有本 reloader 注册过（或启动时吸收的遗留快照）的
 *    id 才允许 unregister，绝不触碰不认识的工具；
 * 2. 注册时若目标 id 已存在且不在白名单内 → 跳过并 warn，把 id 让给既有工具；
 * 3. ownedWrappers 实例核对：卸载与刷新前，比对注册表里当前实例是否仍是自己
 *    注册的那一个，若已被外部用 REPLACE 覆盖过则同样让位，避免反向劫持别人的覆盖。
 *
 * 首次 [start] 时做遗留快照吸收：老版本升级路径下，ToolModule 的启动快照已经把
 * 技能工具注册进了注册表——把「注册表已有且属于当前启用技能」的 id 全部并入
 * 白名单。不吸收的话，这些旧工具永远无法被热卸载，升级用户会被锁死在旧工具上。
 *
 * ## 变更订阅（不做防抖的理由）
 *
 * 订阅 [SkillRegistry.changes]（安装、卸载、启停都会 tryEmit），每收到一次就
 * 同步执行一次 resync。不做防抖：该流的发射频率是用户操作级（点一次安装、拨一次
 * 开关），不是 token 级洪峰；而 resync 本身是幂等 diff，代价只有一次 getActiveTools
 * 快照读加若干次 map 查找。直接同步比引入防抖状态机更简单、更不容易漏事件。
 *
 * ## 线程模型
 *
 * 全部状态（skillOwnedIds、ownedWrappers、started、collectorJob）由 [syncLock]
 * 监视器锁保护；锁内对注册表的调用方向恒为 syncLock 指向注册表内部锁，单向不
 * 回头。本类不注册 ToolRegistry 的注册监听器，因此不存在反向调用路径，无死锁面。
 * [scope] 由宿主注入（建议 SupervisorJob 加 Dispatchers.IO），[stop] 只取消自己
 * 启动的订阅 Job，不触碰宿主 scope 的生命周期。
 *
 * ## 日志桥接
 *
 * 本模块（core:tool-registry）不依赖 core:logging，因此以 [SkillHotReloadLogSink]
 * 注入日志出口。app 层 DI 装配时以尾随 lambda 传入：[SkillHotReloadLogLevel.INFO]
 * 映射为 AppLogger.instance.info(LogCategory.PLUGIN, "SkillHotReloader", message)，
 * [SkillHotReloadLogLevel.WARN] 映射为 AppLogger.instance.warn(LogCategory.PLUGIN,
 * "SkillHotReloader", message)。分类用 PLUGIN（插件与技能），来源标签统一
 * "SkillHotReloader"。
 */
class SkillHotReloader(
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val scope: CoroutineScope,
    private val logger: SkillHotReloadLogSink = SkillHotReloadLogSink { _, _ -> }
) {

    /** 同步监视器锁：保护下方全部可变状态与注册序列。 */
    private val syncLock = Any()

    /**
     * 技能可占用 id 白名单：本 reloader 注册过、或启动时吸收的旧快照遗留 id。
     * 只有白名单内的 id 才允许 unregister——这是防劫持的第一道防线。
     */
    private val skillOwnedIds = LinkedHashSet<String>()

    /**
     * 本 reloader 实际注册进注册表的包装实例（id 到注册时用的 SafeAgentTool）。
     * 卸载与刷新前用它做所有权核对：若注册表里的实例已不是自己注册的那个，
     * 说明被外部 REPLACE 过，让位不动。吸收的遗留 id 不在本表内（实例未知）。
     */
    private val ownedWrappers = HashMap<String, AgentTool>()

    /** changes 订阅协程；null 表示未订阅。全部读写都在 [syncLock] 内。 */
    private var collectorJob: Job? = null

    /** [start] 是否已执行（[stop] 复位，允许重新 start）。 */
    private var started = false

    /**
     * 启动：遗留快照吸收 + 首次全量同步 + 订阅 [SkillRegistry.changes]。
     * 幂等：重复调用直接返回，不会叠加第二个订阅协程。
     *
     * 订阅体带 [onSubscription] 补同步：collect 启动到真正挂上订阅之间存在
     * 窗口，窗口内 tryEmit 的变更会被 SharedFlow 丢弃（replay=0）——订阅就位
     * 后立刻全量 diff 一次即可兜住（幂等，无副作用）。
     */
    fun start() {
        synchronized(syncLock) {
            if (started) return
            started = true
            absorbLegacySnapshotLocked()
            syncLocked()
            // 在锁内 launch：订阅体首次挂起等待发射，不会回头抢锁，
            // 同时封死「start 后立刻 stop」与订阅 Job 赋值之间的竞态窗口。
            collectorJob = scope.launch {
                skillRegistry.changes
                    .onSubscription { resync() }
                    .collect { resync() }
            }
        }
    }

    /**
     * 停止：只取消自己的订阅 Job（scope 由宿主管理，这里不碰）。
     * 已注册的技能工具保持原样不清除；之后仍可 [resync] 手动同步，或再次
     * [start] 重新订阅。
     */
    fun stop() {
        val job = synchronized(syncLock) {
            started = false
            val j = collectorJob
            collectorJob = null
            j
        }
        job?.cancel()
    }

    /**
     * 手动触发一次全量 diff 同步（测试与兜底用）。
     * 与 changes 订阅走同一算法，幂等可重入；未 [start] 时也可调用
     * （此时不做遗留吸收——吸收只在 start 时发生一次）。
     */
    fun resync() {
        synchronized(syncLock) { syncLocked() }
    }

    /**
     * 当前由技能贡献、且确实还在注册表里的工具 id 快照（诊断用）。
     * 被「已被占用」防线跳过的 id 不会出现在结果里——它并非技能注册的。
     */
    fun registeredSkillToolIds(): Set<String> = synchronized(syncLock) {
        skillOwnedIds.filterTo(LinkedHashSet()) { toolRegistry.getTool(it) != null }
    }

    // ── 内部实现：以下方法全部要求持有 [syncLock] ──────────────────

    /**
     * 遗留快照吸收（只在首次 start 时执行一次）。
     * 老版本升级路径：ToolModule 启动快照已把技能工具注册进注册表，把这些
     * id 并入白名单，使它们从此可被热卸载、热刷新。
     */
    private fun absorbLegacySnapshotLocked() {
        val activeIds = skillRegistry.getActiveTools().mapTo(HashSet()) { it.id }
        val absorbed = toolRegistry.getAllTools().map { it.id }.filter { it in activeIds }
        absorbed.forEach { skillOwnedIds.add(it) }
        if (absorbed.isNotEmpty()) {
            log(
                SkillHotReloadLogLevel.INFO,
                "吸收旧版启动快照遗留的技能工具 id：${absorbed.joinToString(", ")}"
            )
        }
    }

    /** 增量 diff 同步主体，见类 KDoc 的算法与防劫持说明。 */
    private fun syncLocked() {
        // 1. 期望集：启用技能声明的工具，重复 id 只保留首个声明
        val desired = LinkedHashMap<String, SkillToolDef>()
        for (def in skillRegistry.getActiveTools()) {
            if (desired.containsKey(def.id)) {
                log(
                    SkillHotReloadLogLevel.WARN,
                    "技能工具 id '${def.id}' 被多个技能重复声明，仅保留首个声明"
                )
                continue
            }
            desired[def.id] = def
        }

        var added = 0
        var removed = 0

        // 2. 先移除：白名单内、但已不在期望集的 id（技能卸载或禁用）
        val staleIds = skillOwnedIds.filter { it !in desired }
        for (id in staleIds) {
            val known = ownedWrappers[id]
            val current = toolRegistry.getTool(id)
            when {
                // 已不在注册表（被外部移除）：只清账本，不重复调用 unregister
                current == null -> Unit
                // 实例已不是自己注册的那个：被外部 REPLACE 过，让位不误删
                known != null && current !== known -> log(
                    SkillHotReloadLogLevel.WARN,
                    "技能工具 '$id' 已被外部工具替换，跳过卸载以避免误删"
                )
                else -> {
                    toolRegistry.unregister(id)
                    removed++
                }
            }
            skillOwnedIds.remove(id)
            ownedWrappers.remove(id)
        }

        // 3. 再注册与刷新：期望集中缺失或定义已变化的工具
        for ((id, def) in desired) {
            val current = toolRegistry.getTool(id)
            if (current == null) {
                // 全新 id，或白名单内的 id 被外部移除后自愈补回
                registerLocked(def)
                added++
                continue
            }
            if (id !in skillOwnedIds) {
                // 防劫持第二道防线：id 已被非技能工具占用，让位并告警
                log(
                    SkillHotReloadLogLevel.WARN,
                    "工具 id '$id' 已被非技能工具占用，跳过注册以避免劫持"
                )
                continue
            }
            val known = ownedWrappers[id]
            if (known != null && current !== known) {
                // 防劫持第三道防线：自己的 id 被外部覆盖过，让位并放弃接管
                log(
                    SkillHotReloadLogLevel.WARN,
                    "技能工具 '$id' 已被外部工具替换，不再接管该 id"
                )
                skillOwnedIds.remove(id)
                ownedWrappers.remove(id)
                continue
            }
            // 定义变化（技能原地升级）才 REPLACE 刷新，避免无谓的实例抖动
            if (current.name != def.name ||
                current.description != def.description ||
                current.parametersSchema != def.parameters
            ) {
                registerLocked(def)
                added++
            }
        }

        log(
            SkillHotReloadLogLevel.INFO,
            "技能工具热同步：+$added -$removed（总数 ${skillOwnedIds.size}）"
        )
    }

    /** 注册一个技能工具（SafeAgentTool 统一安全包装），并登记白名单与实例账本。 */
    private fun registerLocked(def: SkillToolDef) {
        val wrapper = SafeAgentTool(SkillToolAdapter(def, toolExecutor))
        toolRegistry.register(wrapper, DuplicateToolIdPolicy.REPLACE)
        skillOwnedIds.add(def.id)
        ownedWrappers[def.id] = wrapper
    }

    private fun log(level: SkillHotReloadLogLevel, message: String) {
        logger.log(level, message)
    }
}
