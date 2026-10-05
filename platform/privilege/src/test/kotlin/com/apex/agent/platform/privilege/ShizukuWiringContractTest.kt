package com.apex.agent.platform.privilege

import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #211 Shizuku 接线契约锁 —— 无 Root 设备的引导链不再终结于 stub 错误。
 *
 * DefaultPrivilegeManager.executeViaShizuku / executeViaShizukuInput 现委托
 * [ShizukuCommandExecutor]（真实 IShizukuService.newProcess AIDL，uid=2000，
 * 真机上等价 `adb shell`）。DefaultPrivilegeManager 本身绑定 Android 框架
 * （Context / 无障碍服务），JVM 单测无法构造 —— 本类锁定**委托目标**在 JVM
 * 上可验证的失败契约，即 #211 修复后用户可见的语义：
 *
 * 1. Shizuku 不可用 → [ShizukuCommandExecutor.execute] 返回结构化诚实失败
 *    （"Shizuku is not running"），而非旧 stub 文案 "not yet implemented"，
 *    也绝不回退本地 app-shell 执行命令再冒充 Shizuku 输出（降级决策归调用方）；
 * 2. 探测入口（isAvailable / hasPermission —— DefaultPrivilegeManager
 *    .checkShizuku 与 PrivilegeDetector.detectShizuku 共用）在无 binder
 *    环境静默返回 false，不抛异常炸穿调用链。
 *
 * 环境语义：JVM 测试环境天然无 Shizuku binder（Shizuku.pingBinder() 返回
 * false 或抛错均被捕获），两分支汇合到同一确定性结果 —— 与
 * PrivilegeDetectorAuditTest 在 CI 上的既有行为一致。
 */
class ShizukuWiringContractTest {

    @Test
    fun `Shizuku 不可用时 execute 返回诚实失败而非 stub 文案`() = runBlocking {
        val result = ShizukuCommandExecutor.execute("echo shizuku-wiring-probe", timeoutMs = 2000)

        assertFalse("不可用必须如实失败", result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(
            "应是执行器的结构化不可用错误（旧 #211 stub 文案不得回归）: ${result.output}",
            result.output.contains("Shizuku is not running")
        )
        // 绝不降级伪装：不得用本地 shell 执行命令再冒充 Shizuku 输出
        assertFalse(
            "不得回退本地 app-shell 冒充 Shizuku（echo 探测串不应出现）: ${result.output}",
            result.output.contains("shizuku-wiring-probe")
        )
    }

    @Test
    fun `探测入口在无 binder 环境静默返回 false`() {
        // DefaultPrivilegeManager.checkShizuku / PrivilegeDetector.detectShizuku
        // 共用的底层探测契约：未安装/未启动 Shizuku 时返回 false 而非抛异常。
        assertFalse(ShizukuCommandExecutor.isAvailable())
        assertFalse(ShizukuCommandExecutor.hasPermission())
    }
}
