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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
 *  3. 版本与构建（BuildConfig 真实值，行间发丝分隔线）；
 *  4. 功能一览（六大能力，图标 + 一句话，三色强调轮转）；
 *  5. 技术栈（真实运行时依赖清单 → FlowRow 等宽徽章墙）；
 *  6. 相关链接（源码 / 发布通道 / 问题反馈）；
 *  7. Star 引导（渐变 CTA + 星标脉冲）+ 致谢页脚。
 *
 * 各区块入场为一次性交错动画（[AboutSectionContainer]）；Hero 的常驻
 * 动画全部走「State 在 draw 作用域读取」模式 —— 零重组，仅重绘对应图层。
 *
 * #262（draw 每帧分配修复）：流光边框/镜面扫掠覆盖层由 drawWithContent
 * 改 **drawWithCache** —— Outline 与两把 Brush 只在尺寸变化时重建，
 * 动画相位在 draw 作用域读取，每帧零分配（旧实现每帧 createOutline +
 * sweepGradient + linearGradient 三连分配）。呼吸光晕同理把 Brush 提升
 * 到 remember，每帧只改半径。
 */

/** 项目仓库地址 —— 关于页所有外链的基地址。 */
private const val REPO_URL = "https://github.com/Ultra-Guru/Android-Guru-Agent"

/** 发布仓库（双仓库发布架构的产物存放处）。 */
private const val RELEASE_URL = "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases"

