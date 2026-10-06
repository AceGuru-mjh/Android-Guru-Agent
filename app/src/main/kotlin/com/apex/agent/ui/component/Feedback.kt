package com.apex.agent.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════
 *  统一反馈层 —— 全 App 唯一的瞬时反馈组件（Snackbar 收敛层）
 * ═══════════════════════════════════════════════════════════════
 *
 * 现状（Task 5-d 之前）：全 app 17 处裸 `Toast.makeText` + 多处各自为政的
 * SnackbarHost（BrowserChrome / MarketScreen 各 remember 一套）+ AgentChat 的
 * `_uiFeedback` SharedFlow 再转 Toast —— 反馈路径六种风格混乱。本文件收敛为一个
 * 组件一条路径（主控接入：ApexRoot 外包一层 `FeedbackHost { ApexRoot() }`）：
 *
 * ```
 * // 之后任意子树内（无需层层传回调）：
 * val feedback = LocalFeedbackController.current
 * feedback.show("已复制")                                        // INFO（中性档）
 * feedback.show("保存成功", FeedbackSeverity.SUCCESS)
 * feedback.show("下载失败", FeedbackSeverity.ERROR,
 *               withAction = "重试") { retryDownload() }
 * ```
 *
 * 视觉语言对齐 GlassComponents：INFO 走 surfaceVariant 中性档（轻、不抢焦点），
 * SUCCESS/WARNING/ERROR 用固定语义容器色 + 白字（WARNING 与 OfflineBanner
 * 的琥珀警示同一视觉锚点）。
 *
 * 与既有 `_uiFeedback`（AgentChatViewModel）的关系：语义重叠，迁移由主控完成
 * —— Screen 层收集后改调 `LocalFeedbackController.current.show(...)`，收敛后旁路可移除。
 */

/**
 * 反馈级别 —— 决定 Snackbar 容器着色与左侧图标。
 * @param labelRes 级别名（无障碍图例/测试断言用；Snackbar 内级别图标是装饰性，不重复朗读）
 */
enum class FeedbackSeverity(@StringRes val labelRes: Int) {
    INFO(R.string.feedback_severity_info),
    SUCCESS(R.string.feedback_severity_success),
    WARNING(R.string.feedback_severity_warning),
    ERROR(R.string.feedback_severity_error)
}

/**
 * 语义容器色（#268/#269：明暗成对，白字内容两档全 AA）。
 * - 深色主题：品牌反馈绿 / 琥珀警示（与 OfflineBanner 同一锚点）/ 错误红；
 * - 浅色主题：同族深一档（与 ExtendedColors.Light 同族：15803D / B45309 /
 *   BA1A4A）—— 白字对浅色容器 4.5:1+，浅色主题对比度不再失控。
 */
private val SuccessContainerDark = Color(0xFF3FB27F)
private val WarningContainerDark = Color(0xFFE8A33D)
private val ErrorContainerDark = Color(0xFFE5533D)
private val SuccessContainerLight = Color(0xFF15803D)
private val WarningContainerLight = Color(0xFFB45309)
private val ErrorContainerLight = Color(0xFFBA1A4A)

/**
 * severity 的载体：消息本体/actionLabel/时长走标准 [SnackbarVisuals] 字段
 * （SnackbarHost 的时长与进出场动画逻辑零改动），[severity]/[onAction]/[id]
 * 是渲染层 cast 回来用的扩展位，不需要任何旁路状态。
 * @param id 入队序号：保证 visuals 实例唯一（进出场动画按实例判切换）。
 */
