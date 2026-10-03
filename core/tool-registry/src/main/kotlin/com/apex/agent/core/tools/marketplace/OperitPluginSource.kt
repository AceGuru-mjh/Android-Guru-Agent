package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * ═══ Operit 社区插件源（GitHub 聚合）═══
 *
 * Operit（AAswordman/Operit）是 Android 端 Agent 框架，社区插件以
 * GitHub 仓库分发（JS/TS 脚本包、ToolPkg、MCP 插件多种形态）。其中
 * **MCP 插件是标准 stdio JSON-RPC 服务器** —— 与本 App 的 MCP 客户端
 * 天然兼容，无需任何转换层即可当普通 MCP server 装（沙箱 npm/npx 启动）。
 *
 * 数据获取（GitHub Search API，token 可选注入提配额）：
 * - **目录**：两个查询合并 —— `topic:operit-plugin`（社区规范标签）
 *   + `operit mcp`（名称/描述命中），按 star 去重排序；单查询失败时
 *   目录降级返回（[OperitDirectory.partialError] 提示可重试），双查询
 *   全失败才整体 failure；
 * - **安装**：拉仓库根的 `mcp_config.json`（Operit 生态的 MCP 插件配置
 *   约定文件名，实测社区仓库如 operit-xhs-reader-mcp 即此形态）→
 *   `mcp.json`（通用惯例）→ `package.json` 的 mcpServers 键 →
 *   [McpConfigImport] 统一解析。默认分支解析失败时用 `HEAD` 符号
 *   引用（raw.githubusercontent 恒解析到默认分支，master/main 通吃）。
 *   未暴露标准 MCP 配置的仓库（纯 ToolPkg / 脚本插件）会得到明确报错
 *   与「浏览器打开」指引，绝不静默装空配置。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；响应体 2MB 上限，
 * 任何失败 `Result.failure` 不抛异常（错误契约同其他源）。
 */
