package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.thinking.ThinkingProfile

/**
 * ═══ 模式指南底部弹层（#168，从 AgentModeSelector 的「?」图标打开）═══
 *
 * 六模式此前只有菜单里的一句话描述——用户无从得知「Plan 和 Spec 差在哪」
 * 「Assist 到底什么时候会打断我」「Reflect 的轮数设置影响什么」。本弹层
 * 用统一的行为矩阵（执行流 / 工具策略 / 人工介入点 / 提示词段）把六模式
 * 的**真实执行差异**讲清楚，底部附六档思考画像简表（数据直读
 * [ThinkingProfile.forLevel] 静态表——app 依赖 core 单向，OK）。
 *
 * 文案与实现一一对应（不是营销话术）：
 * - PLAN 的「规划只读 → 锁定 → 拓扑执行」= EnginePrompts planningPhase 段
 *   + PlanGraph.lock/Phase 3.5；
 * - REFLECTION 的「生成→评审→修正×N」= executeBuildLoop 反思分支，
 *   N = AgentSettings.reflectionRounds（设置页 Slider）；
 * - HUMAN_ASSIST 的「决策点自动人工介入」= DecisionPointDetector +
 *   HumanAssistFlow 拦截（#168 本体）；
 * - CUSTOM 的「预设指令注入」= ModePresets + config.customInstruction。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeGuideSheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                text = stringResource(R.string.mode_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // ── 六模式各一节 ──
            ModeGuideEntry.entries.forEach { entry ->
                ModeGuideSection(entry)
            }

            HorizontalDivider()

            // ── 思考档位简表 ──
            ThinkingLevelsTable()

            Spacer(modifier = Modifier.size(4.dp))
        }
    }
}

/** 单模式的指南节：图标 + 名称 + 一句话说明 + 适用场景 + 行为差异四行。 */
@Composable
private fun ModeGuideSection(entry: ModeGuideEntry) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 标题行：图标 + 模式名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = agentModeIcon(entry.mode),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.mode.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 一句话说明（复用模式选择菜单同款文案）
            Text(
                text = agentModeDescription(entry.mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 适用场景
            GuideLabelValue(
                label = stringResource(R.string.mode_guide_use_cases),
                value = stringResource(entry.casesRes)
            )
            // 行为差异四行：执行流 / 工具策略 / 人工介入点 / 提示词段
            GuideLabelValue(
                label = stringResource(R.string.mode_guide_flow_label),
                value = stringResource(entry.flowRes)
            )
            GuideLabelValue(
                label = stringResource(R.string.mode_guide_tool_label),
                value = stringResource(entry.toolPolicyRes)
            )
            GuideLabelValue(
                label = stringResource(R.string.mode_guide_human_label),
                value = stringResource(entry.humanRes)
            )
            GuideLabelValue(
                label = stringResource(R.string.mode_guide_prompt_label),
                value = stringResource(entry.promptRes)
            )
        }
    }
}

/** 「标签：内容」小行（行为矩阵单元）。 */
@Composable
private fun GuideLabelValue(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 思考档位画像简表（v1.2：7 深度档 + AUTO 元档）：档位 / 迭代倍率 / 工具自检 / 终检 / 输出预算。 */
@Composable
private fun ThinkingLevelsTable() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.mode_guide_thinking_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.mode_guide_thinking_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 表头
        ThinkingTableRow(
            level = stringResource(R.string.mode_guide_thinking_col_level),
            iters = stringResource(R.string.mode_guide_thinking_col_iters),
            verify = stringResource(R.string.mode_guide_thinking_col_verify),
            budget = stringResource(R.string.mode_guide_thinking_col_budget),
            header = true
        )
        // 数据行（直读 core 静态表——UI 与引擎画像永远同源，杜绝文案漂移；
        // v1.2：MAXIMUM 与 AUTO 之间插入 ULTRACODE/APEXCODE 两个新深度档）
        listOf(
            ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD,
            ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE,
            ThinkingLevel.APEXCODE, ThinkingLevel.AUTO
        ).forEach { level ->
            val profile = ThinkingProfile.forLevel(level)
            ThinkingTableRow(
                level = level.name,
                iters = "×${profile.maxIterationsScale}",
                verify = when {
                    level == ThinkingLevel.AUTO -> stringResource(R.string.mode_guide_thinking_follow)
                    profile.finalSelfCheck -> stringResource(R.string.mode_guide_thinking_self_check)
                    profile.postToolVerification -> stringResource(R.string.mode_guide_thinking_post_tool)
                    else -> "—"
                },
                budget = profile.toolOutputBudget.toString()
            )
        }
    }
}

