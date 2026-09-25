package com.apex.agent.permission

/**
 * ═══ opencode 式权限模式（Issue #155）—— 决策引擎 ═══
 *
 * 纯 Kotlin 决策核心：零 Android / 零网络 / 零 IO 依赖，
 * 输入（模式 + 规则 + 工具上下文）完全确定输出，
 * 同一输入永远得到同一决策——这使它可以在 JVM 单测里穷举全部矩阵，
 * 也使 [PermissionModeGate] 的交互闭环（询问 / 记忆 / 组合）可以被
 * 独立于决策逻辑验证。
 *
 * 决策表（按序短路，命中即返回）：
 *
 *  1. BYPASS 模式 → 一律 [PermissionDecision.AllowExplicit]
 *     （全放行是模式的显式承诺，规则无需再看）；
 *  2. PLAN 模式 → 只读 [PermissionDecision.AllowDefault]，
 *     非只读 [PermissionDecision.Deny]（中文原因）——规则不可越级，
 *     保住规划阶段零副作用的硬承诺；
 *  3. 规则按序首匹配 → ALLOW 映射 [PermissionDecision.AllowExplicit]、
 *     ASK 映射 [PermissionDecision.Ask]、DENY 映射 [PermissionDecision.Deny]；
 *  4. ACCEPT_EDITS 模式默认 → 只读 AllowExplicit；编辑写类
 *     （见 [EDIT_LIKE_PREFIXES]）AllowExplicit；其余 Ask；
 *  5. DEFAULT 模式默认 → 只读 [PermissionDecision.AllowDefault]；
 *     其余（含破坏性 / 敏感操作）一律 Ask——即「DEFAULT 下非只读
 *     一律询问，显式规则才可越级放行」的 opencode 语义。
 */
/**
 * 一次待决工具调用的上下文快照。
 *
 * @param toolId 工具 id（规则匹配的键）。
 * @param readOnlyHint 工具注解：是否只读（不改环境）——来自
 *   [ToolMetadata][com.apex.agent.core.tools.ToolMetadata] 的
 *   [annotations][com.apex.agent.core.tools.ToolMetadata.annotations].readOnlyHint。
 * @param destructiveHint 工具注解：成功时效果是否破坏性 / 不可逆。
 * @param sensitiveAction 工具注解：是否需要用户逐次明示同意的敏感动作
 *   （如读取通知）——敏感但未必破坏性，同样值得一次询问。
 */
data class PermissionContext(
    val toolId: String,
    val readOnlyHint: Boolean,
    val destructiveHint: Boolean,
    val sensitiveAction: Boolean
)

/**
 * 决策引擎输出的四态决策。
 *
 * 「显式」与「默认」放行的区分是组合器的编排依据：
 *  - [AllowExplicit]：规则或模式**显式**承诺放行——用户意志已表达
 *    （点过规则、选过模式、答过授权），可跳过后续风险门，避免双弹窗；
 *  - [AllowDefault]：模式**默认**放行（DEFAULT / PLAN 下的只读工具）——
 *    没有任何用户意志参与，仍应交给后续门（如 RiskAwareToolGate）
 *    继续把关（只读但高风险的工具，如读取通知，仍会被问一次）；
 *  - [Deny]：拒绝，[reason] 面向模型（中文，含可执行指引）；
 *  - [Ask]：需要用户确认——由门层弹授权对话框（含会话记忆）。
 */
sealed class PermissionDecision {
    object AllowExplicit : PermissionDecision() {
        override fun toString(): String = "AllowExplicit"
    }

    object AllowDefault : PermissionDecision() {
        override fun toString(): String = "AllowDefault"
    }

    data class Deny(val reason: String) : PermissionDecision()

    object Ask : PermissionDecision() {
        override fun toString(): String = "Ask"
    }
}

/**
 * 纯函数决策引擎（无状态，线程安全）。
 */
object PermissionDecider {

