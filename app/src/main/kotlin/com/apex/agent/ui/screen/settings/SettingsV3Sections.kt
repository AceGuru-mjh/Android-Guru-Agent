package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.code.subagent.CustomSubAgent
import com.apex.agent.core.code.subagent.SubAgentSettings
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.ui.theme.AccentPalette
import com.apex.agent.ui.theme.UiStyle
import com.apex.agent.ui.theme.UiStylePickerExcluded
import com.apex.agent.ui.theme.accentSwatchColor
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置页 v3 扩展分区（Task 2-S4）。
 *
 * 两个职责：
 *  1. 预算腾挪：自 SettingsScreen.kt 原样迁入的既有分区（上下文压缩 / 视觉 /
 *     外观 / 聊天显示）——逻辑与字符串零改动，仅可见性 private 改 internal，
 *     SettingsScreen 行数压回预算红线内；
 *  2. v3 新分区：子代理（SubAgentSettings 全预算 + 自定义类型编辑器）、
 *     GOAL 验收默认值、LOOP 循环默认值、Coding 工位工具面与防护、
 *     GitHub 连接状态与默认仓库——全部直接消费 AgentSettings 预埋字段，
 *     经 onUpdate 单通道持久化（无摆设开关）。
 *
 * 行组件（SectionCard / SwitchRow / SliderRow / IntFieldRow / TextFieldRow /
 * DropdownRow / DescriptionText）复用 SettingsScreen.kt 的 internal 定义。
 *
 * GitHub 的真实连接（令牌录入）UI 在 Coding 屏（GithubIconButton），本页
 * 只做状态回显与默认仓库偏好，避免双份连接 UI；GithubTokenManager 的
 * defaultRepo / saveDefaultRepo / clearDefaultRepo 契约由 S3 落地。
 */

// ═══════════════════════════════════════════════════════════════════
// 迁入分区（自 SettingsScreen.kt 原样搬迁，逻辑与字符串零改动）
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun CompressionSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
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

@Composable
internal fun VisionSection(
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
internal fun AppearanceSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_appearance), Icons.Outlined.Palette, initiallyExpanded = true) {
        // 界面风格（极简黑白 / 液态玻璃）—— 与深浅模式正交的全局风格维度，
        // 默认极简黑白；切换立即生效（CompositionLocal 驱动，无需 recreate）
        val uiStyle = UiStyle.fromKey(agent.uiStyle)
        UiStyleRow(selected = uiStyle, onSelect = { onAgent(agent.copy(uiStyle = it.key)) })
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
        if (uiStyle == UiStyle.LIQUID_GLASS) {
            // 液态玻璃风格：Dynamic Color + 预设配色才参与配色合成
            SwitchRow(stringResource(R.string.settings_dynamic_color), agent.dynamicColor,
                description = stringResource(R.string.settings_dynamic_color_desc)) {
                onAgent(agent.copy(dynamicColor = it))
            }
            AccentPaletteRow(
                selected = AccentPalette.fromKey(agent.accentPalette),
                onSelect = { onAgent(agent.copy(accentPalette = it.key)) }
            )
        } else {
            // 极简黑白：配色体系不参与 —— 提示而非隐藏，让用户知道入口在哪
            DescriptionText(stringResource(R.string.settings_ui_style_minimal_hint))
        }
        SliderRow(stringResource(R.string.settings_font_scale), agent.fontScale, 0.8f..1.4f, 12,
            description = stringResource(R.string.settings_font_scale_desc),
            onValueChange = { onAgent(agent.copy(fontScale = it)) },
            fmt = { "${(it * 100).roundToInt()}%" })
    }
}

/**
 * 界面风格选择器：极简黑白 / 液态玻璃 双卡横排。
 *
 * 卡片内含风格预览块（非纯色点）：极简 = 半黑半白拼块 + 发丝分割线；
 * 玻璃 = 主色→ tertiary 对角渐变叠白光带。选中以主色描边环标记，
 * 与 AccentPaletteRow 同一选择器语言。
 */
