@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.apex.agent.core.llm.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置中心（三页签：模型 / Agent / 界面）。
 *
 * - 模型页：按 Profile 编辑采样 / 推理 / 上下文 / 工具 / 网络 / 提示词 / 结构化输出；
 * - Agent 页：执行模式全档位、思考深度全档位、重试与循环防护、上下文压缩、视觉；
 * - 界面页：主题模式、动态取色、全局字体缩放、消息时间戳（经 MainActivity /
 *   [com.apex.agent.ui.theme.LocalShowTimestamps] 即时生效）。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val profiles by viewModel.profiles.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val roles by viewModel.roles.collectAsState()
    val agent by viewModel.agentSettings.collectAsState()

    var selectedId by remember { mutableStateOf(viewModel.defaultProfile.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showProviders by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    // Agent 设置统一更新入口
    val onAgent: (AgentSettings) -> Unit = { next -> viewModel.updateAgentSettings { next } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置 · Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            selected?.let {
                                scope.launch { testResult = viewModel.testConnection(it.id) }
                            }
                        },
                        enabled = selected != null
                    ) {
                        Icon(Icons.Outlined.Science, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("模型") },
                    icon = { Icon(Icons.Outlined.SmartToy, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Agent") },
                    icon = { Icon(Icons.Outlined.Psychology, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("界面") },
                    icon = { Icon(Icons.Outlined.Palette, null) }
                )
            }

            when (selectedTab) {
                0 -> ModelsTab(
                    profiles = profiles,
                    providers = providers,
                    selectedId = selectedId,
                    selected = selected,
                    onSelect = { selectedId = it },
                    onAdd = {
                        val id = "profile_${System.currentTimeMillis()}"
                        val p = ModelProfile(
                            id = id, name = "新模型",
                            providerId = providers.firstOrNull()?.id ?: "", modelId = ""
                        )
                        viewModel.upsertProfile(p)
                        selectedId = id
                    },
                    onDuplicate = { viewModel.duplicateProfile(it) },
                    onDelete = { viewModel.deleteProfile(it) },
                    onSetDefault = { viewModel.setDefaultProfile(it) },
                    onUpdate = { viewModel.upsertProfile(it) },
                    onManageProviders = { showProviders = true },
                    onManageRoles = { showRoles = true },
                )
                1 -> AgentTab(
                    agent = agent,
                    roles = roles,
                    profiles = profiles,
                    onAgent = onAgent,
                    viewModel = viewModel,
                )
                2 -> InterfaceTab(agent = agent, onAgent = onAgent)
            }
        }
    }

    if (showProviders) {
        ProvidersDialog(providers, viewModel) { showProviders = false }
    }
    if (showRoles) {
        RolesDialog(roles, profiles, viewModel) { showRoles = false }
    }
    testResult?.let { r ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } },
            title = { Text(if (r.success) "连接成功" else "连接失败") },
            text = { Text(r.message) }
        )
    }
}

// ───────────────────────────── 页签容器 ─────────────────────────────

@Composable
private fun ModelsTab(
    profiles: List<ModelProfile>,
    providers: List<ProviderConfig>,
    selectedId: String,
    selected: ModelProfile?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onUpdate: (ModelProfile) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModelsSection(
            profiles = profiles,
            providers = providers,
            selectedId = selectedId,
            onSelect = onSelect,
            onAdd = onAdd,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onSetDefault = onSetDefault,
            onUpdate = onUpdate,
            onManageProviders = onManageProviders,
            onManageRoles = onManageRoles,
        )
        selected?.let { p ->
            GenerationSection(p, onUpdate)
            ReasoningSection(p, onUpdate)
            ContextSection(p, onUpdate)
            ToolsSection(p, onUpdate)
            NetworkSection(p, onUpdate)
            PromptSection(p, onUpdate)
            AdvancedSection(p, onUpdate)
        }
    }
}

