package com.apex.agent.core.tools.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Skill 定义（apex-skill-v1 manifest）
 *
 * 四种 Skill 类型：
 * 1. Composite — 由多个现有 Tool 组合而成（步骤序列）
 * 2. Prompt    — 不添加新 Tool，注入专用 System Prompt
 * 3. Script    — 包含可执行脚本（Python/Shell），通过 shell_execute 运行
 * 4. Connector — 连接外部服务（API/SSH/数据库）
 */
@Serializable
data class SkillManifest(
    val schema: String = "apex-skill-v1",
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val license: String = "MIT",
    val dependencies: List<String> = emptyList(),  // 依赖的其他 Skill id（按安装顺序先于本 Skill 加载）
    val requirements: SkillRequirements = SkillRequirements(),
    val tools: List<SkillToolDef> = emptyList(),
    val configuration: SkillConfiguration = SkillConfiguration(),
    val promptInjection: String? = null  // Prompt Skill专用
)

@Serializable
data class SkillRequirements(
    val minAppVersion: Int = 1,
    val permissions: List<String> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val privilegeLevel: String = "none"  // none, shizuku, root
)

@Serializable
data class SkillToolDef(
    val id: String,
    val name: String,
    val description: String,
    val parameters: String,  // JSON Schema string
    val implementation: SkillImplementation
)

@Serializable
data class SkillImplementation(
    val type: String,  // "composite", "script", "prompt", "connector"
    val steps: List<SkillStep> = emptyList(),
    val script: String? = null,       // Script Skill
    val scriptLang: String? = null,   // "python", "shell"
    val connectorUrl: String? = null  // Connector Skill
)

@Serializable
data class SkillStep(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val condition: String? = null  // 条件执行
)

@Serializable
data class SkillConfiguration(
    val autoSetup: List<SetupAction> = emptyList(),
    val userConfig: List<UserConfigField> = emptyList()
)

@Serializable
data class SetupAction(
    val action: String,  // "create_directory", "register_tool", "memorize", "install_package"
    val path: String? = null,
    val toolId: String? = null,
    val key: String? = null,
    val content: String? = null,
    val command: String? = null
)

@Serializable
data class UserConfigField(
    val key: String,
    val type: String,  // "string", "integer", "boolean", "enum"
    val default: String? = null,
    val options: List<String> = emptyList(),
    val description: String = ""
)

/**
 * Skill 注册表
 * 管理所有已安装的 Skill（持久化到文件系统）
 */
