package com.apex.agent.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.skydoves.cloudy.cloudy
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════
 *  CloudyFrost —— Cloudy 真模糊霜面材质（v7）
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户痛点：agent 回复气泡白天模式「一片死白」—— Frosted 档的垂直渐变
 * 假霜面平而实，没有磨砂玻璃的光影纵深。气泡位于 hazeSource（消息列表）
 * 子树内，Haze 1.4 不支持嵌套采样，Backdrop 档永远拿不到真模糊 ——
 * 本文件引入 Cloudy 自体模糊补上这块能力。
 *
 * 语义（诚实声明）：Cloudy 模糊的是**材质层自身**（自绘光带纹理），
 * 不是背后内容（backdrop）—— 玻璃下的列表内容经半透明霜面「透出」
 * 而非「被采样」。这与 Haze Backdrop 档是两套互补能力：
 *  - Haze Backdrop：悬浮组件（输入栏/终端尾窗/抽屉导航）真实采样背后 UI；
 *  - Cloudy Frosted：列表内嵌组件（聊天气泡）的材质自体真模糊。
 *
 * 集成纪律：`com.skydoves.cloudy` 的 import 只允许出现在本文件
 * （ui/glass 玻璃系统单点集成；换库只改这里，业务层零感知）。
 *
 * Cloudy 0.2.3 实现核实（源码级）：
 *  - API：`Modifier.cloudy(radius: Int)`（像素半径，1..25 单趟、
 *    超出自动多趟迭代）；@Composable 修饰符工厂，默认 rememberGraphicsLayer；
 *  - 渲染路径：材质层内容 record 进 GraphicsLayer → toImageBitmap 位图回读
 *    → 原生 RenderScriptToolkit（NEON/SIMD CPU，自带线程池）迭代模糊
 *    → 以模糊位图替换原绘制。**全 API 级别同一条 CPU 路径**（0.2.3 无
 *    RenderEffect 分支）；Android Studio 预览（LocalInspectionMode）回退
 *    `Modifier.blur`；
 *  - AAR minSdk 21 < 项目 26 ✓；四 ABI 原生库约 0.37~0.42MB/个。
 *
 * 性能护栏（适用范围裁决）：
 *  - 每个使用点一个离屏 GraphicsLayer + 一次位图回读 + 一次 CPU 模糊；
 *    模糊在 draw 阶段经 runBlocking(Dispatchers.IO) 同步完成 ——
 *    **只在材质层节点（重）绘制时发生**（出现/尺寸变化/主题切换），
 *    静态内容不逐帧重模糊，滚动位移不触发重录；
 *  - 只用于**低数量、高价值**表面：聊天气泡（一屏可见通常 < 15 个）；
 *    禁止接入密集列表（时间线行/工具卡流）与高频重排表面；
 *  - LazyColumn 复用 OK：item 复用时节点与 GraphicsLayer 一并复用；
 *  - 流式气泡高度增长会按需重模糊（换行级别频率，NEON 单趟毫秒级）。
 *
 * 降级行为：blur 抛异常（位图回读失败等）时 Cloudy 层不绘制 ——
 * 霜面零底（GlassSurface 的渐变 background）仍在，观感回到 Gradient
 * 变体，不会出现空白气泡。
 */

/**
 * Cloudy 真模糊材质层 —— 仅被 [GlassSurface] 的 Cloudy 变体内部使用。
 *
 * 本层节点的内容 = [drawCloudyFrostTexture] 手绘光带纹理；`Modifier.cloudy`
 * 包裹在本层上，drawContent 只含纹理 —— **文字内容是兄弟节点，绝不被模糊**。
 * 调用方（GlassSurface）负责：霜面零底在下方、crisp 叠加层与内容在上方。
 */
