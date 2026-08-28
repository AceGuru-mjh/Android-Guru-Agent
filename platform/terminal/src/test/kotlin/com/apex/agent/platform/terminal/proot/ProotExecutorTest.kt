package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.workspace.AbsolutePath
import org.junit.Assert.*
import org.junit.Test

/**
 * P71: ProotExecutor 单元测试 —— G1（真实宿主 pid）/ G4（host env 白名单）。
 *
 * 用 /bin/sh 直接执行（不依赖 proot 安装），JVM 与 CI 全环境可跑。
 * 真实 proot 冒烟见 [ProotExecutorProotSmokeTest]。
 */
class ProotExecutorTest {

    private fun shCommand(vararg args: String) =
        PRootCommand(executable = AbsolutePath("/bin/sh"), arguments = args.toList())

    @Test
    fun `executes command and captures stdout stderr exit code`() {
        val exec = ProotExecutor(hostEnv = { mapOf("PATH" to "/usr/bin:/bin") })
        val result = exec.execute(shCommand("-c", "echo out; echo err 1>&2; exit 7"))

        assertEquals(7, result.exitCode)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
        assertFalse(result.timedOut)
    }

    @Test
    fun `G1 pid is the real host process pid`() {
        val exec = ProotExecutor()
        // 旧实现的假 pid 从 10001 起步；真实 pid 由内核分配，与 /proc 对得上。
        val result = exec.execute(shCommand("-c", "echo $$"))
        assertTrue("pid must be positive", result.pid > 0)
        // $$ 是 sh 自身的 pid —— 必须与 Process.pid() 报告的一致（G1 契约）
        assertEquals("child-reported pid must match executor-reported pid", result.pid, result.stdout.trim().toLong())
    }

    @Test
    fun `G4 host env is exactly the whitelist - no inherited variables`() {
        val exec = ProotExecutor(hostEnv = { mapOf("PROOT_TMP_DIR" to "/tmp/x", "PATH" to "/usr/bin:/bin") })
        // fork 的子进程继承 ProcessBuilder env（已 clear + hostEnv）——
        // 若有泄漏，env 中会出现宿主任意变量（如本测试进程的 HOME/USER）。
        // 注意用 "," 分隔（"|" 会被 shell 解释为管道）。
        val result = exec.execute(
            shCommand("-c", "echo \$PROOT_TMP_DIR,\$HOME,\$USER")
        )
        val parts = result.stdout.trim().split(",")
        assertEquals("PROOT_TMP_DIR must be passed through host env", "/tmp/x", parts[0])
        assertEquals("HOME must NOT be inherited (env cleared)", "", parts[1])
        assertEquals("USER must NOT be inherited (env cleared)", "", parts[2])
    }

    @Test
    fun `timeout kills the process and reports timedOut`() {
        val exec = ProotExecutor()
        val result = exec.execute(shCommand("-c", "sleep 30"), timeoutMs = 300)

        assertTrue(result.timedOut)
        assertTrue("duration should reflect early kill (~300ms, not 30s)", result.durationMs < 5000)
    }

    @Test
    fun `missing executable throws IOException for caller to handle`() {
        val exec = ProotExecutor()
        try {
            exec.execute(PRootCommand(executable = AbsolutePath("/nonexistent/binary"), arguments = emptyList()))
            fail("expected IOException from ProcessBuilder.start()")
        } catch (e: java.io.IOException) {
            // 预期：Cannot run program —— 启动失败语义由调用方决定
        }
    }
}
