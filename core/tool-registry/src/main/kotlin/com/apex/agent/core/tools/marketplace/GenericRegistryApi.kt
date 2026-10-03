package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * ═══ 通用 MCP Registry API 客户端（Generic Registry 规格）═══
 *
 * 官方 MCP Registry（registry.modelcontextprotocol.io，9000+ 服务器）与
 * PulseMCP Sub-Registry（api.pulsemcp.com）实现同一份 Generic Registry API
 * 规格（modelcontextprotocol/registry 的 generic-registry-api.md）——本类
 * 是两者的共享客户端：
 *
 * - **官方源**：无认证，`GET {base}/servers?cursor=&limit=&search=&version=latest`；
 * - **PulseMCP 源**：同一形状 + `X-API-Key` / `X-Tenant-ID` 请求头（合伙人
 *   制 API，凭据由宿主注入 provider）。
 *
 * 响应形状（字段 camelCase，server 元数据嵌套在 `servers[].server`）：
 * `{"servers":[{"server":{"name","title","description","repository",
 * "version","packages":[...],"remotes":[...]}}],"metadata":{"nextCursor"}}`。
 *
 * 安装决策（[RegistryServer.installKind]，决策树的单源实现）：
 * - **远端优先**：`remotes[]` 有 streamable-http / sse 端点 → 直装
 *   HTTP/SSE 配置（无需沙箱，Android 宿主网络直连）；
 * - **npm stdio 次之**：`packages[]` 里有 `registryType=npm` 且
 *   `transport.type=stdio` 的包 → 生成 `npx -y {identifier}` 沙箱 STDIO
 *   配置（安装器负责先在 PRoot Ubuntu 里真实 `npm install -g` 预热，
 *   免去 npx 冷启动撞 180s 握手超时）；
 * - **其余形态**（pypi / mcpb / oci / nuget / 非 stdio npm 包）→ 明确
 *   UNSUPPORTED，UI 引导按仓库 README 手动安装，不静默装残配置。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；网络调用
 * `withContext(Dispatchers.IO)`，响应体 2MB 上限，任何失败
 * `Result.failure` 不抛异常（与 [HubSource]/[McpSoSource] 错误契约一致）。
 */
