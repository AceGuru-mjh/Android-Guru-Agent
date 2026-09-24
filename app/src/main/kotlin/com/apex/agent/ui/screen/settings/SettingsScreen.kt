@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import com.apex.agent.R
import com.apex.agent.core.llm.*
import com.apex.agent.ui.theme.AccentPalette
import com.apex.agent.ui.theme.accentSwatchColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

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
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val agent by viewModel.agentSettings.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf(viewModel.defaultProfile.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showProviders by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) } // 修复：测试连接期间禁用按钮，防连点并发请求
    val scope = rememberCoroutineScope()

    // Agent 设置统一更新入口
    val onAgent: (AgentSettings) -> Unit = { next -> viewModel.updateAgentSettings { next } }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            selected?.let {
                                scope.launch {
                                    isTesting = true
                                    try {
                                        testResult = viewModel.testConnection(it.id)
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            }
                        },
                        enabled = selected != null && !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Science, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isTesting) stringResource(R.string.settings_testing)
                            else stringResource(R.string.settings_test)
                        )
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
                    text = { Text(stringResource(R.string.settings_tab_models)) },
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
                    text = { Text(stringResource(R.string.settings_tab_interface)) },
                    icon = { Icon(Icons.Outlined.Palette, null) }
                )
            }

            when (selectedTab) {
                0 -> ModelsTab(
                    profiles = profiles,
                    selected = selected,
                    viewModel = viewModel,
                    onSelect = { selectedId = it },
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
            title = { Text(if (r.success) stringResource(R.string.settings_conn_success) else stringResource(R.string.settings_conn_failed)) },
            text = { Text(r.message) }
        )
    }
}

// ───────────────────────────── 页签容器 ─────────────────────────────

