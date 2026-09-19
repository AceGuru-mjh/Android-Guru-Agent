package com.apex.agent.ui.screen.glass

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.ui.glass.GlassBadge
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassFloatingButton
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.theme.ApexTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════
 *  玻璃实验室 —— Liquid Glass 内部验证页 · Spec §20
 * ═══════════════════════════════════════════════════════════════
 *
 * 本页仅供开发期人工验收玻璃系统，不进入任何用户流程：
 *  - Backdrop 采样验证区：可拖动玻璃片 + 网格 + 文字 + 漂移光斑，
 *    证明 backdrop 采样的是实时内容而非静态贴图；
 *  - 交互状态 / 档位阶梯 / 工具卡状态 / 输入聚焦：材质分级与状态驱动；
 *  - 玻璃对话框：HazeDialog 跨窗口采样验证区内容；
 *  - 诚实验收清单：运行时逐项核对能力声明 —— Refraction 未实现即红字示警。
 *    （以上共享小节在 [GlassLabSections.kt]，SRP 行数预算拆分）
 *
 * ## v2：夜间 / 白天 双实验室
 * 单页拆成两个独立实验室（顶部切换）：
 *  - **夜间模式**：强制深色主题 —— 近黑基底 + 霓虹光斑，验证玻璃在
 *    暗环境下的"发光材质"表现（HazeTint 提亮 + 主色浸染）；
 *  - **白天模式**：强制浅色主题 —— 白霜玻璃 + 柔光细网，且**更精致**：
 *    三段天空渐变底、20dp 发丝级细网格、三枚低饱和粉彩光斑、
 *    对角柔光带扫掠，并附带「日间精修」独有小节（强调色浸染速览）。
 *
 * 两个实验室各自包裹独立 [ApexTheme]，与系统深浅色设置互不影响 ——
 * 同一设备上并排验证玻璃材质的 Light / Dark 双态表现。
 *
 * 本页是全应用唯一允许无限循环动画的屏幕 —— 漂移光斑是验证实时采样
 * 的必要条件，其余业务界面一律禁止循环动画。
 */

// ═══ 采样验证区尺寸常量 ═══

private val ZoneHeight = 360.dp
private val ChipWidth = 150.dp
private val ChipHeight = 56.dp

/** 采样验证文字 —— 高对比等宽内容，供玻璃片压过验证采样真伪。 */
private const val GridRowText = "GLASS VERIFICATION GRID 0123456789"

// ═══════════════════════════════════════════════════════════════
//  实验室模式
// ═══════════════════════════════════════════════════════════════

/** 实验室形态：夜间（深色强制）/ 白天（浅色强制 · 更精致）。 */
enum class GlassLabMode(val label: String, val icon: ImageVector) {
    NIGHT("夜间模式", Icons.Default.NightsStay),
    DAY("白天模式", Icons.Default.LightMode)
}

// ═══════════════════════════════════════════════════════════════
//  入口
// ═══════════════════════════════════════════════════════════════

@Composable
fun GlassLabScreen() {
    var mode by rememberSaveable { mutableStateOf(GlassLabMode.NIGHT) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 模式切换在两个实验室主题之外 —— 始终以应用当前主题渲染，保证可读
        LabModeSwitcher(mode = mode, onSelect = { mode = it })

        when (mode) {
            GlassLabMode.NIGHT -> ApexTheme(darkTheme = true) {
                GlassLabContent(mode = GlassLabMode.NIGHT)
            }
            GlassLabMode.DAY -> ApexTheme(darkTheme = false) {
                GlassLabContent(mode = GlassLabMode.DAY)
            }
        }
    }
}

