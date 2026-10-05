package com.apex.agent.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.apex.agent.ui.screen.settings.AgentRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══ 内置 Coding 专家模板 —— 官方品牌图标（纯代码矢量）═══
 *
 * 用户诉求：「每个专家模板对应的官方图标，例如 Python 语言图标、
 * Kotlin 语言图标」。[AgentRole.CODING_EXPERTS] 的内置专家原先全部
 * 用 emoji 表示，本文件为每个技术栈手绘官方品牌简化矢量，配色遵循
 * 各官方品牌色（Git #F05032 / Android #3DDC84 / Kotlin #7F52FF …），
 * 仅内置专家使用，不引入任何二进制图片资源：
 *
 *  - 字母瓦型（JS / TS / GO / C# / php / Rust / C++ / Shell）：
 *    背景瓦 + 粗体 Text 字形 —— 避开 TextMeasurer 与 drawText 复杂度；
 *  - 异形 logo（git 分支 / Android 机器人头 / Kotlin 折角方 / Java
 *    咖啡杯 / Python 双蛇 / Swift 燕形 / Ruby 宝石 / SQL 圆柱 / 前端
 *    浏览器窗）：Canvas Path 与 polygon 手绘；
 *  - 全栈为组合语义图标（三层堆叠横条，主题 primary → tertiary
 *    阶梯色，非单一品牌色）。
 *
 * 尺寸约定：调用方经 [Modifier.size] 传入 16–36dp 常用小尺寸；瓦片
 * 文字字号随约束宽度等比缩放（dp 语义，不受系统字体缩放挤压溢出）。
 *
 * 入口两级：
 *  - [AgentRoleBrandIcon]（roleId → 图标，未知 id 回退中性 SmartToy）；
 *  - [AgentRoleAvatarIcon]（role + 图标或 emoji 的组合入口，Agent
 *    模式普通角色自动回退 role.emoji 文本，聊天链路零行为损失）。
 */

// ───────────────────────── 官方品牌色（hex）─────────────────────────

/** Git 官方橙（分支图）。 */
private val GitOrange = Color(0xFFF05032)

/** Android 官方绿（机器人头）。 */
private val AndroidGreen = Color(0xFF3DDC84)

/** Kotlin 官方紫（折角方 mark）。 */
private val KotlinPurple = Color(0xFF7F52FF)

/** Java 官方蓝（杯体）。 */
private val JavaBlue = Color(0xFF5382A1)

/** Java 官方橙（杯柄与蒸汽）。 */
private val JavaOrange = Color(0xFFE76F00)

/** Python 官方蓝（上蛇）。 */
private val PythonBlue = Color(0xFF3776AB)

/** Python 官方黄（下蛇）。 */
private val PythonYellow = Color(0xFFFFD43B)

/** JavaScript 官方黄（瓦底）。 */
private val JavaScriptYellow = Color(0xFFF7DF1E)

/** TypeScript 官方蓝（瓦底）。 */
private val TypeScriptBlue = Color(0xFF3178C6)

/** Go 官方青（瓦底）。 */
private val GoCyan = Color(0xFF00ADD8)

/** Rust 齿轮深灰（官方黑齿轮的日夜双模式中性替代）。 */
private val RustGray = Color(0xFF57534E)

/** C++ 官方蓝（六边形底）。 */
private val CppBlue = Color(0xFF00599C)

/** C# 官方紫（圆瓦底）。 */
private val CSharpPurple = Color(0xFF512BD4)

/** Swift 官方橙（燕形）。 */
private val SwiftOrange = Color(0xFFF05138)

/** PHP 官方紫（椭圆底）。 */
private val PhpPurple = Color(0xFF777BB4)

/** Ruby 官方红（宝石主体）。 */
private val RubyRed = Color(0xFFCC342D)

/** Ruby 暗红（宝石切角暗面）。 */
private val RubyDark = Color(0xFF9B111E)

/** Shell 提示符绿。 */
private val ShellGreen = Color(0xFF4EAA25)

