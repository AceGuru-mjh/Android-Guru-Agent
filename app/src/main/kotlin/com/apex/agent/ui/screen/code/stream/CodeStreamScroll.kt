package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * # Code Stream Scroll — 双锚定智能滚动（时间轴 + 终端面板独立）
 *
 * 移植 Agent 聊天页验证过的锚定模式（isAtBottom 派生 + 阅读模式保护 +
 * 流式即时跟随），封装为可复用状态类——Coding 屏的两个滚动容器
 * （胶囊时间轴 / 终端面板）**各自独立**持有一份：时间轴跟随对话推进，
 * 终端跟随命令输出，互不抢占。
 *
 * ## 语义（与 AgentChatScreen 对齐）
 *
 * - [isAtBottom]：距底 <150px 派生态（程序化滚动的判定基准）；
 * - [userScrolledUp]：阅读模式——snapshotFlow 监听 firstVisibleItemIndex
 *   的**递减方向**（程序化滚动只会递增，递减 ⇔ 用户主动上翻），置位后
 *   auto-follow 暂停，直到用户回底；
 * - [maybeAutoScroll]：流式期间即时 scrollToItem（跟手、无动画叠加），
 *   收尾 animate；
 * - [followTick]：内容字符数 / [stepChars] 节流档位（key 不含文本本身
 *   → 不会每 token 重启 effect）。
 */
class StreamScrollAnchor(
    val listState: LazyListState,
    private val stepChars: Int = 200
) {
    /** 距底 <150px（空列表视为在底）。 */
    val isAtBottom by derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        if (lastVisible == null) true
        else layoutInfo.viewportSize.height - (lastVisible.offset + lastVisible.size) < 150
    }

    /** 用户主动上翻后置位（阅读模式），回底复位。 */
    var userScrolledUp: Boolean = false
        private set

    /** 监听索引递减方向（LaunchedEffect 里 collect）。 */
    suspend fun observe() {
        var previousIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index < previousIndex) userScrolledUp = true
            previousIndex = index
        }
    }

    /** 是否应 auto-follow：在底部附近 或 未进入阅读模式。 */
    fun shouldFollow(): Boolean = isAtBottom || !userScrolledUp

    /** 即时跟随末项（流式期间）。 */
    suspend fun snapToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    /** 动画回底（FAB / 收尾）。 */
    suspend fun animateToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
        userScrolledUp = false
    }

    /** 内容长度节流档位（effect key 用）。 */
    fun followTick(contentLength: Int): Int = contentLength / stepChars
}

/**
 * 锚定装配：挂载观察协程 + 结构变化自动滚动。
 *
 * @param itemCountKey 列表结构变化 key（条目数）
 * @param contentTickKey 内容节流档位 key（字符数/200）
 * @param isStreaming 流式中（即时跟随）与收尾（动画）的分野
 */
@Composable
internal fun rememberStreamAnchor(
    listState: LazyListState,
    itemCountKey: Int,
    contentTickKey: Int,
    isStreaming: Boolean
): StreamScrollAnchor {
    val anchor = remember(listState) { StreamScrollAnchor(listState) }

    LaunchedEffect(listState) { anchor.observe() }

    // 结构变化（新条目）→ 跟随
    LaunchedEffect(itemCountKey, isStreaming) {
        if (itemCountKey > 0 && anchor.shouldFollow()) {
            if (isStreaming) anchor.snapToLast() else anchor.animateToLast()
        }
    }
    // 内容增长（同条目变长）→ 节流跟随
    LaunchedEffect(contentTickKey) {
        if (isStreaming && itemCountKey > 0 && anchor.shouldFollow()) {
            anchor.snapToLast()
        }
    }
    return anchor
}
