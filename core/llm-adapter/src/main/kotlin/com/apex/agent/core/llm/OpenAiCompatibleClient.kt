package com.apex.agent.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI兼容API客户端
 * 支持: OpenAI, Claude(通过proxy), Gemini(通过proxy), 
 *       Ollama, vLLM, llama.cpp server, LM Studio
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,      // "https://api.openai.com/v1"
    private val apiKey: String,
    private val model: String,        // "gpt-4o" / "claude-3" / "qwen2.5"
    private val httpClient: okhttp3.OkHttpClient
) : LlmClient {

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        val requestBody = buildRequestBody(messages, tools, temperature, maxTokens, stream = false)
        
        val request = okhttp3.Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        
        return parseResponse(body)
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        val requestBody = buildRequestBody(messages, tools, temperature, maxTokens, stream = true)
        
        val request = okhttp3.Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        val source = response.body?.source() ?: throw Exception("Empty response")
        
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ")
                if (data == "[DONE]") {
                    emit(LlmStreamChunk(isFinish = true))
                    break
                }
                val chunk = parseStreamChunk(data)
                if (chunk != null) emit(chunk)
            }
        }
    }

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
                                put("content", msg.content)
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
                                // parameters是JSON Schema字符串，需要parse
                                put("parameters", Json.parseToJsonElement(tool.parameters))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseResponse(body: String): LlmResponse {
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
        
        val usage = json["usage"]?.jsonObject?.let { u ->
            Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0
            )
        }
        
        return LlmResponse(content = content, toolCalls = toolCalls, usage = usage)
    }

    private fun parseStreamChunk(data: String): LlmStreamChunk? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val delta = choice?.get("delta")?.jsonObject
            
            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            
            LlmStreamChunk(
                content = content,
                isFinish = finishReason == "stop"
            )
        } catch (e: Exception) {
            null
        }
    }
}
