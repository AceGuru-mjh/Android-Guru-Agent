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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolCallStatus
import com.apex.agent.core.code.stream.ToolKind

/**
 * # Code Tool Detail Sheet — 胶囊详情弹层（分族路由）
 *
 * 点击胶囊/轮次卡展开：按 [ToolKind] 路由到对应详情体——
 *
 * | 族 | 详情体 |
 * |----|--------|
 * | BASH / GIT | 终端全文（等宽 + ANSI 清洗 + 独立滚动） |
 * | EDIT_FILE / WRITE_FILE | 文件 Diff（hunk 级 CodeDiffView，自带懒滚动） |
 * | GREP_SEARCH | 结果列表（逐行命中，等宽） |
 * | TEST | 输出全文（失败段落前置强调） |
 * | 其他 | 通用输出（等宽全文） |
 *
 * **滚动语义**：Diff/Grep 详情体自带懒列表（不再外包 verticalScroll——
 * 同向嵌套滚动断手势）；终端/通用输出详情体保持 ScrollState。
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // statusLabel 是 @Composable——先在组合上下文求值，再进 buildString
                    val statusText = statusLabel(call.status)
                    val meta = buildString {
                        append(statusText)
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
            when (call.kind) {
                ToolKind.EDIT_FILE, ToolKind.WRITE_FILE ->
                    DiffDetailBody(call)

                ToolKind.GREP_SEARCH ->
                    GrepDetailBody(call)

                else ->
                    TerminalDetailBody(call, terminalFallback)
            }

            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

/** 状态徽标文案（本地化；不再是裸枚举名 "PARTIAL"）。 */
@Composable
private fun statusLabel(status: ToolCallStatus): String = stringResource(
    when (status) {
        ToolCallStatus.WAITING -> R.string.code_stream_status_waiting
        ToolCallStatus.RUNNING -> R.string.code_stream_status_running
        ToolCallStatus.SUCCESS -> R.string.code_stream_status_success
        ToolCallStatus.FAILED -> R.string.code_stream_status_failed
        ToolCallStatus.APPLIED -> R.string.code_stream_status_applied
        ToolCallStatus.PARTIAL -> R.string.code_stream_status_partial
    }
)

// ═══ Diff 详情体（CodeDiffView 自带懒滚动，不外包 verticalScroll）═══

@Composable
private fun DiffDetailBody(call: StreamToolCall) {
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
        CodeDiffView(diffText = diffText, modifier = Modifier.fillMaxWidth())
    }
}

// ═══ Grep 结果列表 ═══

@Composable
private fun GrepDetailBody(call: StreamToolCall) {
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
            // #245：grep 命中行可能重复（同文本多行）—— key 掺入下标防撞，
            // 换 query 重算时行状态不再错位复用。#264：从内部 key() 补丁提升为
            // itemsIndexed 的 key 参数 —— item 级复用语义正确，删掉双重包裹。
            itemsIndexed(hits, key = { index, line -> "$index-${line.take(64)}" }) { _, line ->
                GrepHitRow(line)
            }
        }
    }
}

@Composable
private fun GrepHitRow(line: String) {
    // 文件头判定精确化：ripgrep 输出形如 `path/to/File.kt:42:content`——
    // 原实现 contains(":") 会把含冒号的代码行（`fun foo(): Int`）误判成头
    val isHeader = GREP_HEADER_REGEX.containsMatchIn(line)
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

/** ripgrep `path:line:content` 文件头形态（Windows 盘符路径也兼容）。 */
private val GREP_HEADER_REGEX =
    Regex("""^(?:[A-Za-z]:)?[^\s:][^:\n]*:\d+:.*$""")

// ═══ 终端 / 通用输出详情体 ═══

@Composable
private fun TerminalDetailBody(
    call: StreamToolCall,
    terminalFallback: String?
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
    val scroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF101418),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
    ) {
        Text(
            text = text.ifBlank { stringResource(R.string.code_stream_terminal_empty) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD1D5DB),
            modifier = Modifier
                .verticalScroll(scroll)
                .padding(10.dp)
        )
    }
}
