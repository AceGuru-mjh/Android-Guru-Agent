package com.apex.agent.ui.screen.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 工具来源分类的视觉规格：图标 + 标签 + 主题色。
 * 集中管理，保证 ToolCallCard / RunningToolCallCard / ErrorBlock 一致。
 */
@Immutable
internal data class ToolKindStyle(
    val label: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
internal fun toolKindStyle(kind: ToolKind): ToolKindStyle = when (kind) {
    ToolKind.LOCAL -> ToolKindStyle(
        "本地工具", Icons.Default.Build,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.MCP -> ToolKindStyle(
        "MCP", Icons.Default.Hub,
        MaterialTheme.colorScheme.tertiary
    )
    ToolKind.WEB_SEARCH -> ToolKindStyle(
        "联网搜索", Icons.Default.Search,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.WEB_FETCH -> ToolKindStyle(
        "网页抓取", Icons.Default.Language,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.SKILL -> ToolKindStyle(
        "Skill", Icons.Default.AutoAwesome,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.CONNECTOR -> ToolKindStyle(
        "连接器", Icons.Default.Link,
        Color(0xFF8B5CF6)
    )
    ToolKind.PLUGIN -> ToolKindStyle(
        "插件", Icons.Default.Extension,
        Color(0xFFF59E0B)
    )
}

/**
 * 工具调用来源徽章（图标 + 文字 + 浅色底），用于区分 本地/MCP/搜索/抓取/Skill/连接器/插件。
 */
@Composable
internal fun ToolKindBadge(kind: ToolKind, server: String? = null, skill: String? = null) {
    val style = toolKindStyle(kind)
    val color = style.color
    val label = when (kind) {
        ToolKind.SKILL -> skill?.let { "Skill: $it" } ?: style.label
        ToolKind.MCP -> server?.let { "MCP · $it" } ?: style.label
        else -> style.label
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.heightIn(min = 22.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

/**
 * 工具卡的智能摘要行：从参数/输出中提取一行人类可读的关键信息
 * （文件路径 / 命令 / URL / server / skill），折叠时也能看懂这次调用在做什么。
 */
internal fun smartToolSummary(toolName: String, args: String, kind: ToolKind, server: String?): String? {
    val name = toolName.lowercase()
    val path = Regex(""""(?:path|source|dest|target|file|url)"\s*:\s*"([^"]+)"""")
        .find(args)?.groupValues?.getOrNull(1)
    return when {
        name in FILE_OP_TOOLS -> path
        name == "shell_execute" ->
            Regex(""""command"\s*:\s*"([^"]+)"""").find(args)?.groupValues?.getOrNull(1)
                ?.take(80)
        name == "web_fetch" -> path
        name == "web_search" ->
            Regex(""""query"\s*:\s*"([^"]+)"""").find(args)?.groupValues?.getOrNull(1)
        kind == ToolKind.MCP -> server ?: path
        else -> null
    }
}

@Composable
internal fun ToolCallCard(
    toolCall: AgentUiMessage.ToolCall,
    onRetry: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val isError = toolCall.success == false
    val kindStyle = toolKindStyle(toolCall.kind)
    val accent = if (isError) MaterialTheme.colorScheme.error else kindStyle.color

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 来源图标（带类型色圆形底）
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = CircleShape,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = kindStyle.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = toolCall.toolName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 状态徽章
                        val status = when {
                            toolCall.success == true -> Pair("完成", MaterialTheme.colorScheme.primary)
                            toolCall.success == false -> Pair("失败", MaterialTheme.colorScheme.error)
                            else -> Pair("运行", MaterialTheme.colorScheme.secondary)
                        }
                        Surface(
                            color = status.second.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = status.first,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = status.second,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ToolKindBadge(toolCall.kind, toolCall.server, toolCall.skill)

                    // 智能摘要：折叠时也能一眼看懂这次调用在做什么（文件路径 / 命令 / URL）
                    val summary = remember(toolCall.id) {
                        smartToolSummary(toolCall.toolName, toolCall.args, toolCall.kind, toolCall.server)
                    }
                    if (!summary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (toolCall.durationMs > 0) {
                    Text(
                        text = formatDuration(toolCall.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "折叠工具详情" else "展开工具详情",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded && toolCall.args.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "参数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = toolCall.args,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }

            // ═══ 执行过程时间线（展开可见，全量保留步骤与原始输出）═══
            if (expanded && toolCall.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "执行过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                ToolStepTimeline(
                    steps = toolCall.steps,
                    accent = accent,
                    maxHeight = 360.dp
                )
            }

            val outputText = if (expanded) toolCall.fullOutput ?: toolCall.output else toolCall.output

            if (outputText != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val results = if (toolCall.kind == ToolKind.WEB_SEARCH) {
                    remember(outputText) { parseWebSearchResults(outputText) }
                } else {
                    emptyList()
                }
                if (results.isNotEmpty()) {
                    WebSearchResultsCard(results, query = extractSearchQuery(outputText))
                } else {
                    // 智能输出渲染：按工具类型自动选择 代码高亮 / 文件卡 / JSON树 / Shell / 文本 卡片。
                    // output 直接传 outputText（本分支已保证非空）：展开时它等于 fullOutput ?: output，
                    // 折叠时等于 output——与卡片将要展示的内容完全一致。
                    SmartToolOutput(
                        toolName = toolCall.toolName,
                        args = toolCall.args,
                        output = outputText,
                        fullOutput = toolCall.fullOutput,
                        expanded = expanded,
                        isError = isError
                    )
                }
            }

            // 失败工具卡：提供"重试上一条指令"入口（与 ErrorBlock 行为一致）。
            if (isError && onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    RetryChip(onRetry = onRetry)
                }
            }
        }
    }
}

/**
 * 重试按钮（ErrorBlock 与失败 ToolCallCard 共用）：错误色底 + 刷新图标。
 */
@Composable
internal fun RetryChip(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onRetry() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onError
            )
        }
    }
}

/**
 * 从 WebSearchTool 的文本输出中解析出结构化搜索结果。
 * 工具输出形如：
 *   Search results for: "query" (N results)
 *   ---
 *   1. Title
 *      URL: https://...
 *      snippet text
 * 解析失败（如被截断/格式变化）时返回空列表，由调用方回退纯文本。
 */
internal fun parseWebSearchResults(text: String): List<WebSearchItem> {
    val items = mutableListOf<WebSearchItem>()
    val pattern = Regex(
        """(\d+)\.\s+(.+?)\s*\n\s*URL:\s*(\S+)\s*\n\s*(.*?)(?=\n\s*\n\s*\d+\.\s|\n\s*\nUse web_fetch|$)""",
        RegexOption.DOT_MATCHES_ALL
    )
    for (m in pattern.findAll(text)) {
        val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
        val url = m.groupValues[3].trim()
        val snippet = m.groupValues[4].replace(Regex("<[^>]+>"), "").trim()
        if (title.isNotBlank() && url.startsWith("http")) {
            items.add(WebSearchItem(title, url, snippet))
        }
    }
    return items
}

internal fun extractSearchQuery(text: String): String? {
    val m = Regex("""Search results for:\s*"(.*?)"""").find(text) ?: return null
    return m.groupValues[1].trim().takeIf { it.isNotBlank() }
}

@Immutable
internal data class WebSearchItem(val title: String, val url: String, val snippet: String)

/**
 * 联网搜索结果的结构化卡片：标题 + 域名 + 摘要 + 外链图标。
 * 点击在新窗口打开（Android 上用隐式 Intent 打开浏览器）。
 */
@Composable
internal fun WebSearchResultsCard(results: List<WebSearchItem>, query: String?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        query?.let {
            Text(
                text = "🔍 搜索：$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        results.forEach { item ->
            val host = runCatching { java.net.URI(item.url).host }.getOrNull() ?: item.url
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(item.url)
                            )
                            context.startActivity(intent)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.snippet.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "打开链接",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 工具执行过程的垂直时间线（与 harness 的"挂起→完成"两态卡片形成差异）。
 *
 * 渲染带时间戳的步骤流：左轴圆点（按阶段着色）+ 竖向连线 + 右侧（时间戳 + 阶段标签 + 等宽原始输出）。
 * 运行卡与完成卡共用此组件。
 *
 * @param steps       步骤序列（[ToolStep]）。
 * @param accent      主色（取 [ToolKind] 对应色），用于 START/COMPLETE 圆点。
 * @param autoScroll  运行态时是否自动滚动到底部（跟随流式输出）。
 * @param maxHeight   时间线最大高度（完成后整卡可滚动，运行态限制高度）。
 */
@Composable
internal fun ToolStepTimeline(
    steps: List<ToolStep>,
    accent: Color,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = false,
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified
) {
    if (steps.isEmpty()) return
    val listState = rememberLazyListState()
    val dateFmt = remember { SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }

    // 运行态：有新步骤（或"活输出"步骤被原地替换）时自动滚到底部。
    // key 用最后一步的 seq 而非 steps.size——步骤被 200 条 cap 截断后 size 恒定，
    // 原地替换时 size 也不变，size 无法感知更新；seq 单调递增即可。
    // 流式期间用即时 scrollToItem，避免动画叠加抖动。
    if (autoScroll) {
        LaunchedEffect(steps.lastOrNull()?.seq) {
            if (steps.isNotEmpty()) listState.scrollToItem(steps.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .then(if (maxHeight != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.heightIn(max = maxHeight) else Modifier)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(steps) { index, step ->
            val dotColor = when (step.phase) {
                StepPhase.START -> accent
                StepPhase.OUTPUT -> MaterialTheme.colorScheme.outline
                StepPhase.PROGRESS -> MaterialTheme.colorScheme.primary
                StepPhase.COMPLETE -> Color(0xFF22C55E)
                StepPhase.ERROR -> MaterialTheme.colorScheme.error
            }
            val phaseLabel = when (step.phase) {
                StepPhase.START -> "START"
                StepPhase.OUTPUT -> "OUTPUT"
                StepPhase.PROGRESS -> if (step.percent != null)
                    "PROGRESS ${(step.percent * 100).toInt()}%" else "PROGRESS"
                StepPhase.COMPLETE -> "DONE"
                StepPhase.ERROR -> "ERROR"
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                // ═══ 左轴：圆点 + 竖向连线 ═══
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // ═══ 右栏：时间戳 + 阶段标签 + 文本 ═══
                Column(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = dateFmt.format(Date(step.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = phaseLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = dotColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (step.phase == StepPhase.ERROR)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun RunningToolCallCard(toolCall: AgentToolCallUi) {
    val kindStyle = toolKindStyle(toolCall.kind)
    val accent = kindStyle.color

    // 实时耗时计时：立即显示真实已用时长（而非从 0 起跳），此后每秒刷新；
    // ≥60s 后切换为 2m05s 形式，长任务可读性更好。
    var elapsedSec by remember(toolCall.id) {
        mutableStateOf(
            (System.currentTimeMillis() - toolCall.startedAt).coerceAtLeast(0L) / 1000
        )
    }
    LaunchedEffect(toolCall.id) {
        while (true) {
            delay(1000)
            elapsedSec = (System.currentTimeMillis() - toolCall.startedAt).coerceAtLeast(0L) / 1000
        }
    }

    val summary = remember(toolCall.id) {
        smartToolSummary(toolCall.toolName, toolCall.args, toolCall.kind, toolCall.server)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = accent.copy(alpha = 0.10f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = kindStyle.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.toolName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    ToolKindBadge(toolCall.kind, toolCall.server, toolCall.skill)
                    if (!summary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 实时耗时
                if (elapsedSec > 0) {
                    Text(
                        text = if (elapsedSec < 60) "${elapsedSec}s" else formatDuration(elapsedSec * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 脉冲进度环（运行态）
                val transition = rememberInfiniteTransition(label = "toolRunning")
                val ringAlpha by transition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "ringAlpha"
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.5.dp,
                    color = accent.copy(alpha = ringAlpha)
                )
            }

            // ═══ 进度条 + 进度说明（由 ToolProgress 事件驱动）═══
            if (toolCall.progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { toolCall.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            } else if (!toolCall.progressMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
            }
            if (!toolCall.progressMessage.isNullOrBlank()) {
                Text(
                    text = toolCall.progressMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ═══ 工具执行过程时间线（流式，自动滚到底部）═══
            if (toolCall.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                ToolStepTimeline(
                    steps = toolCall.steps,
                    accent = accent,
                    autoScroll = true,
                    maxHeight = 240.dp
                )
            } else if (toolCall.output.isNotEmpty()) {
                // 兜底：无步骤但已有输出（理论上 START 步始终存在，此处为安全占位）。
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = toolCall.output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
