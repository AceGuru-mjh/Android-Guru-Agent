package com.apex.agent.ui.screen.about

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════
 *  关于页 —— v1.4.3 从设置页「关于」区升级为抽屉最下方的一级页面
 * ═══════════════════════════════════════════════════════════════
 *
 * 页面结构（自上而下）：
 *  1. 玻璃 Hero（顶阶动态四件套，见 [AboutHero]）；
 *  2. 软件更新面板（[UpdatePanel]：增量补丁 + 高速镜像 + 进程托管下载）；
 *  3. 版本与构建（BuildConfig 真实值）；
 *  4. 功能一览（六大能力，图标 + 一句话）；
 *  5. 技术栈（真实运行时依赖清单）；
 *  6. 相关链接（源码 / 发布通道 / 问题反馈）+ Star 引导 + 致谢。
 *
 * 各区块入场为一次性交错动画（[AboutSectionContainer]）；Hero 的常驻
 * 动画全部走「State 在 draw 作用域读取」模式 —— 零重组，仅重绘对应图层。
 */

/** 项目仓库地址 —— 关于页所有外链的基地址。 */
private const val REPO_URL = "https://github.com/AceGuru-mjh/Android-Guru-Agent"

/** 发布仓库（双仓库发布架构的产物存放处）。 */
private const val RELEASE_URL = "https://github.com/AceGuru-mjh/Android-Guru-Agent-Release/releases"