/** Shell 终端深瓦底。 */
private val ShellBg = Color(0xFF212126)

/** 前端三原色圆点（调色板语义：红）。 */
private val RgbRed = Color(0xFFE53935)

/** 前端三原色圆点（绿）。 */
private val RgbGreen = Color(0xFF43A047)

/** 前端三原色圆点（蓝）。 */
private val RgbBlue = Color(0xFF1E88E5)

/** 内置 Coding 专家 id 前缀（与 AgentRole.CODING_EXPERTS 同源约定）。 */
private const val CODING_BRAND_PREFIX = "builtin_coding_"

// ───────────────────────── 统一入口 ─────────────────────────

/**
 * roleId → 官方品牌图标。仅覆盖内置 Coding 专家（id 前缀
 * builtin_coding_）；未知 id 回退中性 SmartToy 图标（防御式，
 * Agent 模式角色不经此渲染品牌）。
 *
 * @param contentDescription 无障碍描述（调用方传角色名；null = 装饰性）
 */
@Composable
fun AgentRoleBrandIcon(
    roleId: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    when (roleId) {
        AgentRole.BUILTIN_CODING_FULL_STACK_ID ->
            FullStackMark(modifier.brandA11y(contentDescription))
        "builtin_coding_git" -> GitBranchMark(modifier.brandA11y(contentDescription))
        "builtin_coding_android" -> AndroidRobotMark(modifier.brandA11y(contentDescription))
        "builtin_coding_kotlin" -> KotlinMark(modifier.brandA11y(contentDescription))
        "builtin_coding_java" -> JavaCupMark(modifier.brandA11y(contentDescription))
        "builtin_coding_python" -> PythonSnakesMark(modifier.brandA11y(contentDescription))
        "builtin_coding_javascript" -> BrandTile(
            modifier.brandA11y(contentDescription), JavaScriptYellow, "JS", Color(0xFF15161A)
        )
        "builtin_coding_typescript" -> BrandTile(
            modifier.brandA11y(contentDescription), TypeScriptBlue, "TS", Color.White
        )
        "builtin_coding_go" -> BrandTile(
            modifier.brandA11y(contentDescription), GoCyan, "GO", Color.White, italic = true
        )
        "builtin_coding_rust" -> BrandCanvasTile(
            modifier.brandA11y(contentDescription),
            label = "R",
            labelColor = RustGray,
            labelRatio = 0.36f,
            background = { rustGear(RustGray) }
        )
        "builtin_coding_cpp" -> BrandCanvasTile(
            modifier.brandA11y(contentDescription),
            label = "C++",
            labelColor = Color.White,
            labelRatio = 0.32f,
            background = { hexagonFill(CppBlue) }
        )
        "builtin_coding_csharp" -> BrandTile(
            modifier.brandA11y(contentDescription), CSharpPurple, "C#", Color.White,
            shape = CircleShape
        )
        "builtin_coding_swift" -> SwiftBirdMark(modifier.brandA11y(contentDescription))
        "builtin_coding_php" -> BrandCanvasTile(
            modifier.brandA11y(contentDescription),
            label = "php",
            labelColor = Color.White,
            labelRatio = 0.38f,
            italic = true,
            background = { phpEllipse(PhpPurple) }
        )
        "builtin_coding_ruby" -> RubyGemMark(modifier.brandA11y(contentDescription))
        "builtin_coding_sql" -> DatabaseMark(modifier.brandA11y(contentDescription))
        "builtin_coding_shell" -> BrandTile(
            // 终端提示符字面量：$ 需转义（Kotlin 字符串模板）
            modifier.brandA11y(contentDescription), ShellBg, "\$_", ShellGreen, labelRatio = 0.42f
        )
        "builtin_coding_frontend" -> BrowserFrameMark(modifier.brandA11y(contentDescription))
        else -> Icon(
            imageVector = Icons.Outlined.SmartToy,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 品牌图标与角色 emoji 的组合入口（调用方优先用这个）：
 * 内置 Coding 专家 → 官方品牌图标；其余角色（Agent 模式内置/自定义）
 * → 原 role.emoji 文本回退（胶囊/菜单零行为损失）。
 *
 * @param size 统一渲染边长（常用 16–36dp）
 */
@Composable
fun AgentRoleAvatarIcon(
    role: AgentRole,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = role.name
) {
    if (role.id.startsWith(CODING_BRAND_PREFIX)) {
        AgentRoleBrandIcon(
            roleId = role.id,
            modifier = modifier.size(size),
            contentDescription = contentDescription
        )
    } else {
        // emoji 文本回退：字号随 size 等比（dp 语义），保持原胶囊观感
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                text = role.emoji,
                fontSize = with(LocalDensity.current) { (size * 0.88f).toSp() }
            )
        }
    }
}

// ───────────────────────── 通用小件 ─────────────────────────

/** 品牌图标统一无障碍语义：非空描述替换子树（瓦片 Text 不重复播报）。 */
private fun Modifier.brandA11y(description: String?): Modifier =
    if (description == null) this
    else clearAndSetSemantics { contentDescription = description }

/** 字母瓦：品牌色背景瓦 + 居中粗体字形（字号随瓦宽等比缩放）。 */
@Composable
private fun BrandTile(
    modifier: Modifier = Modifier,
    background: Color,
    label: String,
    labelColor: Color,
    italic: Boolean = false,
    labelRatio: Float = 0.46f,
    shape: Shape = RoundedCornerShape(percent = 24)
) {
    BoxWithConstraints(modifier = modifier.background(background, shape), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            color = labelColor,
            fontSize = with(LocalDensity.current) { (maxWidth * labelRatio).toSp() },
            fontWeight = FontWeight.Bold,
            fontStyle = if (italic) FontStyle.Italic else null,
            maxLines = 1
        )
    }
}