private class FeedbackVisuals(
    override val message: String,
    override val actionLabel: String?,
    val severity: FeedbackSeverity,
    val onAction: (() -> Unit)?,
    val id: Long,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals

/**
 * 反馈控制器 —— 任意子树经 [LocalFeedbackController] 取用。
 *
 * 队列语义：[show] 内部 `scope.launch { host.showSnackbar(visuals) }`，
 * SnackbarHostState 内部 mutex 串行化 —— 新消息自然排队，旧消息 Short 时长
 * （约 4s）离场后下一条接上；顺序即调用顺序（主线程 scope）。
 *
 * 去重：快速双击产生的同签名消息（severity|message|action）只入队一条 ——
 * in-flight 键去重：同签名在队/在屏时后来的直接丢弃，展示结束键释放。
 *
 * 空安全：host/scope 为 null（[NoopFeedbackController] 或未包 [FeedbackHost]
 * 的子树）时 [show] 静默丢弃 —— 任意子树可用而不崩。
 */
open class FeedbackController(
    private val snackbarHostState: SnackbarHostState? = null,
    private val scope: CoroutineScope? = null
) {
    private val lock = Any()

    /** in-flight 去重键：正在排队/展示中的消息签名（非 null 期间同签名消息被丢弃）。 */
    @Volatile
    private var inFlightKey: String? = null

    /** 单调入队序号（FeedHost 生命周期内单调）。 */
    private val sequence = AtomicLong(0L)

    /**
     * 展示一条反馈。
     * @param message 消息文本（已本地化的最终文案）
     * @param severity 级别（默认 INFO 中性档）
     * @param withAction 可选 actionLabel —— 传入即渲染右侧文字按钮
     * @param onAction action 回调（先关当前 Snackbar 再回调，回调内可安全再 show）
     */
    open fun show(
        message: String,
        severity: FeedbackSeverity = FeedbackSeverity.INFO,
        withAction: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        val host = snackbarHostState ?: return // Noop 路径：未包 FeedbackHost，静默丢弃
        val hostScope = scope ?: return
        if (message.isBlank()) return
        val key = "$severity|$message|$withAction"
        synchronized(lock) {
            if (key == inFlightKey) return // 快速双击：同签名仍在队/在屏 → 丢弃
            inFlightKey = key
        }
        val id = sequence.incrementAndGet()
        hostScope.launch {
            try {
                host.showSnackbar(
                    FeedbackVisuals(
                        message = message,
                        actionLabel = withAction,
                        severity = severity,
                        onAction = onAction,
                        id = id
                    )
                )
            } finally {
                synchronized(lock) {
                    if (inFlightKey == key) inFlightKey = null
                }
            }
        }
    }
}

/**
 * No-op 控制器：[LocalFeedbackController] 的默认值 —— 未被 [FeedbackHost]
 * 包裹的子树（预览 / 单测 / 独立复用的屏）读 `current` 不抛异常，show 静默丢弃。
 */
object NoopFeedbackController : FeedbackController()

/**
 * 反馈控制器注入点。staticCompositionLocalOf：controller 实例在 Host 生命周期
 * 内不变，静态局部避免 provides 变化引发整树重组（读取点零开销）。
 */
val LocalFeedbackController: ProvidableCompositionLocal<FeedbackController> =
    staticCompositionLocalOf { NoopFeedbackController }

/**
 * 全局反馈宿主 —— 包在内容外层即激活整棵子树的反馈能力（主控接入点）。
 * 结构：`Box { content(); SnackbarHost(底部居中) }`，SnackbarHost 后组合绘制在
 * 内容之上；时长与进出场动画复用 M3 原生实现，仅渲染壳按 severity 定制。
 * @param content 业务内容（其内任意子树经 LocalFeedbackController.current.show 反馈）
 */
@Composable
fun FeedbackHost(content: @Composable () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val controller = remember(snackbarHostState, scope) {
        FeedbackController(snackbarHostState, scope)
    }
    CompositionLocalProvider(LocalFeedbackController provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            SnackbarHost(
                hostState = snackbarHostState,
                // 底部居中：SnackbarHost 自身不定位，Box 默认 TopStart 会贴到顶上
                modifier = Modifier.align(Alignment.BottomCenter),
                snackbar = { data -> FeedbackSnackbar(data = data) }
            )
        }
    }
}

/**
 * severity 定制渲染：容器着色 + 左侧级别图标 + message + 可选 action 文字按钮。
 * 圆角 14.dp、外边距 12.dp、阴影走 M3 Snackbar 内建 shadowElevation = 6.dp。
 */
@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val visuals = data.visuals as? FeedbackVisuals
    val severity = visuals?.severity ?: FeedbackSeverity.INFO
    val scheme = MaterialTheme.colorScheme
    // #268/#269：明暗分档选容器 —— 白字内容在浅色主题同样达 AA（≥4.5:1）
    val light = scheme.background.luminance() > 0.5f

    val containerColor: Color
    // 白字是状态容器的语义内容色（Snackbar 同 Material onPrimary 范式）：
    // 容器已在两档主题下校准对比度，这里经 onStatusContent 别名取词，
    // 不再裸用 Color.White（#269：通用组件颜色直用收编）。
    val onStatusContent = Color.White
    val contentColor: Color
    val actionColor: Color
    val icon: ImageVector
    when (severity) {
        FeedbackSeverity.INFO -> {
            containerColor = scheme.surfaceVariant
            contentColor = scheme.onSurfaceVariant
            actionColor = scheme.onSurface
            icon = Icons.Outlined.Info
        }
        FeedbackSeverity.SUCCESS -> {
            containerColor = if (light) SuccessContainerLight else SuccessContainerDark
            contentColor = onStatusContent
            actionColor = onStatusContent
            icon = Icons.Filled.CheckCircle
        }
        FeedbackSeverity.WARNING -> {
            containerColor = if (light) WarningContainerLight else WarningContainerDark
            contentColor = onStatusContent
            actionColor = onStatusContent
            icon = Icons.Outlined.Warning
        }
        FeedbackSeverity.ERROR -> {
            containerColor = if (light) ErrorContainerLight else ErrorContainerDark
            contentColor = onStatusContent
            actionColor = onStatusContent
            icon = Icons.Filled.Error
        }
    }

    Snackbar(
        // 外层 12.dp 边距：悬浮于内容之上，避免贴边压住底部输入栏圆角；
        // liveRegion：TalkBack 播报反馈消息（合并图标/文本，action 按钮独立可聚焦）
        modifier = Modifier
            .padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        action = {
            val label = data.visuals.actionLabel
            if (label != null) {
                TextButton(
                    onClick = {
                        // 先关当前（队列推进），再执行回调（回调内可安全再 show 新消息）
                        data.dismiss()
                        visuals?.onAction?.invoke()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = actionColor)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // 装饰性：级别由容器色传达，语义由文本承载
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