/** 夜间 / 白天 双实验室切换器（FilterChip 双列，当前形态高亮）。 */
@Composable
private fun LabModeSwitcher(mode: GlassLabMode, onSelect: (GlassLabMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassLabMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = { onSelect(candidate) },
                    label = { Text(candidate.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = candidate.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "两个实验室各自强制对应深浅主题（不影响系统设置）—— 夜间验证霓虹发光材质，白天验证白霜精修材质。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 实验室主体 —— 处于强制主题内，页面底色随模式走专属渐变。 */
@Composable
private fun GlassLabContent(mode: GlassLabMode) {
    // 全页唯一 HazeState：验证区背景源与对话框跨窗口采样共用同一份
    val backdropState = remember { HazeState() }
    var showDialog by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    // 模式专属页面底色：夜间近黑渐沉，白天通透提亮 —— 保证 Frosted 档
    // （无 backdrop 采样）也坐在正确的明暗基底上，不与外层主题串色。
    val pageBrush = if (mode == GlassLabMode.NIGHT) {
        Brush.verticalGradient(
            colors = listOf(scheme.surfaceContainerLowest, scheme.background)
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White,
            0.55f to scheme.background,
            1f to scheme.primaryContainer.copy(alpha = 0.35f)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBrush)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        LabHeader(mode)
        BackdropZone(state = backdropState, mode = mode)
        if (mode == GlassLabMode.DAY) {
            DaylightRefinementSection()
        }
        InteractionSection()
        TierLadderSection()
        ToolStatesSection()
        InputSection()
        DialogSection(onOpen = { showDialog = true })
        ChecklistSection(mode = mode)
    }

    if (showDialog) {
        LabGlassDialog(
            state = backdropState,
            onDismiss = { showDialog = false }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  页头
// ═══════════════════════════════════════════════════════════════

@Composable
private fun LabHeader(mode: GlassLabMode) {
    val subtitle = if (mode == GlassLabMode.NIGHT) {
        "夜间实验室：近黑基底上的发光玻璃 —— HazeTint 提亮 + 主色浸染，" +
            "霓虹光斑验证实时采样。终端与页面背景本身不是玻璃，不做冒充。"
    } else {
        "白天实验室：白基底上的乳白霜面玻璃 —— 更精致的发丝细网、三枚粉彩光斑与" +
            "对角柔光带。边缘转向冷灰以在白底上可见，材质克制不刷屏。"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Science,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "玻璃实验室 · Liquid Glass",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Backdrop 采样验证区 —— 本页核心
//  haze 源与玻璃叠加件必须是同一 Box 下的兄弟节点，绝不能嵌进源子树
// ═══════════════════════════════════════════════════════════════

@Composable
private fun BackdropZone(state: HazeState, mode: GlassLabMode) {
    var zoneSize by remember { mutableStateOf(IntSize.Zero) }
    // 按压演示反馈：材质验收页的按钮职能是「按下去看玻璃变化」—— 触觉反馈让按压
    // 有真实回响（原空 onClick 会让用户怀疑按钮失效）。
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pressFeedback = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }

    SectionHeader(
        title = "Backdrop 真实采样",
        hint = if (mode == GlassLabMode.NIGHT) {
            "拖动玻璃片扫过网格、文字与霓虹光斑 —— 玻璃内部画面必须实时变化"
        } else {
            "拖动玻璃片扫过细网、文字与粉彩光斑 —— 白霜玻璃下的画面必须实时变化"
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ZoneHeight)
            .clip(GlassShapes.card)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = GlassShapes.card
            )
            .onSizeChanged { coordinates -> zoneSize = coordinates }
    ) {
        // ── haze 源：静态层（渐变底 / 网格 / 文字，尺寸或主题变化才重绘）
        //    + 光斑层（每帧仅 2-3 个圆）。分层后动画帧绘制调用极少，
        //    光斑以低透明度叠加在静态内容之上 —— 灯光漫射语义，采样层不变。 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state)
        ) {
            StaticBackdropCanvas(modifier = Modifier.fillMaxSize(), mode = mode)
            GlowCanvas(modifier = Modifier.fillMaxSize(), mode = mode)
        }

        // ── 可拖动玻璃片：与 haze 源同层叠加，位置随手势累积 ──
        DraggableGlassChip(
            state = state,
            zoneSize = zoneSize
        )

        // ── 底部悬浮件组：全部接同一个 HazeState ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = "玻璃按钮",
                onClick = pressFeedback,
                state = state
            )
            GlassIconButton(
                icon = Icons.Default.BlurOn,
                contentDescription = "采样验证图标按钮",
                onClick = pressFeedback,
                state = state
            )
            GlassFloatingButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "采样验证悬浮球",
                onClick = pressFeedback,
                state = state,
                accent = MaterialTheme.colorScheme.primary
            )
        }

        // ── 右上角引擎徽标：运行时判定本设备模糊实现（真 blur 还是 scrim 兜底） ──
        GlassBadge(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            state = state,
            accent = if (Build.VERSION.SDK_INT >= 32) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            }
        ) {
            Icon(
                imageVector = if (Build.VERSION.SDK_INT >= 32) {
                    Icons.Default.BlurOn
                } else {
                    Icons.Default.Warning
                },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (Build.VERSION.SDK_INT >= 32) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Text(
                text = if (Build.VERSION.SDK_INT >= 32) {
                    "RenderEffect GPU 模糊"
                } else {
                    "API ${Build.VERSION.SDK_INT} · scrim 兜底"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // ── 左上角状态徽标：最后绘制，保持在最上层 ──
        GlassBadge(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            state = state,
            accent = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Default.BlurOn,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (mode == GlassLabMode.NIGHT) "夜间采样验证区" else "白天采样验证区",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 静态采样背景 —— 渐变 / 网格 / 文字，全部为高对比可验证内容。
 * 无状态读取：仅在尺寸或主题变化时重绘，动画帧零成本。
 *
 * 夜间 / 白天两套配方：
 *  - 夜间：surfaceVariant→background 深渐变 + 24dp 网格（0.35 线透明度）；
 *  - 白天（更精致）：白→背景→薄荷 tint 三段天空渐变 + 20dp 发丝细网
 *    （0.22 线透明度、亚像素描边）+ 对角柔光带 —— 白底玻璃的精细光影。
 */
@Composable
private fun StaticBackdropCanvas(modifier: Modifier, mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()

    val rowStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = scheme.onSurface.copy(alpha = if (mode == GlassLabMode.NIGHT) 0.85f else 0.65f)
    )
    // 预排版一次，绘制期零分配 —— 与性能验收项对齐
    val rowLayout = remember(rowStyle) {
        textMeasurer.measure(text = GridRowText, style = rowStyle)
    }

    Canvas(modifier = modifier) {
        // 1. 渐变底
        if (mode == GlassLabMode.NIGHT) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(scheme.surfaceVariant, scheme.background),
                    startY = 0f,
                    endY = size.height
                )
            )
        } else {
            // 白天三段天空渐变：白 → 背景灰 → 薄荷 tint，柔和而有层次
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White,
                    0.6f to scheme.background,
                    1f to scheme.primaryContainer.copy(alpha = 0.45f),
                    startY = 0f,
                    endY = size.height
                )
            )
        }

        // 2. 细网格 —— 模糊真伪一照便知
        //    夜间 24dp 常规网格；白天 20dp 发丝级细网（更细更淡更精致）
        val cell = (if (mode == GlassLabMode.NIGHT) 24.dp else 20.dp).toPx()
        val gridAlpha = if (mode == GlassLabMode.NIGHT) 0.35f else 0.22f
        val gridColor = scheme.onSurfaceVariant.copy(alpha = gridAlpha)
        val gridStroke = if (mode == GlassLabMode.NIGHT) 1f else 0.8f
        var gx = cell
        while (gx < size.width) {
            drawLine(
                color = gridColor,
                start = Offset(x = gx, y = 0f),
                end = Offset(x = gx, y = size.height),
                strokeWidth = gridStroke
            )
            gx += cell
        }
        var gy = cell
        while (gy < size.height) {
            drawLine(
                color = gridColor,
                start = Offset(x = 0f, y = gy),
                end = Offset(x = size.width, y = gy),
                strokeWidth = gridStroke
            )
            gy += cell
        }

        // 3. 白天专属：对角柔光带 —— 一道斜向日光扫过采样面
        if (mode == GlassLabMode.DAY) {
            val sweepWidth = size.width * 0.30f
            val sweepCenterX = size.width * 0.62f
            rotate(
                degrees = 16f,
                pivot = Offset(size.width / 2f, size.height / 2f)
            ) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0f),
                            Color.White.copy(alpha = 0.30f),
                            Color.White.copy(alpha = 0f)
                        ),
                        startX = sweepCenterX - sweepWidth,
                        endX = sweepCenterX + sweepWidth
                    ),
                    topLeft = Offset(sweepCenterX - sweepWidth, -size.height * 0.25f),
                    size = Size(sweepWidth * 2f, size.height * 1.5f)
                )
            }
        }

        // 4. 高对比等宽文字行 —— 交错缩进，行行压过网格
        val step = 24.dp.toPx()
        var index = 0
        var ty = 14.dp.toPx()
        while (ty < size.height) {
            val indent = if (index % 2 == 0) 14.dp.toPx() else 72.dp.toPx()
            drawText(
                rowLayout,
                topLeft = Offset(x = indent, y = ty)
            )
            ty += step
            index += 1
        }
    }
}