class OperitPluginSource(
    private val httpClient: OkHttpClient,
    /** GitHub token provider（已登录用户走认证限流配额，未登录走匿名配额）。 */
    private val gitHubTokenProvider: () -> String? = { null }
) {

    /** Operit 社区插件条目（列表卡片解析产物）。 */
    data class OperitPlugin(
        val fullName: String,
        val description: String,
        val htmlUrl: String,
        val stars: Int,
        val topics: List<String>,
        /** topics 或名称含 mcp → MCP 插件候选（卡片打标）。 */
        val isMcp: Boolean
    ) {
        /** 条目唯一键。 */
        val key: String get() = "operit/$fullName"

        /** 配置名（owner-repo 扁平化，写入注册表用）。 */
        val configName: String
            get() = fullName.lowercase().replace(Regex("""[^a-z0-9._-]+"""), "-")
    }

    /** 社区插件目录（双查询合并产物 + 部分失败降级提示）。 */
    data class OperitDirectory(
        val plugins: List<OperitPlugin>,
        /** 双查询其一失败（限流/网络抖动）的降级提示；null = 两路都成功。 */
        val partialError: String?
    )

    /**
     * 拉取社区插件目录（双查询合并去重，按 star 降序，最多 [MAX_ENTRIES] 条）。
     * 单查询失败降级返回（[OperitDirectory.partialError] 点名），双失败
     * 才整体 failure。
     */
    suspend fun listPlugins(): Result<OperitDirectory> = withContext(Dispatchers.IO) {
        val topicHits = searchGitHub("topic:operit-plugin")
        val keywordHits = searchGitHub("operit mcp")
        if (topicHits == null && keywordHits == null) {
            return@withContext Result.failure(Exception(unavailableMessage()))
        }
        Result.success(mergeDirectory(topicHits, keywordHits))
    }

    /**
     * 拉取某插件的 MCP 配置并解析为 [McpServerConfig]，探测顺序：
     * `mcp_config.json`（Operit 生态约定文件名）→ `mcp.json`（通用惯例，
     * 接受标准 mcpServers 外壳或裸服务器对象）→ `package.json` 的
     * mcpServers 键（npm 生态惯例位置）→ [McpConfigImport] 统一解析。
     *
     * @param fallbackName 配置 JSON 内条目名缺失时的命名回退（目录卡片名）
     */
    suspend fun fetchMcpConfig(
        plugin: OperitPlugin,
        fallbackName: String = plugin.configName
    ): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        val (owner, repo) = plugin.fullName.split("/").let {
            if (it.size >= 2) it[0] to it[1] else return@withContext Result.failure(
                Exception("仓库地址非法：${plugin.fullName}")
            )
        }
        // 默认分支解析失败（匿名限流/网络）→ HEAD 符号引用（GitHub raw
        // 恒解析到默认分支——不再硬编码 main 造成 master 仓库三连 404）
        val branch = resolveDefaultBranch(owner, repo) ?: "HEAD"

        // 1) mcp_config.json：Operit 生态约定（社区 MCP 插件的标配文件名）
        fetchRawText(rawUrl(owner, repo, branch, "mcp_config.json"))?.let { text ->
            parseMcpConfigText(text, fallbackName)?.let { return@withContext Result.success(it) }
        }
        // 2) mcp.json：标准 mcpServers 外壳，或裸服务器对象（自动包壳）
        fetchRawText(rawUrl(owner, repo, branch, "mcp.json"))?.let { text ->
            parseMcpConfigText(text, fallbackName)?.let { return@withContext Result.success(it) }
        }
        // 3) package.json 的 mcpServers 键（npm 生态惯例位置）
        fetchRawText(rawUrl(owner, repo, branch, "package.json"))?.let { text ->
            packageJsonMcpServersText(text)?.let { serversJson ->
                parseMcpConfigText(serversJson, fallbackName)?.let {
                    return@withContext Result.success(it)
                }
            }
        }
        Result.failure(
            Exception(
                "「${plugin.fullName}」未暴露标准 MCP 配置（mcp_config.json / " +
                    "mcp.json / package.json 的 mcpServers）—— 该插件可能是 ToolPkg 或脚本形态，" +
                    "请到仓库页按说明手动接入"
            )
        )
    }

    // ── 内部实现（网络路径）──

    /** GitHub 仓库搜索；网络/解析/非法 token 失败返回 null（由调用方合并判定）。 */
    private fun searchGitHub(query: String): List<OperitPlugin>? = runCatching {
        val request = Request.Builder()
            .url(
                "https://api.github.com/search/repositories?q=" +
                    URLEncoder.encode(query, "UTF-8") +
                    "&sort=stars&per_page=$SEARCH_PAGE_SIZE"
            )
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .apply { gitHubTokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        parseOperitSearchItems(fetchBodyText(request) ?: return@runCatching null)
    }.getOrNull()

    /** 查仓库真实默认分支；失败返回 null（由调用方回退 HEAD 符号引用）。 */
    private fun resolveDefaultBranch(owner: String, repo: String): String? = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .apply { gitHubTokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        val body = fetchBodyText(request) ?: return@runCatching null
        json.parseToJsonElement(body)
            .jsonObject["default_branch"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun rawUrl(owner: String, repo: String, branch: String, path: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    /** raw 文本下载（2MB 上限；失败/非 2xx 返回 null）。 */
    private fun fetchRawText(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.byteStream()?.use { stream ->
                stream.readBytesLimited(MAX_JSON_BYTES)
            } ?: return@use null
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /** JSON/文本 GET（2MB 上限；失败/非 2xx 返回 null；Request 构造异常同样吞）。 */
    private fun fetchBodyText(request: Request): String? = runCatching {
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.byteStream()?.use { stream ->
                stream.readBytesLimited(MAX_JSON_BYTES)
            } ?: return@use null
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /** 读取至多 [max] 字节；超限返回 null（与 HubSource 同款防御）。 */
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
        "无法连接 GitHub（网络不可用、被限流或超时）"

    companion object {
        private const val USER_AGENT = "ApexAgent/1.0 (Operit market browser)"

        /** JSON 响应上限 2MB。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        /** 单查询页大小（双查询合并后截断到 [MAX_ENTRIES]）。 */
        private const val SEARCH_PAGE_SIZE = 30

        /** 目录上限（双查询合并去重后）。 */
        private const val MAX_ENTRIES = 40

        private val json = Json { ignoreUnknownKeys = true }

        // ── 纯解析与合并（public：单测直接喋真实响应切片；无状态无副作用）──

        /**
         * 双查询合并：去重 → star 降序 → 截断；单路失败给降级提示
         * （null = 该路查询失败——GitHub 匿名限流 10 次/分下真实高频）。
         */
        fun mergeDirectory(
            topicHits: List<OperitPlugin>?,
            keywordHits: List<OperitPlugin>?
        ): OperitDirectory {
            val merged = (topicHits.orEmpty() + keywordHits.orEmpty())
                .distinctBy { it.fullName }
                .sortedByDescending { it.stars }
                .take(MAX_ENTRIES)
            val partialError = if (topicHits == null || keywordHits == null) {
                "部分 GitHub 查询失败（匿名限流或网络抖动）——目录可能不完整，可点重试"
            } else {
                null
            }
            return OperitDirectory(plugins = merged, partialError = partialError)
        }

        /** GitHub search 响应文本 → 插件条目列表（坏条目跳过，畸形输入返回空）。 */
        fun parseOperitSearchItems(body: String): List<OperitPlugin>? = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["items"]?.jsonArray?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val fullName = obj.str("full_name")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val topics = obj.optStrings("topics")
                OperitPlugin(
                    fullName = fullName,
                    description = obj.str("description").orEmpty(),
                    htmlUrl = obj.str("html_url").orEmpty(),
                    stars = obj.str("stargazers_count")?.toIntOrNull() ?: 0,
                    topics = topics,
                    isMcp = topics.any { it.equals("mcp", ignoreCase = true) } ||
                        fullName.contains("mcp", ignoreCase = true)
                )
            }
        }.getOrNull()

        /**
         * mcp.json 文本 → [McpServerConfig]（取第一个有效条目；无可装条目返回 null）。
         *
         * 接受两种形状：标准 `{"mcpServers": {...}}` 外壳，或裸服务器对象
         * （`{"command": "npx", ...}` —— 自动包一层 mcpServers 再走统一解析）。
         * STDIO 条目一律 runInSandbox=true（与 mcp.so 源同口径：Android 宿主
         * 没有 node/python 运行时）。
         */
        fun parseMcpConfigText(text: String, fallbackName: String): McpServerConfig? {
            val normalized = if (text.contains("\"mcpServers\"")) {
                text
            } else {
                "{\"mcpServers\":{\"${escapeKey(fallbackName)}\":$text}}"
            }
            val parsed = McpConfigImport.parse(normalized)
            val first = parsed.configs.firstOrNull() ?: return null
            val shouldSandbox = first.transport == McpTransport.STDIO
            return first.copy(
                name = fallbackName.ifBlank { first.name },
                // 安装 ≠ 启动（与官方 Hub / mcp.so 口径一致）
                enabled = false,
                runInSandbox = if (shouldSandbox) true else first.runInSandbox
            )
        }

        /** package.json 文本 → mcpServers 子对象文本（无该键/损坏返回 null）。 */
        fun packageJsonMcpServersText(text: String): String? = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val servers = root["mcpServers"]?.let { el ->
                runCatching { el.jsonObject }.getOrNull()
            } ?: return null
            "{\"mcpServers\":$servers}"
        }.getOrNull()

        /** mcpServers 键名转义（fallbackName 来自仓库名，理论上已安全，双写防御）。 */
        private fun escapeKey(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
        private fun JsonObject?.str(key: String): String? =
            (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        private fun JsonObject.optStrings(key: String): List<String> =
            (this.get(key) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                .orEmpty()
    }
}
