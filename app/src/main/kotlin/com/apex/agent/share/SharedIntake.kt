package com.apex.agent.share

import android.content.Intent
import android.net.Uri
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分享接收队列 —— 系统分享（ACTION_SEND）进入 App 的唯一中转站（v1.4.4 #7）。
 *
 * ## 数据流
 *
 * ```
 * 其他 App（浏览器/图库/文件管理器）
 *   └─ ACTION_SEND → MainActivity.onNewIntent/onCreate
 *        └─ SharedIntake.offer(intent)   ← 只解析，不落盘
 *             └─ StateFlow<SharedIntake?> ← AgentChatViewModel init 订阅
 *                  ├─ 文本 → 预填输入框草稿（用户可见可改，不自动发送）
 *                  └─ 图片 → attachmentManager.attachImage(uri)
 *                       └─ consume() 清空（一次性语义）
 * ```
 *
 * ## 设计决策
 *
 * - **一次性事件用 StateFlow 而非 SharedFlow**：分享可能在 App 未启动时到达
 *   （冷启动路径 onCreate），SharedFlow 无 replay 会丢事件；StateFlow 持有
 *   最近一条直到被 [consume]，VM 恢复时（进程死亡重建/页面切换）仍能接住。
 * - **不自动发送**：分享进来的内容只做"预填"——文本放输入框、图片挂附件条，
 *   发送键仍由用户按。分享 ≠ 授权发送，这是防御性 UX（避免误触把隐私文本
 *   直接发给 LLM 提供商）。
 * - **URI 权限窗口**：ACTION_SEND 附带的 `FLAG_GRANT_READ_URI_PERMISSION` 只在
 *   接收时有效；AttachmentManager.attachImage 内部会即时把内容拷进 App 沙箱
 *   （MessageAttachment.localPath），所以消费动作必须发生在权限窗口内——
 *   即 VM 订阅后**立即**处理，不等用户点发送。
 * - **多图分享（ACTION_SEND_MULTIPLE）**：当前只接单条 ACTION_SEND；多条
 *   场景系统会把本 App 列为可分享目标之一，进入后取第一张图片并提示用户
 *   单条分享（KDoc 如实记录边界，不静默吞数据）。
 */
@Singleton
class SharedIntake @Inject constructor() {

    /** 一次分享投递的载荷：纯文本 / 单张图片 / 二者兼有。 */
    data class Payload(
        val text: String?,
        val imageUri: Uri?,
        /** 到达时间戳（诊断用：过期载荷可被新投递覆盖）。 */
        val receivedAt: Long = System.currentTimeMillis()
    )

    private val _pending = MutableStateFlow<Payload?>(null)

    /** 待消费的分享载荷；null = 无。新分享覆盖旧的（用户连发两次分享，以最新意图为准）。 */
    val pending: StateFlow<Payload?> = _pending.asStateFlow()

    /**
     * MainActivity onCreate / onNewIntent 调用：解析 ACTION_SEND intent 并入队。
     * 非 ACTION_SEND intent 直接忽略（launch 主路径不误伤）。
     * 任何解析异常静默记日志——分享接收是增益路径，绝不让它崩启动链路。
     */
    fun offer(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        runCatching {
            val text: String? = when (intent.type) {
                null -> null
                "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                else -> null
            }
            val isImageShare = intent.type?.startsWith("image/") == true
            val imageUri: Uri? = if (isImageShare) {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: intent.data
            } else {
                null
            }
            if (text == null && imageUri == null) return
            _pending.value = Payload(text = text, imageUri = imageUri)
            AppLogger.instance.info(
                category = LogCategory.UI,
                source = "SharedIntake",
                message = "share received: text=${text != null} image=${imageUri != null}"
            )
        }.onFailure {
            AppLogger.instance.warn(
                category = LogCategory.UI,
                source = "SharedIntake",
                message = "share parse failed: ${it.message}"
            )
        }
    }

    /**
     * 消费（VM 处理完预填/挂附件后调用）：清空待处理载荷。
     * 幂等：无载荷时是 no-op。
     */
    fun consume() {
        _pending.value = null
    }
}
