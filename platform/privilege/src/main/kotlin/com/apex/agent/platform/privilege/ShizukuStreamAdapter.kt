package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import com.apex.agent.platform.privilege.shizuku.ShizukuProcessChannel
import kotlinx.coroutines.flow.Flow

/**
 * Shizuku 流式执行适配器（真实 ADB 级提权）。
 *
 * ## 执行路径
 *
 * 经 [ShizukuProcessChannel.start] 调用 `IShizukuService.newProcess`
 * AIDL —— 命令在 Shizuku 服务端进程（uid=2000，ADB 级）真实执行，
 * stdout/stderr 以 ParcelFileDescriptor 回流本进程，逐行转成
 * [ToolStreamEvent]。`via="shizuku"` 现在名符其实。
 *
 * Shizuku 官方 `Shizuku.newProcess` Java 包装在 API 13.x 为 private 且
 * deprecated（计划 API 14 移除），因此这里直接调用其底层 AIDL 契约
 * （`moe.shizuku.server.IShizukuService`，经 `api` 构件传递依赖暴露）。
 *
 * ## 权限检测
 *
 * 复用 [ShizukuCommandExecutor.hasPermission]，与
 * [PrivilegeDetector.detectShizuku] 保持一致。未授权时返回 false，
 * [ShellStreamSource] 据此跳过本适配器。
 *
 * ## 失败语义
 *
 * AIDL 启动失败（未授权 / binder 死亡）时 [ProcessStreamFactory] 会把
 * 异常转成 [ToolStreamEvent.Error] —— 诚实报错，不回退到本地 shell 冒充。
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
     * 经 [ShizukuProcessChannel]（IShizukuService.newProcess AIDL）以
     * uid=2000 真实启动远端进程，其余读取/取消/退出码语义与本地进程一致
     * （[ProcessStreamFactory] 统一处理）。
     */
    fun executeStream(command: String): Flow<ToolStreamEvent> =
        ProcessStreamFactory.create(
            processBuilder = {
                ShizukuProcessChannel.start(arrayOf("sh", "-c", command))
            },
            via = "shizuku"
        )
}