/** Star 引导行图标色：琥珀色，与 M3 主色拉开距离，白/深底上都醒目。 */
private val StarAmber = Color(0xFFF59E0B)

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val noBrowserHint = stringResource(R.string.settings_about_no_browser)
    val open: (String) -> Unit = { url -> openUrl(context, url, noBrowserHint) }

    // ═══ 彩蛋（v1.4.8 美化）：连点页脚机器人 5 次 → 粒子烟花 ═══
    // 触发键每次自增（同键多次触发也能重放）；toast 与烟花同发。
    var fireworksTrigger by remember { mutableStateOf(0) }
    var logoTaps by remember { mutableStateOf(0) }
    val eggToast = stringResource(R.string.about_easter_egg_toast)
    fun tapLogo() {
        logoTaps++
        if (logoTaps >= 5) {
            logoTaps = 0
            fireworksTrigger++
            Toast.makeText(context, eggToast, Toast.LENGTH_SHORT).show()
        }
    }

    // Hero 可见性门控：页面非 lazy 滚动，滚出视口后无限动画仍在逐帧
    // invalidate + 重录（含 backdrop 采样重绘）——CPU 空转。Hero 滚出
    // 视口 1.5 倍高度后切静态降级帧，滚回自动恢复
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val heroGatePx = with(density) { 252.dp.toPx() * 1.5f }
    val heroVisible by remember(heroGatePx) {
        derivedStateOf { scrollState.value < heroGatePx }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AboutHero(animated = heroVisible)

        // ── 本版亮点（v1.4.8 美化：进页第一眼的价值摘要，先于更新面板）──
        AboutSectionContainer(index = 1) { HighlightsCard() }

        // ── 软件更新（增量补丁 + 高速节点镜像）──
        AboutSectionContainer(index = 8) { UpdatePanel() }

        // ── 版本与构建 ──
        AboutSectionContainer(index = 8) {
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

        // ── 功能一览（三色强调轮转：primary → tertiary → secondary）──
        AboutSectionContainer(index = 8) {
            AboutSectionCard(
                title = stringResource(R.string.about_section_features),
                icon = Icons.Outlined.Widgets
            ) {
                val accentPrimary = MaterialTheme.colorScheme.primary
                val accentTertiary = MaterialTheme.colorScheme.tertiary
                val accentSecondary = MaterialTheme.colorScheme.secondary
                val featureAccents = listOf(accentPrimary, accentTertiary, accentSecondary)
                FeatureRow(
                    Icons.Default.SmartToy,
                    stringResource(R.string.about_feature_agent_title),
                    stringResource(R.string.about_feature_agent_desc),
                    accent = featureAccents[0]
                )
                FeatureRow(
                    Icons.Outlined.Code,
                    stringResource(R.string.about_feature_coding_title),
                    stringResource(R.string.about_feature_coding_desc),
                    accent = featureAccents[1]
                )
                FeatureRow(
                    Icons.Outlined.Terminal,
                    stringResource(R.string.about_feature_terminal_title),
                    stringResource(R.string.about_feature_terminal_desc),
                    accent = featureAccents[2]
                )
                FeatureRow(
                    Icons.Outlined.Storefront,
                    stringResource(R.string.about_feature_market_title),
                    stringResource(R.string.about_feature_market_desc),
                    accent = featureAccents[0]
                )
                FeatureRow(
                    Icons.Outlined.Lock,
                    stringResource(R.string.about_feature_vault_title),
                    stringResource(R.string.about_feature_vault_desc),
                    accent = featureAccents[1]
                )
                FeatureRow(
                    Icons.Outlined.BlurOn,
                    stringResource(R.string.about_feature_glass_title),
                    stringResource(R.string.about_feature_glass_desc),
                    accent = featureAccents[2]
                )
            }
        }

        // ── 技术栈（依赖清单 → 等宽徽章墙）──
        AboutSectionContainer(index = 8) {
            TechStackCard()
        }

        // ── 相关链接 ──
        AboutSectionContainer(index = 8) {
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
        AboutSectionContainer(index = 8) {
            StarCard(onOpen = { open(REPO_URL) })
        }

        // ── 致谢 + 页脚 ──
        AboutSectionContainer(index = 8) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 低透明度 Logo 水印：页脚的轻量收束（连点 5 次 = 烟花彩蛋）
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = stringResource(R.string.about_highlights_hint),
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = { tapLogo() })
                )
                Text(
                    stringResource(R.string.settings_about_credits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                // 彩蛋提示（低透明度，可发现的线索）
                Text(
                    stringResource(R.string.about_highlights_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // ═══ 彩蛋烟花层：全屏 Canvas，不拦截触摸（点击穿透继续可用）═══
    FireworksOverlay(trigger = fireworksTrigger)
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃 Hero —— 顶阶动态四件套
//  ① 极光漂移：三枚 Lissajous 光晕 + 七粒微尘（layerBackdrop 采样源）
//  ② 流光边框：绕心旋转的锥形渐变描边（Linear / Vercel 质感）
//  ③ 镜面扫掠：斜向光带周期性掠过卡面
//  ④ 呼吸光晕：Logo 后主色光环脉动
//
//  性能：四路动画全部以 State<Float> 传入 draw 作用域读取 ——
//  Hero 子树零逐帧重组，每帧仅重绘对应图层（Canvas / drawBehind）。
//  #262：②③的 Outline/Brush 走 drawWithCache（尺寸变化才重建）；
//  ④的 Brush 提升到 remember，每帧只改半径。
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AboutHero(modifier: Modifier = Modifier, animated: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    // 极简黑白风格：装饰动画层（极光 / 流光边框 / 镜面扫掠 / 呼吸光晕）
    // 整体停用 —— 静音克制是风格语义的一部分；Hero 卡本身由 GlassSurface
    // 的风格短路分支自动扁平化。
    val minimal = com.apex.agent.ui.theme.LocalUiStyle.current ==
        com.apex.agent.ui.theme.UiStyle.MINIMAL
    val heroBackdrop = rememberLayerBackdrop()
    val heroShape = RoundedCornerShape(22.dp)

    val drift: State<Float>
    val borderAngle: State<Float>
    val breath: State<Float>
    val sheen: State<Float>
    if (animated && !minimal) {
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

    // #262：呼吸光晕的渐变 Brush 提升出 draw 作用域 —— 颜色 stops 固定，
    // 脉动只改半径（半径是 drawCircle 的参数而非 Brush 内部状态）。
    val breathBrush = remember(scheme.primary) {
        Brush.radialGradient(
            colors = listOf(scheme.primary.copy(alpha = 0.42f), Color.Transparent)
        )
    }

    Box(modifier = modifier.fillMaxWidth().height(252.dp)) {
        // ① 极光氛围层 —— 同时是 Hero 卡的 backdrop 采样源（API 32+ 真实折射）；
        //    极简风格下退化为纯色底（装饰是玻璃材质语言的配套件）
        if (minimal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background)
            )
        } else {
            AuroraCanvas(
                drift = drift,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(heroBackdrop)
            )
        }

        // ② 玻璃卡主体（Backdrop 档：实时采样背后的极光）
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            backdrop = heroBackdrop,
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
                    // ④ 呼吸光晕（极简风格下不绘制 —— 装饰性光环）
                    if (!minimal) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .drawBehind {
                                    val b = breath.value
                                    val r = size.minDimension * (0.55f + 0.12f * b)
                                    drawCircle(
                                        brush = breathBrush,
                                        radius = r,
                                        center = center
                                    )
                                }
                        )
                    }
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
                    letterSpacing = 0.12.em,
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

        // ③ 顶阶动态覆盖层：旋转流光边框 + 对角镜面扫掠（极简风格下整体省略）
        //    #262：drawWithCache —— Outline / 锥形描边 Brush / 光带 Brush
        //    均只在尺寸变化时构建一次；动画相位（borderAngle / sheen）在
        //    onDrawWithContent 里读取，属 draw 阶段读 State —— 只触发重绘，
        //    不重建缓存、不触发重组（旧实现每帧三连分配）。
        //    光带的移动改为 translate 平移固定 Brush（几何只依赖 bandX 的
        //    水平位移，与旧实现逐帧重建 linearGradient 几何等价）。
        if (!minimal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                    val outline = heroShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    // CacheDrawScope 只实现 Density（无 DrawScope.center），
                    // 锥形渐变中心由 size 显式推导
                    val gradientCenter = Offset(size.width / 2f, size.height / 2f)
                    val borderBrush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            scheme.primary.copy(alpha = 0.85f),
                            Color.Transparent,
                            scheme.tertiary.copy(alpha = 0.55f),
                            Color.Transparent
                        ),
                        center = gradientCenter
                    )
                    // 规范位（bandX=0）的光带渐变；动画只平移不重建
                    val sheenBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        start = Offset(-size.width * 0.24f, 0f),
                        end = Offset(size.width * 0.24f, size.height)
                    )
                    val borderStroke = Stroke(width = 1.8.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        rotate(degrees = borderAngle.value, pivot = center) {
                            drawOutline(
                                outline = outline,
                                brush = borderBrush,
                                style = borderStroke
                            )
                        }
                        translate(left = sheen.value * size.width) {
                            drawOutline(outline = outline, brush = sheenBrush)
                        }
                    }
                }
            )
        }
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