/**
 * 漂移光斑层 —— 实时采样的活体证明：背景在动，玻璃内容必须跟着动。
 * 本页被明确豁免循环动画禁令，仅供采样验证。
 * 性能：每帧仅 2-3 个 drawCircle（静态内容已剥离至 [StaticBackdropCanvas]）。
 *
 * 夜间：双霓虹光斑（primary / tertiary，0.18）；
 * 白天：三枚低饱和粉彩光斑（primary / secondary / tertiary，0.10-0.14）+ 更慢速漂移。
 */
@Composable
private fun GlowCanvas(modifier: Modifier, mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val glow = rememberInfiniteTransition(label = "glass_lab_glow")
    val phaseA by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (mode == GlassLabMode.NIGHT) 11000 else 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_primary"
    )
    val phaseB by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (mode == GlassLabMode.NIGHT) 8500 else 10500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_secondary"
    )
    val phaseC by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_tertiary"
    )

    Canvas(modifier = modifier) {
        if (mode == GlassLabMode.NIGHT) {
            // 双霓虹光斑 —— 主色 / 三级色，慢速环游，叠加在静态层之上
            val angleA = phaseA * 2f * PI.toFloat()
            val angleB = phaseB * 2f * PI.toFloat() + 2.1f
            drawCircle(
                color = scheme.primary.copy(alpha = 0.18f),
                radius = 140.dp.toPx(),
                center = Offset(
                    x = size.width * (0.5f + 0.34f * sin(angleA)),
                    y = size.height * (0.42f + 0.28f * cos(angleA))
                )
            )
            drawCircle(
                color = scheme.tertiary.copy(alpha = 0.18f),
                radius = 110.dp.toPx(),
                center = Offset(
                    x = size.width * (0.5f + 0.36f * cos(angleB)),
                    y = size.height * (0.55f + 0.30f * sin(angleB))
                )
            )
        } else {
            // 三枚粉彩光斑 —— 薄荷 / 琥珀 / 品红，低饱和漫射
            val angleA = phaseA * 2f * PI.toFloat()
            val angleB = phaseB * 2f * PI.toFloat() + 2.1f
            val angleC = phaseC * 2f * PI.toFloat() + 4.2f
            drawCircle(
                color = scheme.primary.copy(alpha = 0.14f),
                radius = 130.dp.toPx(),
                center = Offset(
                    x = size.width * (0.5f + 0.32f * sin(angleA)),
                    y = size.height * (0.40f + 0.26f * cos(angleA))
                )
            )
            drawCircle(
                color = scheme.secondary.copy(alpha = 0.12f),
                radius = 100.dp.toPx(),
                center = Offset(
                    x = size.width * (0.5f + 0.36f * cos(angleB)),
                    y = size.height * (0.58f + 0.28f * sin(angleB))
                )
            )
            drawCircle(
                color = scheme.tertiary.copy(alpha = 0.10f),
                radius = 90.dp.toPx(),
                center = Offset(
                    x = size.width * (0.5f + 0.30f * sin(angleC + 1.3f)),
                    y = size.height * (0.50f + 0.32f * cos(angleC))
                )
            )
        }
    }
}

