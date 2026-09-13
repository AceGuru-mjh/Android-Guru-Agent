package com.apex.agent.platform.privilege.shizuku

import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

/**
 * Shizuku ADB 级进程通道（真实提权执行）。
 *
 * ## 为什么直接走 AIDL
 *
 * `rikka.shizuku.Shizuku.newProcess(...)` 在 Shizuku API 13.x 中是 private
 * （且标注 deprecated、计划在 API 14 移除），但底层 AIDL 方法
 * `moe.shizuku.server.IShizukuService.newProcess(cmd, env, dir)` 是稳定契约：
 * 客户端经 `Shizuku.getBinder()` 拿到服务端 binder，`asInterface` 后直接调用。
 * 服务端（`server-shared/.../Service.java#newProcess`）在 Shizuku 服务进程内
 * `Runtime.exec`，进程身份 = shell (uid=2000，ADB 级)，stdin/stdout/stderr 以
 * [android.os.ParcelFileDescriptor] 回传 —— 与 `adb shell` 等价。
 *
 * 注意：调用前须确保 binder 已收到且 app 已被授予 Shizuku 权限
 * （[ShizukuCommandExecutor.isAvailable] / [ShizukuCommandExecutor.hasPermission]）。
 * 服务端 `enforceCallingPermission("newProcess")` 会在未授权时抛
 * [SecurityException] —— 这是真实的权限边界，不做任何静默降级。
 */
object ShizukuProcessChannel {

    /**
     * 取得 Shizuku 服务端的 AIDL 接口。
     *
     * binder 未收到（Shizuku 未运行 / Provider 未初始化）或已死亡 → null。
     */
    fun service(): IShizukuService? {
        return try {
            val binder = Shizuku.getBinder() ?: return null
            if (!binder.pingBinder()) return null
            IShizukuService.Stub.asInterface(binder)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 以 ADB (uid=2000) 身份启动进程。
     *
     * @param argv 完整 argv（含 argv[0]），如 `["sh", "-c", "pm list packages"]`
     * @param env  环境变量（"K=V" 数组）；null = 继承 Shizuku 服务端环境
     * @param dir  工作目录；null = 服务端默认目录
     * @return 包装为 [Process] 的远端进程（流式读取/超时/销毁与本地进程同构）
     * @throws SecurityException 未授权（服务端 enforceCallingPermission 拒绝）
     * @throws IllegalStateException 服务端 exec 失败（如命令不存在）
     * @throws RuntimeException binder 死亡 / 通信失败
     */
    fun start(argv: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val svc = service()
            ?: throw IllegalStateException("Shizuku service is not available (binder not received or dead)")
        val remote: IRemoteProcess = svc.newProcess(argv, env, dir)
        return ShizukuProcessAdapter(remote)
    }
}

/**
 * [IRemoteProcess] → [Process] 适配器。
 *
 * 语义对照（照抄 Shizuku 官方 `ShizukuRemoteProcess` 的映射，后者构造器为
 * 包私有无法直接复用）：
 * - stdin/stdout/stderr = ParcelFileDescriptor 上的自动关闭流
 * - [waitFor] / [exitValue] / [destroy] 直接转发远端 binder 调用
 * - [destroy] 为幂等 best-effort（binder 死亡后远端进程由服务端按
 *   client token 自动回收 —— Shizuku 会在调用方死亡时杀掉它创建的进程）
 */
class ShizukuProcessAdapter(private val remote: IRemoteProcess) : Process() {

    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null
    private var stderr: InputStream? = null

    override fun getOutputStream(): OutputStream {
        if (stdin == null) {
            stdin = android.os.ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        }
        return stdin!!
    }

    override fun getInputStream(): InputStream {
        if (stdout == null) {
            stdout = android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        }
        return stdout!!
    }

    override fun getErrorStream(): InputStream {
        if (stderr == null) {
            stderr = android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        }
        return stderr!!
    }

    override fun waitFor(): Int = remote.waitFor()

    override fun exitValue(): Int = remote.exitValue()

    override fun destroy() {
        runCatching { remote.destroy() }
    }
}
