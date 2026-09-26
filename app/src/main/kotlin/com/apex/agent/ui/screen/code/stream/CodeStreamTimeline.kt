package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.CodeStreamSnapshot
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamEntryGroup
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolKind
import kotlinx.coroutines.launch

/**
 * # Code Stream Timeline — 胶囊时间轴主列表
 *
 * ## 渲染单元（防刷屏分组）
 *
 * 快照 entries 先经 [groupConsecutive] 分段：**同族连续**胶囊超过
 * [StreamEntryGroup.GROUP_THRESHOLD] 个的一段 → 折叠为「+N 更多」
 * 分组行（展开态本地 remember 自持）；其余逐条渲染。
 *
 * ## 双锚定之一（时间轴侧）
 *
 * [rememberStreamAnchor]：流式即时跟随 + 阅读模式保护 + 收尾动画；
 * 底部 FAB（不在底部时出现）一键回底。
 */
@Composable
internal fun CodeStreamTimeline(
    snapshot: CodeStreamSnapshot,
    isStreaming: Boolean,
    onToolClick: (StreamToolCall) -> Unit
) {
    val listState = rememberLazyListState()
    val grouped = remember(snapshot.entries) { groupConsecutive(snapshot.entries) }
    val contentLength = snapshot.entries.sumOf { entryContentLength(it) }
    val anchor = rememberStreamAnchor(
        listState = listState,
        itemCountKey = snapshot.entries.size,
        contentTickKey = anchorFollowTick(contentLength),
        isStreaming = isStreaming
    )
    val scope = rememberCoroutineScope()
    val showFab by remember {
        derivedStateOf { !anchor.isAtBottom && snapshot.entries.isNotEmpty() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            grouped.forEach { segment ->
                when (segment) {
                    is Segment.Single ->
                        item(key = segment.entry.id) {
                            StreamCard(entry = segment.entry, onToolClick = onToolClick)
                        }

                    is Segment.Group -> {
                        item(key = segment.groupKey) {
                            GroupExpandable(
                                calls = segment.calls,
                                onToolClick = onToolClick
                            )
                        }
                    }
                }
            }
        }

        if (showFab) {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { anchor.animateToLast() } },
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.code_stream_fab_bottom)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

/** 分组行 + 展开态（remember 自持）。 */
@Composable
private fun GroupExpandable(
    calls: List<StreamToolCall>,
    onToolClick: (StreamToolCall) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    CodeCapsuleGroupRow(
        calls = calls,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        onClick = onToolClick
    )
}

// ═══ 分段逻辑（纯函数）═══

/** 渲染分段：单条 or 同族连续胶囊组。 */
internal sealed interface Segment {
    data class Single(val entry: StreamEntry) : Segment
    data class Group(val groupKey: String, val kind: ToolKind, val calls: List<StreamToolCall>) : Segment
}

/**
 * 同族**连续**胶囊聚合：仅当相邻且同 [ToolKind] 的胶囊数超过阈值时
 * 成组（分组键 = 族名 + 首胶囊 id，保稳定）。
 */
internal fun groupConsecutive(entries: List<StreamEntry>): List<Segment> {
    val out = mutableListOf<Segment>()
    var bufferKind: ToolKind? = null
    val buffer = mutableListOf<StreamEntry.ToolCapsuleEntry>()

    fun flush() {
        val kind = bufferKind
        if (kind != null && buffer.size > StreamEntryGroup.GROUP_THRESHOLD) {
            out += Segment.Group(
                groupKey = "group-${kind.toString().lowercase()}-${buffer.first().id}",
                kind = kind,
                calls = buffer.map { it.call }
            )
        } else {
            buffer.forEach { out += Segment.Single(it) }
        }
        buffer.clear()
        bufferKind = null
    }

    entries.forEach { entry ->
        if (entry is StreamEntry.ToolCapsuleEntry) {
            if (bufferKind != null && bufferKind != entry.call.kind) flush()
            bufferKind = entry.call.kind
            buffer += entry
        } else {
            flush()
            out += Segment.Single(entry)
        }
    }
    flush()
    return out
}

/** 条目内容长度（节流档位 key 的素材）。 */
private fun entryContentLength(entry: StreamEntry): Int = when (entry) {
    is StreamEntry.UserEntry -> entry.text.length
    is StreamEntry.AssistantEntry -> entry.text.length
    is StreamEntry.ThinkingEntry -> entry.text.length
    is StreamEntry.ToolCapsuleEntry -> entry.call.logTail.length + 40
    is StreamEntry.VerifyCycleEntry -> entry.calls.size * 40
    is StreamEntry.StatusEntry -> entry.text.length
    is StreamEntry.SystemEntry -> entry.text.length
    is StreamEntry.ErrorEntry -> entry.message.length
    is StreamEntry.StopEntry -> entry.reason.length
    is StreamEntry.FileChipsEntry -> entry.files.size * 20
}

private fun anchorFollowTick(contentLength: Int): Int = contentLength / 200
