@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelCapabilityHeuristics
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelProfileDefaults
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.llm.RemoteModelInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模型配置卡片（多档案管理 + 服务商 / 密钥 / 模型 ID 一站配置）。
 *
 * 由原先设置页「模型」页签里两张割裂的卡合并而来（用户反馈「模型档案应该和
 * 配置模型合并一块」）：
 *  - 旧「模型配置」卡：选服务商 → Base URL → API Key → 模型 ID + 获取模型；
 *  - 旧「Models · 模型档案」卡：档案列表 + 增删复制 + 名称/Provider/ModelID/Capabilities。
 *
 * 两卡编辑的其实是同一份 [ModelProfile]（provider / modelId 字段重叠），合并成
 * 一张卡后自上而下：**切换档案 → 新建/复制/重置/删除 → 档案名称 → 服务商 →
 * Base URL → API Key → 模型 ID（手输或「获取模型」拉取）→ 设为默认 /
 * Providers / 角色映射 → 能力标记**。
 *
 * 1. **第一行选择模型服务商** → Base URL 自动填充官方预设（可手改，支持任意 OpenAI 兼容端点）；
 * 2. **API Key 由用户输入** → 经 EncryptedSharedPreferences（AES-256-GCM）加密存储，不明文落盘；
 * 3. **「获取模型」** 直接打 `GET {baseUrl}/models`，把端点上真实存在的模型列成清单让用户选；
 *    弹窗内也支持**手动填写模型 ID**（清单里没有时不必退出弹窗再手输）。
 *
 * ## 持久化策略（性能优化，自旧卡原样保留）
 * URL / Key 的落盘采用 **500ms 防抖**：旧实现每敲一个字符就同步一次 ——
 * Provider 走「JSON 序列化 + 写盘」、Key 走 EncryptedSharedPreferences（每次写入
 * 都要重加密整个文件），连续输入时主线程IO 洪峰明显。现在：
 * - 输入只更新本地状态；停顿 500ms 才落盘；
 * - 切换档案 / 服务商 / 离开设置页 / 点「获取模型」时强制冲刷，绝不丢最后一次编辑。
 */
