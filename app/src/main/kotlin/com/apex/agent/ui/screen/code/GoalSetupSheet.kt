package com.apex.agent.ui.screen.code

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.goal.GoalRuntimeState
import com.apex.agent.core.engine.goal.GoalStatus

/**
 * # S1 — GOAL 目标设定弹层（LoopSetupSheet 同款 ModalBottomSheet）
 *
 * 打开路径（弹层状态在 uiState.showGoalSetup / goalSetupDraft，与 Loop 的
 * Screen 本地态不同——Coding 屏草稿本就在 VM 层）：
 * 1. 切到 GOAL 模式且尚无活动目标（setMode → onEnterGoalMode 单点，
 *    选择器 onSelect 与 init 恢复共用）；
 * 2. GOAL 模式下首条消息发送被拦截（maybeGoalFirstSend，草稿预填目标陈述）。
 *
 * 三要素：目标陈述（做什么）/ 可验证完成条件（做成什么样才算完——验收器
 * 按证据判定，不接受「应该没问题」）/ 最大验收轮次（3/5/8/12/20 预设
 * chips，默认取设置页 goalMaxRounds）。「开始」→ onStart 回调（VM.
 * startGoalModeGoal：startGoal + 首条提示发送 + 关闭弹层清草稿）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalSetupSheet(
    initialText: String,
    defaultMaxRounds: Int,
    onDismiss: () -> Unit,
    onStart: (statement: String, criteria: String, maxRounds: Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 表单状态 ──
    var statement by remember(initialText) { mutableStateOf(initialText) }
    var criteria by remember { mutableStateOf("") }
    var statementTouched by remember { mutableStateOf(false) }
    var criteriaTouched by remember { mutableStateOf(false) }
    // 非预设值（设置页滑杆可出 10 之类）保持为当前值，无 chip 选中
    var maxRounds by remember {
        mutableStateOf(defaultMaxRounds.coerceIn(1, ROUND_PRESETS.last()))
    }
    var helpExpanded by remember { mutableStateOf(false) }

    val statementValid = statement.isNotBlank()
    val criteriaValid = criteria.isNotBlank()

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
                text = stringResource(R.string.goal_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // ── 目标陈述（做什么）──
            OutlinedTextField(
                value = statement,
                onValueChange = { statement = it; statementTouched = true },
                label = { Text(stringResource(R.string.goal_statement_label)) },
                placeholder = { Text(stringResource(R.string.goal_statement_placeholder)) },
                isError = statementTouched && !statementValid,
                supportingText = {
                    if (statementTouched && !statementValid) {
                        Text(stringResource(R.string.goal_setup_empty_statement))
                    }
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 完成条件（可验证；helper 说明验收口径）──
            OutlinedTextField(
                value = criteria,
                onValueChange = { criteria = it; criteriaTouched = true },
                label = { Text(stringResource(R.string.goal_criteria_label)) },
                placeholder = { Text(stringResource(R.string.goal_criteria_placeholder)) },
                isError = criteriaTouched && !criteriaValid,
                supportingText = {
                    if (criteriaTouched && !criteriaValid) {
                        Text(stringResource(R.string.goal_setup_empty_criteria))
                    } else {
                        Text(stringResource(R.string.goal_criteria_hint))
                    }
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 最大验收轮次（预设 chips；每轮 = 一次收尾 + 一次快速模型验收）──
            Text(
                text = stringResource(R.string.goal_max_rounds),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundChip(3, maxRounds) { maxRounds = it }
                RoundChip(5, maxRounds) { maxRounds = it }
                RoundChip(8, maxRounds) { maxRounds = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundChip(12, maxRounds) { maxRounds = it }
                RoundChip(20, maxRounds) { maxRounds = it }
            }

            // ── 帮助折叠行（工作方式一图流）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { helpExpanded = !helpExpanded }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.goal_how_it_works),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (helpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (helpExpanded) {
                Text(
                    text = stringResource(R.string.goal_how_it_works_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 开始（点击即校验：空字段标红 + supportingText 提示）──
            Button(
                onClick = {
                    statementTouched = true
                    criteriaTouched = true
                    if (statementValid && criteriaValid) {
                        onStart(statement.trim(), criteria.trim(), maxRounds)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // UI-012：48dp 触区红线
                    .sizeIn(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.goal_start))
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

/** 轮次预设 chip。 */
@Composable
private fun RoundChip(
    preset: Int,
    current: Int,
    onSelect: (Int) -> Unit
) {
    FilterChip(
        selected = current == preset,
        onClick = { onSelect(preset) },
        label = { Text(preset.toString()) }
    )
}