@Composable
private fun AgentTab(
    agent: AgentSettings,
    roles: ModelRoleConfig,
    profiles: List<ModelProfile>,
    onAgent: (AgentSettings) -> Unit,
    viewModel: SettingsViewModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AgentSection(agent, onAgent)
        CompressionSection(agent, onAgent)
        VisionSection(agent, roles, profiles, viewModel)
    }
}

@Composable
private fun InterfaceTab(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppearanceSection(agent, onAgent)
        ChatDisplaySection(agent, onAgent)
        NotesSection()
    }
}

// ───────────────────────────── 复用控件 ─────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { content() }
            }
        }
    }
}

/** 控件下方的一行小字说明；为 null 时不占位。 */
@Composable
private fun DescriptionText(description: String?) {
    if (description != null) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onCheckedChange)
        }
        DescriptionText(description)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    fmt: (Float) -> String = { it.toString() },
    onValueChange: (Float) -> Unit
) {
    // 拖动期间只更新本地值，松手才回调（避免每个 tick 写 SharedPreferences）
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(
                fmt(local.coerceIn(range)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local.coerceIn(range),
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onValueChange(local.coerceIn(range)) }
        )
        DescriptionText(description)
    }
}

@Composable
private fun IntFieldRow(
    label: String,
    value: Int,
    description: String? = null,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.toIntOrNull()?.let { onValueChange(it.coerceIn(min, max)) }
        },
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    description: String? = null,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

/** 只读下拉（ExposedDropdownMenuBox 实现，替代旧的 AlertDialog + RadioButton）。 */
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    description: String? = null,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = v == selected, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(text)
                            }
                        },
                        onClick = { onSelected(v); expanded = false }
                    )
                }
            }
        }
        DescriptionText(description)
    }
}

@Composable
private fun ChipMultiSelect(
    label: String,
    options: List<Int>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { code ->
                FilterChip(
                    selected = selected.contains(code),
                    onClick = { onToggle(code) },
                    label = { Text(code.toString()) }
                )
            }
        }
    }
}

@Composable
private fun KeyValueEditor(
    label: String,
    map: Map<String, String>,
    onChange: (Map<String, String>) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        map.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$k : $v", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onChange(map - k) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(key, { key = it }, label = { Text("Header") },
                modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(value, { value = it }, label = { Text("Value") },
                modifier = Modifier.weight(1f), singleLine = true)
            IconButton(onClick = {
                if (key.isNotBlank()) { onChange(map + (key to value)); key = ""; value = "" }
            }) { Icon(Icons.Default.Add, null) }
        }
    }
}

// ───────────────────────────── 模型页分区 ─────────────────────────────

