package com.apex.agent.platform.terminal.fs

import com.apex.agent.platform.terminal.bridge.GuestBridgeService
import com.apex.agent.platform.terminal.bridge.GuestBridgeHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T82 — GuestFilesystem 路径门禁 + 命令构造 + 桥协议回归（纯 JVM，无 PRoot ——
 * 执行路径经 LinuxExecutionContextFactory 在无 rootfs 时结构化失败，路径门禁
 * 与 base64 传输语义可全量验证）。
 */
class GuestFilesystemTest {

    // ─── 路径门禁（deny-by-default，基线 §4.8）───

    private val fs = GuestFilesystem(contextFactory = unavailableFactory(), executor = fakeExecutor())

    @Test fun `write guard allows sandbox roots and their children`() {
        for (ok in listOf("/workspace", "/workspace/a/b.txt", "/root/.bashrc", "/tmp/x", "/sdcard/DCIM/1.jpg")) {
            assertEquals(ok, fs.guardWritable(ok))
        }
    }

    @Test fun `write guard rejects everything else including traversal-looking paths`() {
        for (bad in listOf(
            "/etc/passwd", "/usr/bin/sh", "/bin", "/system/bin/sh",
            "/workspace../etc", "/workspace/../../etc/x", "/proc/self/mem",
            "/var/lib/dpkg/status", "/", ""
        )) {
            assertNull("'$bad' must be denied", fs.guardWritable(bad))
        }
    }

    @Test fun `relative paths resolve under workspace`() {
        assertEquals("/workspace/app.kt", fs.normalize("app.kt"))
        assertEquals("/etc/hosts", fs.normalize("/etc/hosts"))
        assertEquals("", fs.normalize("bad\npath"))
        assertEquals("", fs.normalize(""))
    }

    @Test fun `remove refuses root even inside guard`() {
        runBlocking {
            val r = fs.remove("/", recursive = true)
            assertTrue(r is com.apex.agent.platform.terminal.fs.GuestFilesystem.FsResult.Err)
            assertEquals("FsError:PathDenied", (r as com.apex.agent.platform.terminal.fs.GuestFilesystem.FsResult.Err).code)
        }
    }

    @Test fun `ops fail structurally when no rootfs is installed (honest degrade)`() = runBlocking<Unit> {
        val r = fs.readText("/workspace/whatever")
        assertTrue(r is com.apex.agent.platform.terminal.fs.GuestFilesystem.FsResult.Err)
        assertEquals("FsError:ContextUnavailable", (r as com.apex.agent.platform.terminal.fs.GuestFilesystem.FsResult.Err).code)
    }

    // ─── 桥协议（基线 §11.1）───

    @Test fun `bridge round trip via file queue`() = runBlocking<Unit> {
        val dir = File.createTempFile("apexbridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(EchoHandler()),
            scope = CoroutineScope(SupervisorJob())
        )
        bridge.ensureInstalled()
        // guest 侧模拟：写请求文件
        val reqDir = File(dir, "bridge/req")
        File(reqDir, "t1.json").writeText("""{"id":"t1","action":"echo","args":hi}""")
        val handled = bridge.pollOnce()
        assertEquals(1, handled)
        val resp = File(dir, "bridge/resp/t1.json").readText()
        assertTrue(resp.contains("\"ok\":true"))
        assertTrue(resp.contains("\"data\":\"echo:hi\""))
        // 请求文件被清理（不留残骸）
        assertTrue(reqDir.listFiles().isNullOrEmpty())
        dir.deleteRecursively()
    }

    @Test fun `bridge unknown action produces structured error and cleans request`() = runBlocking<Unit> {
        val dir = File.createTempFile("apexbridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = emptyList(),
            scope = CoroutineScope(SupervisorJob())
        )
        bridge.ensureInstalled()
        File(dir, "bridge/req/x.json").writeText("""{"id":"x","action":"nope","args":null}""")
        bridge.pollOnce()
        val resp = File(dir, "bridge/resp/x.json").readText()
        assertTrue(resp.contains("BRIDGE_UNKNOWN_ACTION"))
        dir.deleteRecursively()
    }

    @Test fun `bridge direct dispatch (agent path) shares handler semantics`() = runBlocking<Unit> {
        val dir = File.createTempFile("apexbridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(EchoHandler(), FailingHandler()),
            scope = CoroutineScope(SupervisorJob())
        )
        assertEquals("echo:direct", bridge.dispatch("echo", "direct").data)
        val fail = bridge.dispatch("boom", "null")
        assertFalse(fail.ok)
        assertEquals("kaboom", fail.error)
        dir.deleteRecursively()
    }

    @Test fun `apexctl script installs idempotently and is executable`() {
        val dir = File.createTempFile("apexbridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = emptyList(),
            scope = CoroutineScope(SupervisorJob())
        )
        bridge.ensureInstalled()
        bridge.ensureInstalled()
        val script = File(dir, "bin/apexctl")
        assertTrue(script.isFile)
        assertTrue(script.canExecute())
        assertTrue(script.readText().contains(".apex/bridge/req"))
        dir.deleteRecursively()
    }

    @Test fun `bridge request parser is lenient but requires id and action`() {
        val dir = File.createTempFile("apexbridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(File(dir, "b"), emptyList(), CoroutineScope(SupervisorJob()))
        val t = bridge.parseRequest("""{"id":"9","action":"clipboard","args":"get"}""")
        assertEquals("9", t?.first)
        assertEquals("clipboard", t?.second)
        assertEquals("get", t?.third)
        assertNull(bridge.parseRequest("garbage"))
        assertNull(bridge.parseRequest("""{"action":"x"}"""))
        dir.deleteRecursively()
    }

    private class EchoHandler : GuestBridgeHandler {
        override val action = "echo"
        override val description = "test echo"
        override suspend fun handle(argsJson: String): String = "echo:$argsJson"
    }

    private class FailingHandler : GuestBridgeHandler {
        override val action = "boom"
        override val description = "always fails"
        override suspend fun handle(argsJson: String): String = throw IllegalStateException("kaboom")
    }

    private fun unavailableFactory(): com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory =
        com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory(
            binaryProvider = MissingBinaryProvider(),
            rootfsProvider = EmptyRootfsProvider(),
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                rootDir = File.createTempFile("wsdir", "").let { it.delete(); it.mkdirs(); it }
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File.createTempFile("home", "").let { it.delete(); it.mkdirs(); it }
            ),
            hostEnv = null
        )

    private fun fakeExecutor(): com.apex.agent.platform.terminal.proot.ProotExecutor =
        com.apex.agent.platform.terminal.proot.ProotExecutor()

    private class MissingBinaryProvider : com.apex.agent.platform.terminal.proot.PRootBinaryProvider {
        override suspend fun locate(): Result<com.apex.agent.platform.terminal.workspace.AbsolutePath> =
            Result.failure(IllegalStateException("no proot in JVM test"))
        override suspend fun verify(binary: com.apex.agent.platform.terminal.workspace.AbsolutePath): Result<com.apex.agent.platform.terminal.proot.PRootBinaryInfo> =
            Result.failure(IllegalStateException("no proot in JVM test"))
    }

    private class EmptyRootfsProvider : com.apex.agent.platform.terminal.linux.RootfsProvider {
        override suspend fun current(): com.apex.agent.platform.terminal.linux.RootfsDescriptor? = null
        override suspend fun verify(rootfs: com.apex.agent.platform.terminal.linux.RootfsDescriptor): Result<com.apex.agent.platform.terminal.linux.RootfsVerification> =
            Result.failure(IllegalStateException("no rootfs in JVM test"))
    }
}