    /**
     * 编辑写类工具的 id 前缀集合（公开常量：ACCEPT_EDITS 模式的判定依据，
     * 也是设置 UI 提示文案与单测断言的单一事实源）。
     *
     * 覆盖 Coding 模式的 code_ 写工具族与 Agent 模式的传统文件写工具族：
     * code_edit / code_write / code_git_commit / code_git_branch /
     * write_file / edit_file / delete_file / copy_move_file。
     * 注意 code_git_status / code_git_diff / code_git_log 等只读 git 工具
     * 不在列——它们靠 readOnlyHint 在判定顺序的第一层就被放行。
     */
    val EDIT_LIKE_PREFIXES: List<String> = listOf(
        "code_edit",
        "code_write",
        "code_git_commit",
        "code_git_branch",
        "write_file",
        "edit_file",
        "delete_file",
        "copy_move_file"
    )

    /**
     * 判断工具 id 是否属于编辑写类（前缀匹配 [EDIT_LIKE_PREFIXES] 任一）。
     */
    fun isEditLike(toolId: String): Boolean =
        EDIT_LIKE_PREFIXES.any { toolId.startsWith(it) }

    /**
     * 决策主入口：模式 + 规则 + 上下文 → 四态决策。
     *
     * 求值顺序见文件头决策表；BYPASS 与 PLAN 在规则之前短路，
     * 规则在 ACCEPT_EDITS / DEFAULT 模式默认之前短路（首匹配优先）。
     */
    fun decide(
        mode: PermissionMode,
        rules: List<PermissionRule>,
        ctx: PermissionContext
    ): PermissionDecision = when (mode) {
        // 全放行：模式的显式承诺，规则不再参与
        PermissionMode.BYPASS -> PermissionDecision.AllowExplicit

        // 只读规划：非只读直接拒绝，规则不可越级
        PermissionMode.PLAN -> if (ctx.readOnlyHint) {
            PermissionDecision.AllowDefault
        } else {
            PermissionDecision.Deny(planDenyReason(ctx.toolId))
        }

        // 其余模式：先看规则（首匹配优先，可越级），再看模式默认
        else -> decideWithRules(mode, rules, ctx)
    }

    /** 规则优先分支（BYPASS / PLAN 已在外层短路，不会进入本函数）。 */
    private fun decideWithRules(
        mode: PermissionMode,
        rules: List<PermissionRule>,
        ctx: PermissionContext
    ): PermissionDecision {
        val matched = rules.firstOrNull { PermissionRuleMatcher.matches(it.pattern, ctx.toolId) }
        if (matched != null) {
            return when (matched.effect) {
                PermissionEffect.ALLOW -> PermissionDecision.AllowExplicit
                PermissionEffect.ASK -> PermissionDecision.Ask
                PermissionEffect.DENY -> PermissionDecision.Deny(ruleDenyReason(matched, ctx.toolId))
            }
        }
        return when (mode) {
            PermissionMode.ACCEPT_EDITS -> when {
                // 只读工具：模式显式承诺放行
                ctx.readOnlyHint -> PermissionDecision.AllowExplicit
                // 编辑写类（code_edit / write_file / git 提交…）：自动放行
                isEditLike(ctx.toolId) -> PermissionDecision.AllowExplicit
                // 其余写操作（shell、网络写…）：仍要问
                else -> PermissionDecision.Ask
            }

            // DEFAULT：非只读一律 Ask（destructive / sensitive 只是同一决策的
            // 两个注解视角，决策表仍显式列出以便与规格逐行对照）
            PermissionMode.DEFAULT -> when {
                ctx.readOnlyHint -> PermissionDecision.AllowDefault
                ctx.destructiveHint || ctx.sensitiveAction -> PermissionDecision.Ask
                else -> PermissionDecision.Ask
            }

            // 理论不可达：BYPASS / PLAN 已在 decide 外层处理
            else -> PermissionDecision.Ask
        }
    }

    /** PLAN 模式拒绝非只读工具的面向模型文案。 */
    private fun planDenyReason(toolId: String): String =
        "PLAN 模式为只读规划模式，禁止执行会修改环境的工具（$toolId）。" +
            "请改用只读工具完成调研，或请用户切换权限模式（如 ACCEPT_EDITS）后再执行写操作"

    /** DENY 规则命中时的面向模型文案（指明是哪条规则，便于用户排查）。 */
    private fun ruleDenyReason(rule: PermissionRule, toolId: String): String =
        "工具 $toolId 命中了拒绝规则（模式：${rule.pattern}）。" +
            "请勿重试同一工具；如确需执行，请在「设置 → 工具权限」中调整该条规则"
}
