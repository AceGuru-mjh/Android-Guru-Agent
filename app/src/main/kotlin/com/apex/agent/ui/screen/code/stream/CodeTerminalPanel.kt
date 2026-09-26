package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * # Code Terminal Panel — 终端面板（规格书：终端日志 + 独立锚定）
 *
 * Coding 工作流的 BASH 输出主舞台：
 * - 内容 = 活跃 BASH 胶囊的脉冲缓冲尾窗（[content]，已由 VM 从
 *   CodeStreamSnapshot.terminalContent 传入——整块脉冲，无打字机抖动）；
 * - **独立锚定**：横向滚动（长命令不断行）+ 纵向自动跟随输出尾行，
 *   用户上翻进入阅读模式后暂停跟随（与时间轴锚定互不干扰）；
 * - ANSI 转义清洗（渲染层兜底，面板不渲染控制序列）；
 * - 可折叠：头行 = 活跃命令 + 展开箭头；折叠态只留头行（时间轴优先）。
 */
@Composable
internal fun CodeTerminalPanel(
    content: String,
    activeCommand: String?,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF101418), // 终端恒定深底（浅色主题下也是终端语义）
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column {
            // ── 头行：终端图标 + 活跃命令 + 折叠开关 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A2027))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = activeCommand ?: stringResource(R.string.code_stream_terminal_idle),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9CA3AF),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(R.string.code_stream_terminal_toggle),
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (!collapsed) {
                TerminalBody(content = content)
            }
        }
    }
}

/** 终端正文：横向滚动 + 纵向尾行跟随 + ANSI 清洗。 */
@Composable
private fun TerminalBody(content: String) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val cleaned = remember(content) { stripTerminalAnsi(content) }

    // 独立锚定：输出追加时贴尾行（用户上翻后 maxValue 不追——滚动值保留即阅读态）
    LaunchedEffect(cleaned.length) {
        if (!vertical.isScrollInProgress && vertical.value >= vertical.maxValue - SCROLL_EPSILON) {
            vertical.scrollTo(vertical.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .horizontalScroll(horizontal)
    ) {
        Text(
            text = cleaned.ifBlank { stringResource(R.string.code_stream_terminal_empty) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD1D5DB),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** ANSI 转义序列清洗（与 AgentChatOutputCards.stripAnsi 同规则）。 */
internal fun stripTerminalAnsi(text: String): String =
    ANSI_ESCAPE_REGEX.replace(text, "")

private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

private const val SCROLL_EPSILON = 32
