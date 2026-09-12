package com.apex.agent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 端点返回的模型条目。
 *
 * @property id      供 `/v1/chat/completions` 的 `model` 字段使用
 * @property ownedBy 归属方（OpenAI 规范里的 `owned_by`；Ollama/LM Studio 等可能为空）
 */
data class RemoteModelInfo(
    val id: String,
    val ownedBy: String = ""
)

/**
 * OpenAI 兼容端点的「模型列表」拉取器 —— `GET {baseUrl}/models`。
 *
 * 背景：旧实现把模型 ID 做成纯手输文本框，或写死一批"推荐模型"（这些推荐项在多数
 * 中转站根本不存在，是**假的**）。真正可靠的做法是直接问端点自己有什么模型：
 * OpenAI / DeepSeek / OpenRouter / vLLM / LM Studio / Ollama(≥0.5 的 OpenAI 兼容层)
 * 都实现了 `GET /models`。
 *
 * 失败一律返回携带具体原因的 failure（HTTP 状态码 / 非 JSON 响应体片段），
 * 由 UI 原样展示，不折叠成空列表 —— 否则用户只会看到"获取不到模型"而无法定位。
 */
object ModelsCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉取模型列表。
     *
     * @param baseUrl 如 `https://api.openai.com/v1`（结尾斜杠会被规整）
     * @param apiKey  Bearer token；Ollama 等无需鉴权的端点可传空串
     * @param extraHeaders Provider 级自定义请求头
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
        connectTimeoutMs: Long = 15_000L,
        readTimeoutMs: Long = 30_000L,
    ): Result<List<RemoteModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = buildEndpoint(baseUrl)
            if (endpoint.isBlank()) {
                throw IllegalArgumentException("Base URL 为空或以非法字符开头（需 http:// 或 https://）")
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val builder = Request.Builder().url(endpoint).get()
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            builder.addHeader("Accept", "application/json")
            extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) builder.addHeader(k, v) }

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val snippet = body.replace('\n', ' ').trim().take(160)
                    throw IllegalStateException(
                        "HTTP ${response.code}${if (snippet.isBlank()) "" else "：$snippet"}"
                    )
                }
                parseModels(body)
            }
        }
    }

    /** `{baseUrl}` → `{baseUrl}/models`，容忍结尾斜杠与 `/chat/completions` 误粘。 */
    private fun buildEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return ""
        return "$trimmed/models"
    }

    /**
     * 解析 `GET /models` 响应体。容忍三种形状：
     * - OpenAI 规范：`{"object":"list","data":[{"id":"gpt-4o","owned_by":"openai"}]}`
     * - Ollama / 部分网关：`{"models":[{"name":"qwen2.5:7b"}]}` 或顶层直接是数组
     * - LM Studio：`{"data":[{"id":"model","publisher":"..."}]}`
     */
    fun parseModels(body: String): List<RemoteModelInfo> {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw IllegalStateException("响应不是合法 JSON（疑似网关/鉴权拦截页）：${
                body.replace('\n', ' ').trim().take(120)
            }")

        val array = when {
            element is kotlinx.serialization.json.JsonArray -> element
            else -> {
                val obj = element.jsonObject
                obj["data"]?.jsonArray
                    ?: obj["models"]?.jsonArray
                    ?: kotlinx.serialization.json.JsonArray(emptyList())
            }
        }

        return array.mapNotNull { item ->
            val o = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = (o["id"] ?: o["name"] ?: o["model"])?.jsonPrimitive?.content?.trim()
                ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            val ownedBy = (o["owned_by"] ?: o["publisher"] ?: o["ownedBy"])
                ?.jsonPrimitive?.content?.trim().orEmpty()
            RemoteModelInfo(id, ownedBy)
        }.distinctBy { it.id }.sortedBy { it.id }
    }
}