/** Star 引导行图标色：琥珀色，与 M3 主色拉开距离，白/深底上都醒目。 */
private val StarAmber = Color(0xFFF59E0B)

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val noBrowserHint = stringResource(R.string.settings_about_no_browser)
    val open: (String) -> Unit = { url -> openUrl(context, url, noBrowserHint) }

    // Hero 可见性门控：页面非 lazy 滚动，滚出视口后无限动画仍在逐帧
    // invalidate + 重录（含 haze 模糊重采样）——CPU 空转。Hero 滚出
    // 视口 1.5 倍高度后切静态降级帧，滚回自动恢复
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val heroGatePx = with(density) { 252.dp.toPx() * 1.5f }
    val heroVisible by remember(heroGatePx) {
        derivedStateOf { scrollState.value < heroGatePx }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AboutHero(animated = heroVisible)

        // ── 软件更新（增量补丁 + 高速节点镜像）──
        AboutSectionContainer(index = 1) { UpdatePanel() }

        // ── 版本与构建 ──
        AboutSectionContainer(index = 2) {
            AboutSectionCard(
                title = stringResource(R.string.about_section_build),
                icon = Icons.Outlined.Info
            ) {
                InfoRow(
                    stringResource(R.string.settings_about_version),
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
                InfoRow(
                    stringResource(R.string.settings_about_package),
                    BuildConfig.APPLICATION_ID
                )
                InfoRow(
                    stringResource(R.string.settings_about_build_type),
                    BuildConfig.BUILD_TYPE
                )
            }
        }

        // ── 功能一览 ──
        AboutSectionContainer(index = 3) {
            AboutSectionCard(
                title = stringResource(R.string.about_section_features),
                icon = Icons.Outlined.Widgets
            ) {
                FeatureRow(
                    Icons.Default.SmartToy,
                    stringResource(R.string.about_feature_agent_title),
                    stringResource(R.string.about_feature_agent_desc)
                )
                FeatureRow(
                    Icons.Outlined.Code,
                    stringResource(R.string.about_feature_coding_title),
                    stringResource(R.string.about_feature_coding_desc)
                )
                FeatureRow(
                    Icons.Outlined.Terminal,
                    stringResource(R.string.about_feature_terminal_title),
                    stringResource(R.string.about_feature_terminal_desc)
                )
                FeatureRow(
                    Icons.Outlined.Storefront,
                    stringResource(R.string.about_feature_market_title),
                    stringResource(R.string.about_feature_market_desc)
                )
                FeatureRow(
                    Icons.Outlined.Lock,
                    stringResource(R.string.about_feature_vault_title),
                    stringResource(R.string.about_feature_vault_desc)
                )
                FeatureRow(
                    Icons.Outlined.BlurOn,
                    stringResource(R.string.about_feature_glass_title),
                    stringResource(R.string.about_feature_glass_desc)
                )
            }
        }

        // ── 技术栈 ──
        AboutSectionContainer(index = 4) {
            AboutSectionCard(
                title = stringResource(R.string.about_section_stack),
                icon = Icons.Outlined.Layers
            ) {
                Text(
                    stringResource(R.string.settings_about_components_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // ── 相关链接 ──
        AboutSectionContainer(index = 5) {
            AboutSectionCard(
                title = stringResource(R.string.about_section_links),
                icon = Icons.Outlined.OpenInNew
            ) {
                LinkRow(
                    Icons.Outlined.Code,
                    stringResource(R.string.about_link_source),
                    REPO_URL,
                    open
                )
                LinkRow(
                    Icons.Outlined.Download,
                    stringResource(R.string.about_link_release),
                    RELEASE_URL,
                    open
                )
                LinkRow(
                    Icons.Outlined.BugReport,
                    stringResource(R.string.about_link_issues),
                    "$REPO_URL/issues",
                    open
                )
            }
        }

        // ── Star 引导 ──
        AboutSectionContainer(index = 6) {
            StarCard(onOpen = { open(REPO_URL) })
        }

        // ── 致谢 + 页脚 ──
        AboutSectionContainer(index = 7) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.settings_about_credits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃 Hero —— 顶阶动态四件套
//  ① 极光漂移：三枚 Lissajous 光晕 + 七粒微尘（haze 采样源）
//  ② 流光边框：绕心旋转的锥形渐变描边（Linear / Vercel 质感）
//  ③ 镜面扫掠：斜向光带周期性掠过卡面
//  ④ 呼吸光晕：Logo 后主色光环脉动
//
//  性能：四路动画全部以 State<Float> 传入 draw 作用域读取 ——
//  Hero 子树零逐帧重组，每帧仅重绘对应图层（Canvas / drawBehind）。
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AboutHero(modifier: Modifier = Modifier, animated: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val hazeState = remember { HazeState() }
    val heroShape = RoundedCornerShape(22.dp)

    val drift: State<Float>
    val borderAngle: State<Float>
    val breath: State<Float>
    val sheen: State<Float>
    if (animated) {
        val transition = rememberInfiniteTransition(label = "about_hero")
        drift = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 26000, easing = LinearEasing)),
            label = "aurora_drift"
        )
        borderAngle = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 9000, easing = LinearEasing)),
            label = "border_sweep"
        )
        breath = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breath"
        )
        sheen = transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(1600)
            ),
            label = "specular_sweep"
        )
    } else {
        // 静态降级帧：极光取中位相位、光带停在卡外（不可见）
        drift = remember { mutableStateOf(0.5f) }
        borderAngle = remember { mutableStateOf(0f) }
        breath = remember { mutableStateOf(0.5f) }
        sheen = remember { mutableStateOf(-0.4f) }
    }

    Box(modifier = modifier.fillMaxWidth().height(252.dp)) {
        // ① 极光氛围层 —— 同时是 Hero 卡的 backdrop 采样源（API 32+ 真实折射）
        AuroraCanvas(
            drift = drift,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        )

        // ② 玻璃卡主体（Backdrop 档：实时采样背后的极光）
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            state = hazeState,
            style = GlassStyle.Card,
            shape = heroShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // ④ 呼吸光晕
                    Box(
                        Modifier
                            .size(96.dp)
                            .drawBehind {
                                val b = breath.value
                                val r = size.minDimension * (0.55f + 0.12f * b)
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            scheme.primary.copy(alpha = 0.34f + 0.16f * b),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = r
                                    ),
                                    radius = r,
                                    center = center
                                )
                            }
                    )
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        color = scheme.primary.copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = scheme.primary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "APEX//AGENT",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Text(
                    stringResource(R.string.drawer_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip("v${BuildConfig.VERSION_NAME}", emphasized = true)
                    HeroChip("VC ${BuildConfig.VERSION_CODE}", emphasized = false)
                    HeroChip(BuildConfig.BUILD_TYPE, emphasized = false)
                }
            }
        }

        // ③ 顶阶动态覆盖层：旋转流光边框 + 对角镜面扫掠
        // #262 Compose 最佳实践（draw 阶段零分配）：drawWithContent → drawWithCache。
        // 缓存层（size 变化才重建）：outline（createOutline 形状代数）+ 流光边框
        // Brush（颜色与中心都不随旋转角变化 —— 旋转发生在 draw 时的 rotate()
        // 变换里，Brush 本体静态）+ 描边宽度 + 扫掠光带颜色表。动画相位
        //（borderAngle / sheen）只在 onDrawWithContent 里读取，不击穿缓存。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val outline = heroShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    val borderBrush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            scheme.primary.copy(alpha = 0.85f),
                            Color.Transparent,
                            scheme.tertiary.copy(alpha = 0.55f),
                            Color.Transparent
                        ),
                        center = center
                    )
                    val borderStroke = Stroke(width = 1.8.dp.toPx())
                    val sheenColors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.20f),
                        Color.Transparent
                    )
                    val halfBand = size.width * 0.24f
                    onDrawWithContent {
                        drawContent()
                        rotate(degrees = borderAngle.value, pivot = center) {
                            drawOutline(
                                outline = outline,
                                brush = borderBrush,
                                style = borderStroke
                            )
                        }
                        val bandX = sheen.value * size.width
                        drawOutline(
                            outline = outline,
                            brush = Brush.linearGradient(
                                colors = sheenColors,
                                start = Offset(bandX - halfBand, 0f),
                                end = Offset(bandX + halfBand, size.height)
                            )
                        )
                    }
                }
        )
    }
}

