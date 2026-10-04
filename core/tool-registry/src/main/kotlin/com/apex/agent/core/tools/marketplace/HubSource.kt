package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ═══ 官方 Hub 仓库源（技能 + MCP 双目录）═══
 *
 * 学习业界标准 CLI 编码智能体的远程注册表模式：`index.json` 只放元数据（小体积、一次拉全），
 * 技能正文按需单文件下载（raw.githubusercontent.com）；MCP 配置极小直接内联
 * 在索引里（一次请求即完整目录）。
 *
 * - **技能仓库**（apex-skill-hub）：62 个生活/通用型 Agent 技能 —— APK 内置
 *   瘦身到 13 个核心后，其余全部迁往该仓库，市场里按需安装；
 * - **MCP 仓库**（apex-mcp-hub）：沙箱（PRoot Ubuntu 内 npx）与远端 MCP
 *   服务器目录 —— 安装（enabled=false）→ 配置 → 启动 的完整闭环走市场。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；所有网络调用
 * `withContext(Dispatchers.IO)`，响应体有大小上限（2MB），任何失败返回
 * [Result.failure]，不向调用方抛异常（与 [ClawHubSource]/[ModelScopeSource]
 * 的错误契约一致）。
 */
class HubSource(
    private val httpClient: OkHttpClient
) {

    // ═══ 技能仓库 ═══

    /** 技能目录条目（index.json 的 `skills[]` 元素）。 */
    data class HubSkillEntry(
        val id: String,
        val name: String,
        val version: String = "1.0.0",
        val description: String = "",
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val scope: String = "agent",
        val author: String = "",
        /** 相对仓库根的 manifest 路径（下载时拼 RAW 基址）。 */
        val file: String
    ) {
        /** 条目唯一键。 */
        val key: String get() = "hub/$id"

        /** 对某工位是否可见（scope=all 双工位都保留，与市场分级口径一致）。 */
        fun visibleToScope(scope: String): Boolean =
            scope.isBlank() || this.scope == "all" || this.scope == scope
    }

    /**
     * 拉取技能仓库目录（`index.json`）。成功返回全部条目，调用方按工位/查询过滤。
     */
    suspend fun listSkills(): Result<List<HubSkillEntry>> = withContext(Dispatchers.IO) {
        val resp = fetchJsonText(SKILL_HUB_INDEX_URL)
            ?: return@withContext Result.failure(Exception(hubUnavailableMessage("skill")))
        if (resp.code !in 200..299) {
            return@withContext Result.failure(Exception("技能仓库目录获取失败：HTTP ${resp.code}"))
        }
        val body = resp.body
            ?: return@withContext Result.failure(Exception("技能仓库目录响应为空或超过大小上限（2MB）"))
        parseSkillIndex(body)
    }

    /**
     * 下载单个技能的完整 manifest JSON 文本（apex-skill-v1），交给
     * MarketInstallManager.installSkillFromJson 走统一安装管道。
     */
    suspend fun downloadSkillManifest(entry: HubSkillEntry): Result<String> =
        withContext(Dispatchers.IO) {
            val url = "$SKILL_HUB_RAW_BASE/${entry.file}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.failure(Exception("HTTP ${resp.code}"))
                    }
                    val body = resp.body ?: return@use Result.failure(Exception("空响应"))
                    val bytes = body.byteStream().use { it.readBytesLimited(MAX_JSON_BYTES) }
                        ?: return@use Result.failure(
                            Exception("技能包过大（超过 ${MAX_JSON_BYTES / 1024 / 1024}MB）")
                        )
                    Result.success(bytes.toString(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Result.failure(Exception("下载失败: ${e.message}"))
            }
        }

    // ═══ MCP 仓库 ═══

    /** MCP 目录条目（index.json 的 `servers[]` 元素，含完整安装配置）。 */
    data class HubMcpEntry(
        val name: String,
        val description: String = "",
        /** "STDIO" | "HTTP" | "SSE"（安装时映射 McpTransport）。 */
        val transport: String = "STDIO",
        val url: String = "",
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val runInSandbox: Boolean = false,
        /** 安装时初始启停态（仓库默认 false —— 安装 ≠ 启动）。 */
        val enabled: Boolean = false,
        val scope: String = "all",
        /** true = 需要 Ubuntu rootfs（终端页安装）—— UI 据此展示引导。 */
        val requiresRootfs: Boolean = false,
        val vendor: String = "",
        val tags: List<String> = emptyList()
    ) {
        /** 对某工位是否可见。 */
        fun visibleToScope(scope: String): Boolean =
            scope.isBlank() || this.scope == "all" || this.scope == scope

        /** 一行端点摘要（与 MarketMcpRow.endpointSummary 同口径）。 */
        fun endpointSummary(): String = when (transport.uppercase()) {
            "STDIO" -> (listOfNotNull(command?.takeIf { it.isNotBlank() }) + args)
                .joinToString(" ").trim().ifEmpty { "(未配置命令)" }
            else -> url.ifBlank { "(未配置 URL)" }
        }
    }

    /**
     * 拉取 MCP 仓库目录（`index.json`，配置内联 —— 单次请求即完整目录）。
     */
    suspend fun listMcpServers(): Result<List<HubMcpEntry>> = withContext(Dispatchers.IO) {
        val resp = fetchJsonText(MCP_HUB_INDEX_URL)
            ?: return@withContext Result.failure(Exception(hubUnavailableMessage("mcp")))
        if (resp.code !in 200..299) {
            return@withContext Result.failure(Exception("MCP 仓库目录获取失败：HTTP ${resp.code}"))
        }
        val body = resp.body
            ?: return@withContext Result.failure(Exception("MCP 仓库目录响应为空或超过大小上限（2MB）"))
        parseMcpIndex(body)
    }

    // ── 内部实现 ──

    /** JSON GET（2MB 上限；IO 异常/超时返回 null，由调用方转 Result.failure）。 */
    private fun fetchJsonText(url: String): HttpReply? {
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
            null
        }
    }

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
        if (total >= max && read() >= 0) return null
        return out.toByteArray()
    }

    // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    private fun JsonObject.strMap(key: String): Map<String, String> {
        val obj = this.get(key) as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }.toMap()
    }

    private fun hubUnavailableMessage(kind: String): String =
        "无法连接官方${if (kind == "mcp") " MCP " else " "}仓库（网络不可用或超时）"

    companion object {
        /** 官方技能仓库（AceGuru-mjh/apex-skill-hub）。 */
        const val SKILL_HUB_REPO = "AceGuru-mjh/apex-skill-hub"

        /** 官方 MCP 仓库（AceGuru-mjh/apex-mcp-hub）。 */
        const val MCP_HUB_REPO = "AceGuru-mjh/apex-mcp-hub"

        private const val SKILL_HUB_RAW_BASE =
            "https://raw.githubusercontent.com/$SKILL_HUB_REPO/main"
        private const val SKILL_HUB_INDEX_URL = "$SKILL_HUB_RAW_BASE/index.json"
        private const val MCP_HUB_INDEX_URL =
            "https://raw.githubusercontent.com/$MCP_HUB_REPO/main/index.json"
        private const val USER_AGENT = "ApexAgent/1.0"

        /** JSON 响应上限 2MB（与 ClawHubSource 一致）。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        // ── 纯解析（public：单测直接喋 index.json 文本；无状态无副作用）──

        /** 技能目录 index.json 文本 → 条目列表（坏条目跳过，整体损坏转 failure）。 */
        fun parseSkillIndex(body: String): Result<List<HubSkillEntry>> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val raw = root["skills"]?.jsonArray ?: emptyList()
            Result.success(raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseSkillEntry) })
        } catch (e: Exception) {
            Result.failure(Exception("技能仓库目录解析失败: ${e.message}"))
        }

        /** MCP 目录 index.json 文本 → 条目列表（坏条目跳过，整体损坏转 failure）。 */
        fun parseMcpIndex(body: String): Result<List<HubMcpEntry>> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val raw = root["servers"]?.jsonArray ?: emptyList()
            Result.success(raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseMcpEntry) })
        } catch (e: Exception) {
            Result.failure(Exception("MCP 仓库目录解析失败: ${e.message}"))
        }

        private fun parseSkillEntry(obj: JsonObject): HubSkillEntry? {
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val file = obj.str("file")?.takeIf { it.isNotBlank() } ?: return null
            return HubSkillEntry(
                id = id,
                name = obj.str("name")?.takeIf { it.isNotBlank() } ?: id,
                version = obj.str("version") ?: "1.0.0",
                description = obj.str("description").orEmpty(),
                category = obj.str("category"),
                tags = obj.strings("tags"),
                scope = obj.str("scope")?.takeIf { it.isNotBlank() } ?: "agent",
                author = obj.str("author").orEmpty(),
                file = file
            )
        }

        private fun parseMcpEntry(obj: JsonObject): HubMcpEntry? {
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: return null
            return HubMcpEntry(
                name = name,
                description = obj.str("description").orEmpty(),
                transport = obj.str("transport")?.takeIf { it.isNotBlank() } ?: "STDIO",
                url = obj.str("url").orEmpty(),
                command = obj.str("command"),
                args = obj.strings("args"),
                env = obj.strMap("env"),
                runInSandbox = obj.bool("runInSandbox") ?: false,
                enabled = obj.bool("enabled") ?: false,
                scope = obj.str("scope")?.takeIf { it.isNotBlank() } ?: "all",
                requiresRootfs = obj.bool("requiresRootfs") ?: false,
                vendor = obj.str("vendor").orEmpty(),
                tags = obj.strings("tags")
            )
        }

        // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
        private fun JsonObject?.str(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject?.bool(key: String): Boolean? =
            (this?.get(key) as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.strings(key: String): List<String> =
            (this.get(key) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()

        private fun JsonObject.strMap(key: String): Map<String, String> {
            val obj = this.get(key) as? JsonObject ?: return emptyMap()
            return obj.entries.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }.toMap()
        }
    }
}