@Composable
private fun ModelsSection(
    profiles: List<ModelProfile>,
    providers: List<ProviderConfig>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onUpdate: (ModelProfile) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
) {
    var resetTarget by remember { mutableStateOf<ModelProfile?>(null) }

    SectionCard("Models · 模型档案", Icons.Outlined.SmartToy, initiallyExpanded = true) {
        profiles.forEach { p ->
            val prov = providers.firstOrNull { it.id == p.providerId }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == selectedId, onClick = { onSelect(p.id) })
                        Text(p.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (p.isDefault) Icon(Icons.Default.Star, "Default",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("${prov?.displayName ?: "无 Provider"} · ${p.modelId} · ${p.displayContext()}",
                        style = MaterialTheme.typography.bodySmall)
                    if (p.capabilities.summary().isNotBlank()) {
                        Text(p.capabilities.summary(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    Row {
                        TextButton(onClick = { onSetDefault(p.id) }) { Text("设为默认") }
                        TextButton(onClick = { onDuplicate(p.id) }) { Text("复制") }
                        TextButton(onClick = { resetTarget = p }) { Text("重置参数") }
                        TextButton(onClick = { onDelete(p.id) },
                            enabled = profiles.size > 1) { Text("删除") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, Modifier.weight(1f)) { Text("+ 添加模型") }
            OutlinedButton(onClick = onManageProviders, Modifier.weight(1f)) { Text("Providers") }
            OutlinedButton(onClick = onManageRoles, Modifier.weight(1f)) { Text("角色") }
        }

        // 选中 Profile 基本信息编辑
        val sel = profiles.firstOrNull { it.id == selectedId }
        sel?.let { p ->
            HorizontalDivider()
            TextFieldRow("模型名称", p.name) { onUpdate(p.copy(name = it)) }
            DropdownRow("Provider", providers.map { it.id to it.displayName }, p.providerId) {
                onUpdate(p.copy(providerId = it))
            }
            TextFieldRow("Model ID", p.modelId, description = "如 gpt-4o-mini / deepseek-chat / qwen2.5:7b") {
                onUpdate(p.copy(modelId = it))
            }
            Text("Capabilities", style = MaterialTheme.typography.labelMedium)
            CapabilityEditor(p.capabilities) { onUpdate(p.copy(capabilities = it)) }
        }
    }

    resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("重置参数") },
            text = { Text("将「${target.name}」的采样 / 推理 / 上下文 / 工具 / 网络参数恢复为默认值？\n\n名称、Provider、模型 ID 与能力标记保留。") },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(target.copy(
                        temperature = 0.7f, topP = 1.0f, topK = 0, minP = 0.0f,
                        frequencyPenalty = 0.0f, presencePenalty = 0.0f,
                        repetitionPenalty = 1.0f, seed = null, stopSequences = emptyList(),
                        reasoningEffort = ReasoningEffort.MEDIUM, thinkingBudget = null,
                        maxOutputTokens = 4096, reservedOutputTokens = 4096,
                        connectTimeoutMs = 15_000L, readTimeoutMs = 120_000L,
                        writeTimeoutMs = 30_000L, requestTimeoutMs = 120_000L,
                        retryCount = 2, retryDelayMs = 1_000L, maxRetryDelayMs = 10_000L,
                    ))
                    resetTarget = null
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("取消") } }
        )
    }
}

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
    }
}

@Composable
private fun GenerationSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Generation · 采样参数", Icons.Outlined.Tune) {
        SliderRow("Temperature", p.temperature, 0f..2f, 20,
            description = "越高越发散，低值更稳定可复现；Agent 任务建议 0.2~0.7",
            onValueChange = { onUpdate(p.copy(temperature = it)) }, fmt = { "%.2f".format(it) })
        SliderRow("Top P", p.topP, 0f..1f, 10,
            description = "核采样截断；与 Temperature 二选一调整即可",
            onValueChange = { onUpdate(p.copy(topP = it)) }, fmt = { "%.2f".format(it) })
        SliderRow("Top K (0=禁用)", p.topK.toFloat(), 0f..200f, 200,
            description = "仅本地模型（Qwen/Gemma/llama.cpp）常用",
            onValueChange = { onUpdate(p.copy(topK = it.toInt())) }, fmt = { it.toInt().toString() })
        SliderRow("Min P", p.minP, 0f..1f, 20,
            description = "按概率比例截断，本地模型推荐 0.05~0.1",
            onValueChange = { onUpdate(p.copy(minP = it)) }, fmt = { "%.2f".format(it) })
        SliderRow("Frequency Penalty", p.frequencyPenalty, -2f..2f, 40,
            description = "按出现次数惩罚重复 token",
            onValueChange = { onUpdate(p.copy(frequencyPenalty = it)) }, fmt = { "%.2f".format(it) })
        SliderRow("Presence Penalty", p.presencePenalty, -2f..2f, 40,
            description = "只要出现过就惩罚，鼓励新话题",
            onValueChange = { onUpdate(p.copy(presencePenalty = it)) }, fmt = { "%.2f".format(it) })
        SliderRow("Repetition Penalty", p.repetitionPenalty, 0f..2f, 40,
            description = "本地模型常用，1.0 为不惩罚",
            onValueChange = { onUpdate(p.copy(repetitionPenalty = it)) }, fmt = { "%.2f".format(it) })
        IntFieldRow("Seed (0=Auto)", p.seed?.toInt() ?: 0,
            description = "固定随机种子以复现输出，0 为自动") {
            onUpdate(p.copy(seed = if (it == 0) null else it.toLong()))
        }
        TextFieldRow("Stop Sequences (逗号分隔)", p.stopSequences.joinToString(","),
            description = "命中即停止生成，如 \"\\n\\nUser:\"") {
            onUpdate(p.copy(stopSequences = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }))
        }
        IntFieldRow("Max Output Tokens", p.maxOutputTokens,
            description = "单次回复的输出上限", min = 256, max = 1_000_000) {
            onUpdate(p.copy(maxOutputTokens = it))
        }
    }
}

