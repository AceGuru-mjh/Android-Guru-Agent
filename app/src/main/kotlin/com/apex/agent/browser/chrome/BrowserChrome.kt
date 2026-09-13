package com.apex.agent.browser.chrome

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/*
 * BrowserChrome —— 浮窗展开态的完整浏览器外观（root composable）。
 *
 * 结构：
 *   Column
 *   ├─ ChromeBar          地址栏（即进度条）+ 导航键 + 菜单
 *   ├─ handoffBanner      人工接管横幅（可选 slot，显式握手模式用）
 *   ├─ TabStrip           标签条（单标签自动隐藏）
 *   └─ Box(weight=1f)
 *      ├─ pageSlot()      ← 引擎的 WebView 容器，原封不动放进来
 *      ├─ FindBar         页内查找（底部）
 *      └─ DownloadShelf   下载进度浮签（左下）
 *   浮层：标签总览 / 下载面板 / 用户脚本面板 / JS 弹窗 / 权限弹窗 / Snackbar
 *
 * 自带 MaterialTheme（暗色）与 CompositionLocal 调色板，宿主浮窗无需任何主题包装。
 */
@Composable
fun BrowserChrome(
    gateway: BrowserEngineGateway,
    modifier: Modifier = Modifier,
    config: ChromeConfig = ChromeConfig(),
    palette: ChromePalette = ChromePalette.neon(),
    scripts: UserscriptRegistry? = null,
    controller: BrowserChromeController = remember(gateway, config, scripts) {
        BrowserChromeController(gateway, scripts, config)
    },
    /** 人工接管横幅（显式握手 WAITING_HUMAN 时宿主注入；null 则不渲染）。 */
    handoffBanner: (@Composable () -> Unit)? = null,
    /** chrome 头部实际高度变化回调（宿主用它给 WebView 容器留白，防止遮挡）。 */
    onChromeHeightChanged: ((Int) -> Unit)? = null,
    pageSlot: @Composable () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(controller) { controller.start(scope) }
    DisposableEffect(controller) {
        onDispose { controller.stop() }
    }

    // 系统返回键：编辑态 → 查找栏 → 浮层，逐级让位；都关着则交还宿主。
    // 浮窗 ComposeView 没挂 OnBackPressedDispatcherOwner（Activity 才有）时
    // 直接不注册，避免任何窗口层级差异下的运行时异常。
    val hasBackDispatcher = LocalOnBackPressedDispatcherOwner.current != null
    val backConsumable = (ui.address is AddressBarUi.Editing ||
        ui.findBarVisible || ui.sheet != null) && hasBackDispatcher
    BackHandler(enabled = backConsumable) { controller.handleBack() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(controller) {
        controller.snacks.collect { snack ->
            val result = snackbarHostState.showSnackbar(snack.message, snack.actionLabel)
            if (result == SnackbarResult.ActionPerformed) snack.onAction?.invoke()
        }
    }

    val colorScheme = remember(palette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color(0xFF00202A),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.stroke,
            secondary = palette.accentAlt,
        )
    }

    CompositionLocalProvider(LocalChromePalette provides palette) {
        MaterialTheme(colorScheme = colorScheme) {
            Column(modifier = modifier.fillMaxSize().background(palette.background)) {

                // chrome 头部（地址栏 + 可选接管横幅 + 标签条）：宿主据实际高度给 WebView 留白
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            onChromeHeightChanged?.invoke(coords.size.height)
                        }
                ) {
                    ChromeBar(
                        ui = ui,
                        controller = controller,
                        onCopyLink = {
                            val url = controller.currentUrl()
                            if (url != null) {
                                clipboard.setText(AnnotatedString(url))
                                controller.showSnack("链接已复制")
                            } else {
                                controller.showSnack("当前无页面链接")
                            }
                        },
                    )

                    handoffBanner?.invoke()

                    AnimatedVisibility(visible = ui.tabStripVisible) {
                        TabStrip(ui = ui, controller = controller)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 2.dp),
                ) {
                    pageSlot()

                    FindBar(
                        visible = ui.findBarVisible,
                        findState = ui.findState,
                        controller = controller,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    DownloadShelf(
                        gateway = gateway,
                        controller = controller,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }

                SnackbarHost(hostState = snackbarHostState)
            }

            // ---- 浮层组 ----
            TabSwitcherSheet(ui = ui, controller = controller)
            DownloadsSheet(ui = ui, gateway = gateway, controller = controller)
            UserscriptsSheet(
                visible = ui.sheet == ChromeSheet.SCRIPTS && scripts != null,
                registry = scripts,
                onDismiss = { controller.closeSheet() },
            )
            JsDialogHost(gateway = gateway, config = config)
            PermissionRequestHost(gateway = gateway)
        }
    }
}
