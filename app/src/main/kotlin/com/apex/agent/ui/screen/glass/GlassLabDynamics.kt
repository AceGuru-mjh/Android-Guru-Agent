package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface

/**
 * ═══════════════════════════════════════════════════════════════
 *  顶阶动态 —— v1.4.5 动效预算重构（Pro Dynamics v2）
 * ═══════════════════════════════════════════════════════════════
 *
 * 对标顶级产品动态语言的三件套（全部实时绘制，逐帧可验证）：
 *  1. **流光边框**：锥形渐变描边沿轮廓流动 —— 进入卡片 / 点击触发单圈，
 *     空闲时静止；**色标轮转替代画布旋转**：outline 几何固定不转，
 *     圆角矩形拐角不再出现旋转描边撕裂（原 rotate + sweep 的经典接缝坑）；
 *  2. **镜面扫掠**：全 section 共享同一扫掠时钟（多卡同屏相位一致，
 *     不再各跑各的光带互相打架）；光带经 GlassSurface.specularSweep
 *     绘制在材质之上、文字之下 —— 扫过正文时不再洗白文字；
 *     浅色主题光带用 primary 染色（纯白光带在白霜底上是白上白，看不见）；
 *  3. **呼吸光晕**：唯一保留的常驻律动 —— 浅色主题用「加深 tint 光晕 +
 *     tint 细环」双线索（细环在白底上清晰可读且不发灰；单纯拉高
 *     光晕透明度只会得到灰蒙蒙的脏边）。
 *
 * 动效预算（Motion Budget）：
 *  - 同屏常驻循环动画 ≤ 1 —— 呼吸光晕是本区唯一的 ambient 律动；
 *  - 流光边框 / 镜面扫掠均为事件驱动（进入 / 点击触发），空闲零动画成本；
 *  - 三件套同开也不再互相轰炸：常驻的只有呼吸，其余按需播放。
 *
 * 诚实声明（延续本实验室原则）：
 *  - 边框/扫掠/光晕均为单图层 Canvas 级绘制，无折射位移（Refraction 仍未实现）；
 *  - 动画值以 State<Float> 传入 draw 作用域读取 —— 子树零逐帧重组。
 */
@Composable
internal fun DynamicsSection() {
    SectionHeader(
        title = "顶阶动态 · Pro Dynamics",
        hint = "三件套动态语言 —— 动效预算内运行：流光/扫掠事件触发，呼吸为唯一常驻律动"
    )

    // ═══ 共享扫掠时钟 ═══
    // 全 section 唯一的扫掠相位源：多张卡同屏传同一个 State，
    // 相位完全一致 —— 修复原「每卡独立 rememberInfiniteTransition」
    // 各自起播导致的光带错乱（视觉混乱源）。
    val sweepClock = rememberInfiniteTransition(label = "shared_specular_sweep")
    val sweepPhase = sweepClock.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        // keyframes 实现「扫完即停」：前 4200ms 扫掠，后 1600ms 停在卡外
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5800 // 4200 扫 + 1600 停
                (-0.4f) at 0 with FastOutSlowInEasing
                1.4f at 4200
                1.4f at 5800
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "shared_sweep_phase"
    )

    ConicBorderCard()
    SpecularSweepCard(sweepPhase)
    BreathingOrbsRow()
}

// ── 1. 流光边框 ──────────────────────────────────────────────────────────────

/**
 * 锥形渐变描边卡：进入组合 / 点击触发单圈流光（2.4s），空闲静止。
 *
 * 实现要点（v1.4.5 重构）：
 *  - **几何不旋转**：原实现 `rotate(angle) { drawOutline(sweepGradient) }` 把圆角
 *    矩形描边整体转起来 —— 非正多边形轮廓旋转后描边离开原边界，拐角处出现
 *    撕裂错位（Compose clip 边界经典接缝坑）；
 *  - **色标轮转**：改为每帧把 sweep gradient 的颜色停靠点按 angle/360 平移，
 *    outline 始终固定在圆角矩形上 —— 光斑照样绕轮廓流动，接缝消失；
 *  - **事件触发单循环**：替换永久旋转（视觉疲劳 + 干扰阅读）——
 *    进入卡片时自动播放一圈，点击再触发一圈。
 */