@Composable
private fun UiStyleRow(selected: UiStyle, onSelect: (UiStyle) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_ui_style), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UiStyleOptionCard(
                style = UiStyle.MINIMAL,
                label = stringResource(R.string.settings_ui_style_minimal),
                description = stringResource(R.string.settings_ui_style_minimal_desc),
                selected = selected == UiStyle.MINIMAL,
                onClick = { onSelect(UiStyle.MINIMAL) },
                modifier = Modifier.weight(1f)
            )
            UiStyleOptionCard(
                style = UiStyle.LIQUID_GLASS,
                label = stringResource(R.string.settings_ui_style_glass),
                description = stringResource(R.string.settings_ui_style_glass_desc),
                selected = selected == UiStyle.LIQUID_GLASS,
                onClick = { onSelect(UiStyle.LIQUID_GLASS) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        DescriptionText(stringResource(R.string.settings_ui_style_desc))
    }
}

/** 单张风格选项卡：预览块 + 标题 + 一句描述。 */
@Composable
private fun UiStyleOptionCard(
    style: UiStyle,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 风格预览块：宽幅色带，直观展示两种材质语言
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (style == UiStyle.MINIMAL) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    } else {
                        Modifier.background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
                                )
                            )
                        )
                    }
                )
        ) {
            if (style == UiStyle.MINIMAL) {
                // 极简预览：左墨右纸拼块 + 中缝发丝线
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.onSurface)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
            } else {
                // 玻璃预览：白色斜向光带（玻璃受光的标志性语言）
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                        )
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.settings_accent_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(16.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape
                        )
                        .padding(2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            items(UiStylePickerExcluded, key = { it.name }) { palette ->
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
internal fun ChatDisplaySection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
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

// ═══════════════════════════════════════════════════════════════════
// v3 新分区一：子代理（预算全量 + 自定义类型编辑器）
// ═══════════════════════════════════════════════════════════════════

/**
 * 子代理分区：SubAgentSettings 的全部预算旋钮 + 自定义类型编辑器。
 *
 * 预算写入统一经 [SubAgentSettings.sanitized] 钳制（防御式：任何形状输入
 * 收敛合法区间）；自定义类型由 code_task 的 custom 类型 + custom_name
 * 按名解析（内置四类型之外）。key 不可与保留字或既有自定义 key 冲突。
 */
@Composable
internal fun SubAgentSettingsSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    val sub = agent.subagent
    // 预算字段统一钳制后落盘（滑块步进已保证合法，sanitized 双保险）
    val updateSub: (SubAgentSettings.() -> SubAgentSettings) -> Unit = { block ->
        onUpdate(agent.copy(subagent = sub.block().sanitized()))
    }
    var adding by remember { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.settings_v3_subagent_title),
        icon = Icons.Outlined.Groups,
        subtitle = stringResource(R.string.settings_v3_subagent_subtitle)
    ) {
        SwitchRow(
            stringResource(R.string.settings_v3_subagent_enabled),
            sub.enabled,
            description = stringResource(R.string.settings_v3_subagent_enabled_desc)
        ) { on -> updateSub { copy(enabled = on) } }

        SliderRow(
            stringResource(R.string.settings_v3_subagent_concurrency),
            sub.maxConcurrent.toFloat(), 1f..8f, 6,
            description = stringResource(R.string.settings_v3_subagent_concurrency_desc),
            onValueChange = { updateSub { copy(maxConcurrent = it.toInt()) } },
            fmt = { it.toInt().toString() }
        )
        DropdownRow(
            stringResource(R.string.settings_v3_subagent_timeout),
            listOf(
                60_000L to "1 min", 120_000L to "2 min", 300_000L to "5 min",
                600_000L to "10 min", 900_000L to "15 min",
            ),
            sub.timeoutMs,
            description = stringResource(R.string.settings_v3_subagent_timeout_desc)
        ) { ms -> updateSub { copy(timeoutMs = ms) } }
        SliderRow(
            stringResource(R.string.settings_v3_subagent_max_turns),
            sub.maxTurns.toFloat(), 5f..30f, 24,
            description = stringResource(R.string.settings_v3_subagent_max_turns_desc),
            onValueChange = { updateSub { copy(maxTurns = it.toInt()) } },
            fmt = { it.toInt().toString() }
        )
        SliderRow(
            stringResource(R.string.settings_v3_subagent_tool_output),
            sub.toolOutputLimit.toFloat(), 1000f..16000f, 29,
            description = stringResource(R.string.settings_v3_subagent_tool_output_desc),
            onValueChange = { updateSub { copy(toolOutputLimit = snapTo(it, 500)) } },
            fmt = { snapTo(it, 500).toString() }
        )
        SliderRow(
            stringResource(R.string.settings_v3_subagent_output),
            sub.outputLimit.toFloat(), 1000f..32000f, 61,
            description = stringResource(R.string.settings_v3_subagent_output_desc),
            onValueChange = { updateSub { copy(outputLimit = snapTo(it, 500)) } },
            fmt = { snapTo(it, 500).toString() }
        )
        DropdownRow(
            stringResource(R.string.settings_v3_subagent_context),
            listOf(
                32_000 to "32K", 64_000 to "64K",
                128_000 to "128K", 256_000 to "256K",
            ),
            sub.contextBudget,
            description = stringResource(R.string.settings_v3_subagent_context_desc)
        ) { tokens -> updateSub { copy(contextBudget = tokens) } }
        SliderRow(
            stringResource(R.string.settings_v3_subagent_temperature),
            sub.temperature, 0f..2f, 19,
            description = stringResource(R.string.settings_v3_subagent_temperature_desc),
            onValueChange = { updateSub { copy(temperature = it) } },
            fmt = { String.format(Locale.US, "%.1f", it) }
        )

        HorizontalDivider()
        Text(
            stringResource(R.string.settings_v3_subagent_custom_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        DescriptionText(stringResource(R.string.settings_v3_subagent_custom_hint))
        if (sub.customTypes.isEmpty()) {
            Text(
                stringResource(R.string.settings_v3_subagent_custom_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        sub.customTypes.forEach { custom ->
            CustomSubAgentCard(
                custom = custom,
                onChange = { updated ->
                    onUpdate(agent.copy(subagent = agent.subagent.copy(
                        customTypes = agent.subagent.customTypes.map {
                            if (it.key == custom.key) updated else it
                        }
                    )))
                },
                onDelete = {
                    onUpdate(agent.copy(subagent = agent.subagent.copy(
                        customTypes = agent.subagent.customTypes.filter { it.key != custom.key }
                    )))
                }
            )
        }
        OutlinedButton(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_v3_subagent_custom_add))
        }
    }

    // ── 新增对话框：key 归一 + 保留字/重复拒绝；保存前整份预算 sanitized 钳制 ──
    if (adding) {
        CustomSubAgentAddDialog(
            existingKeys = sub.customTypes.map { it.key }.toSet(),
            onDismiss = { adding = false },
            onSave = { custom ->
                onUpdate(agent.copy(subagent = agent.subagent.copy(
                    customTypes = agent.subagent.customTypes + custom
                ).sanitized()))
                adding = false
            }
        )
    }
}

/**
 * 单个自定义子代理卡：key 只读标识 + 名称 / 描述 / 提示词 / 工具白名单 /
 * 轮次覆盖就地编辑 + 删除按钮。每次改动直接经 customTypes 全量落盘。
 */
@Composable
private fun CustomSubAgentCard(
    custom: CustomSubAgent,
    onChange: (CustomSubAgent) -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    custom.key,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace)
                )
                // UI-012：48dp 触区红线
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.settings_v3_subagent_custom_delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextFieldRow(
                stringResource(R.string.settings_v3_subagent_custom_name),
                custom.displayName
            ) { name -> onChange(custom.copy(displayName = name.take(CUSTOM_NAME_LIMIT))) }
            TextFieldRow(
                stringResource(R.string.settings_v3_subagent_custom_desc_label),
                custom.description,
                description = stringResource(R.string.settings_v3_subagent_custom_desc_hint)
            ) { desc -> onChange(custom.copy(description = desc.take(CUSTOM_DESC_LIMIT))) }
            OutlinedTextField(
                value = custom.systemPrompt,
                onValueChange = { prompt -> onChange(custom.copy(systemPrompt = prompt.take(CUSTOM_PROMPT_LIMIT))) },
                label = { Text(stringResource(R.string.settings_v3_subagent_custom_prompt)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                minLines = 3,
                maxLines = 8
            )
            DescriptionText(stringResource(R.string.settings_v3_subagent_custom_prompt_hint))
            TextFieldRow(
                stringResource(R.string.settings_v3_subagent_custom_tools),
                custom.toolIds.joinToString(","),
                description = stringResource(R.string.settings_v3_subagent_custom_tools_hint)
            ) { raw ->
                onChange(custom.copy(toolIds = raw.split(",").map { id -> id.trim() }.filter { id -> id.isNotBlank() }))
            }
            IntFieldRow(
                stringResource(R.string.settings_v3_subagent_custom_turns),
                custom.maxTurns,
                description = stringResource(R.string.settings_v3_subagent_custom_turns_desc),
                min = 0, max = 30
            ) { turns -> onChange(custom.copy(maxTurns = turns)) }
        }
    }
}

/**
 * 新增自定义子代理对话框：key（归一 + 保留字与重复拒绝）+ 展示名。
 * 描述与提示词等细节创建后在卡片上就地编辑。
 */
@Composable
private fun CustomSubAgentAddDialog(
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onSave: (CustomSubAgent) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    val normalizedKey = CustomSubAgent.normalizeKey(keyInput)
    val keyError = when {
        keyInput.isBlank() -> null
        CustomSubAgent.RESERVED_KEYS.contains(normalizedKey) ->
            stringResource(R.string.settings_v3_subagent_key_reserved)
        existingKeys.contains(normalizedKey) ->
            stringResource(R.string.settings_v3_subagent_key_duplicate)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_v3_subagent_custom_new_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.settings_v3_subagent_custom_key)) },
                    supportingText = {
                        Text(
                            keyError ?: stringResource(R.string.settings_v3_subagent_custom_key_hint),
                            color = if (keyError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    isError = keyError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(CUSTOM_NAME_LIMIT) },
                    label = { Text(stringResource(R.string.settings_v3_subagent_custom_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalizedKey.isNotBlank() && keyError == null,
                onClick = {
                    onSave(
                        CustomSubAgent(
                            key = normalizedKey,
                            displayName = nameInput.trim().ifBlank { normalizedKey }
                        )
                    )
                }
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════
// v3 新分区二：GOAL 目标模式默认值
// ═══════════════════════════════════════════════════════════════════

/**
 * GOAL 分区：快速模型验收开关（关闭后信任主模型自评，省 token）、
 * 新目标默认验收轮次上限、会话恢复提示续跑。验收调用走 LlmRequestContext
 * 的 FAST 角色——便宜小模型在 模型 Tab 的 Roles 里配置。
 */
@Composable
internal fun GoalSettingsSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_v3_goal_title),
        icon = Icons.Outlined.Flag,
        subtitle = stringResource(R.string.settings_v3_goal_subtitle)
    ) {
        SwitchRow(
            stringResource(R.string.settings_v3_goal_verifier),
            agent.goalVerifierEnabled,
            description = stringResource(R.string.settings_v3_goal_verifier_desc)
        ) { on -> onUpdate(agent.copy(goalVerifierEnabled = on)) }
        SliderRow(
            stringResource(R.string.settings_v3_goal_max_rounds),
            agent.goalMaxRounds.toFloat(), 3f..20f, 16,
            description = stringResource(R.string.settings_v3_goal_max_rounds_desc),
            onValueChange = { onUpdate(agent.copy(goalMaxRounds = it.toInt())) },
            fmt = { it.toInt().toString() }
        )
        SwitchRow(
            stringResource(R.string.settings_v3_goal_auto_resume),
            agent.goalAutoResume,
            description = stringResource(R.string.settings_v3_goal_auto_resume_desc)
        ) { on -> onUpdate(agent.copy(goalAutoResume = on)) }
        DescriptionText(stringResource(R.string.settings_v3_goal_note))
    }
}

// ═══════════════════════════════════════════════════════════════════
// v3 新分区三：LOOP 循环模式默认值
// ═══════════════════════════════════════════════════════════════════

/** LOOP 分区：新循环的间隔 / 执行次数预填 + 后台通知与补跑策略。 */
@Composable
internal fun LoopSettingsSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_v3_loop_title),
        icon = Icons.Outlined.Replay,
        subtitle = stringResource(R.string.settings_v3_loop_subtitle)
    ) {
        DropdownRow(
            stringResource(R.string.settings_v3_loop_interval),
            listOf(
                60_000L to "1 min", 300_000L to "5 min", 1_800_000L to "30 min",
                3_600_000L to "1 h", 21_600_000L to "6 h",
            ),
            agent.loopDefaultIntervalMs,
            description = stringResource(R.string.settings_v3_loop_interval_desc)
        ) { ms -> onUpdate(agent.copy(loopDefaultIntervalMs = ms)) }
        SliderRow(
            stringResource(R.string.settings_v3_loop_max_runs),
            agent.loopMaxRunsDefault.toFloat(), 1f..50f, 48,
            description = stringResource(R.string.settings_v3_loop_max_runs_desc),
            onValueChange = { onUpdate(agent.copy(loopMaxRunsDefault = it.toInt())) },
            fmt = { it.toInt().toString() }
        )
        SwitchRow(
            stringResource(R.string.settings_v3_loop_notify),
            agent.loopNotifyOnRun,
            description = stringResource(R.string.settings_v3_loop_notify_desc)
        ) { on -> onUpdate(agent.copy(loopNotifyOnRun = on)) }
        SwitchRow(
            stringResource(R.string.settings_v3_loop_catch_up),
            agent.loopCatchUpMissed,
            description = stringResource(R.string.settings_v3_loop_catch_up_desc)
        ) { on -> onUpdate(agent.copy(loopCatchUpMissed = on)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
// v3 新分区四：Coding 工位（工具面与防护）
// ═══════════════════════════════════════════════════════════════════

/**
 * Coding 工位分区：工具面（全量 / 聚焦核心）、读守卫、自动项目规则、
 * MCP 与技能作用域隔离。四项全部被引擎侧消费（EngineToolPlanner /
 * 标准线引擎 / RulesProvider）。
 */
@Composable
internal fun CodingSettingsSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_v3_coding_title),
        icon = Icons.Outlined.Terminal,
        subtitle = stringResource(R.string.settings_v3_coding_subtitle)
    ) {
        DropdownRow(
            stringResource(R.string.settings_v3_coding_surface),
            listOf(
                "full" to stringResource(R.string.settings_v3_coding_surface_full),
                "core" to stringResource(R.string.settings_v3_coding_surface_core),
            ),
            agent.codingToolSurface,
            description = stringResource(R.string.settings_v3_coding_surface_desc)
        ) { surface -> onUpdate(agent.copy(codingToolSurface = surface)) }
        SwitchRow(
            stringResource(R.string.settings_v3_coding_read_guard),
            agent.codingReadGuard,
            description = stringResource(R.string.settings_v3_coding_read_guard_desc)
        ) { on -> onUpdate(agent.copy(codingReadGuard = on)) }
        SwitchRow(
            stringResource(R.string.settings_v3_coding_auto_rules),
            agent.codingAutoRules,
            description = stringResource(R.string.settings_v3_coding_auto_rules_desc)
        ) { on -> onUpdate(agent.copy(codingAutoRules = on)) }
        SwitchRow(
            stringResource(R.string.settings_v3_coding_mcp_scope),
            agent.mcpScopeIsolation,
            description = stringResource(R.string.settings_v3_coding_mcp_scope_desc)
        ) { on -> onUpdate(agent.copy(mcpScopeIsolation = on)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
// v3 新分区五：GitHub（只读状态 + 默认仓库偏好）
// ═══════════════════════════════════════════════════════════════════

/**
 * GitHub 分区：连接状态回显（真实连接 UI 在 Coding 屏，避免双份）、
 * 默认仓库查看与编辑（owner 或 owner/repo，规范化失败显示格式错误）、
 * 断开连接。数据源为 SettingsViewModel 转发的 GithubTokenManager 流。
 */
@Composable
internal fun GithubSettingsSection(viewModel: SettingsViewModel) {
    val connection by viewModel.githubConnection.collectAsStateWithLifecycle()
    val defaultRepo by viewModel.githubDefaultRepo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SectionCard(
        title = stringResource(R.string.settings_v3_github_title),
        icon = Icons.Outlined.Public,
        subtitle = stringResource(R.string.settings_v3_github_subtitle)
    ) {
        // ── 连接状态行（只读）：已连接用户名 / 未连接 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connection.isConnected) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                contentDescription = null,
                tint = if (connection.isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (connection.isConnected)
                    stringResource(
                        R.string.settings_v3_github_status_connected,
                        connection.username ?: "GitHub"
                    )
                else stringResource(R.string.settings_v3_github_status_disconnected),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!connection.isConnected) {
            DescriptionText(stringResource(R.string.settings_v3_github_connect_hint))
        }

        HorizontalDivider()

        // ── 默认仓库：当前值行 + 编辑入口（规范化保存，失败显示格式错误）──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_v3_github_default_repo),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                defaultRepo.ifBlank { stringResource(R.string.settings_v3_github_default_repo_unset) },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (defaultRepo.isBlank()) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.primary
            )
        }
        var repoInput by remember(defaultRepo) { mutableStateOf(defaultRepo) }
        var invalid by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = repoInput,
            onValueChange = {
                repoInput = it
                invalid = false
                saved = false
            },
            label = { Text(stringResource(R.string.settings_v3_github_repo_edit_label)) },
            supportingText = {
                Text(
                    when {
                        invalid -> stringResource(R.string.settings_v3_github_repo_invalid)
                        saved -> stringResource(R.string.settings_v3_github_repo_saved)
                        else -> stringResource(R.string.settings_v3_github_default_repo_hint)
                    },
                    color = if (invalid) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall
                )
            },
            isError = invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    // 契约（S3）：规范化保存返回结果串；null = 格式无法识别
                    val normalized = viewModel.saveGithubDefaultRepo(repoInput)
                    if (normalized == null) {
                        invalid = true
                        saved = false
                    } else {
                        invalid = false
                        saved = true
                        repoInput = normalized
                    }
                }
            }) { Text(stringResource(R.string.settings_save)) }
            if (defaultRepo.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    viewModel.clearGithubDefaultRepo()
                    invalid = false
                    saved = false
                }) { Text(stringResource(R.string.settings_v3_github_clear)) }
            }
        }

        if (connection.isConnected) {
            OutlinedButton(
                onClick = { viewModel.disconnectGithub() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.settings_v3_github_disconnect),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 私有辅助
// ═══════════════════════════════════════════════════════════════════

// 字符预算滑块的步进吸附（500 步进；防御式取整，杜绝浮点尾差落盘）
private fun snapTo(value: Float, step: Int): Int = (value / step).roundToInt() * step

// 自定义子代理各文本字段的软上限（防设置存储被无界文本撑爆）
private const val CUSTOM_NAME_LIMIT = 40
private const val CUSTOM_DESC_LIMIT = 200
private const val CUSTOM_PROMPT_LIMIT = 8000