@Composable
private fun ReasoningSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Reasoning · 推理", Icons.Outlined.Psychology) {
        Text("Reasoning Effort", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ReasoningEffort.values().toList()) { eff ->
                FilterChip(selected = p.reasoningEffort == eff,
                    onClick = { onUpdate(p.copy(reasoningEffort = eff)) },
                    label = { Text(eff.name) })
            }
        }
        DescriptionText("模型原生思考强度（OpenAI o 系 / DeepSeek-R1 / Qwen3 等）；不支持的模型自动忽略")
        DropdownRow("Thinking Budget (tokens)",
            listOf(null to "Auto", 1024 to "1024", 2048 to "2048", 4096 to "4096",
                8192 to "8192", 16384 to "16384", 32768 to "32768", 65536 to "65536"),
            p.thinkingBudget,
            description = "思维链 token 预算；与 Effort 不强行绑定（不同 Provider 语义不同）") {
            onUpdate(p.copy(thinkingBudget = it))
        }
        SwitchRow("Show Thinking", p.showThinking,
            description = "在对话流中展示模型的思考过程") {
            onUpdate(p.copy(showThinking = it))
        }
    }
}

@Composable
private fun ContextSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Context · 上下文", Icons.Outlined.Subject) {
        DropdownRow(
            "Context Window 预设",
            listOf(
                8000 to "8K", 16_000 to "16K", 32_000 to "32K", 64_000 to "64K",
                128_000 to "128K", 200_000 to "200K", 262_144 to "256K",
                524_288 to "512K", 1_000_000 to "1M"
            ),
            p.contextWindow,
            description = "模型标称上下文窗口；选预设后可在下方精确修改"
        ) { onUpdate(p.copy(contextWindow = it)) }
        IntFieldRow("精确值（tokens）", p.contextWindow,
            description = "与上方预设联动；当前值：${p.displayContext()}",
            min = 1000, max = 10_000_000) {
            onUpdate(p.copy(contextWindow = it))
        }
        IntFieldRow("Reserved Output Tokens (Agent 预留)", p.reservedOutputTokens,
            description = "为系统提示 + 工具 + 历史预留的预算，避免上下文占满",
            min = 0, max = 100_000) {
            onUpdate(p.copy(reservedOutputTokens = it))
        }
    }
}

@Composable
private fun ToolsSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Tools · 工具调用", Icons.Outlined.Build) {
        SwitchRow("Enable Tools", p.enableTools,
            description = "关闭后模型只输出文本，不调用任何工具") {
            onUpdate(p.copy(enableTools = it))
        }
        DropdownRow("Tool Choice",
            ToolChoiceMode.values().map { it to it.name }, p.toolChoice,
            description = "AUTO=模型自定；REQUIRED=强制调用；NONE=禁用") {
            onUpdate(p.copy(toolChoice = it))
        }
        SwitchRow("Parallel Tool Calls", p.parallelToolCalls,
            description = "允许模型一次返回多个并行工具调用") {
            onUpdate(p.copy(parallelToolCalls = it))
        }
        IntFieldRow("Max Tool Calls / Turn", p.maxToolCalls,
            description = "单轮对话的工具调用次数上限", min = 1, max = 100) {
            onUpdate(p.copy(maxToolCalls = it))
        }
        IntFieldRow("Tool Call Timeout (s)", p.toolTimeoutSeconds,
            description = "单个工具执行超时", min = 1, max = 3600) {
            onUpdate(p.copy(toolTimeoutSeconds = it))
        }
        IntFieldRow("Max Tool Result Tokens", p.maxToolResultTokens,
            description = "工具结果注入上下文前的 token 截断阈值", min = 100, max = 100_000) {
            onUpdate(p.copy(maxToolResultTokens = it))
        }
    }
}