/** 简表行（header = true 时表头样式）。 */
@Composable
private fun ThinkingTableRow(
    level: String,
    iters: String,
    verify: String,
    budget: String,
    header: Boolean = false
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row {
        Text(
            text = level,
            style = style,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            modifier = Modifier.weight(1.4f)
        )
        Text(text = iters, style = style, color = color, modifier = Modifier.weight(0.8f))
        Text(text = verify, style = style, color = color, modifier = Modifier.weight(1.2f))
        Text(
            text = budget,
            style = style,
            color = color,
            modifier = Modifier.weight(0.8f)
        )
    }
}

/**
 * 单模式指南条目：模式 + 四个行为维度文案资源
 * （适用场景 / 执行流 / 工具策略 / 人工介入点 / 提示词段）。
 */
private data class ModeGuideEntry(
    val mode: AgentMode,
    val casesRes: Int,
    val flowRes: Int,
    val toolPolicyRes: Int,
    val humanRes: Int,
    val promptRes: Int
) {
    companion object {
        /** 六模式指南（AgentMode 声明序 = 菜单序 = 指南序）。 */
        val entries = listOf(
            ModeGuideEntry(
                mode = AgentMode.BUILD,
                casesRes = R.string.mode_guide_build_cases,
                flowRes = R.string.mode_guide_build_flow,
                toolPolicyRes = R.string.mode_guide_build_tools,
                humanRes = R.string.mode_guide_build_human,
                promptRes = R.string.mode_guide_build_prompt
            ),
            ModeGuideEntry(
                mode = AgentMode.PLAN,
                casesRes = R.string.mode_guide_plan_cases,
                flowRes = R.string.mode_guide_plan_flow,
                toolPolicyRes = R.string.mode_guide_plan_tools,
                humanRes = R.string.mode_guide_plan_human,
                promptRes = R.string.mode_guide_plan_prompt
            ),
            ModeGuideEntry(
                mode = AgentMode.SPEC,
                casesRes = R.string.mode_guide_spec_cases,
                flowRes = R.string.mode_guide_spec_flow,
                toolPolicyRes = R.string.mode_guide_spec_tools,
                humanRes = R.string.mode_guide_spec_human,
                promptRes = R.string.mode_guide_spec_prompt
            ),
            ModeGuideEntry(
                mode = AgentMode.REFLECTION,
                casesRes = R.string.mode_guide_reflect_cases,
                flowRes = R.string.mode_guide_reflect_flow,
                toolPolicyRes = R.string.mode_guide_reflect_tools,
                humanRes = R.string.mode_guide_reflect_human,
                promptRes = R.string.mode_guide_reflect_prompt
            ),
            ModeGuideEntry(
                mode = AgentMode.HUMAN_ASSIST,
                casesRes = R.string.mode_guide_assist_cases,
                flowRes = R.string.mode_guide_assist_flow,
                toolPolicyRes = R.string.mode_guide_assist_tools,
                humanRes = R.string.mode_guide_assist_human,
                promptRes = R.string.mode_guide_assist_prompt
            ),
            ModeGuideEntry(
                mode = AgentMode.CUSTOM,
                casesRes = R.string.mode_guide_custom_cases,
                flowRes = R.string.mode_guide_custom_flow,
                toolPolicyRes = R.string.mode_guide_custom_tools,
                humanRes = R.string.mode_guide_custom_human,
                promptRes = R.string.mode_guide_custom_prompt
            )
        )
    }
}