class GenericRegistryApi(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    /** 每请求附加头（PulseMCP 凭据等；返回空 map = 无附加头）。 */
    private val extraHeaders: () -> Map<String, String> = { emptyMap() }
) {

    /**
     * 拉取目录一页。[cursor] 来自上页 [RegistryPage.nextCursor]（null =
     * 首页）；[search] 非空走服务端搜索（同时支持分页）；始终带
     * `version=latest`（否则同一服务器会按版本重复出现）。
     */
    suspend fun listServers(
        cursor: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        search: String? = null
    ): Result<RegistryPage> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/servers?version=latest&limit=")
            append(limit.coerceIn(1, MAX_PAGE_LIMIT))
            cursor?.takeIf { it.isNotBlank() }?.let { c ->
                append("&cursor=")
                append(URLEncoder.encode(c, "UTF-8"))
            }
            search?.takeIf { it.isNotBlank() }?.let { q ->
                append("&search=")
                append(URLEncoder.encode(q, "UTF-8"))
            }
        }
        val resp = fetchJsonText(url)
            ?: return@withContext Result.failure(Exception(unavailableMessage()))
        if (resp.code !in 200..299) {
            return@withContext Result.failure(
                Exception(httpErrorMessage(resp.code, resp.body))
            )
        }
        val body = resp.body
            ?: return@withContext Result.failure(
                Exception("Registry 目录响应为空或超过大小上限（2MB）")
            )
        parseServerList(body)
    }

    // ── 内部实现 ──

    /** JSON GET（2MB 上限；IO 异常/超时折为 null → 统一 Result.failure）。 */
    private fun fetchJsonText(url: String): HttpReply? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .apply {
                for ((k, v) in extraHeaders()) {
                    if (k.isNotBlank() && v.isNotBlank()) header(k, v)
                }
            }
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.byteStream()?.use { stream ->
                    stream.readBytesLimited(MAX_JSON_BYTES)?.toString(Charsets.UTF_8)
                }
                HttpReply(resp.code, body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private class HttpReply(val code: Int, val body: String?)

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
        "无法连接 Registry 目录（网络不可用或超时）"

    /** 带 body 尾部的 HTTP 错误（PulseMCP 的 401 详情在 body 里，直接透出）。 */
    private fun httpErrorMessage(code: Int, body: String?): String {
        val detail = body?.take(200)?.replace('\n', ' ')?.trim().orEmpty()
        return if (detail.isNotEmpty()) {
            "Registry 目录获取失败：HTTP $code（$detail）"
        } else {
            "Registry 目录获取失败：HTTP $code"
        }
    }

    companion object {
        private const val USER_AGENT = "ApexAgent/1.0 (MCP registry client)"

        /** JSON 响应上限 2MB（与 HubSource / McpSoSource 一致）。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        /** 默认页大小（移动端列表折中；服务端硬上限 100）。 */
        const val DEFAULT_PAGE_SIZE = 30

        private const val MAX_PAGE_LIMIT = 100

        private val json = Json { ignoreUnknownKeys = true }

        // ── 纯解析（public：单测直接喋真实响应切片；无状态无副作用）──

        /** 目录响应文本 → [RegistryPage]（坏条目跳过，整体损坏转 failure）。 */
        fun parseServerList(body: String): Result<RegistryPage> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val raw = root["servers"]?.jsonArray ?: emptyList()
            val servers = raw.mapNotNull { el ->
                ((el as? JsonObject)?.get("server") as? JsonObject)?.let(::parseServer)
            }.distinctBy { it.name }
            val nextCursor = ((root["metadata"] as? JsonObject)
                ?.get("nextCursor") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            Result.success(RegistryPage(servers, nextCursor))
        } catch (e: Exception) {
            Result.failure(Exception("Registry 目录解析失败: ${e.message}"))
        }

        private fun parseServer(obj: JsonObject): RegistryServer? {
            val name = obj.strOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
            val remotes = obj.optArray("remotes")?.mapNotNull { el ->
                (el as? JsonObject)?.let(::parseRemote)
            }.orEmpty()
            val packages = obj.optArray("packages")?.mapNotNull { el ->
                (el as? JsonObject)?.let(::parsePackage)
            }.orEmpty()
            return RegistryServer(
                name = name,
                displayName = obj.strOrNull("title")?.takeIf { it.isNotBlank() }
                    ?: name.substringAfterLast('/'),
                description = obj.strOrNull("description").orEmpty(),
                repositoryUrl = (obj["repository"] as? JsonObject)
                    ?.strOrNull("url").orEmpty(),
                version = obj.strOrNull("version").orEmpty(),
                remotes = remotes,
                packages = packages
            )
        }

        private fun parseRemote(obj: JsonObject): RegistryRemote? {
            val url = obj.strOrNull("url")?.takeIf { it.isNotBlank() } ?: return null
            val type = obj.strOrNull("type").orEmpty().lowercase()
            val headers = obj.optArray("headers")?.mapNotNull { el ->
                (el as? JsonObject)?.let { h ->
                    val headerName = h.strOrNull("name")?.takeIf { it.isNotBlank() }
                        ?: return@let null
                    headerName to h.strOrNull("value").orEmpty()
                }
            }.orEmpty().toMap()
            return RegistryRemote(
                url = url,
                isSse = type == "sse",
                headers = headers
            )
        }

        private fun parsePackage(obj: JsonObject): RegistryPackage? {
            val identifier = obj.strOrNull("identifier")?.takeIf { it.isNotBlank() }
                ?: return null
            val envVars = obj.optArray("environmentVariables")?.mapNotNull { el ->
                (el as? JsonObject)?.strOrNull("name")?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val requiredEnvVars = obj.optArray("environmentVariables")?.mapNotNull { el ->
                val envObj = el as? JsonObject ?: return@mapNotNull null
                val envName = envObj.strOrNull("name")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                // 官方 schema 键为 isRequired（boolean；字符串 "true" 也宽容接受）
                val required = (envObj.get("isRequired") as? JsonPrimitive)?.booleanOrNull
                    ?: false
                if (required) envName else null
            }.orEmpty()
            return RegistryPackage(
                registryType = obj.strOrNull("registryType").orEmpty().lowercase(),
                identifier = identifier,
                version = obj.strOrNull("version").orEmpty(),
                runtimeHint = obj.strOrNull("runtimeHint"),
                isStdioTransport = (obj["transport"] as? JsonObject)
                    ?.strOrNull("type")?.equals("stdio", ignoreCase = true) ?: true,
                fileSha256 = obj.strOrNull("fileSha256"),
                envVarNames = envVars,
                requiredEnvVarNames = requiredEnvVars
            )
        }

        // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
        private fun JsonObject?.strOrNull(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.optArray(key: String): JsonArray? =
            this.get(key) as? JsonArray
    }
}

/** Registry 目录页（一页条目 + 下一页游标；nextCursor=null 即末页）。 */
data class RegistryPage(
    val servers: List<RegistryServer>,
    val nextCursor: String?
)

/** Registry 远端端点（server.json 的 `remotes[]` 元素）。 */
data class RegistryRemote(
    val url: String,
    /** true = SSE 传输（false = streamable-http，首选）。 */
    val isSse: Boolean,
    /** 请求头模板（值可能是 `Bearer {smithery_api_key}` 这类占位符，装后须编辑补齐）。 */
    val headers: Map<String, String> = emptyMap()
)

/** Registry 发行包（server.json 的 `packages[]` 元素）。 */
data class RegistryPackage(
    /** "npm" | "pypi" | "mcpb" | "oci" | "nuget" | "cargo"。 */
    val registryType: String,
    /** npm 形态下即包名（如 `@modelcontextprotocol/server-filesystem`）。 */
    val identifier: String,
    val version: String,
    /** 运行时提示（"npx" / "uvx" / "docker"…；npm 包忽略，恒走 npx）。 */
    val runtimeHint: String?,
    /** transport.type 是否 stdio（缺失时按 stdio 宽容处理）。 */
    val isStdioTransport: Boolean,
    /** mcpb 包完整性校验值（v1 暂不消费，保留展示）。 */
    val fileSha256: String?,
    val envVarNames: List<String>,
    val requiredEnvVarNames: List<String>
)

/**
 * Registry 服务器条目 —— 市场目录行与安装决策树的共享模型。
 *
 * `installKind` 是唯一安装入口判定（远端 > npm 沙箱 > 不支持），
 * [toMcpServerConfig] 按判定产出配置（enabled 恒 false —— 安装 ≠ 启动，
 * 与官方 Hub / mcp.so 源口径一致）。
 */
data class RegistryServer(
    /** reverse-DNS 全名（如 `io.github.bytedance/mcp-server-filesystem`）。 */
    val name: String,
    val displayName: String,
    val description: String,
    val repositoryUrl: String,
    val version: String,
    val remotes: List<RegistryRemote>,
    val packages: List<RegistryPackage>
) {
    /** 条目唯一键（列表 key 去重用）。 */
    val key: String get() = "registry/" + name.lowercase()

    /** 写入注册表的配置名（市场徽标 / 防重复装都以该名为键）。 */
    val configName: String get() = displayName.ifBlank { name.substringAfterLast('/') }

    /** 安装形态判定。 */
    enum class InstallKind { REMOTE, NPM, UNSUPPORTED }

    /** 首选远端：streamable-http 优先于 sse（无形态负担，直连即用）。 */
    val bestRemote: RegistryRemote?
        get() = remotes.firstOrNull { !it.isSse } ?: remotes.firstOrNull()

    /** 首个可沙箱安装的包：npm + stdio（其余形态 v1 不装）。 */
    val npmPackage: RegistryPackage?
        get() = packages.firstOrNull { it.registryType == "npm" && it.isStdioTransport }

    /** 非 npm 形态的首个包（UNSUPPORTED 提示用）。 */
    val unsupportedPackage: RegistryPackage?
        get() = packages.firstOrNull { it.registryType != "npm" }

    val installKind: InstallKind
        get() = when {
            bestRemote != null -> InstallKind.REMOTE
            npmPackage != null -> InstallKind.NPM
            else -> InstallKind.UNSUPPORTED
        }

    /**
     * 按安装形态产出 MCP 配置（UNSUPPORTED 返回 null）。
     *
     * - REMOTE：url + 传输映射（sse → SSE，否则 HTTP），headers 原样保留
     *   模板值（占位符需用户在「编辑」里补真值）；
     * - NPM：`npx -y {identifier}` + runInSandbox=true（Android 宿主无
     *   node，必须路由 PRoot 沙箱；安装器先真实 npm install 预热）。
     */
    fun toMcpServerConfig(): McpServerConfig? = when (installKind) {
        InstallKind.REMOTE -> {
            val remote = bestRemote ?: return null
            McpServerConfig(
                name = configName,
                url = remote.url,
                transport = if (remote.isSse) McpTransport.SSE else McpTransport.HTTP,
                enabled = false,
                headers = remote.headers
            )
        }
        InstallKind.NPM -> {
            val pkg = npmPackage ?: return null
            McpServerConfig(
                name = configName,
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", pkg.identifier),
                runInSandbox = true,
                enabled = false
            )
        }
        InstallKind.UNSUPPORTED -> null
    }
}
