package com.apex.agent.marketplace

import android.content.Context
import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.plugin.host.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 市场统一安装管道
 *
 * 把来自不同来源（内置模板 / URL / JSON 内容 / 魔搭 / GitHub）的安装请求
 * 分发到对应的注册表：
 * - Skill      → [SkillRegistry]（<id>.json manifest + <id>/ 资源目录）
 * - MCP        → [McpManager]（mcp_servers.json）
 * - 连接器      → [ConnectorRegistry]（connectors.json）
 * - 插件 APK   → [PluginManager]（系统插件发现 + 加载）
 *
 * 所有方法返回 Result，错误信息可直接展示给用户。
 */
@Singleton
class MarketInstallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val pluginManager: PluginManager,
    private val httpClient: OkHttpClient,
    private val modelScopeSource: ModelScopeSource
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ═══ Skill：JSON 内容安装 ═══
    fun installSkillFromJson(content: String): Result<String> {
        return skillRegistry.install(content).map { "已安装 Skill：${it.name} (${it.id})" }
    }

    // ═══ Skill：URL 安装 ═══
    suspend fun installSkillFromUrl(url: String): Result<String> {
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "ApexAgent/1.0").build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} 下载失败")
                resp.body?.string() ?: throw Exception("空响应")
            }
        }.fold(
            onSuccess = { installSkillFromJson(it) },
            onFailure = { Result.failure(Exception("下载失败: ${it.message}")) }
        )
    }

    // ═══ Skill：内置模板安装 ═══
    fun installSkillTemplate(templateId: String): Result<String> {
        val entry = SkillInstallTool.BUILTIN_TEMPLATES_BY_ID[templateId]
            ?: return Result.failure(Exception("未知模板 $templateId"))
        return installSkillFromJson(entry.manifestJson)
    }

    // ═══ Skill：魔搭 SKILL.md → apex-skill-v1（prompt 型） ═══
    suspend fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill): Result<String> {
        val mdResult = modelScopeSource.fetchSkillMarkdown(skill)
        if (mdResult.isFailure) return Result.failure(mdResult.exceptionOrNull() ?: Exception("下载失败"))
        val markdown = mdResult.getOrThrow()

        // 资源文件（references/scripts 等）下载到 skillsDir/<id>/ 资源目录
        val resourceFiles = skill.files.filter { it != skill.path && !it.endsWith("/") }
        val skillHome = File(skillRegistryHome(), skill.id).apply { mkdirs() }
        var resourceFailed = false
        for (filePath in resourceFiles) {
            val content = modelScopeSource.fetchSkillResource(skill, filePath).getOrNull()
            if (content != null) {
                val target = File(skillHome, filePath.removePrefix("skills/${skill.id}/"))
                target.parentFile?.mkdirs()
                target.writeText(content)
            } else {
                resourceFailed = true
            }
        }

        // 构建 prompt 型 manifest：promptInjection = SKILL.md 全文
        val escaped = markdown
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val manifest = buildString {
            append("{\n")
            append("\"schema\":\"apex-skill-v1\",\n")
            append("\"id\":\"${skill.id}\",\n")
            append("\"name\":\"${skill.name}\",\n")
            append("\"version\":\"1.0.0\",\n")
            append("\"description\":\"${escape(skill.description)}\",\n")
            append("\"author\":\"modelscope\",\n")
            append("\"promptInjection\":\"$escaped\",\n")
            append("\"tools\":[],\n")
            append("\"configuration\":{\"autoSetup\":[]}\n")
            append("}")
        }

        return installSkillFromJson(manifest).map { msg ->
            if (resourceFailed) "$msg（部分资源文件下载失败）" else msg
        }
    }

    // ═══ Skill：GitHub 仓库安装（尝试常见 manifest 路径） ═══
    suspend fun installSkillFromGitHubRepo(owner: String, repo: String): Result<String> {
        val candidates = listOf(
            "manifest.json", "skill.json", "apex-skill.json", "$repo.json", "skills/$repo.json"
        )
        var lastError = "仓库中未找到可安装的 manifest"
        for (candidate in candidates) {
            val url = "https://raw.githubusercontent.com/$owner/$repo/main/$candidate"
            val result = tryDownload(url) ?: continue
            // 必须是合法的 apex-skill-v1 manifest 才接受
            if (looksLikeApexManifest(result)) {
                return installSkillFromJson(result)
            }
            lastError = "找到文件但不是有效的 apex-skill-v1 manifest"
        }
        return Result.failure(Exception(lastError))
    }

    // ═══ GitHub 仓库搜索（市场"集成"页） ═══
    suspend fun searchGitHubSkills(query: String, token: String? = null): Result<List<GitHubRepoHit>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=20")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ApexAgent/1.0")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("GitHub 搜索失败: HTTP ${resp.code}"))
                }
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                val hits = root["items"]?.jsonArray?.mapNotNull { item ->
                    val obj = item.jsonObject
                    GitHubRepoHit(
                        fullName = obj["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        stars = obj["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                Result.success(hits)
            }
        } catch (e: Exception) {
            Result.failure(Exception("GitHub 搜索异常: ${e.message}"))
        }
    }

    // ═══ MCP ═══
    suspend fun addMcpServer(config: McpServerConfig): Result<String> {
        return mcpManager.addServer(config).map { "已添加 MCP 服务器：${config.name}" }
    }

    suspend fun removeMcpServer(name: String) {
        mcpManager.removeServer(name)
    }

    suspend fun connectMcpServer(name: String): Result<String> {
        return mcpManager.connect(name).map { "已连接 MCP 服务器：$name" }
    }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        mcpManager.setEnabled(name, enabled)
    }

    // ═══ 连接器 ═══
    fun addConnector(def: ConnectorDef): Result<String> {
        return connectorRegistry.add(def).map { "已添加连接器：${def.name}" }
    }

    fun removeConnector(id: String) {
        connectorRegistry.remove(id)
    }

    fun setConnectorEnabled(id: String, enabled: Boolean) {
        connectorRegistry.setEnabled(id, enabled)
    }

    // ═══ 插件 ═══
    fun loadPlugin(packageName: String) {
        pluginManager.discoverPlugins()
            .firstOrNull { it.packageName == packageName }
            ?.let { pluginManager.loadPlugin(it) }
    }

    fun unloadPlugin(packageName: String) {
        pluginManager.unloadPlugin(packageName)
    }

    // ═══ 内部工具 ═══

    /** SkillRegistry 的 skillsDir 下按 skill id 存放资源文件（与 <id>.json 索引同层） */
    private fun skillRegistryHome(): File =
        File(context.filesDir, "skills").apply { mkdirs() }

    private fun looksLikeApexManifest(content: String): Boolean {
        return try {
            val obj = json.parseToJsonElement(content).jsonObject
            obj["schema"]?.jsonPrimitive?.content == "apex-skill-v1" &&
                !(obj["id"]?.jsonPrimitive?.content ?: "").isBlank()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryDownload(url: String): String? {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "ApexAgent/1.0").build()
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** GitHub 仓库搜索结果条目 */
data class GitHubRepoHit(
    val fullName: String,
    val description: String,
    val stars: Int,
    val htmlUrl: String
)
