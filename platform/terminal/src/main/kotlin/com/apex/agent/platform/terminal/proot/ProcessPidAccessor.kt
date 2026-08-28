package com.apex.agent.platform.terminal.proot

/**
 * P71 G1: 读取 java.lang.Process 的真实宿主 pid。
 *
 * 为什么不用 `process.pid()`：那是 Java 9+ API，Android 的 java.lang.Process
 * 直到 API 34 才提供 —— 直接调用会让 compileSdk<34 的 Android 编译失败
 *（Unresolved reference 'pid'）。
 *
 * 策略（全部反射、零编译期依赖）：
 *  1. 反射方法 `pid()`：JVM 9+（CI 集成测试）与 Android 34+（现代真机）
 *  2. 反射字段 `pid`：旧 Android 的 ProcessBuilder$ProcessImpl 内部字段（best-effort，
 *     受 hidden-api 约束时抛异常被吞）
 *  3. 两者都失败 → -1（诚实降级：pid 仅用于诊断/快照/日志对账，-1 表示未知，
 *     绝不伪造 —— 这正是 G1 修复的要点：不再用 10000 起步的假计数器）
 */
internal object ProcessPidAccessor {

    fun pidOf(process: Process): Long {
        // 1) Java 9+ / Android 34+ 方法
        try {
            val m = Process::class.java.getMethod("pid")
            return (m.invoke(process) as Number).toLong()
        } catch (_: Exception) {
            // fall through
        }
        // 2) 旧 Android ProcessImpl 内部字段（best-effort）
        try {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            return (f.get(process) as Number).toLong()
        } catch (_: Exception) {
            // fall through
        }
        return -1L
    }
}
