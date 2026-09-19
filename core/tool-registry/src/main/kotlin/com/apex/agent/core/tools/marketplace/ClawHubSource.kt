package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * ClawHub（clawhub.ai）技能仓库源
 *
 * 对接 ClawHub 公开只读 API（无需认证）：
 * - 热门浏览：`GET /api/v1/trending?limit=&offset=&lane=clawhub-trending`
 *   （`lane` 参数服务端实测会混入 skills.sh 等外部源条目，本类只保留
 *   `install.kind == "clawhub"` 的原生条目——只有它们能走下载端点）
 * - 关键词搜索：`GET /api/v1/search?q=<kw>&limit=&offset=`
 * - 下载 ZIP：`GET /api/v1/download?slug=<slug>&ownerHandle=<ownerHandle>`
 *   → application/zip（SKILL.md + _meta.json + skill-card.md + references/ 等）
 *
 * 安装策略与 [ModelScopeSource] 一致：把 SKILL.md 转成本 App 的
 * apex-skill-v1 **prompt 型** manifest（promptInjection = SKILL.md 全文），
 * references/scripts 等资源解压到 skill 资源目录，安装后出现在 Skill
 * 管理页、可开关、可被 `/skill:ch-<id>` 斜杠命令路由。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；所有网络调用统一
 * `withContext(Dispatchers.IO)`，响应体有大小上限（2MB JSON / 20MB ZIP），
 * 任何失败都返回 [Result.failure]，不向调用方抛异常。
 */
