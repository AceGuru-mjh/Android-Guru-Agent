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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.ExecutionSpec
import com.apex.agent.core.engine.RiskLevel

/**
 * Skill 执行横幅：`/skill:xxx` 启动 Skill 时的专用卡片。
 *
 * 与工具卡片 SKILL 徽章同色系（primary + AutoAwesome 图标），标题注明
 * "正在执行 Skill"，右侧脉冲圆点表示 Agent 循环仍在运行。其后该 Skill
 * 产生的工具调用会以 SKILL 来源徽章展示，形成完整的执行链路视觉。
 */
@Composable
internal fun SkillBannerCard(skill: String) {
    val color = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "skill-banner-pulse").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skill-banner-alpha"
    )
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
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在执行 Skill",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = skill,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = pulse), CircleShape)
            )
        }
    }
}

@Composable
internal fun PlanCard(plan: ExecutionPlan) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("📋 Execution Plan", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            plan.steps.forEach { step ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${step.index + 1}.", style = MaterialTheme.typography.bodySmall)
                    Text(step.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun PlanConfirmationCard(
    plan: ExecutionPlan,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("确认执行此计划？", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject) { Text("取消") }
                androidx.compose.material3.Button(onClick = onConfirm) { Text("执行") }
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
        )
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
                Text("📐 需求规格", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                // 风险徽章
                val risk = riskColor(spec.riskLevel)
                Surface(
                    color = risk.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "风险 ${spec.riskLevel.name}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = risk,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "目标",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = spec.goal,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            SpecSection("需求", spec.requirements)
            SpecSection("约束", spec.constraints)
            SpecSection("验收标准", spec.acceptanceCriteria)
            SpecSection("交付物", spec.deliverables)
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("确认此规格并开始执行？", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "目标：${spec.goal}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (spec.deliverables.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "交付物：${spec.deliverables.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject) { Text("驳回") }
                androidx.compose.material3.Button(onClick = onConfirm) { Text("确认执行") }
            }
        }
    }
}
