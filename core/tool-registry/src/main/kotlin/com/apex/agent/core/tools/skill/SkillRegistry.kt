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

    init {
        skillsDir.mkdirs()
        loadInstalledSkills()
    }

    data class InstalledSkill(
        val manifest: SkillManifest,
        val enabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val config: MutableMap<String, String> = mutableMapOf()
    )

    /**
     * 安装 Skill（从 JSON 字符串）
     */
    fun install(manifestJson: String): Result<SkillManifest> {
        return try {
            val manifest = json.decodeFromString<SkillManifest>(manifestJson)

            if (manifest.id.isBlank()) return Result.failure(Exception("Skill ID is empty"))
            if (manifest.name.isBlank()) return Result.failure(Exception("Skill name is empty"))

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
     * 卸载 Skill
     */
    fun uninstall(skillId: String): Boolean {
        installedSkills.remove(skillId) ?: return false
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
                installedSkills[manifest.id] = InstalledSkill(manifest)
            } catch (_: Exception) { /* skip malformed */ }
        }
    }
}
