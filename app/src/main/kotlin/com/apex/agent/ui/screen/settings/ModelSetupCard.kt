package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.RemoteModelInfo
import kotlinx.coroutines.launch

/**
 * 模型配置卡片 —— 一张卡配完一个可用模型。
 *
 * 取代原先「写死的推荐模型卡片 + 藏在 Providers 弹窗里的 URL/Key + 纯手输 Model ID」
 * 的三段式流程（推荐型号在多数中转站并不存在，等于假数据）。现在的行为：
 *
 * 1. **第一行选择模型服务商** → Base URL 自动填充官方预设（可手改，支持任意 OpenAI 兼容端点）；
 * 2. **API Key 由用户输入** → 经 EncryptedSharedPreferences（AES-256-GCM）加密存储，不明文落盘；
 * 3. **「获取模型」** 直接打 `GET {baseUrl}/models`，把端点上真实存在的模型列成清单让用户选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSetupCard(
    providers: List<ProviderConfig>,
    selected: ModelProfile?,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val providerId = selected?.providerId.orEmpty()
    val provider = providers.firstOrNull { it.id == providerId }

    var baseUrl by remember { mutableStateOf(provider?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(viewModel.getProviderApiKey(providerId)) }
    var keyVisible by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf(false) }

    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var discoveredModels by remember { mutableStateOf(emptyList<RemoteModelInfo>()) }
    var showModelPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 切换服务商 / 首次进入：URL 与 Key 回填该服务商的当前值
    LaunchedEffect(providerId) {
        baseUrl = provider?.baseUrl.orEmpty()
        apiKey = viewModel.getProviderApiKey(providerId)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "模型配置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            if (selected == null) {
                Text(
                    "还没有模型档案，点击下方「+ 添加模型」新建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                return@Card
            }

            // ── 第一行：选择服务商（URL 自动预填）──
            ExposedDropdownMenuBox(
                expanded = expandedProvider,
                onExpandedChange = { expandedProvider = it }
            ) {
                OutlinedTextField(
                    value = provider?.displayName ?: "未选择服务商",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("选择模型服务商") },
                    leadingIcon = { Icon(Icons.Outlined.SmartToy, null, Modifier.size(18.dp)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProvider) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expandedProvider,
                    onDismissRequest = { expandedProvider = false }
                ) {
                    providers.forEach { prov ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = prov.id == providerId, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(prov.displayName)
                                        if (prov.baseUrl.isNotBlank()) {
                                            Text(
                                                prov.baseUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                viewModel.upsertProfile(selected.copy(providerId = prov.id))
                                baseUrl = prov.baseUrl
                                apiKey = viewModel.getProviderApiKey(prov.id)
                                expandedProvider = false
                            }
                        )
                    }
                }
            }

            // ── 第二行：Base URL（选服务商后自动预填，仍可手改）──
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { next ->
                    baseUrl = next
                    val target = providers.firstOrNull { it.id == providerId } ?: return@OutlinedTextField
                    viewModel.upsertProvider(target.copy(baseUrl = next.trim()))
                },
                label = { Text("Base URL") },
                leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)) },
                supportingText = {
                    Text(
                        if (baseUrl.isBlank()) "自定义端点请在此填写，例：https://api.xxx.com/v1"
                        else "请求发往 ${baseUrl.trimEnd('/')}/chat/completions",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 第三行：API Key（用户输入，即写即加密）──
            OutlinedTextField(
                value = apiKey,
                onValueChange = { next ->
                    apiKey = next
                    viewModel.setProviderApiKey(providerId, next)
                },
                label = { Text("API Key") },
                leadingIcon = { Icon(Icons.Outlined.Key, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (keyVisible) "隐藏" else "显示"
                        )
                    }
                },
                supportingText = {
                    Text(
                        "由你输入，AES-256-GCM 加密存储在设备本地，不明文写入配置文件",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyVisible = false }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 第四行：模型（手输 + 从端点获取）──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = selected.modelId,
                    onValueChange = { viewModel.upsertProfile(selected.copy(modelId = it.trim())) },
                    label = { Text("模型") },
                    supportingText = {
                        Text(
                            if (selected.modelId.isBlank()) "点右侧「获取模型」从端点拉取可用模型"
                            else "已选模型：${selected.modelId}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !modelsLoading && baseUrl.startsWith("http"),
                    onClick = {
                        modelsLoading = true
                        modelsError = null
                        scope.launch {
                            viewModel.fetchModels(providerId).fold(
                                onSuccess = { list ->
                                    discoveredModels = list
                                    showModelPicker = true
                                },
                                onFailure = { modelsError = it.message }
                            )
                            modelsLoading = false
                        }
                    }
                ) {
                    if (modelsLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("获取模型")
                }
            }

            modelsError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !selected.isDefault,
                    onClick = { viewModel.setDefaultProfile(selected.id) }
                ) { Text(if (selected.isDefault) "已是默认模型" else "设为默认") }
            }
        }
    }

    if (showModelPicker) {
        AvailableModelsDialog(
            providerName = provider?.displayName ?: "当前端点",
            models = discoveredModels,
            currentModelId = selected?.modelId.orEmpty(),
            onDismiss = { showModelPicker = false },
            onPick = { modelId ->
                selected?.let { viewModel.upsertProfile(it.copy(modelId = modelId)) }
                showModelPicker = false
            }
        )
    }
}

/**
 * 端点真实模型清单选择器。
 *
 * 数据来自 `GET {baseUrl}/models` —— 不再使用写死的推荐列表。支持关键字筛选，
 * 空列表时给出明确原因（端点未开放 `/models` 或 Key 无权访问）。
 */
@Composable
private fun AvailableModelsDialog(
    providerName: String,
    models: List<RemoteModelInfo>,
    currentModelId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        if (query.isBlank()) models
        else models.filter { it.id.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName 的可用模型（${models.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("筛选模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text(
                        "端点未返回任何模型。请确认 Base URL 正确、API Key 有权限，" +
                            "或直接在「模型」输入框里手填模型 ID。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.id }) { model ->
                            TextButton(
                                onClick = { onPick(model.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = model.id == currentModelId, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(model.id, maxLines = 1)
                                        if (model.ownedBy.isNotBlank()) {
                                            Text(
                                                model.ownedBy,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