@Composable
private fun VisionSection(
    agent: AgentSettings,
    roles: ModelRoleConfig,
    profiles: List<ModelProfile>,
    viewModel: SettingsViewModel
) {
    SectionCard("Vision · 视觉", Icons.Outlined.Visibility) {
        SwitchRow("Enable Vision", agent.visionEnabled,
            description = "允许 Agent 读取截图 / 图片（数据预埋，引擎后续接入）") {
            viewModel.updateAgentSettings { copy(visionEnabled = it) }
        }
        DropdownRow("Screenshot Quality",
            listOf("auto" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High"),
            agent.screenshotQuality,
            description = "截图注入上下文时的压缩质量（数据预埋）") {
            viewModel.updateAgentSettings { copy(screenshotQuality = it) }
        }
        IntFieldRow("Max Screenshots in Context", agent.maxScreenshots,
            description = "上下文中最多保留的截图张数（数据预埋）", min = 1, max = 20) {
            viewModel.updateAgentSettings { copy(maxScreenshots = it) }
        }
        DropdownRow("Vision Model (角色)",
            listOf("" to "未指定") + profiles.map { it.id to it.name },
            roles.visionProfileId) {
            viewModel.updateRoles { copy(visionProfileId = it) }
        }
    }
}

@Composable
private fun NetworkSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Network · 网络", Icons.Outlined.Cloud) {
        IntFieldRow("Connect Timeout (ms)", p.connectTimeoutMs.toInt(),
            min = 1000, max = 300_000) { onUpdate(p.copy(connectTimeoutMs = it.toLong())) }
        IntFieldRow("Read Timeout (ms)", p.readTimeoutMs.toInt(),
            description = "等待响应数据的时间；流式长回复可适当调大",
            min = 5_000, max = 600_000) { onUpdate(p.copy(readTimeoutMs = it.toLong())) }
        IntFieldRow("Write Timeout (ms)", p.writeTimeoutMs.toInt(),
            min = 1_000, max = 300_000) { onUpdate(p.copy(writeTimeoutMs = it.toLong())) }
        IntFieldRow("Request Timeout (ms)", p.requestTimeoutMs.toInt(),
            description = "整个请求的总超时",
            min = 5_000, max = 600_000) { onUpdate(p.copy(requestTimeoutMs = it.toLong())) }
        IntFieldRow("Retry Count", p.retryCount,
            description = "请求失败后的自动重试次数", min = 0, max = 10) {
            onUpdate(p.copy(retryCount = it))
        }
        IntFieldRow("Retry Delay (ms)", p.retryDelayMs.toInt(),
            description = "首次重试等待（此后指数退避）", min = 0, max = 60_000) {
            onUpdate(p.copy(retryDelayMs = it.toLong()))
        }
        IntFieldRow("Max Retry Delay (ms)", p.maxRetryDelayMs.toInt(),
            min = 1_000, max = 300_000) { onUpdate(p.copy(maxRetryDelayMs = it.toLong())) }
        ChipMultiSelect("Retry On", listOf(408, 429, 500, 502, 503, 504), p.retryOnCodes) {
            val set = p.retryOnCodes.toMutableSet()
            if (set.contains(it)) set.remove(it) else set.add(it)
            onUpdate(p.copy(retryOnCodes = set))
        }
        SwitchRow("Streaming", p.streaming,
            description = "流式输出（打字机效果）；关闭后整段返回") {
            onUpdate(p.copy(streaming = it))
        }
        SwitchRow("Keep Alive", p.keepAlive,
            description = "连接复用，减少握手开销") {
            onUpdate(p.copy(keepAlive = it))
        }
    }
}

