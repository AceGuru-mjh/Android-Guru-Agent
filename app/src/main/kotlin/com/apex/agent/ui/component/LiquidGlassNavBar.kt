package com.apex.agent.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃底部导航栏（圆形长条）
 *
 * 基于 io.github.kyant0:backdrop 1.0.0（Compose Multiplatform Liquid Glass）：
 * - [backdrop] 由调用方用 rememberLayerBackdrop() 创建，并把内容区注册为
 *   背景源（Modifier.layerBackdrop），导航栏即可实时模糊背后的内容；
 * - 效果栈：vibrancy（色彩增强）+ blur（高斯模糊）+ lens（折射透镜），
 *   均为 RenderEffect（API 31+ 硬件加速），低版本自动降级为纯 surface 色；
 * - 选中项圆形按钮：青霓虹填充 + 发光投影，未选中半透明白。
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
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    // 将外部 backdrop（内容层快照）包装为本组件专属绘制源
    val glassBackdrop = rememberBackdrop(backdrop) { drawBackdrop -> drawBackdrop() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { RoundedCornerShape(36.dp) },
                effects = {
                    vibrancy()
                    blur(10f.dp.toPx())
                    lens(16f.dp.toPx(), 16f.dp.toPx())
                },
                onDrawSurface = {
                    // 玻璃基色：半透明白
                    drawRect(Color.White.copy(alpha = 0.14f))
                }
            )
            .height(72.dp)
    ) {
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
