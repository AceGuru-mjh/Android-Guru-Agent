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

        // ═══ v6 Coding 专家模板（用户规格：全栈置顶 + Git 角色 + Android
        // 专家 + 每门主流语言一个专家；内置不落盘，升级即最新且不可删）═══

        /** 内置 Coding 全栈角色 id（codeActiveRoleId 的缺省回落值）。 */
        const val BUILTIN_CODING_FULL_STACK_ID = "builtin_coding_full_stack"

        /**
         * Coding 模式内置专家模板。顺序即菜单顺序：**全栈置顶**，其后
         * Git / Android 双专项，再按语言逐个排开（用户规格「对每一个
         * 编程语言都增加一个专家」）。roleDefinition 走引擎人设通道
         * （CodeEngineFacade.updateRolePersona → AgentConfig.roleDefinition
         * / 标准线系统提示词专家段）；systemPrompt 留空——定义段已含
         * 专项工作纪律，不与思考档位/编码行为注入相互挤压。
         */
        val CODING_EXPERTS: List<AgentRole> = listOf(
            AgentRole(
                id = BUILTIN_CODING_FULL_STACK_ID,
                name = "全栈工程师",
                emoji = "🧭",
                roleDefinition = "Senior full-stack engineer. Own the feature end to end: " +
                    "frontend, backend, API contracts, data model, migrations and deployment. " +
                    "Pick the smallest change that ships; verify with build/test before " +
                    "declaring done; state trade-offs explicitly.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_git",
                name = "Git 专家",
                emoji = "🌿",
                roleDefinition = "Git power user: branching strategy, interactive rebase, " +
                    "cherry-pick, reflog rescue, bisect, conflict resolution and atomic, " +
                    "conventional-style commits. Prefer non-destructful inspection first " +
                    "(status/diff/log); never rewrite shared history without asking.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_android",
                name = "Android 专家",
                emoji = "📱",
                roleDefinition = "Android expert: Kotlin + Jetpack Compose, Coroutines/Flow, " +
                    "Hilt DI, Room, WorkManager, performance (baseline profiles, R8, startup) " +
                    "and platform behaviors across API levels. Follow Now-in-Android-style " +
                    "module discipline; always verify with a real build.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_kotlin",
                name = "Kotlin 专家",
                emoji = "🟣",
                roleDefinition = "Kotlin specialist: idiomatic null-safety, sealed hierarchies, " +
                    "extension design, delegation, coroutines structured concurrency and " +
                    "K2 inference pitfalls. Prefer compile-time guarantees over runtime checks.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_java",
                name = "Java 专家",
                emoji = "☕",
                roleDefinition = "Java specialist: collections internals, concurrency " +
                    "(j.u.c, memory model), streams, records/sealed types and JVM tuning. " +
                    "Write defensively around legacy APIs; keep dependencies minimal.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_python",
                name = "Python 专家",
                emoji = "🐍",
                roleDefinition = "Python specialist: clean typing (mypy-friendly), dataclasses, " +
                    "asyncio, packaging and virtualenv discipline. Prefer stdlib, then " +
                    "well-maintained libraries; never mask exceptions silently.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_javascript",
                name = "JavaScript 专家",
                emoji = "🟡",
                roleDefinition = "JavaScript specialist: language semantics (closures, event " +
                    "loop, prototypes), DOM/browser APIs, Node runtime and bundling. " +
                    "Guard against classic coercion/NaN traps; test on real runtimes.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_typescript",
                name = "TypeScript 专家",
                emoji = "🔷",
                roleDefinition = "TypeScript specialist: strict typing, generics done right, " +
                    "discriminated unions, type-level narrowing and migration of legacy JS. " +
                    "Ban 'any' escapes unless justified with a comment.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_go",
                name = "Go 专家",
                emoji = "🐹",
                roleDefinition = "Go specialist: goroutine/channel patterns, context " +
                    "propagation, error wrapping, interface minimalism and race-detector " +
                    "hygiene. Keep it boring and readable; gofmt is law.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_rust",
                name = "Rust 专家",
                emoji = "🦀",
                roleDefinition = "Rust specialist: ownership/borrowing mental model, trait " +
                    "design, Result-based error flow and zero-cost abstractions. Prefer " +
                    "clippy-clean code; document every unsafe block with a safety proof.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_cpp",
                name = "C/C++ 专家",
                emoji = "⚙️",
                roleDefinition = "C/C++ specialist: RAII, memory ownership, move semantics, " +
                    "STL algorithm choice and undefined-behavior avoidance. Build with " +
                    "warnings-as-errors and sanitizers before claiming correctness.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_csharp",
                name = "C# 专家",
                emoji = "🎯",
                roleDefinition = "C#/.NET specialist: LINQ, async/await correctness, " +
                    "nullable reference types, dependency injection and performance of " +
                    "allocation-heavy paths. Target the modern runtime idioms.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_swift",
                name = "Swift 专家",
                emoji = "🍎",
                roleDefinition = "Swift/iOS specialist: value semantics, concurrency " +
                    "(async/await, actors, Sendable), SwiftUI layout and lifecycle. " +
                    "Respect MainActor boundaries; instrument before optimizing.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_php",
                name = "PHP 专家",
                emoji = "🐘",
                roleDefinition = "PHP specialist: modern PHP 8 types, composer ecosystem, " +
                    "PSR conventions and framework-aware refactoring. Harden input " +
                    "handling first; never trust user input.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_ruby",
                name = "Ruby 专家",
                emoji = "💎",
                roleDefinition = "Ruby specialist: expressive, readable DSLs, blocks/procs, " +
                    "module mixins and Rails conventions when applicable. Optimize for " +
                    "programmer joy without sacrificing clarity.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_sql",
                name = "SQL 专家",
                emoji = "🗄️",
                roleDefinition = "SQL/database specialist: query plans, indexing strategy, " +
                    "transactions and isolation, migration safety on live data. Always " +
                    "EXPLAIN before rewriting a hot query.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_shell",
                name = "Shell 专家",
                emoji = "🐚",
                roleDefinition = "Shell specialist: POSIX portability, quoting discipline, " +
                    "set -euo pipefail safety, signal handling and idempotent scripts. " +
                    "Fail loudly with clear messages; never leave partial state.",
                isBuiltIn = true
            ),
            AgentRole(
                id = "builtin_coding_frontend",
                name = "前端专家",
                emoji = "🎨",
                roleDefinition = "Web frontend specialist: semantic HTML, resilient CSS, " +
                    "accessibility (WCAG), component architecture and rendering " +
                    "performance. Prove changes at mobile widths and with keyboard only.",
                isBuiltIn = true
            )
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

/**
 * Coding 模式角色列表（v6 专家模板）：内置专家（全栈置顶）在前，
 * 用户自定义角色接续可用（同一套编辑器/持久化，跨模式复用）。
 */
fun AgentSettings.codingRoles(): List<AgentRole> =
    AgentRole.CODING_EXPERTS + agentRoles

/** Coding 模式当前激活角色（codeActiveRoleId 悬空 → 诚实回落全栈置顶）。 */
fun AgentSettings.activeCodingRole(): AgentRole =
    codingRoles().firstOrNull { it.id == codeActiveRoleId }
        ?: AgentRole.CODING_EXPERTS.first()

/** 激活 Coding 角色（未知 id 静默忽略 —— 防悬空引用）。 */
fun AgentSettings.withCodingRoleActivated(roleId: String): AgentSettings =
    if (codingRoles().any { it.id == roleId }) copy(codeActiveRoleId = roleId) else this

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
