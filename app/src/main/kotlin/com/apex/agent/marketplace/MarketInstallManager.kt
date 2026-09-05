package com.apex.agent.marketplace

import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.github.GithubTokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - Skill      → [SkillRegistry]（`<id>.json` manifest + `<id>/` 资源目录）
 * - MCP        → [McpManager]（mcp_servers.json）
 * - 连接器      → [ConnectorRegistry]（connectors.json）
 *
 * 所有方法返回 Result，错误信息可直接展示给用户。
 * v2：网络路径全部 withContext(Dispatchers.IO) + 响应体大小上限；
 * JSON 转义补齐换行/回车/制表符（PR45 初版只转义 \\ 与 \"，多行
 * description 必然写坏 manifest）。
 */
@Singleton
class MarketInstallManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val httpClient: OkHttpClient,
    private val modelScopeSource: ModelScopeSource,
    private val githubTokenManager: GithubTokenManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class GitHubRepoHit(
        val fullName: String,
        val description: String,
        val stars: Int,
        val htmlUrl: String
    )

    // ═══ Skill：JSON 内容安装 ═══
    suspend fun installSkillFromJson(content: String): Result<String> =
        withContext(Dispatchers.IO) {
            skillRegistry.install(content).map { "已安装 Skill：${it.name}（${it.id}）" }
        }

    // ═══ Skill：URL 安装（IO 线程 + 2MB 上限）═══
    suspend fun installSkillFromUrl(url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ApexAgent/1.0")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} 下载失败")
                    val bytes = resp.body?.byteStream()?.use { stream ->
                        stream.readBytesLimited(MAX_DOWNLOAD_BYTES)
                    } ?: throw Exception("空响应")
                    if (bytes == null) throw Exception("文件过大（>${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB）")
                    String(bytes, Charsets.UTF_8)
                }
            }.fold(
                onSuccess = { content -> installSkillFromJson(content) },
                onFailure = { Result.failure(Exception("下载失败：${it.message}")) }
            )
        }

    // ═══ Skill：内置模板安装 ═══
    suspend fun installSkillTemplate(templateId: String): Result<String> {
        val manifestJson = when (templateId) {
            "coding_principles" -> SkillInstallTool.CODING_PRINCIPLES_TEMPLATE
            "web_scraper" -> SkillInstallTool.WEB_SCRAPER_TEMPLATE
            "file_organizer" -> SkillInstallTool.FILE_ORGANIZER_TEMPLATE
            "code_runner" -> SkillInstallTool.CODE_RUNNER_TEMPLATE
            "data_analyzer" -> SkillInstallTool.DATA_ANALYZER_TEMPLATE
            else -> return Result.failure(Exception("未知模板 $templateId"))
        }
        return installSkillFromJson(manifestJson)
    }

    // ═══ Skill：魔搭 SKILL.md → apex-skill-v1（prompt 型）═══
    suspend fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill): Result<String> {
        val markdown = modelScopeSource.fetchSkillMarkdown(skill).getOrElse {
            return Result.failure(Exception("SKILL.md 下载失败：${it.message}"))
        }

        // 资源文件（references/scripts 等）下载到 skillsDir/<id>/ 资源目录
        val resourceFiles = skill.files.filter { it != skill.path && !it.endsWith("/") }
        val skillHome = File(skillHomeDir(), "ms-${skill.id}").apply { mkdirs() }
        var resourceFailed = false
        for (filePath in resourceFiles) {
            val content = modelScopeSource.fetchSkillResource(skill, filePath).getOrNull()
            if (content != null) {
                val rel = filePath.removePrefix("skills/${skill.id}/")
                if (rel.isBlank() || rel.contains("..")) continue
                val target = File(skillHome, rel)
                // 路径穿越防御：目标必须仍在资源目录内
                if (!target.canonicalPath.startsWith(skillHome.canonicalPath + File.separator)) continue
                target.parentFile?.mkdirs()
                target.writeText(content)
            } else {
                resourceFailed = true
            }
        }

        // 构建 prompt 型 manifest：promptInjection = SKILL.md 全文
        val manifest = buildString {
            append("{\n")
            append("\"schema\":\"apex-skill-v1\",\n")
            append("\"id\":\"ms-${escapeJson(skill.id)}\",\n")
            append("\"name\":\"${escapeJson(skill.name)}\",\n")
            append("\"version\":\"1.0.0\",\n")
            append("\"description\":\"${escapeJson(skill.description)}\",\n")
            append("\"author\":\"modelscope\",\n")
            append("\"promptInjection\":\"${escapeJson(markdown)}\",\n")
            append("\"tools\":[],\n")
            append("\"configuration\":{\"autoSetup\":[]}\n")
            append("}")
        }

        return installSkillFromJson(manifest).map { msg ->
            if (resourceFailed) "$msg（部分资源文件下载失败）" else msg
        }
    }

    // ═══ Skill：GitHub 仓库安装（尝试常见 manifest 路径）═══
    suspend fun installSkillFromGitHubRepo(owner: String, repo: String): Result<String> =
        withContext(Dispatchers.IO) {
            val candidates = listOf(
                "manifest.json", "skill.json", "apex-skill.json", "$repo.json", "skills/$repo.json"
            )
            var lastError = "仓库中未找到可安装的 manifest"
            for (candidate in candidates) {
                val url = "https://raw.githubusercontent.com/$owner/$repo/main/$candidate"
                val content = tryDownload(url) ?: continue
                // 必须是合法的 apex-skill-v1 manifest 才接受
                if (looksLikeApexManifest(content)) {
                    return@withContext installSkillFromJson(content)
                }
                lastError = "找到文件但不是有效的 apex-skill-v1 manifest"
            }
            Result.failure(Exception(lastError))
        }

    // ═══ GitHub 仓库搜索（市场"集成"页）═══
    suspend fun searchGitHubSkills(query: String): Result<List<GitHubRepoHit>> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=20")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ApexAgent/1.0")
                    .apply { githubTokenManager.getToken()?.let { header("Authorization", "Bearer $it") } }
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("GitHub 搜索失败: HTTP ${resp.code}"))
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

    // ═══ 连接器 ═══
    fun addConnector(def: ConnectorDef): Result<String> {
        return connectorRegistry.add(def).map { "已添加连接器：${def.name}" }
    }

    // ─── 内部 ───

    private fun skillHomeDir(): File {
        // SkillRegistry 持久化在 filesDir/skills；魔搭资源目录挂在其下
        val dir = File(context.filesDir, "skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun tryDownload(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "ApexAgent/1.0").build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.byteStream()?.use { it.readBytesLimited(MAX_DOWNLOAD_BYTES) }
                ?: return@use null
            if (bytes == null) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /** 读取至多 [max] 字节；超限返回 null。 */
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && read() >= 0) return null
        return out.toByteArray()
    }

    private fun looksLikeApexManifest(content: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(content).jsonObject
        obj["schema"]?.jsonPrimitive?.content == "apex-skill-v1" ||
            (obj.containsKey("id") && obj.containsKey("name") && obj.containsKey("tools"))
    }.getOrDefault(false)

    /** 完整 JSON 字符串转义（含换行/回车/制表符）。 */
    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    }
}