/** 极光氛围：顶部主色洗刷 + 三枚 Lissajous 漂移光晕 + 七粒上升微尘。
 *
 *  #262 Compose 最佳实践：Canvas（drawBehind）→ Box + drawWithCache。
 *  光晕的 Lissajous 轨迹只动**中心**，半径只依赖尺寸 —— 渐变 Brush 以
 *  原点为中心缓存，绘制期 translate 到动点（颜色/半径/中心零逐帧分配）。
 *  洗刷渐变整体静态。微尘 alpha 随闪烁动画，保留最小 copy 分配。 */
@Composable
private fun AuroraCanvas(drift: State<Float>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val orbA = scheme.primary.copy(alpha = 0.15f)
    val orbB = scheme.tertiary.copy(alpha = 0.12f)
    val orbC = scheme.secondary.copy(alpha = 0.10f)
    val wash = scheme.primaryContainer.copy(alpha = 0.18f)
    val speckColor = scheme.primary

    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            // ── 缓存层（size 变化才重建）──
            val washBrush = Brush.verticalGradient(
                colors = listOf(wash, Color.Transparent),
                startY = 0f,
                endY = h * 0.55f
            )
            // 光晕渐变原点化：半径只依赖宽度（0.55/0.48/0.42 × w），
            // 中心随 translate 移动 —— Brush 完全静态可缓存。
            val orbABrush = Brush.radialGradient(
                colors = listOf(orbA, Color.Transparent),
                center = Offset.Zero,
                radius = w * 0.55f
            )
            val orbBBrush = Brush.radialGradient(
                colors = listOf(orbB, Color.Transparent),
                center = Offset.Zero,
                radius = w * 0.48f
            )
            val orbCBrush = Brush.radialGradient(
                colors = listOf(orbC, Color.Transparent),
                center = Offset.Zero,
                radius = w * 0.42f
            )
            val orbARadius = w * 0.55f
            val orbBRadius = w * 0.48f
            val orbCRadius = w * 0.42f
            // 微尘半径只有 3 档（i % 3），预换算缓存
            val speckRadii = floatArrayOf(
                1.1f.dp.toPx(),
                1.6f.dp.toPx(),
                2.1f.dp.toPx()
            )
            onDrawBehind {
                val t = drift.value * 2.0 * PI

                // 顶部主色洗刷
                drawRect(brush = washBrush)
                // 三枚漂移光晕（Lissajous 轨迹互不同步，永不重复构图）
                drawOrb(
                    brush = orbABrush,
                    cx = w * (0.80f + 0.09f * sin(t).toFloat()),
                    cy = h * (0.20f + 0.10f * cos(t * 0.7).toFloat()),
                    radius = orbARadius
                )
                drawOrb(
                    brush = orbBBrush,
                    cx = w * (0.14f + 0.08f * sin(t * 1.3 + 1.1).toFloat()),
                    cy = h * (0.74f + 0.12f * cos(t * 0.9).toFloat()),
                    radius = orbBRadius
                )
                drawOrb(
                    brush = orbCBrush,
                    cx = w * (0.50f + 0.15f * sin(t * 0.8 + 2.4).toFloat()),
                    cy = h * (0.45f + 0.16f * cos(t * 1.1).toFloat()),
                    radius = orbCRadius
                )
                // 七粒微尘：上升 + 摇曳 + 闪烁
                repeat(7) { i ->
                    val phase = (drift.value + i * 0.143f) % 1f
                    val y = h * (1.06f - 1.18f * phase)
                    val x = w * (0.08f + 0.84f * ((i * 0.37f) % 1f)) +
                        w * 0.025f * sin(t * 1.6 + i * 1.9).toFloat()
                    val twinkle = 0.5f + 0.5f * sin(t * 2.4 + i * 1.7).toFloat()
                    drawCircle(
                        color = speckColor.copy(alpha = 0.10f + 0.22f * twinkle),
                        radius = speckRadii[i % 3],
                        center = Offset(x, y)
                    )
                }
            }
        }
    )
}

