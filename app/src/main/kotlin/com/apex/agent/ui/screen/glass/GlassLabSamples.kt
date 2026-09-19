package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.glassClickable
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════
 *  玻璃实验室 · 样品陈列馆（Sample Gallery）—— 液态玻璃材质标本
 * ═══════════════════════════════════════════════════════════════
 *
 * 定位：玻璃实验室的「样品陈列」层，与 [GlassLabSections] 的共享小节并列
 * —— **只存在于玻璃实验室（开发验收页），不进入任何用户流程**。正式 UI 的
 * 玻璃体系（ui/glass 三件套）一行不动，样品均为独立配方，验收满意后再提炼。
 *
 * 陈列内容（每件样品 = 标本卡：可交互玻璃样品 + 等宽参数标签）：
 *  - 标本 01 材质配方：水晶清透/磨砂乳白/主色浸染/深空墨黑/薄雾轻纱/双层夹胶；
 *  - 标本 02 边缘光变：同一 Frosted 基材 × 无边缘/亮线/渐变描边/外发光；
 *  - 标本 03 反射条纹 / 04 按压形变 / 05 采样真伪对照（详见各小节注释）。
 *
 * 技术约定：haze 用法照抄 GlassSurface（hazeEffect(state, style) + HazeStyle），
 * hazeSource 只挂在「样品抽屉」容器上，玻璃样品永远是它的同层兄弟节点（嵌进源
 * 子树会被 zIndex 递归保护排除）；两个抽屉与 BackdropZone **共用同一 HazeState**
 * —— 各区窗口坐标互不重叠，haze 逐区按重叠绘制互不串扰；全部样品只用静态绘制
 * 或单次触发动画，零循环动画（实验室光斑除外）；夜间/白天各自强制主题。
 */

// ═══ 陈列尺寸常量 ═══

private val ShelfHeight = 272.dp          // 配方抽屉：两行标本卡 + 内边距
private val SpecChipHeight = 54.dp        // 单片标本玻璃（载玻片比例）
private val LaminatedBoxHeight = 52.dp    // 双层夹胶错位容器
private val CompareZoneHeight = 192.dp    // 采样真伪对照区
private val CompareChipHeight = 96.dp     // 真伪对照样品高度
private val MilkWhite = Color(0xFFF4EFE6) // 乳白配方基色（磨砂乳白/真伪对照共用）

/** 标本参数标签的等宽小字（实验室标签风格） */
private val SpecimenTagMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp)

// ═══════════════════════════════════════════════════════════════
//  样品数据类型
// ═══════════════════════════════════════════════════════════════

/** 玻璃边缘处理 —— 边缘研究 + 各配方共用。 */
private sealed interface EdgeStyle {
    /** 无边缘：轮廓只靠材质与背景的明度差 */
    data object None : EdgeStyle

    /** 均匀亮线：最经典的 1dp 玻璃边 */
    data class Hairline(val color: Color, val width: Dp = 1.dp) : EdgeStyle

    /** 渐变描边：上亮下暗 —— 顶光下的玻璃断面 */
    data class Gradient(val top: Color, val bottom: Color, val width: Dp = 1.5.dp) : EdgeStyle

    /** 外发光：外层 halo（借彩色阴影）+ 内缘亮线 */
    data class Glow(val color: Color, val width: Dp = 1.dp) : EdgeStyle
}

/** 15° 倾斜反射条纹参数。 */
private data class StreakStyle(val degrees: Float = 15f, val color: Color)

/**
 * 单件玻璃样品的完整配方。tint 已含 alpha；state 传 null 即 Frosted 档
 * （诚实降级为主题色薄霜），传 HazeState 即 Backdrop 档（实时采样）。
 */
private data class GlassSpec(
    val name: String,
    val code: String = "",
    val blur: Dp = 14.dp,
    val tint: Color,
    val tintTop: Color = Color.Unspecified,     // Frosted 顶部提亮；缺省由 tint 派生
    val background: Color = Color.Unspecified,  // haze 背景兜底（Backdrop 档必填）
    val fallback: Color = Color.Unspecified,    // 低 API 无 blur 时的 scrim
    val noise: Float = 0.05f,
    val edge: EdgeStyle = EdgeStyle.None,
    val sheen: Float = 0f,                      // 顶部内高光强度 0..1
    val streak: StreakStyle? = null,
    val laminated: Boolean = false              // 双层夹胶：由 LaminatedUnit 渲染
)

