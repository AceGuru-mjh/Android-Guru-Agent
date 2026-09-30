package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.ui.screen.agent.HtmlArtifactDetector
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════
// Coding 屏工位对等增强（Agent 屏能力对齐，God-file 预算拆分）
// ═══════════════════════════════════════════════════════════════
// 独立文件原因：CodeViewModel 贴 1200 行门禁（scripts/check_file_size.sh），
// 「后台完成通知 / 末轮重试 / HTML 产物预览 / 模型快速切换」四组对等能力
// 以 internal 扩展挂靠 VM（与 AgentMessageActions / AgentChatHtmlPreview
// 同款拆分模式），依赖成员已开放 internal。

/**
 * ① 后台完成通知（对齐 AgentChatEventApplier v1.4.4 #4）。
 *
 * Coding 长任务动辄数十轮迭代，切后台/锁屏后完成无感知是最痛的交互缺口。
 * 判定顺序与 Agent 屏一致：设置开关 → 用户是否在场 → 通知权限；三者全过
 * 才发射。通知是增益路径，任何一环不满足都静默跳过（绝不阻塞 Complete 收尾）。
 */
internal fun CodeViewModel.notifyTaskComplete(event: AgentEvent.Complete) {
    runCatching {
        val settingsSnapshot = settingsRepository.agentSettings.value
        if (settingsSnapshot.taskCompletionNotify &&
            !foregroundTracker.isForeground &&
            notifications.canPost(appContext)
        ) {
            val triggerText = _uiState.value.messages
                .lastOrNull { it.role == CodeChatMessage.Role.USER }
                ?.text?.trim()?.take(40)
            val title = triggerText?.takeIf { it.isNotBlank() }
                ?: appContext.getString(com.apex.agent.R.string.notif_task_done_fallback_title)
            notifications.notifyTaskDone(appContext, title, event.summary)
        }
    }
}

/**
 * ② 末轮重试（对齐 Agent 屏 regenerateResponse 的 Coding 语义）。
 *
 * 找到最后一条用户输入，截断其后全部条目（消息 + 时间轴双通道）后重发——
 * 工作流视角的「从这条需求重新跑一遍」。与 Agent 屏的差异：Coding 屏的
 * 既有产物（已落盘文件）不回滚，重跑是在既有工作区状态上的增量修复。
 *
 * - isRunning 门禁：运行中拒绝（重试会取消在途收集器，交错态不可预期）；
 * - 无用户输入（纯斜杠流水线开局）→ 提示后放弃。
 */
internal fun CodeViewModel.retryLastRun() {
    val state = _uiState.value
    if (state.isRunning) {
        appendSystemMessage("任务运行中，等结束后再重试")
        return
    }
    val lastUser = state.messages.lastOrNull { it.role == CodeChatMessage.Role.USER }
    if (lastUser == null || lastUser.text.isBlank()) {
        appendSystemMessage("没有可重试的用户请求")
        return
    }
    // 截断消息列表：移除该用户输入及其后全部（runEngine → beginRun 会重新
    // 追加用户气泡，两个通道口径一致、不产生重复气泡）。
    val userIdx = state.messages.indexOfLast { it.id == lastUser.id }
    _uiState.update { s -> s.copy(messages = s.messages.take(userIdx)) }
    streamSession.truncateFromLastUser()
    runEngine(lastUser.text, displayGoal = lastUser.text)
}

/**
 * 时间轴截断：移除最后一条用户气泡及其后全部条目（重试前调用；随后
 * [com.apex.agent.core.code.stream.CodeStreamSession.beginRun] 重新追加）。
 */
internal fun com.apex.agent.core.code.stream.CodeStreamSession.truncateFromLastUser() {
    val entries = snapshot().entries
    val lastUserIdx = entries.indexOfLast { it is StreamEntry.UserEntry }
    if (lastUserIdx >= 0) replaceAll(entries.take(lastUserIdx))
}

/**
 * ③ HTML 产物预览路径解析（对齐 AgentChatHtmlPreview 的 Coding 版）。
 *
 * Coding 工作区写出的 .html（Agent 前端产物场景）此前只能去终端 cat。
 * 解析规则：guest `/workspace/` 前缀 → 工作区根替换；宿主绝对路径直接
 * 验证；相对路径 → 当前激活工作区根下解析。不可预览（不存在/非 html）→ null。
 */
internal fun CodeViewModel.resolveHtmlPreviewPath(rawPath: String): String? {
    val root = workspaceRoots.activeRoot()
        ?: File(appContext.filesDir, "linux/workspaces/default")
    val candidate: File = when {
        rawPath.startsWith("/workspace/", ignoreCase = true) ->
            File(root, rawPath.removePrefix("/workspace/"))
        rawPath.startsWith("/") -> File(rawPath)
        else -> File(root, rawPath)
    }
    return if (HtmlArtifactDetector.isPreviewableHostFile(candidate.absolutePath)) {
        candidate.absolutePath
    } else null
}

// ═══════════════════════════════════════════════════════════════
// ④ 模型快速切换（对齐 Agent 屏「小大脑」菜单的数据通道）
// ═══════════════════════════════════════════════════════════════
// Coding 工位此前换模型要绕道设置页——深水档切 reasoning 强模型的高频
// 路径被切断。BrainMenuButton 是无状态组件，数据/回调全部经本节扩展从
// SettingsRepository 直取（Profile 持久化 + DynamicLlmClient 自动重建，
// 两工位共享同一 ModelRuntime，切换即下一轮生效）。

/** 当前选中的模型 Profile id（无状态派生：默认优先，空列表 → null）。 */
internal fun CodeViewModel.currentProfileIdOrNull(): String? {
    val list = settingsRepository.profiles.value
    return list.firstOrNull { it.isDefault }?.id ?: list.firstOrNull()?.id
}

/** 全部模型 Profile（菜单列表数据源；同一底层流，getter 安全）。 */
internal val CodeViewModel.profiles: StateFlow<List<ModelProfile>>
    get() = settingsRepository.profiles

/** 全部 Provider（模型列表展示 Provider 名）。 */
internal val CodeViewModel.providers: StateFlow<List<ProviderConfig>>
    get() = settingsRepository.providers

/**
 * 切换当前模型：设为默认 Profile + 引擎温度同步（[selectProfile]）。
 * 运行中的 LLM client 由 DynamicLlmClient 自动重建，即时生效。
 */
internal fun CodeViewModel.selectProfile(profileId: String) {
    val target = settingsRepository.getProfile(profileId) ?: return
    settingsRepository.setDefaultProfile(profileId)
    codeEngineImpl?.updateTemperature(target.temperature)
}

/** 更新当前模型的采样参数（Temperature / Top-P / Max Tokens；持久化即生效）。 */
internal fun CodeViewModel.updateModelParams(temperature: Float, topP: Float, maxTokens: Int) {
    val cur = settingsRepository.getProfile(currentProfileIdOrNull() ?: return) ?: return
    settingsRepository.upsertProfile(
        cur.copy(temperature = temperature, topP = topP, maxTokens = maxTokens)
    )
}