/** 形状瓦：Canvas 自绘异形底 + 居中粗体字形（Rust 齿轮 / C++ 六边形 / php 椭圆）。 */
@Composable
private fun BrandCanvasTile(
    modifier: Modifier = Modifier,
    label: String,
    labelColor: Color,
    labelRatio: Float = 0.44f,
    italic: Boolean = false,
    background: DrawScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) { background() }
        Text(
            text = label,
            color = labelColor,
            fontSize = with(LocalDensity.current) { (maxWidth * labelRatio).toSp() },
            fontWeight = FontWeight.Bold,
            fontStyle = if (italic) FontStyle.Italic else null,
            maxLines = 1
        )
    }
}

// ───────────────────────── DrawScope 归一化工具 ─────────────────────────

/** 画布内接正方形边长（小图标按正方形设计，非方形约束时居中）。 */
private val DrawScope.side: Float
    get() = minOf(size.width, size.height)

/** 归一化坐标（0..1，内接正方形内）→ 绝对像素 Offset。 */
private fun DrawScope.pt(x: Float, y: Float): Offset {
    val s = side
    return Offset((size.width - s) / 2f + s * x, (size.height - s) / 2f + s * y)
}

/** 归一化多边形（顶点 x,y 平铺序列，至少 3 点）→ 闭合 Path。 */
private fun DrawScope.polygon(vararg xy: Float): Path {
    val first = pt(xy[0], xy[1])
    return Path().apply {
        moveTo(first.x, first.y)
        var i = 2
        while (i < xy.size) {
            val vertex = pt(xy[i], xy[i + 1])
            lineTo(vertex.x, vertex.y)
            i += 2
        }
        close()
    }
}

// ───────────────────────── 异形 logo（Canvas 手绘） ─────────────────────────