// ═══════════════════════════════════════════════════════════════
//  小节入口 —— GlassLabContent 两个模式各挂载一份
// ═══════════════════════════════════════════════════════════════

/** 样品陈列馆主体：全部样品跟随当前强制主题（夜间霓虹 / 白天粉彩）。 */
@Composable
internal fun SamplesSection(state: HazeState, mode: GlassLabMode) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "样品陈列馆 · Sample Gallery",
            hint = "液态玻璃材质标本陈列 —— 只在玻璃实验室验收，不进入任何正式界面；满意后再提炼为正式组件"
        )
        RecipeShelf(state = state, mode = mode)
        EdgeStudySection(mode = mode)
        StreakStudySection(mode = mode)
        PressMorphSection(mode = mode)
        LiveVsStaticSection(state = state, mode = mode)
    }
}

/** 子研究标题 —— 比小节标题低一级（等宽中字 + 次级提示）。 */
@Composable
private fun StudyHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = title, style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
        )
        Text(
            text = hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  标本 01 · 材质配方抽屉 —— 六种配方实时采样陈列
// ═══════════════════════════════════════════════════════════════

/**
 * 配方标本抽屉：底衬为第二采样区（与 BackdropZone 共用 HazeState），六种
 * 配方的玻璃片作为同层兄弟节点悬浮其上，逐片实时采样，可按压感受材质。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeShelf(state: HazeState, mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val specs = recipeSpecs(night = mode == GlassLabMode.NIGHT, scheme = scheme)

    StudyHeader(
        title = "标本 01 · 材质配方",
        hint = "六种配方 × 实时采样 —— 抽屉底衬为第二采样区（与 BackdropZone 共用 HazeState），按压标本感受材质"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth().height(ShelfHeight)
            .clip(GlassShapes.card)
            .border(width = 1.dp, color = scheme.outlineVariant.copy(alpha = 0.5f), shape = GlassShapes.card)
    ) {
        // haze 源：标本抽屉底衬（静态 Canvas，尺寸/主题变化才重绘）
        Box(modifier = Modifier.fillMaxSize().hazeSource(state)) {
            SpecimenLinerCanvas(modifier = Modifier.fillMaxSize(), mode = mode, dense = false)
        }
        // 标本陈列层：源的同层兄弟节点（悬浮其上），逐片实时采样
        FlowRow(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 3
        ) {
            specs.forEach { spec ->
                if (spec.laminated) LaminatedUnit(state = state, mode = mode, modifier = Modifier.weight(1f))
                else SpecimenUnit(spec = spec, state = state, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 六种配方 —— 颜色组合期从当前主题解析，夜间/白天各自调参。 */
private fun recipeSpecs(night: Boolean, scheme: ColorScheme): List<GlassSpec> {
    val bg = scheme.background
    val scrim = scheme.surfaceContainerHigh
    return listOf(
        // 1. 水晶清透：低 tint 高透明 + 1dp 亮边 + 微弱内高光
        GlassSpec(
            name = "水晶清透", code = "CRYSTAL", blur = 8.dp, noise = 0.03f, sheen = 0.10f,
            tint = Color.White.copy(alpha = if (night) 0.08f else 0.12f),
            background = bg, fallback = scrim.copy(alpha = 0.30f),
            edge = edgeHairline(night, scheme, 0.45f, 0.28f)
        ),
        // 2. 磨砂乳白：高模糊 + 乳白 tint + 柔边
        GlassSpec(
            name = "磨砂乳白", code = "FROSTED_MILK", blur = 26.dp, noise = 0.12f, sheen = 0.14f,
            tint = MilkWhite.copy(alpha = if (night) 0.34f else 0.58f),
            background = bg, fallback = scrim.copy(alpha = 0.62f),
            edge = EdgeStyle.Gradient(
                top = if (night) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.50f),
                bottom = if (night) MilkWhite.copy(alpha = 0.06f) else scheme.onSurface.copy(alpha = 0.10f)
            )
        ),
        // 3. 主色浸染：primary 低比例浸染 —— 白天低饱和粉彩，夜间霓虹感
        GlassSpec(
            name = "主色浸染", code = "TINTED", blur = 14.dp, noise = 0.08f, sheen = 0.08f,
            tint = scheme.primary.copy(alpha = if (night) 0.24f else 0.15f),
            background = bg, fallback = scrim.copy(alpha = 0.45f),
            edge = EdgeStyle.Hairline(color = scheme.primary.copy(alpha = if (night) 0.35f else 0.30f))
        ),
        // 4. 深空墨黑：夜间专属 —— 近黑 tint + 顶缘高光（白天显示深板岩变体）
        GlassSpec(
            name = "深空墨黑", code = "OBSIDIAN", blur = 20.dp, noise = 0.05f, sheen = 0.18f,
            tint = if (night) Color(0xFF05070D).copy(alpha = 0.78f) else Color(0xFF232A38).copy(alpha = 0.55f),
            background = bg, fallback = scrim.copy(alpha = 0.80f),
            edge = EdgeStyle.Gradient(top = Color.White.copy(alpha = 0.55f), bottom = Color.White.copy(alpha = 0.08f))
        ),
        // 5. 薄雾轻纱：极低模糊 + 极淡 tint —— 「克制」的极限样本
        GlassSpec(
            name = "薄雾轻纱", code = "VEIL", blur = 3.dp, noise = 0.02f, sheen = 0.04f,
            tint = Color.White.copy(alpha = if (night) 0.05f else 0.09f),
            background = bg, fallback = scrim.copy(alpha = 0.20f),
            edge = edgeHairline(night, scheme, 0.12f, 0.10f)
        ),
        // 6. 双层夹胶：两片错位叠放（LaminatedUnit 专属渲染）
        GlassSpec(
            name = "双层夹胶", code = "LAMINATED", blur = 18.dp, laminated = true,
            tint = Color.White.copy(alpha = if (night) 0.16f else 0.20f),
            background = bg, fallback = scrim.copy(alpha = 0.40f)
        )
    )
}

