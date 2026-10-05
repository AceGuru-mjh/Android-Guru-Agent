package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * - 单次 listSkills 的 frontmatter 抓取限制并发数，避免 GitHub API
 *   匿名限流 60 req/h 被目录页一次性打爆。
 *
 * v3 修复（P2-5）：
 * - frontmatter 探测改 64KB **截断读取**：超长 SKILL.md 不再因超限返回
 *   null 而让条目从目录里静默消失（frontmatter 永远在文件头部）；
 * - frontmatter 抓取改 Semaphore(4) 受控并发：几十个技能目录从串行数十秒
 *   降到秒级；单条失败保留条目（name 回退 id），不再整条丢弃。
 */
class ModelScopeSource(
    private val httpClient: OkHttpClient,
    // P2（市场审计）：token 改为每次请求时取 —— 旧构造快照在 @Singleton 生命周期
    // 内固化首帧值，用户登录/更换 GitHub token 后源永不感知（匿名配额继续降级）。
    private val gitHubTokenProvider: () -> String? = { null }
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

            // P2-5：frontmatter 抓取改 Semaphore(4) 受控并发 —— 串行时几十上百
            // 个技能目录要数十秒；map + awaitAll 保证结果顺序与目录序一致，
            // 单条失败保留条目（对齐「抓到但无 frontmatter」的既有回退分支）。
            val semaphore = Semaphore(FRONTMATTER_CONCURRENCY)
            val skills = coroutineScope {
                skillDirs.map { dir ->
                    async {
                        val id = dir.substringAfterLast('/')
                        // 只抓 SKILL.md 头部做 frontmatter 解析（64KB 截断读，省流量）
                        val frontmatter = semaphore.withPermit {
                            fetchFrontmatter("$dir/SKILL.md")
                        }
                        ModelScopeSkill(
                            id = id,
                            name = frontmatter?.first ?: id,
                            description = frontmatter?.second ?: "",
                            path = "$dir/SKILL.md",
                            files = tree.filter { it.startsWith("$dir/") }
                        )
                    }
                }.awaitAll()
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
            .apply { gitHubTokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // P2（市场审计）：改流式限读 —— 旧 `body.string()` 先整体读入内存再查
            // 长度，恶意超大响应的 OOM 防御形同虚设；与 HubSource/ClawHubSource/
            // McpSoSource 的统一流式上限口径对齐。
            val body = response.body?.byteStream()?.use { stream ->
                stream.readBytesLimited(MAX_BODY_BYTES)
            }?.toString(Charsets.UTF_8) ?: return null
            val treeArray = json.parseToJsonElement(body).jsonObject["tree"]?.jsonArray ?: return null
            return treeArray.mapNotNull { el ->
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                val path = obj["path"]?.jsonPrimitive?.content
                if (type == "blob" && path != null) path else null
            }
        }
    }

    /** 流式限读（与 HubSource/ClawHubSource/McpSoSource 同款）：超限返回 null。 */
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

    /**
     * 解析 SKILL.md frontmatter 的 name/description（只读前 64KB；超长文件
     * 截断头部继续解析 —— frontmatter 永远在文件开头，截断不影响取值）。
     */
    private fun fetchFrontmatter(path: String): Pair<String?, String?>? {
        val raw = fetchRaw(
            path,
            limitBytes = FRONTMATTER_LIMIT_BYTES,
            truncate = true
        ) ?: return null
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

    /**
     * 抓取仓库内单文件内容。
     *
     * [truncate] = true（frontmatter 探测路径）：读到 [limitBytes] 即停，超限
     * 返回**截断后的头部**（P2-5：>64KB 的 SKILL.md 不能因超限从目录消失，
     * frontmatter 解析只需要文件头部）；
     * [truncate] = false（安装全文路径）：超限返回 null → 调用方明确报
     * 「下载失败」，不静默装出被截断的技能正文。
     */
    private fun fetchRaw(
        path: String,
        limitBytes: Int = MAX_BODY_BYTES,
        truncate: Boolean = false
    ): String? {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/modelscope/modelscope-skills/main/$path")
            .header("User-Agent", "ApexAgent/1.0")
            .apply { gitHubTokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.let { stream ->
                    // 多读 1 字节用于判断是否超限；截断模式下读满即断流，
                    // 不会把整个大文件拉下来
                    val bytes = stream.readBytes(limitBytes + 1)
                    when {
                        bytes.size <= limitBytes -> String(bytes, Charsets.UTF_8)
                        truncate -> String(bytes, 0, limitBytes, Charsets.UTF_8)
                        else -> null
                    }
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

        /** frontmatter 探测只取前 64KB（超长文件截断头部，不丢弃条目）。 */
        private const val FRONTMATTER_LIMIT_BYTES = 64 * 1024

        /** frontmatter 抓取受控并发数（串行太慢，全并发会压垮 raw CDN 连接配额）。 */
        private const val FRONTMATTER_CONCURRENCY = 4
    }
}