/**
 * 可拖动玻璃片 —— 拖动累积位移并钳制在验证区内；双击复位。
 * 片内实时回显坐标（等宽字体）：拖动时坐标数字在变，玻璃下方的内容也在变
 * —— 采样必须跟随位置实时更新，这是比光斑更强的逐帧验证信号。
 */
@Composable
private fun DraggableGlassChip(state: HazeState, zoneSize: IntSize) {
    var chipOffset by remember { mutableStateOf(Offset.Zero) }

    GlassCard(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = chipOffset.x.roundToInt(),
                    y = chipOffset.y.roundToInt()
                )
            }
            .size(width = ChipWidth, height = ChipHeight)
            .pointerInput(Unit) {
                // 双击复位 —— 拖丢后一键回原点
                detectTapGestures(onDoubleTap = { chipOffset = Offset.Zero })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val maxX = (zoneSize.width - ChipWidth.roundToPx())
                        .coerceAtLeast(0)
                        .toFloat()
                    val maxY = (zoneSize.height - ChipHeight.roundToPx())
                        .coerceAtLeast(0)
                        .toFloat()
                    chipOffset = Offset(
                        x = (chipOffset.x + dragAmount.x).coerceIn(0f, maxX),
                        y = (chipOffset.y + dragAmount.y).coerceIn(0f, maxY)
                    )
                }
            },
        state = state,
        style = GlassStyle.Floating,
        shape = GlassShapes.card
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    text = "拖动我 · 双击复位",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "x ${chipOffset.x.roundToInt()} · y ${chipOffset.y.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