class SkillRegistry(
    private val skillsDir: File
) {
    private val installedSkills = mutableMapOf<String, InstalledSkill>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // 禁用状态持久化：sidecar 文件（每行一个 skill id），不污染 manifest schema
    private val disabledIds = mutableSetOf<String>()

    init {
        skillsDir.mkdirs()
        loadDisabledIds()
        loadInstalledSkills()
    }

    private val disabledFile: File get() = File(skillsDir, ".disabled")

    private fun loadDisabledIds() {
        disabledIds.clear()
        if (disabledFile.exists()) {
            disabledFile.readLines().forEach { if (it.isNotBlank()) disabledIds.add(it.trim()) }
        }
    }

    private fun persistDisabledIds() {
        try {
            disabledFile.writeText(disabledIds.joinToString("\n"))
        } catch (_: Exception) { /* 写失败不阻断内存态，下次 setEnabled 再试 */ }
    }

    data class InstalledSkill(
        val manifest: SkillManifest,
        val enabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val config: MutableMap<String, String> = mutableMapOf()
    )

    /**
     * 安装 Skill（从 JSON 字符串）。会先校验依赖是否齐备，缺失依赖则拒绝安装。
     */
    fun install(manifestJson: String): Result<SkillManifest> {
        return try {
            val manifest = json.decodeFromString<SkillManifest>(manifestJson)

            if (manifest.id.isBlank()) return Result.failure(Exception("Skill ID is empty"))
            if (manifest.name.isBlank()) return Result.failure(Exception("Skill name is empty"))

            // 依赖校验：所有依赖必须已安装
            val missing = SkillDependencyResolver.validateDependencies(manifest, installedSkills.keys)
            if (missing.isNotEmpty()) {
                return Result.failure(Exception("Missing dependencies: ${missing.joinToString(", ")}"))
            }

            val skillFile = File(skillsDir, "${manifest.id}.json")
            skillFile.writeText(manifestJson)

            installedSkills[manifest.id] = InstalledSkill(manifest)

            executeAutoSetup(manifest)

            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(Exception("Skill install failed: ${e.message}"))
        }
    }

    /**
     * 从 ZIP 包安装 Skill。
     * 包内需含一个 `<skillId>.json`（apex-skill-v1 manifest），其余文件为 Skill 资源。
     * 解压走 [SafeZipExtractor]（路径穿越防御 + zip bomb 防护）。
     */
    fun installFromZip(zipFile: File): Result<SkillManifest> {
        val tmpDir = File(skillsDir, ".tmp-${zipFile.nameWithoutExtension}-${System.nanoTime()}")
        return try {
            SafeZipExtractor.extract(zipFile, tmpDir)

            val manifestFile = tmpDir.listFiles()
                ?.firstOrNull { it.extension == "json" && it.name != "installed.json" }
                ?: return Result.failure(Exception("No skill manifest .json found in zip"))

            val manifest = json.decodeFromString<SkillManifest>(manifestFile.readText())

            // 依赖校验
            val missing = SkillDependencyResolver.validateDependencies(manifest, installedSkills.keys)
            if (missing.isNotEmpty()) {
                return Result.failure(Exception("Missing dependencies: ${missing.joinToString(", ")}"))
            }

            // 资源目录：将 zip 内除 manifest 外的所有文件并入 skillsDir/<id>/，
            // manifest 只作为顶层索引 <id>.json 保存（避免重复存储）。
            val skillHome = File(skillsDir, manifest.id).apply { mkdirs() }
            tmpDir.listFiles()
                ?.filter { it != manifestFile }
                ?.forEach { it.copyRecursively(File(skillHome, it.name), overwrite = true) }

            val skillFile = File(skillsDir, "${manifest.id}.json")
            skillFile.writeText(manifestFile.readText())

            installedSkills[manifest.id] = InstalledSkill(manifest)
            executeAutoSetup(manifest)

            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(Exception("Skill zip install failed: ${e.message}"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * 卸载 Skill
     */
    fun uninstall(skillId: String): Boolean {
        installedSkills.remove(skillId) ?: return false
        disabledIds.remove(skillId)
        persistDisabledIds()
        return File(skillsDir, "$skillId.json").delete()
    }

    /**
     * 获取所有已安装 Skill
     */
    fun getInstalled(): List<InstalledSkill> = installedSkills.values.toList()

    /**
     * 获取启用的 Skill 提供的工具
     */
    fun getActiveTools(): List<SkillToolDef> {
        return installedSkills.values
            .filter { it.enabled }
            .flatMap { it.manifest.tools }
    }

    /**
     * 获取所有 Prompt 注入
     */
    fun getPromptInjections(): List<String> {
        return installedSkills.values
            .filter { it.enabled }
            .mapNotNull { it.manifest.promptInjection }
    }

    /**
     * 启用/禁用 Skill
     */
    fun setEnabled(skillId: String, enabled: Boolean) {
        installedSkills[skillId]?.let {
            installedSkills[skillId] = it.copy(enabled = enabled)
        }
        if (enabled) disabledIds.remove(skillId) else disabledIds.add(skillId)
        persistDisabledIds()
    }

    /**
     * 设置 Skill 配置
     */
    fun setConfig(skillId: String, key: String, value: String) {
        installedSkills[skillId]?.config?.put(key, value)
    }

    /**
     * 执行自动配置（创建目录等。register_tool/memorize 由 ToolRegistry 层处理）
     */
    private fun executeAutoSetup(manifest: SkillManifest) {
        for (action in manifest.configuration.autoSetup) {
            when (action.action) {
                "create_directory" -> {
                    action.path?.let { File(skillsDir.parentFile, it).mkdirs() }
                }
                // register_tool / memorize / install_package 由调用方在 ToolRegistry 中处理
            }
        }
    }

    private fun loadInstalledSkills() {
        skillsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val manifest = json.decodeFromString<SkillManifest>(file.readText())
                installedSkills[manifest.id] = InstalledSkill(manifest, enabled = manifest.id !in disabledIds)
            } catch (_: Exception) { /* skip malformed */ }
        }
    }
}
