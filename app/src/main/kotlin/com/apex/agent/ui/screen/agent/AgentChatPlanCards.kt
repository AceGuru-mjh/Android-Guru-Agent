package com.apex.agent.ui.screen.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.ExecutionSpec
import com.apex.agent.core.engine.RiskLevel
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassCard

/**
 * 流水线路由横幅：`/skill:xxx` `/connector:xxx` `/plugin:xxx` 触发时的专用卡片。
 *
 * 与工具卡片同色系（Skill=primary+AutoAwesome / 连接器=紫+Link / 插件=琥珀+Extension），
 * 运行中右侧脉冲圆点表示 Agent 循环仍在运行；完成后变为对勾并显示总耗时，
 * 其后同来源的工具调用会以对应来源徽章展示，形成完整的执行链路视觉。
 */
@Composable
internal fun PipelineBannerCard(banner: AgentUiMessage.PipelineBanner) {
    val finished = banner.finishedAt != null
    // i18n：标签改持 @StringRes，组合内 stringResource 取词
    val style: Triple<Int, ImageVector, Color> = when (banner.kind) {
        ToolKind.CONNECTOR -> Triple(
            R.string.chat_pipeline_calling_connector,
            Icons.Default.Link,
            Color(0xFF8B5CF6)
        )
        ToolKind.PLUGIN -> Triple(
            R.string.chat_pipeline_calling_plugin,
            Icons.Default.Extension,
            Color(0xFFF59E0B)
        )
        else -> Triple(
            R.string.chat_pipeline_running_skill,
            Icons.Default.AutoAwesome,
            MaterialTheme.colorScheme.primary
        )
    }
    val (runningLabelRes, icon, color) = style
    val title = when {
        finished && banner.kind == ToolKind.CONNECTOR -> stringResource(R.string.chat_pipeline_connector_done)
        finished && banner.kind == ToolKind.PLUGIN -> stringResource(R.string.chat_pipeline_plugin_done)
        finished -> stringResource(R.string.chat_pipeline_skill_done)
        else -> stringResource(runningLabelRes)
    }

    // 脉冲动画仅在运行态创建——完成态横幅不再运行无限动画，避免常驻重组开销。
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Surface(
                color = color.copy(alpha = 0.18f),
                shape = CircleShape,
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = banner.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (finished) {
                // 完成态：对勾 + 总耗时
                val durationMs = (banner.finishedAt ?: 0L) - banner.startedAt
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.chat_cd_done),
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                // 运行态：脉冲圆点
                val pulse by rememberInfiniteTransition(label = "pipeline-banner-pulse").animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "pipeline-banner-alpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color.copy(alpha = pulse), CircleShape)
                )
            }
        }
    }
}

/** 锁定计划的步骤视觉态：已完成（暗淡）/ 执行中（高亮）/ 待执行。 */
private enum class PlanStepVisual { PENDING, CURRENT, DONE }