@Composable
private fun PromptSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Prompt · 提示词", Icons.Outlined.EditNote) {
        OutlinedTextField(p.systemPromptPrefix, { onUpdate(p.copy(systemPromptPrefix = it)) },
            label = { Text("System Prompt Prefix（附加到系统提示前）") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            maxLines = 6)
        DropdownRow("Prompt Preset",
            listOf(
                "default" to "Default（清空）",
                "android_expert" to "Android Expert",
                "coding" to "Coding Agent",
                "automation" to "Automation Agent",
            ),
            "default",
            description = "选择预设即填入上方前缀，可再手改"
        ) { preset ->
            val template = when (preset) {
                "android_expert" ->
                    "You are an expert Android engineer. Prefer adb, gradle and Kotlin; verify with build output before claiming success."
                "coding" ->
                    "You are a senior coding agent. Write clean, production-ready code; explain key decisions briefly."
                "automation" ->
                    "You are a device automation agent. Plan minimal, reliable UI steps and verify after each action."
                else -> ""
            }
            onUpdate(p.copy(systemPromptPrefix = template))
        }
    }
}

@Composable
private fun AdvancedSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Advanced · 高级", Icons.Outlined.Settings) {
        DropdownRow("Structured Output",
            StructuredOutputMode.values().map { it to it.name }, p.structuredOutputMode) {
            onUpdate(p.copy(structuredOutputMode = it))
        }
        SwitchRow("Strict Schema", p.structuredOutputStrict,
            description = "JSON Schema 严格模式（仅部分 Provider 支持）") {
            onUpdate(p.copy(structuredOutputStrict = it))
        }
        KeyValueEditor("Custom Headers", p.customHeaders) {
            onUpdate(p.copy(customHeaders = it))
        }
    }
}

// ───────────────────────────── Agent 页分区 ─────────────────────────────

@Composable
private fun AgentSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard("Agent · 运行参数", Icons.Outlined.Psychology, initiallyExpanded = true) {
        DropdownRow("Execution Mode",
            listOf(
                "auto" to "Auto · 自动（旧）",
                "build" to "Build · 边想边做",
                "chat" to "Chat · 对话（旧）",
                "plan" to "Plan · 先规划后执行",
                "spec" to "Spec · 规格确认后执行",
                "reflect" to "Reflect · 反思改进",
                "assist" to "Assist · 人工决策",
                "custom" to "Custom · 自定义指令",
            ),
            agent.defaultMode,
            description = "默认执行模式，修改后重启应用生效") {
            onUpdate(agent.copy(defaultMode = it))
        }
        DropdownRow("Think Level",
            listOf(
                "minimal" to "Minimal · 直接执行",
                "light" to "Light · 简短思考",
                "standard" to "Standard · 标准推理",
                "deep" to "Deep · 深度思考",
                "maximum" to "Maximum · 极深思考",
            ),
            agent.thinkLevel,
            description = "决策前推理深度（影响 system prompt，重启应用生效）") {
            onUpdate(agent.copy(thinkLevel = it))
        }
        IntFieldRow("Max Iterations", agent.maxIterations,
            description = "单任务最大迭代次数，防止无限循环", min = 1, max = 200) {
            onUpdate(agent.copy(maxIterations = it))
        }
        SwitchRow("Keep Alive", agent.keepAlive) { onUpdate(agent.copy(keepAlive = it)) }
        SliderRow("Reflection Rounds", agent.reflectionRounds.toFloat(), 1f..3f, 2,
            description = "Reflect 模式每轮执行一次「评审 → 修正」",
            onValueChange = { onUpdate(agent.copy(reflectionRounds = it.toInt())) },
            fmt = { it.toInt().toString() })

        HorizontalDivider()
        Text("Retry / Loop Detection", style = MaterialTheme.typography.labelMedium)
        SwitchRow("Auto Retry", agent.autoRetry) { onUpdate(agent.copy(autoRetry = it)) }
        IntFieldRow("Max Retry / Action", agent.maxRetryPerAction, min = 0, max = 10) {
            onUpdate(agent.copy(maxRetryPerAction = it))
        }
        SwitchRow("Loop Detection", agent.loopDetection) { onUpdate(agent.copy(loopDetection = it)) }
        IntFieldRow("Detection Window (steps)", agent.loopDetectionWindow, min = 2, max = 50) {
            onUpdate(agent.copy(loopDetectionWindow = it))
        }
        IntFieldRow("Same Action Threshold", agent.sameActionThreshold, min = 2, max = 20) {
            onUpdate(agent.copy(sameActionThreshold = it))
        }
        SwitchRow("Auto Recovery", agent.autoRecovery) { onUpdate(agent.copy(autoRecovery = it)) }

        HorizontalDivider()
        Text("Advanced Agent Behavior（数据预埋）", style = MaterialTheme.typography.labelMedium)
        SwitchRow("Reflection", agent.reflection,
            description = "启用生成后的自我评审与修正") { onUpdate(agent.copy(reflection = it)) }
        SwitchRow("Planning", agent.planning) { onUpdate(agent.copy(planning = it)) }
        SwitchRow("Replanning", agent.replanning) { onUpdate(agent.copy(replanning = it)) }
        SwitchRow("Parallel Tool Execution", agent.parallelToolExecution) {
            onUpdate(agent.copy(parallelToolExecution = it))
        }
        SwitchRow("Background Execution", agent.backgroundExecution) {
            onUpdate(agent.copy(backgroundExecution = it))
        }
    }
}

