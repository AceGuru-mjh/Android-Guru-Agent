package com.apex.agent.ui.screen.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassToolCard
import com.apex.agent.ui.glass.GlassToolStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 工具来源分类的视觉规格：图标 + 标签 + 主题色。
 * 集中管理，保证 ToolCallCard / RunningToolCallCard / ErrorBlock 一致。
 * i18n：label 改持 @StringRes，展示端（ToolKindBadge 等）stringResource 取词。
 */
@Immutable
internal data class ToolKindStyle(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val color: Color
)

@Composable
internal fun toolKindStyle(kind: ToolKind): ToolKindStyle = when (kind) {
    ToolKind.LOCAL -> ToolKindStyle(
        R.string.chat_toolkind_local, Icons.Default.Build,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.MCP -> ToolKindStyle(
        R.string.chat_toolkind_mcp, Icons.Default.Hub,
        MaterialTheme.colorScheme.tertiary
    )
    ToolKind.WEB_SEARCH -> ToolKindStyle(
        R.string.chat_toolkind_web_search, Icons.Default.Search,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.WEB_FETCH -> ToolKindStyle(
        R.string.chat_toolkind_web_fetch, Icons.Default.Language,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.SKILL -> ToolKindStyle(
        R.string.chat_toolkind_skill, Icons.Default.AutoAwesome,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.GITHUB -> ToolKindStyle(
        // 官方 Octocat mark（res/drawable/ic_github_mark）+ GitHub fg-muted 灰：
        // 深浅主题均可读（纯黑 #181717 在暗色主题不可见）。
        R.string.chat_toolkind_github, ImageVector.vectorResource(R.drawable.ic_github_mark),
        Color(0xFF6E7681)
    )
    ToolKind.CONNECTOR -> ToolKindStyle(
        R.string.chat_toolkind_connector, Icons.Default.Link,
        Color(0xFF8B5CF6)
    )
    ToolKind.PLUGIN -> ToolKindStyle(
        R.string.chat_toolkind_plugin, Icons.Default.Extension,
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
        ToolKind.SKILL -> skill?.let { "Skill: $it" } ?: stringResource(style.labelRes)
        ToolKind.MCP -> server?.let { "MCP · $it" } ?: stringResource(style.labelRes)
        else -> stringResource(style.labelRes)
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
 * （文件路径 / 命令 / URL / server / skill / GitHub owner/repo），
 * 折叠时也能看懂这次调用在做什么。
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
        name.startsWith("github_") -> githubSummary(name, args)
        kind == ToolKind.MCP -> server ?: path
        else -> null
    }
}

/**
 * GitHub 工具的摘要：owner/repo > 查询词 > 用户名，按参数字段提取。
 * 旧实现 github_* 落入 else 返回 null，折叠卡看不到这次在读哪个仓库。
 */
private fun githubSummary(name: String, args: String): String? {
    val repo = Regex(""""(?:repo|repository)"\s*:\s*"([^"]+)"""")
        .find(args)?.groupValues?.getOrNull(1)
    val owner = Regex(""""owner"\s*:\s*"([^"]+)"""")
        .find(args)?.groupValues?.getOrNull(1)
    val query = Regex(""""query"\s*:\s*"([^"]+)"""")
        .find(args)?.groupValues?.getOrNull(1)
    return when {
        repo != null && owner != null -> "$owner/$repo"
        repo != null -> repo
        owner != null -> "@$owner"
        query != null -> query.take(80)
        else -> null
    }
}

/**
 * ═══ 工具调用卡 v2：小胶囊折叠态（用户反馈「工具调用缩小成小胶囊，点击才放大」）═══
 *
 * 折叠态：单行小胶囊（高 ~26dp）—— 来源图标 + 工具名 + 状态圆点 + 智能摘要 +
 * 时长；不渲染参数/输出/时间线，流水里的几十次调用不再刷屏。
 * 展开态：完整 GlassToolCard（参数 + 执行时间线 + 智能输出 + 重试），
 * 内容与 v1 完全一致，只是按需渲染。
 * 顺序性：卡片仍由 AgentChatEventApplier 按完成顺序追加到消息流，本卡不重排。
 */
@Composable
internal fun ToolCallCard(
    toolCall: AgentUiMessage.ToolCall,
    onRetry: (() -> Unit)? = null,
    /** 重试门禁：流式生成中置 false（与 ErrorBlock/消息菜单同款口径）。 */
    retryEnabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val isError = toolCall.success == false
    val kindStyle = toolKindStyle(toolCall.kind)
    val accent = if (isError) MaterialTheme.colorScheme.error else kindStyle.color
    val summary = remember(toolCall.id) {
        smartToolSummary(toolCall.toolName, toolCall.args, toolCall.kind, toolCall.server)
    }

    if (!expanded) {
        // ═══ 折叠态：小胶囊（默认状态）═══
        ToolCallCapsule(
            toolName = toolCall.toolName,
            kindStyle = kindStyle,
            accent = accent,
            summary = summary,
            durationMs = toolCall.durationMs,
            isError = isError,
            isRunning = false,
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true }
        )
        return
    }

    // ═══ 展开态：完整 GlassToolCard（Liquid Glass Frosted 档）═══
    // 卡片位于消息源 LazyColumn 内部 —— Haze 1.4 不支持源内嵌套采样，
    // 诚实降级为 Frosted：主题薄霜 + 状态着色 + 边缘光 + 高光，不冒充 backdrop。
    GlassToolCard(
        status = if (isError) GlassToolStatus.FAILED else GlassToolStatus.COMPLETED,
        expanded = expanded,
        accent = accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = false }
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
                            color = MaterialTheme.colorScheme.onSurface,
                            // 修复：长 MCP 工具名把状态徽章/时长挤... 出卡片（无 maxLines 时整行溢出）
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // 状态徽章（i18n：组合内取词）
                        val status = when {
                            toolCall.success == true -> Pair(
                                stringResource(R.string.chat_status_done),
                                MaterialTheme.colorScheme.primary
                            )
                            toolCall.success == false -> Pair(
                                stringResource(R.string.chat_status_failed),
                                MaterialTheme.colorScheme.error
                            )
                            else -> Pair(
                                stringResource(R.string.chat_status_running),
                                MaterialTheme.colorScheme.secondary
                            )
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
                    contentDescription = if (expanded) stringResource(R.string.chat_cd_collapse_tool)
                    else stringResource(R.string.chat_cd_expand_tool),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (toolCall.args.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_params),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
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
            if (toolCall.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_execution_steps),
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

            val outputText = toolCall.fullOutput ?: toolCall.output

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
                    SmartToolOutput(
                        toolName = toolCall.toolName,
                        args = toolCall.args,
                        output = outputText,
                        fullOutput = toolCall.fullOutput,
                        expanded = true,
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
                    RetryChip(onRetry = onRetry, enabled = retryEnabled)
                }
            }
        }
    }
}

/**
 * ═══ 工具调用小胶囊（折叠态专用，ToolCallCard / RunningToolCallCard 共用）═══
 *
 * 用户需求：「调用工具和一些调用什么的都缩小，最好缩小成非常小的那种，
 * 点击才会放大，缩小成小胶囊」。设计：全宽但高度仅 ~26dp 的细胶囊 ——
 * 来源图标(12dp) + 工具名(等宽字号) + 状态圆点(6dp) + 智能摘要(灰色单行) +
 * 时长 + 展开箭头(12dp)。点击整个胶囊展开完整卡片。
 *
 * 状态圆点：运行中 = 主色脉冲；完成 = 绿色实心；失败 = 红色实心。
 */
@Composable
internal fun ToolCallCapsule(
    toolName: String,
    kindStyle: ToolKindStyle,
    accent: Color,
    summary: String?,
    durationMs: Long,
    isError: Boolean,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 运行态脉冲：圆点透明度 0.35↔1 循环（完成/失败态静态）
    val transition = rememberInfiniteTransition(label = "capsuleDot")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "capsuleDotAlpha"
    )
    val dotColor = when {
        isError -> MaterialTheme.colorScheme.error
        isRunning -> accent
        else -> Color(0xFF22C55E)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        modifier = modifier
            .heightIn(min = 26.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = kindStyle.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = toolName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp)
            )
            // 状态圆点（运行中脉冲）
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = dotColor.copy(alpha = if (isRunning) pulseAlpha else 1f),
                        shape = CircleShape
                    )
            )
            // 智能摘要（灰色单行，占满剩余宽度；无摘要留白）
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            // 时长
            if (durationMs > 0) {
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * 重试按钮（ErrorBlock 与失败 ToolCallCard 共用）：错误色底 + 刷新图标。
 *
 * @param enabled 门禁：流式生成中置 false —— 重试会取消在途轮次（旧工具卡
 *   悬挂 + 部分回复丢失），与消息菜单的 UX-1 门禁同款口径。
 */
@Composable
internal fun RetryChip(onRetry: () -> Unit, enabled: Boolean = true) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(enabled = enabled) { onRetry() }
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
                text = stringResource(R.string.chat_retry),
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
                text = stringResource(R.string.chat_search_query, it),
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
                        contentDescription = stringResource(R.string.chat_cd_open_link),
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
        itemsIndexed(steps, key = { _, s -> s.id }) { index, step ->
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

    // ═══ v2 小胶囊折叠态：运行中的工具默认也只占一行 ~26dp（用户反馈
    // 「调用工具缩小成小胶囊，点击才放大」），点击展开完整运行卡（时间线/
    // 进度/输出）。展开态内容与 v1 完全一致。═══
    var expanded by remember(toolCall.id) { mutableStateOf(false) }

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

    if (!expanded) {
        ToolCallCapsule(
            toolName = toolCall.toolName,
            kindStyle = kindStyle,
            accent = accent,
            summary = summary,
            durationMs = elapsedSec * 1000,
            isError = false,
            isRunning = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true }
        )
        return
    }

    // ═══ Liquid Glass 迁移：ElevatedCard → GlassToolCard Frosted 档 ═══
    // 运行态：工具类型色 accent 驱动着色 + 边缘光，保留既有脉冲反馈环
    GlassToolCard(
        status = GlassToolStatus.RUNNING,
        accent = accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = false }
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
