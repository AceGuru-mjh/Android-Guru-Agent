package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.CodeStreamSnapshot
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamEntryGroup
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolKind
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
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
 *
 * ## v5 玻璃接线
 *
 * - [backdrop]：时间轴 = 玻璃采样源（悬浮输入栏 GlassCard 的 backdrop
 *   来源；null = 不接线，独立预览场景用）；
 * - [bottomInset]：底部悬浮栈（终端尾窗/输入栏等）高度 —— 列表
 *   contentPadding 与 FAB 底部偏移同步补偿，最后一条不被遮挡。
 */
@Composable
internal fun CodeStreamTimeline(
    snapshot: CodeStreamSnapshot,
    isStreaming: Boolean,
    onToolClick: (StreamToolCall) -> Unit,
    bottomInset: Dp = 0.dp,
    backdrop: LayerBackdrop? = null
) {
    val listState = rememberLazyListState()
    val grouped = remember(snapshot.entries) { groupConsecutive(snapshot.entries) }
    val contentLength = remember(snapshot.entries) { snapshot.entries.sumOf { entryContentLength(it) } }
    val anchor = rememberStreamAnchor(
        listState = listState,
        itemCountKey = snapshot.entries.size,
        contentTickKey = anchorFollowTick(contentLength),
        isStreaming = isStreaming
    )
    val scope = rememberCoroutineScope()
    // FAB 可见性只依赖可追踪的 derived state（anchor.isAtBottom）；
    // entries 判空放到组合处——原实现把首次组合时的空 snapshot 闭包进
    // derivedStateOf，普通属性读取不被追踪 → FAB 永远不出现（P0）
    val showFab by remember { derivedStateOf { !anchor.isAtBottom } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            // v5：底部悬浮栈（终端尾窗/输入栏）动态补偿 —— 最后一条不被遮挡
            // v6 紧凑化：12 → 8dp（悬浮栈自身已瘦身）
            contentPadding = PaddingValues(bottom = 8.dp + bottomInset)
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
            // 尾哨兵：snapToLast 锚到末条顶部而非尾部——长气泡流式时新内容
            // 在视口外不可见；1dp 哨兵恒为末条，贴它即贴底（P1）
            item(key = "stream-tail-sentinel") {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }

        AnimatedVisibility(
            visible = showFab && snapshot.entries.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // v6 紧凑化：ExtendedFAB(56dp+文字) → SmallFAB(40dp 纯图标) ——
            // 回底是时间轴高频小操作，缩小视觉质量但不减可达性（CD 保留语义）
            SmallFloatingActionButton(
                onClick = { scope.launch { anchor.animateToLast() } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = 8.dp + bottomInset)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.code_stream_fab_bottom),
                    modifier = Modifier.size(18.dp)
                )
            }
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
