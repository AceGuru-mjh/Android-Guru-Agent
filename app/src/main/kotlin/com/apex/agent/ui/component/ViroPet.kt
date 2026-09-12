package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

// ═══════════════════════════════════════════════════════════════════════════
// Viro 桌宠 —— 聊天输入栏上方的像素风病毒吉祥物
// ═══════════════════════════════════════════════════════════════════════════
//
// 素材来源：viro-pet 模型（pet.json 逐像素数据），重排为统一网格 sprite sheet：
//   res/drawable-nodpi/viro_pet_sheet.webp —— 1664×2376，
//   9 行（动画）× 8 列（帧），单元格 208×264（2x 像素画缩放）。
// 行序与 pet.json framesData 一致：idle / running-right / running-left /
// waving / jumping / failed / waiting / running / review。
// 所有帧按"脚底基线"对齐（单元格底部 y=260），保留作者 encoded 的帧内位移
//（jumping 的腾空弧线即来自帧内相对位移）。
//
// 动画状态由聊天运行态驱动（AgentChatScreen 侧映射 ViroPetMood），
// 宠物始终位于输入栏正上方、独占一行布局槽位 —— 不遮挡任何消息内容或功能。
// ═══════════════════════════════════════════════════════════════════════════

/** sprite sheet 布局常量（与生成脚本保持一致）。 */
private const val VIRO_SHEET_FRAMES = 8
private const val VIRO_CELL_WIDTH = 208
private const val VIRO_CELL_HEIGHT = 264

/**
 * Viro 的 9 种形态（动画）。
 *
 * @param row sprite sheet 中的行索引
 * @param frameMillis 每帧停留时长（控制动画节奏：跑动快、待机慢）
 */
enum class ViroPetAnim(val row: Int, val frameMillis: Long) {
    Idle(0, 120),
    RunningRight(1, 70),
    RunningLeft(2, 70),
    Waving(3, 110),
    Jumping(4, 90),
    Failed(5, 150),
    Waiting(6, 170),
    Running(7, 75),
    Review(8, 130),
}

/**
 * 由 Agent 聊天运行态推导的宠物情绪（AgentChatScreen 侧负责映射）。
 *
 * @param label 状态小标签文案；null 表示待机（不打扰）
 */
enum class ViroPetMood(val label: String?) {
    Idle(null),
    Thinking("思考中"),
    ToolRunning("执行工具"),
    Streaming("回复中"),
    WaitingUser("等待输入"),
    ReviewPlan("待确认"),
    Error("出错了"),
    Success("任务完成"),
}

/**
 * 单帧循环播放指定动画的 Viro 宠物（纯渲染，无状态推导）。
 *
 * 帧计数按 [anim] 重置（remember(anim)），切形态时从第 0 帧开始；
 * 时钟用 LaunchedEffect + delay 实现（像素画 8~14fps，开销可忽略）。
 */
@Composable
fun ViroPet(
    anim: ViroPetAnim,
    modifier: Modifier = Modifier
) {
    val sheet: ImageBitmap = ImageBitmap.imageResource(R.drawable.viro_pet_sheet)
    var frame by remember(anim) { mutableStateOf(0) }
    LaunchedEffect(anim) {
        while (true) {
            delay(anim.frameMillis)
            frame = (frame + 1) % VIRO_SHEET_FRAMES
        }
    }
    Canvas(modifier = modifier) {
        val dstHeight = size.height
        if (dstHeight <= 0f) return@Canvas
        // 保持单元格宽高比（208:264），在给定高度内等比缩放、水平居中
        val dstWidth = dstHeight * (VIRO_CELL_WIDTH.toFloat() / VIRO_CELL_HEIGHT)
        val dstX = ((size.width - dstWidth) / 2f).roundToInt()
        drawImage(
            image = sheet,
            srcOffset = IntOffset(frame * VIRO_CELL_WIDTH, anim.row * VIRO_CELL_HEIGHT),
            srcSize = IntSize(VIRO_CELL_WIDTH, VIRO_CELL_HEIGHT),
            dstOffset = IntOffset(dstX, 0),
            dstSize = IntSize(dstWidth.roundToInt(), dstHeight.roundToInt()),
            filterQuality = FilterQuality.Low
        )
    }
}

