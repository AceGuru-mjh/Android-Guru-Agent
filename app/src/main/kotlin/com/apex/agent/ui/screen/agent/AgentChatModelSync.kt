package com.apex.agent.ui.screen.agent

import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// 模型切换 / 上下文窗口同步（Issue #222）—— AgentChatViewModel 的 internal 扩展
// （God-file 预算拆分，模式同 AgentChatHistoryController.kt：依赖的成员已开放，
// 调用点无感知）
//
// 职责：让「引擎压缩门 + 顶部水位条分母」始终跟随当前默认模型 Profile 的真实
// contextWindow，不再钉在全局设置 128k（旧 AgentConfig.maxContextTokens 语义）：
//  - selectProfile：聊天页"小大脑"菜单切换模型 —— 立即 patch 引擎 + 刷 UI；
//  - installContextWindowSync：VM init 安装的 profiles 流监听 —— 设置页
//    setDefaultProfile / 模型窗口编辑（upsertProfile）同样热更新兜底。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 切换当前模型：把该 Profile 设为默认 + 同步角色映射 + 引擎温度 + 上下文窗口，
 * 运行中的 LLM client 由 DynamicLlmClient 自动重建（即时生效）。
 */
internal fun AgentChatViewModel.selectProfile(profileId: String) {
    val target = settingsRepository.getProfile(profileId) ?: return
    settingsRepository.setDefaultProfile(profileId)
    settingsRepository.updateRoles { copy(primaryProfileId = profileId) }
    // 引擎侧同步温度 + 上下文窗口（Issue #222：压缩门与水位条分母跟随所选模型
    // 的真实 contextWindow；profiles collector 兜底设置页路径，此处立即 patch
    // 保证切换当帧 UI / 引擎同相）。
    val window = target.effectiveContextWindow(
        settingsRepository.agentSettings.value.maxContextTokens
    )
    (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
        cfg.copy(temperature = target.temperature, maxContextTokens = window)
    }
    // 修复：模型原生思考强度跟随当前模型（每 Profile 独立持久化）——
    // 切换后同步 UI 状态，避免 chip 显示上一个模型的档位（旧实现遗漏）。
    _uiState.update { it.copy(reasoningEffort = target.reasoningEffort, contextMaxTokens = window) }
}

/**
 * 更新当前模型的采样参数（Temperature / Top-P / Max Tokens）。
 * 写入 Profile（持久化）后由 DynamicLlmClient 即时生效。
 */
internal fun AgentChatViewModel.updateModelParams(temperature: Float, topP: Float, maxTokens: Int) {
    val cur = settingsRepository.getProfile(currentProfileId.value ?: return) ?: return
    settingsRepository.upsertProfile(
        cur.copy(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens
        )
    )
    (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
        cfg.copy(temperature = temperature)
    }
}

/**
 * Issue #222：安装上下文窗口跟随流（VM init 调用一次）。
 *
 * 默认 Profile 变化（聊天页 selectProfile / 设置页 setDefaultProfile / 模型
 * 窗口编辑 upsertProfile）→ 引擎 maxContextTokens patchConfig 热更新 + 水位条
 * 分母同步刷新。取窗口值去重 —— 温度等其它字段变更不触发无谓 patch；
 * 与角色/规则/预设 collector 同款运行时通道。
 */
internal fun AgentChatViewModel.installContextWindowSync() {
    // launchSafely：profiles 流/引擎 patch 的异常不炸进程（v1.4.9 闪退防御）
    launchSafely(tag = "contextWindowSync") {
        settingsRepository.profiles
            .map { list ->
                (list.firstOrNull { it.isDefault } ?: list.firstOrNull())
                    ?.effectiveContextWindow(settingsRepository.agentSettings.value.maxContextTokens)
            }
            .distinctUntilChanged()
            .collect { window ->
                if (window == null) return@collect
                (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
                    cfg.copy(maxContextTokens = window)
                }
                _uiState.update { it.copy(contextMaxTokens = window) }
            }
    }
}
