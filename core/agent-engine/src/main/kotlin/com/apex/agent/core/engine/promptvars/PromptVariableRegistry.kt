package com.apex.agent.core.engine.promptvars

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 内置变量条目：元数据 + 求值器（对齐 RikkaHub 的 PlaceholderInfo 结构，剥离 @Composable）。 */
internal data class BuiltInVariableEntry(
    val def: PromptVariableDef,
    val resolve: (PromptVariableContext) -> String
)

/**
 * ═══ 提示词变量注册表（4-b）═══
 *
 * 内置 19 个变量（覆盖 RikkaHub PlaceholderTransformer 的 12 个内置
 * ——model_id / model_name / locale / timezone / 设备 / 电量 / 用户与
 * 助手称呼 / 日期——并追加 agent 特有的 mode / thinking_level /
 * tool_count / session_id / network / os 等），外加会话级自定义变量。
 *
 * **解析优先级**（高 → 低）：
 *
 * 1. [PromptVariableContext.custom]——本次求值的临时覆盖（模板引擎
 *    注入 overrides 与模板默认值走这一层）；
 * 2. [registerCustom] 注册的自定义变量（可覆盖同名内置变量，如把
 *    {{time}} 固定为演示值）；
 * 3. 内置变量。
 *
 * **命名规范**：规范名小写 `[a-z][a-z0-9_]*`；查找大小写不敏感
 * （{{MODEL_ID}} 与 {{model_id}} 等价，对齐 RikkaHub 的 ignoreCase）。
 *
 * **时间确定性**：时间类变量（time / date / datetime / weekday）用
 * [java.text.SimpleDateFormat] + 构造注入的 [locale] + 上下文的
 * [PromptVariableContext.timeZoneId] 格式化 [PromptVariableContext.nowMs]。
 * 默认 locale 跟随系统；测试注入 Locale.US + 假钟 + UTC 时区即可断言
 * 精确字符串。SimpleDateFormat 非线程安全，每次求值新建实例。
 */