/** 常用边缘：夜间白亮线 / 白天冷灰亮线（透明度分档，由调用方定）。 */
private fun edgeHairline(night: Boolean, scheme: ColorScheme, nightAlpha: Float, dayAlpha: Float): EdgeStyle {
    val c = if (night) Color.White.copy(alpha = nightAlpha) else scheme.onSurface.copy(alpha = dayAlpha)
    return EdgeStyle.Hairline(color = c)
}

/** 单件标本卡：玻璃片 + 悬挂式参数标签（实验室标本卡样式）。 */
@Composable
private fun SpecimenUnit(spec: GlassSpec, state: HazeState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SpecimenGlass(
            spec = spec, state = state,
            modifier = Modifier.fillMaxWidth().height(SpecChipHeight)
        ) { SpecimenCodeLabel(code = spec.code) }
        SpecimenTag(name = spec.name, lines = spec.paramLines())
    }
}

/** 双层夹胶标本：后片（左上）+ 前片（右下）错位叠放，展示深度层次。 */
@Composable
private fun LaminatedUnit(state: HazeState, mode: GlassLabMode, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    // 后片：轻模糊薄玻璃；前片：重模糊厚玻璃 —— 错位叠出前后景深
    val backSpec = GlassSpec(
        name = "", code = "L1", blur = 10.dp, noise = 0.03f, tint = Color.White.copy(alpha = 0.10f),
        background = scheme.background, fallback = scheme.surfaceContainerHigh.copy(alpha = 0.35f),
        edge = EdgeStyle.Hairline(color = Color.White.copy(alpha = 0.22f))
    )
    val frontSpec = GlassSpec(
        name = "", code = "L2", blur = 18.dp, noise = 0.08f, sheen = 0.12f,
        tint = Color.White.copy(alpha = if (night) 0.16f else 0.20f),
        background = scheme.background, fallback = scheme.surfaceContainerHigh.copy(alpha = 0.45f),
        edge = EdgeStyle.Gradient(top = Color.White.copy(alpha = 0.30f), bottom = Color.White.copy(alpha = 0.08f))
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(LaminatedBoxHeight)) {
            SpecimenGlass(
                spec = backSpec, state = state, cornerRadius = 10.dp,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.80f).height(40.dp)
            ) { SpecimenCodeLabel(code = "L1") }
            SpecimenGlass(
                spec = frontSpec, state = state, cornerRadius = 10.dp,
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.80f).height(40.dp)
            ) { SpecimenCodeLabel(code = "L2") }
        }
        SpecimenTag(name = "双层夹胶", lines = listOf("L1 b=10 α0.10", "L2 b=18 α0.16", "错位·双层深度"))
    }
}

