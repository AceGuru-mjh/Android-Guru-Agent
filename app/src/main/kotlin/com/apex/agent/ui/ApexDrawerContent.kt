package com.apex.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.ui.glass.GlassNavigationItem
import com.apex.agent.ui.screen.agent.AgentChatViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * ═══════════════════════════════════════════════════════════════
 *  ApexDrawerContent —— Liquid Glass 迁移版
 * ═══════════════════════════════════════════════════════════════
 *
 * Spec §5B / §17：
 *  - Drawer 本体不整面玻璃化 —— 玻璃只落在每个 Navigation Item 上；
 *  - 每项保留品牌层级：霓虹指示条 + 圆形图标井 + 文字；
 *  - Normal 非常轻 / Selected 提亮 / Pressed 短暂高光；
 *  - 结构：氛围背景 = hazeSource —— 导航项悬浮其上做真实 backdrop 采样。
 */
@Composable
fun ApexDrawerContent(
    currentDestination: DrawerDestination,
    onDestinationSelected: (DrawerDestination) -> Unit,
    tokenManager: GithubTokenManager
) {
    val agentVm: AgentChatViewModel = hiltViewModel()
    val agentState by agentVm.uiState.collectAsStateWithLifecycle()
    val githubState by tokenManager.connectionState.collectAsStateWithLifecycle()
    val glassState = remember { HazeState() }

    ModalDrawerSheet(
        modifier = Modifier.width(288.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // ═══ 玻璃舞台：氛围背景为源，导航列悬浮采样 ═══
        Box(modifier = Modifier.fillMaxSize()) {
            DrawerAuroraBackdrop(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(glassState)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // ═══ 品牌头部（霓虹光晕，随内容滚动） ═══
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            tonalElevation = 0.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column {
                            Text(
                                "APEX//AGENT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "全能 AI 助手",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        "v1.0",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // ═══ 玻璃导航项 —— 每项独立玻璃材质，Normal 极轻 ═══
                val destinations = listOf(
                    DrawerDestination.Agent,
                    DrawerDestination.Terminal,
                    DrawerDestination.Market,
                    DrawerDestination.Memory,
                    DrawerDestination.Tasks,
                    DrawerDestination.Storage,
                    DrawerDestination.Permissions,
                    DrawerDestination.Log,
                    DrawerDestination.Settings,
                    DrawerDestination.GlassLab
                )

                destinations.forEach { dest ->
                    GlassNavigationItem(
                        icon = dest.icon,
                        label = dest.label,
                        selected = currentDestination == dest,
                        onClick = { onDestinationSelected(dest) },
                        state = glassState,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ═══ 底部状态（等宽 chip） ═══
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusChip("MODE", agentState.mode.name)
                        StatusChip("THINK", agentState.thinkingLevel.name)
                    }
                    if (agentState.historyDepth > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusChip("MEM", "${agentState.historyDepth} 条")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // GitHub 连接状态常显（不再仅在触发 /mcp:github 时提示）
                    GithubStatusRow(
                        isConnected = githubState.isConnected,
                        username = githubState.username
                    )
                }
            }
        }
    }
}

/**
 * 抽屉氛围背景 —— 玻璃的真实采样源。
 * 渐变 + 双光晕 + 细网格：终端质感的“可被玻璃折射的环境光”。
 * 静态绘制，无逐帧动画 —— 性能零负担。
 */
@Composable
private fun DrawerAuroraBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceContainerLow
    val glowA = scheme.primary.copy(alpha = 0.10f)
    val glowB = scheme.tertiary.copy(alpha = 0.07f)
    val grid = scheme.onSurfaceVariant.copy(alpha = 0.05f)
    val topWash = scheme.primaryContainer.copy(alpha = 0.22f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(base)
        // 顶部主色洗刷 —— 与品牌头呼应
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(topWash, Color.Transparent),
                startY = 0f,
                endY = h * 0.30f
            )
        )
        // 双光晕：右上主色 / 左下品红
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowA, Color.Transparent),
                center = Offset(w * 0.85f, h * 0.12f),
                radius = w * 0.9f
            ),
            radius = w * 0.9f,
            center = Offset(w * 0.85f, h * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowB, Color.Transparent),
                center = Offset(w * 0.10f, h * 0.92f),
                radius = w * 0.8f
            ),
            radius = w * 0.8f,
            center = Offset(w * 0.10f, h * 0.92f)
        )
        // 细网格 —— 玻璃滑过时提供可见的“内容”纹理
        val cell = 24.dp.toPx()
        var x = 0f
        while (x < w) {
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            x += cell
        }
        var y = 0f
        while (y < h) {
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            y += cell
        }
    }
}

/**
 * 抽屉 footer 的 GitHub 连接状态行：常显。
 * - 已连接：绿色圆点 + "@用户名"
 * - 未连接：灰色圆点 + "GitHub 未连接"
 */
@Composable
private fun GithubStatusRow(
    isConnected: Boolean,
    username: String?
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    )
            )
            Text(
                text = if (isConnected) "@${username ?: "GitHub"}" else "GitHub 未连接",
                style = MaterialTheme.typography.labelSmall,
                color = if (isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
