package com.apex.agent.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 顶部上下文仪表盘长条。
 *
 * 视觉：横向长条，已用上下文以霓虹色填充（带发光光晕），未用部分为暗灰半透明。
 * 占比 = 真实上下文使用比例（used / max）。颜色随阈值变化：
 *   - <60%  正常（霓虹青 primary）
 *   - 60-80% 警告（橙）
 *   - >80%  危险（粉/红）
 *
 * 点击长条弹出仪表盘菜单：token 详细数据 + 主动压缩按钮。
 *
 * @param usedTokens 当前占用 token（分子）
 * @param maxTokens  上下文上限 token（分母，<=0 时视为 1 防除零）
 * @param onCompress 主动压缩回调（接 AgentChatViewModel.compressNow）
 */
@Composable
fun ContextMeterBar(
    usedTokens: Int,
    maxTokens: Int,
    onCompress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMax = if (maxTokens <= 0) 1 else maxTokens
    val ratio = (usedTokens.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    val percent = (ratio * 100).toInt()

    // 颜色阈值：正常青 / 警告橙 / 危险粉红
    val accent = when {
        percent >= 80 -> Color(0xFFFF4D8D) // 霓虹粉
        percent >= 60 -> Color(0xFFFFB020) // 警告橙
        else -> MaterialTheme.colorScheme.primary // 霓虹青
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(durationMillis = 400),
        label = "meter_ratio"
    )
    // 危险态（>80%）脉冲辉光，增强告警未来感。
    // P3-f（6-c）：infinite transition 移入危险态分支组合——正常/警告态下不再常驻
    // 无限动画（原先每个空闲帧都在跑），仅 >=80% 时才启动脉冲。
    val glowAlpha = if (percent >= 80) {
        val pulse by rememberInfiniteTransition(label = "meter_pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "glow_alpha"
        )
        pulse
    } else 0.5f

    // ═══ 高度收紧（用户反馈「上面那一部分太高」）：整条从 48dp 点击槽位压到 20dp。═══
    // 顶栏纵向叠了 TopAppBar(64) + 本条 + 模式栏(~52)，此前本条以「48dp 最小触摸目标」
    // 占位把顶部推到 ~164dp。全宽长条的触摸面积远超 48x48dp（宽度补偿高度，
    // Material 无障碍对超宽元素允许 <48dp 高度），仪表盘是辅助诊断入口而非高频操作。
    Column(modifier = modifier.fillMaxWidth()) {
        // ═══ 顶部长条（点击弹仪表盘）═══
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clickable { menuExpanded = true },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(horizontal = 12.dp)
            ) {
                // 未使用段（暗灰半透明，占满）—— 4dp 细条配 2dp 圆角
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                // 已用段（霓虹辉光 + 渐变实体 + 末端亮点，按真实比例）
                if (animatedRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedRatio)
                            .fillMaxHeight()
                    ) {
                        // 辉光层：同色放大 + 原生 blur（零依赖霓虹弥散）
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .blur(7.dp)
                                .background(color = accent.copy(alpha = glowAlpha))
                        )
                        // 实体段：横向渐变（中心亮→边缘微暗）增加体积感
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            accent.copy(alpha = 0.85f),
                                            accent,
                                            accent.copy(alpha = 0.9f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                        // 末端高光点（能量流头部）
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(6.dp)
                                .blur(2.dp)
                                .background(color = Color.White.copy(alpha = 0.9f))
                        )
                    }
                }
            }
        }

        // ═══ 仪表盘下拉菜单 ═══
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            Text(
                text = stringResource(R.string.context_dashboard),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()

            // token 详细数据
            DashboardRow(label = stringResource(R.string.context_used_tokens), value = "$usedTokens")
            DashboardRow(label = stringResource(R.string.context_max_tokens), value = "$safeMax")
            DashboardRow(label = stringResource(R.string.context_usage_ratio), value = "$percent%")
            DashboardRow(
                label = stringResource(R.string.context_status),
                value = when {
                    percent >= 80 -> stringResource(R.string.context_status_danger)
                    percent >= 60 -> stringResource(R.string.context_status_warning)
                    else -> stringResource(R.string.context_status_normal)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 主动压缩按钮
            DropdownMenuItem(
                text = { Text(stringResource(R.string.context_compress)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Compress,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    menuExpanded = false
                    onCompress()
                }
            )
            Text(
                text = stringResource(R.string.context_compress_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DashboardRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