@Composable
internal fun CloudyFrostLayer(
    palette: GlassPalette,
    style: GlassStyle,
    modifier: Modifier = Modifier
) {
    // Cloudy 半径单位是像素（Int），档位 blurRadius 是 dp —— 按 density 换算。
    // 夹 [8, 40]：低密度屏不至于弱到无感；高密度屏封顶（>25px 走两趟迭代）。
    val density = LocalDensity.current.density
    val radiusPx = remember(style.blurRadius, density) {
        (style.blurRadius.value * density).roundToInt().coerceIn(8, 40)
    }
    Box(
        modifier = modifier
            // 自体真模糊：API 全级别原生 NEON CPU 位图模糊（预览模式回退 blur）
            .cloudy(radius = radiusPx)
            // 纹理画在 cloudy 内层 —— 被记录进离屏层并被模糊的就是它
            .drawBehind { drawCloudyFrostTexture(palette) }
    )
}

/**
 * 光带纹理 —— 全部 palette 派生、DrawScope 手绘，无位图资源：
 *  - 垂直渐变：frostLift 顶部受光 → 中部透明（底色交给霜面零底），
 *    尾段 frostBase 低浓度沉色（磨砂玻璃自下而上的厚度感）；
 *  - 斜向光带 ×3（主受光带 / 交叉细亮带 / 右下反带）：真模糊把硬边
 *    光带扩散成柔和发光磨砂 —— 白天 = White+primary 微染的受光
 *    （治「死白」），夜间 = White+primary 霓虹光雾。
 * 光带色复用 palette.sweepColor / specular（已在 glassPalette 按主题
 * 双态调好浓度与染色，这里只定几何）。
 */
private fun DrawScope.drawCloudyFrostTexture(palette: GlassPalette) {
    if (size.width <= 0f || size.height <= 0f) return
    val w = size.width
    val h = size.height

    // ═══ 垂直渐变：顶部 lift + 尾段沉色（浓度按 palette 自带 alpha 派生）═══
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.frostLift, Color.Transparent),
            startY = 0f,
            endY = h * 0.72f
        )
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                palette.frostBase.copy(alpha = palette.frostBase.alpha * 0.35f)
            ),
            startY = h * 0.45f,
            endY = h
        )
    )

    // ═══ 斜向光带 A：主受光带（左上 → 中右，宽而柔）═══
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, palette.sweepColor, Color.Transparent),
            start = Offset(w * 0.02f, 0f),
            end = Offset(w * 0.68f, h * 0.55f)
        )
    )

    // ═══ 斜向光带 B：交叉细亮带（更陡角度、白高光、更窄）═══
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, palette.specular, Color.Transparent),
            start = Offset(w * 0.42f, 0f),
            end = Offset(w * 1.0f, h * 0.75f)
        )
    )

    // ═══ 斜向光带 C：右下反带（第二光源暗示，浓度四成）═══
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                palette.sweepColor.copy(alpha = palette.sweepColor.alpha * 0.4f),
                Color.Transparent
            ),
            start = Offset(w, h),
            end = Offset(w * 0.25f, h * 0.35f)
        )
    )
}

/**
 * Agent 回复气泡玻璃壳 —— 完成/流式/思考三气泡的统一容器（v7）。
 *
 * 与 [GlassCard]（Gradient 霜面）的差异只在材质：内部走
 * [GlassStyle.Bubble] + [GlassFrostMaterial.Cloudy]（真模糊光雾材质）；
 * 边缘光/镜面高光/深度阴影仍由 GlassSurface crisp 绘制，文字永不被模糊。
 * 形状、强调色、padding 由调用方保持既有约定（气泡尾巴角、340dp 上限、
 * 12dp 紧凑内距）。
 */
@Composable
fun AgentBubbleGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
    accent: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        // 气泡嵌在 hazeSource（消息列表）子树内 —— Haze 1.4 无法嵌套采样，
        // 诚实走 Frosted 档的 Cloudy 变体（自体真模糊，不冒充 backdrop）
        state = null,
        style = GlassStyle.Bubble,
        shape = shape,
        accent = accent,
        frostMaterial = GlassFrostMaterial.Cloudy
    ) {
        Column { content() }
    }
}
