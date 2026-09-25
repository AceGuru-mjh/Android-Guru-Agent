package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 行为规则分区（设置 → Agent 页，Issue #164）═══
 *
 * Rules 规则系统的设置面（结构对齐 v1.0 的 PermissionSettingsSection）：
 *  - 全局规则区：多行 OutlinedTextField（[GLOBAL_RULES_SOFT_LIMIT] 字符计数，
 *    超过 [GLOBAL_RULES_TRUNCATION_LINE] 变红提示截断风险——该线与
 *    core 层 RulesProvider.MAX_FILE_CHARS 一致，UI 提示有真实语义）；
 *  - 项目规则区：只读状态行（工作区根 + 规则文件发现结果）+ 可选的
 *    「创建模板」按钮（回调由宿主提供，null = 隐藏）。
 *
 * 数据完全由参数驱动（全局规则从 AgentSettings.globalRules 流入，变更经
 * 回调流出）；本 Composable 零本地状态、**不自行挂载**——由主控集成进
 * SettingsScreen 的 AgentTab（PermissionSettingsSection 之后）。
 *
 * 参数语义：
 *  - [workspaceRootLabel] == null → 无工作区：显示 no_workspace 兜底文案，
 *    隐藏创建按钮（没有可写的根）；
 *  - [projectRulesStatus]：有工作区时传命中描述（宿主用
 *    code_rules_project_status_loaded_fmt 格式化，如「已加载：AGENTS.md」）；
 *    未创建规则文件时传 null（本组件回落 missing 文案）。
 *
 * 主控挂载（接线契约）：
 *
 * RulesSettingsSection(
 *     globalRules = agent.globalRules,
 *     onGlobalRulesChange = { r -> onAgent(agent.copy(globalRules = r)) },
 *     workspaceRootLabel = ...,      // CodeWorkspaceManager.activeWorkspace()?.root 相对 filesDir 的标签，null=无
 *     projectRulesStatus = ...,      // RulesProvider.loadProjectRules(root, null) 是否命中（IO，建议 VM 预计算）
 *     onCreateProjectRules = ...     // 在工作区根写入 AGENTS.md 模板后刷新状态；无工作区传 null
 * )
 *
 * ── stringResource 键清单（已追加进 strings_code.xml；en / zh 文案）──
 *
 * code_rules_section_title           行为规则 / Behavioral Rules
 * code_rules_section_subtitle        全局规则与 AGENTS.md 项目规则 / Global rules and AGENTS.md project rules
 * code_rules_global_label            全局规则 / Global rules
 * code_rules_global_hint             对所有工作区与两种模式生效；超过 32768 字符的部分会被截断
 * code_rules_global_count_fmt        %1$d / %2$d 字符 / %1$d / %2$d chars
 * code_rules_project_title           项目规则（AGENTS.md）/ Project rules (AGENTS.md)
 * code_rules_project_status_loaded_fmt 已加载：%1$s / Loaded: %1$s（宿主格式化用）
 * code_rules_project_status_missing  未创建（在工作区根放置 AGENTS.md / CLAUDE.md / .cursorrules 即生效）
 * code_rules_project_status_no_workspace 无工作区——切换到 Code 屏创建或选择工作区后生效
 * code_rules_project_create          创建模板 / Create template
 * code_rules_section_note            优先级：项目规则（AGENTS.md）> 全局规则 > 一般偏好；均低于安全与权限约束
 */
@Composable
internal fun RulesSettingsSection(
    globalRules: String,
    onGlobalRulesChange: (String) -> Unit,
    workspaceRootLabel: String?,
    projectRulesStatus: String?,
    onCreateProjectRules: (() -> Unit)?
) {
    SectionCard(
        title = stringResource(R.string.code_rules_section_title),
        icon = Icons.Outlined.MenuBook,
        subtitle = stringResource(R.string.code_rules_section_subtitle)
    ) {
        // ── 全局规则区：多行编辑 + 字符计数 ──
        Text(
            stringResource(R.string.code_rules_global_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        val overLimit = globalRules.length > GLOBAL_RULES_TRUNCATION_LINE
        OutlinedTextField(
            value = globalRules,
            onValueChange = { onGlobalRulesChange(it.take(GLOBAL_RULES_SOFT_LIMIT)) },
            supportingText = {
                Text(
                    stringResource(
                        R.string.code_rules_global_count_fmt,
                        globalRules.length,
                        GLOBAL_RULES_TRUNCATION_LINE
                    ),
                    color = if (overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            },
            isError = overLimit,
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.code_rules_global_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        HorizontalDivider()

        // ── 项目规则区：只读状态行 + 可选创建按钮 ──
        Text(
            stringResource(R.string.code_rules_project_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (workspaceRootLabel == null) {
            // 无工作区：状态文案兜底，隐藏创建按钮（没有可写的根）
            Text(
                stringResource(R.string.code_rules_project_status_no_workspace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            Text(
                workspaceRootLabel,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                projectRulesStatus
                    ?: stringResource(R.string.code_rules_project_status_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (projectRulesStatus == null) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (onCreateProjectRules != null) {
                OutlinedButton(
                    onClick = onCreateProjectRules,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.code_rules_project_create))
                }
            }
        }

        HorizontalDivider()

        // ── 优先级说明（与 CodePrompts 的「规则优先级」段同一语义）──
        Text(
            stringResource(R.string.code_rules_section_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 与 core 层 RulesProvider.MAX_FILE_CHARS 对齐的截断线（超此长度注入时截断）。 */
private const val GLOBAL_RULES_TRUNCATION_LINE = 32 * 1024

/** 输入软上限（截断线的 2 倍）：防止设置存储被无界文本撑爆，不挡正常使用。 */
private const val GLOBAL_RULES_SOFT_LIMIT = 64 * 1024
