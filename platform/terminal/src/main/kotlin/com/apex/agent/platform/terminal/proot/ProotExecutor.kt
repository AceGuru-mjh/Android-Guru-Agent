package com.apex.agent.platform.terminal.proot

/**
 * P71: ProotExecutor —— 无 PTY 的 proot 短命令执行器（ProcessBuilder + pipes）。
 *
 * 定位（PR #75 计划 §3.3）：P68 的 ProcessBuilder 路线从"运行时"降级为本执行器，
 * 专用于 rootfs 探针、apt（P76）、环境检测等非交互批处理，以及 CI/JVM 集成测试
 * （JVM 无 JNI .so，必须走 ProcessBuilder）。生产交互会话一律走 forkpty 路径
 * （LinuxPRootBackend → nativeCreateSessionArgv）。
 *
 * 对 P68 (PRootProcessProvider) 的两个修复：
 *  - G1 假 PID：`Process.pid()`（Java 9+）返回真实宿主 pid，替代 10000 起步的计数器 ——
 *    信号/快照/日志才能对上真实进程。
 *  - G4 host/guest env 分离：[hostEnv] 只含 proot 自身需要的变量
 *    （PROOT_TMP_DIR/PROOT_LOADER/LD_LIBRARY_PATH/PATH）；guest 环境变量只经
 *    argv 的 -E 传入，两套 env 永不混合。
 */
class ProotExecutor(
    /** proot 进程的宿主 env（来自 PRootHostEnvironment.hostEnv()；测试可注入任意 map）。 */
    private val hostEnv: () -> Map<String, String> = { emptyMap() }
) {

    /** 一次执行的完整结果。 */
    data class Execution(
        val pid: Long,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean = false
    )

    /**
     * 同步执行一个已构建好的 [PRootCommand]（含 proot 本体在内的完整 argv）。
     * stdout/stderr 分离捕获（redirectErrorStream=false）。
     *
     * @param timeoutMs 超时强杀（destroyForcibly）并以 timedOut=true 返回。
     */
    fun execute(command: PRootCommand, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Execution {
        val argv = listOf(command.executable.value) + command.arguments
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)
        // G4：宿主 env 严格白名单 —— pb.environment() 先清空再填 hostEnv，
        // 杜绝 Android/JVM 的任意宿主变量（HOME/TERM/ANDROID_*…）泄入 proot 进程。
        val pbEnv = pb.environment()
        pbEnv.clear()
        pbEnv.putAll(hostEnv())

        val start = System.currentTimeMillis()
        val proc = pb.start()

        // 并发排水防 pipe 满（stdout/stderr 同时活跃时单线程 readText 会死锁）。
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val stdoutThread = Thread {
            outBuf.append(runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }
        val stderrThread = Thread {
            errBuf.append(runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault(""))
        }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()

        var timedOut = false
        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            timedOut = true
        }
        stdoutThread.join(2000)
        stderrThread.join(2000)
        val exit = if (timedOut) -1 else proc.exitValue()

        return Execution(
            pid = proc.pid(), // G1：真实宿主 pid
            exitCode = exit,
            stdout = outBuf.toString(),
            stderr = errBuf.toString(),
            durationMs = System.currentTimeMillis() - start,
            timedOut = timedOut
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}
