package com.apex.agent.tools

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.engine.modes.ModePreset
import com.apex.agent.core.tools.builtin.AgentRoleConfigSnapshot
import com.apex.agent.core.tools.builtin.AgentSettingsHost
import com.apex.agent.core.tools.builtin.AgentSettingsPatch
import com.apex.agent.core.tools.builtin.AgentSettingsSnapshot
import com.apex.agent.core.tools.builtin.ModelProfileSummary
import com.apex.agent.core.tools.builtin.ModePresetSummary
import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AgentSettingsHost] 的 Android 实现。
 *
 * 桥接 [SettingsRepository] 的运行时 Settings StateFlow —— 读取快照
 * 由 [AgentSettings] → [AgentSettingsSnapshot] 转换；写入补丁
 * 由 [AgentSettingsPatch] → [AgentSettings.update{}] 转换。
 *
 * **安全契约**：仅暴露 [AgentSettingsPatch] 中显式声明的字段，未知字段
 * （如 `permissionMode` / `permissionRules` / `mcpScopeIsolation` 等安全
 * 字段）永不被工具修改。
 */
class AgentSetupHost(
    private val settingsRepository: SettingsRepository
) : AgentSettingsHost {

    override suspend fun snapshot(): AgentSettingsSnapshot = withContext(Dispatchers.IO) {
        val s = settingsRepository.agentSettings.value
        AgentSettingsSnapshot(
            defaultMode = s.defaultMode,
            thinkLevel = s.thinkLevel,
            thinkingLevelOverride = s.thinkingLevelOverride,
            forceDeepThinking = s.forceDeepThinking,
            codeThinkingLogic = s.codeThinkingLogic,
            codeThinkingLevel = s.codeThinkingLevel,
            codeExecutionMode = s.codeExecutionMode,
            maxIterations = s.maxIterations,
            keepAlive = s.keepAlive,
            autoRetry = s.autoRetry,
            maxRetryPerAction = s.maxRetryPerAction,
            loopDetection = s.loopDetection,
            loopDetectionWindow = s.loopDetectionWindow,
            sameActionThreshold = s.sameActionThreshold,
            autoRecovery = s.autoRecovery,
            parallelToolExecution = s.parallelToolExecution,
            taskCompletionNotify = s.taskCompletionNotify,
            maxContextTokens = s.maxContextTokens,
            compressionThreshold = s.compressionThreshold,
            preserveRecentTurns = s.preserveRecentTurns,
            maxToolOutputLength = s.maxToolOutputLength,
            reflectionRounds = s.reflectionRounds,
            selectedModePresetId = s.selectedModePresetId,
            themeMode = s.themeMode,
            accentPalette = s.accentPalette,
            fontScale = s.fontScale,
            showTimestamps = s.showTimestamps,
            language = s.language,
            sendKeyBehavior = s.sendKeyBehavior,
            showRunSummary = s.showRunSummary
        )
    }

    override suspend fun apply(patch: AgentSettingsPatch): Boolean = withContext(Dispatchers.IO) {
        try {
            settingsRepository.updateAgentSettings { current ->
                current.copy(
                    defaultMode = patch.defaultMode ?: current.defaultMode,
                    thinkLevel = patch.thinkLevel ?: current.thinkLevel,
                    thinkingLevelOverride = patch.thinkingLevelOverride ?: current.thinkingLevelOverride,
                    forceDeepThinking = patch.forceDeepThinking ?: current.forceDeepThinking,
                    codeThinkingLogic = patch.codeThinkingLogic ?: current.codeThinkingLogic,
                    codeThinkingLevel = patch.codeThinkingLevel ?: current.codeThinkingLevel,
                    codeExecutionMode = patch.codeExecutionMode ?: current.codeExecutionMode,
                    maxIterations = patch.maxIterations ?: current.maxIterations,
                    keepAlive = patch.keepAlive ?: current.keepAlive,
                    autoRetry = patch.autoRetry ?: current.autoRetry,
                    maxRetryPerAction = patch.maxRetryPerAction ?: current.maxRetryPerAction,
                    loopDetection = patch.loopDetection ?: current.loopDetection,
                    loopDetectionWindow = patch.loopDetectionWindow ?: current.loopDetectionWindow,
                    sameActionThreshold = patch.sameActionThreshold ?: current.sameActionThreshold,
                    autoRecovery = patch.autoRecovery ?: current.autoRecovery,
                    parallelToolExecution = patch.parallelToolExecution ?: current.parallelToolExecution,
                    taskCompletionNotify = patch.taskCompletionNotify ?: current.taskCompletionNotify,
                    maxContextTokens = patch.maxContextTokens ?: current.maxContextTokens,
                    compressionThreshold = patch.compressionThreshold ?: current.compressionThreshold,
                    preserveRecentTurns = patch.preserveRecentTurns ?: current.preserveRecentTurns,
                    maxToolOutputLength = patch.maxToolOutputLength ?: current.maxToolOutputLength,
                    reflectionRounds = patch.reflectionRounds ?: current.reflectionRounds,
                    selectedModePresetId = patch.selectedModePresetId ?: current.selectedModePresetId,
                    themeMode = patch.themeMode ?: current.themeMode,
                    accentPalette = patch.accentPalette ?: current.accentPalette,
                    fontScale = patch.fontScale ?: current.fontScale,
                    showTimestamps = patch.showTimestamps ?: current.showTimestamps,
                    language = patch.language ?: current.language,
                    sendKeyBehavior = patch.sendKeyBehavior ?: current.sendKeyBehavior,
                    showRunSummary = patch.showRunSummary ?: current.showRunSummary
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 列出模型 Profile 摘要——从 [SettingsRepository.profiles] 转换。
 */
suspend fun SettingsRepository.listProfileSummaries(): List<ModelProfileSummary> =
    withContext(Dispatchers.IO) {
        profiles.value.map { p: ModelProfile ->
            ModelProfileSummary(
                id = p.id,
                name = p.name,
                providerId = p.providerId,
                modelId = p.modelId,
                isDefault = p.isDefault,
                contextWindow = p.contextWindow,
                capabilities = p.capabilities.summary(),
                temperature = p.temperature.toDouble(),
                maxTokens = p.maxOutputTokens
            )
        }
    }

/**
 * 设置默认 Profile 的便捷封装（带返回值）。
 */
suspend fun SettingsRepository.setDefaultProfileAsync(id: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = profiles.value.any { it.id == id }
            if (!exists) return@withContext false
            setDefaultProfile(id)
            true
        } catch (_: Throwable) {
            false
        }
    }

/**
 * 列出 Agent 角色绑定快照。
 */
suspend fun SettingsRepository.listRoleSnapshot(): AgentRoleConfigSnapshot =
    withContext(Dispatchers.IO) {
        val r = roles.value
        AgentRoleConfigSnapshot(
            primaryProfileId = r.primaryProfileId,
            visionProfileId = r.visionProfileId,
            reasoningProfileId = r.reasoningProfileId,
            fastProfileId = r.fastProfileId,
            summaryProfileId = r.summaryProfileId
        )
    }

/**
 * 修改角色绑定——把 role 字段映射到 ModelRoleConfig 对应字段。
 */
suspend fun SettingsRepository.activateRoleAsync(role: String, profileId: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = profiles.value.any { it.id == profileId }
            if (!exists) return@withContext false
            updateRoles { current ->
                when (role) {
                    "primary" -> current.copy(primaryProfileId = profileId)
                    "vision" -> current.copy(visionProfileId = profileId)
                    "reasoning" -> current.copy(reasoningProfileId = profileId)
                    "fast" -> current.copy(fastProfileId = profileId)
                    "summary" -> current.copy(summaryProfileId = profileId)
                    else -> current
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

/**
 * 设置 Provider API Key。
 */
suspend fun SettingsRepository.setProviderApiKeyAsync(providerId: String, apiKey: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = providers.value.any { it.id == providerId }
            if (!exists) return@withContext false
            setProviderApiKey(providerId, apiKey)
            true
        } catch (_: Throwable) {
            false
        }
    }

/**
 * 列出模式预设摘要（内置 + 用户自定义）。
 */
suspend fun SettingsRepository.listModePresetSummaries(): List<ModePresetSummary> =
    withContext(Dispatchers.IO) {
        // 自定义预设（持久化的）
        val userPresets = agentSettings.value.customModePresets.map { p: ModePreset ->
            ModePresetSummary(
                id = p.id,
                name = p.name,
                builtin = p.builtin,
                summary = p.summary()
            )
        }
        // 内置预设（运行时合成）
        val builtin = com.apex.agent.core.engine.modes.BuiltinModePresets.ALL.map { p ->
            ModePresetSummary(
                id = p.id,
                name = p.name,
                builtin = true,
                summary = p.summary()
            )
        }
        builtin + userPresets
    }

/**
 * 选中模式预设。
 */
suspend fun SettingsRepository.selectModePresetAsync(presetId: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            // 空串 = 清除
            if (presetId.isBlank()) {
                updateAgentSettings { it.copy(selectedModePresetId = "") }
                return@withContext true
            }
            // 校验：必须是内置或用户预设 id
            val all = listModePresetSummaries()
            if (all.none { it.id == presetId }) return@withContext false
            updateAgentSettings { it.copy(selectedModePresetId = presetId) }
            true
        } catch (_: Throwable) {
            false
        }
    }
