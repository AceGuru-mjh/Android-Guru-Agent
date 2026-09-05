package com.apex.agent.core.engine.task

import kotlinx.coroutines.flow.Flow

/**
 * T76 — Task 持久化存储接口（N-2）。
 *
 * 设计契约（任务书 §4/§6/§21，审计 D-1）：
 * - **原子性**：[save] 必须实现 temp 写入 → fsync → 原子 rename。进程在
 *   写入中途被杀时，磁盘上要么是旧 checkpoint、要么是完整新 checkpoint，
 *   绝无半写状态（半写 temp 文件在下次扫描时清理）。
 * - **发现**：[loadActiveTasks] 供启动恢复扫描（RUNNING/WAITING_USER 等
 *   活跃态任务，含半写 temp 清理 + 损坏文件隔离）。
 * - **宽容解析**：schema 版本 + `ignoreUnknownKeys`——未来新增字段不破坏
 *   旧文件读取（先读后写升级）。
 * - **损坏隔离**：解析失败移入 `corrupt/` 而非删除（用户可排查），
 *   不阻塞其他任务的加载。
 *
 * 实现注意：方法均为同步阻塞 IO 语义，调用方（TaskRuntime）负责在 IO
 * dispatcher 上调度。纯 JVM 模块只暴露接口，Android 侧由 DI 提供目录；
 * [FileTaskStore] 本身零 Android 依赖（java.io），测试直接用临时目录。
 */
interface TaskStore {

    /**
     * 原子保存任务 checkpoint（全量单文件覆盖）。
     * @throws TaskStoreIOException 底层 IO 失败（磁盘满等）——调用方决定降级策略
     */
    fun save(task: AgentTask)

    /**
     * 加载单个任务。文件不存在返回 null；解析失败 → 隔离到 corrupt/ 并返回
     * null（不抛异常，避免一个坏文件阻塞启动扫描）。
     */
    fun load(taskId: String): AgentTask?

    /**
     * 启动恢复扫描：返回所有处于活跃态（[AgentTask.isActive]）的任务，
     * 顺手完成两项清理：
     * 1. 删除半写 temp 文件（崩溃残留）；
     * 2. 隔离损坏文件（移入 corrupt/）。
     * 返回按 updatedAt 降序（最近活跃在前）。
     */
    fun loadActiveTasks(): List<AgentTask>

    /**
     * 全量任务历史（含终态），按 createdAt 降序。UI 任务历史列表用。
     * 损坏文件同样隔离并跳过。
     */
    fun loadAllTasks(): List<AgentTask>

    /**
     * 删除任务文件（UI 手动删除历史记录）。终态任务专用；活跃任务删除
     * 前应先由 TaskRuntime 转为终态。物理删除，不可恢复。
     */
    fun delete(taskId: String)

    /**
     * 变更通知（可选能力）：任务落盘后发射。实现可选——UI 进度刷新可用
     * 亦可不用（TaskRuntime 的内存态流转足够 UI 消费）。默认空实现。
     */
    val changes: Flow<AgentTask> get() = kotlinx.coroutines.flow.emptyFlow()
}

/** TaskStore 底层 IO 异常（区别于解析失败——后者走损坏隔离路径）。 */
class TaskStoreIOException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * 任务 ID 生成器（稳定、可排序、进程内唯一）。
 *
 * `task-<epochMillis>-<random4hex>`：时间前缀保证可读可排序；随机后缀
 * 防同毫秒并发创建碰撞。
 */
object TaskIds {
    fun newId(nowMs: Long = System.currentTimeMillis(), random: Int = (0..0xFFFF).random()): String =
        "task-$nowMs-${"%04x".format(random)}"
}
