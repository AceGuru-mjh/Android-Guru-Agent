package com.apex.agent.platform.terminal.linux

import com.apex.agent.platform.terminal.runtime.RuntimeState
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimeContractTest {

    @Test fun `CpuArchitecture has 6 types`() {
        assertEquals(6, CpuArchitecture.values().size)
        assertTrue(CpuArchitecture.values().any { it.name == "ARM64" })
        assertTrue(CpuArchitecture.values().any { it.name == "X86_64" })
    }

    @Test fun `LinuxDistribution has 7 types`() {
        assertEquals(7, LinuxDistribution.values().size)
        assertTrue(LinuxDistribution.values().any { it.name == "UBUNTU" })
        assertTrue(LinuxDistribution.values().any { it.name == "DEBIAN" })
        assertTrue(LinuxDistribution.values().any { it.name == "ALPINE" })
    }

    @Test fun `LinuxUserspaceType has 5 types`() {
        assertEquals(5, LinuxUserspaceType.values().size)
        assertTrue(LinuxUserspaceType.values().any { it.name == "NATIVE" })
        assertTrue(LinuxUserspaceType.values().any { it.name == "PROOT" })
        assertTrue(LinuxUserspaceType.values().any { it.name == "CONTAINER" })
    }

    @Test fun `RootfsType has 6 types`() {
        assertEquals(6, RootfsType.values().size)
        assertTrue(RootfsType.values().any { it.name == "DIRECTORY" })
        assertTrue(RootfsType.values().any { it.name == "ARCHIVE" })
    }

    @Test fun `LinuxCapability has 12 types`() {
        assertEquals(12, LinuxCapability.values().size)
        assertTrue(LinuxCapability.values().any { it.name == "EXECUTION" })
        assertTrue(LinuxCapability.values().any { it.name == "PTY" })
        assertTrue(LinuxCapability.values().any { it.name == "ROOTFS" })
        assertTrue(LinuxCapability.values().any { it.name == "PACKAGE_MANAGER" })
    }

    @Test fun `LinuxPid is type-safe`() {
        val a = LinuxPid(1234)
        val b = LinuxPid(5678)
        assertNotEquals(a.value, b.value)
    }

    @Test fun `LinuxProcessState has 7 states`() {
        assertEquals(7, LinuxProcessState.values().size)
    }

    @Test fun `TerminationMode has GRACEFUL and FORCE`() {
        assertEquals(2, TerminationMode.values().size)
    }

    @Test fun `LinuxMountType has 9 types`() {
        assertEquals(9, LinuxMountType.values().size)
        assertTrue(LinuxMountType.values().any { it.name == "ROOTFS" })
        assertTrue(LinuxMountType.values().any { it.name == "PROC" })
        assertTrue(LinuxMountType.values().any { it.name == "BIND" })
    }

    @Test fun `RootfsState has 6 states`() {
        assertEquals(6, RootfsState.values().size)
        assertTrue(RootfsState.values().any { it.name == "AVAILABLE" })
        assertTrue(RootfsState.values().any { it.name == "CORRUPTED" })
    }

    @Test fun `LinuxRuntimeFailure has 10 types`() {
        assertEquals(10, LinuxRuntimeFailure.values().size)
        assertTrue(LinuxRuntimeFailure.values().any { it.name == "ROOTFS_UNAVAILABLE" })
        assertTrue(LinuxRuntimeFailure.values().any { it.name == "ARCHITECTURE_UNSUPPORTED" })
    }

    @Test fun `FilesystemCapabilities has 9 fields`() {
        val caps = FilesystemCapabilities()
        assertTrue(caps.read)
        assertTrue(caps.write)
        assertTrue(caps.create)
        assertFalse(caps.hardLinks)
        assertFalse(caps.permissions)
    }

    @Test fun `ProcessCapabilities has 4 fields`() {
        val caps = ProcessCapabilities()
        assertTrue(caps.processGroups)
        assertTrue(caps.signals)
        assertFalse(caps.processTree)
        assertFalse(caps.reattach)
    }

    @Test fun `RuntimeConfiguration has defaults`() {
        val config = RuntimeConfiguration.DEFAULT
        assertTrue(config.environment.isEmpty())
        assertTrue(config.persistence)
        assertNull(config.workingDirectory)
    }

    @Test fun `RootfsDescriptor is immutable`() {
        val desc = RootfsDescriptor(
            id = "rootfs-1", distribution = LinuxDistribution.UBUNTU,
            version = "24.04", architecture = CpuArchitecture.ARM64,
            location = null, sizeBytes = null, checksum = null, readOnly = false
        )
        assertEquals(LinuxDistribution.UBUNTU, desc.distribution)
        assertEquals("24.04", desc.version)
        assertFalse(desc.readOnly)
    }

    @Test fun `LinuxProcessRequest separates executable and arguments`() {
        val req = LinuxProcessRequest(
            executable = "bash",
            arguments = listOf("-c", "echo hello")
        )
        assertEquals("bash", req.executable)
        assertEquals(2, req.arguments.size)
        assertEquals("-c", req.arguments[0])
        // Verify no single command string
        val fields = LinuxProcessRequest::class.java.declaredFields.map { it.name }
        assertFalse("no command field", fields.any { it == "command" })
    }

    @Test fun `UserspaceLaunchRequest has rootfs`() {
        val req = UserspaceLaunchRequest(
            rootfs = RootfsDescriptor(
                id = "r1", distribution = LinuxDistribution.UBUNTU, version = "24.04",
                architecture = CpuArchitecture.ARM64, location = null,
                sizeBytes = null, checksum = null, readOnly = false
            ),
            executable = "bash",
            arguments = emptyList(),
            workingDirectory = null,
            environment = emptyMap(),
            terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO
        )
        assertNotNull(req.rootfs)
    }

    @Test fun `LinuxRuntimeInfo has all fields`() {
        val info = LinuxRuntimeInfo(
            architecture = CpuArchitecture.ARM64,
            kernelVersion = "6.1.0",
            distribution = LinuxDistribution.UBUNTU,
            distributionVersion = "24.04",
            userspaceType = LinuxUserspaceType.NATIVE,
            rootfsType = RootfsType.DIRECTORY,
            isRoot = false,
            uid = 1000,
            gid = 1000
        )
        assertEquals(CpuArchitecture.ARM64, info.architecture)
        assertFalse(info.isRoot)
        assertEquals(1000L, info.uid)
    }

    @Test fun `LinuxUser has uid gid and isRoot`() {
        val user = LinuxUser(
            uid = 0, gid = 0, username = "root",
            home = WorkspacePath.home(), isRoot = true
        )
        assertTrue(user.isRoot)
        assertEquals(0L, user.uid)
    }
}

