package com.apex.agent.core.code.standard

/**
 * # Standard Permission Source — 标准线权限配置源
 *
 * 设置层 → 引擎的**只读快照通道**：权限模式与规则不进引擎构造参数，
 * 而是每轮任务开始前经本源拉取最新快照——用户改设置即时生效，无需
 * 重启会话/重建引擎。
 *
 * ## 接线方式（与 app 层 PermissionModeGate 的 settingsProvider 同款模式）
 *
 * - 引擎只依赖本接口（core 层保持零 Android/设置层依赖）；
 * - DI 在 App 层把 [snapshot] 接到 SettingsRepository.agentSettings
 *   （PermissionSnapshot(mode, rules) 拍平为本 [Snapshot]）；
 * - 无设置层注入时用 [NONE]（空源 = 纯模式兜底，引擎单测/预览场景）。
 *
 * ## 模式与 AgentMode 硬门的正交性
 *
 * - [Snapshot.mode] 是**用户信任偏好**（BYPASS / DEFAULT / ACCEPT_EDITS /
 *   PLAN 四档兜底策略，设置层持久化，本源下发）；
 * - AgentMode.PLAN（任务规划档）是**任务阶段硬约束**，由引擎经
 *   [StandardPermissionEngine.decide] 的 `planGate` 参数独立传入——
 *   两者互不覆盖：PLAN 档只读硬门在任何权限模式下都拒写副作用，
 *   权限模式则决定"规划之外"的兜底询问策略。
 */
interface StandardPermissionSource {

    /** 当前配置快照（模式 + 工具级规则 + 命令级规则）。 */
    fun snapshot(): Snapshot

    /**
     * 一次拉取的权限配置（不可变值对象；引擎按需整体替换内部状态，
     * 避免增量同步的中间态）。
     */
    data class Snapshot(
        val mode: StandardPermissionMode = StandardPermissionMode.DEFAULT,
        val rules: List<StandardPermissionRule> = emptyList(),
        val commandRules: List<StandardPermissionRule> = emptyList()
    )

    companion object {
        /** 空源（引擎单测/无设置层注入时的默认——纯模式兜底）。 */
        val NONE: StandardPermissionSource = object : StandardPermissionSource {
            override fun snapshot() = Snapshot()
        }
    }
}
