package com.apex.agent.core.engine.modes

import kotlinx.serialization.Serializable

/**
 * ═══ 自定义模式预设（#168）═══
 *
 * [com.apex.agent.core.engine.AgentMode.CUSTOM] 此前只有一条单字符串指令
 * （`custom_mode_instruction`，SharedPreferences 平键），用户只能维护
 * 「一份当前指令」——想在「翻译官」「代码评审」「头脑风暴」几个人格间
 * 切换就得反复整段改写。
 *
 * 预设系统 = **多套命名指令**：
 *
 * ```
 * AgentSettings.customModePresets: List<ModePreset>   ← 用户预设（持久化 JSON）
 * AgentSettings.selectedModePresetId: String          ← 当前选中（"" = 未选）
 * BuiltinModePresets.ALL                             ← 4 套内置（不可删，可复制）
 * ```
 *
 * 生效链路（app 层组装，引擎零新概念）：
 * - 选中预设的 [instruction] 拍平进 [com.apex.agent.core.engine.AgentConfig]
 *   的 `customInstruction` 字段（既有 "## Custom Instructions" 注入点）；
 * - 未选预设时回退旧单串指令（`custom_mode_instruction` 迁移兼容）。
 *
 * 本类型位于 core/agent-engine（app 可依赖 core；core 不反向依赖 app），
 * `@Serializable` 支持随 AgentSettings 整体 JSON 持久化。
 *
 * @param id 稳定标识（内置以 `builtin_` 前缀；用户预设 `preset_<ts>_<rand>`）
 * @param name 展示名（UI 列表/chip）
 * @param instruction 注入 system prompt 的指令正文（原样拼入
 *   "## Custom Instructions" 段）
 * @param builtin true = 内置预设（不可删除/编辑，可复制为自定义副本）
 * @param createdAt 创建时间戳（列表排序用，0 = 未记录）
 */
