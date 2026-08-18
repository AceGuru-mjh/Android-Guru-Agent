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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.apex.agent.core.llm.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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

    var showProviders by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings · 模型控制中心") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前编辑的 Profile
            selected?.let { profile ->
                AssistChip(
                    onClick = { },
                    label = { Text("编辑模型：${profile.name} · ${profile.modelId}") },
                    leadingIcon = { Icon(Icons.Outlined.SmartToy, null) }
                )
            }

            // ── Models ──
            ModelsSection(
                profiles = profiles,
                providers = providers,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onAdd = {
                    val id = "profile_${System.currentTimeMillis()}"
                    val p = ModelProfile(id = id, name = "新模型", providerId = providers.firstOrNull()?.id ?: "")
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

            // ── Generation ──
            selected?.let { p ->
                GenerationSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Reasoning ──
            selected?.let { p ->
                ReasoningSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Context ──
            selected?.let { p ->
                ContextSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Tools ──
            selected?.let { p ->
                ToolsSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Vision ──
            VisionSection(agent, roles, profiles, viewModel)

            // ── Agent ──
            AgentSection(agent) { viewModel.updateAgentSettings(it) }

            // ── Network ──
            selected?.let { p ->
                NetworkSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Prompt ──
            selected?.let { p ->
                PromptSection(p) { viewModel.upsertProfile(it) }
            }

            // ── Advanced ──
            selected?.let { p ->
                AdvancedSection(p) { viewModel.upsertProfile(it) }
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

// ───────────────────────────── 复用控件 ─────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    fmt: (Float) -> String = { it.toString() }
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(fmt(value), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, { onValueChange(it.coerceIn(range)) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun IntFieldRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun TextFieldRow(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    OutlinedTextField(
        value = current,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
    )
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            confirmButton = { },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (v, text) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            onSelected(v); expanded = false
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = v == selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(text)
                        }
                    }
                }
            }
        )
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

// ───────────────────────────── 各分区 ─────────────────────────────

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
    SectionCard("Models · 模型档案", Icons.Outlined.SmartToy, initiallyExpanded = true) {
        profiles.forEach { p ->
            val prov = providers.firstOrNull { it.id == p.providerId }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == selectedId, onClick = { onSelect(p.id) })
                        Text(p.name, style = MaterialTheme.typography.titleSmall, Modifier.weight(1f))
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
            TextFieldRow("Model ID", p.modelId) { onUpdate(p.copy(modelId = it)) }
            Text("Capabilities", style = MaterialTheme.typography.labelMedium)
            CapabilityEditor(p.capabilities) { onUpdate(p.copy(capabilities = it)) }
        }
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
            { onUpdate(p.copy(temperature = it)) }, { "%.2f".format(it) })
        SliderRow("Top P", p.topP, 0f..1f, 10,
            { onUpdate(p.copy(topP = it)) }, { "%.2f".format(it) })
        SliderRow("Top K (0=禁用)", p.topK.toFloat(), 0f..200f, 200,
            { onUpdate(p.copy(topK = it.toInt())) }, { it.toInt().toString() })
        SliderRow("Min P", p.minP, 0f..1f, 20,
            { onUpdate(p.copy(minP = it)) }, { "%.2f".format(it) })
        SliderRow("Frequency Penalty", p.frequencyPenalty, -2f..2f, 40,
            { onUpdate(p.copy(frequencyPenalty = it)) }, { "%.2f".format(it) })
        SliderRow("Presence Penalty", p.presencePenalty, -2f..2f, 40,
            { onUpdate(p.copy(presencePenalty = it)) }, { "%.2f".format(it) })
        SliderRow("Repetition Penalty", p.repetitionPenalty, 0f..2f, 40,
            { onUpdate(p.copy(repetitionPenalty = it)) }, { "%.2f".format(it) })
        IntFieldRow("Seed (0=Auto)", p.seed?.toInt() ?: 0) {
            onUpdate(p.copy(seed = if (it == 0) null else it.toLong()))
        }
        TextFieldRow("Stop Sequences (逗号分隔)", p.stopSequences.joinToString(",")) {
            onUpdate(p.copy(stopSequences = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }))
        }
        IntFieldRow("Max Output Tokens", p.maxOutputTokens) { onUpdate(p.copy(maxOutputTokens = it)) }
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
        DropdownRow("Thinking Budget (tokens)",
            listOf(null to "Auto", 1024 to "1024", 2048 to "2048", 4096 to "4096",
                8192 to "8192", 16384 to "16384", 32768 to "32768", 65536 to "65536"),
            p.thinkingBudget) { onUpdate(p.copy(thinkingBudget = it)) }
        SwitchRow("Show Thinking", p.showThinking) { onUpdate(p.copy(showThinking = it)) }
        Text("注：Reasoning Effort 与 Thinking Budget 不强行绑定（不同 Provider 语义不同）。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ContextSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Context · 上下文", Icons.Outlined.Subject) {
        DropdownRow("Context Window",
            listOf(32000 to "32K", 64000 to "64K", 128000 to "128K", 200000 to "200K",
                1000000 to "1M"),
            p.contextWindow) { onUpdate(p.copy(contextWindow = it)) }
        IntFieldRow("Reserved Output Tokens (Agent 预留)", p.reservedOutputTokens) {
            onUpdate(p.copy(reservedOutputTokens = it))
        }
    }
}

@Composable
private fun ToolsSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Tools · 工具调用", Icons.Outlined.Build) {
        SwitchRow("Enable Tools", p.enableTools) { onUpdate(p.copy(enableTools = it)) }
        DropdownRow("Tool Choice",
            ToolChoiceMode.values().map { it to it.name }, p.toolChoice) {
            onUpdate(p.copy(toolChoice = it))
        }
        SwitchRow("Parallel Tool Calls", p.parallelToolCalls) { onUpdate(p.copy(parallelToolCalls = it)) }
        IntFieldRow("Max Tool Calls / Turn", p.maxToolCalls) { onUpdate(p.copy(maxToolCalls = it)) }
        IntFieldRow("Tool Call Timeout (s)", p.toolTimeoutSeconds) { onUpdate(p.copy(toolTimeoutSeconds = it)) }
        IntFieldRow("Max Tool Result Tokens", p.maxToolResultTokens) { onUpdate(p.copy(maxToolResultTokens = it)) }
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
        SwitchRow("Enable Vision", agent.visionEnabled) {
            viewModel.updateAgentSettings { copy(visionEnabled = it) }
        }
        DropdownRow("Screenshot Quality",
            listOf("auto" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High"),
            agent.screenshotQuality) { viewModel.updateAgentSettings { copy(screenshotQuality = it) } }
        IntFieldRow("Max Screenshots in Context", agent.maxScreenshots) {
            viewModel.updateAgentSettings { copy(maxScreenshots = it) }
        }
        DropdownRow("Vision Model (角色)",
            listOf("" to "未指定") + profiles.map { it.id to it.name },
            roles.visionProfileId) { viewModel.updateRoles { copy(visionProfileId = it) } }
        Text("注：Vision 相关运行时参数（截图质量/数量）为数据预埋，引擎后续接入。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun AgentSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard("Agent · 运行参数", Icons.Outlined.SmartToy) {
        DropdownRow("Execution Mode",
            listOf("auto" to "Auto", "chat" to "Chat", "build" to "Build"),
            agent.defaultMode) { onUpdate(agent.copy(defaultMode = it)) }
        DropdownRow("Think Level",
            listOf("standard" to "Standard", "deep" to "Deep", "minimal" to "Minimal"),
            agent.thinkLevel) { onUpdate(agent.copy(thinkLevel = it)) }
        IntFieldRow("Max Iterations", agent.maxIterations) { onUpdate(agent.copy(maxIterations = it)) }
        SwitchRow("Keep Alive", agent.keepAlive) { onUpdate(agent.copy(keepAlive = it)) }

        HorizontalDivider()
        Text("Retry / Loop Detection", style = MaterialTheme.typography.labelMedium)
        SwitchRow("Auto Retry", agent.autoRetry) { onUpdate(agent.copy(autoRetry = it)) }
        IntFieldRow("Max Retry / Action", agent.maxRetryPerAction) { onUpdate(agent.copy(maxRetryPerAction = it)) }
        SwitchRow("Loop Detection", agent.loopDetection) { onUpdate(agent.copy(loopDetection = it)) }
        IntFieldRow("Detection Window (steps)", agent.loopDetectionWindow) { onUpdate(agent.copy(loopDetectionWindow = it)) }
        IntFieldRow("Same Action Threshold", agent.sameActionThreshold) { onUpdate(agent.copy(sameActionThreshold = it)) }
        SwitchRow("Auto Recovery", agent.autoRecovery) { onUpdate(agent.copy(autoRecovery = it)) }

        HorizontalDivider()
        Text("Advanced Agent Behavior（数据预埋）", style = MaterialTheme.typography.labelMedium)
        SwitchRow("Reflection", agent.reflection) { onUpdate(agent.copy(reflection = it)) }
        SwitchRow("Planning", agent.planning) { onUpdate(agent.copy(planning = it)) }
        SwitchRow("Replanning", agent.replanning) { onUpdate(agent.copy(replanning = it)) }
        SwitchRow("Parallel Tool Execution", agent.parallelToolExecution) { onUpdate(agent.copy(parallelToolExecution = it)) }
        SwitchRow("Background Execution", agent.backgroundExecution) { onUpdate(agent.copy(backgroundExecution = it)) }
    }
}

@Composable
private fun NetworkSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Network · 网络", Icons.Outlined.Cloud) {
        IntFieldRow("Connect Timeout (ms)", p.connectTimeoutMs.toInt()) { onUpdate(p.copy(connectTimeoutMs = it.toLong())) }
        IntFieldRow("Read Timeout (ms)", p.readTimeoutMs.toInt()) { onUpdate(p.copy(readTimeoutMs = it.toLong())) }
        IntFieldRow("Write Timeout (ms)", p.writeTimeoutMs.toInt()) { onUpdate(p.copy(writeTimeoutMs = it.toLong())) }
        IntFieldRow("Request Timeout (ms)", p.requestTimeoutMs.toInt()) { onUpdate(p.copy(requestTimeoutMs = it.toLong())) }
        IntFieldRow("Retry Count", p.retryCount) { onUpdate(p.copy(retryCount = it)) }
        IntFieldRow("Retry Delay (ms)", p.retryDelayMs.toInt()) { onUpdate(p.copy(retryDelayMs = it.toLong())) }
        IntFieldRow("Max Retry Delay (ms)", p.maxRetryDelayMs.toInt()) { onUpdate(p.copy(maxRetryDelayMs = it.toLong())) }
        ChipMultiSelect("Retry On", listOf(408, 429, 500, 502, 503, 504), p.retryOnCodes) {
            val set = p.retryOnCodes.toMutableSet()
            if (set.contains(it)) set.remove(it) else set.add(it)
            onUpdate(p.copy(retryOnCodes = set))
        }
        SwitchRow("Streaming", p.streaming) { onUpdate(p.copy(streaming = it)) }
        SwitchRow("Keep Alive", p.keepAlive) { onUpdate(p.copy(keepAlive = it)) }
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
            listOf("default" to "Default", "android_expert" to "Android Expert",
                "coding" to "Coding Agent", "automation" to "Automation Agent"),
            "default") { /* 预设为占位，后续可按预设注入前缀 */ }
    }
}

@Composable
private fun AdvancedSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard("Advanced · 高级", Icons.Outlined.Settings) {
        DropdownRow("Structured Output",
            StructuredOutputMode.values().map { it to it.name }, p.structuredOutputMode) {
            onUpdate(p.copy(structuredOutputMode = it))
        }
        SwitchRow("Strict Schema", p.structuredOutputStrict) { onUpdate(p.copy(structuredOutputStrict = it)) }
        KeyValueEditor("Custom Headers", p.customHeaders) { onUpdate(p.copy(customHeaders = it)) }
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
                TextFieldRow("Base URL", p.baseUrl) { p = p.copy(baseUrl = it) }
                DropdownRow("Auth Type", AuthType.values().map { it to it.name }, p.authType) { p = p.copy(authType = it) }
                TextFieldRow("Organization", p.organization) { p = p.copy(organization = it) }
                TextFieldRow("Project", p.project) { p = p.copy(project = it) }
                // API Keys（多 Key + 轮换）
                Text("API Keys（每行一个）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    p.apiKeys.joinToString("\n"),
                    { p = p.copy(apiKeys = it.lines().map { s -> s.trim() }.filter { s -> s.isNotBlank() }) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 6
                )
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
