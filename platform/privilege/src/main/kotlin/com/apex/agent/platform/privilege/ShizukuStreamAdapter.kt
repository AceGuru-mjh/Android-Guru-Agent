package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.flow.Flow

/**
 * Shizuku 流式执行适配器。
 *
 * ## 当前实现（与既有 ShizukuCommandExecutor 行为一致）
 *
 * Shizuku 13.x 的 `rikka.shizuku.Shizuku.newProcess(...)` 不再是 public API
 * （CI 报 `it is private in 'rikka/shizuku/Shizuku'`）。项目既有的
 * [ShizukuCommandExecutor.executeBlocking] 在 Shizuku 通道下同样回退到普通
 * `Runtime.exec("sh","-c",cmd)` —— 即 Shizuku 仅作为「binder 存活 + 已授权」的
 * 信号，真正的提权执行由 PrivilegeDetector 链路在 Root 通道完成。
 *
 * 因此本适配器同样使用 `sh -c <command>`，保证流式体验立即可用、编译通过。
 * via 标记为 `"shizuku"` 以区分，但 **当前 Shizuku 通道与普通 shell 的实际权限
 * 相同**（都只能访问 sandbox）。
 *
 * ## 后续（TODO）
 *
 * 真正的 ADB 级流式执行需要通过 Shizuku 的 `IProcessService` / `ShizukuBinderWrapper`
 * 包装 `android.os.IProcessService` 或使用 `Shizuku.newProcess` 的反射替代，这属于
 * 单独的提权增强 PR。届时只需替换 [executeStream] 里的 processBuilder，其余
 * ProcessStreamFactory / UI 链路无需改动。
 *
 * ## 权限检测
 *
 * 复用 [ShizukuCommandExecutor.hasPermission]，与
 * [PrivilegeDetector.detectShizuku] 保持一致。未授权时返回 false，
 * [ShellStreamSource] 据此跳过本适配器。
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
     * 当前用 `sh -c` 走 [ProcessStreamFactory]；via="shizuku" 仅作标记。
     * 提权增强见类文档 TODO。
     */
    fun executeStream(command: String): Flow<ToolStreamEvent> =
        ProcessStreamFactory.create(
            processBuilder = {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            },
            via = "shizuku"
        )
}