class ClawHubSource(
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 市场条目（trending 与 search 的字段并集映射，可缺失字段有默认值）。 */
    data class ClawHubSkillEntry(
        val slug: String,
        val owner: String,
        val displayName: String,
        val summary: String,
        val downloads: Long = 0,
        val stars: Long = 0,
        val featured: Boolean = false,
        val official: Boolean = false,
        val categories: List<String> = emptyList(),
        val avatarUrl: String? = null,
        val ownerDisplayName: String = "",
        val updatedAt: Long = 0
    ) {
        /** 条目唯一键：同 slug 不同 owner 视为不同条目。 */
        val key: String get() = "$owner/$slug"

        /** 安装到本地后的 skill id（ch- 前缀 + slug 合法化）。 */
        val installId: String get() = installIdFor(slug)

        /** ClawHub 站点主页。 */
        val homepage: String get() = "https://clawhub.ai/$owner/skills/$slug"
    }

    /**
     * 一页结果：条目 + 是否还有下一页。
     *
     * hasMore 用**原始**条目数与 limit 比较（过滤掉的外部源条目也计入），
     * 否则过滤后条目数偏小会提前判定"没有更多"。
     */
    data class ClawHubPage(
        val entries: List<ClawHubSkillEntry>,
        val hasMore: Boolean
    )

    /** 热门技能浏览（无需关键词）。 */
    suspend fun listTrending(
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): Result<ClawHubPage> = withContext(Dispatchers.IO) {
        val resp = fetchJson("$BASE_URL/trending?limit=$limit&offset=$offset&lane=clawhub-trending")
            ?: return@withContext Result.failure(Exception("无法连接 clawhub.ai（网络不可用或超时）"))
        if (resp.code !in 200..299) {
            return@withContext Result.failure(Exception("热门列表获取失败：HTTP ${resp.code}"))
        }
        val bodyText = resp.body
            ?: return@withContext Result.failure(Exception("热门列表响应为空或超过大小上限（2MB）"))
        try {
            val root = json.parseToJsonElement(bodyText).jsonObject
            val raw = root["items"]?.jsonArray ?: emptyList()
            val entries = raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseTrendingItem) }
            Result.success(ClawHubPage(entries, raw.size >= limit))
        } catch (e: Exception) {
            Result.failure(Exception("热门列表解析失败: ${e.message}"))
        }
    }

    /** 关键词搜索（空关键词直接报错，与 ClawHub 搜索端点行为一致）。 */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): Result<ClawHubPage> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) {
            return@withContext Result.failure(Exception("请输入搜索关键词"))
        }
        val encoded = URLEncoder.encode(q, "UTF-8")
        val resp = fetchJson("$BASE_URL/search?q=$encoded&limit=$limit&offset=$offset")
            ?: return@withContext Result.failure(Exception("无法连接 clawhub.ai（网络不可用或超时）"))
        if (resp.code !in 200..299) {
            return@withContext Result.failure(Exception("搜索失败：HTTP ${resp.code}"))
        }
        val bodyText = resp.body
            ?: return@withContext Result.failure(Exception("搜索响应为空或超过大小上限（2MB）"))
        try {
            val root = json.parseToJsonElement(bodyText).jsonObject
            val raw = root["results"]?.jsonArray ?: emptyList()
            val entries = raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseSearchItem) }
            Result.success(ClawHubPage(entries, raw.size >= limit))
        } catch (e: Exception) {
            Result.failure(Exception("搜索结果解析失败: ${e.message}"))
        }
    }

    /**
     * 下载技能 ZIP（含 references/scripts 等资源），返回字节内容。
     * 上限 [MAX_ZIP_BYTES]（20MB，与本地 ZIP 导入分支一致）。
     */
    suspend fun downloadSkillZip(entry: ClawHubSkillEntry): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/download" +
                "?slug=${URLEncoder.encode(entry.slug, "UTF-8")}" +
                "&ownerHandle=${URLEncoder.encode(entry.owner, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/zip")
                .build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.failure(Exception("HTTP ${resp.code}"))
                    }
                    val body = resp.body ?: return@use Result.failure(Exception("空响应"))
                    val bytes = body.byteStream().use { it.readBytesLimited(MAX_ZIP_BYTES) }
                        ?: return@use Result.failure(
                            Exception("安装包过大（超过 ${MAX_ZIP_BYTES / 1024 / 1024}MB）")
                        )
                    Result.success(bytes)
                }
            } catch (e: Exception) {
                // 无挂起点，不会吞 CancellationException；意外异常一律转 Result
                Result.failure(Exception("下载失败: ${e.message}"))
            }
        }

    // ── 内部实现 ──

    /** 热门条目解析（注意：混入的 skills.sh 外部源条目会被过滤——下载端点只认原生条目）。 */
    private fun parseTrendingItem(obj: JsonObject): ClawHubSkillEntry? {
        val slug = obj.str("slug")?.takeIf { it.isNotBlank() } ?: return null
        val install = obj["install"] as? JsonObject
        if (install?.str("kind") != "clawhub") return null
        val reference = install.str("reference")
        val owner = (obj["sourceIdentity"] as? JsonObject).str("owner")
            ?: reference?.takeIf { it.contains('/') }?.substringBefore('/')
            ?: return null
        val metrics = obj["metrics"] as? JsonObject
        val publisher = obj["publisher"] as? JsonObject
        return ClawHubSkillEntry(
            slug = slug,
            owner = owner,
            displayName = obj.str("displayName")?.takeIf { it.isNotBlank() } ?: slug,
            summary = obj.str("summary").orEmpty(),
            downloads = metrics.long("lifetimeInstalls")
                ?: metrics.long("trending24hDownloads") ?: 0,
            featured = obj.bool("featured") ?: false,
            official = obj.bool("official") ?: publisher.bool("official") ?: false,
            avatarUrl = publisher.str("image"),
            ownerDisplayName = publisher.str("displayName").orEmpty(),
            updatedAt = metrics.long("updatedAt") ?: 0
        )
    }

    /** 搜索条目解析（owner 优先取 native.ownerHandle，缺失时拆 install.reference）。 */
    private fun parseSearchItem(obj: JsonObject): ClawHubSkillEntry? {
        val slug = obj.str("slug")?.takeIf { it.isNotBlank() } ?: return null
        val install = obj["install"] as? JsonObject
        val reference = install?.str("reference")
        val native = obj["native"] as? JsonObject
        val nativeOwner = native?.get("owner") as? JsonObject
        val nativeSkill = native?.get("skill") as? JsonObject
        val stats = nativeSkill?.get("stats") as? JsonObject
        val owner = native.str("ownerHandle")
            ?: obj.str("ownerHandle")
            ?: reference?.takeIf { it.contains('/') }?.substringBefore('/')
            ?: return null
        return ClawHubSkillEntry(
            slug = slug,
            owner = owner,
            displayName = obj.str("displayName")?.takeIf { it.isNotBlank() }
                ?: nativeSkill.str("displayName")?.takeIf { it.isNotBlank() } ?: slug,
            summary = obj.str("summary")
                ?: nativeSkill.str("summary") ?: "",
            downloads = obj.long("downloads") ?: stats.long("downloads") ?: 0,
            stars = stats.long("stars") ?: 0,
            featured = obj.bool("featured") ?: false,
            official = obj.bool("official")
                ?: (obj["publisher"] as? JsonObject).bool("official") ?: false,
            categories = (nativeSkill?.get("categories") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            avatarUrl = nativeOwner.str("image"),
            ownerDisplayName = nativeOwner.str("displayName")
                ?: (obj["publisher"] as? JsonObject).str("displayName").orEmpty(),
            updatedAt = obj.long("updatedAt") ?: 0
        )
    }

    /** JSON GET（2MB 上限；IO 异常/超时返回 null，由调用方转 Result.failure）。 */
    private fun fetchJson(url: String): HttpReply? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.byteStream()?.use { stream ->
                    stream.readBytesLimited(MAX_JSON_BYTES)?.toString(Charsets.UTF_8)
                }
                HttpReply(resp.code, body)
            }
        } catch (e: Exception) {
            // 网络不可达 / 超时 / 意外运行时异常统一按失败处理（无挂起点，不吞取消）
            null
        }
    }

    /** HTTP 响应轻量包装：状态码 + 文本响应体（body 为 null 表示获取失败或超限）。 */
    private class HttpReply(val code: Int, val body: String?)

    /** 读取至多 [max] 字节；超限返回 null（防御恶意超大响应 OOM）。 */
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
        // 读满后仍有剩余字节 → 超限
        if (total >= max && read() >= 0) return null
        return out.toByteArray()
    }

    // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回 null
    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.long(key: String): Long? =
        (this?.get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    companion object {
        private const val BASE_URL = "https://clawhub.ai/api/v1"
        private const val USER_AGENT = "ApexAgent/1.0"

        /** 单页条目数（搜索分页；服务端 offset 实测暂不生效，见 [TRENDING_PAGE_SIZE] 注释）。 */
        const val DEFAULT_PAGE_SIZE = 30

        /**
         * 热门列表单页条目数。trending 端点 offset 实测被服务端忽略（返回重复页），
         * 因此首次拉取直接取大页（limit 生效）；加载更多遇到全重复页会自动收起（VM 去重后 0 新条目 → hasMore=false）。
         */
        const val TRENDING_PAGE_SIZE = 60

        /** JSON 列表响应上限 2MB（与 MarketInstallManager 下载上限一致）。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        /** 技能 ZIP 上限 20MB（技能包可带 references/scripts 资源）。 */
        private const val MAX_ZIP_BYTES = 20 * 1024 * 1024

        /**
         * slug → 合法本地 skill id（"ch-" 前缀）。
         * [com.apex.agent.core.tools.skill.SkillRegistry] 只允许 [A-Za-z0-9_.-]，
         * 禁止 `..`、前导点与路径分隔符——非法字符替换为 '-'，连续点折叠。
         */
        fun installIdFor(slug: String): String {
            val cleaned = buildString {
                for (c in slug) {
                    append(
                        when {
                            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                                c == '_' || c == '-' || c == '.' -> c
                            else -> '-'
                        }
                    )
                }
            }
                .replace(Regex("\\.{2,}"), ".")
                .take(120)   // 先截断再去尾部点，避免截断产生的尾点破坏 id 正则
                .trim('.')
            return "ch-" + cleaned.ifBlank { "skill" }
        }
    }
}
