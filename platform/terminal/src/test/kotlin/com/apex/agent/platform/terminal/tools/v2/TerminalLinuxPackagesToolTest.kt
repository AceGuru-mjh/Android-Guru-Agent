package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageInfo
import com.apex.agent.platform.terminal.pkg.PackageInstallOptions
import com.apex.agent.platform.terminal.pkg.PackageOperation
import com.apex.agent.platform.terminal.pkg.PackageOperationResult
import com.apex.agent.platform.terminal.pkg.PackageOperationState
import com.apex.agent.platform.terminal.pkg.PackageOperationType
import com.apex.agent.platform.terminal.pkg.PackageManagerStatus
import com.apex.agent.platform.terminal.pkg.PackageRemoveOptions
import com.apex.agent.platform.terminal.pkg.PackageSearchResult
import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.pkg.PackageUpdateOptions
import com.apex.agent.platform.terminal.pkg.PackageUpgradeOptions
import com.apex.agent.platform.terminal.pkg.PackageOperationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

/**
 * T76: TerminalLinuxPackagesTool 单元测试 —— JSON 契约 + 结构化参数。
 */
class TerminalLinuxPackagesToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakePm : LinuxPackageManager {
        override suspend fun status() = PackageManagerStatus(
            available = true, manager = "apt-get", version = "3.0",
            databaseState = com.apex.agent.platform.terminal.pkg.PackageDatabaseState.HEALTHY,
            lockState = com.apex.agent.platform.terminal.pkg.PackageLockState.FREE,
            metadataState = com.apex.agent.platform.terminal.pkg.PackageMetadataState.CURRENT,
            brokenPackages = emptyList()
        )
        override suspend fun update(options: PackageUpdateOptions) = op(PackageOperationType.UPDATE, listOf("git"), true)
        override suspend fun install(packages: List<PackageSpec>, options: PackageInstallOptions) =
            op(PackageOperationType.INSTALL, packages.map { it.name }, true)
        override suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions) =
            op(PackageOperationType.REMOVE, packages.map { it.name }, true)
        override suspend fun upgrade(packages: List<PackageSpec>, options: PackageUpgradeOptions) =
            op(PackageOperationType.UPGRADE, packages.map { it.name }, true)
        override suspend fun search(query: String) = PackageSearchResult(
            query, listOf(PackageInfo("python3", "3.12", "arm64", true, "3.12", "Python", null))
        )
        override suspend fun info(packageName: String) = PackageInfo(
            packageName, "3.12.3", "arm64", true, "3.12.3", "Interactive high-level OO language", 12_000_000
        )
        override suspend fun isInstalled(packageName: String) = packageName == "git"
        override suspend fun installedVersion(packageName: String) = if (packageName == "git") "2.43.0" else null
        override suspend fun repair() = op(PackageOperationType.REPAIR, emptyList(), true)
        override fun operations(): Flow<PackageOperationEvent> = emptyFlow()

        private fun op(type: PackageOperationType, pkgs: List<String>, succeed: Boolean) = PackageOperation(
            id = "op-1", type = type, state = if (succeed) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
            requestedPackages = pkgs.map { PackageSpec(it) }, startedAt = 0, finishedAt = 100,
            exitCode = if (succeed) 0 else 100,
            result = PackageOperationResult(
                installed = pkgs, durationMs = 100, exitCode = if (succeed) 0 else 100,
                state = if (succeed) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = "Setting up git", stderr = ""
            ),
            error = null
        )
    }

    private val tool = TerminalLinuxPackagesTool(FakePm())

    @Test fun `tool id is terminal linux packages`() {
        assertEquals("terminal.linux.packages", tool.id)
    }

    @Test fun `status action returns available`() = runBlocking {
        val out = tool.invoke("""{"action":"status"}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("apt-get", obj["manager"]!!.jsonPrimitive.content)
    }

    @Test fun `update action returns succeeded`() = runBlocking {
        val out = tool.invoke("""{"action":"update"}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("SUCCEEDED", obj["state"]!!.jsonPrimitive.content)
    }

    @Test fun `install action parses packages array`() = runBlocking {
        val out = tool.invoke("""{"action":"install","packages":["git","python3"]}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("install", obj["action"]!!.jsonPrimitive.content)
        // installed array should contain git and python3
        val installed = obj["installed"]!!.jsonArray
        assertEquals(2, installed.size)
    }

    @Test fun `install with empty packages returns error`() = runBlocking {
        val out = tool.invoke("""{"action":"install","packages":[]}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun `search action returns results`() = runBlocking {
        val out = tool.invoke("""{"action":"search","query":"python"}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, obj["count"]!!.jsonPrimitive.content.toInt())
        val results = obj["results"]!!.jsonArray
        assertEquals("python3", results[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `isInstalled action returns boolean`() = runBlocking {
        val out = tool.invoke("""{"action":"isInstalled","packages":["git"]}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["installed"]!!.jsonPrimitive.boolean)
        assertEquals("2.43.0", obj["version"]!!.jsonPrimitive.content)
    }

    @Test fun `info action returns package details`() = runBlocking {
        val out = tool.invoke("""{"action":"info","packages":["python3"]}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("python3", obj["name"]!!.jsonPrimitive.content)
        assertEquals("3.12.3", obj["version"]!!.jsonPrimitive.content)
    }

    @Test fun `unknown action returns error`() = runBlocking {
        val out = tool.invoke("""{"action":"frobnicate"}""")
        val obj = json.parseToJsonElement(out).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("InvalidInput", obj["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun `parameters schema is valid JSON`() {
        val schema = json.parseToJsonElement(tool.parametersSchema).jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertNotNull(schema["properties"]!!.jsonObject["action"])
    }
}