/**
 * Viro 宠物宿主：情绪 → 动画映射 + 瞬态演出 + 点击彩蛋 + 状态小标签。
 *
 * 挂载于聊天主 Column 输入栏上方（固定 56dp 高的独占行），因此永远不会
 * 覆盖消息列表或输入栏内的任何控件。
 *
 * 瞬态演出（自动回落到情绪基线动画）：
 * - [chatEmpty] 变 true（新会话）：挥手打招呼 ~2s
 * - [celebrationKey] 变化（RunSummary 出现）：跳跃庆祝 ~2.2s
 * - 点击宠物：随机跳跃/挥手 ~1.4s
 *
 * @param mood 聊天运行态推导的情绪（屏幕侧负责映射）
 * @param chatEmpty 当前会话是否为空（新会话打招呼）
 * @param celebrationKey 庆祝触发键（RunSummary 消息 id；null 不触发）
 */
@Composable
fun ViroPetHost(
    mood: ViroPetMood,
    chatEmpty: Boolean,
    celebrationKey: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var overrideAnim by remember { mutableStateOf<ViroPetAnim?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }

    /** 播放一段瞬态动画后自动回落（后播覆盖先播）。 */
    fun play(anim: ViroPetAnim, durationMillis: Long) {
        playJob?.cancel()
        playJob = scope.launch {
            overrideAnim = anim
            delay(durationMillis)
            overrideAnim = null
        }
    }

    // 新会话（消息清空）→ 挥手打招呼
    LaunchedEffect(chatEmpty) {
        if (chatEmpty) play(ViroPetAnim.Waving, durationMillis = 2_000)
    }
    // RunSummary 出现 → 跳跃庆祝（按消息 id 触发，每轮任务只演一次）
    LaunchedEffect(celebrationKey) {
        if (celebrationKey != null) play(ViroPetAnim.Jumping, durationMillis = 2_200)
    }

    // 情绪基线 → 动画形态
    val baseAnim = when (mood) {
        ViroPetMood.Idle -> ViroPetAnim.Idle
        ViroPetMood.Thinking -> ViroPetAnim.Review
        ViroPetMood.ToolRunning -> ViroPetAnim.Running
        ViroPetMood.Streaming -> ViroPetAnim.RunningRight
        ViroPetMood.WaitingUser -> ViroPetAnim.Waiting
        ViroPetMood.ReviewPlan -> ViroPetAnim.Review
        ViroPetMood.Error -> ViroPetAnim.Failed
        ViroPetMood.Success -> ViroPetAnim.Idle
    }
    val anim = overrideAnim ?: baseAnim

    // 点击反馈：按压微放大（与发送键的 press-scale 一致的手感）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val petScale by animateFloatAsState(
        targetValue = if (pressed) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.45f),
        label = "viro_pet_press_scale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // 宠物本体：56dp 触控热区（无障碍标准），绘制约 44×56dp（保持单元格宽高比）
        Box(
            modifier = Modifier
                .padding(start = 12.dp) // 与消息列表 horizontal padding 对齐
                .size(56.dp)
                .scale(petScale)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "和 Viro 玩"
                ) {
                    play(
                        if (Random.nextBoolean()) ViroPetAnim.Jumping else ViroPetAnim.Waving,
                        durationMillis = 1_400
                    )
                }
                .semantics { contentDescription = "Viro 桌宠（当前状态：${mood.label ?: "待机"}）" }
        ) {
            ViroPet(
                anim = anim,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 状态小标签：非待机时展开（错误态换红色调），与输入栏工具 chip 同级轻量
        AnimatedVisibility(
            visible = mood.label != null,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            val isError = mood == ViroPetMood.Error
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            ) {
                Text(
                    text = mood.label ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
