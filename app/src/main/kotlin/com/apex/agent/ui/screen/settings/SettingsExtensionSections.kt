package com.apex.agent.ui.screen.settings

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import kotlinx.coroutines.launch

/**
 * # Issue #164 / #165 —— AgentTab 的规则与钩子扩展区（挂载块抽出）
 *
 * [SettingsScreen] 已贴近文件预算红线（check_file_size 1200 行门禁），
 * v1.1 的两个新设置区（全局规则 + AGENTS.md 状态、钩子启停）的挂载逻辑
 * 内聚到本文件，AgentTab 只留一行调用。
 *
 * ## IO 纪律
 * 规则文件命中（`projectRulesFileName`）与模板创建（`createProjectRulesTemplate`）
 * 都在 [SettingsViewModel] 的 IO 调度器上执行；Composable 侧经 produceState
 * 异步取值——不阻塞组合、不重复读盘。创建成功后以 [rulesRefreshKey] 触发
 * 重查（不用 Snackbar：本页无挂载的 Host，showSnackbar 会永久挂起）。
 */
@Composable
internal fun AgentRulesAndHooksSections(
    agent: AgentSettings,
    onAgent: (AgentSettings) -> Unit,
    viewModel: SettingsViewModel
) {
    // ═══ 规则（#164）：全局规则（Agent/coding 双模式）+ 默认工作区 AGENTS.md ═══
    var rulesRefreshKey by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0)
    }
    val projectRulesFile by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        key1 = rulesRefreshKey
    ) {
        value = viewModel.projectRulesFileName()
    }
    val coroutineScope = rememberCoroutineScope()
    // stringResource 不能在 let 回调（非 Composable）里调——先提升格式串
    val loadedFmt = stringResource(R.string.code_rules_project_status_loaded_fmt)
    RulesSettingsSection(
        globalRules = agent.globalRules,
        onGlobalRulesChange = { rules -> onAgent(agent.copy(globalRules = rules)) },
        workspaceRootLabel = "linux/workspaces/default",
        projectRulesStatus = projectRulesFile?.let { fileName -> loadedFmt.format(fileName) },
        onCreateProjectRules = {
            coroutineScope.launch {
                viewModel.createProjectRulesTemplate()
                rulesRefreshKey++
            }
        }
    )

    // ═══ 钩子（#165）：声明式钩子启停（工具前后 / 会话生命周期）═══
    val hooks by viewModel.hooks.collectAsStateWithLifecycle()
    HooksSettingsSection(
        hooks = hooks,
        onToggle = { id, enabled -> viewModel.setHookEnabled(id, enabled) }
    )
}