@Composable
private fun ModelsTab(
    profiles: List<ModelProfile>,
    selected: ModelProfile?,
    viewModel: SettingsViewModel,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
) {
    // 高级参数分区（Generation / Reasoning / …）仍走统一 upsert 入口
    val onUpdate: (ModelProfile) -> Unit = { viewModel.upsertProfile(it) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 合并后的单卡：多档案管理 + 选服务商 → URL 预填 → 填 Key → 拉取真实模型
        // （原 ModelsSection 模型档案卡已并入，删除/重置/能力标记编辑也随之迁移）
        ModelSetupCard(
            profiles = profiles,
            selected = selected,
            viewModel = viewModel,
            onSelect = onSelect,
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
        AboutSection()
    }
}

// ───────────────────────────── 复用控件 ─────────────────────────────

@Composable
internal fun SectionCard(
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
            onValueChangeFinished = { onValueChange(local.coerceIn(range)) },
            modifier = Modifier.semantics { contentDescription = label } // 修复：TalkBack 播报滑块名称
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
// 原 ModelsSection（模型档案卡）与 CapabilityEditor 已并入 ModelSetupCard.kt
// （档案选择 / 新建复制重置删除 / 名称 / Capabilities 均在合并后的单卡内）。
// （上游新增的 ImageGen / VideoGen 能力位已同步迁移至 ModelSetupCard 的 CapabilityEditor。）

@Composable
private fun GenerationSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_generation), Icons.Outlined.Tune) {
        SliderRow("Temperature", p.temperature, 0f..2f, 20,
            description = stringResource(R.string.settings_temperature_desc),
            onValueChange = { onUpdate(p.copy(temperature = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Top P", p.topP, 0f..1f, 10,
            description = stringResource(R.string.settings_top_p_desc),
            onValueChange = { onUpdate(p.copy(topP = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow(stringResource(R.string.settings_top_k_label), p.topK.toFloat(), 0f..200f, 200,
            description = stringResource(R.string.settings_top_k_desc),
            onValueChange = { onUpdate(p.copy(topK = it.toInt())) }, fmt = { it.toInt().toString() })
        SliderRow("Min P", p.minP, 0f..1f, 20,
            description = stringResource(R.string.settings_min_p_desc),
            onValueChange = { onUpdate(p.copy(minP = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Frequency Penalty", p.frequencyPenalty, -2f..2f, 40,
            description = stringResource(R.string.settings_freq_penalty_desc),
            onValueChange = { onUpdate(p.copy(frequencyPenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Presence Penalty", p.presencePenalty, -2f..2f, 40,
            description = stringResource(R.string.settings_presence_penalty_desc),
            onValueChange = { onUpdate(p.copy(presencePenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Repetition Penalty", p.repetitionPenalty, 0f..2f, 40,
            description = stringResource(R.string.settings_rep_penalty_desc),
            onValueChange = { onUpdate(p.copy(repetitionPenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        IntFieldRow("Seed (0=Auto)", p.seed?.toInt() ?: 0,
            description = stringResource(R.string.settings_seed_desc)) {
            onUpdate(p.copy(seed = if (it == 0) null else it.toLong()))
        }
        TextFieldRow(stringResource(R.string.settings_stop_seq_label), p.stopSequences.joinToString(","),
            description = stringResource(R.string.settings_stop_seq_desc)) {
            onUpdate(p.copy(stopSequences = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }))
        }
        IntFieldRow("Max Output Tokens", p.maxOutputTokens,
            description = stringResource(R.string.settings_max_output_desc), min = 256, max = 1_000_000) {
            onUpdate(p.copy(maxOutputTokens = it))
        }
    }
}

@Composable
private fun ReasoningSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_reasoning), Icons.Outlined.Psychology) {
        Text("Reasoning Effort", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ReasoningEffort.values().toList()) { eff ->
                FilterChip(selected = p.reasoningEffort == eff,
                    onClick = { onUpdate(p.copy(reasoningEffort = eff)) },
                    label = { Text(eff.name) })
            }
        }
        DescriptionText(stringResource(R.string.settings_effort_desc))
        DropdownRow("Thinking Budget (tokens)",
            listOf(null to "Auto", 1024 to "1024", 2048 to "2048", 4096 to "4096",
                8192 to "8192", 16384 to "16384", 32768 to "32768", 65536 to "65536"),
            p.thinkingBudget,
            description = stringResource(R.string.settings_budget_desc)) {
            onUpdate(p.copy(thinkingBudget = it))
        }
        SwitchRow("Show Thinking", p.showThinking,
            description = stringResource(R.string.settings_show_thinking_desc)) {
            onUpdate(p.copy(showThinking = it))
        }
    }
}

@Composable
private fun ContextSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_context), Icons.Outlined.Subject) {
        DropdownRow(
            stringResource(R.string.settings_ctx_window_label),
            listOf(
                8000 to "8K", 16_000 to "16K", 32_000 to "32K", 64_000 to "64K",
                128_000 to "128K", 200_000 to "200K", 262_144 to "256K",
                524_288 to "512K", 1_000_000 to "1M"
            ),
            p.contextWindow,
            description = stringResource(R.string.settings_ctx_window_desc)
        ) { onUpdate(p.copy(contextWindow = it)) }
        IntFieldRow(stringResource(R.string.settings_ctx_exact_label), p.contextWindow,
            description = stringResource(R.string.settings_ctx_exact_desc, p.displayContext()),
            min = 1000, max = 10_000_000) {
            onUpdate(p.copy(contextWindow = it))
        }
        IntFieldRow(stringResource(R.string.settings_reserved_label), p.reservedOutputTokens,
            description = stringResource(R.string.settings_reserved_desc),
            min = 0, max = 100_000) {
            onUpdate(p.copy(reservedOutputTokens = it))
        }
    }
}

@Composable
private fun ToolsSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_tools), Icons.Outlined.Build) {
        SwitchRow("Enable Tools", p.enableTools,
            description = stringResource(R.string.settings_enable_tools_desc)) {
            onUpdate(p.copy(enableTools = it))
        }
        DropdownRow("Tool Choice",
            ToolChoiceMode.values().map { it to it.name }, p.toolChoice,
            description = stringResource(R.string.settings_tool_choice_desc)) {
            onUpdate(p.copy(toolChoice = it))
        }
        SwitchRow("Parallel Tool Calls", p.parallelToolCalls,
            description = stringResource(R.string.settings_parallel_calls_desc)) {
            onUpdate(p.copy(parallelToolCalls = it))
        }
        IntFieldRow("Max Tool Calls / Turn", p.maxToolCalls,
            description = stringResource(R.string.settings_max_tool_calls_desc), min = 1, max = 100) {
            onUpdate(p.copy(maxToolCalls = it))
        }
        IntFieldRow("Tool Call Timeout (s)", p.toolTimeoutSeconds,
            description = stringResource(R.string.settings_tool_timeout_desc), min = 1, max = 3600) {
            onUpdate(p.copy(toolTimeoutSeconds = it))
        }
        IntFieldRow("Max Tool Result Tokens", p.maxToolResultTokens,
            description = stringResource(R.string.settings_tool_result_desc), min = 100, max = 100_000) {
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
    SectionCard(stringResource(R.string.settings_section_vision), Icons.Outlined.Visibility) {
        SwitchRow("Enable Vision", agent.visionEnabled,
            description = stringResource(R.string.settings_vision_desc)) {
            viewModel.updateAgentSettings { copy(visionEnabled = it) }
        }
        DropdownRow("Screenshot Quality",
            listOf("auto" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High"),
            agent.screenshotQuality,
            description = stringResource(R.string.settings_screenshot_quality_desc)) {
            viewModel.updateAgentSettings { copy(screenshotQuality = it) }
        }
        IntFieldRow("Max Screenshots in Context", agent.maxScreenshots,
            description = stringResource(R.string.settings_max_screenshots_desc), min = 1, max = 20) {
            viewModel.updateAgentSettings { copy(maxScreenshots = it) }
        }
        DropdownRow(stringResource(R.string.settings_vision_model_label),
            listOf("" to stringResource(R.string.settings_not_set)) + profiles.map { it.id to it.name },
            roles.visionProfileId) {
            viewModel.updateRoles { copy(visionProfileId = it) }
        }
    }
}

@Composable
private fun NetworkSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_network), Icons.Outlined.Cloud) {
        IntFieldRow("Connect Timeout (ms)", p.connectTimeoutMs.toInt(),
            min = 1000, max = 300_000) { onUpdate(p.copy(connectTimeoutMs = it.toLong())) }
        IntFieldRow("Read Timeout (ms)", p.readTimeoutMs.toInt(),
            description = stringResource(R.string.settings_read_timeout_desc),
            min = 5_000, max = 600_000) { onUpdate(p.copy(readTimeoutMs = it.toLong())) }
        IntFieldRow("Write Timeout (ms)", p.writeTimeoutMs.toInt(),
            min = 1_000, max = 300_000) { onUpdate(p.copy(writeTimeoutMs = it.toLong())) }
        IntFieldRow("Request Timeout (ms)", p.requestTimeoutMs.toInt(),
            description = stringResource(R.string.settings_request_timeout_desc),
            min = 5_000, max = 600_000) { onUpdate(p.copy(requestTimeoutMs = it.toLong())) }
        IntFieldRow("Retry Count", p.retryCount,
            description = stringResource(R.string.settings_retry_count_desc), min = 0, max = 10) {
            onUpdate(p.copy(retryCount = it))
        }
        IntFieldRow("Retry Delay (ms)", p.retryDelayMs.toInt(),
            description = stringResource(R.string.settings_retry_delay_desc), min = 0, max = 60_000) {
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
            description = stringResource(R.string.settings_streaming_desc)) {
            onUpdate(p.copy(streaming = it))
        }
        SwitchRow("Keep Alive", p.keepAlive,
            description = stringResource(R.string.settings_keep_alive_desc)) {
            onUpdate(p.copy(keepAlive = it))
        }
    }
}

@Composable
private fun PromptSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_prompt), Icons.Outlined.EditNote) {
        OutlinedTextField(p.systemPromptPrefix, { onUpdate(p.copy(systemPromptPrefix = it)) },
            label = { Text(stringResource(R.string.settings_prompt_prefix_label)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            maxLines = 6)
        // 修复：下拉选中值随用户选择联动（原硬编码 "default"，选后仍显示 Default）
        var presetChoice by remember(p.id) {
            mutableStateOf(
                when (p.systemPromptPrefix) {
                    PROMPT_PRESET_ANDROID -> "android_expert"
                    PROMPT_PRESET_CODING -> "coding"
                    PROMPT_PRESET_AUTOMATION -> "automation"
                    "" -> "default"
                    else -> "default" // 手改前缀无法反推预设，回退 default
                }
            )
        }
        DropdownRow("Prompt Preset",
            listOf(
                "default" to stringResource(R.string.settings_preset_default),
                "android_expert" to "Android Expert",
                "coding" to "Coding Agent",
                "automation" to "Automation Agent",
            ),
            presetChoice,
            description = stringResource(R.string.settings_preset_desc)
        ) { preset ->
            presetChoice = preset
            val template = when (preset) {
                "android_expert" -> PROMPT_PRESET_ANDROID
                "coding" -> PROMPT_PRESET_CODING
                "automation" -> PROMPT_PRESET_AUTOMATION
                else -> ""
            }
            onUpdate(p.copy(systemPromptPrefix = template))
        }
    }
}

// Prompt Preset 模板（供选中值反推复用）
private const val PROMPT_PRESET_ANDROID =
    "You are an expert Android engineer. Prefer adb, gradle and Kotlin; verify with build output before claiming success."
private const val PROMPT_PRESET_CODING =
    "You are a senior coding agent. Write clean, production-ready code; explain key decisions briefly."
private const val PROMPT_PRESET_AUTOMATION =
    "You are a device automation agent. Plan minimal, reliable UI steps and verify after each action."

@Composable
private fun AdvancedSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_advanced), Icons.Outlined.Settings) {
        DropdownRow("Structured Output",
            StructuredOutputMode.values().map { it to it.name }, p.structuredOutputMode) {
            onUpdate(p.copy(structuredOutputMode = it))
        }
        SwitchRow("Strict Schema", p.structuredOutputStrict,
            description = stringResource(R.string.settings_strict_schema_desc)) {
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
    SectionCard(stringResource(R.string.settings_section_agent), Icons.Outlined.Psychology, initiallyExpanded = true) {
        DropdownRow("Execution Mode",
            listOf(
                "auto" to stringResource(R.string.settings_mode_auto),
                "build" to stringResource(R.string.settings_mode_build),
                "chat" to stringResource(R.string.settings_mode_chat),
                "plan" to stringResource(R.string.settings_mode_plan),
                "spec" to stringResource(R.string.settings_mode_spec),
                "reflect" to stringResource(R.string.settings_mode_reflect),
                "assist" to stringResource(R.string.settings_mode_assist),
                "custom" to stringResource(R.string.settings_mode_custom),
            ),
            agent.defaultMode,
            description = stringResource(R.string.settings_mode_desc)) {
            onUpdate(agent.copy(defaultMode = it))
        }
        DropdownRow("Think Level",
            listOf(
                "minimal" to stringResource(R.string.settings_think_minimal),
                "light" to stringResource(R.string.settings_think_light),
                "standard" to stringResource(R.string.settings_think_standard),
                "deep" to stringResource(R.string.settings_think_deep),
                "maximum" to stringResource(R.string.settings_think_maximum),
            ),
            agent.thinkLevel,
            description = stringResource(R.string.settings_think_desc)) {
            onUpdate(agent.copy(thinkLevel = it))
        }
        IntFieldRow("Max Iterations", agent.maxIterations,
            description = stringResource(R.string.settings_max_iterations_desc), min = 1, max = 200) {
            onUpdate(agent.copy(maxIterations = it))
        }
        SwitchRow("Keep Alive", agent.keepAlive) { onUpdate(agent.copy(keepAlive = it)) }
        SliderRow("Reflection Rounds", agent.reflectionRounds.toFloat(), 1f..3f, 2,
            description = stringResource(R.string.settings_reflection_rounds_desc),
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
        Text(stringResource(R.string.settings_advanced_behavior), style = MaterialTheme.typography.labelMedium)
        SwitchRow("Reflection", agent.reflection,
            description = stringResource(R.string.settings_reflection_desc)) { onUpdate(agent.copy(reflection = it)) }
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
        stringResource(R.string.settings_section_compression),
        Icons.Outlined.Storage,
        subtitle = stringResource(R.string.settings_compression_subtitle)
    ) {
        IntFieldRow("Max Context Tokens", agent.maxContextTokens,
            description = stringResource(R.string.settings_max_ctx_tokens_desc),
            min = 1000, max = 10_000_000) { onAgent(agent.copy(maxContextTokens = it)) }
        SliderRow("Compression Threshold", agent.compressionThreshold, 0.5f..0.95f, 8,
            description = stringResource(R.string.settings_threshold_desc),
            onValueChange = { onAgent(agent.copy(compressionThreshold = it)) },
            fmt = { String.format(Locale.US, "%.2f", it) })
        IntFieldRow("Preserve Recent Turns", agent.preserveRecentTurns,
            description = stringResource(R.string.settings_preserve_turns_desc), min = 1, max = 50) {
            onAgent(agent.copy(preserveRecentTurns = it))
        }
        IntFieldRow("Max Tool Output Length", agent.maxToolOutputLength,
            description = stringResource(R.string.settings_tool_output_len_desc), min = 200, max = 100_000) {
            onAgent(agent.copy(maxToolOutputLength = it))
        }
    }
}

// ───────────────────────────── 界面页分区 ─────────────────────────────

@Composable
private fun AppearanceSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_appearance), Icons.Outlined.Palette, initiallyExpanded = true) {
        // 语言（system | zh | en；切换后 MainActivity recreate 生效，见 LanguageManager）
        DropdownRow(
            stringResource(R.string.settings_language),
            listOf(
                "system" to stringResource(R.string.settings_language_system),
                "zh" to stringResource(R.string.settings_language_chinese),
                "en" to stringResource(R.string.settings_language_english),
            ),
            agent.language,
            description = stringResource(R.string.settings_language_desc)
        ) {
            onAgent(agent.copy(language = it))
        }
        DropdownRow(stringResource(R.string.settings_theme_mode),
            listOf(
                "system" to stringResource(R.string.settings_theme_system),
                "dark" to stringResource(R.string.settings_theme_dark),
                "light" to stringResource(R.string.settings_theme_light),
            ),
            agent.themeMode,
            description = stringResource(R.string.settings_apply_now)) {
            onAgent(agent.copy(themeMode = it))
        }
        SwitchRow(stringResource(R.string.settings_dynamic_color), agent.dynamicColor,
            description = stringResource(R.string.settings_dynamic_color_desc)) {
            onAgent(agent.copy(dynamicColor = it))
        }
        AccentPaletteRow(
            selected = AccentPalette.fromKey(agent.accentPalette),
            onSelect = { onAgent(agent.copy(accentPalette = it.key)) }
        )
        SliderRow(stringResource(R.string.settings_font_scale), agent.fontScale, 0.8f..1.4f, 12,
            description = stringResource(R.string.settings_font_scale_desc),
            onValueChange = { onAgent(agent.copy(fontScale = it)) },
            fmt = { "${(it * 100).roundToInt()}%" })
    }
}

/**
 * 预设主题配色选择器：色点 + 名称的横向滚动行。
 *
 * 色点颜色跟随当前深浅态（深色态展示霓虹提亮色，浅色态展示可读深色），
 * 选中项以 onSurface 描边环 + 主色勾标标记；Dynamic Color 开启时
 * 预设被覆盖，但仍可预选（关闭动态取色后立即生效）。
 */
@Composable
private fun AccentPaletteRow(
    selected: AccentPalette,
    onSelect: (AccentPalette) -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_accent_palettes), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(AccentPalette.entries) { palette ->
                val swatch = accentSwatchColor(palette, dark)
                val isSelected = palette == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(palette) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.settings_accent_selected),
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        palette.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        DescriptionText(stringResource(R.string.settings_accent_desc))
    }
}

@Composable
private fun ChatDisplaySection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_chat), Icons.Outlined.Chat) {
        SwitchRow(stringResource(R.string.settings_timestamps), agent.showTimestamps,
            description = stringResource(R.string.settings_timestamps_desc)) {
            onAgent(agent.copy(showTimestamps = it))
        }
        // 发送键行为：send → IME「发送」直接发出；newline → IME「换行」（发送用按钮）
        DropdownRow(
            stringResource(R.string.settings_send_key),
            listOf(
                "send" to stringResource(R.string.settings_send_key_send),
                "newline" to stringResource(R.string.settings_send_key_newline),
            ),
            agent.sendKeyBehavior,
            description = stringResource(R.string.settings_send_key_desc)
        ) {
            onAgent(agent.copy(sendKeyBehavior = it))
        }
        // 任务总结卡片：默认隐藏（不占空间），开启后任务完成时显示总结卡
        SwitchRow(
            stringResource(R.string.settings_show_run_summary),
            agent.showRunSummary,
            description = stringResource(R.string.settings_show_run_summary_desc)
        ) {
            onAgent(agent.copy(showRunSummary = it))
        }
    }
}

