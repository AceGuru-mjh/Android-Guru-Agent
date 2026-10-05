package com.apex.agent.core.engine.modes

// 迁自 main/ModePresets.kt（2026-10 死代码清偿）：生产链路的预设持久化由
// app 层 SettingsRepository（AgentSettings.customModePresets）直接承载，
// core 侧本接口与内存实现仅被 ModePresetsTest 消费 —— 归位测试源集。

/**
 * 预设持久化边界接口（#168）。
 *
 * core 侧只定义契约 + 内存实现（单测用）；真实持久化由 app 层
 * SettingsRepository 承载（AgentSettings.customModePresets 字段随
 * agent_settings_v2 JSON 一起落盘）——core 不感知 Android 存储。
 */
interface ModePresetStore {
    /** 读取全部用户预设（不含内置；内置由 [BuiltinModePresets.ALL] 静态提供）。 */
    fun load(): List<ModePreset>

    /** 全量覆写用户预设列表（upsert/remove 的底层原语）。 */
    fun save(list: List<ModePreset>)
}

/** 内存实现（测试 / 预览用；save 即全量替换，load 返回快照副本）。 */
class InMemoryModePresetStore(
    initial: List<ModePreset> = emptyList()
) : ModePresetStore {
    private val presets = initial.toMutableList()

    override fun load(): List<ModePreset> = presets.toList()

    override fun save(list: List<ModePreset>) {
        presets.clear()
        presets.addAll(list)
    }
}