@Composable
private fun CompressionSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard(
        "Context Compression · 上下文压缩",
        Icons.Outlined.Storage,
        subtitle = "重启应用 / 新会话后生效"
    ) {
        IntFieldRow("Max Context Tokens", agent.maxContextTokens,
            description = "上下文预算，超出触发压缩",
            min = 1000, max = 10_000_000) { onAgent(agent.copy(maxContextTokens = it)) }
        SliderRow("Compression Threshold", agent.compressionThreshold, 0.5f..0.95f, 8,
            description = "上下文占用达到该比例时触发压缩",
            onValueChange = { onAgent(agent.copy(compressionThreshold = it)) },
            fmt = { "%.2f".format(it) })
        IntFieldRow("Preserve Recent Turns", agent.preserveRecentTurns,
            description = "压缩时保留最近 N 轮对话原文", min = 1, max = 50) {
            onAgent(agent.copy(preserveRecentTurns = it))
        }
        IntFieldRow("Max Tool Output Length", agent.maxToolOutputLength,
            description = "工具输出超出后截断（字符）", min = 200, max = 100_000) {
            onAgent(agent.copy(maxToolOutputLength = it))
        }
    }
}

// ───────────────────────────── 界面页分区 ─────────────────────────────

@Composable
private fun AppearanceSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard("外观 · Appearance", Icons.Outlined.Palette, initiallyExpanded = true) {
        DropdownRow("主题模式",
            listOf(
                "system" to "跟随系统",
                "dark" to "深色",
                "light" to "浅色",
            ),
            agent.themeMode,
            description = "立即生效") {
            onAgent(agent.copy(themeMode = it))
        }
        SwitchRow("Dynamic Color（动态取色）", agent.dynamicColor,
            description = "Android 12+ 按系统壁纸取色，覆盖默认配色") {
            onAgent(agent.copy(dynamicColor = it))
        }
        SliderRow("字体缩放", agent.fontScale, 0.8f..1.4f, 12,
            description = "全局字体缩放，立即生效",
            onValueChange = { onAgent(agent.copy(fontScale = it)) },
            fmt = { "${(it * 100).roundToInt()}%" })
    }
}

@Composable
private fun ChatDisplaySection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard("对话 · Chat Display", Icons.Outlined.Chat) {
        SwitchRow("显示消息时间戳", agent.showTimestamps,
            description = "在消息气泡旁显示 HH:mm 时间戳，立即生效") {
            onAgent(agent.copy(showTimestamps = it))
        }
    }
}