/** 单枚径向光晕 —— 缓存渐变以原点为中心，translate 到动点后绘制。
 *  （与原 `radialGradient(center=Offset(cx,cy))` 逐像素等效：渐变中心与
 *  圆心同经 translate，半径不变。） */
private fun DrawScope.drawOrb(brush: Brush, cx: Float, cy: Float, radius: Float) {
    translate(left = cx, top = cy) {
        drawCircle(
            brush = brush,
            radius = radius,
            center = Offset.Zero
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  区块骨架：一次性交错入场 + 卡片 + 行控件
// ═══════════════════════════════════════════════════════════════

/** 一次性交错入场：淡入 + 轻微上滑，index 越大出现越晚。 */
@Composable
private fun AboutSectionContainer(index: Int, content: @Composable () -> Unit) {
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        delay(110L * index)
        visibleState.targetState = true
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(durationMillis = 380)) +
            slideInVertically(tween(durationMillis = 380)) { it / 6 }
    ) {
        content()
    }
}

/** 标准内容卡：小图标 + 标题 + 主体。 */
@Composable
private fun AboutSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            content()
        }
    }
}

/** Hero 底部的等宽小徽章：主版本高亮，其余中性。 */
@Composable
private fun HeroChip(text: String, emphasized: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 版本/构建信息行：左标签右值（等宽字体）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 功能一览行：圆形图标井 + 标题 + 一句话描述。 */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            Box(
                Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 链接行：图标 + 标签 + 右侧外链角标，整行可点。 */
@Composable
private fun LinkRow(icon: ImageVector, label: String, url: String, open: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open(url) }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Icon(
                Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** Star 引导小卡：琥珀星标，整行可点跳仓库页。 */
@Composable
private fun StarCard(onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = StarAmber)
            Text(
                stringResource(R.string.settings_about_star),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * ACTION_VIEW 打开外链的统一出口：runCatching 兜底极端环境无浏览器 Activity
 * 时不崩溃，Toast 告知（本页与 [UpdatePanel] 共用）。
 */
internal fun openUrl(context: android.content.Context, url: String, noBrowserHint: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, noBrowserHint, Toast.LENGTH_SHORT).show()
    }
}
