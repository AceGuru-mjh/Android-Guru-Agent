package com.apex.agent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 流式LLM客户端
 * 使用SSE (Server-Sent Events) 实现真正的流式输出 
 */
class StreamingOpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val httpClient: OkHttpClient
) : LlmClient {

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        // 非流式：直接POST等待完整响应
        val body = buildRequestBody(messages, tools, temperature, maxTokens, stream = false)
        
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).await()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        
        return parseNonStreamResponse(responseBody)
    }

    /**
     * 流式调用：逐token返回
     * 实现原理：SSE (text/event-stream) 
     * 每个chunk格式：data: {"choices":[{"delta":{"content":"..."}}]}
     */
    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        val body = buildRequestBody(messages, tools, temperature, maxTokens, stream = true)
        
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).await()
        
        if (!response.isSuccessful) {
            throw Exception("LLM API error: ${response.code} ${response.body?.string()}")
        }
        
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        var line: String?
        
        try {
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                
                // SSE格式：以"data: "开头
                if (!currentLine.startsWith("data: ")) continue
                
                val data = currentLine.removePrefix("data: ").trim()
                
                // 流结束标记
                if (data == "[DONE]") {
                    emit(LlmStreamChunk(isFinish = true))
                    break
                }
                
                // 解析chunk
                val chunk = parseStreamChunk(data)
                if (chunk != null) {
                    emit(chunk)
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    // ═══ 内部方法 ═══
    
    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        stream: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", stream)
            
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
                                put("content", msg.content)
                            }
                            is LlmMessage.Assistant -> {
                                put("role", "assistant")
                                if (msg.content.isNotEmpty()) {
                                    put("content", msg.content)
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
            val choice = choices.firstOrNull()?.jsonObject ?: return null
            val delta = choice["delta"]?.jsonObject ?: return null
            
            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            
            // 处理tool_calls的流式返回
            val toolCalls = delta["tool_calls"]?.jsonArray?.mapNotNull { tc ->
                val tcObj = tc.jsonObject
                val func = tcObj["function"]?.jsonObject ?: return@mapNotNull null
                ToolCall(
                    id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                    name = func["name"]?.jsonPrimitive?.content ?: "",
                    arguments = func["arguments"]?.jsonPrimitive?.content ?: ""
                )
            } ?: emptyList()
            
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
        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull
        val toolCalls = message?.get("tool_calls")?.jsonArray?.map { tc ->
            val tcObj = tc.jsonObject
            ToolCall(
                id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                name = tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
            )
        } ?: emptyList()
        
        return LlmResponse(content = content, toolCalls = toolCalls)
    }
    
    // OkHttp suspend扩展
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
            override fun onFailure(call: Call, e: java.io.IOException) {
                cont.resumeWithException(e)
            }
        })
        invokeOnCancellation { cancel() }
    }
}