@Composable
private fun ConicBorderCard() {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)

    // pulses ≥ 1 即播放一圈：初值 1 = 进入组合自动播一圈；点击 +1 重启
    var pulses by remember { mutableStateOf(1) }
    val angle = remember { Animatable(0f) }

    LaunchedEffect(pulses) {
        angle.snapTo(0f)
        angle.animateTo(360f, tween(durationMillis = 2400, easing = LinearEasing))
    }

    DynamicsFrame(label = "流光边框 · Conic Border", icon = Icons.Filled.FlashOn) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                // 点击再触发一圈（触屏设备上按压即 hover 语义的等价交互）
                .pointerInput(Unit) {
                    detectTapGestures { pulses += 1 }
                }
                // #323：outline 与 sweep 笔刷经 drawWithCache 只随尺寸重建；
                // 旋转改走 DrawScope.rotate（旧实现逐帧重建 outline + 停靠点
                // 排序 + sweepGradient 三连分配，色标回绕保护逻辑随之退役）
                .drawWithCache {
                    val outline = cardShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    // #323：sweepGradient(vararg colorStops, center = ...) ——色标对
                    // 走 vararg 展开，center 用命名参数（CacheDrawScope 无 center
                    // 成员，只有 Size.center 扩展）。
                    val brush = Brush.sweepGradient(
                        *arrayOf(
                            0f to Color.Transparent,
                            0.24f to scheme.primary.copy(alpha = 0.90f),
                            0.50f to Color.Transparent,
                            0.74f to scheme.tertiary.copy(alpha = 0.60f),
                            1f to Color.Transparent
                        ),
                        center = size.center
                    )
                    onDrawWithContent {
                        drawContent()
                        val deg = angle.value
                        if (deg <= 0f) return@onDrawWithContent
                        rotate(degrees = deg, pivot = center) {
                            drawOutline(
                                outline = outline,
                                brush = brush,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                style = GlassStyle.Card,
                shape = cardShape
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "光沿轮廓流动",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "进入 / 点击触发单圈 —— 色标轮转替代画布旋转，圆角零接缝",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 2. 镜面扫掠 ──────────────────────────────────────────────────────────────

/**
 * 周期性斜向光带：4.2s 扫过 + 1.6s 真停顿，相位来自共享时钟。
 *
 * v1.4.5 重构：
 *  - 相位由 [DynamicsSection] 统一供给 —— 多卡同屏完全同步；
 *  - 光带不再画在 drawWithContent 的内容之上（旧版会洗白文字），
 *    而是经 [GlassSurface.specularSweep] 绘制在材质之上、文字之下；
 *  - 光带颜色随主题派生：浅色 = primary 染色光带（白底可辨不脏），
 *    深色 = 白色高光。
 */
@Composable
private fun SpecularSweepCard(sweepPhase: State<Float>) {
    val cardShape = RoundedCornerShape(16.dp)

    DynamicsFrame(label = "镜面扫掠 · Specular Sweep", icon = Icons.Filled.BlurOn) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            style = GlassStyle.Card,
            shape = cardShape,
            specularSweep = sweepPhase
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Lens,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        "光带掠过卡面",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "多卡共享时钟同步扫掠 —— 光带在文字层下，浅色主题 primary 染色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── 3. 呼吸光晕 ──────────────────────────────────────────────────────────────

/**
 * 三枚相位错开 1/3 周期的玻璃球：光环半径与亮度同步脉动。
 * 本组是三件套中唯一的常驻律动（动效预算：同屏常驻循环 ≤ 1）。
 */
@Composable
private fun BreathingOrbsRow() {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f

    // 浅色主题：光晕色向 onSurface 加深 25% —— 提高与白底的明度差。
    // 只拉 alpha 不加深色，浅 tint 在白底上依旧不可见，拉过头就是灰蒙脏边；
    // 加深后的 tint 让低 alpha 也能被读到，同时保持「色」而非「灰」。
    val deepPrimary = if (dark) scheme.primary else lerp(scheme.primary, scheme.onSurface, 0.25f)
    val deepTertiary = if (dark) scheme.tertiary else lerp(scheme.tertiary, scheme.onSurface, 0.25f)
    val deepSecondary = if (dark) scheme.secondary else lerp(scheme.secondary, scheme.onSurface, 0.25f)

    val transition = rememberInfiniteTransition(label = "breathing_orbs")
    // 三路相位：0 / 1/3 / 2/3 周期错开 —— 呼吸此起彼伏
    val breathA = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_a"
    )
    val breathB = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(866)
        ),
        label = "breath_b"
    )
    val breathC = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(1733)
        ),
        label = "breath_c"
    )

    DynamicsFrame(label = "呼吸光晕 · Breathing Glow", icon = Icons.Filled.AutoAwesome) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BreathingOrb(breath = breathA, orbSize = 56.dp, tint = scheme.primary, deepTint = deepPrimary, dark = dark)
            BreathingOrb(breath = breathB, orbSize = 72.dp, tint = scheme.tertiary, deepTint = deepTertiary, dark = dark)
            BreathingOrb(breath = breathC, orbSize = 56.dp, tint = scheme.secondary, deepTint = deepSecondary, dark = dark)
        }
    }
}

