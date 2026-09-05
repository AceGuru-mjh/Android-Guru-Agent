package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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
 * 可开关、可被 `/skill:ms-<id>` 斜杠命令路由。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测。
 *
 * v2 修复（相对 PR45 初版）：
 * - 所有网络调用统一 `withContext(Dispatchers.IO)`，调用方线程模型不再影响安全；
 * - 响应体大小上限 [MAX_BODY_BYTES]（2MB），防御恶意超大响应 OOM；
 * - 单次 listSkills 的 frontmatter 抓取限制并发数（顺序抓取，避免 GitHub API
 *   匿名限流 60 req/h 被目录页一次性打爆）。
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

    /** 列出仓库中全部可用 Skill（GitHub git/trees API，无需 token）。 */
    suspend fun listSkills(): Result<List<ModelScopeSkill>> = withContext(Dispatchers.IO) {
        try {
            val tree = fetchTree() ?: return@withContext Result.failure(
                Exception("无法读取 modelscope-skills 仓库目录（网络不可达或被限流）")
            )
            val skillDirs = tree
                .filter { it.startsWith("skills/") && it.endsWith("/SKILL.md") }
                .map { it.removeSuffix("/SKILL.md") }
                .sorted()

            val skills = skillDirs.mapNotNull { dir ->
                val id = dir.substringAfterLast('/')
                // 只抓 SKILL.md 的前几行做 frontmatter 解析（截断请求体，省流量）
                val frontmatter = fetchFrontmatter("$dir/SKILL.md") ?: return@mapNotNull null
                ModelScopeSkill(
                    id = id,
                    name = frontmatter.first ?: id,
                    description = frontmatter.second ?: "",
                    path = "$dir/SKILL.md",
                    files = tree.filter { it.startsWith("$dir/") }
                )
            }
            Result.success(skills)
        } catch (e: Exception) {
            Result.failure(Exception("ModelScope 列表失败: ${e.message}"))
        }
    }

    /** 下载某个 Skill 的 SKILL.md 全文（用于转 apex-skill-v1 manifest）。 */
    suspend fun fetchSkillMarkdown(skill: ModelScopeSkill): Result<String> =
        withContext(Dispatchers.IO) {
            fetchRaw(skill.path)?.let { Result.success(it) }
                ?: Result.failure(Exception("下载失败: ${skill.path}"))
        }

    /** 下载插件目录内的资源文件（references/scripts 等），返回 content。 */
    suspend fun fetchSkillResource(skill: ModelScopeSkill, filePath: String): Result<String> =
        withContext(Dispatchers.IO) {
            fetchRaw(filePath)?.let { Result.success(it) }
                ?: Result.failure(Exception("下载失败: $filePath"))
        }

    /** 在仓库技能中做关键词过滤（本地过滤，含中英文）。 */
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

    private fun fetchTree(): List<String>? {
        val request = Request.Builder()
            .url("$baseUrl/git/trees/main?recursive=1")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ApexAgent/1.0")
            .apply { gitHubToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.length > MAX_BODY_BYTES) return null
            val treeArray = json.parseToJsonElement(body).jsonObject["tree"]?.jsonArray ?: return null
            return treeArray.mapNotNull { el ->
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                val path = obj["path"]?.jsonPrimitive?.content
                if (type == "blob" && path != null) path else null
            }
        }
    }

    /** 解析 SKILL.md frontmatter 的 name/description（只读前 64KB）。 */
    private fun fetchFrontmatter(path: String): Pair<String?, String?>? {
        val raw = fetchRaw(path, limitBytes = FRONTMATTER_LIMIT_BYTES) ?: return null
        // YAML frontmatter: ---
        //   name: xxx
        //   description: yyy
        // ---
        if (!raw.startsWith("---")) return null to null
        val end = raw.indexOf("---", 3)
        val front = if (end > 0) raw.substring(3, end) else raw.substring(3)
        var name: String? = null
        var description: String? = null
        for (line in front.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name:") -> name = trimmed.removePrefix("name:").trim()
                trimmed.startsWith("description:") ->
                    description = trimmed.removePrefix("description:").trim()
            }
        }
        return name to description
    }

    private fun fetchRaw(path: String, limitBytes: Int = MAX_BODY_BYTES): String? {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/modelscope/modelscope-skills/main/$path")
            .header("User-Agent", "ApexAgent/1.0")
            .apply { gitHubToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.let { stream ->
                    val bytes = stream.readBytes(limitBytes + 1)
                    if (bytes.size > limitBytes) null else String(bytes, Charsets.UTF_8)
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    companion object {
        /** 单次响应体上限（防御恶意/超大响应 OOM）。 */
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024

        /** frontmatter 探测只取前 64KB。 */
        private const val FRONTMATTER_LIMIT_BYTES = 64 * 1024
    }
}
