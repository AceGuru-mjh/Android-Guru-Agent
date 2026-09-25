package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Anchor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.tools.hook.HookEventType

/**
 * ═══ 钩子分区（设置 → Agent 页，Issue #165）═══
 *
 * Hooks 钩子系统的设置面：声明式钩子（hooks.json）列表 + 启停开关。
 *
 *  - 数据完全由参数驱动：宿主（SettingsScreen 装配）从 [HookRegistry.getConfigs]
 *    映射出 [HookUiModel] 列表流入，开关经 [onToggle]（→ setEnabled）流出；
 *    本组件不持有任何本地状态。
 *  - 系统钩子（isSystem=true）显示「内置」徽标；**本节刻意不提供删除/新增
 *    按钮**——v1.1 声明式钩子仅支持启停，自定义增删留待后续版本
 *    （hooks.json 的写入 API 与防呆校验是独立工作量，见 Issue #165 后续）。
 *  - [HookUiModel.eventLabel] 约定为事件枚举名（HookConfig.event.name），
 *    本组件内映射为本地化文案（7 类事件键 code_hooks_event_*），未知值
 *    原样显示——这样数据侧（VM）无需接触资源上下文。
 *
 * 主控挂载（SettingsScreen 的 AgentTab，PermissionSettingsSection 后）：
 *
 * HooksSettingsSection(
 *     hooks = agentHooks,   // HookRegistry.getConfigs().map { it.toUiModel() }
 *     onToggle = { id, enabled -> viewModel.setHookEnabled(id, enabled) }
 * )
 *
 * ── stringResource 键清单（本文件全部引用；en / zh 见 strings_hooks.xml）──
 *
 * code_hooks_section_title          钩子 / Hooks
 * code_hooks_section_subtitle       工具与会话生命周期钩子 / Tool & session lifecycle hooks
 * code_hooks_builtin_badge          内置 / Built-in
 * code_hooks_enabled_label          启用钩子 / Enable hook
 * code_hooks_event_pre_tool_use     工具调用前 / Before tool use
 * code_hooks_event_post_tool_use    工具调用后 / After tool use
 * code_hooks_event_user_prompt_submit 输入提交 / Prompt submit
 * code_hooks_event_session_start    会话开始 / Session start
 * code_hooks_event_session_end      会话结束 / Session end
 * code_hooks_event_stop             回合结束 / Turn end
 * code_hooks_event_subagent_stop    子代理结束 / Subagent end
 * code_hooks_event_pre_compact      压缩前 / Before compact
 * code_hooks_no_hooks               暂无钩子 / No hooks configured
 */
@Composable
internal fun HooksSettingsSection(
    hooks: List<HookUiModel>,
    onToggle: (String, Boolean) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.code_hooks_section_title),
        icon = Icons.Outlined.Anchor,
        subtitle = stringResource(R.string.code_hooks_section_subtitle)
    ) {
        if (hooks.isEmpty()) {
            Text(
                stringResource(R.string.code_hooks_no_hooks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            hooks.forEach { hook ->
                HookRow(hook = hook, onToggle = onToggle)
            }
        }
    }
}

/** 钩子行的纯展示模型（宿主从 HookRegistry.HookConfig 映射而来）。 */
data class HookUiModel(
    val id: String,
    val name: String,
    /** 事件枚举名（HookConfig.event.name），本组件内映射为本地化文案。 */
    val eventLabel: String,
    /** 工具匹配模式（非工具事件为 null；显示时用等宽字体）。 */
    val pattern: String?,
    val isSystem: Boolean,
    val enabled: Boolean
)

/**
 * 单个钩子行：名称（+「内置」徽标）/ 事件徽标 + pattern 等宽字体 / 启停开关。
 * 行布局对齐 PermissionSettingsSection.RuleRow 的密度与对齐惯例。
 */
@Composable
private fun HookRow(
    hook: HookUiModel,
    onToggle: (String, Boolean) -> Unit
) {
    val enabledLabel = stringResource(R.string.code_hooks_enabled_label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hook.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hook.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    BuiltinBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EventBadge(hook.eventLabel)
                hook.pattern?.let { pattern ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pattern,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = hook.enabled,
            onCheckedChange = { onToggle(hook.id, it) },
            // TalkBack 播报开关用途（对齐 SettingsScreen.SliderRow 的语义标注惯例）
            modifier = Modifier.semantics { contentDescription = enabledLabel }
        )
    }
}

/** 「内置」徽标：中性蓝灰（区别于权限效应的三色语义——钩子无好坏之分）。 */
@Composable
private fun BuiltinBadge() {
    val foreground = Color(0xFF3D6A96)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = foreground.copy(alpha = 0.12f),
        contentColor = foreground
    ) {
        Text(
            stringResource(R.string.code_hooks_builtin_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 事件徽标：低饱和主色底（事件类型是中性元数据，不是效应判断）。 */
@Composable
private fun EventBadge(eventLabel: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Text(
            localizedEventLabel(eventLabel),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 事件枚举名 → 本地化显示名；未知值（未来新增事件 + 旧版 UI）原样显示。 */
@Composable
private fun localizedEventLabel(eventLabel: String): String = when (eventLabel) {
    HookEventType.PRE_TOOL_USE.name -> stringResource(R.string.code_hooks_event_pre_tool_use)
    HookEventType.POST_TOOL_USE.name -> stringResource(R.string.code_hooks_event_post_tool_use)
    HookEventType.USER_PROMPT_SUBMIT.name -> stringResource(R.string.code_hooks_event_user_prompt_submit)
    HookEventType.SESSION_START.name -> stringResource(R.string.code_hooks_event_session_start)
    HookEventType.SESSION_END.name -> stringResource(R.string.code_hooks_event_session_end)
    HookEventType.STOP.name -> stringResource(R.string.code_hooks_event_stop)
    HookEventType.SUBAGENT_STOP.name -> stringResource(R.string.code_hooks_event_subagent_stop)
    HookEventType.PRE_COMPACT.name -> stringResource(R.string.code_hooks_event_pre_compact)
    else -> eventLabel
}
