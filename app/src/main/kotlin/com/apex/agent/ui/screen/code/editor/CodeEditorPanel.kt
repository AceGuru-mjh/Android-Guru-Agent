package com.apex.agent.ui.screen.code.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R

/**
 * # Code 屏编辑器面板 —— 当前文件查看（Issue #154）
 *
 * 纯 Compose 无状态展示组件：文件数据（[EditorFile]）、加载态、错误态全部由
 * 主控（CodeViewModel）持有，本面板只渲染 + 上报交互（关面板 / 点行引用）。
 *
 * 视觉语言与 CodeScreen.TodoPanel 同款：surfaceContainerLow 底 + 10dp 圆角；
 * 头部 = 等宽小字路径 + 行数徽标 + 加载小菊花 + 关闭按钮；正文 = 行号列
 * （右对齐、主色 60% 透明）+ 等宽 12sp 代码行（[CodeHighlighter] 着色），
 * 所有行共享同一个横向 ScrollState（滚动任一行全列同步，行号列固定不滚）。
 *
 * 高度自持：正文 LazyColumn 以 heightIn(max = 432.dp) 兜底 —— 面板可挂载在
 * 无有界高度约束的 Column 里（TodoPanel 同款位置）而不会因无限高度约束崩溃；
 * 内容不足时面板随内容收缩，不占满屏。
 *
 * 行点击回调 [onLineClick] 收到 1-based 行号，主控据此把 `@path:line` 拼进
 * 输入栏（选区引用）。文件为二进制 / 空文件 / 超行数截断时给出对应提示行。
 */
@Composable
fun CodeEditorPanel(
    filePath: String,
    file: EditorFile?,
    isLoading: Boolean,
    errorText: String?,
    onClose: () -> Unit,
    onLineClick: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ── 头部：路径 + 行数徽标 + 加载指示 + 关闭 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (file != null && !file.binary) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.code_editor_lines_fmt, file.totalLines),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (isLoading) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.code_editor_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── 错误态：红色提示行（不渲染正文）；否则按文件状态分支 ──
            if (errorText != null) {
                Spacer(Modifier.size(6.dp))
                ErrorRow(errorText)
            } else when {
                file == null -> {
                    if (isLoading) {
                        CenteredHint(stringResource(R.string.code_editor_loading))
                    }
                }
                file.binary -> CenteredHint(stringResource(R.string.code_editor_binary))
                file.lines.isEmpty() -> CenteredHint(stringResource(R.string.code_editor_empty))
                else -> {
                    Spacer(Modifier.size(6.dp))
                    CodeLinesBody(file = file, filePath = filePath, onLineClick = onLineClick)
                    if (file.truncated) {
                        Text(
                            text = stringResource(
                                R.string.code_editor_truncated_fmt, file.lines.size, file.totalLines
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══ 正文代码行 ═══

/** 代码主体：LazyColumn 行列表；全部行的横向滚动共享同一个 ScrollState。 */
@Composable
private fun CodeLinesBody(file: EditorFile, filePath: String, onLineClick: (Int) -> Unit) {
    val extension = filePath.substringAfterLast('.', "")
    val horizontalState = rememberScrollState()
    val gutterWidth = gutterWidthFor(file.totalLines)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 432.dp)
    ) {
        itemsIndexed(file.lines, key = { index, _ -> index }) { index, line ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineClick(index + 1) }
            ) {
                Text(
                    text = "${index + 1}",
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.width(gutterWidth)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = codeLineAnnotated(line = line, extension = extension),
                    softWrap = false,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.horizontalScroll(horizontalState)
                )
            }
        }
    }
}

/**
 * 单行 → 着色 AnnotatedString。只读组合函数：仅依赖主题色与纯计算，无副
 * 作用缓存 —— LazyColumn 只组合可见行（约数十行），每行几条正则的开销
 * 可忽略，换取实现简单（remember 需要可写组合上下文，与只读约束互斥）。
 */
@ReadOnlyComposable
@Composable
private fun codeLineAnnotated(line: String, extension: String): AnnotatedString {
    val builder = AnnotatedString.Builder(line)
    if (extension.isNotEmpty()) {
        CodeHighlighter.spansFor(line, extension).forEach { span ->
            span.applyTo(builder, line)
        }
    }
    return builder.toAnnotatedString()
}

/**
 * 着色映射（主题安全色，避开蓝紫系）：关键字/标签 → 主色；字符串 → 仓库
 * diff 绿；注释 → 表面弱化 60%；数字 → 仓库 diff 红；注解/属性 → 第三色。
 */
@ReadOnlyComposable
@Composable
private fun spanColor(kind: HighlightKind): Color = when (kind) {
    HighlightKind.KEYWORD -> MaterialTheme.colorScheme.primary
    HighlightKind.STRING -> Color(0xFF1B8A5A)
    HighlightKind.COMMENT -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    HighlightKind.NUMBER -> Color(0xFFB03A3A)
    HighlightKind.ANNOTATION -> MaterialTheme.colorScheme.tertiary
    HighlightKind.TAG -> MaterialTheme.colorScheme.primary
    HighlightKind.ATTRIBUTE -> MaterialTheme.colorScheme.tertiary
}

/** 把单个高亮片段写入构建器：区间 clamp 到行长度、半开语义、退化区间跳过。 */
@ReadOnlyComposable
@Composable
private fun HighlightSpan.applyTo(builder: AnnotatedString.Builder, line: String) {
    val s = start.coerceAtMost(line.length)
    val e = end.coerceAtMost(line.length)
    if (s >= e) return
    builder.addStyle(SpanStyle(color = spanColor(kind)), s, e)
}

// ═══ 辅助小组件 ═══

/** 错误提示行：ErrorBar（CodeScreen）同款视觉，无关闭按钮（随面板关闭消失）。 */
@Composable
private fun ErrorRow(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 居中弱化提示（加载中 / 二进制 / 空文件共用）。 */
@Composable
private fun CenteredHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 行号列宽：按真实总行数位数估算（等宽小字号约 8dp/位 + 余量）。 */
private fun gutterWidthFor(totalLines: Int) =
    (maxOf(1, totalLines).toString().length * 8 + 8).dp