/** 轮次预设（3/5/8/12/20；末值兼作 goalMaxRounds 脏值钳制上界）。 */
private val ROUND_PRESETS = listOf(3, 5, 8, 12, 20)

/**
 * # S1 — GOAL 目标状态卡（输入栏上方，LoopActiveCard 同款形态）
 *
 * 渲染策略（设计决策）：**只要存在目标（无论 ACTIVE/ACHIEVED/STOPPED）
 * 就显示**——切走模式也显示，用户可随时停止/重启（目标生命周期独立于
 * 模式，与 Agent 屏 LoopActiveCard 同口径）。
 *
 * 结构：头行 Flag 图标 + 目标陈述（单行截断）+ 状态徽标（进行中 = 轮次
 * N/max；✅ 已达成；⛔ 已停止）+ 操作按钮（进行中「停止目标」/ 已停止
 * 「重启目标」，已达成不显示操作）；完成条件摘要两行截断（点击切换展开
 * 全文）；最近验收 ✅/❌ + 理由单行截断（无验收显示「尚未验收」）。
 * 48dp 触控目标。
 */
@Composable
internal fun GoalStatusCard(
    state: GoalRuntimeState,
    onStop: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    var criteriaExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // ── 头行：Flag + 陈述 + 状态徽标 + 操作 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = stringResource(R.string.goal_cd_goal_status),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.spec.statement,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLineOf(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (state.status) {
                    GoalStatus.ACTIVE -> StatusAction(
                        label = stringResource(R.string.goal_stop),
                        icon = Icons.Default.Stop,
                        onClick = onStop
                    )
                    GoalStatus.STOPPED -> StatusAction(
                        label = stringResource(R.string.goal_resume),
                        icon = Icons.Default.Replay,
                        onClick = onResume
                    )
                    GoalStatus.ACHIEVED -> Unit
                }
            }

            // ── 完成条件摘要（两行截断，点击展开全文）──
            Text(
                text = state.spec.acceptanceCriteria,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (criteriaExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { criteriaExpanded = !criteriaExpanded }
            )

            // ── 最近验收（✅/❌ + 理由；无验收 = 尚未验收）──
            val check = state.lastCheck
            Text(
                text = if (check == null) {
                    stringResource(R.string.goal_last_check_none)
                } else {
                    (if (check.achieved) "✅ " else "❌ ") + check.reason
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 状态卡操作按钮（图标 + 文案，48dp 触区）。 */
@Composable
private fun StatusAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        // UI-012：48dp 触区红线
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(label, maxLines = 1)
    }
}

/** 状态徽标行：ACTIVE 带轮次进度（N/max），终态只显文案。 */
@Composable
private fun statusLineOf(state: GoalRuntimeState): String = when (state.status) {
    GoalStatus.ACTIVE ->
        stringResource(R.string.goal_status_active) + " · " + stringResource(
            R.string.goal_rounds_progress,
            state.roundsDone,
            state.spec.maxRounds
        )
    GoalStatus.ACHIEVED -> stringResource(R.string.goal_status_achieved)
    GoalStatus.STOPPED -> stringResource(R.string.goal_status_stopped)
}
