package com.apex.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * ═══════════════════════════════════════════════════════════════
 *  #224：手搓导航返回栈（rememberSaveable 的 route 列表方案）
 * ═══════════════════════════════════════════════════════════════
 *
 * 旧病灶（ApexRoot）：只有一个 currentDestination 状态值 + when 切页，
 * 返回键被硬编码 `currentDestination = Agent` —— 从聊天页「小大脑→去配置」
 * 跳到设置页，改完按返回回不到聊天上下文，而是被强制拽回 Agent；来源页
 * 信息在单值状态里无处安放。
 *
 * 本类是「当前页 + 下方历史栈」的极简返回栈（不迁移 navigation-compose，
 * 单 issue 改造成本见 issue 讨论）。语义对齐 Android 顶层导航惯例：
 *
 *  - [navigateToTopLevel]（抽屉一级导航）= **替换**：新一级目的地开启全新
 *    历史 —— 顶层切换不产生返回层级（标准 drawer/bottom-nav 行为）；
 *  - [push]（应用内前进导航，如聊天页「去配置」→设置、更新横幅「查看」
 *    →关于）= **压栈**：当前页入历史，返回可回来源页；
 *  - [pop]（返回键/顶栏返回）= **弹栈**：栈非空回直接来源页（返回 true）；
 *    栈空返回 false，由调用方兜底（ApexRoot 保持 UX-2 既有行为：回 Agent）。
 *
 * 持久化：[Saver] 把 [history 旧→新 ..., current] 的 route 串行成
 * ArrayList&lt;String&gt;，经 rememberSaveable 跨旋转/进程重建存活（沿用原
 * DestinationSaver 的 route 往返 + 未知 route 兜底 Agent 容错，含 "skill"
 * 老路由迁移市场页）。
 *
 * 可测性：纯状态机（[mutableStateOf] 在非组合环境可直接构造读写），单测见
 * app/src/test/kotlin/com/apex/agent/ui/NavigationBackStackTest.kt。
 */
internal class NavigationBackStack(
    initialCurrent: DrawerDestination,
    initialHistory: List<DrawerDestination> = emptyList()
) {
    /** 当前目的地（抽屉高亮 / when 切页 / 顶栏标题的唯一依据） */
    var current: DrawerDestination by mutableStateOf(initialCurrent)
        private set

    /**
     * current 之下的历史（压栈顺序，last() = 直接来源页）。
     * 不变量：current == Agent 时栈必空 —— Agent 只作为栈底被压入，
     * 弹回即出栈，Agent 页自身永远不吃返回键（交系统默认行为）。
     */
    private var history: List<DrawerDestination> by mutableStateOf(initialHistory)

    /** 返回键是否可沿历史回退（false = 栈空，由调用方自行兜底） */
    val canPop: Boolean get() = history.isNotEmpty()

    /**
     * #224：顶层导航（抽屉项点击）—— 替换语义。
     * 清空历史、切换一级目的地：顶层切换不产生返回层级。
     */
    fun navigateToTopLevel(dest: DrawerDestination) {
        history = emptyList()
        current = dest
    }

    /**
     * #224：应用内前进导航 —— 压栈语义。
     * 当前页入历史、切换到 dest，返回时回到来源页。
     * 连续压入同一目的地视为无效操作（守卫重复层级，如「去配置」连点）。
     */
    fun push(dest: DrawerDestination) {
        if (dest == current) return
        history = history + current
        current = dest
    }

    /**
     * #224：弹栈回退 —— 栈非空则回到直接来源页并返回 true；
     * 栈空则状态不变、返回 false（兜底去向由调用方决定）。
     */
    fun pop(): Boolean {
        val previous = history.lastOrNull() ?: return false
        history = history.dropLast(1)
        current = previous
        return true
    }

    companion object {
        /**
         * #224：rememberSaveable Saver —— [history 旧→新 ..., current] 的
         * route 列表。空列表防御：异常数据兜底回 Agent 初始态。
         */
        val Saver: Saver<NavigationBackStack, ArrayList<String>> = Saver(
            save = { stack ->
                arrayListOf<String>().apply {
                    addAll(stack.history.map { it.route })
                    add(stack.current.route)
                }
            },
            restore = { routes ->
                if (routes.isEmpty()) {
                    NavigationBackStack(DrawerDestination.Agent)
                } else {
                    val destinations = routes.map { destinationFromRoute(it) }
                    NavigationBackStack(
                        initialCurrent = destinations.last(),
                        initialHistory = destinations.dropLast(1)
                    )
                }
            }
        )
    }
}

/**
 * #224：ApexRoot 导航返回栈的 rememberSaveable 入口（初始 = Agent 主页）。
 */
@Composable
internal fun rememberNavigationBackStack(): NavigationBackStack =
    rememberSaveable(saver = NavigationBackStack.Saver) {
        NavigationBackStack(DrawerDestination.Agent)
    }

/**
 * #224：route 字符串 → [DrawerDestination]（从 ApexRoot 的私有
 * DestinationSaver 收编为共享映射）。DrawerDestination 是 sealed class
 * （非 enum，无 name/entries），只能以 route 往返；未知 route 兜底
 * Agent，"skill" 老 route 兜底市场页（Skill 屏已并入市场，老用户
 * 重建时若停留在原 Skill 页不落空）。
 */
internal fun destinationFromRoute(route: String): DrawerDestination = when (route) {
    DrawerDestination.Agent.route -> DrawerDestination.Agent
    DrawerDestination.Code.route -> DrawerDestination.Code
    DrawerDestination.Templates.route -> DrawerDestination.Templates
    DrawerDestination.Terminal.route -> DrawerDestination.Terminal
    // "skill" route 保留兜底：老用户重建时若停留在原 Skill 页，落到市场
    "skill" -> DrawerDestination.Market
    DrawerDestination.Market.route -> DrawerDestination.Market
    DrawerDestination.Memory.route -> DrawerDestination.Memory
    DrawerDestination.Tasks.route -> DrawerDestination.Tasks
    DrawerDestination.Storage.route -> DrawerDestination.Storage
    DrawerDestination.Permissions.route -> DrawerDestination.Permissions
    DrawerDestination.Vault.route -> DrawerDestination.Vault
    DrawerDestination.Log.route -> DrawerDestination.Log
    DrawerDestination.Settings.route -> DrawerDestination.Settings
    DrawerDestination.GlassLab.route -> DrawerDestination.GlassLab
    DrawerDestination.Usage.route -> DrawerDestination.Usage
    DrawerDestination.Diagnostics.route -> DrawerDestination.Diagnostics
    DrawerDestination.About.route -> DrawerDestination.About
    else -> DrawerDestination.Agent
}
