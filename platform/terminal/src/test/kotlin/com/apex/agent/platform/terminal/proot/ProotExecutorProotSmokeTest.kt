package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * P71: ProotExecutor 真实 proot 冒烟测试（host rootfs = "/"，GH runner 直跑 VM）。
 *
 * 验证链路（无 JNI，JVM ProcessBuilder）：
 *   PRootCommandBuilder（含 P71 的 -w 修正）
 *     → ProotExecutor.execute（G1 真实 pid + G4 env 白名单）
 *     → 真实 proot -r / -- /bin/sh -c …
 *
 * 自跳过（assumeTrue）：proot 未安装或 ptrace 受限的 runner。
 * CI 的 app-compile job 安装了 proot —— GH ubuntu-24.04 实测可跑
 *（PRootRuntimeIntegrationTest 同款 prootCanRun 预检）。
 *
 * 真机 forkpty→execv(proot) 全链路见 androidTest NativePtyArgvInstrumentationTest。
 */
class ProotExecutorProotSmokeTest {

    private class HostRootfsProvider : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = RootfsDescriptor(
            id = "host-root",
            distribution = LinuxDistribution.UNKNOWN,
            version = null,
            architecture = CpuArchitecture.X86_64,
            location = AbsolutePath("/"),
            sizeBytes = null,
            checksum = null,
            readOnly = false
        )

        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    private fun prootBinary(): File? =
        listOf("/usr/bin/proot", "/usr/local/bin/proot", "/bin/proot")
            .map { File(it) }
            .firstOrNull { it.exists() && it.canExecute() }

    private fun prootCanRun(): Boolean {
        val bin = prootBinary() ?: return false
        return try {
            val proc = ProcessBuilder(bin.absolutePath, "-r", "/", "--kill-on-exit", "--", "/bin/true")
                .redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun buildCommand(bin: File, guestCwd: String, command: List<String>): PRootCommand {
        val builder = PRootCommandBuilderImpl()
        val launch = PRootLaunchRequest(
            rootfs = runBlocking { HostRootfsProvider().current()!! },
            executable = command.first(),
            arguments = command.drop(1),
            workingDirectory = com.apex.agent.platform.terminal.workspace.WorkspacePath(guestCwd),
            environment = mapOf("P71_SMOKE" to "ok"),
            binds = emptyList(),
            fakeRoot = true,
            killOnExit = true
        )
        return builder.build(launch, AbsolutePath(bin.absolutePath), AbsolutePath("/"), AbsolutePath("/tmp"))
    }

    @Test
    fun `proot exec true exits zero with real pid`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = exec.execute(buildCommand(bin!!, "/root", listOf("/bin/true")))

        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue(result.pid > 0)
        assertFalse(result.timedOut)
    }

    @Test
    fun `proot echoes through rootfs with -E env visible in guest`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = exec.execute(
            buildCommand(bin!!, "/root", listOf("/bin/sh", "-c", "echo P71=\$P71_SMOKE cwd=\$(pwd)"))
        )

        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertEquals("P71=ok cwd=/root", result.stdout.trim())
    }

    @Test
    fun `proot passes nonzero exit code through`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = exec.execute(
            buildCommand(bin!!, "/root", listOf("/bin/sh", "-c", "exit 42"))
        )

        assertEquals(42, result.exitCode)
    }

    @Test
    fun `proot -w fix lands in guest workspace mapping`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)

        // 不执行 —— 仅验证 builder 输出的 -w 是 guest 路径（旧 bug：/tmp）
        val cmd = buildCommand(bin!!, "workspace:/sub", listOf("/bin/true"))
        val wIdx = cmd.arguments.indexOf("-w")
        assertTrue(wIdx >= 0)
        assertEquals("/workspace/sub", cmd.arguments[wIdx + 1])
    }

    /**
     * 启动延迟基准（PR #75 计划 §20："每次启动 PRoot 的成本"量化 —— P71 产出数据）。
     *
     * 输出 proot -r / /bin/true 的 5 次冷启动耗时 —— JVM 侧 fork+exec+ptrace
     * attach 的下界（真机数值 = 此值 + rootfs I/O + bash profile，见 androidTest 基准）。
     */
    @Test
    fun `startup latency benchmark produces data`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val samples = mutableListOf<Long>()
        repeat(5) {
            val r = exec.execute(buildCommand(bin!!, "/root", listOf("/bin/true")))
            assertEquals(0, r.exitCode)
            samples.add(r.durationMs)
        }
        println("═══ P71 proot startup benchmark (host /, /bin/true, ${samples.size} runs) ═══")
        println("samples(ms): $samples")
        println("min=${samples.min()}ms avg=${samples.average().toLong()}ms max=${samples.max()}ms")
        // 断言只做 sanity（不锁具体数值 —— 硬件相关），数据进 CI 日志供汇总
        assertTrue("single cold start must complete under 10s", samples.max()!! < 10_000)
    }
}
