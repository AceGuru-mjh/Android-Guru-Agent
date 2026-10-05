package com.apex.agent.ui.screen.usage

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.theme.statusSuccess
import com.apex.agent.ui.theme.statusWarning
import com.apex.agent.usage.UsageDaily
import com.apex.agent.usage.UsageLedger
import com.apex.agent.usage.UsageModelStat
import com.apex.agent.usage.UsageSessionStat
import com.apex.agent.usage.UsageTotals
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.entry.entryModelOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 用量仪表盘 ViewModel —— 从 [UsageLedger] 聚合数据并驱动 UI 状态。
 *
 * 聚合跑在 Dispatchers.Default（账本读方法为纯内存快照计算，不阻塞主线程）；
 * 「时间范围」只影响 [UsageUiState.daily]（每日图表），模型分解 / 会话排行 /
 * 汇总卡固定展示全库口径（与 [UsageLedger] 各读方法的语义一一对应）。
 */
@HiltViewModel
class UsageDashboardViewModel @Inject constructor(
    private val ledger: UsageLedger
) : ViewModel() {

    data class UsageUiState(
        val loading: Boolean = true,
        /** 7 / 30 / 90 / 0（0 = 全部历史）。 */
        val rangeDays: Int = 7,
        /** 日聚合（新日期在前，图表侧自行倒序正排）。 */
        val daily: List<UsageDaily> = emptyList(),
        val models: List<UsageModelStat> = emptyList(),
        val totals: UsageTotals? = null,
        val sessions: List<UsageSessionStat> = emptyList()
    )

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    /** 刷新代次：用户快速连点范围切换时，只回填最新一次的结果（旧协程结果丢弃）。 */
    private val refreshGeneration = AtomicLong(0)

    init { refresh() }

    /** 切换统计范围（7/30/90/0）并重新聚合。 */
    fun setRange(days: Int) {
        _uiState.update { it.copy(rangeDays = days) }
        refresh()
    }

    /** 重新聚合全部视图数据（首载 / 手动刷新 / 范围切换共用入口）。 */
    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.Default) {
            val range = _uiState.value.rangeDays
            val daily = ledger.daily(range)
            val models = ledger.modelBreakdown()
            val totals = ledger.totals()
            val sessions = ledger.topSessions()
            // 过期结果直接丢弃：期间用户已发起新范围，旧数据回填会与 chips 选中态错位
            if (refreshGeneration.get() == generation) {
                _uiState.update {
                    it.copy(loading = false, daily = daily, models = models, totals = totals, sessions = sessions)
                }
            }
        }
    }

    /** 清空全部本地用量记录（UI 侧确认对话框之后才可调用）。 */
    fun clearAll() {
        viewModelScope.launch(Dispatchers.Default) {
            ledger.clearAll()
            refresh()
        }
    }

    /**
     * 导出全量记录为 CSV 并发起系统分享。
     *
     * 写入 `cacheDir/usage-export/usage-<ts>.csv`（FileProvider cache-path 已覆盖
     * 全部 cache 子目录，见 res/xml/file_paths.xml），随后 ACTION_SEND 分享；
     * 模式与 LogViewerScreen.exportAndShare 一致：applicationContext 解包跨协程、
     * 分享意图加 NEW_TASK（从非 Activity 上下文启动）。
     */
    fun exportCsvAndShare(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(appContext.cacheDir, "usage-export")
                    dir.mkdirs()
                    File(dir, "usage-${System.currentTimeMillis()}.csv")
                        .apply { writeText(ledger.exportCsv()) }
                }
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                appContext.startActivity(
                    Intent.createChooser(intent, appContext.getString(R.string.usage_share_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { e ->
                AppLogger.instance.warn(LogCategory.UI, "UsageDashboard", "用量 CSV 导出分享失败: ${e.message}")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  页面
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 用量仪表盘页 —— Token 用量的持久化统计可视化。
 *
 * 数据源 [UsageLedger]（每轮 LLM 调用后由主控接线落账）。内容自上而下：
 * 汇总卡（累计/今日/近7天/请求数+均值）→ 每日用量柱状图（Vico）→
 * 模型用量分解（占比条）→ 会话 Top5 → 导出 CSV / 清空数据。
 */
@Composable
fun UsageDashboardScreen(viewModel: UsageDashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 头部：标题 + 副标题 + 刷新 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.usage_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                GlassIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.usage_refresh),
                    onClick = viewModel::refresh,
                    size = 36.dp
                )
            }
        }

        // ── 时间范围（只作用于每日图表）──
        Text(stringResource(R.string.usage_range_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.rangeDays == 7, onClick = { viewModel.setRange(7) }, label = { Text(stringResource(R.string.usage_range_7d)) })
            FilterChip(selected = state.rangeDays == 30, onClick = { viewModel.setRange(30) }, label = { Text(stringResource(R.string.usage_range_30d)) })
            FilterChip(selected = state.rangeDays == 90, onClick = { viewModel.setRange(90) }, label = { Text(stringResource(R.string.usage_range_90d)) })
            FilterChip(selected = state.rangeDays == 0, onClick = { viewModel.setRange(0) }, label = { Text(stringResource(R.string.usage_range_all)) })
        }

        val totals = state.totals
        when {
            // 首次加载：尚无任何快照
            totals == null -> Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.usage_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 全空态：账本一条记录都没有
            totals.totalRequests == 0 -> UsageEmptyState()
            else -> {
                SummaryCard(totals)
                DailyChartCard(state.daily)
                ModelBreakdownCard(state.models)
                TopSessionsCard(state.sessions)
                ManageDataCard(
                    records = totals.totalRequests,
                    onExport = { viewModel.exportCsvAndShare(context) },
                    onClear = { showClearDialog = true }
                )
            }
        }
    }

    if (showClearDialog) {
        ClearConfirmDialog(
            onConfirm = { showClearDialog = false; viewModel.clearAll() },
            onDismiss = { showClearDialog = false }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  区块组件
// ═══════════════════════════════════════════════════════════════════════════

/** 全空态 —— 居中图标 + 引导文案（诚实空态：只引导，不假装有数据）。 */
@Composable
private fun UsageEmptyState() {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.TableChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Text(stringResource(R.string.usage_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.usage_empty_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            Text(stringResource(R.string.usage_empty_local_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

/** 区块卡头：小图标 + 标题 + 副说明（四张数据卡的统一样式）。 */
@Composable
private fun CardHeader(icon: ImageVector, title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 汇总卡：累计总量 / 今日 / 最近 7 天 / 最近 30 天 + 总请求数（含每请求均值）。 */
@Composable
private fun SummaryCard(totals: UsageTotals) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.usage_summary_title),
                hint = stringResource(R.string.usage_summary_note)
            )
            Row(Modifier.fillMaxWidth()) {
                StatCell(stringResource(R.string.usage_stat_all_time), totals.allTimeTokens, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                // #244：汇总色改语义函数（明暗成对；旧硬编码 Green/Amber 单态 hex）
                StatCell(stringResource(R.string.usage_stat_today), totals.todayTokens, statusSuccess(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatCell(stringResource(R.string.usage_stat_7d), totals.last7dTokens, statusWarning(), Modifier.weight(1f))
                StatCell(stringResource(R.string.usage_stat_30d), totals.last30dTokens, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    label = stringResource(R.string.usage_stat_requests),
                    value = totals.totalRequests.toLong(),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    caption = stringResource(R.string.usage_stat_avg_per_request, formatTokens(totals.avgTokensPerRequest))
                )
                // 右下角：以累计总量口径收尾，避免三格布局失衡
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.usage_tokens_count, formatTokens(totals.allTimeTokens)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** 大数字 + 小标签的统计单元（数字用等宽字体，千分位分组）。 */
@Composable
private fun StatCell(
    label: String,
    value: Long,
    valueColor: Color,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    Column(modifier) {
        Text(formatTokens(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
        }
    }
}

/** 每日用量柱状图卡（Vico columnChart；daily 倒序入、正序出）。 */
@Composable
private fun DailyChartCard(daily: List<UsageDaily>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                icon = Icons.Outlined.TableChart,
                title = stringResource(R.string.usage_chart_title),
                hint = stringResource(R.string.usage_chart_hint)
            )
            if (daily.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.usage_chart_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 账本返回新→旧；图表按时间正排（旧→新），横轴即时间轴
                val series = remember(daily) { daily.asReversed() }
                val barColor = MaterialTheme.colorScheme.primary
                val columns = remember(barColor) { listOf(LineComponent(color = barColor.toArgb())) }
                Chart(
                    chart = columnChart(columns = columns),
                    model = entryModelOf(*series.map { it.totalTokens.toFloat() }.toTypedArray()),
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
                // 自绘日期标签（范式同 TaskHistoryScreen L184-197）：SpaceBetween 对齐，
                // 天数多时最多取 5 个均匀锚点（首/末精确对齐左右端柱，中间近似）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val fmt = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
                    labelIndices(series.size).forEach { idx ->
                        Text(fmt.format(Date(series[idx].dayEpochMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 范围内 Prompt / Completion 拆分
                val promptSum = series.sumOf { it.promptTokens }
                val completionSum = series.sumOf { it.completionTokens }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.usage_prompt_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.usage_tokens_count, formatTokens(promptSum)), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.usage_completion_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.usage_tokens_count, formatTokens(completionSum)), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 模型用量分解卡：每模型一行（名称 + 占比% + 占比进度条 + 总量与请求次数）。 */
@Composable
private fun ModelBreakdownCard(models: List<UsageModelStat>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.usage_models_title),
                hint = stringResource(R.string.usage_models_hint)
            )
            if (models.isEmpty()) {
                Text(stringResource(R.string.usage_models_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                models.forEach { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.modelId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.usage_model_share, m.share * 100f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(
                            progress = { m.share },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            stringResource(R.string.usage_tokens_count, formatTokens(m.totalTokens)) + " · " +
                                stringResource(R.string.usage_requests_count, m.requests),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 会话 Top5 卡：短会话号 + 总量 + 次数 + 相对时间。 */
@Composable
private fun TopSessionsCard(sessions: List<UsageSessionStat>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.usage_sessions_title),
                hint = stringResource(R.string.usage_sessions_hint)
            )
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.usage_sessions_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sessions.forEach { s ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (s.sessionId.isBlank()) stringResource(R.string.usage_session_unknown)
                                else stringResource(R.string.usage_session_short_id, s.sessionId.take(8)),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                relativeTimeLabel(s.lastAt) + " · " + stringResource(R.string.usage_requests_count, s.requests),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(formatTokens(s.totalTokens), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 底部数据管理卡：导出 CSV（GlassButton）+ 清空数据（红色调 TextButton，对话框确认）。 */
@Composable
private fun ManageDataCard(records: Int, onExport: () -> Unit, onClear: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.usage_actions_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.usage_records_stored, records), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = stringResource(R.string.usage_export_csv),
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Outlined.Download
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.usage_clear_data), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(stringResource(R.string.usage_export_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.usage_clear_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.usage_clear_irreversible), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
        }
    }
}

/** 清空确认对话框 —— 破坏性操作必须二次确认（红色调确认键）。 */
@Composable
private fun ClearConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_clear_confirm_title)) },
        text = { Text(stringResource(R.string.usage_clear_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.usage_clear_confirm_ok), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.usage_dialog_cancel))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  工具
// ═══════════════════════════════════════════════════════════════════════════

/** 千分位分组的整数格式（跟随系统 Locale）。 */
private fun formatTokens(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

/**
 * 日期标签锚点：≤5 天逐日全标；>5 天取首/1⁄4/中/3⁄4/末 5 个（首末精确对齐
 * SpaceBetween 两端，中间为近似位置）。
 */
private fun labelIndices(size: Int): List<Int> {
    if (size <= 0) return emptyList()
    if (size <= 5) return (0 until size).toList()
    return listOf(0, size / 4, size / 2, size * 3 / 4, size - 1).distinct().sorted()
}

/** 相对时间（简版）：<1 分钟「刚刚」/ <1 小时「x 分钟前」/ <24 小时「x 小时前」/ 其余「x 天前」。 */
@Composable
private fun relativeTimeLabel(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> stringResource(R.string.usage_rel_just_now)
        diff < 3_600_000L -> stringResource(R.string.usage_rel_minutes_ago, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(R.string.usage_rel_hours_ago, (diff / 3_600_000L).toInt())
        else -> stringResource(R.string.usage_rel_days_ago, (diff / 86_400_000L).toInt())
    }
}
