package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.AgentModePresetListTool
import com.apex.agent.core.tools.builtin.AgentModePresetSelectTool
import com.apex.agent.core.tools.builtin.AgentProfileListTool
import com.apex.agent.core.tools.builtin.AgentProfileSetDefaultTool
import com.apex.agent.core.tools.builtin.AgentProviderSetKeyTool
import com.apex.agent.core.tools.builtin.AgentRoleActivateTool
import com.apex.agent.core.tools.builtin.AgentRoleListTool
import com.apex.agent.core.tools.builtin.AgentSettingGetTool
import com.apex.agent.core.tools.builtin.AgentSettingSetTool
import com.apex.agent.core.tools.builtin.AlarmSetTool
import com.apex.agent.core.tools.builtin.AppDownloadInstallTool
import com.apex.agent.core.tools.builtin.AppInstallApkTool
import com.apex.agent.core.tools.builtin.CalendarEventListTool
import com.apex.agent.core.tools.builtin.ContactSearchTool
import com.apex.agent.core.tools.builtin.FilePickTool
import com.apex.agent.core.tools.builtin.ImageCaptureTool
import com.apex.agent.core.tools.builtin.NotificationPostTool
import com.apex.agent.core.tools.builtin.SmsSendTool
import com.apex.agent.tools.AgentSetupHost
import com.apex.agent.tools.AndroidApkInstaller
import com.apex.agent.tools.AlarmSetter
import com.apex.agent.tools.CalendarReader
import com.apex.agent.tools.CameraCapturer
import com.apex.agent.tools.ContactsReader
import com.apex.agent.tools.FilePicker
import com.apex.agent.tools.NotificationPoster
import com.apex.agent.tools.SmsSender
import com.apex.agent.tools.activateRoleAsync
import com.apex.agent.tools.listModePresetSummaries
import com.apex.agent.tools.listProfileSummaries
import com.apex.agent.tools.listRoleSnapshot
import com.apex.agent.tools.selectModePresetAsync
import com.apex.agent.tools.setDefaultProfileAsync
import com.apex.agent.tools.setProviderApiKeyAsync
import com.apex.agent.ui.screen.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.io.File

/**
 * # v5 Capability Enhancement — Tool Registration
 *
 * 拆出 [ToolModule.provideToolRegistry] 的 v5 三段注册（§14e / §14f / §14g）。
 *
 * SRP 预算原因：主注册文件已逼近 1200 行预算（[scripts/check_file_size.sh]
 * 强制），新工具族（18 个）单独成段，与既有
 * [ToolModuleJvmToolsRegistration] / [ToolModuleRegistrySections] 同款拆分模式。
 *
 * 三段：
 * - **§14e**：Agent 自主设置（9 工具）——agent_setting_get/set、agent_profile_*、
 *   agent_provider_set_key、agent_role_*、agent_mode_preset_*
 * - **§14f**：下载 + 安装 APK（2 工具）——app_install_apk / app_download_install
 * - **§14g**：更多能力（7 工具）——contact_search / sms_send / calendar_event_list /
 *   alarm_set / file_pick / image_capture / notification_post
 *
 * 设计文档：[docs/v5-capability-enhancement.md]
 */
