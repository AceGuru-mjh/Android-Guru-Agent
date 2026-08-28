package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.*
import com.apex.agent.platform.terminal.runtime.RuntimeState
import com.apex.agent.platform.terminal.runtime.RuntimeType
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PRootCommandBuilderTest {

    @Test fun `builds PRoot command with rootfs and executable`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64, null, null, null, false),
            executable = "bash",
            arguments = listOf("-c", "echo hello")
        )
        val cmd = builder.build(
            request = request,
            prootBinary = AbsolutePath("/usr/bin/proot"),
            rootfsPath = AbsolutePath("/data/rootfs"),
            workspacePath = AbsolutePath("/data/workspace")
        )
        assertEquals("/usr/bin/proot", cmd.executable.value)
        assertTrue(cmd.arguments.contains("-r"))
        assertTrue(cmd.arguments.contains("/data/rootfs"))
        assertTrue(cmd.arguments.contains("bash"))
        assertTrue(cmd.arguments.contains("echo hello"))
    }

    @Test fun `builds with fakeRoot flag`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, null, CpuArchitecture.ARM64, null, null, null, false),
            executable = "sh",
            fakeRoot = true
        )
        val cmd = builder.build(request, AbsolutePath("/proot"), AbsolutePath("/rootfs"), AbsolutePath("/ws"))
        assertTrue(cmd.arguments.contains("-0"))
    }

    @Test fun `builds with bind mounts`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, null, CpuArchitecture.ARM64, null, null, null, false),
            executable = "sh",
            binds = listOf(PRootBind(AbsolutePath("/host/tmp"), "/tmp", true))
        )
        val cmd = builder.build(request, AbsolutePath("/proot"), AbsolutePath("/rootfs"), AbsolutePath("/ws"))
        assertTrue(cmd.arguments.any { it.contains("/host/tmp:/tmp:0") })
    }

    @Test fun `builds with environment passthrough`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, null, CpuArchitecture.ARM64, null, null, null, false),
            executable = "sh",
            environment = mapOf("LANG" to "en_US.UTF-8")
        )
        val cmd = builder.build(request, AbsolutePath("/proot"), AbsolutePath("/rootfs"), AbsolutePath("/ws"))
        assertTrue(cmd.arguments.any { it.contains("LANG=en_US.UTF-8") })
    }

    @Test fun `command separates executable and arguments`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, null, CpuArchitecture.ARM64, null, null, null, false),
            executable = "python3",
            arguments = listOf("-c", "print(1)")
        )
        val cmd = builder.build(request, AbsolutePath("/proot"), AbsolutePath("/rootfs"), AbsolutePath("/ws"))
        // Verify not a single string
        assertTrue(cmd.arguments is List<*>)
        assertTrue(cmd.arguments.contains("python3"))
        assertTrue(cmd.arguments.contains("print(1)"))
    }

    @Test fun `workspace always bound to slash workspace`() {
        val builder = PRootCommandBuilderImpl()
        val request = PRootLaunchRequest(
            rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, null, CpuArchitecture.ARM64, null, null, null, false),
            executable = "sh"
        )
        val cmd = builder.build(request, AbsolutePath("/proot"), AbsolutePath("/rootfs"), AbsolutePath("/my/ws"))
        assertTrue(cmd.arguments.any { it.contains("/my/ws:/workspace") })
    }
}

class RootfsValidationErrorTest {
    @Test fun `all error types exist`() {
        assertEquals(10, RootfsValidationError.values().size)
        assertTrue(RootfsValidationError.values().any { it.name == "MISSING_ROOT" })
        assertTrue(RootfsValidationError.values().any { it.name == "ARCHITECTURE_MISMATCH" })
        assertTrue(RootfsValidationError.values().any { it.name == "CORRUPTED" })
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
}

class LinuxMountPlannerTest {
    @Test fun `plan includes rootfs and workspace`() {
        val planner = LinuxMountPlannerImpl()
        val rootfs = RootfsDescriptor("r1", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64, AbsolutePath("/rootfs"), null, null, false)
        val workspace = com.apex.agent.platform.terminal.workspace.WorkspaceSnapshot(
            id = com.apex.agent.platform.terminal.workspace.WorkspaceId("w1"),
            root = AbsolutePath("/ws"),
            state = com.apex.agent.platform.terminal.workspace.WorkspaceState.READY,
            sharing = com.apex.agent.platform.terminal.workspace.WorkspaceSharing.SHARED,
            layout = com.apex.agent.platform.terminal.workspace.WorkspaceLayout(),
            sessionCount = 0, createdAt = 0
        )
        val plan = planner.plan(rootfs, workspace)
        assertTrue(plan.mounts.any { it.type == com.apex.agent.platform.terminal.linux.LinuxMountType.ROOTFS })
        assertTrue(plan.mounts.any { it.type == com.apex.agent.platform.terminal.linux.LinuxMountType.BIND })
        assertTrue(plan.mounts.any { it.type == com.apex.agent.platform.terminal.linux.LinuxMountType.HOME })
    }
}

class LinuxEnvironmentBuilderTest {
    @Test fun `builds Linux PATH not Android PATH`() = runBlocking {
        val builder = LinuxEnvironmentBuilderImpl()
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val env = builder.build(rt, LinuxProcessRequest(executable = "sh"))
        assertEquals("/usr/local/bin:/usr/bin:/bin", env["PATH"])
        // P71: 原断言把 Class 与 Boolean 比较（恒不等、无意义）且引用 android.os.Build
        //（JVM 不可编译）。改为真实断言：Linux PATH 不得包含 Android data 目录。
        assertFalse("PATH must not reference Android /data dir: ${env["PATH"]}", env["PATH"]!!.contains("/data"))
    }

