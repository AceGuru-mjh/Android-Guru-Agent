package com.apex.agent.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apex.agent.R
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * 全屏图片预览（Lightbox）
 *
 * 体验细节：
 * - 加载态：居中转圈（#227 —— 原实现慢网下纯黑屏无任何反馈）
 * - 失败态：BrokenImage 图标 + 「点击重试」，点击整个区域触发重试
 *   （#227 —— 原实现失败后永远黑屏且无重试入口）
 * - 双指缩放（限制 1x ~ 4x，避免过小/过大）
 * - 单指拖动（仅在 scale > 1 时生效；scale == 1 时拖动直接被点击吞掉以触发关闭）
 * - 双击放大到 2.5x / 双击还原到 1x
 * - 单击空白处关闭
 * - 右上角 × 关闭按钮，带半透明黑色背景 + statusBarsPadding（沉浸式状态栏）
 * - decorFitsSystemWindows = false 让 Dialog 全屏
 *
 * @param imageModel 任意 Coil 支持的 model：Uri / String / File / ByteArray
 * @param onDismiss 关闭回调
 */
@Composable
fun ImageLightbox(
    imageModel: Any,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // #227：重试驱动 —— retryKey 变更触发下方 ImageRequest 重建，
    // Coil 视作新请求重新加载（失败的结果本就不进缓存，重试必然重走网络）。
    var retryKey by remember { mutableStateOf(0) }
    val context = LocalContext.current
    // #227：请求对象随 (imageModel, retryKey) 记忆化重建 —— retryKey 以
    // setParameter 载入参与请求相等性判定，确保重试不被记忆化去重吞掉。
    val request = remember(imageModel, retryKey) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .setParameter("lightbox_retry", retryKey)
            .build()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "Full Image View",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    // 1. 双指缩放与拖动（仅 scale > 1 时拖动生效，避免图片被甩出屏幕）
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = newScale
                            if (newScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                // 缩小回 1x 时重置位移
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    // 2. 双击放大/还原 + 单击关闭
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = { onDismiss() }
                        )
                    },
                contentScale = ContentScale.Fit,
                // #227：加载态 —— 居中转圈（白色对深色遮罩，主题色不适用）
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                },
                // #227：失败态 —— 图标 + 重试提示；点击整个区域重试（成功内容
                // 仍走默认 success 槽，缩放/手势行为不变）。错误内容的 clickable
                // 会消费点击，外层 onTap 不会双触发关闭。
                error = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // 重试同时复位缩放手势状态，避免失败态误触的
                                // 双击放大在重试成功后残留
                                retryKey++
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "图片加载失败，点击重试",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            )

            // 关闭按钮（沉浸式 statusBarsPadding）
            // #271：48dp 触区红线（原 40dp，UI-012 同款修复）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        MaterialTheme.shapes.small
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    // #280：复用既有 chat_cd_close（原硬编码 "关闭"）
                    contentDescription = stringResource(R.string.chat_cd_close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
