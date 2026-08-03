package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var temperature by remember(settings) { mutableStateOf(settings.temperature) }
    var showSaveSnackbar by remember { mutableStateOf(false) }
    
    val presets = listOf(
        "OpenAI" to LlmPreset("https://api.openai.com/v1", "gpt-4o"),
        "DeepSeek" to LlmPreset("https://api.deepseek.com/v1", "deepseek-chat"),
        "OpenRouter" to LlmPreset("https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet"),
        "Ollama (本地)" to LlmPreset("http://10.0.2.2:11434/v1", "qwen2.5:72b"),
        "自定义" to LlmPreset("", "")
    )
    var selectedPreset by remember { mutableIntStateOf(4) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = {
            if (showSaveSnackbar) {
                Snackbar("配置已保存") {
                    showSaveSnackbar = false
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══ 预设选择 ═══
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速预设", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    presets.forEachIndexed { index, (name, preset) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = selectedPreset == index,
                                onClick = {
                                    selectedPreset = index
                                    baseUrl = preset.baseUrl
                                    model = preset.model
                                }
                            )
                            Text(
                                text = name,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // ═══ API配置 ═══
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("API 配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, null) }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = { Icon(Icons.Default.Key, null) }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        placeholder = { Text("gpt-4o / claude-3.5-sonnet / qwen2.5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.SmartToy, null) }
                    )
                }
            }
            
            // ═══ 参数配置 ═══
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("参数", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Temperature: ${"%.1f".format(temperature)}")
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                }
            }
            
            // ═══ 测试连接 ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.testConnection(baseUrl, apiKey, model)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("测试连接")
                }
                
                Button(
                    onClick = {
                        viewModel.saveConfig(baseUrl, apiKey, model, temperature)
                        showSaveSnackbar = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存")
                }
            }
            
            // 测试状态
            viewModel.testResult?.let { result ->
                Surface(
                    color = if (result.success) 
                        MaterialTheme.colorScheme.tertiaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = result.message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

data class LlmPreset(val baseUrl: String, val model: String)
