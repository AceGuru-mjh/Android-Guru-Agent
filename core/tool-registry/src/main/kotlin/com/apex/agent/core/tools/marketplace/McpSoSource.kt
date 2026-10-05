package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * ═══ mcp.so 社区目录源 ═══
 *
 * mcp.so 是目前收录量最大的 MCP 服务器聚合目录（1.5 万+ 服务器，
 * 由各 GitHub 仓库 README 自动提取安装配置）。此前市场唯一的 MCP 目录
 * 是自建 apex-mcp-hub（8 条精选）—— 用户反馈「市场中不会显示 mcp.so 的
 * MCP」，本源补齐社区长尾目录。
 *
 * 数据获取（mcp.so 无公开 JSON API，HTML 路由对非浏览器 UA 也正常返回）：
 * - **目录**：`GET https://mcp.so/servers?page=N`（SSR HTML，每页 60 条卡片，
 *   卡片内嵌名称/作者/描述/分类/热度/徽标，正则解析，无 DOM 依赖）；
 * - **安装配置**：`GET https://mcp.so/servers/{slug}` 详情页的
 *   `<pre><code class="font-mono">` 块 —— 内容正是社区通用
 *   `{"mcpServers": {...}}` JSON，与 [McpConfigImport] 的解析口径完全一致
 *   （README 未暴露标准配置块的服务器会明确报错而不是静默安装空配置）。
 *
 * 纯 JVM（OkHttp + 正则 + kotlinx.serialization），可单测；网络调用
 * `withContext(Dispatchers.IO)`，响应体 2MB 上限，任何失败
 * `Result.failure` 不抛异常（与 [HubSource]/[ClawHubSource] 错误契约一致）。
 */
