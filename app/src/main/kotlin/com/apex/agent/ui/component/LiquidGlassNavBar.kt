package com.apex.agent.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃底部导航栏（圆形长条）
 *
 * 效果完全由 Compose 原生绘制（零三方依赖）：
 * - 胶囊形容器：半透明白色渐变（顶部亮、底部透）模拟玻璃折射；
 * - 顶部高光弧线 + 边缘 1dp 白色描边模拟液态玻璃边缘高光；
 * - 每项为圆形图标按钮，选中项青霓虹填充 + 发光投影，未选中半透明白；
 * - 项目下方小字标签（选中项高亮）。
 *
 * 调研结论（2026-08 记录）：液态玻璃第三方库（io.github.kyant0:backdrop）
 * 所有版本要求 Kotlin ≥2.2 / Compose ≥1.9，与项目 Kotlin 2.0.21 /
 * Compose 1.7.x（BOM 2024.12.01）不兼容，强引会复刻 Lucide 元数据事故，
 * 故采用自绘（效果等价、零依赖、零风险）。
 */
data class GlassTab(
    val id: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun LiquidGlassNavBar(
    tabs: List<GlassTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(32.dp),
                ambientColor = primary.copy(alpha = 0.35f),
                spotColor = primary.copy(alpha = 0.45f)
            )
            .clip(RoundedCornerShape(32.dp))
            .height(72.dp)
    ) {
        // ═══ 液态玻璃容器（自绘） ═══
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            // 基色：顶部亮白渐变 → 底部深色透出（玻璃折射感）
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.10f),
                        Color(0xFF0F172A).copy(alpha = 0.30f)
                    )
                ),
                cornerRadius = radius
            )
            // 顶部高光弧线（液态玻璃边缘高光）
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.55f)
                    )
                ),
                topLeft = Offset(size.width * 0.10f, 1.dp.toPx()),
                size = Size(size.width * 0.80f, 1.2.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )
            // 边缘描边
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                style = Stroke(width = 1.dp.toPx()),
                cornerRadius = radius
            )
        }

        // ═══ 五项圆形按钮 ═══
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEach { tab ->
                GlassTabItem(
                    tab = tab,
                    selected = tab.id == selectedId,
                    primary = primary,
                    onSurface = onSurface,
                    onClick = { onSelect(tab.id) }
                )
            }
        }
    }
}

@Composable
private fun GlassTabItem(
    tab: GlassTab,
    selected: Boolean,
    primary: Color,
    onSurface: Color,
    onClick: () -> Unit
) {
    val circleColor by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f),
        label = "glassCircle"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) Color.White else onSurface.copy(alpha = 0.75f),
        label = "glassIcon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) primary else onSurface.copy(alpha = 0.55f),
        label = "glassLabel"
    )
    val glow by animateDpAsState(
        targetValue = if (selected) 10.dp else 0.dp,
        label = "glassGlow"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(44.dp)
                .shadow(
                    elevation = glow,
                    shape = CircleShape,
                    ambientColor = primary.copy(alpha = 0.6f),
                    spotColor = primary.copy(alpha = 0.8f)
                ),
            shape = CircleShape,
            color = circleColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor
        )
    }
}