/** 标准内容卡：小图标 + 标题 + 主体（标题右侧发丝收尾线）。 */
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
                // 标题后的发丝收尾线：给每个区块头一个轻量的视觉锚点
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                ) {
                    HorizontalDivider()
                }
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

/** 版本/构建信息行：左标签右值（等宽字体），行间发丝分隔。 */
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

/** 功能一览行：圆形图标井 + 标题 + 一句话描述（[accent] 三色轮转注入活力）。 */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = accent.copy(alpha = 0.12f)
        ) {
            Box(
                Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accent
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

/**
 * 技术栈徽章墙：把「开源组件」清单（· 分隔的单一 string 资源）拆成
 * FlowRow 等宽徽章 —— 相比旧版一整段纯文本，信息可扫读、密度可控，
 * 且窄屏/宽屏都自然换行。三色低饱和轮转描边与功能行同语言。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechStackCard() {
    val raw = stringResource(R.string.settings_about_components_list)
    val items = remember(raw) {
        raw.split("·").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val accentPrimary = MaterialTheme.colorScheme.primary
    val accentTertiary = MaterialTheme.colorScheme.tertiary
    val accentSecondary = MaterialTheme.colorScheme.secondary
    val accents = listOf(accentPrimary, accentTertiary, accentSecondary)

    AboutSectionCard(
        title = stringResource(R.string.about_section_stack),
        icon = Icons.Outlined.Layers
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { i, name ->
                StackChip(name = name, accent = accents[i % accents.size])
            }
        }
    }
}

/** 单枚技术栈徽章：等宽小字 + 低饱和底 + 同色系描边。 */
@Composable
private fun StackChip(name: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = accent.copy(alpha = 0.10f),
        modifier = Modifier.drawBehind {
            drawRoundRect(
                color = accent.copy(alpha = 0.28f),
                style = Stroke(width = 0.8.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx())
            )
        }
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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

/**
 * Star 引导 CTA：主色→第三色对角渐变底 + 星标脉冲 + 行尾箭头。
 * 相比旧版的中性 surfaceVariant 小卡，这一版把「求 Star」的视觉
 * 优先级提到与功能卡同档（整页唯一的渐变 CTA，语义即行动号召）。
 */
@Composable
private fun StarCard(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 星标脉冲：1f → 1.12f 缓动往返（低频率，视觉克制）
    val pulse = rememberInfiniteTransition(label = "star_pulse")
    val starScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "star_scale"
    )
    val gradStart = scheme.primary.copy(alpha = 0.20f)
    val gradEnd = scheme.tertiary.copy(alpha = 0.20f)
    val strokeColor = scheme.primary.copy(alpha = 0.35f)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .drawBehind {
                // 对角渐变 CTA 底 + 半透明主色描边
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(gradStart, gradEnd),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = CornerRadius(14.dp.toPx())
                )
                drawRoundRect(
                    color = strokeColor,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(14.dp.toPx())
                )
            }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = StarAmber,
                modifier = Modifier.size(
                    (26 * starScale).dp
                )
            )
            Text(
                stringResource(R.string.settings_about_star),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = scheme.primary
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

// ═══════════════════════════════════════════════════════════════
//  v1.4.8 美化新增：本版亮点卡 + 彩蛋烟花层
// ═══════════════════════════════════════════════════════════════

/**
 * 本版亮点卡 —— 进页第一眼的价值摘要（先于更新面板）。
 *
 * 版本徽章（等宽字）+ 四条亮点（三色强调轮转的圆点 + 一句话）；
 * 亮点文案是「发版 PR 更新 strings」的轻量惯例（与仓库既有
 * strings 演进纪律一致），无远端依赖、离线可读。
 */
@Composable
private fun HighlightsCard() {
    val scheme = MaterialTheme.colorScheme
    val accents = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    val highlights = listOf(
        R.string.about_highlight_1,
        R.string.about_highlight_2,
        R.string.about_highlight_3,
        R.string.about_highlight_4
    )
    AboutSectionCard(
        title = stringResource(R.string.about_section_highlights),
        icon = Icons.Outlined.AutoAwesome
    ) {
        // 版本徽章行：发丝分隔线上方的当前版本胶囊
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.primary.copy(alpha = 0.12f)
        ) {
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        highlights.forEachIndexed { i, res ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 3.dp)
            ) {
                // 亮点圆点：三色强调轮转（与功能卡同一节奏）
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .drawBehind {
                            drawCircle(color = accents[i % accents.size])
                        }
                )
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 彩蛋烟花层 —— trigger 自增触发一轮 ~1.9s 的粒子庆祝。
 *
 * 实现纪律（与 Hero 同款）：
 * - 粒子表按 trigger 键 remember（每次触发换一批随机初速/色相），
 *   动画相位走 Animatable 单值驱动 Canvas 重绘 —— 零逐帧重组；
 * - 72 粒 × 径向爆散 + 重力下坠 + 尾段淡出；色相取主题三强调色
 *   + StarAmber 旋转，深浅主题下都醒目；
 * - 播放结束自动隐藏（progress >= 1 不再绘制，层不可见也不拦截
 *   触摸 —— 指针穿透，页面照常可用）。
 */
@Composable
private fun FireworksOverlay(trigger: Int) {
    if (trigger <= 0) return
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, StarAmber)
    }
    // 粒子表：角度/初速/半径抖动/色相序号 —— trigger 变化即换一批
    val particles = remember(trigger) {
        List(72) { i ->
            val angle = (i / 72f) * 2f * PI.toFloat() + (i % 7) * 0.13f
            val speed = 0.55f + (i % 11) / 17f  // 0.55..1.08 归一速度
            Triple(angle, speed, i and 3)
        }
    }
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1900, easing = LinearEasing))
    }
    if (progress.value >= 1f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = progress.value
        // 中心 = 画面中上三分之一（烟花在视野里而非边角）
        val cx = size.width / 2f
        val cy = size.height / 3.2f
        val maxR = size.minDimension * 0.52f
        // 淡出：前 70% 满亮，尾段线性衰减
        val alpha = when {
            t < 0.7f -> 1f
            else -> 1f - (t - 0.7f) / 0.3f
        }
        for ((angle, speed, colorIdx) in particles) {
            // 出膛快、后段减速（ease-out 轨迹）+ 轻微重力下坠
            val eased = 1f - (1f - t).let { it * it }
            val r = maxR * speed * eased
            val gravity = 36f * t * t
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle) + gravity
            // 粒子大小随速度差异化，尾段缩小
            val radius = (4.5f - speed * 2.2f) * (1f - t * 0.4f) * density
            drawCircle(
                color = palette[colorIdx].copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = radius.coerceAtLeast(1.2f * density),
                center = Offset(x, y)
            )
        }
    }
}