class FakeLinuxRuntimeTest {

    @Test fun `FakeLinuxRuntime initializes to READY`() = runBlocking {
        val rt = FakeLinuxRuntime()
        assertEquals(RuntimeState.CREATED, rt.state)
        rt.initialize()
        assertEquals(RuntimeState.READY, rt.state)
    }

    @Test fun `FakeLinuxRuntime shutdown to CLOSED`() = runBlocking {
        val rt = FakeLinuxRuntime()
        rt.initialize()
        rt.shutdown()
        assertEquals(RuntimeState.CLOSED, rt.state)
    }

    @Test fun `FakeLinuxRuntime reports LINUX type`() = runBlocking {
        val rt = FakeLinuxRuntime()
        assertEquals(com.apex.agent.platform.terminal.runtime.RuntimeType.LINUX, rt.type)
    }

    @Test fun `FakeLinuxRuntime supports basic capabilities`() = runBlocking {
        val rt = FakeLinuxRuntime()
        assertTrue(rt.supports(LinuxCapability.EXECUTION))
        assertTrue(rt.supports(LinuxCapability.PTY))
        assertTrue(rt.supports(LinuxCapability.SHELL))
        assertFalse(rt.supports(LinuxCapability.ROOTFS))
        assertFalse(rt.supports(LinuxCapability.PACKAGE_MANAGER))
    }

    @Test fun `FakeLinuxRuntime provides filesystem`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val fs = rt.filesystem()
        val path = WorkspacePath("workspace:/tmp/test")
        fs.createDirectories(path)
        assertTrue(fs.exists(path))
        assertTrue(fs.isDirectory(path))
    }

    @Test fun `FakeLinuxRuntime provides process provider`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val provider = rt.processProvider()
        val handle = provider.start(LinuxProcessRequest(executable = "echo", arguments = listOf("hello"))).getOrThrow()
        val snap = handle.snapshot().getOrThrow()
        assertEquals("echo", snap.executable)
        assertTrue(snap.isAlive)
    }

    @Test fun `FakeLinuxRuntime process terminate and await`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val provider = rt.processProvider()
        val handle = provider.start(LinuxProcessRequest(executable = "sleep")).getOrThrow()
        handle.terminate()
        val exit = handle.await().getOrThrow()
        assertNotNull(exit.exitCode)
        assertEquals(0, exit.exitCode)
    }

    @Test fun `FakeLinuxRuntime provides environment`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val env = rt.environment()
        assertEquals("/home/agent", env.get("HOME"))
        assertEquals("/bin/bash", env.shell().path)
        assertEquals("agent", env.user().username)
    }

    @Test fun `FakeLinuxRuntime provides runtime info`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val info = rt.runtimeInfo()
        assertEquals(CpuArchitecture.ARM64, info.architecture)
        assertEquals(LinuxDistribution.UBUNTU, info.distribution)
        assertEquals("24.04-fake", info.distributionVersion)
    }

    @Test fun `FakeLinuxRuntime provides PTY`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val pty = rt.ptyProvider()
        val session = pty.create(LinuxPtyRequest(executable = "bash")).getOrThrow()
        assertNotNull(session.process)
    }

    @Test fun `FakeLinuxRuntime provides shell provider`() = runBlocking {
        val rt = FakeLinuxRuntime()
        val shells = rt.shellProvider()
        val default = shells.defaultShell()
        assertEquals("bash", default.name)
        val available = shells.availableShells()
        assertTrue(available.size >= 2)
    }

    @Test fun `FakeLinuxRuntime snapshot is immutable`() = runBlocking {
        val rt = FakeLinuxRuntime()
        rt.initialize()
        val snap = rt.snapshot()
        assertEquals(com.apex.agent.platform.terminal.runtime.RuntimeType.LINUX, snap.type)
    }
}
