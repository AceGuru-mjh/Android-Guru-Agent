package com.apex.agent.browser.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures

/*
 * ChromeDialogSurface —— 浮窗安全的浮层原语（自绘，零窗口依赖）。
 *
 * 为什么不用 AlertDialog / ModalBottomSheet：二者的底层各自创建
 * android.app.Dialog（component Dialog window）——它需要宿主窗口 token；
 * 本 chrome 运行在 WindowManager overlay（app context，无 Activity 窗口），
 * 缺 token 会 BadTokenException。这里全部改为组合层自绘：
 * - [ChromeScrimDialog]：遮罩 + 居中卡片（对应 AlertDialog 的位置）；
 * - [ChromeBottomSheet]：遮罩 + 底部滑入面板（对应 ModalBottomSheet）。
 * 触摸全部在 Compose 组合内消化，不产生任何系统窗口，浮窗内 100% 可靠。
 */

/** scrim 透明度（与浮层体系一致的暗压）。 */
private val ScrimColor = Color.Black.copy(alpha = 0.62f)

/**
 * 居中对话框：点击遮罩关闭（[onDismiss]），卡片内容吞掉点击不外透。
 * 用法骨架与 AlertDialog 对齐：内容在 ColumnScope 里自排。
 */
@Composable
fun ChromeScrimDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnScrimTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScrimColor)
            .pointerInput(dismissOnScrimTap) {
                if (dismissOnScrimTap) detectTapGestures { onDismiss() }
            },
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = chromePalette().surface,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                // 卡片吞掉触摸：避免点内容误触 scrim 的 dismiss
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(min = 64.dp)
                    .imePadding(),
                content = content,
            )
        }
    }
}

/**
 * 底部面板：上半遮罩（点击关闭），下半卡片自底滑入。
 * 内容自己控制最大高度（建议 heightIn(max = ...)），滑动关闭可后续增强。
 */
@Composable
fun ChromeBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetContent: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimColor)
                .statusBarsPadding(),
        ) {
            // 上半遮罩：点击即关
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = chromePalette().surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    content = sheetContent,
                )
            }
        }
    }
}

/** Sheet 顶部的拖拽条（视觉提示可滑动关闭）；在 sheetContent 的 ColumnScope 内调用。 */
@Composable
fun ColumnScope.SheetGrabBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 10.dp)
            .height(4.dp)
            .width(72.dp)
            .align(Alignment.CenterHorizontally)
            .background(
                color = chromePalette().textSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp),
            ),
    )
}
