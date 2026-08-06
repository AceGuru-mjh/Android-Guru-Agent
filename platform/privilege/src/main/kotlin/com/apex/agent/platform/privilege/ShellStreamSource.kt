package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Shell 流式执行源。
 *
 * 让 `shell_execute` 能逐行把 stdout / stderr 推到 UI，而不是等整个命令结束后
 * 一次性返回。这是工具输出流式化的核心落地。
 *
 * ## 权限链
 *
 * 按 [PrivilegeDetector.getPrivilegeLevel] 选择最高可用通道：
 *
 * 1. **Root** → `su -c <command>`（全能：/system、/data、mount、SELinux …）。
 * 2. **Shizuku** → `Shizuku.newProcess(...)`（ADB 级，uid=2000：pm/am/settings/
 *    dumpsys、/sdcard 读写 …）。由 [ShizukuStreamAdapter] 实现。
 * 3. **普通 Shell** → `sh -c <command>`（最弱：仅 sandbox）。
 *
 * 三者都通过 [ProcessStreamFactory] 转成统一的 [ToolStreamEvent] Flow —— 唯一
 * 区别只是「如何创建 Process」。
 *
 * ## stderr 处理
 *
 * stderr 行以 `[stderr] ` 前缀混入同一输出流（与 stdout 交错），让用户在工具
 * 执行期间就能看到错误信息。非零 exit 会被转成 [ToolStreamEvent.Error]，使
 * engine 把工具调用标记为失败。
 *
 * ## 取消语义
 *
 * 由 [ProcessStreamFactory] 统一处理：收集方取消（如 `abort()`）时，
 * `Process.destroy()` 被调用，立即杀死子进程，避免僵尸进程或持续输出。
 */
object ShellStreamSource {

    /**
     * 流式执行 [command]。返回的 Flow 在收集时启动进程，收集方取消时销毁进程。
     */
    fun executeStream(command: String): Flow<ToolStreamEvent> {
        return when (PrivilegeDetector.getPrivilegeLevel()) {
            PrivilegeLevel.ROOT -> ProcessStreamFactory.create(
                processBuilder = { Runtime.getRuntime().exec(arrayOf("su", "-c", command)) },
                via = "root"
            )
            PrivilegeLevel.SHIZUKU -> {
                // Shizuku 已授权时走 ADB 级流式；未授权时 isAvailable()=false，
                // 但 getPrivilegeLevel() 只在 detectShizuku()=true 时返回 SHIZUKU，
                // 所以走到这里说明 Shizuku binder 活且已授权，可直接使用。
                ShizukuStreamAdapter.executeStream(command)
            }
            PrivilegeLevel.NORMAL_SHELL -> ProcessStreamFactory.create(
                processBuilder = { Runtime.getRuntime().exec(arrayOf("sh", "-c", command)) },
                via = "shell"
            )
        }
    }
}
