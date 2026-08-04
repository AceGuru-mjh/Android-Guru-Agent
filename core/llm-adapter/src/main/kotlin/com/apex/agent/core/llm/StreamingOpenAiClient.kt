package com.apex.agent.core.llm

import kotlinx.coroutines.Dispatchers
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
        val responseBody = response.body?.string() ?: throw LlmException("Empty response")
        
        if (!response.isSuccessful) {
            throw LlmException("API error ${response.code}: $responseBody")
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
        
        val response = httpClient.newCall(request).await()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw LlmException("API error ${response.code}: $errorBody")
        }
        
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
        
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (!currentLine.startsWith("data: ")) continue
                
                val data = currentLine.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    emit(LlmStreamChunk(isFinish = true))
                    break
                }
                
                parseStreamChunk(data)?.let { emit(it) }
            }
        } finally {
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
        return buildJsonObject {
            put("model", config.model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", stream)

            // 模型原生思考强度（OpenAI o-series 的 reasoning_effort；
            // DeepSeek-R1 / Qwen3-thinking 等也兼容此字段）
            config.reasoningEffort.apiValue?.let { effort ->
                put("reasoning_effort", effort)
                // MAX 模式下提高 max_completion_tokens 上限，让思考链有空间
                if (config.reasoningEffort == ReasoningEffort.MAX) {
                    put("max_completion_tokens", maxOf(maxTokens, 8192))
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
            
            if (tools.isNotEmpty()) {
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
                put("tool_choice", "auto")
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
            
            // 流式tool_calls
            val toolCalls = mutableListOf<ToolCall>()
            delta?.get("tool_calls")?.jsonArray?.forEach { tc ->
                val tcObj = tc.jsonObject
                val func = tcObj["function"]?.jsonObject
                toolCalls.add(ToolCall(
                    id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                    name = func?.get("name")?.jsonPrimitive?.content ?: "",
                    arguments = func?.get("arguments")?.jsonPrimitive?.content ?: ""
                ))
            }
            
            LlmStreamChunk(
                content = content,
                toolCalls = toolCalls,
                isFinish = finishReason == "stop" || finishReason == "tool_calls"
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseNonStreamResponse(body: String): LlmResponse {
        val json = Json.parseToJsonElement(body).jsonObject
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
            Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0
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
                    cont.resumeWithException(e)
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}

class LlmException(message: String) : Exception(message)
