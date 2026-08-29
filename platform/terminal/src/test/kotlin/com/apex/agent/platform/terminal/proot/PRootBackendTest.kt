package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P68 共享组件测试（T73 收编后仅覆盖 P71 LinuxPRootBackend 仍在使用的活代码：
 * PRootCommandBuilder / RootfsValidation 类型。旧 PRootRuntime / MountPlanner /
 * EnvironmentBuilder / 错误模型已随 P68 运行时栈删除 —— 其职责由 P71
 * LinuxPRootBackendTest + ProotExecutorTest 承接）。
 */
class PRootCommandBuilderTest {
    private fun rootfs() = RootfsDescriptor(
        id = "ubuntu-24.04-arm64", distribution = LinuxDistribution.UBUNTU,
        version = "24.04", architecture = CpuArchitecture.ARM64,
        location = AbsolutePath("/data/rootfs/ubuntu/versions/v1"),
        sizeBytes = 123L, checksum = "abc", readOnly = false
    )

    @Test fun `builds PRoot command with rootfs and executable`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/bash", listOf("-i")),
            prootBinary = AbsolutePath("/lib/libproot.so"),
            rootfsPath = AbsolutePath("/data/rootfs/v1"),
            workspacePath = AbsolutePath("/data/ws")
        )
        assertEquals("/lib/libproot.so", cmd.executable.value)
        assertEquals("-r", cmd.arguments[0])
        assertEquals("/data/rootfs/v1", cmd.arguments[1])
        // guest command lives after "--": executable then its arguments
        val dd = cmd.arguments.indexOf("--")
        assertTrue(dd > 0)
        assertEquals("/bin/bash", cmd.arguments[dd + 1])
        assertEquals("-i", cmd.arguments.last())
    }

    @Test fun `builds with fakeRoot flag`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/sh", fakeRoot = true),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w")
        )
        assertTrue(cmd.arguments.contains("-0"))
    }

    @Test fun `builds with bind mounts`() {
        val req = PRootLaunchRequest(
            rootfs(), "/bin/sh",
            binds = listOf(PRootBind(AbsolutePath("/host/cache"), "/root/.cache"))
        )
        val cmd = PRootCommandBuilderImpl().build(req, AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"))
        assertTrue(cmd.arguments.contains("/host/cache:/root/.cache"))
    }

    @Test fun `builds with environment passthrough`() {
        val req = PRootLaunchRequest(rootfs(), "/bin/sh", environment = mapOf("HOME" to "/root"))
        val cmd = PRootCommandBuilderImpl().build(req, AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"))
        val idx = cmd.arguments.indexOf("-E")
        assertTrue(idx > 0)
        assertEquals("HOME=/root", cmd.arguments[idx + 1])
    }

    @Test fun `command separates executable and arguments`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/bash", listOf("-c", "echo hi")),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w")
        )
        // proot is the executable; guest command lives after "--"
        val dd = cmd.arguments.indexOf("--")
        assertTrue(dd > 0)
        assertEquals(listOf("/bin/bash", "-c", "echo hi"), cmd.arguments.subList(dd + 1, cmd.arguments.size))
    }

    @Test fun `workspace always bound to slash workspace`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/sh"),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/data/ws")
        )
        assertTrue(cmd.arguments.contains("-b"))
        assertTrue(cmd.arguments.contains("/data/ws:/workspace"))
    }

    @Test fun `guest cwd maps workspace prefix to slash workspace`() {
        val builder = PRootCommandBuilderImpl()
        assertEquals("/workspace", builder.toGuestPath("workspace:/"))
        assertEquals("/workspace/foo", builder.toGuestPath("workspace:/foo"))
        assertEquals("/workspace/foo", builder.toGuestPath("workspace:foo"))
        assertEquals("/root", builder.toGuestPath("/root"))  // no prefix = guest absolute path
    }
}

class RootfsValidationErrorTest {
    @Test fun `all error types exist`() {
        // P68 contract surface still used by ProvisionedRootfsProvider fallback checks.
        assertEquals(10, RootfsValidationError.values().size)
    }

    @Test fun `RootfsValidation is immutable data class`() {
        val v = RootfsValidation(
            valid = true, architectureCompatible = true,
            hasRootDirectory = true, hasBin = true, hasEtc = true,
            hasUsr = true, hasHome = true, errors = emptyList()
        )
        assertTrue(v.valid)
        assertTrue(v.errors.isEmpty())
    }

    @Test fun `RootfsState covers lifecycle`() {
        // sanity: the descriptor state enum used by providers still exists
        assertTrue(RootfsState.values().contains(RootfsState.AVAILABLE))
    }
}
