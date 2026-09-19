package com.apex.agent.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import coil.compose.AsyncImage

/**
 * 聊天气泡内联 Markdown 渲染器。
 * 终端原生审美：行内代码用等宽 + 霓虹底，代码块用独立深色容器 + 语言标签 + 复制。
 *
 * 多模态输出：模型生成的图片（![alt](url)）用 Coil 渲染，点击进
 * [ImageLightbox]；视频（<video src> / 视频扩展名 URL）渲染为视频卡
 * （外部播放器打开）；流式未闭合的媒体语法渲染为"接收中"占位卡。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit = {},
    onLinkClick: ((String) -> Unit)? = null
) {
    val nodes = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier.fillMaxWidth()) {
        nodes.forEach { node ->
            when (node) {
                is MarkdownNode.Paragraph -> {
                    val annotated = buildInline(node.segments)
                    var textLayout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
                    val context = LocalContext.current
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        onTextLayout = { textLayout = it },
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .pointerInput(annotated) {
                                detectTapGestures { pos ->
                                    textLayout?.let { layout ->
                                        val offset = layout.getOffsetForPosition(pos)
                                        annotated.getStringAnnotations("URL", offset, offset)
                                            .firstOrNull()
                                            ?.let { annotation ->
                                                val handler = onLinkClick
                                                    ?: { url: String -> FileOpener.openUrl(context, url) }
                                                handler(annotation.item)
                                            }
                                    }
                                }
                            }
                    )
                }
                is MarkdownNode.Heading -> {
                    val style = when (node.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.labelLarge
                    }
                    Text(
                        text = node.text,
                        style = style,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                is MarkdownNode.BulletItem -> {
                    Row(
                        modifier = Modifier.padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownNode.OrderedItem -> {
                    Row(
                        modifier = Modifier.padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${node.index}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownNode.CodeBlock -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    CodeBlock(lang = node.lang, code = node.code)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                is MarkdownNode.Image -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    MarkdownImage(alt = node.alt, url = node.url, onImageClick = onImageClick)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                is MarkdownNode.Video -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    VideoCard(url = node.url)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                is MarkdownNode.PendingMedia -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    PendingMediaCard()
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 行内片段 → AnnotatedString。
 * 行内代码用等宽+霓虹底 span；链接带下划线 + "URL" annotation（点击命中）。
 */
@Composable
private fun buildInline(segments: List<InlineSegment>): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    val codeFg = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        segments.forEach { seg ->
            when (seg) {
                is InlineSegment.Text -> append(seg.text)
                is InlineSegment.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = codeFg
                    )
                ) { append(" ${seg.text} ") }

                is InlineSegment.Bold -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                ) { append(seg.text) }

                is InlineSegment.Link -> {
                    pushStringAnnotation("URL", seg.url)
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        )
                    ) { append(seg.text) }
                    pop()
                }
            }
        }
    }
}

/**
 * Markdown 图片：Coil 异步加载（https / data: URI 均支持），
 * 加载中显示占位色块（高度下限避免布局跳动），点击进 Lightbox。
 */
@Composable
private fun MarkdownImage(alt: String, url: String, onImageClick: (String) -> Unit) {
    AsyncImage(
        model = url,
        contentDescription = alt,
        contentScale = ContentScale.Fit,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onImageClick(url) }
    )
}

/**
 * 视频卡：应用内无视频播放器（Compose VideoElement 不可用），以卡片 +
 * 播放按钮呈现，点击唤起外部播放器（ACTION_VIEW，http URL 直开；
 * 本地路径经 FileOpener FileProvider 打开）。
 */
@Composable
private fun VideoCard(url: String) {
    val context = LocalContext.current
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
            .clickable {
                if (url.startsWith("http")) FileOpener.openUrl(context, url)
                else FileOpener.openFile(context, url, "video/mp4")
            }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放视频",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        if (url.startsWith("http")) FileOpener.openUrl(context, url)
                        else FileOpener.openFile(context, url, "video/mp4")
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "打开视频",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** 流式未闭合媒体语法的占位卡：媒体还在生成的途中。 */
@Composable
private fun PendingMediaCard() {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor.copy(alpha = 0.5f),
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "正在接收媒体…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "图片/视频生成中，完成后在此显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * 独立代码块：深色容器 + 霓虹左条 + 语言标签 + 复制按钮（复制后短暂对勾）。
 */
@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(10.dp.toPx())
                )
            }
    ) {
        Column {
            // 顶栏：语言标签 + 复制
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = lang.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 代码主体（等宽 + 横向滚动 + 左侧霓虹装饰条）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}
