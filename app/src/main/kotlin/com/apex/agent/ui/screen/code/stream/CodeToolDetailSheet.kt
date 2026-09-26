package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolKind

/**
 * # Code Tool Detail Sheet — 胶囊详情弹层（分族路由 + 滚动保持）
 *
 * 点击胶囊/轮次卡展开：按 [ToolKind] 路由到对应详情体——
 *
 * | 族 | 详情体 |
 * |----|--------|
 * | BASH / GIT | 终端全文（等宽 + ANSI 清洗 + 独立滚动） |
 * | EDIT_FILE / WRITE_FILE | 文件 Diff（hunk 级 CodeDiffView） |
 * | GREP_SEARCH | 结果列表（逐行命中，等宽） |
 * | TEST | 输出全文（失败段落前置强调） |
 * | 其他 | 通用输出（等宽全文） |
 *
 * **滚动保持**：每种详情体的滚动状态 remember 在弹层重组之外
 * （remember(call.id) 键控）——来回切换胶囊不丢阅读位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeToolDetailSheet(
    call: StreamToolCall,
    terminalFallback: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // ── 标题区：族图标 + 名称 + target + 状态 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                val style = capsuleStyle(call.kind)
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${call.displayName} · ${call.target}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    val meta = buildString {
                        append(call.status.name)
                        append(" · ").append(formatCapsuleDuration(call.displayDuration(System.currentTimeMillis())))
                        call.exitCode?.let { append(" · exit $it") }
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 详情体（分族路由）──
            val scroll = remember(call.id) { ScrollStateHolder() }
            when (call.kind) {
                ToolKind.EDIT_FILE, ToolKind.WRITE_FILE ->
                    DiffDetailBody(call, scroll)

                ToolKind.GREP_SEARCH ->
                    GrepDetailBody(call, scroll)

                else ->
                    TerminalDetailBody(call, terminalFallback, scroll)
            }

            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

/** 滚动保持载体（每种详情体一个，键控 call.id）。 */
private class ScrollStateHolder {
    val state = androidx.compose.foundation.ScrollState(0)
}

// ═══ Diff 详情体 ═══

@Composable
private fun DiffDetailBody(call: StreamToolCall, scroll: ScrollStateHolder) {
    val diffText = call.diffText
    Text(
        text = stringResource(R.string.code_stream_detail_diff_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    if (diffText.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.code_stream_detail_diff_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(scroll.state)
        ) {
            CodeDiffView(diffText = diffText)
        }
    }
}

// ═══ Grep 结果列表 ═══

@Composable
private fun GrepDetailBody(call: StreamToolCall, scroll: ScrollStateHolder) {
    Text(
        text = stringResource(R.string.code_stream_detail_grep_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    val hits = remember(call.logTail) {
        call.logTail.lines().filter { it.isNotBlank() }
    }
    if (hits.isEmpty()) {
        Text(
            text = stringResource(R.string.code_stream_detail_grep_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
        ) {
            items(hits) { line ->
                GrepHitRow(line)
            }
        }
    }
}

@Composable
private fun GrepHitRow(line: String) {
    val isHeader = line.contains(":") && !line.startsWith(" ")
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isHeader) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isHeader) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ═══ 终端 / 通用输出详情体 ═══

@Composable
private fun TerminalDetailBody(
    call: StreamToolCall,
    terminalFallback: String?,
    scroll: ScrollStateHolder
) {
    Text(
        text = stringResource(
            if (call.kind == ToolKind.BASH || call.kind == ToolKind.GIT)
                R.string.code_stream_detail_terminal_title
            else R.string.code_stream_detail_output_title
        ),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    val raw = terminalFallback?.takeIf { it.isNotBlank() } ?: call.logTail
    val text = remember(raw) { stripTerminalAnsi(raw) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = androidx.compose.ui.graphics.Color(0xFF101418),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
    ) {
        Text(
            text = text.ifBlank { stringResource(R.string.code_stream_terminal_empty) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = androidx.compose.ui.graphics.Color(0xFFD1D5DB),
            modifier = Modifier
                .verticalScroll(scroll.state)
                .padding(10.dp)
        )
    }
}
