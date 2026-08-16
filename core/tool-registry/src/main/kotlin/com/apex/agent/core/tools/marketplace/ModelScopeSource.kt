package com.apex.agent.core.tools.marketplace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 魔搭（ModelScope）集成源
 *
 * 对接官方 [modelscope/modelscope-skills](https://github.com/modelscope/modelscope-skills)
 * 仓库：Claude 插件市场格式（.claude-plugin/marketplace.json），
 * 每个插件目录内含 Anthropic Agent Skills 格式的 `SKILL.md`
 * （frontmatter: name / description）。
 *
 * 安装策略：把 SKILL.md 转成本 App 的 apex-skill-v1 **prompt 型** manifest
 * （promptInjection = SKILL.md 全文），references/scripts 等资源下载到
 * skill 资源目录。这样魔搭技能安装后即出现在 Skill 管理页、
 * 可开关、可被 `/skill:ms-xxx` 斜杠命令路由。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测。
 */
class ModelScopeSource(
    private val httpClient: OkHttpClient,
    private val gitHubToken: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com/repos/modelscope/modelscope-skills"

    data class ModelScopeSkill(
        val id: String,            // 插件 id（如 ms-hub）
        val name: String,          // SKILL.md frontmatter name
        val description: String,   // SKILL.md frontmatter description
        val path: String,          // skills/<id>/SKILL.md
        val files: List<String>    // 插件目录内全部文件路径
    )

    /** 列出仓库中全部可用 Skill（GitHub git/trees API，无需 token） */
    suspend fun listSkills(): Result<List<ModelScopeSkill>> {
        return try {
            val tree = fetchTree() ?: return Result.failure(Exception("无法读取 modelscope-skills 仓库目录"))
            val skillDirs = tree
                .filter { it.startsWith("skills/") && it.endsWith("/SKILL.md") }
                .map { it.removeSuffix("/SKILL.md") }

            val skills = skillDirs.mapNotNull { dir ->
                val id = dir.substringAfterLast('/')
                val skillFiles = tree.filter { it.startsWith("$dir/") }
                val mdPath = "$dir/SKILL.md"
                val frontmatter = fetchFrontmatter(mdPath) ?: return@mapNotNull null
                ModelScopeSkill(
                    id = id,
                    name = frontmatter.first ?: id,
                    description = frontmatter.second ?: "",
                    path = mdPath,
                    files = skillFiles
                )
            }
            Result.success(skills)
        } catch (e: Exception) {
            Result.failure(Exception("ModelScope 列表失败: ${e.message}"))
        }
    }

    /** 下载某个 Skill 的 SKILL.md 全文（用于转 apex-skill-v1 manifest） */
    suspend fun fetchSkillMarkdown(skill: ModelScopeSkill): Result<String> {
        return fetchRaw(skill.path)
    }

    /** 下载插件目录内的资源文件（references/scripts 等），返回 content */
    suspend fun fetchSkillResource(skill: ModelScopeSkill, filePath: String): Result<String> {
        return fetchRaw(filePath)
    }

    /** 在仓库技能中做关键词过滤（本地过滤，含中英文） */
    fun filterSkills(skills: List<ModelScopeSkill>, query: String): List<ModelScopeSkill> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return skills
        return skills.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.id.lowercase().contains(q)
        }
    }

    // ── 内部实现 ──

    private suspend fun fetchTree(): List<String>? {
        val request = Request.Builder()
            .url("$baseUrl/git/trees/main?recursive=1")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ApexAgent/1.0")
            .apply { gitHubToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = json.parseToJsonElement(response.body?.string() ?: return null).jsonObject
            return root["tree"]?.jsonArray
                ?.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull }
        }
    }

    /** 解析 SKILL.md 的 YAML frontmatter（仅取 name/description 两键，容忍其他键） */
    private suspend fun fetchFrontmatter(path: String): Pair<String?, String?>? {
        val md = fetchRaw(path).getOrNull() ?: return null
        val lines = md.lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.indexOfFirst { it.trim() == "---" && it != lines.first() }
        if (end < 0) return null
        var name: String? = null
        var description: String? = null
        for (i in 1 until end) {
            val line = lines[i].trim()
            when {
                line.startsWith("name:") -> name = line.removePrefix("name:").trim().trim('"').trim('\'')
                line.startsWith("description:") -> {
                    // 支持 >- 多行块（缩进续行）
                    val first = line.removePrefix("description:").trim()
                    if (first == ">-" || first == ">") {
                        val body = mutableListOf<String>()
                        var j = i + 1
                        while (j < end && (lines[j].startsWith(" ") || lines[j].isBlank())) {
                            if (lines[j].isNotBlank()) body.add(lines[j].trim())
                            j++
                        }
                        description = body.joinToString(" ")
                    } else {
                        description = first.trim('"').trim('\'')
                    }
                }
            }
        }
        return name to description
    }

    private suspend fun fetchRaw(path: String): Result<String> {
        return try {
            val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val request = Request.Builder()
                .url("$baseUrl/contents/$encoded")
                .header("Accept", "application/vnd.github.raw+json")
                .header("User-Agent", "ApexAgent/1.0")
                .apply { gitHubToken?.let { header("Authorization", "Bearer $it") } }
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code} 下载 $path"))
                } else {
                    Result.success(response.body?.string() ?: "")
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("下载 $path 失败: ${e.message}"))
        }
    }

    companion object {
        const val REPO = "modelscope/modelscope-skills"
    }
}