class McpSoSource(
    private val httpClient: OkHttpClient
) {

    /** 目录排序方式（对应 mcp.so 顶部 select 的 sort 参数）。 */
    enum class Sort(val param: String) {
        POPULAR("popular"),
        LATEST("latest"),
        FEATURED("featured"),
        STAR("star")
    }

    /**
     * mcp.so 目录条目（列表卡片解析产物；安装配置在安装时按需拉详情页）。
     */
    data class McpSoEntry(
        /** 详情页 slug（URL 末段，全局唯一）。 */
        val slug: String,
        val name: String,
        val author: String = "",
        val description: String = "",
        val category: String = "",
        /** 热度原文（"2.5K" / "255"），仅展示用。 */
        val popularity: String = "",
        val verified: Boolean = false,
        val featured: Boolean = false
    ) {
        /** 条目唯一键（列表 key 去重用）。 */
        val key: String get() = "mcpso/$slug"

        /** 详情页地址（浏览器打开 / 安装时拉配置）。 */
        val detailUrl: String get() = "$BASE_URL/servers/$slug"
    }

    /**
     * 拉取目录某一页（60 条/页）。[page] 从 1 起；末页（含翻到整数倍页
     * 边界后的空页）返回 success + 空列表，由调用方据此置 hasMore=false。
     */
    suspend fun listServers(
        page: Int = 1,
        sort: Sort = Sort.POPULAR
    ): Result<List<McpSoEntry>> = withContext(Dispatchers.IO) {
        if (page < 1) return@withContext Result.failure(IllegalArgumentException("page must be >= 1"))
        val url = "$BASE_URL/servers?page=$page&sort=${sort.param}"
        val html = fetchHtml(url)
            ?: return@withContext Result.failure(Exception(unavailableMessage()))
        // P2-2：HTTP 200 但解析出 0 条是合法末页（总条数恰为 60 的整数倍时，
        // 最后一翻会拿到空目录页）——返回空列表交由调用方收起「加载更多」；
        // 连接失败 / 非 2xx 仍走上面的 failure 分支报真错误。
        Result.success(parseServerListHtml(html))
    }

    /**
     * 拉取某台服务器的安装配置并解析为 [McpServerConfig]：
     * 详情页 mcpServers JSON → [McpConfigImport] 统一解析 → STDIO 条目
     * 路由到 PRoot 沙箱（Android 宿主无 node/python 运行时）。
     *
     * @param fallbackName 配置 JSON 内条目名缺失/为空时的命名回退
     */
    suspend fun fetchServerConfig(
        entry: McpSoEntry,
        fallbackName: String = entry.name
    ): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        val html = fetchHtml(entry.detailUrl)
            ?: return@withContext Result.failure(Exception(unavailableMessage()))
        val configJson = extractConfigJson(html)
            ?: return@withContext Result.failure(
                Exception(noConfigMessage(entry.name))
            )
        parseConfigEntry(configJson, fallbackName)
    }

    // ── 内部实现 ──

    /** HTML GET（2MB 上限；IO 异常/超时折为 null → 统一 Result.failure）。 */
    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.byteStream()?.use { stream ->
                    stream.readBytesLimited(MAX_HTML_BYTES)?.toString(Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取至多 [max] 字节；超限返回 null（防御超大响应 OOM，与 HubSource 同款）。 */
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
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

    private fun unavailableMessage(): String =
        "无法连接 mcp.so（网络不可用、被拦截或超时）"

    private fun noConfigMessage(name: String): String =
        "「$name」在 mcp.so 未提供标准 MCP 配置块（README 无 mcpServers JSON）——" +
            "请到详情页按仓库说明手动添加"

    companion object {
        const val BASE_URL = "https://mcp.so"
        private const val USER_AGENT = "ApexAgent/1.0 (MCP market browser)"

        /** HTML 响应上限 2MB（列表页 ~300KB，预留改版余量）。 */
        private const val MAX_HTML_BYTES = 2 * 1024 * 1024

        /** mcp.so 侧边栏分类全集（卡片 footer 的分类 span 匹配用）。 */
        internal val KNOWN_CATEGORIES = setOf(
            "AI & Agents", "Reasoning", "Memory & Knowledge", "Search",
            "Browser Automation", "Data & Analytics", "Developer Tools",
            "Version Control", "Productivity", "Databases",
            "Cloud & Infrastructure", "Files & Storage", "Communication",
            "Media & Design", "Finance & Commerce", "Other"
        )

        // ── 纯解析（public：单测直接喂 HTML 文本；无状态无副作用）──

        private val CARD_PATTERN = Regex(
            """<a\s+href="/servers/([^"?/]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val H3_PATTERN = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
        private val AUTHOR_PATTERN = Regex(
            """<p\s+class="[^"]*truncate[^"]*text-xs[^"]*"[^>]*>(.*?)</p>"""
        )
        private val DESC_PATTERN = Regex(
            """<p\s+class="[^"]*line-clamp-2[^"]*"[^>]*>(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val FOOTER_PATTERN = Regex(
            """<div\s+class="[^"]*mt-auto[^"]*"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val SPAN_PATTERN = Regex("""<span[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        private val POPULARITY_PATTERN = Regex("""^\d[\d.,]*\s*[KkMm]?$""")

        /** 目录页 HTML → 条目列表（坏卡片跳过，畸形输入返回空列表）。 */
        fun parseServerListHtml(html: String): List<McpSoEntry> = CARD_PATTERN
            .findAll(html)
            .mapNotNull { match ->
                val slug = match.groupValues[1]
                val inner = match.groupValues[2]
                val name = stripTags(H3_PATTERN.find(inner)?.groupValues?.get(1) ?: "")
                if (slug.isBlank() || name.isBlank()) return@mapNotNull null
                val author = stripTags(
                    AUTHOR_PATTERN.find(inner)?.groupValues?.get(1) ?: ""
                ).trim()
                val description = normalizeSpace(
                    stripTags(DESC_PATTERN.find(inner)?.groupValues?.get(1) ?: "")
                )
                val footer = FOOTER_PATTERN.find(inner)?.groupValues?.get(1) ?: ""
                var popularity = ""
                var category = ""
                for (spanMatch in SPAN_PATTERN.findAll(footer)) {
                    val spanText = normalizeSpace(stripTags(spanMatch.groupValues[1]))
                    if (spanText.isEmpty()) continue
                    if (spanText in KNOWN_CATEGORIES) {
                        category = spanText
                    } else if (popularity.isEmpty() && POPULARITY_PATTERN.matches(spanText)) {
                        popularity = spanText
                    }
                }
                McpSoEntry(
                    slug = slug,
                    name = name,
                    author = author,
                    description = description,
                    category = category,
                    popularity = popularity,
                    verified = inner.contains("lucide-badge-check"),
                    featured = inner.contains("lucide-sparkles")
                )
            }
            .distinctBy { it.slug }
            .toList()

        /**
         * 详情页 HTML → mcpServers 配置 JSON 文本（找不到标准配置块返回 null）。
         */
        fun extractConfigJson(html: String): String? {
            // 配置块固定在 <pre><code class="font-mono">…</code> 里（"Add this
            // server … configuration below" 段落之后）；页面其他 code 块没有
            // mcpServers 内容。逐块解实体后嗅探 mcpServers 键，避免依赖段落顺序。
            val codePattern = Regex(
                """<code[^>]*>(.*?)</code>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in codePattern.findAll(html)) {
                val candidate = unescapeHtml(match.groupValues[1]).trim()
                if (candidate.contains("\"mcpServers\"")) return candidate
            }
            // 兜底：极少数卡片直接裸放 JSON（无 code 包装）
            val bare = unescapeHtml(html)
            val idx = bare.indexOf("\"mcpServers\"")
            if (idx > 0) {
                val start = bare.lastIndexOf('{', idx)
                if (start >= 0) return extractBalancedJson(bare, start)
            }
            return null
        }

        /**
         * mcpServers JSON 文本 → [McpServerConfig]（取第一个条目；沿用
         * [McpConfigImport] 的判定与逐条报错口径）。
         *
         * 安装策略：**STDIO 一律路由 PRoot 沙箱**（runInSandbox=true）——
         * mcp.so 的 stdio 条目几乎全是 npx/uvx/docker 启动，Android 宿主没有
         * node/python 运行时；远端条目（url/headers）按原样安装。
         */
        fun parseConfigEntry(configJson: String, fallbackName: String): Result<McpServerConfig> {
            val parsed = McpConfigImport.parse(configJson)
            val first = parsed.configs.firstOrNull()
                ?: return Result.failure(
                    Exception(parsed.errors.firstOrNull()?.reason ?: "配置中没有任何有效服务器条目")
                )
            // P2（市场审计）：目录卡名（fallbackName）优先于远端 JSON 键名 ——
            // 市场徽标判定（state.mcps.any { it.name == entry.name }）与防重复装
            // （exists 检查）都以目录名为键；远端键名与目录名常不一致，旧策略
            // （远端优先）导致徽标永不出 + 可重复安装同卡多份。
            val configName = fallbackName.ifBlank { first.name }
            val shouldSandbox = first.transport == McpTransport.STDIO
            return Result.success(
                first.copy(
                    name = configName,
                    // 安装 ≠ 启动（与官方 Hub 仓库口径一致）：enabled 恒 false
                    enabled = false,
                    runInSandbox = if (shouldSandbox) true else first.runInSandbox
                )
            )
        }

        /** 从 [start]（'{' 下标）起做字符串感知的花括号配平，截取完整 JSON。 */
        private fun extractBalancedJson(text: String, start: Int): String? {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString) {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            return null
        }

        /** 去内层标签 + HTML 实体解码（双写防御：mcp.so 对 & 有二次转义）。 */
        internal fun stripTags(raw: String): String =
            unescapeHtml(raw.replace(Regex("""<[^>]*>"""), ""))

        private fun normalizeSpace(text: String): String =
            text.replace(Regex("""\s+"""), " ").trim()

        internal fun unescapeHtml(text: String): String {
            var out = text
            repeat(2) {
                out = out
                    .replace("&quot;", "\"")
                    .replace("&#x27;", "'")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
            }
            return out
        }
    }
}
