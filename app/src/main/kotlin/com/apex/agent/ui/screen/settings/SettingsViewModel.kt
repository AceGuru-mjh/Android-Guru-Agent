package com.apex.agent.ui.screen.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.llm.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ModelSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f
)

/** Agent 运行参数，持久化于 SharedPreferences，供 Chat 启动时读取。 */
data class AgentSettings(
    val defaultMode: String = "auto",   // build | chat | auto
    val thinkLevel: String = "standard", // standard | deep | minimal
    val maxIterations: Int = 20,
    val keepAlive: Boolean = true
)

data class TestResult(
    val success: Boolean,
    val message: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
    
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ModelSettings> = _settings.asStateFlow()

    private val _agentSettings = MutableStateFlow(loadAgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()
    
    var testResult: TestResult? by mutableStateOf(null)
        private set

    fun saveConfig(baseUrl: String, apiKey: String, model: String, temperature: Float) {
        prefs.edit()
            .putString("llm_base_url", baseUrl)
            .putString("llm_api_key", apiKey)
            .putString("llm_model", model)
            .putFloat("llm_temperature", temperature)
            .apply()
        
        _settings.value = ModelSettings(baseUrl, apiKey, model, temperature)
    }
    
    fun testConnection(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            testResult = null
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val config = LlmConfig(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        timeoutSeconds = 15
                    )
                    val client = LlmClientFactory.create(config)
                    
                    // 发送简单测试消息
                    val response = client.chat(
                        messages = listOf(LlmMessage.User("Say 'OK' in one word.")),
                        maxTokens = 10
                    )
                    
                    val content = response.content
                    if (content != null) {
                        TestResult(true, "✅ 连接成功！模型响应: ${content.take(50)}")
                    } else if (response.toolCalls.isNotEmpty()) {
                        TestResult(true, "✅ 连接成功！（模型返回了工具调用）")
                    } else {
                        TestResult(false, "⚠️ 连接成功但响应为空")
                    }
                } catch (e: Exception) {
                    TestResult(false, "❌ 连接失败: ${e.message?.take(200)}")
                }
            }
            
            testResult = result
        }
    }
    
    /** 局部更新 Agent 设置并持久化。 */
    fun updateAgentSettings(block: AgentSettings.() -> AgentSettings) {
        val next = _agentSettings.value.block()
        prefs.edit()
            .putString("agent_default_mode", next.defaultMode)
            .putString("agent_think_level", next.thinkLevel)
            .putInt("agent_max_iterations", next.maxIterations)
            .putBoolean("agent_keep_alive", next.keepAlive)
            .apply()
        _agentSettings.value = next
    }
    
    private fun loadSettings(): ModelSettings {
        return ModelSettings(
            baseUrl = prefs.getString("llm_base_url", "") ?: "",
            apiKey = prefs.getString("llm_api_key", "") ?: "",
            model = prefs.getString("llm_model", "") ?: "",
            temperature = prefs.getFloat("llm_temperature", 0.7f)
        )
    }

    private fun loadAgentSettings(): AgentSettings {
        return AgentSettings(
            defaultMode = prefs.getString("agent_default_mode", "auto") ?: "auto",
            thinkLevel = prefs.getString("agent_think_level", "standard") ?: "standard",
            maxIterations = prefs.getInt("agent_max_iterations", 20),
            keepAlive = prefs.getBoolean("agent_keep_alive", true)
        )
    }
}
