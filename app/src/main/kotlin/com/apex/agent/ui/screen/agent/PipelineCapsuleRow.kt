package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 输入栏 · 流水线指令迷你胶囊行 ═══
 *
 * 用户从斜杠菜单（SlashCommandButton / 实时联想）选中 Skill / MCP / 插件 /
 * 连接器后，指令不再以 `/skill:xxx` 裸文本污染输入框，而是挂在本行的
 * 迷你胶囊上：
 *
 * ```
 * ┌──────────────────────────────┐
 * │ [</> skill: 网页搜索 ×]        │  ← 胶囊行（无胶囊时不渲染）
 * │ [ 🔍 输入框 ................ ] │
 * └──────────────────────────────┘
 * ```
 *
 * - 胶囊内容 = 类型图标 + `type: 展示名` + × 移除钮；
 * - Skill 图标用 Material Icons 的 `Code`（视觉即 `</>` —— 代码符号，
 *   与技能"可复用程序块"的心智模型一致）；
 * - MCP / 连接器 / 插件分别用 Api / Link / Extension（与斜杠菜单分组图标同源）；
 * - 点击 × 移除胶囊；再选一条直接替换（单条语义）；
 * - 发送时由 ViewModel 把胶囊拼回 `/type:id` + 输入框附加文本走斜杠管线。
 *
 * 视觉沿用 ToolkitChip 的紧凑规格（小图标 + labelSmall + 20dp 关闭钮），
 * 保持输入栏所有"状态标签"一族的一致性。
 */
@Composable
fun PipelineCapsuleRow(
    pending: PendingPipelineCommand?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pending == null) return

    val removeCd = androidx.compose.ui.res.stringResource(R.string.chat_cd_close)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        PipelineCapsule(
            pending = pending,
            removeCd = removeCd,
            onRemove = onRemove
        )
    }
}

@Composable
private fun PipelineCapsule(
    pending: PendingPipelineCommand,
    removeCd: String,
    onRemove: () -> Unit
) {
    val icon = pipelineIconOf(pending.type)
    val typeTag = pipelineTypeTagOf(pending.type)
    val cd = "$typeTag: ${pending.label}"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier.semantics { contentDescription = cd }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$typeTag: ${pending.label}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = removeCd,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/** 类型 → 图标（与斜杠菜单分组图标同源，跨入口认知一致）。 */
private fun pipelineIconOf(type: String): ImageVector = when (type) {
    "mcp" -> Icons.Default.Api
    "connector" -> Icons.Default.Link
    "plugin" -> Icons.Default.Extension
    else -> Icons.Default.Code // skill —— Material "Code" 即 </> 代码符号
}

/** 类型 → 胶囊前缀标签（全小写，与斜杠命令 type 段一致）。 */
private fun pipelineTypeTagOf(type: String): String = when (type) {
    "mcp" -> "mcp"
    "connector" -> "connector"
    "plugin" -> "plugin"
    else -> "skill"
}
