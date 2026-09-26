package com.apex.agent.core.tools.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 搜索供应商抽象—— 4-d 框架的核心接口。
 *
 * 借鉴 RikkaHub 的 SearchService（19 家供应商统一到一个接口 + 每家自带
 * 参数 schema），但做纯 JVM 化裁剪：去掉 Composable 描述 UI 与 scrape
 * 通道，只保留 search；配置不进接口（RikkaHub 把 apiKey 揉进 sealed
 * options，本框架改为调用时传入 [SearchProviderConfig]，注册表才能
 * 在不改供应商实现的前提下热更新 key / 优先级 / 开关）。
 *
 * 实现纪律（防御式 IO）：
 * 1. [search] 永不抛异常（CancellationException 除外），一切失败折叠为
 *    [SearchResponse.error]；
 * 2. 解析逻辑抽成 internal 纯函数（parseXxxResponse），用固定 JSON /
 *    HTML 夹具做单元测试，不依赖真实网络。
 */
interface SearchProvider {
    /** 供应商唯一 id（同时是缓存 / 限流的桶键）。 */
    val id: String

    /** 展示名（设置页 / 日志用）。 */
    val displayName: String

    /** 是否必须配置 API key（true 且 key 为空时注册表直接跳过该供应商）。 */
    val requiresApiKey: Boolean

    /** 是否支持域名过滤 / 时间范围 / 语言等高级参数（不支持则静默忽略）。 */
    val supportsAdvancedParams: Boolean

    /**
     * 是否为"兜底爬虫"供应商（DDG / Bing HTML 抓取）。
     *
     * 注册表的调度分两相：先按优先级跑 API 供应商，全失败后才落到
     * 兜底爬虫相。默认 false，只有免 key 的 HTML 抓取实现覆盖为 true。
     */
    val isScrapeFallback: Boolean
        get() = false

    /**
     * 执行一次搜索。契约：失败必须折叠进 [SearchResponse.error] 返回，
     * 不得抛异常；协程取消（CancellationException）必须原样上抛。
     */
    suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse
}

/**
 * HTTP 状态码语义化异常：executeJson 对非 2xx 响应产出该异常，
 * [toSearchProviderError] 据此还原状态码并推导 retryable。
 */
class SearchProviderHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

/**
 * 基于 OkHttp 的供应商公共基类：统一请求执行 + 错误折叠 + 计时帮助。
 */
abstract class HttpSearchProvider(
    protected val client: OkHttpClient
) : SearchProvider {

    /**
     * 执行请求并取回响应体字符串。
     *
     * - 可取消：awaitOk 基于 enqueue + suspendCancellableCoroutine，
     *   协程取消时同步 call.cancel()（与 WebTools.kt 的 awaitOk 同源，
     *   那份是文件私有无法复用，这里独立维护一份）；
     * - 非 2xx：折叠为 [SearchProviderHttpException] 失败（携带状态码）；
     * - 网络异常：折叠为失败；CancellationException 原样上抛。
     */
    protected suspend fun executeJson(request: Request): Result<String> = try {
        val response = client.newCall(request).awaitSearchCall()
        response.use {
            if (it.isSuccessful) {
                Result.success(it.body?.string() ?: "")
            } else {
                Result.failure(
                    SearchProviderHttpException(it.code, "HTTP ${it.code} ${it.message}")
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 统一的失败响应（items 为空 + error 填充）。 */
    protected fun failureResponse(query: SearchQuery, error: SearchProviderError): SearchResponse =
        SearchResponse(
            query = query,
            providerId = id,
            items = emptyList(),
            tookMs = 0L,
            cached = false,
            error = error
        )

    /** 便捷：本地配置类错误（空 query / 缺 baseUrl）。 */
    protected fun configError(query: SearchQuery, message: String): SearchResponse =
        failureResponse(
            query,
            SearchProviderError(SearchProviderError.CODE_CONFIG, message, retryable = false)
        )

    /** 便捷：供应商要求 key 但配置为空（未发起请求）。 */
    protected fun authMissingError(query: SearchQuery): SearchResponse =
        failureResponse(
            query,
            SearchProviderError(
                SearchProviderError.CODE_AUTH_MISSING,
                "$displayName requires an API key (set it in provider config)",
                retryable = false
            )
        )

    /** 计时帮助：毫秒耗时。 */
    protected fun elapsedSince(startedAtMs: Long): Long =
        System.currentTimeMillis() - startedAtMs
}

// ═════════════════════════════════════════════════════════════════
// 文件私有：可取消的 OkHttp Call await（与 WebTools.kt 同模式）
// ═════════════════════════════════════════════════════════════════

private suspend fun Call.awaitSearchCall(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                // 取消发生在响应到达之后：没人消费这个响应，直接回收连接
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
            // 已取消时的失败（通常是 call.cancel() 触发的）是预期噪音，忽略
        }
    })
    cont.invokeOnCancellation { runCatching { this@awaitSearchCall.cancel() } }
}

// ═════════════════════════════════════════════════════════════════
// 包内共享：宽松 JSON 解析帮助（全部防御式，任何形状吞得下）
// ═════════════════════════════════════════════════════════════════

/** 宽松 JSON：未知键忽略、引号宽松、null 视作默认值。 */
internal val searchJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** 解析顶层对象；任何解析失败 / 非对象形状都折叠为 null。 */
internal fun parseJsonObject(body: String): kotlinx.serialization.json.JsonObject? = try {
    searchJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
} catch (e: Exception) {
    null
}

/** 取字符串字段（JsonNull / 缺失 / 非原始值均返回 null）。 */
internal fun kotlinx.serialization.json.JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

/** 取双精度字段（用于 score 等相关性分）。 */
internal fun kotlinx.serialization.json.JsonObject.doubleField(name: String): Double? =
    (this[name] as? JsonPrimitive)?.doubleOrNull

/** 取整型字段。 */
internal fun kotlinx.serialization.json.JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

/** 取数组字段（缺失 / 非数组返回 null）。 */
internal fun kotlinx.serialization.json.JsonObject.arrayField(name: String): JsonArray? =
    this[name] as? JsonArray

/**
 * 异常到供应商错误的统一映射：
 * - [SearchProviderHttpException] 还原 HTTP 状态码（429 / 5xx 可重试）；
 * - 其余（IOException / DNS / 超时）按网络错误处理，标记可重试。
 */
internal fun Throwable.toSearchProviderError(): SearchProviderError = when (this) {
    is SearchProviderHttpException -> SearchProviderError.http(statusCode, message ?: "HTTP $statusCode")
    else -> SearchProviderError.network(
        "${this::class.simpleName ?: "Exception"}: ${message ?: "no message"}"
    )
}

/** 搜索框架默认共享客户端（真实部署建议注入全局单例客户端共享连接池）。 */
fun defaultSearchClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()
