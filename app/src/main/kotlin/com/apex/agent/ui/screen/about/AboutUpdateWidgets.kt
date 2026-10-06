package com.apex.agent.ui.screen.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.update.DownloadMirror
import com.apex.agent.update.HotUpdateEngine
import com.apex.agent.update.PatchIndex
import com.apex.agent.update.PatchUpdateEngine

/**
 * ═══════════════════════════════════════════════════════════════
 *  关于页「软件更新」小控件层（v1.4.5 从 AboutUpdatePanel 拆出）
 * ═══════════════════════════════════════════════════════════════
 *
 * SRP 拆分（1200 行预算合规）：本文件只放「无状态可复用小控件」——
 * 进度条 / 链路可视化 / 徽章 / 镜像选择行与对话框。状态与编排留在
 * [AboutUpdatePanel]，对话框层在 [AboutUpdateDialogs]。
 */

/**
 * 增量流水线进度：下载段（N/M · 百分比 · 已下载字节）或合成段（补丁 N/M ·
 * 百分比）—— v1.4.5 附「后台持续 + 断点续传」提示（离开页面不中断）。
 */
@Composable
internal fun PatchFlowProgress(flow: PatchUpdateEngine.State) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (flow) {
            is PatchUpdateEngine.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { flow.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(
                            R.string.about_update_patch_downloading,
                            flow.step, flow.steps, flow.percent
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (flow.bytesSoFar > 0) {
                        Text(
                            formatMb(flow.bytesSoFar),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            is PatchUpdateEngine.State.Applying -> {
                LinearProgressIndicator(
                    progress = { flow.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(
                        R.string.about_update_patch_applying,
                        flow.step, flow.steps, flow.percent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            else -> Unit
        }
    }
}

/**
 * 补丁链路可视化 —— 本地版本 →（每段补丁 · 体积）→ 目标版本。
 * 等宽小字 + 箭头，实验室标签风格；单段链只画一行直达。
 */
@Composable
internal fun PatchChainPath(chain: PatchIndex.Chain) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 路径行：vLocal → vMid → … → vTarget（节点 = 补丁边界版本）
        Row(verticalAlignment = Alignment.CenterVertically) {
            val nodes = buildList {
                add(chain.steps.firstOrNull()?.fromTag?.removePrefix("v") ?: "?")
                chain.steps.forEach { add(it.toTag.removePrefix("v")) }
            }
            nodes.forEachIndexed { index, node ->
                if (index > 0) {
                    Text(
                        " → ",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.outline
                    )
                }
                Text(
                    node,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (index == nodes.lastIndex) FontWeight.SemiBold
                    else FontWeight.Normal,
                    color = if (index == nodes.lastIndex) scheme.primary else scheme.onSurfaceVariant
                )
            }
        }
        // 分段体积行（>1 段才展开；单跳已在按钮上标总体积）
        if (chain.steps.size > 1) {
            Text(
                chain.steps.joinToString(" · ") { formatMb(it.sizeBytes) },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.outline
            )
        }
    }
}

/** 主色实底小徽章（NEW）。 */
@Composable
internal fun StatusBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 当前下载源展示行：镜像名 + 延迟徽章（AUTO 显示已解析的最快节点）。 */
@Composable
internal fun MirrorSelectionRow(
    selected: DownloadMirror,
    speeds: Map<DownloadMirror, Long>,
    onClick: () -> Unit
) {
    val fastest = speeds.minByOrNull { it.value }?.key
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_about_update_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                val label = when (selected) {
                    DownloadMirror.AUTO -> {
                        val target = fastest?.let { mirrorLabel(it) }
                            ?: stringResource(R.string.settings_about_update_source_direct)
                        stringResource(R.string.settings_about_update_source_auto) + " · $target"
                    }
                    else -> mirrorLabel(selected)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            // 右侧：AUTO 且有测速 → 「最快」；手选 → 延迟毫秒
            if (selected == DownloadMirror.AUTO && fastest != null) {
                Text(
                    stringResource(R.string.settings_about_update_fastest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (selected != DownloadMirror.AUTO) {
                speeds[selected]?.let { latency ->
                    Text(
                        stringResource(R.string.settings_about_update_ms, latency),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 镜像选择对话框：单选 + 各节点延迟 + 打开即测速。 */
@Composable
internal fun MirrorSelectionDialog(
    selected: DownloadMirror,
    speeds: Map<DownloadMirror, Long>,
    probing: Boolean,
    onSelect: (DownloadMirror) -> Unit,
    onDismiss: () -> Unit
) {
    val fastest = speeds.minByOrNull { it.value }?.key
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_update_source_title)) },
        // text 必须传 lambda：直接传 Column(...) 调用会得到 Unit，
        // 导致 AlertDialog 重载解析失败并级联报出 title/confirmButton 处的
        // 假错误（@Composable invocations can only happen…）。
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.settings_about_update_mirror_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))

                val options = listOf(DownloadMirror.AUTO) + DownloadMirror.NODES
                options.forEach { mirror ->
                    val latency = speeds[mirror]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mirror) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mirror == selected, onClick = { onSelect(mirror) })
                        Column(Modifier.weight(1f)) {
                            Text(mirrorLabel(mirror), style = MaterialTheme.typography.bodyMedium)
                            if (mirror == DownloadMirror.AUTO) {
                                Text(
                                    stringResource(R.string.settings_about_update_mirror_auto_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        // 延迟徽章：测速中 spinner 文案 / 毫秒 / 不通
                        val latencyText = when {
                            probing && mirror != DownloadMirror.AUTO ->
                                stringResource(R.string.settings_about_update_speedtesting)
                            latency != null ->
                                stringResource(R.string.settings_about_update_ms, latency)
                            mirror != DownloadMirror.AUTO ->
                                stringResource(R.string.settings_about_update_unreachable)
                            else -> ""
                        }
                        if (latencyText.isNotEmpty()) {
                            Text(
                                latencyText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (mirror == fastest) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        if (mirror == fastest) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.settings_about_update_fastest),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_about_update_close))
            }
        }
    )
}

/** 镜像显示名（加速站以其域名命名，免翻译歧义）。 */
@Composable
internal fun mirrorLabel(mirror: DownloadMirror): String = when (mirror) {
    DownloadMirror.AUTO -> stringResource(R.string.settings_about_update_source_auto)
    DownloadMirror.DIRECT -> stringResource(R.string.settings_about_update_source_direct)
    DownloadMirror.GHFAST -> "ghfast.top"
    DownloadMirror.GHPROXY_NET -> "ghproxy.net"
    DownloadMirror.GHPROXY_COM -> "gh-proxy.com"
}

/** 字节数 → 「318.4 MB」式人类可读体积（一位小数：<1MB 不再显示成 0 MB）。 */
internal fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

/**
 * 热更流水线进度（v1.4.7 热更新体系 —— 零安装通道）：
 * 下载段（百分比 + 字节）→ 校验段（不定进度条：ZIP 指纹 + 逐文件复核 +
 * 原子落位）。附「离开页面不中断」提示（应用级流水线，同增量路径语义）。
 */
@Composable
internal fun HotFlowProgress(flow: HotUpdateEngine.State) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (flow) {
            is HotUpdateEngine.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { flow.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(
                            R.string.about_update_hot_downloading,
                            flow.percent
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (flow.bytesSoFar > 0) {
                        Text(
                            formatMb(flow.bytesSoFar),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            is HotUpdateEngine.State.Verifying -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.about_update_hot_verifying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            else -> Unit
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// v1.4.8 本地更新包常驻区（关于页任何状态都展示）
// ═════════════════════════════════════════════════════════════════════════════
// 用户反馈：「补丁合成的 APK 装完后在关于页消失，得去 MT 管理器找」。
// 现常驻列出 Download/ApexAgent/ 下所有 APK（合成包 + 全量包），用户随时
// 可一键安装/删除，无需离开 App。

/**
 * 本地更新包常驻区。无 APK 时折叠为空（不占视觉），有 APK 时显示清单 +
 * 每个文件的版本/种类/体积/安装/删除按钮。清单为空时返回 Unit（不渲染）。
 */
@Composable
internal fun LocalApkInventorySection(
    entries: List<LocalApkEntry>,
    onInstall: (LocalApkEntry) -> Unit,
    onDelete: (LocalApkEntry) -> Unit
) {
    if (entries.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.about_update_local_inventory_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        "${entries.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.about_update_local_inventory_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            // 列表区（高度上限避免过长挤压状态主体）
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                    }
                    LocalApkRow(
                        entry,
                        onInstall = { onInstall(entry) },
                        onDelete = { onDelete(entry) }
                    )
                }
            }
        }
    }
}

/** 单行本地 APK：版本号 + 种类徽章 + 体积 + 安装/删除按钮。 */
@Composable
private fun LocalApkRow(
    entry: LocalApkEntry,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "v${entry.versionLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        localApkKindLabel(entry.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                formatMbLocal(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
            Text(
                entry.file.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
        OutlinedButton(
            onClick = onInstall,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp, vertical = 4.dp
            )
        ) {
            Icon(
                Icons.Outlined.SystemUpdateAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.about_update_local_install))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.about_update_local_delete),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** MB 体积格式化（与 AboutUpdatePanel.formatMb 同构，避免跨文件可见性放大）。 */
private fun formatMbLocal(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb)
    else if (mb >= 10) "%.1f MB".format(mb)
    else "%.2f MB".format(mb)
}
