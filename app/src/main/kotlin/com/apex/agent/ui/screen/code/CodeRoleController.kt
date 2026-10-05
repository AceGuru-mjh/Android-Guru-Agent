package com.apex.agent.ui.screen.code

import com.apex.agent.ui.screen.settings.AgentRole
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.screen.settings.activeCodingRole
import com.apex.agent.ui.screen.settings.codingRoles
import com.apex.agent.ui.screen.settings.withCodingRoleActivated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * # Code Role Controller — Coding 专家模板控制器（v6）
 *
 * 用户规格：「coding 模式增加一些重要的 agent 模板 —— 置顶全栈、Git
 * 角色、Android 专家、每门语言一个专家」。模板数据在
 * [AgentRole.CODING_EXPERTS]（内置不落盘）；本控制器负责：
 *
 * - 角色列表/激活角色的 StateFlow（设置层 agentSettings 派生，含用户
 *   自定义角色跨模式复用）；
 * - 选择即持久化（codeActiveRoleId）→ 设置流回灌 → 引擎人设通道
 *   （CodeEngineFacade.updateRolePersona，双引擎同步）—— 下一轮请求
 *   生效，无需重启；
 * - 启动即应用一次当前激活角色（VM 构造后首次组合时接线）。
 *
 * 独立文件（God-file 预算纪律：CodeViewModel 只减不增，模式同
 * CodeLongTaskCenterOps 的 internal 扩展拆分——本类经 VM 的 lazy
 * 属性暴露，CodeScreen 直取）。
 */
class CodeRoleController(
    private val settingsRepository: SettingsRepository,
    private val engineAccessor: () -> com.apex.agent.core.code.CodeEngineFacade?,
    scope: CoroutineScope
) {

    /** 全量 Coding 角色列表（内置专家置顶 + 自定义接续）。 */
    val roles: StateFlow<List<AgentRole>> = settingsRepository.agentSettings
        .map { it.codingRoles() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前激活角色（悬空 → 全栈置顶兜底）。 */
    val active: StateFlow<AgentRole> = settingsRepository.agentSettings
        .map { it.activeCodingRole() }
        .stateIn(scope, SharingStarted.Eagerly, AgentRole.CODING_EXPERTS.first())

    init {
        // 设置流 → 引擎人设（启动首帧与运行时热切换同一条通道）。
        scope.launch {
            settingsRepository.agentSettings
                .distinctUntilChangedBy { it.codeActiveRoleId }
                .collect { settings -> applyToEngine(settings.activeCodingRole()) }
        }
    }

    /** 激活角色（模式行胶囊入口；持久化 + collector 负责引擎生效）。 */
    fun select(roleId: String) {
        settingsRepository.updateAgentSettings { withCodingRoleActivated(roleId) }
    }

    private fun applyToEngine(role: AgentRole) {
        engineAccessor()?.updateRolePersona(
            roleDefinition = role.roleDefinition,
            rolePrompt = role.systemPrompt.ifBlank { null }
        )
    }
}