@Composable
private fun NotesSection() {
    SectionCard(stringResource(R.string.settings_section_notes), Icons.Outlined.Info) {
        Text(
            stringResource(R.string.settings_notes_body),
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
    var deleteProvTarget by remember { mutableStateOf<ProviderConfig?>(null) } // 修复：删除 Provider 加确认（连带静默重指派其下档案）
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_close)) } },
        title = { Text("Providers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { prov ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(prov.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (prov.isBuiltIn) {
                                    // 修复：原 AssistChip(onClick={}) 渲染为可点击涟漪但无任何动作 —— 改为纯静态徽标
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            stringResource(R.string.settings_builtin),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Text(prov.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Text("Keys: ${prov.apiKeys.size}", style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { editing = prov }) { Text(stringResource(R.string.settings_edit)) }
                                if (!prov.isBuiltIn) TextButton(onClick = { deleteProvTarget = prov }) { Text(stringResource(R.string.settings_delete)) }
                            }
                        }
                    }
                }
                Button(onClick = {
                    editing = ProviderConfig(id = "prov_${System.currentTimeMillis()}", displayName = "New Provider", baseUrl = "https://")
                }) { Text(stringResource(R.string.settings_add_provider)) }
            }
        }
    )
    editing?.let { prov ->
        ProviderEditorDialog(prov, viewModel) { editing = null }
    }

    deleteProvTarget?.let { prov ->
        AlertDialog(
            onDismissRequest = { deleteProvTarget = null },
            title = { Text(stringResource(R.string.settings_delete_provider_title)) },
            text = { Text(stringResource(R.string.settings_delete_provider_text, prov.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProvider(prov.id)
                    deleteProvTarget = null
                }) { Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteProvTarget = null }) { Text(stringResource(R.string.settings_cancel)) } }
        )
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
        confirmButton = { TextButton(onClick = { viewModel.upsertProvider(p); onDismiss() }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(stringResource(R.string.settings_edit_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldRow("Display Name", p.displayName) { p = p.copy(displayName = it) }
                TextFieldRow("Base URL", p.baseUrl, description = stringResource(R.string.settings_base_url_hint)) { p = p.copy(baseUrl = it) }
                DropdownRow("Auth Type", AuthType.values().map { it to it.name }, p.authType) { p = p.copy(authType = it) }
                TextFieldRow("Organization", p.organization) { p = p.copy(organization = it) }
                TextFieldRow("Project", p.project) { p = p.copy(project = it) }
                // 多 Key + 轮换策略
                Text(stringResource(R.string.settings_keys_hint), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    p.apiKeys.joinToString("\n"),
                    { p = p.copy(apiKeys = it.lines().map { s -> s.trim() }.filter { s -> s.isNotBlank() }) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 6
                )
                DropdownRow("Key Rotation",
                    listOf(
                        KeyRotationMode.DISABLED to stringResource(R.string.settings_rotation_disabled),
                        KeyRotationMode.SEQUENTIAL to stringResource(R.string.settings_rotation_sequential),
                        KeyRotationMode.ON_ERROR to stringResource(R.string.settings_rotation_on_error),
                        KeyRotationMode.ON_RATE_LIMIT to stringResource(R.string.settings_rotation_rate_limit),
                    ),
                    p.keyRotationMode,
                    description = stringResource(R.string.settings_rotation_desc)) {
                    p = p.copy(keyRotationMode = it)
                }
                KeyValueEditor(stringResource(R.string.settings_default_headers), p.defaultHeaders) {
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
    val options = listOf("" to stringResource(R.string.settings_not_set)) + profiles.map { it.id to it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_done)) } },
        title = { Text(stringResource(R.string.settings_model_roles)) },
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
                Text(stringResource(R.string.settings_role_note),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    )
}
