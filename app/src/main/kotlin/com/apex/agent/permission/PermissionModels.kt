package com.apex.agent.permission

import kotlinx.serialization.Serializable

/**
 * ═══ opencode 式权限模式（Issue #155）—— 数据模型 ═══
 *
 * 三层结构自底向上：
 *  1. 本文件（PermissionModels.kt）—— 可序列化数据模型 + 规则匹配器，
 *     供 AgentSettings 持久化引用（规则列表本身就是 JSON 落盘的一部分）；
 *  2. [PermissionDecider] —— 纯 Kotlin 决策引擎（零 Android / 零 IO 依赖），
 *     模式 + 规则 + 工具上下文 → 四态决策；
 *  3. [PermissionModeGate] —— 挂进 v3 执行管线的门（含会话记忆与
 *     用户询问闭环），由 [PermissionAwareToolGate] 与既有风险门组合。
 *
 * 语义对齐 opencode 的 permission 三元组：每条规则是「模式 → 效应」，
 * 效应只有 allow / ask / deny 三种；模式给出兜底策略，规则可越级
 * （在 DEFAULT 下显式放行非只读工具，或在任意模式下收紧到 ASK）。
 *
 * ── 权限模式（opencode 语义）──
 *
 * - [PermissionMode.BYPASS]：全放行——不做任何询问与拦截（危险，仅供信任场景）；
 * - [PermissionMode.DEFAULT]：规则 + 按需询问——只读工具默认放行（仍交后续
 *   风险门把关），其余一律先问用户；显式 ALLOW 规则可越级放行；
 * - [PermissionMode.ACCEPT_EDITS]：编辑类自动放行——文件编辑 / git 提交等
 *   写操作不再询问，其余非只读工具仍先问；
 * - [PermissionMode.PLAN]：只读模式——只读工具默认放行，任何会修改环境的
 *   工具直接拒绝（规则也不可越级，保住「规划阶段零副作用」的硬承诺）。
 */
@Serializable
enum class PermissionMode {
    BYPASS,
    DEFAULT,
    ACCEPT_EDITS,
    PLAN
}

/**
 * 规则效应：命中一条规则后对工具的处置。
 *
 * - [ALLOW]：放行（显式放行，可跳过后续风险门）；
 * - [ASK]：询问用户（弹授权对话框，支持会话记忆）；
 * - [DENY]：拒绝（带面向模型的中文原因）。
 */
@Serializable
enum class PermissionEffect {
    ALLOW,
    ASK,
    DENY
}

/**
 * 单条权限规则：模式 + 效应。
 *
 * @param pattern 工具 id 模式——精确 id（如 code_edit），或前缀后接单个
 *   星号字符的尾缀通配（如 code_git_ 尾星、mcp__ 尾星）；单独一个星号
 *   字符匹配一切工具。完整匹配规则见 [PermissionRuleMatcher]。
 * @param effect 命中后的处置动作。
 */
@Serializable
data class PermissionRule(
    val pattern: String,
    val effect: PermissionEffect
)

/**
 * 规则模式匹配器（纯函数，无状态）。
 *
 * 匹配语义（对齐 opencode，刻意保持简单）：
 *  - 模式非法（空串 / 纯空白）→ 永不匹配；
 *  - 单独一个星号字符 → 匹配一切工具（用户显式全放行的快捷方式）；
 *  - 以星号字符结尾 → 去掉末尾星号后做前缀匹配（尾缀通配），
 *    服务 MCP 一等工具的动态注册（mcp__github__ 前缀可命中
 *    mcp__github__create_issue 这类运行期才知道的 id）；
 *  - 其余 → 精确 id 相等（大小写敏感）。
 *
 * 不支持中间通配——工具 id 是受控命名空间，前缀 + 精确两级已覆盖
 * 全部真实需求，实现与审计都保持可穷举。
 */
object PermissionRuleMatcher {

    /**
     * 判断 [pattern] 是否命中 [toolId]。
     *
     * 线程安全：纯字符串运算，无共享状态。
     */
    fun matches(pattern: String, toolId: String): Boolean {
        // 模式非法：空串或纯空白永不匹配（防御设置层写入脏数据）
        if (pattern.isBlank()) return false
        // 单独一个星号字符 = 通配一切
        if (pattern == WILDCARD) return true
        return if (pattern.endsWith(WILDCARD)) {
            // 尾缀通配：前缀匹配（前缀为空的情况已被上面单星分支拦截）
            val prefix = pattern.dropLast(1)
            prefix.isNotEmpty() && toolId.startsWith(prefix)
        } else {
            // 精确匹配，大小写敏感
            pattern == toolId
        }
    }

    /** 通配星号字符（常量单源，避免魔法字符散落）。 */
    private const val WILDCARD = "*"
}