@Composable
internal fun PlanCard(plan: ExecutionPlan, currentStepIndex: Int = -1) {
    // Liquid Glass 迁移：GlassCard Frosted 档 —— 列表内卡片不冒充 backdrop
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("📋 Execution Plan", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                // #169 锁定徽标：PlanMessage 仅在用户确认后入列 —— 计划已锁定，
                // 执行期间不可修改（引擎侧 locked 局部 val，UI 只读展示）。
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = stringResource(R.string.plan_locked),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.plan_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            plan.steps.forEach { step ->
                // #169 执行进度可视化：当前步（StepStart 的 index）高亮，
                // 已过步骤暗淡，待执行步骤正常色。
                val visual = when {
                    step.index == currentStepIndex -> PlanStepVisual.CURRENT
                    currentStepIndex >= 0 && step.index < currentStepIndex -> PlanStepVisual.DONE
                    else -> PlanStepVisual.PENDING
                }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (visual == PlanStepVisual.CURRENT) "▶ ${step.index + 1}." else "${step.index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (visual == PlanStepVisual.CURRENT) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        step.description,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (visual == PlanStepVisual.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
                        color = when (visual) {
                            PlanStepVisual.CURRENT -> MaterialTheme.colorScheme.primary
                            PlanStepVisual.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            PlanStepVisual.PENDING -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/**
 * #169 计划确认卡（人控）：每步 Checkbox（默认勾选）+ 上移/下移重排，
 * 未勾选步骤灰色划线；「执行」保持在卡片最末，未勾选任何步骤时禁用
 * （引擎侧对空集另有防御回退，见 PlanGraph.applyAdjustments）。
 *
 * 提交语义：onConfirm 回传（勾选的原 index 集，展示顺序的原 index 清单）；
 * 引擎 Phase 3.5 用 PlanGraph 应用筛选/重排并做 dependsOn 拓扑校验 ——
 * 用户顺序若与依赖冲突，拓扑排序自动纠正并附警告。
 */
@Composable
internal fun PlanConfirmationCard(
    plan: ExecutionPlan,
    onConfirm: (enabledSteps: List<Int>, order: List<Int>) -> Unit,
    onReject: () -> Unit
) {
    // 人控状态：勾选集 + 展示顺序（均存「原 step.index」；确认时整体回传，
    // UI 不预演拓扑结果 —— 锁定前的最终顺序由引擎决定）。
    val enabledIds = remember(plan) {
        mutableStateMapOf<Int, Boolean>().apply { plan.steps.forEach { put(it.index, true) } }
    }
    val orderIds = remember(plan) {
        mutableStateListOf<Int>().apply { addAll(plan.steps.map { it.index }) }
    }
    val anyEnabled = orderIds.any { enabledIds[it] == true }

    // Liquid Glass 迁移：GlassCard Frosted 档 + secondary accent 延续确认卡语义色
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.secondary
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.chat_confirm_plan_title), style = MaterialTheme.typography.titleSmall)
                // 规划期只读徽标（#169）：Agent 处于只读规划阶段，尚未执行任何操作
                Surface(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plan_planning_readonly),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.plan_adjust_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            orderIds.forEachIndexed { displayPos, stepId ->
                val step = plan.steps.firstOrNull { it.index == stepId } ?: return@forEachIndexed
                val checked = enabledIds[stepId] == true
                val stepEnableDesc = stringResource(R.string.plan_step_enable)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp)
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { enabledIds[stepId] = it },
                        modifier = Modifier
                            .size(32.dp)
                            .semantics { contentDescription = stepEnableDesc }
                    )
                    Text(
                        text = "${displayPos + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (checked) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodySmall,
                        // 未勾选步骤：灰色 + 划线（执行时将被跳过）
                        color = if (checked) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (checked) null else TextDecoration.LineThrough,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (displayPos > 0) orderIds.add(displayPos - 1, orderIds.removeAt(displayPos))
                        },
                        enabled = displayPos > 0,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            stringResource(R.string.plan_move_up),
                            Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (displayPos < orderIds.lastIndex) orderIds.add(displayPos + 1, orderIds.removeAt(displayPos))
                        },
                        enabled = displayPos < orderIds.lastIndex,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            stringResource(R.string.plan_move_down),
                            Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            // 「执行」保持在卡片最末；空选禁用
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.chat_cancel)) }
                Button(
                    enabled = anyEnabled,
                    onClick = {
                        onConfirm(orderIds.filter { enabledIds[it] == true }, orderIds.toList())
                    }
                ) { Text(stringResource(R.string.chat_execute)) }
            }
        }
    }
}

// ═══ Spec 模式组件 ═══

/**
 * 风险等级 → 颜色（Spec / Plan 卡片通用）。
 */
@Composable
internal fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.LOW -> Color(0xFF22C55E)
    RiskLevel.MEDIUM -> Color(0xFFF59E0B)
    RiskLevel.HIGH -> Color(0xFFF97316)
    RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
}

/**
 * 需求规格卡片（Spec 模式确认通过后展示）：
 * 目标 / 需求 / 约束 / 验收标准 / 交付物，分节呈现。
 */
@Composable
internal fun SpecCard(spec: ExecutionSpec) {
    // Liquid Glass 迁移：GlassCard Frosted 档 + primary 倾向着色
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        accent = MaterialTheme.colorScheme.primary
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = CircleShape,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(stringResource(R.string.chat_spec_title), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                // 风险徽章
                val risk = riskColor(spec.riskLevel)
                Surface(
                    color = risk.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.chat_risk_level, spec.riskLevel.name),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = risk,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.chat_spec_goal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = spec.goal,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            SpecSection(stringResource(R.string.chat_spec_requirements), spec.requirements)
            SpecSection(stringResource(R.string.chat_spec_constraints), spec.constraints)
            SpecSection(stringResource(R.string.chat_spec_acceptance), spec.acceptanceCriteria)
            SpecSection(stringResource(R.string.chat_spec_deliverables), spec.deliverables)
        }
    }
}

/**
 * Spec 分节列表（空列表自动隐藏）。
 */
@Composable
internal fun SpecSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(2.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier.padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = item,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Spec 确认卡：展示规格要点，等待用户确认/驳回。
 */
@Composable
internal fun SpecConfirmationCard(
    spec: ExecutionSpec,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    // Liquid Glass 迁移：GlassCard Frosted 档 + primary 倾向着色
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.chat_confirm_spec_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_spec_goal_line, spec.goal),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (spec.deliverables.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.chat_spec_deliverables_line,
                        spec.deliverables.joinToString("、")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.chat_reject)) }
                androidx.compose.material3.Button(onClick = onConfirm) { Text(stringResource(R.string.chat_confirm_execute)) }
            }
        }
    }
}