/** 全栈：三层堆叠横条（阶梯错位 → 层叠架构感；主题 primary→tertiary 阶梯色）。 */
@Composable
private fun FullStackMark(modifier: Modifier) {
    val topColor = MaterialTheme.colorScheme.primary
    val bottomColor = MaterialTheme.colorScheme.tertiary
    val layerColors = listOf(topColor, lerp(topColor, bottomColor, 0.5f), bottomColor)
    Canvas(modifier = modifier) {
        val origin = pt(0f, 0f)
        val barHeight = side * 0.2f
        val gap = side * 0.06f
        repeat(3) { i ->
            val inset = side * (0.06f + i * 0.09f)
            drawRoundRect(
                color = layerColors[i],
                topLeft = Offset(origin.x + inset, origin.y + side * 0.12f + i * (barHeight + gap)),
                size = Size(side - inset * 2f, barHeight),
                cornerRadius = CornerRadius(side * 0.04f)
            )
        }
    }
}

/** Git：官方橙分支图（左主干 + 右分支折线接入 + 三节点描边圆）。 */
@Composable
private fun GitBranchMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = side * 0.1f
        drawLine(GitOrange, pt(0.28f, 0.2f), pt(0.28f, 0.8f), stroke, cap = StrokeCap.Round)
        drawLine(GitOrange, pt(0.72f, 0.2f), pt(0.72f, 0.5f), stroke, cap = StrokeCap.Round)
        val fold = Path().apply {
            val start = pt(0.72f, 0.5f)
            val corner = pt(0.72f, 0.66f)
            val end = pt(0.28f, 0.66f)
            moveTo(start.x, start.y)
            quadraticBezierTo(corner.x, corner.y, end.x, end.y)
        }
        drawPath(fold, GitOrange, style = Stroke(stroke, cap = StrokeCap.Round))
        listOf(0.28f to 0.2f, 0.72f to 0.2f, 0.28f to 0.8f).forEach { (x, y) ->
            drawCircle(GitOrange, radius = side * 0.115f, center = pt(x, y), style = Stroke(side * 0.08f))
        }
    }
}

/** Android：官方绿机器人头（圆顶 + 平底 + 双天线 + 双眼白点）。 */
@Composable
private fun AndroidRobotMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = AndroidGreen,
            topLeft = pt(0.2f, 0.28f),
            size = Size(side * 0.6f, side * 0.52f),
            cornerRadius = CornerRadius(side * 0.26f)
        )
        drawCircle(Color.White, radius = side * 0.05f, center = pt(0.35f, 0.47f))
        drawCircle(Color.White, radius = side * 0.05f, center = pt(0.65f, 0.47f))
        val antenna = side * 0.07f
        drawLine(AndroidGreen, pt(0.3f, 0.3f), pt(0.19f, 0.1f), antenna, cap = StrokeCap.Round)
        drawLine(AndroidGreen, pt(0.7f, 0.3f), pt(0.81f, 0.1f), antenna, cap = StrokeCap.Round)
    }
}

/** Kotlin：官方紫方形 + 右缘 V 缺口（缺角三角半透明 → 经典折角双 polygon）。 */
@Composable
private fun KotlinMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawPath(polygon(0f, 1f, 0f, 0f, 1f, 0f, 0.5f, 0.5f, 1f, 1f), KotlinPurple)
        drawPath(polygon(1f, 0f, 0.5f, 0.5f, 1f, 1f), KotlinPurple.copy(alpha = 0.4f))
    }
}

/** Java：#5382A1 杯体 + #E76F00 杯柄弧与两缕蒸汽（简化咖啡杯）。 */
@Composable
private fun JavaCupMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = JavaBlue,
            topLeft = pt(0.16f, 0.4f),
            size = Size(side * 0.5f, side * 0.42f),
            cornerRadius = CornerRadius(side * 0.05f)
        )
        drawArc(
            color = JavaOrange,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = pt(0.62f, 0.42f),
            size = Size(side * 0.24f, side * 0.38f),
            style = Stroke(side * 0.07f, cap = StrokeCap.Round)
        )
        val steam = side * 0.055f
        drawLine(JavaOrange, pt(0.3f, 0.08f), pt(0.3f, 0.3f), steam, cap = StrokeCap.Round)
        drawLine(JavaOrange, pt(0.48f, 0.04f), pt(0.48f, 0.3f), steam, cap = StrokeCap.Round)
    }
}

