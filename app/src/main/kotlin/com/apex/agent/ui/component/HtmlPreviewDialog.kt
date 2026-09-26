package com.apex.agent.ui.component

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apex.agent.R
import java.io.File

/**
 * 应用内 HTML 预览对话框（Agent 产物即时可视化）。
 *
 * 背景：Agent 常产出 HTML 单页（报告 / 可视化 / 原型 / Canvas 演示）。旧路径只有
 * FileProvider → 外部浏览器（冷启动 1-3s + 跳出 App）。本组件用**应用内 WebView**：
 * - 应用进程内的 WebView 内核随首次打开完成预热，本地文件渲染 <100ms 量级；
 * - `loadDataWithBaseURL(父目录)` 加载内容 —— 相对路径资源（./style.css、img/）
 *   经 baseUrl 正常解析，且无 file:// scheme 白名单问题；
 * - 允许 JS / DOM storage（绝大多数生成物是交互式页面）；
 * - 外链 http(s) 放行（CDN 资源可用），file:// 跨目录导航拦截（防沙箱逃逸）。
 *
 * 性能要点（“加载速度要快”）：
 * - 不 clearCache、不 clearFormData —— 会话内内核复用；
 * - cacheMode = LOAD_DEFAULT：CDN 资源命中缓存直接回显；
 * - offscreenPreRaster：分屏/后台窗口恢复无重绘闪烁。
 */
@Composable
fun HtmlPreviewDialog(
    filePath: String,
    onDismiss: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    val title = remember(filePath) { file.name }
    var reloadTick by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 标题栏：文件名 + 刷新 + 关闭 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    val refreshCd = stringResource(R.string.html_preview_refresh)
                    IconButton(onClick = { reloadTick++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = refreshCd,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    val closeCd = stringResource(R.string.html_preview_close)
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = closeCd,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // ── WebView 内容区（reloadTick 变化触发 update 重载；key(filePath)
                // 保证切换预览文件时重建 WebView —— client 捕获的 base 目录同步刷新）──
                androidx.compose.runtime.key(filePath) {
                    HtmlWebView(
                        file = file,
                        reloadTick = reloadTick,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    // 系统返回键关闭预览（Dialog 默认已支持；显式声明防御 usePlatformDefaultWidth
    // 下部分厂商 ROM 的焦点转移异常）。
    BackHandler(onBack = onDismiss)
}

/** WebView 宿主：读文件 → loadDataWithBaseURL（快 + 相对资源可用）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlWebView(
    file: File,
    reloadTick: Int,
    modifier: Modifier = Modifier
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // baseUrl = 父目录：相对引用（./x.js、images/y.png、子页面 a 链接）可解析。
    val baseUrl = remember(file) {
        file.parentFile?.absolutePath?.let { "file://$it/" } ?: "about:blank"
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 交互式生成物需要 JS + DOM storage。
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // 本地文件 + CDN 混合内容：允许两者（生成物常见 <script src="https://cdn...">）。
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = true
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 窗口恢复时直接用离屏栅格化位图，避免闪烁重绘。
                    @Suppress("DEPRECATION")
                    settings.offscreenPreRaster = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url ?: return false
                            val scheme = url.scheme ?: return false
                            if (!scheme.equals("file", true)) {
                                // http(s) 外链：放行（CDN 资源 / 跳转链接）。
                                return false
                            }
                            // file:// 导航：仅放行同目录内页面（多页生成物可互相跳转），
                            // 跨目录拦截（防越出预览目录读取沙箱其它文件）。
                            return !url.toString().startsWith(baseUrl, ignoreCase = true)
                        }
                    }
                    webChromeClient = WebChromeClient()
                }
            },
            update = { webView ->
                // 初次组合与手动刷新（reloadTick 变化）时（重新）加载。
                loading = true
                loadError = null
                val html = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (html == null) {
                    loadError = file.absolutePath
                    loading = false
                } else {
                    webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center)
            )
        }
        loadError?.let { path ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.html_preview_load_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