/** 玻璃片内的等宽代号小字（内容画在材质之上，保持清晰）。 */
@Composable
private fun SpecimenCodeLabel(code: String) {
    Text(
        text = code, style = SpecimenTagMono, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    )
}

/** 悬挂式标本标签：近似不透明底板，保证在花底衬上依旧可读。 */
@Composable
private fun SpecimenTag(name: String, lines: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = name, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
            fontWeight = FontWeight.Medium, color = scheme.onSurface
        )
        lines.forEach { line ->
            Text(text = line, style = SpecimenTagMono, color = scheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  标本 02 · 边缘光变研究 —— 同一基材 × 四种边缘
// ═══════════════════════════════════════════════════════════════

/**
 * 边缘研究：四片完全相同的 Frosted 基材，只换边缘处理 —— 验证玻璃
 * 「厚度感」主要来自边缘受光，而非模糊本身。
 */
@Composable
private fun EdgeStudySection(mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    StudyHeader(
        title = "标本 02 · 边缘光变研究",
        hint = "同一 Frosted 基材 × 四种边缘处理 —— 厚度感主要来自边缘受光，而非模糊本身"
    )
    // 控制变量：四片共用同一基材配方
    val frost = GlassSpec(
        name = "", blur = 14.dp, noise = 0.08f, sheen = 0.08f,
        tint = if (night) scheme.surfaceContainerHigh.copy(alpha = 0.55f)
        else scheme.surfaceVariant.copy(alpha = 0.78f),
        tintTop = if (night) scheme.surfaceContainerHighest.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.50f)
    )
    val edges: List<Pair<String, EdgeStyle>> = listOf(
        "无边缘" to EdgeStyle.None,
        "亮线 1dp" to edgeHairline(night, scheme, 0.50f, 0.32f),
        "渐变描边" to EdgeStyle.Gradient(
            top = if (night) Color.White.copy(alpha = 0.60f) else scheme.onSurface.copy(alpha = 0.40f),
            bottom = if (night) Color.White.copy(alpha = 0.08f) else scheme.onSurface.copy(alpha = 0.06f)
        ),
        "外发光" to EdgeStyle.Glow(color = scheme.primary.copy(alpha = if (night) 0.45f else 0.40f))
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        edges.forEach { (label, edge) ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SpecimenGlass(
                    spec = frost.copy(edge = edge), state = null, cornerRadius = 12.dp,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                )
                SpecimenTag(name = label, lines = edgeTagLines(edge))
            }
        }
    }
}

// 边缘研究的标签行 —— 简短等宽，窄列不换行
private fun edgeTagLines(edge: EdgeStyle): List<String> = when (edge) {
    EdgeStyle.None -> listOf("edge=none", "轮廓=明度差")
    is EdgeStyle.Hairline -> listOf("edge=${edge.width.value.toInt()}dp", "亮线 α${"%.2f".format(edge.color.alpha)}")
    is EdgeStyle.Gradient -> listOf("edge=grad", "上亮下暗")
    is EdgeStyle.Glow -> listOf("edge=glow", "halo=primary")
}

// ═══════════════════════════════════════════════════════════════
//  标本 03 · 表面反射条纹 —— 静态 15° 高光带
// ═══════════════════════════════════════════════════════════════

/**
 * 反射条纹研究：左 = 现有顶缘竖向扫掠；右 = 15° 倾斜柔和高光带提案
 * （Canvas 渐变，静态绘制、非循环动画 —— 省电原则）。
 */