/** Python：官方双色双蛇简化（蓝上黄下错位圆角砖 + 蛇头眼点）。 */
@Composable
private fun PythonSnakesMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val corner = CornerRadius(side * 0.1f)
        drawRoundRect(PythonBlue, pt(0.1f, 0.04f), Size(side * 0.62f, side * 0.44f), cornerRadius = corner)
        drawRoundRect(PythonYellow, pt(0.28f, 0.52f), Size(side * 0.62f, side * 0.44f), cornerRadius = corner)
        drawCircle(Color.White, radius = side * 0.05f, center = pt(0.24f, 0.26f))
        drawCircle(PythonBlue, radius = side * 0.05f, center = pt(0.76f, 0.74f))
    }
}

/** Swift：官方橙燕形（贝塞尔 path 逼近：头 → 左翼 → 尾叉回勾 → 腹底 → 内弧）。 */
@Composable
private fun SwiftBirdMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val head = pt(0.9f, 0.1f)
        val wingTop = pt(0.16f, 0.3f)
        val wingTip = pt(0.05f, 0.64f)
        val tailHook = pt(0.38f, 0.58f)
        val belly = pt(0.48f, 0.96f)
        val inner = pt(0.64f, 0.52f)
        val bird = Path().apply {
            moveTo(head.x, head.y)
            cubicTo(pt(0.62f, 0.02f).x, pt(0.62f, 0.02f).y, pt(0.34f, 0.08f).x, pt(0.34f, 0.08f).y, wingTop.x, wingTop.y)
            cubicTo(pt(0.05f, 0.4f).x, pt(0.05f, 0.4f).y, pt(0.02f, 0.54f).x, pt(0.02f, 0.54f).y, wingTip.x, wingTip.y)
            cubicTo(pt(0.16f, 0.56f).x, pt(0.16f, 0.56f).y, pt(0.28f, 0.54f).x, pt(0.28f, 0.54f).y, tailHook.x, tailHook.y)
            cubicTo(pt(0.3f, 0.72f).x, pt(0.3f, 0.72f).y, pt(0.36f, 0.88f).x, pt(0.36f, 0.88f).y, belly.x, belly.y)
            cubicTo(pt(0.42f, 0.74f).x, pt(0.42f, 0.74f).y, pt(0.52f, 0.6f).x, pt(0.52f, 0.6f).y, inner.x, inner.y)
            cubicTo(pt(0.76f, 0.36f).x, pt(0.76f, 0.36f).y, pt(0.86f, 0.2f).x, pt(0.86f, 0.2f).y, head.x, head.y)
            close()
        }
        drawPath(bird, SwiftOrange)
    }
}

/** Ruby：官方红宝石多边形（暗红切角暗面：左冠 + 右底）。 */
@Composable
private fun RubyGemMark(modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawPath(polygon(0.28f, 0.12f, 0.72f, 0.12f, 0.93f, 0.42f, 0.5f, 0.95f, 0.07f, 0.42f), RubyRed)
        drawPath(polygon(0.28f, 0.12f, 0.5f, 0.12f, 0.36f, 0.42f), RubyDark)
        drawPath(polygon(0.5f, 0.95f, 0.93f, 0.42f, 0.64f, 0.42f), RubyDark)
    }
}