internal fun registerV5EnhancementTools(
    registry: ToolRegistry,
    context: Context,
    httpClient: OkHttpClient,
    downloadDir: File,
    settingsRepository: SettingsRepository
) {
    // ═══ §14e. v5 — Agent 自主设置（9 个工具，让 agent 自己读写应用配置）═══
    // 用户原始诉求："能让 agent 自行帮你完成设置，开启自己软件任何设置"。
    // 工具本体见 [AgentSetupTools.kt]——纯 JVM，参数解析 + 字段白名单校验。
    // 持久化经 [AgentSetupHost] lambda 桥接 [SettingsRepository]。
    //
    // 安全契约：仅暴露 [AgentSettingsPatch] 白名单字段，permissionMode /
    // permissionRules / mcpScopeIsolation 等安全字段永不被工具修改。
    val agentSetupHost = AgentSetupHost(settingsRepository)
    registry.register(SafeAgentTool(AgentSettingGetTool(agentSetupHost)))
    registry.register(SafeAgentTool(AgentSettingSetTool(agentSetupHost)))
    registry.register(SafeAgentTool(AgentProfileListTool {
        settingsRepository.listProfileSummaries()
    }))
    registry.register(SafeAgentTool(AgentProfileSetDefaultTool(
        setDefault = { id -> settingsRepository.setDefaultProfileAsync(id) },
        listProvider = { settingsRepository.listProfileSummaries() }
    )))
    registry.register(SafeAgentTool(AgentProviderSetKeyTool(
        setKey = { providerId, apiKey -> settingsRepository.setProviderApiKeyAsync(providerId, apiKey) }
    )))
    registry.register(SafeAgentTool(AgentRoleListTool {
        settingsRepository.listRoleSnapshot()
    }))
    registry.register(SafeAgentTool(AgentRoleActivateTool(
        activator = { role, profileId -> settingsRepository.activateRoleAsync(role, profileId) }
    )))
    registry.register(SafeAgentTool(AgentModePresetListTool {
        settingsRepository.listModePresetSummaries()
    }))
    registry.register(SafeAgentTool(AgentModePresetSelectTool(
        selector = { id -> settingsRepository.selectModePresetAsync(id) }
    )))

    // ═══ §14f. v5 — 下载 + 安装 APK（2 个工具，无需 Shizuku/root）═══
    // 用户原始诉求："增加 download 工具，可以利用网页自动化安装应用"。
    // - app_install_apk：本地 APK → PackageInstaller（无特权安装路径）
    // - app_download_install：URL → 下载 → 校验 → 安装（一站式）
    // 与现有 app_install（pm install，需 Shizuku/root）互补。
    val apkInstaller = AndroidApkInstaller(context)
    registry.register(SafeAgentTool(AppInstallApkTool(apkInstaller)))
    registry.register(SafeAgentTool(AppDownloadInstallTool(httpClient, downloadDir, apkInstaller)))

    // ═══ §14g. v5 — 更多能力（7 个工具：联系人/SMS/日历/闹钟/文件选择/拍照/通知）═══
    // 与 #172 高级设备工具包（torch/vibrate/battery_status/network_info/
    // tts_speak/share_content/deep_link/image_info/image_convert）互补。
    // 风险与权限：每个工具的元数据显式声明风险级别与必要权限（用户/会话
    // 门控会处理 HIGH/MEDIUM 的弹窗确认）。
    val contactsReader = ContactsReader(context)
    val smsSender = SmsSender(context)
    val calendarReader = CalendarReader(context)
    val alarmSetter = AlarmSetter(context)
    val filePicker = FilePicker(context)
    val cameraCapturer = CameraCapturer(context)
    val notificationPoster = NotificationPoster(context)
    registry.register(SafeAgentTool(ContactSearchTool(
        searcher = { query, limit -> contactsReader.search(query, limit) }
    )))
    registry.register(SafeAgentTool(SmsSendTool(
        sender = { phone, message, slotId -> smsSender.send(phone, message, slotId) }
    )))
    registry.register(SafeAgentTool(CalendarEventListTool(
        lister = { daysAhead, limit -> calendarReader.list(daysAhead, limit) }
    )))
    registry.register(SafeAgentTool(AlarmSetTool(
        setter = { params -> alarmSetter.set(params) }
    )))
    registry.register(SafeAgentTool(FilePickTool(
        picker = { mime, multi -> filePicker.pick(mime, multi) }
    )))
    registry.register(SafeAgentTool(ImageCaptureTool(
        capturer = { params -> cameraCapturer.capture(params) }
    )))
    registry.register(SafeAgentTool(NotificationPostTool(
        poster = { params -> notificationPoster.post(params) }
    )))
}