class PromptVariableRegistry(
    private val locale: Locale = Locale.getDefault()
) {

    private val lock = Any()

    /** 用户自定义变量（规范名 → 值）。LinkedHashMap 保序（definitions 展示稳定）。 */
    private val customVars = LinkedHashMap<String, String>()

    /** 内置变量表（LinkedHashMap：definitions 按声明顺序输出）。 */
    private val builtIns: LinkedHashMap<String, BuiltInVariableEntry> = buildBuiltIns()

    // ═══ 注册 / 注销 ═══

    /**
     * 注册会话级自定义变量。名字先规范化（trim + 小写）再校验
     * `[a-z][a-z0-9_]*`，非法名抛 [IllegalArgumentException]（调用方
     * bug，fail-fast）。同名重复注册 = 覆盖（后写胜出）。
     */
    fun registerCustom(name: String, value: String) {
        val canonical = canonicalizeOrThrow(name, "registerCustom")
        synchronized(lock) { customVars[canonical] = value }
    }

    /**
     * 注销自定义变量：存在且已移除返回 true；不存在返回 false。
     * 内置变量不可注销——若自定义层覆盖过同名内置变量，注销后内置
     * 解析自然恢复。
     */
    fun unregisterCustom(name: String): Boolean {
        val canonical = canonicalizeOrNull(name) ?: return false
        return synchronized(lock) { customVars.remove(canonical) != null }
    }

    /** 当前自定义变量快照（规范名 → 值；防御性拷贝）。 */
    fun customVariables(): Map<String, String> = synchronized(lock) { customVars.toMap() }

    // ═══ 查询 ═══

    /** 全部变量元数据（内置在前按声明序 + 自定义在后）。 */
    fun definitions(): List<PromptVariableDef> {
        val customDefs = synchronized(lock) {
            customVars.keys.map { name ->
                PromptVariableDef(
                    name = name,
                    description = "用户自定义变量",
                    scope = VariableScope.USER,
                    isBuiltIn = false,
                    example = ""
                )
            }
        }
        return builtIns.values.map { it.def } + customDefs
    }

    /** 是否为内置变量（大小写不敏感）。 */
    fun isBuiltIn(name: String): Boolean {
        val canonical = canonicalizeOrNull(name) ?: return false
        return canonical in builtIns
    }

    // ═══ 求值 ═══

    /**
     * 解析变量（大小写不敏感）。优先级：context.custom > 注册的自定义 > 内置。
     * 未知变量或非法名返回 null——不抛（展开器决定 KEEP/EMPTY/THROW 策略）。
     */
    fun resolveOrNull(name: String, context: PromptVariableContext): String? {
        val canonical = canonicalizeOrNull(name) ?: return null
        context.custom[canonical]?.let { return it }
        synchronized(lock) { customVars[canonical]?.let { return it } }
        return builtIns[canonical]?.resolve?.invoke(context)
    }

    /** 解析变量，未知时回退空串（便捷入口）。 */
    fun resolve(name: String, context: PromptVariableContext): String =
        resolveOrNull(name, context) ?: ""

    // ═══ 内部 ═══

    /** 规范化：trim + 小写；再校验命名规范，非法返回 null。 */
    private fun canonicalizeOrNull(name: String): String? {
        val canonical = name.trim().lowercase()
        if (!NAME_PATTERN.matches(canonical)) return null
        return canonical
    }

    private fun canonicalizeOrThrow(name: String, op: String): String {
        val canonical = canonicalizeOrNull(name)
        require(canonical != null) { "$op: illegal variable name '$name' (expected [a-z][a-z0-9_]*)" }
        return canonical
    }

    /** 时间格式化：上下文时区 + 注入 locale + 上下文时钟。 */
    private fun formatTime(pattern: String, context: PromptVariableContext): String {
        val fmt = SimpleDateFormat(pattern, locale)
        val zoneId = context.timeZoneId.trim().ifBlank { TimeZone.getDefault().id }
        fmt.timeZone = TimeZone.getTimeZone(zoneId)
        return fmt.format(Date(context.nowMs()))
    }

    /** 组合「平台 + 设备摘要」为 os 变量（任一为空取另一，全空 unknown）。 */
    private fun composeOs(context: PromptVariableContext): String {
        val parts = listOf(context.platform.trim(), context.deviceSummary.trim())
            .filter { it.isNotBlank() }
        return if (parts.isEmpty()) "unknown" else parts.joinToString(" ")
    }

    private fun buildBuiltIns(): LinkedHashMap<String, BuiltInVariableEntry> {
        val entries = listOf(
            // ── 会话级：模型与运行形态 ──
            entry(
                "model_id", "当前模型 ID", VariableScope.SESSION, "gemini-2.0-flash"
            ) { it.modelId.trim().ifBlank { "unknown" } },
            entry(
                "model_name", "当前模型展示名", VariableScope.SESSION, "Gemini 2.0 Flash"
            ) { it.modelName.trim().ifBlank { "unknown" } },
            entry(
                "mode", "当前执行模式（BUILD/PLAN/SPEC/...）", VariableScope.SESSION, "BUILD"
            ) { it.modeName.trim().ifBlank { "BUILD" } },
            entry(
                "thinking_level", "当前思考档位（NONE~MAXIMUM/AUTO）", VariableScope.SESSION, "DEEP"
            ) { it.thinkingLevelName.trim().ifBlank { "STANDARD" } },
            entry(
                "tool_count", "本轮暴露的工具数量", VariableScope.SESSION, "42"
            ) { it.toolCount.toString() },
            entry(
                "session_id", "当前会话 ID", VariableScope.SESSION, "session-8f3k"
            ) { it.sessionId.trim().ifBlank { "none" } },
            // ── 用户级：人设称呼 ──
            entry(
                "agent_name", "Agent 自称（空回退 Apex Agent）", VariableScope.USER, "Apex Agent"
            ) { it.agentName.trim().ifBlank { DEFAULT_AGENT_NAME } },
            entry(
                "user_name", "Agent 对用户的称呼（空回退 user）", VariableScope.USER, "Boss"
            ) { it.userTitle.trim().ifBlank { "user" } },
            // ── 系统级：时间（按上下文时区与假钟确定性求值）──
            entry("time", "当前时间（HH:mm）", VariableScope.SYSTEM, "14:30") {
                formatTime("HH:mm", it)
            },
            entry("date", "当前日期（yyyy-MM-dd）", VariableScope.SYSTEM, "2025-01-01") {
                formatTime("yyyy-MM-dd", it)
            },
            entry("datetime", "当前日期时间（yyyy-MM-dd HH:mm）", VariableScope.SYSTEM, "2025-01-01 14:30") {
                formatTime("yyyy-MM-dd HH:mm", it)
            },
            entry("weekday", "星期几（按注册表 locale 本地化缩写）", VariableScope.SYSTEM, "Wed") {
                formatTime("EEE", it)
            },
            // ── 系统级：环境快照（app 层注入的字符串）──
            entry("locale", "语言区域标签", VariableScope.SYSTEM, "zh-CN") {
                it.locale.trim().ifBlank { locale.toLanguageTag() }
            },
            entry("timezone", "时区 ID", VariableScope.SYSTEM, "Asia/Shanghai") {
                it.timeZoneId.trim().ifBlank { TimeZone.getDefault().id }
            },
            entry("platform", "运行平台", VariableScope.SYSTEM, "Android") {
                it.platform.trim().ifBlank { "unknown" }
            },
            entry("device", "设备摘要（app 层注入）", VariableScope.SYSTEM, "Pixel 9 Pro") {
                it.deviceSummary.trim().ifBlank { "unknown" }
            },
            entry("network", "网络状态摘要", VariableScope.SYSTEM, "Wi-Fi") {
                it.networkSummary.trim().ifBlank { "unknown" }
            },
            entry("battery", "电量状态摘要", VariableScope.SYSTEM, "85%") {
                it.batterySummary.trim().ifBlank { "unknown" }
            },
            entry("os", "操作系统摘要（平台 + 设备组合）", VariableScope.SYSTEM, "Android Pixel 9 Pro") {
                composeOs(it)
            }
        )
        val map = LinkedHashMap<String, BuiltInVariableEntry>()
        for (e in entries) {
            map[e.def.name] = e
        }
        return map
    }

    private fun entry(
        name: String,
        description: String,
        scope: VariableScope,
        example: String,
        resolve: (PromptVariableContext) -> String
    ): BuiltInVariableEntry = BuiltInVariableEntry(
        def = PromptVariableDef(
            name = name,
            description = description,
            scope = scope,
            isBuiltIn = true,
            example = example
        ),
        resolve = resolve
    )

    private companion object {
        /** 规范变量名：小写字母开头，后接小写字母/数字/下划线。 */
        val NAME_PATTERN = Regex("[a-z][a-z0-9_]*")

        /** agent_name 空串回退值（对齐 AgentConfig 注释的默认身份行）。 */
        const val DEFAULT_AGENT_NAME = "Apex Agent"
    }
}