@Serializable
data class ModePreset(
    val id: String,
    val name: String,
    val instruction: String = "",
    val builtin: Boolean = false,
    val createdAt: Long = 0
) {

    /** 指令首行摘要（UI 列表卡展示；空指令返回占位符）。 */
    fun summary(maxChars: Int = 48): String {
        val firstLine = instruction.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return ""
        return if (firstLine.length <= maxChars) firstLine else firstLine.take(maxChars) + "…"
    }

    companion object {
        /** 内置预设 id 前缀（删除保护 + UI 锁标判定）。 */
        const val BUILTIN_ID_PREFIX = "builtin_"

        /** 旧单串指令迁移预设的固定 id（幂等：重复迁移不会叠加）。 */
        const val MIGRATED_PRESET_ID = "preset_migrated_legacy"

        /** 迁移预设的展示名（i18n 由 UI 层处理；core 侧保留中性文案）。 */
        const val MIGRATED_PRESET_NAME = "迁移的自定义指令 / Migrated instruction"

        /** 生成新预设 id（时间戳 + 随机后缀，跨进程基本不碰撞）。 */
        fun newId(): String =
            "preset_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/**
 * 内置预设（4 套）—— 不可删、不可改、可复制为自定义副本。
 *
 * 指令正文遵循「角色 + 硬性行为约束 + 输出格式」三段式：第一句定身份，
 * 中段 MUST/NEVER 级别约束（提示词工程上比建议式措辞更可执行），
 * 尾部给输出形态约定。指令为英文书写（system prompt 其余段落同为英文，
 * 保持语言一致性；输出语言由角色字段 roleLanguage / 用户消息语言驱动）。
 */
object BuiltinModePresets {

    /** 翻译官：双语文本互译，保留格式与术语表。 */
    val TRANSLATOR = ModePreset(
        id = "builtin_translator",
        name = "翻译官 / Translator",
        builtin = true,
        createdAt = 0L,
        instruction = """
            You are a professional translator. Translate the user's text faithfully between Chinese and English.
            - Detect the source language automatically and translate into the OTHER language (unless the user names a target language).
            - Preserve tone, register, and formatting: headings, lists, tables, code identifiers and markdown must survive translation untouched.
            - NEVER add explanations, summaries, or your own opinions. Output ONLY the translation.
            - For ambiguous terms, keep the original in parentheses on first occurrence, e.g. 端点（endpoint）.
            - Technical jargon follows mainstream community conventions (e.g. "dependency injection" → 依赖注入, NOT word-by-word).
            - If the source text is mixed-language, translate every segment so the whole output is in the single target language.
        """.trimIndent()
    )

    /** 代码评审：结构化 review，按严重度分级，给可执行修复。 */
    val CODE_REVIEWER = ModePreset(
        id = "builtin_code_reviewer",
        name = "代码评审 / Code Reviewer",
        builtin = true,
        createdAt = 0L,
        instruction = """
            You are a rigorous senior code reviewer. Review the provided code like a merge-request gatekeeper.
            - Structure EVERY review as: Verdict (approve / approve-with-comments / request-changes) → Findings → Suggested patch.
            - Classify each finding by severity: 🔴 blocking (bugs, security, data loss), 🟡 major (design, performance, maintainability), 🟢 minor (style, naming, docs).
            - For every finding cite the exact file/function/line snippet, explain WHY it matters, and show a concrete fix — no vague advice like "consider refactoring".
            - Check for: correctness & edge cases, error handling, resource leaks, concurrency hazards, security (injection, path traversal, secrets in code), test coverage gaps.
            - Praise genuinely good code in one short line at the end; do not invent issues to seem thorough.
            - If the code is too small to review meaningfully, say so and ask for the full context instead of guessing.
        """.trimIndent()
    )

    /** 头脑风暴：发散数量优先，禁止过早收敛，允许押注疯狂想法。 */
    val BRAINSTORMER = ModePreset(
        id = "builtin_brainstorm",
        name = "头脑风暴 / Brainstormer",
        builtin = true,
        createdAt = 0L,
        instruction = """
            You are a lateral-thinking brainstorming partner. Quantity and variety beat polish in this mode.
            - Always offer at least 5 distinct ideas, numbered, spanning different categories: the obvious safe pick, the cheap quick win, the ambitious moonshot, the contrarian inversion, the borrow-from-another-domain analogy.
            - For each idea give ONE line on the core mechanism and ONE line on the main risk — no essays.
            - NEVER merge everything into a single "best" recommendation in the first pass; evaluation happens only after the user shortlists.
            - Build on the user's stated constraints but challenge at least one assumption explicitly ("what if X weren't true?").
            - Wild ideas are welcome; label them 🌙 moonshot so the user can filter.
            - End by asking which 1-2 ideas to deepen, NOT by deciding for the user.
        """.trimIndent()
    )

    /** 严谨科学家：假设-证据-结论链，区分事实与推断，量化不确定性。 */
    val RIGOROUS_SCIENTIST = ModePreset(
        id = "builtin_scientist",
        name = "严谨科学家 / Rigorous Scientist",
        builtin = true,
        createdAt = 0L,
        instruction = """
            You are a rigorous, evidence-first analyst. Treat every claim as a hypothesis until supported.
            - Structure answers as: Claim → Evidence (with source/derivation) → Confidence (high/medium/low + why) → Caveats.
            - Clearly separate: established facts, well-supported inferences, and speculation. Label each explicitly; never let speculation masquerade as fact.
            - Quantify uncertainty whenever possible (ranges, order-of-magnitude estimates) and state the assumptions behind every number.
            - Actively seek disconfirming evidence: for every conclusion, note the observation that would falsify it.
            - Prefer "I don't know, and here is how we could find out" over a confident guess.
            - When tools can verify (search, computation, file reads), verify BEFORE asserting.
        """.trimIndent()
    )

    /** 全部内置预设（声明序 = UI 展示序）。 */
    val ALL: List<ModePreset> = listOf(TRANSLATOR, CODE_REVIEWER, BRAINSTORMER, RIGOROUS_SCIENTIST)

    /** 按 id 查找内置预设。 */
    fun byId(id: String): ModePreset? = ALL.firstOrNull { it.id == id }
}

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

// ═══════════════════════════════════════════════════════
// 预设列表纯函数操作（app 层 AgentSettings copy() 的组合原语）
// ═══════════════════════════════════════════════════════

/**
 * 生效预设全集 = 内置在前（声明序）+ 用户预设在后（创建时间序）。
 * 与 UI 列表展示序一致，选中 id 悬空时 [selectedFrom] 诚实回落 null。
 */
fun effectiveModePresets(userPresets: List<ModePreset>): List<ModePreset> =
    BuiltinModePresets.ALL + userPresets.sortedBy { it.createdAt }

/** 在生效全集里按 id 解析选中预设；未选 / id 悬空 / 被删 → null。 */
fun selectedModePreset(userPresets: List<ModePreset>, selectedId: String): ModePreset? {
    if (selectedId.isBlank()) return null
    return effectiveModePresets(userPresets).firstOrNull { it.id == selectedId }
}

/** upsert：同 id 覆盖（编辑），否则追加（新建）。返回新列表（不修改入参）。 */
fun upsertModePreset(userPresets: List<ModePreset>, preset: ModePreset): List<ModePreset> {
    val idx = userPresets.indexOfFirst { it.id == preset.id }
    return if (idx >= 0) {
        userPresets.toMutableList().apply { set(idx, preset) }
    } else {
        userPresets + preset
    }
}

/** 删除用户预设（内置 id 不在用户列表中，天然删不掉）；被删除项恰为选中项时由调用方清空 selectedModePresetId。 */
fun removeModePreset(userPresets: List<ModePreset>, presetId: String): List<ModePreset> =
    userPresets.filterNot { it.id == presetId }

/** 内置预设复制为可编辑副本（新 id、去 builtin 标记、名字追加序号）。 */
fun ModePreset.duplicateAsCustom(): ModePreset = copy(
    id = ModePreset.newId(),
    name = "$name ②",
    builtin = false,
    createdAt = System.currentTimeMillis()
)

/**
 * 旧单串指令 → 迁移预设（一次性）。
 *
 * 迁移条件（三者同时满足，保证幂等且不覆盖新数据）：
 * 1. 旧指令非空；
 * 2. 用户预设列表为空（尚无任何自定义预设）；
 * 3. 迁移预设 id（[ModePreset.MIGRATED_PRESET_ID]）不存在（未迁移过）。
 *
 * 返回 null = 无需迁移。迁移后调用方应把 selectedModePresetId 指向
 * 迁移预设，保持「升级前后 CUSTOM 模式行为一致」。
 */
fun migrateLegacyCustomInstruction(
    userPresets: List<ModePreset>,
    legacyInstruction: String?
): ModePreset? {
    if (legacyInstruction.isNullOrBlank()) return null
    if (userPresets.isNotEmpty()) return null
    if (userPresets.any { it.id == ModePreset.MIGRATED_PRESET_ID }) return null
    return ModePreset(
        id = ModePreset.MIGRATED_PRESET_ID,
        name = ModePreset.MIGRATED_PRESET_NAME,
        instruction = legacyInstruction.trim(),
        builtin = false,
        createdAt = System.currentTimeMillis()
    )
}