@Composable
private fun StreakStudySection(mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    StudyHeader(
        title = "标本 03 · 表面反射条纹",
        hint = "静态 15° 倾斜高光带（Canvas 渐变 · 无循环动画）—— 与现有顶缘扫掠对照"
    )
    val base = GlassSpec(
        name = "", blur = 14.dp, noise = 0.10f,
        tint = MilkWhite.copy(alpha = if (night) 0.30f else 0.52f),
        tintTop = Color.White.copy(alpha = if (night) 0.18f else 0.42f),
        edge = edgeHairline(night, scheme, 0.30f, 0.22f)
    )
    val slabs = listOf(
        Triple("顶缘扫掠 · 现状", base.copy(sheen = 0.22f), listOf("sheen=0.22", "竖向 0→45%")),
        Triple(
            "15° 条纹 · 提案",
            base.copy(
                streak = StreakStyle(degrees = 15f, color = Color.White.copy(alpha = if (night) 0.32f else 0.62f))
            ),
            listOf("streak=15°", "白带 α" + if (night) "0.32" else "0.62")
        )
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        slabs.forEach { (name, slabSpec, lines) ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SpecimenGlass(spec = slabSpec, state = null, modifier = Modifier.fillMaxWidth().height(76.dp))
                SpecimenTag(name = name, lines = lines)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  标本 04 · 按压形变反馈 —— 果冻感验证
// ═══════════════════════════════════════════════════════════════

/**
 * 按压形变：按住时 scale 收缩到 0.96 + 顶部高光带随按压下移（受光源被
 * 「压低」）；松开后弹簧回弹 —— 单次触发动画（进入/回弹各一次）。
 */
@Composable
private fun PressMorphSection(mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    StudyHeader(
        title = "标本 04 · 按压形变反馈",
        hint = "按住：scale 0.96 + 高光带下移；松开：弹簧回弹 —— 液态玻璃的果冻感验证"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 单次触发弹簧动画：按压进入、松开回弹各一次，非循环
    val morph by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "press_morph"
    )
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    val scale = 1f - 0.04f * morph
    val sheenBase = if (night) 0.22f else 0.34f
    val tint = if (night) scheme.surfaceContainerHigh.copy(alpha = 0.60f) else scheme.surfaceVariant.copy(alpha = 0.80f)
    val lift = if (night) scheme.surfaceContainerHighest.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.55f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(
                    Brush.verticalGradient(listOf(lift, tint), startY = 0f, endY = Float.POSITIVE_INFINITY),
                    shape = shape
                )
                .drawBehind {
                    val corner = CornerRadius(size.height / 2f)
                    drawSpecimenEdge(
                        corner = corner,
                        edge = EdgeStyle.Gradient(
                            top = if (night) Color.White.copy(alpha = 0.40f) else scheme.onSurface.copy(alpha = 0.30f),
                            bottom = if (night) Color.White.copy(alpha = 0.08f)
                            else scheme.onSurface.copy(alpha = 0.06f)
                        ),
                        boost = 1f + morph * 0.7f
                    )
                    // 高光位置随按压下移 —— 松开弹回原位
                    drawTopSheen(
                        color = Color.White.copy(alpha = (sheenBase * (1f + morph * 0.6f)).coerceAtMost(0.6f)),
                        corner = corner, shiftPx = morph * 8.dp.toPx()
                    )
                }
                .glassClickable(interaction) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "按住我 · 果冻回弹", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium, color = scheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                // 实时回显当前缩放 —— 动画期间连续变化，回弹归 1.00
                Text(
                    text = "×%.2f".format(scale), style = SpecimenTagMono.copy(fontSize = 10.sp),
                    color = scheme.primary
                )
            }
        }
        SpecimenTag(
            name = "按压形变 · PRESS MORPH",
            lines = listOf("scale 1.00→0.96 · sheen +8dp", "spring bouncy · 单次触发")
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  标本 05 · 采样真伪对照 —— 真玻璃 vs 假色块
// ═══════════════════════════════════════════════════════════════

/**
 * 真伪对照：左 = haze 实时采样玻璃片（可拖动、双击复位）；右 = 同 tint 参数
 * 静态半透明色块 —— 真玻璃把网格糊成柔边漫射、色斑洇开；假色块下网格依旧锐。
 */
@Composable
private fun LiveVsStaticSection(state: HazeState, mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    var zoneSize by remember { mutableStateOf(IntSize.Zero) }

    StudyHeader(
        title = "标本 05 · 采样真伪对照",
        hint = "左 = haze 实时采样（可拖动 · 双击复位）｜右 = 同参数静态色块 —— 看网格：真玻璃糊，假玻璃锐"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth().height(CompareZoneHeight)
            .clip(GlassShapes.card)
            .border(width = 1.dp, color = scheme.outlineVariant.copy(alpha = 0.5f), shape = GlassShapes.card)
            .onSizeChanged { coordinates -> zoneSize = coordinates }
    ) {
        // haze 源：对照底衬（密集网格 + 文字 + 色斑，让差异一眼可见）
        Box(modifier = Modifier.fillMaxSize().hazeSource(state)) {
            SpecimenLinerCanvas(modifier = Modifier.fillMaxSize(), mode = mode, dense = true)
        }

        // 右：静态假玻璃 —— 同 tint 纯色遮罩，无采样无模糊
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd).padding(12.dp)
                .fillMaxWidth(0.40f).height(CompareChipHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(MilkWhite.copy(alpha = if (mode == GlassLabMode.NIGHT) 0.34f else 0.58f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "假 · 静态色块", style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
                Text(text = "blur=0 · 纯遮罩", style = SpecimenTagMono, color = scheme.onSurfaceVariant)
            }
        }

        // 中：VS 分隔
        Column(
            modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.width(1.dp).height(22.dp).background(scheme.outlineVariant))
            Text(
                text = "VS", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = scheme.primary
            )
            Box(modifier = Modifier.width(1.dp).height(22.dp).background(scheme.outlineVariant))
        }

        // 底部判读标签
        Text(
            text = "判读：真玻璃把网格糊成漫射 · 假玻璃下网格依旧锐利",
            style = SpecimenTagMono, color = scheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(6.dp)).background(scheme.surfaceContainer.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )

        // 左：真玻璃探针（最后绘制压在最上层；可拖动采样）
        DraggableLiveChip(state = state, mode = mode, zoneSize = zoneSize)
    }
}

/**
 * 可拖动真玻璃探针 —— 复刻 DraggableGlassChip 交互：拖动累积位移并钳制在
 * 对照区内，双击复位；拖过网格/色斑时玻璃内部采样内容必须实时变化。
 */
@Composable
private fun DraggableLiveChip(state: HazeState, mode: GlassLabMode, zoneSize: IntSize) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // REVIEW-R2：zoneSize 以参数传入，pointerInput(Unit) 闭包会冻结首帧值
    //（首帧 IntSize.Zero → 探针被钳死在原点拖不动）。包 state 让拖动闭包读到最新尺寸。
    val zoneSizeState = androidx.compose.runtime.rememberUpdatedState(zoneSize)
    val spec = GlassSpec(
        name = "", blur = 18.dp, noise = 0.10f, sheen = 0.12f,
        tint = MilkWhite.copy(alpha = if (night) 0.34f else 0.58f),
        background = scheme.background,
        fallback = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        edge = EdgeStyle.Gradient(
            top = if (night) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.55f),
            bottom = if (night) Color.White.copy(alpha = 0.06f) else scheme.onSurface.copy(alpha = 0.10f)
        )
    )
    SpecimenGlass(
        spec = spec, state = state,
        modifier = Modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .fillMaxWidth(0.40f)
            .height(CompareChipHeight)
            .pointerInput(Unit) {
                // 双击复位 —— 拖丢后一键回原点
                detectTapGestures(onDoubleTap = { dragOffset = Offset.Zero })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 钳制在对照区内（片宽 = 区宽 40%）；尺寸经 state 读最新值（R2）。
                    val zone = zoneSizeState.value
                    val maxX = zone.width * 0.60f
                    val maxY = (zone.height - CompareChipHeight.roundToPx()).coerceAtLeast(0).toFloat()
                    dragOffset = Offset(
                        (dragOffset.x + dragAmount.x).coerceIn(0f, maxX),
                        (dragOffset.y + dragAmount.y).coerceIn(0f, maxY)
                    )
                }
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "真 · 实时采样", style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            Text(text = "拖我 · 双击复位", style = SpecimenTagMono, color = scheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  标本抽屉底衬 —— haze 源内容（静态 Canvas）
// ═══════════════════════════════════════════════════════════════

/**
 * 底衬画布：渐变底 + 细网格 + 色斑圆 + 等宽文字行 —— 全部静态，仅尺寸/主题
 * 变化时重绘。密集模式（dense）用于真伪对照区：色斑更大更多，差异一眼可见。
 */
@Composable
private fun SpecimenLinerCanvas(modifier: Modifier, mode: GlassLabMode, dense: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val night = mode == GlassLabMode.NIGHT
    val textMeasurer = rememberTextMeasurer()
    val rowStyle = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        color = scheme.onSurface.copy(alpha = if (night) 0.55f else 0.42f)
    )
    val rowText = if (dense) "LIVE vs STATIC GRID 0123456789" else "SPECIMEN LINER 0123456789"
    // 预排版一次，绘制期零分配 —— 与 StaticBackdropCanvas 同一手法
    val rowLayout = remember(rowStyle, rowText) { textMeasurer.measure(text = rowText, style = rowStyle) }

    Canvas(modifier = modifier) {
        // 1. 底渐变：夜间沉暗 / 白天透亮（三段天空渐变）
        drawRect(
            Brush.verticalGradient(
                if (night) listOf(scheme.surfaceContainerLow, scheme.background)
                else listOf(Color.White, scheme.background, scheme.primaryContainer.copy(alpha = 0.30f)),
                startY = 0f, endY = size.height
            )
        )
        // 2. 细网格 —— 模糊真伪的照妖镜
        val cell = 16.dp.toPx()
        val gridColor = scheme.onSurfaceVariant.copy(alpha = if (night) 0.30f else 0.20f)
        var gx = 0f
        while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 0.8f); gx += cell }
        var gy = 0f
        while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 0.8f); gy += cell }
        // 3. 色斑圆 —— 玻璃浸染/透色的采样素材（拖动真玻璃探针扫过即可见）
        val swatchAlpha = if (night) 0.55f else 0.45f
        val w = size.width
        val h = size.height
        if (dense) {
            drawCircle(scheme.primary.copy(alpha = swatchAlpha), 52.dp.toPx(), Offset(w * 0.22f, h * 0.32f))
            drawCircle(scheme.secondary.copy(alpha = swatchAlpha), 52.dp.toPx(), Offset(w * 0.50f, h * 0.74f))
            drawCircle(scheme.tertiary.copy(alpha = swatchAlpha), 52.dp.toPx(), Offset(w * 0.78f, h * 0.36f))
        } else {
            drawCircle(scheme.primary.copy(alpha = swatchAlpha), 40.dp.toPx(), Offset(w * 0.28f, h * 0.36f))
            drawCircle(scheme.tertiary.copy(alpha = swatchAlpha), 40.dp.toPx(), Offset(w * 0.74f, h * 0.62f))
        }
        // 4. 等宽文字行 —— 交错缩进（密集模式铺满，抽屉模式点缀三行）
        val step = 20.dp.toPx()
        var ty = 12.dp.toPx()
        var index = 0
        while (ty < size.height) {
            drawText(rowLayout, topLeft = Offset(if (index % 2 == 0) 10.dp.toPx() else 56.dp.toPx(), ty))
            ty += step
            index += 1
            if (!dense && index >= 3) break
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SpecimenGlass —— 标本玻璃统一渲染核心
// ═══════════════════════════════════════════════════════════════

/**
 * 标本玻璃：材质层（Backdrop 采样 / Frosted 薄霜）+ 边缘光 + 顶缘内高光 +
 * 可选 15° 条纹，全部单次绘制叠加。渲染顺序照抄 GlassSurface：阴影（外
 * 发光档）→ 按压缩放 → 裁剪 → 材质 → 叠加层 → 点击。
 */
@Composable
private fun SpecimenGlass(
    spec: GlassSpec,
    state: HazeState?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    content: @Composable () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 单次触发动画：按压进入 / 松开回弹（tween 140ms，与 GlassSurface 对齐）
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "specimen_press"
    )
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(cornerRadius)

    // 材质层：state 非空 = Backdrop 档（hazeEffect 实时采样，用法与
    // GlassSurface 完全一致）；为空 = Frosted 档（主题色薄霜，诚实降级）
    val material = if (state != null) {
        val bg = if (spec.background.isSpecified) spec.background else scheme.background
        val fb = if (spec.fallback.isSpecified) spec.fallback else scheme.surfaceContainerHigh.copy(alpha = 0.55f)
        Modifier.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = bg,
                tints = listOf(HazeTint(spec.tint)),
                blurRadius = spec.blur,
                noiseFactor = spec.noise,
                fallbackTint = HazeTint(fb)
            )
        )
    } else {
        val topLift = if (spec.tintTop.isSpecified) spec.tintTop else spec.tint.copy(alpha = spec.tint.alpha * 0.6f)
        Modifier.background(
            Brush.verticalGradient(listOf(topLift, spec.tint), startY = 0f, endY = Float.POSITIVE_INFINITY),
            shape = shape
        )
    }

    // 外发光档：借彩色阴影做 halo（画在裁剪外，最外层）
    val glow = spec.edge as? EdgeStyle.Glow
    Box(
        modifier = modifier
            .then(
                if (glow == null) Modifier
                else Modifier.shadow(
                    elevation = 8.dp, shape = shape, clip = false,
                    ambientColor = glow.color, spotColor = glow.color
                )
            )
            // 按压缩放：玻璃的「形变」反馈（单次动画值直接映射）
            .graphicsLayer { val s = 1f - 0.03f * press; scaleX = s; scaleY = s }
            .clip(shape)
            .then(material)
            // 边缘光 / 顶缘内高光 / 15° 条纹 —— 画在材质之上、内容之下
            .drawBehind {
                val corner = CornerRadius(cornerRadius.toPx())
                drawSpecimenEdge(corner = corner, edge = spec.edge, boost = 1f + press * 0.6f)
                if (spec.sheen > 0.01f) {
                    drawTopSheen(
                        color = Color.White.copy(alpha = (spec.sheen * (1f + press * 0.8f)).coerceAtMost(0.5f)),
                        corner = corner
                    )
                }
                spec.streak?.let { drawSpecularStreak(color = it.color, degrees = it.degrees) }
            }
            // 无涟漪点击：按压反馈即材质语言（触觉 + 形变 + 边缘受光）
            .glassClickable(interaction) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════════
//  绘制助手 —— 全部单次绘制，无额外图层分配
// ═══════════════════════════════════════════════════════════════

/** 边缘绘制：boost 为按压/激活增亮系数（受光即提边）。 */
private fun DrawScope.drawSpecimenEdge(corner: CornerRadius, edge: EdgeStyle, boost: Float) {
    when (edge) {
        EdgeStyle.None -> {}
        is EdgeStyle.Hairline -> drawRoundRect(
            color = edge.color.boostAlpha(boost),
            cornerRadius = corner,
            style = Stroke(width = edge.width.toPx())
        )
        is EdgeStyle.Gradient -> drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(edge.top.boostAlpha(boost), edge.bottom), startY = 0f, endY = size.height
            ),
            cornerRadius = corner,
            style = Stroke(width = edge.width.toPx())
        )
        // 外发光档的内缘亮线（halo 由外层彩色阴影承担）
        is EdgeStyle.Glow -> drawRoundRect(
            color = edge.color.boostAlpha(boost),
            cornerRadius = corner,
            style = Stroke(width = edge.width.toPx())
        )
    }
}

