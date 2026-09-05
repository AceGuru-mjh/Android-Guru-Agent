package com.apex.agent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
/**
 * OpenAI兼容流式客户端
 * 支持任何 /v1/chat/completions 端点
 */
class StreamingOpenAiClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient
) : LlmClient {

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        val body = buildRequestBody(messages, tools, temperature, maxTokens, stream = false)
        
        val request = buildRequest(body)
        val response = httpClient.newCall(request).await()
        val responseBody = response.body?.string() ?: throw LlmException.EmptyResponse()
        
        if (!response.isSuccessful) {
            throw LlmException.Http(response.code, responseBody)
        }
        
        return parseNonStreamResponse(responseBody)
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        val body = buildRequestBody(messages, tools, temperature, maxTokens, stream = true)
        val request = buildRequest(body)

        val call = httpClient.newCall(request)
        val response = call.await()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            response.close()
            throw LlmException.Http(response.code, errorBody)
        }

        val responseBody = response.body ?: run {
            response.close()
            throw LlmException.EmptyBody()
        }
        val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))

        // 修复取消传播：当收集者取消 flow 时，把 OkHttp 响应体关闭，
        // 这样 readLine() 会立即抛出 IOException 而不是阻塞到 readTimeoutMs
        // （旧实现等 await() 返回后便不再监听取消，取消后还要阻塞最多 120s）。
        val cancelHandle = coroutineContext[Job]
            ?.invokeOnCompletion { runCatching { response.close() } }

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // 每行检查一次取消状态（readLine 阻塞期间靠上面的 invokeOnCompletion 兜底）
                coroutineContext.ensureActive()
                val currentLine = line ?: break
                // 兼容 OpenAI SSE 规范允许的两种前缀：`data:` 与 `data: `。
                // 旧实现只认 `data: `（带一个空格），部分代理发 `data:{...}` 会被静默丢弃。
                if (!currentLine.startsWith("data:")) continue

                val data = currentLine.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    emit(LlmStreamChunk(isFinish = true))
                    break
                }

                parseStreamChunk(data)?.let { emit(it) }
            }
        } finally {
            cancelHandle?.dispose()
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    // ═══ 内部方法 ═══
    
    private fun buildRequest(body: JsonObject): Request {
        val builder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        
        // API Key（某些API用Bearer，某些用自定义header）
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        
        // 自定义headers
        config.customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }
        
        return builder.build()
    }
    
    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        stream: Boolean
    ): JsonObject {
        // 客户侧预校验 maxTokens：超出 contextWindow - reservedOutputTokens 的请求
        // 会被服务端以模糊的 HTTP 400 拒绝，用户难以定位。这里提前裁减并保留安全余量。
        val effectiveMaxTokens = if (config.contextWindow > 0) {
            val cap = (config.contextWindow - config.reservedOutputTokens).coerceAtLeast(256)
            maxTokens.coerceAtMost(cap)
        } else {
            maxTokens
        }
        return buildJsonObject {
            put("model", config.model)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            put("stream", stream)

            // ── Sampling 参数（完整开放）─────────────────────────
            if (config.topP != 1.0f) put("top_p", config.topP)
            // T72 §二十二：top_k / min_p / repetition_penalty 为非标准参数，
            // OpenAI/Anthropic 端点会 400 拒绝。仅在已知接受这些参数的 provider
            // 上发送（本地推理 / 自定义兼容端点）。
            val acceptsLocalSampling = config.providerId in LOCAL_SAMPLING_PROVIDERS
            if (config.topK != 0 && acceptsLocalSampling) put("top_k", config.topK)
            if (config.minP != 0.0f && acceptsLocalSampling) put("min_p", config.minP)
            if (config.presencePenalty != 0.0f) put("presence_penalty", config.presencePenalty)
            if (config.frequencyPenalty != 0.0f) put("frequency_penalty", config.frequencyPenalty)
            if (config.repetitionPenalty != 1.0f && acceptsLocalSampling) put("repetition_penalty", config.repetitionPenalty)
            config.seed?.let { put("seed", it) }
            if (config.stopSequences.isNotEmpty()) {
                putJsonArray("stop") { config.stopSequences.forEach { s -> add(s) } }
            }

            // ── Reasoning（原生思考强度 + 思维预算）──────────────
            // T72 §二十二修复：仅当模型声明 reasoning 能力时才发送 reasoning_effort。
            // 旧实现默认 MEDIUM → 对所有端点（含非推理模型）发 "reasoning_effort":"medium"，
            // 部分服务端会 400。
            if (config.capabilities.reasoning) {
                config.reasoningEffort.apiValue?.let { effort ->
                    put("reasoning_effort", effort)
                }
            }
            // 思维预算与 reasoning_effort 不强行绑定：显式设置时以此为准
            val maxCompletion = config.thinkingBudget ?: run {
                if (config.reasoningEffort == ReasoningEffort.MAX && config.capabilities.reasoning) maxOf(maxTokens, 8192) else null
            }
            maxCompletion?.let { put("max_completion_tokens", it) }

            // ── Structured Output ───────────────────────────────
            // T72 §二十二修复：仅当模型声明 structuredOutput 能力时才发送
            // response_format；schema 使用 config.jsonSchema（非空时 parse，
            // 为空回退 {"type":"object"}，兼容旧行为）。
            when (config.structuredOutputMode) {
                StructuredOutputMode.TEXT -> Unit
                StructuredOutputMode.JSON -> {
                    if (config.capabilities.jsonMode || config.capabilities.structuredOutput) {
                        putJsonObject("response_format") {
                            put("type", "json_object")
                        }
                    }
                }
                StructuredOutputMode.JSON_SCHEMA -> {
                    if (config.capabilities.structuredOutput) {
                        putJsonObject("response_format") {
                            put("type", "json_schema")
                            put("strict", config.structuredOutputStrict)
                            putJsonObject("json_schema") {
                                put("name", "structured_output")
                                val schemaEl = config.jsonSchema
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                                if (schemaEl != null) put("schema", schemaEl)
                                else putJsonObject("schema") { put("type", "object") }
                            }
                        }
                    }
                }
            }

            putJsonArray("messages") {
                for (msg in messages) {
                    addJsonObject {
                        when (msg) {
                            is LlmMessage.System -> {
                                put("role", "system")
                                put("content", msg.content)
                            }
                            is LlmMessage.User -> {
                                put("role", "user")
                                if (msg.images.isEmpty()) {
                                    put("content", msg.content)
                                } else {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        }
                                        msg.images.forEach { img ->
                                            addJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put("url", "data:${img.mimeType};base64,${img.base64Data}")
                                                    put("detail", img.detail)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is LlmMessage.Assistant -> {
                                put("role", "assistant")
                                if (msg.content.isNotBlank()) {
                                    put("content", msg.content)
                                } else {
                                    put("content", JsonNull)
                                }
                                if (msg.toolCalls.isNotEmpty()) {
                                    putJsonArray("tool_calls") {
                                        for (tc in msg.toolCalls) {
                                            addJsonObject {
                                                put("id", tc.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", tc.name)
                                                    put("arguments", tc.arguments)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is LlmMessage.ToolResult -> {
                                put("role", "tool")
                                put("tool_call_id", msg.toolCallId)
                                put("content", msg.content)
                            }
                        }
                    }
                }
            }
            
            if (config.enableTools && tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in tools) {
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", Json.parseToJsonElement(tool.parameters))
                            }
                        }
                    }
                }
                put(
                    "tool_choice",
                    when (config.toolChoice) {
                        ToolChoiceMode.AUTO -> "auto"
                        ToolChoiceMode.REQUIRED -> "required"
                        ToolChoiceMode.NONE -> "none"
                    }
                )
                if (config.parallelToolCalls) {
                    put("parallel_tool_calls", true)
                } else {
                    // T72 §二十二修复：旧实现 false 时直接省略键，导致用户无法
                    // 关闭并行工具调用（服务端默认 true）。现在显式发送 false。
                    put("parallel_tool_calls", false)
                }
            }
        }
    }
    
    private fun parseStreamChunk(data: String): LlmStreamChunk? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val choices = json["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) return null

            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull

            // 原生思考内容（DeepSeek-R1 `reasoning_content`、部分 Anthropic 代理 `reasoning`）。
            // 旧实现丢弃，导致思考类模型的思维链在 UI 上不可见。
            val reasoningContent = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull

            // 流式tool_calls
            val toolCalls = mutableListOf<ToolCall>()
            delta?.get("tool_calls")?.jsonArray?.forEach { tc ->
                val tcObj = tc.jsonObject
                val func = tcObj["function"]?.jsonObject
                // 读取 index（并行工具调用必需），默认 -1 表示非流式/不适用。
                val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: -1
                toolCalls.add(ToolCall(
                    id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                    name = func?.get("name")?.jsonPrimitive?.content ?: "",
                    arguments = func?.get("arguments")?.jsonPrimitive?.content ?: "",
                    index = idx
                ))
            }

            LlmStreamChunk(
                content = content,
                toolCalls = toolCalls,
                reasoningContent = reasoningContent,
                isFinish = finishReason == "stop" || finishReason == "tool_calls"
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseNonStreamResponse(body: String): LlmResponse {
        // P2 fix（边界值）：旧实现对非流式响应无防护 —— 代理/网关对 200 返回 HTML 时
        // Json.parseToJsonElement 直接抛 SerializationException；usage 数值返回
        // "1234.0"（float）或字符串时 .int 抛 NumberFormatException。两者都绕过
        // ErrorClassifier 的精确分类。与流式路径（全量 try/catch）防护等级对齐。
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw LlmException.Parse(it)
        }
        val choices = json["choices"]?.jsonArray
        val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull
        val toolCalls = message?.get("tool_calls")?.jsonArray?.map { tc ->
            val tcObj = tc.jsonObject
            ToolCall(
                id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                name = tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
            )
        } ?: emptyList()
        
        val usage = json["usage"]?.jsonObject?.let { u ->
            // intOrNull：容忍代理返回的 "1234.0" / 字符串数值，不再抛 NumberFormatException
            Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
        
        return LlmResponse(content = content, toolCalls = toolCalls, usage = usage)
    }
    
    // OkHttp suspend扩展
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!cont.isCancelled) {
                    // T72：把原始 IOException 包成 [LlmException.Network]，供
                    // 上层 [com.apex.agent.core.llm.runtime.ErrorClassifier] 精确分类。
                    cont.resumeWithException(LlmException.Network(e))
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private companion object {
        /**
         * 已知接受 top_k / min_p / repetition_penalty 等非标准采样参数的 Provider。
         * OpenAI / Anthropic / Google / DeepSeek / OpenRouter 的官方端点不接受这些参数
         * （会返回 400），故仅对本地推理与自定义兼容端点发送。
         */
        val LOCAL_SAMPLING_PROVIDERS: Set<String> = setOf(
            "ollama", "lmstudio", "vllm", "custom_openai"
        )
    }
}

/**
 * T72 §十四 — 适配层异常类型化。
 *
 * 旧实现仅有一个 `class LlmException(message: String)`，上层只能靠字符串匹配
 * 区分"超时/限流/鉴权失败"——脆弱且不可靠。现在拆成 sealed 层级，运行时
 * [com.apex.agent.core.llm.runtime.ErrorClassifier] 据子类型精确映射到
 * [com.apex.agent.core.llm.runtime.ModelRuntimeException]。
 *
 * 向后兼容：仍是 [Exception] 子类，旧的 `catch (e: Exception)` / `catch (e: LlmException)`
 * 仍能捕获（sealed 基类即 [LlmException]）。
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** HTTP 非 2xx 响应。携带状态码 [code] 与响应体 [body]。 */
    class Http(val code: Int, val body: String) : LlmException("API error $code: $body")

    /** 响应体为空（非流式）。 */
    class EmptyResponse : LlmException("Empty response")

    /** 流式响应体为空。 */
    class EmptyBody : LlmException("Empty response body")

    /** 网络层错误（连接失败 / DNS / SocketTimeout 等），包装原始 [IOException]。 */
    class Network(cause: java.io.IOException) : LlmException("Network error: ${cause.message}", cause)

    /** P2 fix：响应体非法（HTML 错误页/畸形 JSON/数值类型漂移），供 ErrorClassifier 映射到
     *  ModelResponseInvalid 而非落入未分类异常。 */
    class Parse(cause: Throwable? = null) : LlmException("Response parse error: ${cause?.message}", cause)
}