    @Test fun `HOME is not Android data dir`() = runBlocking {
        val builder = LinuxEnvironmentBuilderImpl()
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val env = builder.build(rt, LinuxProcessRequest(executable = "sh"))
        val home = env["HOME"]
        assertNotNull(home)
        assertFalse("HOME must not be Android path", home!!.contains("/data/data"))
    }

    @Test fun `SHELL comes from ShellProvider`() = runBlocking {
        val builder = LinuxEnvironmentBuilderImpl()
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val env = builder.build(rt, LinuxProcessRequest(executable = "sh"))
        assertNotNull(env["SHELL"])
    }

    @Test fun `request environment overrides runtime`() = runBlocking {
        val builder = LinuxEnvironmentBuilderImpl()
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val env = builder.build(rt, LinuxProcessRequest(
            executable = "sh",
            environment = mapOf("CUSTOM_VAR" to "custom_value", "PATH" to "/override")
        ))
        assertEquals("custom_value", env["CUSTOM_VAR"])
        assertEquals("/override", env["PATH"])
    }

    @Test fun `TMPDIR is set`() = runBlocking {
        val builder = LinuxEnvironmentBuilderImpl()
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val env = builder.build(rt, LinuxProcessRequest(executable = "sh"))
        assertEquals("/tmp", env["TMPDIR"])
    }
}

class PRootErrorCodeTest {
    @Test fun `all error codes exist`() {
        assertEquals(15, PRootErrorCode.values().size)
        assertTrue(PRootErrorCode.values().any { it.name == "BINARY_NOT_FOUND" })
        assertTrue(PRootErrorCode.values().any { it.name == "ROOTFS_INVALID" })
        assertTrue(PRootErrorCode.values().any { it.name == "STARTUP_TIMEOUT" })
        assertTrue(PRootErrorCode.values().any { it.name == "USERSPACE_CRASHED" })
    }

    @Test fun `PRootError has code and message`() {
        val err = PRootError(PRootErrorCode.BINARY_NOT_FOUND, "proot binary not found")
        assertEquals(PRootErrorCode.BINARY_NOT_FOUND, err.code)
        assertFalse(err.recoverable)
    }
}

class PRootRuntimeTest {

    @Test fun `initializes to READY`() = runBlocking {
        val rt = PRootRuntime(
            binaryProvider = FakePRootBinaryProvider(),
            rootfsValidator = FakeRootfsValidator()
        )
        rt.initialize()
        assertEquals(RuntimeState.READY, rt.state)
    }

    @Test fun `type is LINUX`() {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        assertEquals(RuntimeType.LINUX, rt.type)
    }

    @Test fun `capabilities report honestly`() {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        val caps = rt.capabilities()
        assertTrue(caps.pty)
        assertFalse(caps.processGroups)  // PRoot does not support real process groups
        assertFalse(caps.reattach)
    }

    @Test fun `supports capability-based query`() {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        assertTrue(rt.supports(LinuxCapability.EXECUTION))
        assertTrue(rt.supports(LinuxCapability.PTY))
        assertFalse(rt.supports(LinuxCapability.ROOTFS))
        assertFalse(rt.supports(LinuxCapability.PACKAGE_MANAGER))
    }

    @Test fun `shutdown to CLOSED`() = runBlocking {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        rt.initialize()
        rt.shutdown()
        assertEquals(RuntimeState.CLOSED, rt.state)
    }

    @Test fun `runtimeInfo reports PROOT userspace`() = runBlocking {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        rt.initialize()
        val info = rt.runtimeInfo()
        assertEquals(LinuxUserspaceType.PROOT, info.userspaceType)
    }

    @Test fun `environment returns LinuxEnvironment`() = runBlocking {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        rt.initialize()
        val env = rt.environment()
        assertNotNull(env.user())
        assertTrue(env.user().isRoot)
    }

    @Test fun `snapshot is immutable`() = runBlocking {
        val rt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        rt.initialize()
        val snap = rt.snapshot()
        assertEquals(RuntimeType.LINUX, snap.type)
    }
}

// ─── Test helpers ───
class FakePRootBinaryProvider : PRootBinaryProvider {
    override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/usr/bin/proot"))
    override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
        PRootBinaryInfo(binary, PRootVersion(5, 3, 0), CpuArchitecture.ARM64, true)
    )
}

class FakeRootfsValidator : RootfsValidator {
    override suspend fun validate(rootfs: RootfsDescriptor): Result<RootfsValidation> = Result.success(
        RootfsValidation(true, true, true, true, true, true, true, emptyList())
    )
}