/** 顶缘内高光：上半区受光渐隐；shiftPx 让高光带整体下移（按压形变用）。 */
private fun DrawScope.drawTopSheen(color: Color, corner: CornerRadius, shiftPx: Float = 0f) {
    if (color.alpha <= 0.01f) return
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(color, Color.Transparent), startY = shiftPx, endY = size.height * 0.5f + shiftPx
        ),
        cornerRadius = corner
    )
}

/** 15° 倾斜反射条纹：柔和高光带斜穿样品 —— 静态绘制，零循环动画。 */
private fun DrawScope.drawSpecularStreak(color: Color, degrees: Float) {
    if (color.alpha <= 0.01f) return
    val band = size.width * 0.55f
    val centerX = size.width / 2f
    rotate(degrees = degrees, pivot = Offset(centerX, size.height / 2f)) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, color, Color.Transparent),
                startX = centerX - band / 2f,
                endX = centerX + band / 2f
            ),
            topLeft = Offset(centerX - band / 2f, -size.height),
            size = Size(band, size.height * 3f)
        )
    }
}

/** 透明度增亮（按压受光）。 */
private fun Color.boostAlpha(boost: Float): Color = copy(alpha = (alpha * boost).coerceAtMost(1f))

/** 基色十六进制（不含 alpha —— alpha 由标签单独标注）。 */
private fun Color.hexCode(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** 配方参数标签行（等宽小字，实验室标签风格）。 */
private fun GlassSpec.paramLines(): List<String> = listOf(
    "blur=${blur.value.toInt()}dp",
    "tint=${tint.hexCode()}",
    "α${"%.2f".format(tint.alpha)} ${edge.tag()}"
)

/** 边缘的简短标签（窄列防换行）。 */
private fun EdgeStyle.tag(): String = when (this) {
    EdgeStyle.None -> "e=0"
    is EdgeStyle.Hairline -> "e=${width.value.toInt()}dp"
    is EdgeStyle.Gradient -> "e=grad"
    is EdgeStyle.Glow -> "e=glow"
}
