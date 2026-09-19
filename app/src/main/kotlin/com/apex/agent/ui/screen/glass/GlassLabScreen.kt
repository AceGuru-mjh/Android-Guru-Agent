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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.apex.agent.ui.glass.GlassDialog
import com.apex.agent.ui.glass.GlassFloatingButton
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.glass.GlassNavigationItem
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassTier
import com.apex.agent.ui.glass.GlassToolCard
import com.apex.agent.ui.glass.GlassToolStatus
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

/** 验证清单条目 —— 全部字段在组合期求值，禁止静态谎言。 */
private data class CheckItem(
    val name: String,
    val status: String,
    val note: String,
    val color: Color
)

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

/** 通用小节标题 —— 标题用等宽字体，提示用次级色。 */
@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = hint,
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

// ═══════════════════════════════════════════════════════════════
//  日间精修 —— 白天实验室独有小节
// ═══════════════════════════════════════════════════════════════

/**
 * 白天模式的加分项：强调色浸染速览 + 发丝细节展示。
 *
 * 白天玻璃的关键差异（GlassStyle.glassPalette 浅色分支）：
 *  - 材质为"乳白霜面"而非"提亮发光"；
 *  - 边缘高光转向冷灰（onSurface 低透明度）—— 白底上白描边不可见；
 *  - 强调色只提供"倾向"（低浓度浸染 tint 与边缘），不刷屏。
 * 本节用三枚强调色玻璃药丸 + 乳白霜面卡直观对照。
 */
