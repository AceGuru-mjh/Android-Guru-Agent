package com.apex.agent.ui.screen.settings

/**
 * ═══ Agent 角色（人设）数据模型 ═══
 *
 * 应用户需求：「设置中增加 agent 角色 —— 内置的是全能 agent，用户可自定义。
 * 自定义需要填：agent 名字、agent 对你的称呼、提示词、角色定义，以及更多
 * 自定义的选项。」
 *
 * 架构位：
 *  - 本类属 app 层（UI + 持久化），[com.apex.agent.core.engine.AgentConfig]
 *    只消费拍平后的字符串字段（agentName/userTitle/roleDefinition/rolePrompt/
 *    roleStyle/roleLanguage）—— 引擎不感知数据模型，模块边界防腐；
 *  - 持久化：[AgentSettings.agentRoles]（仅自定义角色）+ [AgentSettings.activeRoleId]
 *    （kotlinx.serialization JSON，ignoreUnknownKeys 加 schema 演进）；
 *  - 内置角色（[ALL_ROUNDER]）不落盘、运行时合成 —— 升级永远拿最新定义，
 *    且不可删除/编辑（UI 锁定，可「另存为」自定义副本）；
 *  - 生效路径：AgentModule 启动快照（重启生效）+ AgentChatViewModel 监听
 *    agentSettings 变化 patchConfig（运行时热切换，无需重启）。
 *
 * @param id            稳定标识（自定义角色用 role_<timestamp> 派生）
 * @param name          agent 名字（自称/身份行 "You are <name>"）—— 必填
 * @param userTitle     agent 对用户的称呼（如「老板」）；空 = 不约束
 * @param emoji         角色图标（聊天顶栏胶囊 + 角色列表展示）
 * @param roleDefinition 角色定义（这个 agent 是谁、擅长什么、边界在哪）
 * @param systemPrompt  提示词（用户自定义提示词，原样拼入 Agent Role 段）
 * @param style         语气风格键："" | professional | friendly | humorous | concise
 * @param replyLanguage 回复语言键："" 跟随用户输入 | zh | en
 * @param isBuiltIn     内置角色标记（不可删除/不可编辑，只能另存为）
 */
@kotlinx.serialization.Serializable
data class AgentRole(
    val id: String,
    val name: String,
    val userTitle: String = "",
    val emoji: String = "🤖",
    val roleDefinition: String = "",
    val systemPrompt: String = "",
    val style: String = "",
    val replyLanguage: String = "",
    val isBuiltIn: Boolean = false
) {
    companion object {
        /** 内置全能角色 id（持久化的 activeRoleId 缺省值）。 */
        const val BUILTIN_ALL_ROUNDER_ID = "builtin_all_rounder"

        /**
         * 内置角色：全能 Agent —— 与历史默认行为完全一致的人设显式化。
         * agentName 为空串 → 引擎身份行回落 "Apex Agent"（零行为变化），
         * 其余字段仅做正面的能力自述（不收窄能力）。
         */
        val ALL_ROUNDER: AgentRole = AgentRole(
            id = BUILTIN_ALL_ROUNDER_ID,
            name = "Apex Agent",
            emoji = "⚡",
            userTitle = "",
            roleDefinition = "All-round autonomous agent: coding, shell, web, files, " +
                "device control, memory and task planning — pick the best tool for any job.",
            systemPrompt = "",
            style = "",
            replyLanguage = "",
            isBuiltIn = true
        )

        /** 新建自定义角色的 id 派生。 */
        fun newId(): String = "role_${System.currentTimeMillis()}"
    }
}

// ───────────────────────── AgentSettings 角色视图（运行时合成） ─────────────────────────

/** 全量角色列表：内置在前 + 自定义在后（内置不落盘，升级即最新）。 */
fun AgentSettings.allRoles(): List<AgentRole> =
    listOf(AgentRole.ALL_ROUNDER) + agentRoles

/** 当前激活角色（activeRoleId 悬空/被删 → 诚实回落内置全能角色）。 */
fun AgentSettings.activeRole(): AgentRole =
    allRoles().firstOrNull { it.id == activeRoleId } ?: AgentRole.ALL_ROUNDER

// ───────────────────────── 角色变更操作（AgentSettings 副本语义） ─────────────────────────

/** 新增/更新自定义角色（内置 id 落入会被忽略 —— 内置不可编辑）。 */
fun AgentSettings.withRoleUpserted(role: AgentRole): AgentSettings {
    require(!role.isBuiltIn) { "内置角色不可编辑（只可另存为自定义副本）" }
    val sanitized = role.copy(
        id = role.id.ifBlank { AgentRole.newId() },
        name = role.name.trim(),
        userTitle = role.userTitle.trim(),
        emoji = role.emoji.trim().ifBlank { "🤖" }.take(4),
        roleDefinition = role.roleDefinition.trim(),
        systemPrompt = role.systemPrompt.trim(),
        style = role.style,
        replyLanguage = role.replyLanguage
    )
    if (sanitized.name.isEmpty()) return this  // 名字必填：空名静默拒绝（UI 层有校验）
    val next = if (agentRoles.any { it.id == sanitized.id }) {
        agentRoles.map { if (it.id == sanitized.id) sanitized else it }
    } else {
        agentRoles + sanitized
    }
    return copy(agentRoles = next)
}

/** 删除自定义角色；若删除的是激活角色 → 激活回落内置全能。 */
fun AgentSettings.withRoleRemoved(roleId: String): AgentSettings {
    if (agentRoles.none { it.id == roleId }) return this
    val next = agentRoles.filter { it.id != roleId }
    return copy(
        agentRoles = next,
        activeRoleId = if (activeRoleId == roleId) AgentRole.BUILTIN_ALL_ROUNDER_ID else activeRoleId
    )
}

/** 激活角色（未知 id 静默忽略 —— 防悬空引用）。 */
fun AgentSettings.withRoleActivated(roleId: String): AgentSettings =
    if (allRoles().any { it.id == roleId }) copy(activeRoleId = roleId) else this
