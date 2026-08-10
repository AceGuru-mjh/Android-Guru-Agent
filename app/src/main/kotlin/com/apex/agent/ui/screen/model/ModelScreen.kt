package com.apex.agent.ui.screen.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.ui.screen.settings.SettingsViewModel

private data class ModelPreset(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyHint: String = ""
)

private val PRESETS = listOf(
    ModelPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o"),
    ModelPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ModelPreset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet"),
    ModelPreset("Ollama", "http://10.0.2.2:11434/v1", "qwen2.5:72b", "ollama"),
    ModelPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var temperature by remember(settings) { mutableFloatStateOf(settings.temperature) }
    var selectedPreset by remember { mutableStateOf(-1) }
    var showSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(showSnackbar) {
        if (showSnackbar) {
            snackbarHostState.showSnackbar(message = "配置已保存")
            showSnackbar = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("模型配置") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 预设
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速预设", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PRESETS.forEachIndexed { i, preset ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            RadioButton(
                                selected = selectedPreset == i,
                                onClick = {
                                    selectedPreset = i
                                    baseUrl = preset.baseUrl
                                    model = preset.model
                                    if (preset.apiKeyHint.isNotEmpty()) apiKey = preset.apiKeyHint
                                }
                            )
                            Text(preset.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            // API配置
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("API 配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = { Icon(Icons.Default.Key, null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.SmartToy, null) }
                    )
                }
            }

            // 参数
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temperature: ${"%.1f".format(temperature)}", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.testConnection(baseUrl, apiKey, model) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测试")
                }
                Button(
                    onClick = {
                        viewModel.saveConfig(baseUrl, apiKey, model, temperature)
                        showSnackbar = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }

            // 测试结果
            viewModel.testResult?.let { result ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = result.message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