@Composable
private fun NotesSection() {
    SectionCard("说明 · Notes", Icons.Outlined.Info) {
        Text(
            "· 终端字号、最大行数、单色模式等在「终端」页的「终端外观」卡片中设置。\n" +
            "· 部分开关为数据预埋（标注见各项说明），由对应引擎层后续接入。\n" +
            "· 模型 Profile / Provider / 角色映射等改动即时保存，Agent 引擎参数重启应用后生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ───────────────────────────── 对话框 ─────────────────────────────

@Composable
private fun ProvidersDialog(
    providers: List<ProviderConfig>,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
        title = { Text("Providers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { prov ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(prov.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (prov.isBuiltIn) AssistChip(onClick = {}, label = { Text("内置") })
                            }
                            Text(prov.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Text("Keys: ${prov.apiKeys.size}", style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { editing = prov }) { Text("编辑") }
                                if (!prov.isBuiltIn) TextButton(onClick = { viewModel.deleteProvider(prov.id) }) { Text("删除") }
                            }
                        }
                    }
                }
                Button(onClick = {
                    editing = ProviderConfig(id = "prov_${System.currentTimeMillis()}", displayName = "New Provider", baseUrl = "https://")
                }) { Text("+ 添加 Provider") }
            }
        }
    )
    editing?.let { prov ->
        ProviderEditorDialog(prov, viewModel) { editing = null }
    }
}

@Composable
private fun ProviderEditorDialog(
    provider: ProviderConfig,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var p by remember { mutableStateOf(provider) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { viewModel.upsertProvider(p); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text("编辑 Provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldRow("Display Name", p.displayName) { p = p.copy(displayName = it) }
                TextFieldRow("Base URL", p.baseUrl, description = "OpenAI 兼容端点，如 https://api.openai.com/v1") { p = p.copy(baseUrl = it) }
                DropdownRow("Auth Type", AuthType.values().map { it to it.name }, p.authType) { p = p.copy(authType = it) }
                TextFieldRow("Organization", p.organization) { p = p.copy(organization = it) }
                TextFieldRow("Project", p.project) { p = p.copy(project = it) }
                // 多 Key + 轮换策略
                Text("API Keys（每行一个）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    p.apiKeys.joinToString("\n"),
                    { p = p.copy(apiKeys = it.lines().map { s -> s.trim() }.filter { s -> s.isNotBlank() }) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 6
                )
                DropdownRow("Key Rotation",
                    listOf(
                        KeyRotationMode.DISABLED to "DISABLED · 禁用",
                        KeyRotationMode.SEQUENTIAL to "SEQUENTIAL · 顺序轮换",
                        KeyRotationMode.ON_ERROR to "ON_ERROR · 出错切换",
                        KeyRotationMode.ON_RATE_LIMIT to "ON_RATE_LIMIT · 仅 429 切换",
                    ),
                    p.keyRotationMode,
                    description = "多 Key 轮换策略（数据预埋，客户端后续接入）") {
                    p = p.copy(keyRotationMode = it)
                }
                KeyValueEditor("Default Headers（Provider 级默认请求头）", p.defaultHeaders) {
                    p = p.copy(defaultHeaders = it)
                }
            }
        }
    )
}

@Composable
private fun RolesDialog(
    roles: ModelRoleConfig,
    profiles: List<ModelProfile>,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val options = listOf("" to "未指定") + profiles.map { it.id to it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("完成") } },
        title = { Text("Model Roles · 多模型角色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelRole.values().forEach { role ->
                    val current = roles.profileIdFor(role)
                    DropdownRow(role.label, options, current) {
                        viewModel.updateRoles {
                            when (role) {
                                ModelRole.PRIMARY -> copy(primaryProfileId = it)
                                ModelRole.VISION -> copy(visionProfileId = it)
                                ModelRole.REASONING -> copy(reasoningProfileId = it)
                                ModelRole.FAST -> copy(fastProfileId = it)
                                ModelRole.SUMMARY -> copy(summaryProfileId = it)
                            }
                        }
                    }
                }
                Text("角色映射已保存；真正的多 client 路由由 Agent 引擎后续接入。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    )
}