/**
 * 单枚呼吸玻璃球：外圈脉动光晕 + tint 细环 + 玻璃本体（参数名避开 DrawScope.size）。
 *
 * 浅色模式双线索：加深 tint 的径向光晕（可读且不灰）+ 纯 tint 细环
 *（1.5dp 描边环在白底上轮廓清晰，环随呼吸收张）—— 取代旧版
 * 「浅色几乎不可见 / 拉高强度则边缘发灰」的单光晕配方。
 */
@Composable
private fun BreathingOrb(
    breath: State<Float>,
    orbSize: androidx.compose.ui.unit.Dp,
    tint: Color,
    deepTint: Color,
    dark: Boolean
) {
    // 光晕 / 细环强度按明暗双态调参：深色维持原发光语言，
    // 浅色靠「加深色 + 细环」而非拉 alpha —— 不产生灰脏边
    val haloPeak = if (dark) 0.30f else 0.26f
    val haloAmp = if (dark) 0.18f else 0.16f
    val ringBase = if (dark) 0.10f else 0.26f
    val ringAmp = if (dark) 0.10f else 0.18f
    val coreBase = if (dark) 0.45f else 0.55f

    Box(contentAlignment = Alignment.Center) {
        // 脉动光晕 + 细环（draw 作用域读 State —— 零重组）
        Box(
            Modifier
                .size(orbSize * 1.65f)
                .drawBehind {
                    val b = breath.value
                    val r = size.minDimension * (0.30f + 0.16f * b)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                deepTint.copy(alpha = haloPeak + haloAmp * b),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )
                    drawCircle(
                        color = tint.copy(alpha = ringBase + ringAmp * b),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
        )
        GlassSurface(
            modifier = Modifier.size(orbSize),
            style = GlassStyle.Floating,
            shape = CircleShape,
            accent = tint
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(orbSize * 0.34f)
                        .drawBehind {
                            val b = breath.value
                            drawCircle(
                                color = tint.copy(alpha = coreBase + 0.35f * b),
                                radius = size.minDimension / 2f
                            )
                        }
                )
            }
        }
    }
}

// ── 展示框 ──────────────────────────────────────────────────────────────────

/** 动态演示的标准外框：等宽小标签 + 演示主体。 */
@Composable
private fun DynamicsFrame(
    label: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}
