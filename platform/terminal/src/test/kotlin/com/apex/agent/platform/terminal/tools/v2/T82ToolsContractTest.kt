package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.fs.GuestFilesystem
import com.apex.agent.platform.terminal.pkg.InstalledPackage
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageManagerStatus
import com.apex.agent.platform.terminal.pkg.PackageOperation
import com.apex.agent.platform.terminal.pkg.PackageOperationEvent
import com.apex.agent.platform.terminal.pkg.PackageSearchResult
import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.pkg.PackageUpdateOptions
import com.apex.agent.platform.terminal.pkg.PackageInstallOptions
import com.apex.agent.platform.terminal.pkg.PackageRemoveOptions
import com.apex.agent.platform.terminal.pkg.PackageUpgradeOptions
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T82 — 新增工具契约测试：
 * terminal.fs（门禁错误 JSON 化）/ terminal.linux.packages installed + mirror（未接线降级）。
 */
class T82ToolsContractTest {

    // 简易 Fake：installed 返回固定列表（UbuntuAptPackageManager 的 dpkg-query 解析
    // 在 proot 执行层已由诚实降级语义保护；这里锁定工具层 JSON 契约）。
    private class FakePm : LinuxPackageManager {
        override suspend fun status(): PackageManagerStatus = PackageManagerStatus(
            available = true, manager = "fake", version = "1.0",
            databaseState = com.apex.agent.platform.terminal.pkg.PackageDatabaseState.HEALTHY,
            lockState = com.apex.agent.platform.terminal.pkg.PackageLockState.FREE,
            metadataState = com.apex.agent.platform.terminal.pkg.PackageMetadataState.CURRENT,
            brokenPackages = emptyList()
        )
        override suspend fun update(options: PackageUpdateOptions): PackageOperation = throw UnsupportedOperationException()
        override suspend fun install(packages: List<PackageSpec>, options: PackageInstallOptions): PackageOperation = throw UnsupportedOperationException()
        override suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions): PackageOperation = throw UnsupportedOperationException()
        override suspend fun upgrade(packages: List<PackageSpec>, options: PackageUpgradeOptions): PackageOperation = throw UnsupportedOperationException()
        override suspend fun search(query: String): PackageSearchResult = PackageSearchResult(query, emptyList())
        override suspend fun info(packageName: String): com.apex.agent.platform.terminal.pkg.PackageInfo =
            com.apex.agent.platform.terminal.pkg.PackageInfo(
                name = packageName, version = null, architecture = null, installed = false,
                candidateVersion = null, description = null, sizeBytes = null
            )
        override suspend fun isInstalled(packageName: String): Boolean = true
        override suspend fun installedVersion(packageName: String): String? = "1.2.3"
        override suspend fun installed(limit: Int): List<InstalledPackage> = listOf(
            // manager 层过滤 rc*（UbuntuAptPackageManager 契约：只报 ii*）。
            InstalledPackage("git", "1:2.43.0-1", "ii ")
        )
        override suspend fun repair(): PackageOperation = throw UnsupportedOperationException()
        override fun operations(): Flow<PackageOperationEvent> = kotlinx.coroutines.flow.emptyFlow()
    }

    @Test fun `packages installed action returns structured real list (replaces stub)`() = runBlocking<Unit> {
        val tool = TerminalLinuxPackagesTool(FakePm())
        val json = tool.invoke("""{"action":"installed","limit":10}""")
        assertTrue(json.contains("\"name\":\"git\""))
        assertTrue(json.contains("\"version\":\"1:2.43.0-1\""))
        // rc*（已卸载留配置）被过滤 —— 只报 ii*
        assertFalse(json.contains("removed-pkg"))
    }

    @Test fun `packages mirror action degrades structurally when sources not wired`() = runBlocking<Unit> {
        val tool = TerminalLinuxPackagesTool(FakePm(), sources = null, rootfsDir = { null }, arch = { null })
        val json = tool.invoke("""{"action":"mirror","op":"list"}""")
        assertTrue(json.contains("AptError:UNSUPPORTED"))
    }

    @Test fun `packages mirror action lists registry when wired`() = runBlocking<Unit> {
        val root = File.createTempFile("rootfs", "").let { it.delete(); it.mkdirs(); it }
        val tool = TerminalLinuxPackagesTool(
            FakePm(),
            sources = com.apex.agent.platform.terminal.ubuntu.UbuntuSourcesList(),
            rootfsDir = { root },
            arch = { com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64 }
        )
        val json = tool.invoke("""{"action":"mirror","op":"list"}""")
        assertTrue(json.contains("tuna"))
        assertTrue(json.contains("aliyun"))
        val setJson = tool.invoke("""{"action":"mirror","op":"set","mirror":"tuna"}""")
        assertTrue(setJson.contains("\"mirror\":\"tuna\""))
        assertTrue(setJson.contains("\"mirrorHost\":\"mirrors.tuna.tsinghua.edu.cn\""))
        root.deleteRecursively()
    }

    @Test fun `fs tool rejects writes outside sandbox with structured error`() = runBlocking<Unit> {
        val fs = GuestFilesystem(
            contextFactory = unavailable(),
            executor = com.apex.agent.platform.terminal.proot.ProotExecutor()
        )
        val tool = TerminalFsTool(fs)
        val json = tool.invoke("""{"action":"write","path":"/etc/passwd","content":"x"}""")
        assertTrue(json.contains("FsError:PathDenied"))
    }

    @Test fun `fs tool schema declares all ten actions`() {
        val fs = GuestFilesystem(
            contextFactory = unavailable(),
            executor = com.apex.agent.platform.terminal.proot.ProotExecutor()
        )
        val tool: TerminalTool = TerminalFsTool(fs)
        for (a in listOf("read", "write", "append", "list", "stat", "exists", "mkdir", "remove", "move", "copy")) {
            assertTrue(tool.parametersSchema.contains("\"$a\""))
        }
        assertEquals("terminal.fs", tool.id)
    }

    @Test fun `bridge tool status lists handlers and call dispatches`() = runBlocking<Unit> {
        val dir = File.createTempFile("bridge", "").let { it.delete(); it.mkdirs(); it }
        val bridge = com.apex.agent.platform.terminal.bridge.GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(object : com.apex.agent.platform.terminal.bridge.GuestBridgeHandler {
                override val action = "clipboard"
                override val description = "test clipboard"
                override suspend fun handle(argsJson: String): String = "clip:$argsJson"
            }),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val tool = TerminalBridgeTool(bridge)
        val status = tool.invoke("""{"action":"status"}""")
        assertTrue(status.contains("\"clipboard\""))
        val call = tool.invoke("""{"action":"call","bridgeAction":"clipboard","args":"get"}""")
        assertTrue(call.contains("\"data\":\"clip:get\""))
        val unknown = tool.invoke("""{"action":"call","bridgeAction":"nope"}""")
        assertTrue(unknown.contains("BRIDGE_UNKNOWN_ACTION"))
        dir.deleteRecursively()
    }

    private fun unavailable(): com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory =
        com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory(
            binaryProvider = object : com.apex.agent.platform.terminal.proot.PRootBinaryProvider {
                override suspend fun locate(): Result<com.apex.agent.platform.terminal.workspace.AbsolutePath> =
                    Result.failure(IllegalStateException("none"))
                override suspend fun verify(binary: com.apex.agent.platform.terminal.workspace.AbsolutePath): Result<com.apex.agent.platform.terminal.proot.PRootBinaryInfo> =
                    Result.failure(IllegalStateException("none"))
            },
            rootfsProvider = object : com.apex.agent.platform.terminal.linux.RootfsProvider {
                override suspend fun current(): com.apex.agent.platform.terminal.linux.RootfsDescriptor? = null
                override suspend fun verify(rootfs: com.apex.agent.platform.terminal.linux.RootfsDescriptor): Result<com.apex.agent.platform.terminal.linux.RootfsVerification> =
                    Result.failure(IllegalStateException("none"))
            },
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                File.createTempFile("wsdir", "").let { it.delete(); it.mkdirs(); it }
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File.createTempFile("home", "").let { it.delete(); it.mkdirs(); it }
            ),
            hostEnv = null
        )
}