@Composable
internal fun ModelSetupCard(
    profiles: List<ModelProfile>,
    selected: ModelProfile?,
    viewModel: SettingsViewModel,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Provider 清单从 ViewModel 取（合并后本卡是唯一消费方，不再经页签层层透传）
    val providers by viewModel.providers.collectAsStateWithLifecycle()

    val providerId = selected?.providerId.orEmpty()
    val provider = providers.firstOrNull { it.id == providerId }

    var baseUrl by remember { mutableStateOf(provider?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(viewModel.getProviderApiKey(providerId)) }
    var keyVisible by remember { mutableStateOf(false) }
    var expandedProfile by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf(false) }

    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var discoveredModels by remember { mutableStateOf(emptyList<RemoteModelInfo>()) }
    var showModelPicker by remember { mutableStateOf(false) }

    // 删除 / 重置参数的确认框（自 SettingsScreen.ModelsSection 迁入）
    var deleteTarget by remember { mutableStateOf<ModelProfile?>(null) }
    var resetTarget by remember { mutableStateOf<ModelProfile?>(null) }

    val scope = rememberCoroutineScope()

    // ═══ 诚实化（用户质疑"test 模型 / deepseekflash 名字不完整"的根因）═══
    // 列表内容 = 端点 /models 响应的逐字透传：若 Base URL 指向第三方中转站，
    // 中转站返回什么 id（test / deepseekflash…）就展示什么 —— 不是 App 造假。
    // 但旧 UI 把弹窗冠名为「DeepSeek 的可用模型」，掩盖了真实请求端点。
    // 现在比对【当前 Base URL vs 内置官方预设】→ 偏离即显式警告 + 一键恢复官方端点；
    // 弹窗标题也带上实际请求的 host，让用户看清模型列表到底是谁返回的。
    val officialBaseUrl = remember(providerId) {
        ModelProfileDefaults.builtInProviders.firstOrNull { it.id == providerId }?.baseUrl.orEmpty()
    }
    val urlDeviated = officialBaseUrl.isNotBlank() &&
        baseUrl.trim().trimEnd('/').equals(officialBaseUrl.trimEnd('/'), ignoreCase = true).not()

    // 最新值引用（DisposableEffect 冲刷时取离开屏幕那一刻的值，而非首次组合的旧值）
    val latestProviderId by rememberUpdatedState(providerId)
    val latestBaseUrl by rememberUpdatedState(baseUrl)
    val latestApiKey by rememberUpdatedState(apiKey)
    val latestProviders by rememberUpdatedState(providers)

    /** 把当前编辑中的 URL/Key 冲刷进持久层（切档案 / 切服务商 / dispose / 拉模型前调用）。 */
    fun flushEdits(targetProviderId: String = latestProviderId) {
        if (targetProviderId.isBlank()) return
        val persistedUrl = latestProviders.firstOrNull { it.id == targetProviderId }?.baseUrl.orEmpty()
        if (latestBaseUrl.trim() != persistedUrl) {
            viewModel.setProviderBaseUrl(targetProviderId, latestBaseUrl)
        }
        if (latestApiKey != viewModel.getProviderApiKey(targetProviderId)) {
            viewModel.setProviderApiKey(targetProviderId, latestApiKey)
        }
    }

    // 离开设置页时冲刷未落盘的编辑（防抖任务会随组合销毁被取消）
    DisposableEffect(Unit) {
        onDispose { flushEdits() }
    }

    // URL 防抖落盘：停顿 500ms 才写（避免每键一次 JSON 序列化 + 写盘）。
    // 回写后的 Provider 流更新会触发重组，此时 baseUrl == provider.baseUrl → 提前返回，不会回环。
    LaunchedEffect(baseUrl, providerId) {
        if (baseUrl == provider?.baseUrl) return@LaunchedEffect
        delay(500)
        viewModel.setProviderBaseUrl(providerId, baseUrl)
    }

    // Key 防抖落盘：EncryptedSharedPreferences 每次写入都重加密整文件，
    // 每键同步会掉帧；停顿 500ms + 离开页面冲刷保证不丢。
    LaunchedEffect(apiKey, providerId) {
        if (apiKey == viewModel.getProviderApiKey(providerId)) return@LaunchedEffect
        delay(500)
        viewModel.setProviderApiKey(providerId, apiKey)
    }

    // 切换档案 / 服务商导致 providerId 变化时：回填新服务商的当前 URL/Key
    // （旧服务商编辑中的值已在各下拉 onClick 里同步冲刷，不依赖防抖 ——
    //   防抖任务会因 key 变化被取消）
    LaunchedEffect(providerId) {
        baseUrl = provider?.baseUrl.orEmpty()
        apiKey = viewModel.getProviderApiKey(providerId)
    }

    /**
     * 新建模型档案并选中。默认挂到「自定义 OpenAI 兼容端点」（不预设厂商倾向，
     * 与默认种子档案策略一致），Base URL 留空由用户填写。
     */
    fun createProfile() {
        // 新档的 provider 大概率与当前不同：先冲刷旧 provider 编辑中的值
        flushEdits(providerId)
        val newProviderId = providers.firstOrNull { it.id == "custom_openai" }?.id
            ?: providers.firstOrNull()?.id ?: ""
        val id = "profile_${System.currentTimeMillis()}"
        viewModel.upsertProfile(
            ModelProfile(id = id, name = "新模型", providerId = newProviderId, modelId = "")
        )
        onSelect(id)
        // 同步回填新 provider 的 URL/Key —— 若留给 LaunchedEffect 回填，期间重启的
        // 防抖任务会拿旧值比对并可能把旧 provider 的 URL 写进新 provider
        val np = providers.firstOrNull { it.id == newProviderId }
        baseUrl = np?.baseUrl.orEmpty()
        apiKey = viewModel.getProviderApiKey(newProviderId)
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
                Column(Modifier.weight(1f)) {
                    Text("模型配置", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "多档案 / 服务商 / 密钥 / 模型 ID 一站配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (selected == null) {
                Text(
                    "还没有模型档案，点击下方按钮新建一个（默认使用「自定义」服务商，Base URL 由你填写）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Button(onClick = { createProfile() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ 新建模型档案")
                }
                return@Card
            }
            val sel: ModelProfile = selected

            // ── 档案选择：切换当前编辑的模型档案（URL/Key 属于 provider，切档不清空）──
            ExposedDropdownMenuBox(
                expanded = expandedProfile,
                onExpandedChange = { expandedProfile = it }
            ) {
                OutlinedTextField(
                    value = if (sel.isDefault) "★ ${sel.name}" else sel.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型档案") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProfile) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expandedProfile,
                    onDismissRequest = { expandedProfile = false }
                ) {
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = p.id == sel.id, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(p.name)
                                            if (p.isDefault) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "默认",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            "${providers.firstOrNull { prov -> prov.id == p.providerId }?.displayName ?: "无 Provider"}" +
                                                " · ${p.modelId.ifBlank { "未设模型" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            },
                            onClick = {
                                if (p.id != sel.id) {
                                    // 先冲刷旧 provider 编辑中的值（防抖任务会随 providerId
                                    // 变化被取消），再切档 —— 否则 500ms 内切走的编辑会丢
                                    flushEdits(providerId)
                                    onSelect(p.id)
                                    // 同步回填新档 provider 的 URL/Key（防抖旧值写入新 provider）
                                    val np = providers.firstOrNull { prov -> prov.id == p.providerId }
                                    baseUrl = np?.baseUrl.orEmpty()
                                    apiKey = viewModel.getProviderApiKey(p.providerId)
                                }
                                expandedProfile = false
                            }
                        )
                    }
                }
            }

            // ── 档案操作：新建 / 复制 / 重置参数（带确认）/ 删除（带确认）──
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { createProfile() }) { Text("+ 新建", fontSize = 12.sp) }
                TextButton(onClick = { viewModel.duplicateProfile(sel.id) }) { Text("复制", fontSize = 12.sp) }
                TextButton(onClick = { resetTarget = sel }) { Text("重置参数", fontSize = 12.sp) }
                TextButton(
                    enabled = profiles.size > 1 && !sel.isDefault,
                    onClick = { deleteTarget = sel }
                ) { Text("删除", fontSize = 12.sp) }
            }

            // ── 档案名称 ──
            OutlinedTextField(
                value = sel.name,
                onValueChange = { viewModel.upsertProfile(sel.copy(name = it)) },
                label = { Text("档案名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 服务商选择（URL 自动预填官方端点，可手改）──
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
                                // 先冲刷旧服务商编辑中的值（防抖任务会随 providerId 变化被取消），
                                // 再切档位 —— 否则 500ms 内切走的编辑会丢
                                flushEdits(providerId)
                                viewModel.upsertProfile(sel.copy(providerId = prov.id))
                                baseUrl = prov.baseUrl
                                apiKey = viewModel.getProviderApiKey(prov.id)
                                expandedProvider = false
                            }
                        )
                    }
                }
            }

            // ── Base URL（选服务商后自动预填，仍可手改；防抖 500ms 落盘）──
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)) },
                supportingText = {
                    Column {
                        Text(
                            if (baseUrl.isBlank()) "自定义端点请在此填写，例：https://api.xxx.com/v1"
                            else "请求发往 ${baseUrl.trimEnd('/')}/chat/completions",
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (urlDeviated) {
                            Text(
                                "⚠ 当前 Base URL 偏离「${provider?.displayName}」官方预设" +
                                    "（官方：$officialBaseUrl）。拉到的模型清单由该端点返回，" +
                                    "出现的 model id（含 test / 命名不完整项）均来自它，非官方列表。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 偏离官方预设：一键恢复（弹窗列表里那些"test"多来自中转站，恢复官方端点后即消失）
            if (urlDeviated) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        baseUrl = officialBaseUrl
                        viewModel.setProviderBaseUrl(providerId, officialBaseUrl)
                    }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复官方端点 $officialBaseUrl", fontSize = 12.sp)
                    }
                }
            }

            // ── API Key（用户输入；防抖 500ms 加密落盘）──
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
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

            // ── 模型 ID（手输 + 从端点获取）──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sel.modelId,
                    onValueChange = { viewModel.upsertProfile(sel.copy(modelId = it.trim())) },
                    label = { Text("模型 ID") },
                    supportingText = {
                        Text(
                            if (sel.modelId.isBlank()) "点右侧「获取模型」从端点拉取，也可直接手填"
                            else "已选模型：${sel.modelId}",
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
                            // 拉模型前先冲刷防抖中的 URL/Key，确保请求用的就是眼前编辑的值
                            flushEdits()
                            viewModel.fetchModels(providerId, baseUrlOverride = baseUrl).fold(
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

            // ── 底部操作行：设为默认 / Providers / 角色映射 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !sel.isDefault,
                    onClick = { viewModel.setDefaultProfile(sel.id) }
                ) { Text(if (sel.isDefault) "已是默认模型" else "设为默认") }
                TextButton(onClick = onManageProviders) { Text("Providers…") }
                TextButton(onClick = onManageRoles) { Text("角色映射…") }
            }

            // ── 能力标记（自 SettingsScreen.CapabilityEditor 迁入，合并后唯一使用方在本卡）──
            Text("能力标记", style = MaterialTheme.typography.labelMedium)
            CapabilityEditor(sel.capabilities) { caps ->
                viewModel.upsertProfile(sel.copy(capabilities = caps))
            }
        }
    }

    if (showModelPicker) {
        AvailableModelsDialog(
            providerName = provider?.displayName ?: "当前端点",
            requestUrl = baseUrl.trim().trimEnd('/') + "/models",
            urlDeviated = urlDeviated,
            models = discoveredModels,
            currentModelId = selected?.modelId.orEmpty(),
            onDismiss = { showModelPicker = false },
            onPick = { modelId ->
                selected?.let {
                    viewModel.upsertProfile(
                        it.copy(
                            modelId = modelId,
                            // 能力启发式预填：端点 /models 不带能力元数据，
                            // 按模型 id 命名约定推断 vision / ImageGen / VideoGen
                            //（只加不减，用户手改过的位不会被覆盖）。
                            capabilities = ModelCapabilityHeuristics.enrichCapabilities(
                                modelId, it.capabilities
                            )
                        )
                    )
                }
                showModelPicker = false
            }
        )
    }

    // 重置参数确认框（自 SettingsScreen.ModelsSection 迁入）
    resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("重置参数") },
            text = {
                Text("将「${target.name}」的采样 / 推理 / 上下文 / 工具 / 网络参数恢复为默认值？\n\n名称、Provider、模型 ID 与能力标记保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.upsertProfile(
                        target.copy(
                            temperature = 0.7f, topP = 1.0f, topK = 0, minP = 0.0f,
                            frequencyPenalty = 0.0f, presencePenalty = 0.0f,
                            repetitionPenalty = 1.0f, seed = null, stopSequences = emptyList(),
                            reasoningEffort = ReasoningEffort.MEDIUM, thinkingBudget = null,
                            maxOutputTokens = 4096, reservedOutputTokens = 4096,
                            connectTimeoutMs = 15_000L, readTimeoutMs = 120_000L,
                            writeTimeoutMs = 30_000L, requestTimeoutMs = 120_000L,
                            retryCount = 2, retryDelayMs = 1_000L, maxRetryDelayMs = 10_000L,
                        )
                    )
                    resetTarget = null
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("取消") } }
        )
    }

    // 删除档案确认框（自 SettingsScreen.ModelsSection 迁入；破坏性操作必须确认）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型档案") },
            text = {
                Text("确定删除「${target.name}」（${target.modelId.ifBlank { "未设模型" }}）？\n\n该操作不可恢复；默认档案不可删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(target.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/**
 * 端点真实模型清单选择器。
 *
 * 数据来自 `GET {baseUrl}/models` —— 不再使用写死的推荐列表。支持关键字筛选，
 * 空列表时给出明确原因（端点未开放 `/models` 或 Key 无权访问）。
 * 清单下方同时提供**手动填写模型 ID**入口（用户需求：「获取模型，下面必须也
 * 可以自定义填写」）—— 中转站清单里没有的型号不必退出弹窗再手输。
 *
 * 诚实化：标题带【实际请求的端点 host】，副行展示完整请求 URL —— 用户反馈
 * "选了 DeepSeek 却拉出 test / deepseekflash 这类模型名"，根因是其 Base URL
 * 指向第三方中转站（清单=端点响应逐字透传）。冠名 + URL 同屏后，"这份清单
 * 到底是谁返回的"一目了然；[urlDeviated] 再叠加偏离官方预设警告。
 */
@Composable
private fun AvailableModelsDialog(
    providerName: String,
    requestUrl: String,
    urlDeviated: Boolean,
    models: List<RemoteModelInfo>,
    currentModelId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var customModelId by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        if (query.isBlank()) models
        else models.filter { it.id.contains(query.trim(), ignoreCase = true) }
    }
    val requestHost = remember(requestUrl) {
        runCatching { java.net.URI(requestUrl).host ?: requestUrl }.getOrDefault(requestUrl)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("$providerName · $requestHost 的可用模型（${models.size}）")
                Text(
                    "GET $requestUrl",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (urlDeviated) {
                    Text(
                        "⚠ 端点偏离官方预设 —— 清单由该中转/自定义端点返回，非官方模型表",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
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
                            "或直接在下方手动填写模型 ID。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    // 高度封顶：给下方「手动填写」区留出稳定可见的空间
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
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
                                        Text(
                                            model.id,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (model.ownedBy.isNotBlank()) {
                                            Text(
                                                model.ownedBy,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 列表选不到？直接手动填写模型 ID（私有部署 / 中转站常见）──
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = customModelId,
                    onValueChange = { customModelId = it },
                    label = { Text("或手动填写模型 ID") },
                    placeholder = { Text("my-model-v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        enabled = customModelId.isNotBlank(),
                        onClick = { onPick(customModelId.trim()) }
                    ) { Text("使用自定义 ID") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 能力标记编辑器（自 SettingsScreen.kt 迁入 —— 档案卡合并后唯一使用方在本卡）。
 * 含上游（PR #125）新增的多模态输出能力位 ImageGen / VideoGen。
 */
@Composable
private fun CapabilityEditor(caps: ModelCapabilities, onChange: (ModelCapabilities) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        item { FilterChip(selected = caps.text, onClick = { onChange(caps.copy(text = !caps.text)) }, label = { Text("Text") }) }
        item { FilterChip(selected = caps.vision, onClick = { onChange(caps.copy(vision = !caps.vision)) }, label = { Text("Vision") }) }
        item { FilterChip(selected = caps.toolCalling, onClick = { onChange(caps.copy(toolCalling = !caps.toolCalling)) }, label = { Text("Tools") }) }
        item { FilterChip(selected = caps.structuredOutput, onClick = { onChange(caps.copy(structuredOutput = !caps.structuredOutput)) }, label = { Text("JSON") }) }
        item { FilterChip(selected = caps.reasoning, onClick = { onChange(caps.copy(reasoning = !caps.reasoning)) }, label = { Text("Reason") }) }
        item { FilterChip(selected = caps.longContext, onClick = { onChange(caps.copy(longContext = !caps.longContext)) }, label = { Text("LongCtx") }) }
        item { FilterChip(selected = caps.streaming, onClick = { onChange(caps.copy(streaming = !caps.streaming)) }, label = { Text("Stream") }) }
        item { FilterChip(selected = caps.imageInput, onClick = { onChange(caps.copy(imageInput = !caps.imageInput)) }, label = { Text("ImgIn") }) }
        // 多模态输出能力位：开启 ImageGen 的模型请求时携带 modalities=[text,image]，
        // 让 OpenRouter 等网关的生图模型真正返回图片 part。
        item { FilterChip(selected = caps.imageGeneration, onClick = { onChange(caps.copy(imageGeneration = !caps.imageGeneration)) }, label = { Text("ImageGen") }) }
        item { FilterChip(selected = caps.videoGeneration, onClick = { onChange(caps.copy(videoGeneration = !caps.videoGeneration)) }, label = { Text("VideoGen") }) }
    }
}