/** SQL：数据库圆柱（onSurfaceVariant 描边 + primary 淡填充 + 中腰条纹）。 */
@Composable
private fun DatabaseMark(modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val width = side * 0.6f
        val ellipseH = side * 0.2f
        val originY = (size.height - side) / 2f
        val topY = originY + side * 0.1f
        val bottomY = originY + side * 0.9f
        val bodyTop = topY + ellipseH / 2f
        val bodyBottom = bottomY - ellipseH / 2f
        val left = centerX - width / 2f
        // 淡填充：顶椭圆 + 主体 + 底弧
        drawOval(fill, topLeft = Offset(left, topY), size = Size(width, ellipseH))
        drawRect(fill, topLeft = Offset(left, bodyTop), size = Size(width, bodyBottom - bodyTop))
        drawArc(fill, 0f, 180f, true, Offset(left, bottomY - ellipseH), Size(width, ellipseH))
        // 描边：顶椭圆 + 两侧线 + 底弧 + 中腰条纹
        val stroke = side * 0.075f
        drawOval(outline, topLeft = Offset(left, topY), size = Size(width, ellipseH), style = Stroke(stroke))
        drawLine(outline, Offset(left, bodyTop), Offset(left, bodyBottom), stroke)
        drawLine(outline, Offset(left + width, bodyTop), Offset(left + width, bodyBottom), stroke)
        drawArc(outline, 0f, 180f, false, Offset(left, bottomY - ellipseH), Size(width, ellipseH), style = Stroke(stroke))
        drawArc(outline, 0f, 180f, false, Offset(left, size.height / 2f - ellipseH / 2f), Size(width, ellipseH), style = Stroke(side * 0.055f))
    }
}

/** 前端：浏览器窗口线框 + 三原色圆点（调色板语义）+ 两条内容占位线。 */
@Composable
private fun BrowserFrameMark(modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = outline,
            topLeft = pt(0.07f, 0.1f),
            size = Size(side * 0.86f, side * 0.8f),
            cornerRadius = CornerRadius(side * 0.14f),
            style = Stroke(side * 0.065f)
        )
        drawLine(outline, pt(0.07f, 0.34f), pt(0.93f, 0.34f), side * 0.05f)
        drawCircle(RgbRed, radius = side * 0.055f, center = pt(0.21f, 0.22f))
        drawCircle(RgbGreen, radius = side * 0.055f, center = pt(0.35f, 0.22f))
        drawCircle(RgbBlue, radius = side * 0.055f, center = pt(0.49f, 0.22f))
        val content = outline.copy(alpha = 0.45f)
        drawLine(content, pt(0.21f, 0.56f), pt(0.79f, 0.56f), side * 0.04f)
        drawLine(content, pt(0.21f, 0.72f), pt(0.63f, 0.72f), side * 0.04f)
    }
}

// ───────────────────────── 形状瓦的 Canvas 底 ─────────────────────────

/** Rust：深灰齿轮环（圆环描边 + 8 枚圆帽径向齿，官方黑齿轮的日夜双模式观感）。 */
private fun DrawScope.rustGear(color: Color) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val ringRadius = side * 0.31f
    drawCircle(color, radius = ringRadius, center = Offset(centerX, centerY), style = Stroke(side * 0.13f))
    val toothWidth = side * 0.13f
    val inner = ringRadius + side * 0.04f
    val outer = side * 0.46f
    repeat(8) { i ->
        val angle = PI * i / 4.0
        val directionX = cos(angle).toFloat()
        val directionY = sin(angle).toFloat()
        drawLine(
            color = color,
            start = Offset(centerX + directionX * inner, centerY + directionY * inner),
            end = Offset(centerX + directionX * outer, centerY + directionY * outer),
            strokeWidth = toothWidth,
            cap = StrokeCap.Round
        )
    }
}

/** C++：官方蓝平顶六边形填充（顶点 30° 步进 60°）。 */
private fun DrawScope.hexagonFill(color: Color) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = side * 0.47f
    val hexagon = Path()
    repeat(6) { i ->
        val angle = PI / 6.0 + i * PI / 3.0
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()
        if (i == 0) hexagon.moveTo(x, y) else hexagon.lineTo(x, y)
    }
    hexagon.close()
    drawPath(hexagon, color)
}

/** PHP：官方紫横向椭圆填充。 */
private fun DrawScope.phpEllipse(color: Color) {
    drawOval(
        color = color,
        topLeft = Offset(size.width * 0.05f, size.height * 0.2f),
        size = Size(size.width * 0.9f, size.height * 0.6f)
    )
}
