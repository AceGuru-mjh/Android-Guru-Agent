package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.loop.CronSchedule
import com.apex.agent.loop.LoopConfig
import com.apex.agent.loop.LoopKind
import com.apex.agent.loop.LoopModels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * ═══ S2 — Loop 循环配置弹层（ModeGuideSheet 同款 ModalBottomSheet）═══
 *
 * 打开路径：
 * 1. 切到 LOOP 模式且尚无 activeLoop（AgentChatScreen 模式选择器回调）；
 * 2. LOOP 模式下首条消息发送被拦截（maybeInterceptLoopFirstSend，预填草稿文本）。
 *
 * 三种子形态（LoopKind）：固定间隔（预设 chips + 自定义分钟，下限 30s 钳制）/
 * Cron 表达式（实时显示下次触发，解析失败红字；常用预设 chips）/ 一次性提醒
 * （分钟快捷 + 自定义）。保存 → onStart 回调（VM.startLoop）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoopSetupSheet(
    initialPrompt: String,
    sessionTag: String,
    defaultIntervalMs: Long,
    defaultMaxRuns: Int,
    defaultNotify: Boolean,
    onDismiss: () -> Unit,
    onStart: (LoopConfig) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 表单状态 ──
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var promptTouched by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(LoopKind.INTERVAL) }
    // INTERVAL：预设毫秒（null = 自定义）
    var intervalPresetMs by remember { mutableLongStateOf(defaultIntervalMs) }
    var customIntervalMin by remember { mutableStateOf("") }
    // CRON
    var cronExpr by remember { mutableStateOf("*/5 * * * *") }
    // ONCE：延迟分钟预设（-1 = 自定义）
    var onceDelayMin by remember { mutableLongStateOf(5L) }
    var customOnceMin by remember { mutableStateOf("") }
    // 通用
    var maxRunsText by remember { mutableStateOf(defaultMaxRuns.coerceIn(1, 999).toString()) }
    var notifyOnRun by remember { mutableStateOf(defaultNotify) }

    // ── 校验（反应式：按钮可用性 + 内联错误提示共用）──
    val promptValid = prompt.isNotBlank()
    val customIntervalMinValue = customIntervalMin.trim().toLongOrNull()
    val intervalValid = intervalPresetMs > 0 || (customIntervalMinValue != null && customIntervalMinValue > 0)
    val intervalMs = intervalPresetMs.takeIf { it > 0 }
        ?: (customIntervalMinValue?.times(60_000L) ?: 0L)
    val cronNext = if (cronExpr.isBlank()) null else CronSchedule.nextAfter(cronExpr, System.currentTimeMillis())
    val cronValid = cronNext != null
    val customOnceMinValue = customOnceMin.trim().toLongOrNull()
    val onceValid = onceDelayMin > 0 || (customOnceMinValue != null && customOnceMinValue > 0)
    val onceDelayMinutes = onceDelayMin.takeIf { it > 0 } ?: (customOnceMinValue ?: 0L)
    val maxRunsValue = maxRunsText.trim().toIntOrNull()

    val canSave = promptValid && when (kind) {
        LoopKind.INTERVAL -> intervalValid
        LoopKind.CRON -> cronValid
        LoopKind.ONCE -> onceValid
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 标题 ──
            Text(
                text = stringResource(R.string.loop_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // ── 提示词 ──
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it; promptTouched = true },
                label = { Text(stringResource(R.string.loop_prompt_label)) },
                placeholder = { Text(stringResource(R.string.loop_prompt_placeholder)) },
                isError = promptTouched && !promptValid,
                supportingText = {
                    if (promptTouched && !promptValid) Text(stringResource(R.string.loop_prompt_required))
                },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 子形态三选 ──
            Text(
                text = stringResource(R.string.loop_kind_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == LoopKind.INTERVAL,
                    onClick = { kind = LoopKind.INTERVAL },
                    label = { Text(stringResource(R.string.loop_kind_interval)) }
                )
                FilterChip(
                    selected = kind == LoopKind.CRON,
                    onClick = { kind = LoopKind.CRON },
                    label = { Text(stringResource(R.string.loop_kind_cron)) }
                )
                FilterChip(
                    selected = kind == LoopKind.ONCE,
                    onClick = { kind = LoopKind.ONCE },
                    label = { Text(stringResource(R.string.loop_kind_once)) }
                )
            }

            when (kind) {
                LoopKind.INTERVAL -> IntervalSection(
                    presetMs = intervalPresetMs,
                    onPresetSelect = { intervalPresetMs = it; customIntervalMin = "" },
                    customMin = customIntervalMin,
                    onCustomChange = { customIntervalMin = it; intervalPresetMs = 0L },
                    customInvalid = !intervalValid
                )
                LoopKind.CRON -> CronSection(
                    expr = cronExpr,
                    onExprChange = { cronExpr = it },
                    nextAtMs = cronNext
                )
                LoopKind.ONCE -> OnceSection(
                    delayMin = onceDelayMin,
                    onPresetSelect = { onceDelayMin = it; customOnceMin = "" },
                    customMin = customOnceMin,
                    onCustomChange = { customOnceMin = it; onceDelayMin = -1L },
                    customInvalid = !onceValid
                )
            }

            // ── 最大执行次数（ONCE 固定 1，不渲染）──
            if (kind != LoopKind.ONCE) {
                OutlinedTextField(
                    value = maxRunsText,
                    onValueChange = { maxRunsText = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.loop_max_runs_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = maxRunsValue == null || maxRunsValue < 1,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 后台通知开关 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.loop_notify_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = notifyOnRun, onCheckedChange = { notifyOnRun = it })
            }

            HorizontalDivider()

            // ── 行为说明 + 保存 ──
            Text(
                text = stringResource(R.string.loop_background_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    onStart(
                        LoopConfig(
                            id = LoopModels.newId(),
                            prompt = prompt.trim(),
                            kind = kind,
                            intervalMs = if (kind == LoopKind.INTERVAL) {
                                intervalMs.coerceAtLeast(LoopModels.MIN_INTERVAL_MS)
                            } else {
                                defaultIntervalMs
                            },
                            cronExpr = if (kind == LoopKind.CRON) cronExpr.trim() else "",
                            triggerAt = if (kind == LoopKind.ONCE) {
                                now + onceDelayMinutes * 60_000L
                            } else 0L,
                            maxRuns = if (kind == LoopKind.ONCE) 1
                            else (maxRunsValue ?: defaultMaxRuns).coerceIn(1, 999),
                            runsDone = 0,
                            enabled = true,
                            createdAt = now,
                            lastRunAt = 0L,
                            sessionTag = sessionTag,
                            notifyOnRun = notifyOnRun
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    // UI-012：48dp 触区红线
                    .sizeIn(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.loop_save))
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

/** INTERVAL 段：预设 chips（1m/5m/30m/1h/6h）+ 自定义分钟（下限 30s 钳制提示）。 */
@Composable
private fun IntervalSection(
    presetMs: Long,
    onPresetSelect: (Long) -> Unit,
    customMin: String,
    onCustomChange: (String) -> Unit,
    customInvalid: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntervalChip(60_000L, presetMs, onPresetSelect) { stringResource(R.string.loop_interval_min_format, 1) }
            IntervalChip(300_000L, presetMs, onPresetSelect) { stringResource(R.string.loop_interval_min_format, 5) }
            IntervalChip(1_800_000L, presetMs, onPresetSelect) { stringResource(R.string.loop_interval_min_format, 30) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntervalChip(3_600_000L, presetMs, onPresetSelect) { stringResource(R.string.loop_interval_hour_format, 1) }
            IntervalChip(21_600_000L, presetMs, onPresetSelect) { stringResource(R.string.loop_interval_hour_format, 6) }
        }
        OutlinedTextField(
            value = customMin,
            onValueChange = onCustomChange,
            label = { Text(stringResource(R.string.loop_interval_custom_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = customMin.isNotBlank() && customInvalid,
            supportingText = {
                if (customMin.isNotBlank() && customInvalid) {
                    Text(stringResource(R.string.loop_interval_custom_invalid))
                } else {
                    Text(stringResource(R.string.loop_min_interval_hint))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun IntervalChip(
    chipMs: Long,
    selectedMs: Long,
    onSelect: (Long) -> Unit,
    label: @Composable () -> String
) {
    FilterChip(
        selected = selectedMs == chipMs,
        onClick = { onSelect(chipMs) },
        label = { Text(label()) }
    )
}

/** CRON 段：表达式输入 + 实时下次触发预览（失败红字）+ 常用预设。 */
@Composable
private fun CronSection(
    expr: String,
    onExprChange: (String) -> Unit,
    nextAtMs: Long?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = expr,
            onValueChange = onExprChange,
            label = { Text(stringResource(R.string.loop_cron_label)) },
            isError = nextAtMs == null,
            supportingText = {
                if (nextAtMs == null) {
                    Text(stringResource(R.string.loop_cron_invalid), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.loop_cron_next_preview, formatAbsolute(nextAtMs)))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CronPreset("*/5 * * * *", expr, onExprChange) { stringResource(R.string.loop_cron_preset_5min) }
            CronPreset("0 * * * *", expr, onExprChange) { stringResource(R.string.loop_cron_preset_hourly) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CronPreset("0 9 * * *", expr, onExprChange) { stringResource(R.string.loop_cron_preset_daily9) }
            CronPreset("0 9 * * 1", expr, onExprChange) { stringResource(R.string.loop_cron_preset_mon9) }
        }
    }
}

@Composable
private fun CronPreset(
    presetExpr: String,
    currentExpr: String,
    onExprChange: (String) -> Unit,
    label: @Composable () -> String
) {
    FilterChip(
        selected = currentExpr.trim() == presetExpr,
        onClick = { onExprChange(presetExpr) },
        label = { Text(label()) }
    )
}

/** ONCE 段：延迟快捷（5m/30m/1h/明天此刻）+ 自定义分钟。 */
@Composable
private fun OnceSection(
    delayMin: Long,
    onPresetSelect: (Long) -> Unit,
    customMin: String,
    onCustomChange: (String) -> Unit,
    customInvalid: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnceChip(5L, delayMin, onPresetSelect) { stringResource(R.string.loop_once_in_5m) }
            OnceChip(30L, delayMin, onPresetSelect) { stringResource(R.string.loop_once_in_30m) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnceChip(60L, delayMin, onPresetSelect) { stringResource(R.string.loop_once_in_1h) }
            OnceChip(24 * 60L, delayMin, onPresetSelect) { stringResource(R.string.loop_once_tomorrow) }
        }
        OutlinedTextField(
            value = customMin,
            onValueChange = onCustomChange,
            label = { Text(stringResource(R.string.loop_once_custom_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = customMin.isNotBlank() && customInvalid,
            supportingText = {
                if (customMin.isNotBlank() && customInvalid) {
                    Text(stringResource(R.string.loop_interval_custom_invalid))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OnceChip(
    chipMin: Long,
    selectedMin: Long,
    onSelect: (Long) -> Unit,
    label: @Composable () -> String
) {
    FilterChip(
        selected = selectedMin == chipMin,
        onClick = { onSelect(chipMin) },
        label = { Text(label()) }
    )
}

/**
 * ═══ S2 — Loop 运行状态卡（输入栏上方，activeLoop 非空时渲染）═══
 *
 * 「Loop 运行中 · 第 N/M 轮 · 下次 X 后」+ 停止 + 立即触发。
 * 倒计时每秒刷新（remember + LaunchedEffect 墙钟）；INTERVAL/CRON 显示相对
 * 倒计时，ONCE 显示绝对时刻；CRON 表达式失效（Long.MAX_VALUE）显示「—」。
 */
@Composable
internal fun LoopActiveCard(
    config: LoopConfig,
    nextRunAtOf: (LoopConfig, Long) -> Long,
    onStop: () -> Unit,
    onRunNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 每秒墙钟（LaunchedEffect 键控 config 轮次：标记新轮时重挂，立即重算）
    var nowMs by remember(config.id, config.runsDone) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(config.id, config.runsDone) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val nextAt = nextRunAtOf(config, nowMs)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = stringResource(R.string.loop_cd_loop_status),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.loop_active_status) + " · " + stringResource(
                        R.string.loop_run_n_of_m,
                        config.runsDone.coerceAtMost(config.maxRuns),
                        config.maxRuns
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = nextRunLabel(config, nextAt, nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onRunNow,
                // UI-012：48dp 触区红线
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Default.Replay,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.loop_run_now), maxLines = 1)
            }
            TextButton(
                onClick = onStop,
                // UI-012：48dp 触区红线
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.loop_stop), maxLines = 1)
            }
        }
    }
}

/** 下次触发文案：ONCE → 绝对时刻；INTERVAL/CRON → 倒计时；不可达 → 「—」。 */
@Composable
private fun nextRunLabel(config: LoopConfig, nextAt: Long, nowMs: Long): String = when {
    nextAt == Long.MAX_VALUE -> "—"
    config.kind == LoopKind.ONCE -> stringResource(R.string.loop_next_run_at, formatAbsolute(nextAt))
    else -> stringResource(R.string.loop_next_run_in, countdownText(nextAt - nowMs))
}

/** 剩余毫秒 → 本地化倒计时（≥1h「X时Y分」/ ≥1m「X分Y秒」/ 其余「X秒」）。 */
@Composable
private fun countdownText(remainingMs: Long): String {
    val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        totalSec >= 3600 -> stringResource(R.string.loop_countdown_hm, h, m)
        totalSec >= 60 -> stringResource(R.string.loop_countdown_ms, m, s)
        else -> stringResource(R.string.loop_countdown_s, totalSec)
    }
}

/** 绝对时刻（设备时区，MM-dd HH:mm）。 */
private fun formatAbsolute(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