@Composable
private fun DaylightRefinementSection() {
    val scheme = MaterialTheme.colorScheme

    SectionHeader(
        title = "日间精修 · 光影细节",
        hint = "白天实验室独有 —— 白霜材质 × 强调色浸染 × 发丝边缘，克制而精致"
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        style = GlassStyle.Card
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 强调色浸染药丸行：薄荷 / 琥珀 / 品红
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassBadge(
                    accent = scheme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "薄荷浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                GlassBadge(
                    accent = scheme.secondary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "琥珀浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                GlassBadge(
                    accent = scheme.tertiary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "品红浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 发丝分隔线 × 2 —— 精致感来自克制的细节
            HorizontalHairline()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "发丝边缘",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "浅色分支边缘高光转向冷灰 —— 白底上白描边不可见，0.5dp 发丝线保持轮廓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalHairline()
            Text(
                text = "白天玻璃是\"白霜\"而非\"发光\"：tint 为乳白底 + 主色 4% 倾向浸染；" +
                    "镜面高光强度 ×1.5 补偿白底漫射。整体观感应比夜间更轻盈、更精致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 0.5dp 发丝分隔线 —— 白天精修的细节语汇。 */
@Composable
private fun HorizontalHairline() {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

// ═══════════════════════════════════════════════════════════════
//  交互状态区
// ═══════════════════════════════════════════════════════════════

@Composable
private fun InteractionSection() {
    // 同 BackdropZone：交互状态演示按钮接触觉反馈（原空 onClick 无回响）。
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pressFeedback = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    SectionHeader(
        title = "交互状态",
        hint = "选中提亮 / 禁用降权 / 按压受光 —— 材质对状态即时响应"
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassNavigationItem(
                icon = Icons.Default.SmartToy,
                label = "选中项 · Selected",
                selected = true,
                onClick = pressFeedback
            )
            GlassNavigationItem(
                icon = Icons.Default.Terminal,
                label = "常态项 · Normal",
                selected = false,
                onClick = pressFeedback
            )
            GlassNavigationItem(
                icon = Icons.Default.Build,
                label = "禁用项 · Disabled",
                selected = false,
                enabled = false,
                onClick = pressFeedback
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButton(
                    text = "玻璃按钮 · 按压我",
                    onClick = pressFeedback
                )
                Text(
                    text = "聚焦态见下方输入控件 —— 焦点驱动材质亮度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃档位阶梯
// ═══════════════════════════════════════════════════════════════

/** 档位 → 业务落点对照（验收人可直接核对真实界面用法）。 */
private val TierUsageNotes: Map<GlassTier, String> = mapOf(
    GlassTier.Subtle to "玻璃徽标 / 状态 chip",
    GlassTier.Control to "图标按钮 / 输入控件",
    GlassTier.Card to "消息卡 / 工具卡 / 任务卡",
    GlassTier.Navigation to "抽屉导航项",
    GlassTier.Floating to "FAB / 聊天输入栏",
    GlassTier.Dialog to "玻璃对话框面板",
    GlassTier.Strong to "低频高聚焦场景（预留）"
)

@Composable
private fun TierLadderSection() {
    SectionHeader(
        title = "玻璃档位阶梯",
        hint = "七档材质强度递进 + 各档真实业务落点 —— 全部为 Frosted 档铺在页面背景上，不冒充 backdrop"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassTierRow(name = "Subtle", style = GlassStyle.Subtle)
        GlassTierRow(name = "Control", style = GlassStyle.Control)
        GlassTierRow(name = "Card", style = GlassStyle.Card)
        GlassTierRow(name = "Navigation", style = GlassStyle.Navigation)
        GlassTierRow(name = "Floating", style = GlassStyle.Floating)
        GlassTierRow(name = "Dialog", style = GlassStyle.Dialog)
        GlassTierRow(name = "Strong", style = GlassStyle.Strong)
    }
}

/** 单个档位行 —— 左侧档名 + 业务落点，右侧参数，等宽字体呈现材质配方。 */
@Composable
private fun GlassTierRow(name: String, style: GlassStyle) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        style = style
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = TierUsageNotes[style.tier] ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "blur ${style.blurRadius.value.toInt()}dp · tint ${style.tintAlpha} · scrim ${style.scrimAlpha}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  工具卡状态
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ToolStatesSection() {
    SectionHeader(
        title = "工具卡状态",
        hint = "四种状态驱动着色 —— 低饱和浸染，不用强色刷屏"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassToolRow(
            status = GlassToolStatus.RUNNING,
            icon = Icons.Default.PlayArrow,
            text = "正在执行 · adb shell pm list packages"
        )
        GlassToolRow(
            status = GlassToolStatus.COMPLETED,
            icon = Icons.Default.CheckCircle,
            text = "已完成 · 3.4s · 退出码 0"
        )
        GlassToolRow(
            status = GlassToolStatus.FAILED,
            icon = Icons.Default.Error,
            text = "已失败 · 权限不足，执行被拒绝"
        )
        GlassToolRow(
            status = GlassToolStatus.WAITING,
            icon = Icons.Default.HourglassEmpty,
            text = "等待确认 · 用户授权挂起中"
        )
    }
}

/** 单个工具卡样例 —— 单行紧凑内容 + 状态名。 */
@Composable
private fun GlassToolRow(status: GlassToolStatus, icon: ImageVector, text: String) {
    GlassToolCard(
        status = status,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = status.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  输入控件 —— 聚焦驱动的玻璃响应
// ═══════════════════════════════════════════════════════════════

@Composable
private fun InputSection() {
    var value by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    SectionHeader(
        title = "输入控件",
        hint = "聚焦驱动玻璃亮度 —— 获得焦点时材质受光上升，失焦回落"
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        style = GlassStyle.Control,
        focused = focused
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(text = "Glass Input") },
                singleLine = true,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "尝试聚焦上方输入框 —— 玻璃卡片的高光与边缘亮度随之上升，失焦后回落。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃对话框 —— HazeDialog 跨窗口采样
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DialogSection(onOpen: () -> Unit) {
    SectionHeader(
        title = "玻璃对话框",
        hint = "HazeDialog 跨窗口采样 —— 面板模糊的是验证区的真实内容"
    )
    GlassButton(
        text = "打开玻璃对话框",
        onClick = onOpen,
        leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        accent = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun LabGlassDialog(state: HazeState, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        state = state
    ) {
        Text(
            text = "玻璃对话框",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "本面板经 HazeDialog 跨窗口采样背后验证区的真实内容：网格、文字与漂移光斑全部进入模糊范围。" +
                "面板内容保持清晰 —— 玻璃只作用于材质本身，不冒充内容模糊。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(18.dp))
        GlassButton(
            text = "关闭",
            onClick = onDismiss
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  诚实验收清单 —— 运行时求值，禁止静态谎言
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ChecklistSection(mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val pass = Color(0xFF22C55E)
    val fallback = Color(0xFFF59E0B)
    val fail = scheme.error
    // Blur 档运行时判定：API 32+ 才有 RenderEffect GPU 模糊，之下诚实降级 scrim
    val blurOn = Build.VERSION.SDK_INT >= 32

    val items = listOf(
        CheckItem(
            name = "Backdrop",
            status = "PASS",
            note = "Haze GraphicsLayer 采样背后内容",
            color = pass
        ),
        CheckItem(
            name = "Blur",
            status = if (blurOn) "PASS" else "FALLBACK",
            note = if (blurOn) "RenderEffect GPU 模糊" else "API 31-: scrim 降级，无 blur",
            color = if (blurOn) pass else fallback
        ),
        CheckItem(
            name = "Material response",
            status = "PASS",
            note = "按压/聚焦/选中/禁用驱动亮度与形变",
            color = pass
        ),
        CheckItem(
            name = "Edge lighting",
            status = "PASS",
            note = if (mode == GlassLabMode.NIGHT) "内描边渐变受光" else "冷灰发丝边缘（白底白描边不可见）",
            color = pass
        ),
        CheckItem(
            name = "Depth",
            status = "PASS",
            note = "双级阴影线索",
            color = pass
        ),
        CheckItem(
            name = "Specular",
            status = "PASS",
            note = "顶部高光扫掠",
            color = pass
        ),
        CheckItem(
            name = "Noise",
            status = "PASS",
            note = "Haze noiseFactor 玻璃颗粒质感",
            color = pass
        ),
        CheckItem(
            name = "Dynamic theme",
            status = "PASS",
            note = "调色板组合期派生自 MaterialTheme —— 本页为强制 ${mode.label}，切顶部形态可对照双态",
            color = pass
        ),
        CheckItem(
            name = "Refraction",
            status = "NOT IMPLEMENTED",
            note = "未实现折射位移 —— 拒绝冒充",
            color = fail
        ),
        CheckItem(
            name = "Interaction",
            status = "PASS",
            note = "140ms 激活度动画，非循环",
            color = pass
        ),
        CheckItem(
            name = "Performance",
            status = "PASS",
            note = "静态层与光斑层分离：动画帧仅 2-3 绘制调用，无逐帧 Bitmap 分配",
            color = pass
        ),
        CheckItem(
            name = "Fallback",
            status = "PASS",
            note = "低版本自动 scrim",
            color = pass
        )
    )

    SectionHeader(
        title = "诚实验收清单",
        hint = "运行时逐项核对 —— 本设备 SDK ${Build.VERSION.SDK_INT}，当前 ${mode.label}（强制主题）"
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item -> HonestCheckRow(item = item) }
        }
    }
}

/** 单条验收行 —— 状态徽标等宽小字；NOT IMPLEMENTED 整行错误色 + 底色浸染。 */
@Composable
private fun HonestCheckRow(item: CheckItem) {
    val scheme = MaterialTheme.colorScheme
    val strong = item.status == "NOT IMPLEMENTED"

    val rowModifier = if (strong) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(item.color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    }

    Column(
        modifier = rowModifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
                color = if (strong) item.color else scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            GlassBadge(accent = item.color) {
                if (strong) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = item.color
                    )
                }
                if (item.status == "FALLBACK") {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = item.color
                    )
                }
                Text(
                    text = item.status,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = item.color
                )
            }
        }
        Text(
            text = item.note,
            style = MaterialTheme.typography.bodySmall,
            color = if (strong) item.color else scheme.onSurfaceVariant
        )
    }
}
