package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.flow.Flow
import rikka.shizuku.Shizuku

/**
 * Shizuku 流式执行适配器。
 *
 * 用 [Shizuku.newProcess] 创建一个以 shell 用户（uid=2000，即 ADB 级）运行的
 * 子进程，再交给 [ProcessStreamFactory] 转成 [ToolStreamEvent] Flow。这样
 * `shell_execute` 在有 Shizuku 但无 Root 的设备上也能流式执行 `pm list packages`
 * / `am start` / `dumpsys` 等 ADB 级命令，而不必降级到只能访问 sandbox 的普通 shell。
 *
 * ## 权限检测
 *
 * 复用 [ShizukuCommandExecutor.isAvailable] / [hasPermission]，与
 * [PrivilegeDetector.detectShizuku] 保持一致，避免两套检测逻辑出现分歧。
 * 未授权时 [isAvailable] 返回 false，调用方（[ShellStreamSource]）据此回落到普通 shell。
 *
 * ## 本适配器不负责请求权限
 *
 * 请求权限需要 Activity 上下文 + 用户交互，属于 UI 层职责（见 `PermissionsScreen`
 * 的 `ShizukuPermissionCard`）。本适配器假设权限已就绪；未就绪时仅返回 false 让调用方降级。
 *
 * ## 取消语义
 *
 * 由 [ProcessStreamFactory] 统一处理：收集方取消时 `Process.destroy()` 被调用，
 * Shizuku 进程随之销毁。
 */
object ShizukuStreamAdapter {

    /**
     * Shizuku 服务是否可用且已授权。
     *
     * 委托给 [ShizukuCommandExecutor.hasPermission]，它内部已处理 binder 存活 +
     * v11 前后版本差异。
     */
    fun isAvailable(): Boolean = ShizukuCommandExecutor.hasPermission()

    /**
     * 流式执行 [command]。调用前应先检查 [isAvailable]。
     *
     * `Shizuku.newProcess(cmd, env, dir)` 等价于在 ADB shell 中执行 `sh -c <cmd>`，
     * 返回标准 [Process]，因此可无缝交给 [ProcessStreamFactory]。
     */
    fun executeStream(command: String): Flow<ToolStreamEvent> =
        ProcessStreamFactory.create(
            processBuilder = {
                Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            },
            via = "shizuku"
        )
}
