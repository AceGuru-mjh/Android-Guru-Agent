package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 市场（v3 顶栏双视图重构）
 *
 * 结构（对应产品反馈：「点击市场后顶部有市场 / 已安装管理两个导航，
 * 两个导航下面都保留原有子导航 skill / mcp / 插件等」）：
 *
 * ```
 * ┌─────────────────────────────────────────────┐
 * │  PrimaryTabRow：  市场  │  已安装管理            │  ← 顶栏视图切换
 * ├─────────────────────────────────────────────┤
 * │  SecondaryTabRow： 插件 │ Skills │ MCP │ 连接器 │ 集成  │  ← 原有五个子导航（两视图共享）
 * ├─────────────────────────────────────────────┤
 * │  内容区（发现/安装 ↔ 管理已装）                  │
 * └─────────────────────────────────────────────┘
 * ```
 *
 * - 「市场」发现视图：插件发现 / Skills 模板与导入 / MCP 添加 / 连接器添加 /
 *   魔搭 + GitHub 集成（见 [MarketBrowseTabs.kt]）；
 * - 「已安装管理」视图：上述五类的已装内容启停 / 卸载 / 连接管理
 *   （见 [MarketInstalledTabs.kt]）；
 * - 切换视图不重置子页签；已安装视图空态提供「去市场安装」一键跳回。
 *
 * 数据层见 [MarketViewModel]（全部 IO 线程化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(viewModel: MarketViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 修复跨屏 stale 开关：VM 为 Activity 级单例，在 Skill 页切换的开关不会自动同步到市场页 ——
    // 每次进入本屏时强制刷新快照（原仅 init 刷新一次）
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 已安装计数徽标：已加载插件 + 已装技能 + MCP 工具源 + 连接器
    val installedCount = state.plugins.count { it.loaded } +
        state.skills.size +
        state.mcps.size +
        state.connectors.size

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ 顶栏视图导航：市场 / 已安装管理 ═══
            MarketScopeTabs(
                scope = state.scope,
                installedCount = installedCount,
                onSelect = viewModel::selectScope
            )

            // ═══ 子导航：五个分类页签（两视图共享，切换视图不重置）═══
            SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                MarketTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            // ═══ 内容区：按视图 × 分类分发 ═══
            when (state.scope) {
                MarketScope.BROWSE -> when (state.selectedTab) {
                    MarketTab.PLUGINS -> BrowsePluginsTab(state, viewModel)
                    MarketTab.SKILLS -> BrowseSkillsTab(state, viewModel)
                    MarketTab.MCP -> BrowseMcpTab(state, viewModel)
                    MarketTab.CONNECTORS -> BrowseConnectorsTab(state, viewModel)
                    MarketTab.INTEGRATIONS -> BrowseIntegrationsTab(state, viewModel)
                }
                MarketScope.INSTALLED -> when (state.selectedTab) {
                    MarketTab.PLUGINS -> InstalledPluginsTab(state, viewModel)
                    MarketTab.SKILLS -> InstalledSkillsTab(state, viewModel)
                    MarketTab.MCP -> InstalledMcpTab(state, viewModel)
                    MarketTab.CONNECTORS -> InstalledConnectorsTab(state, viewModel)
                    MarketTab.INTEGRATIONS -> InstalledIntegrationsTab(state, viewModel)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
