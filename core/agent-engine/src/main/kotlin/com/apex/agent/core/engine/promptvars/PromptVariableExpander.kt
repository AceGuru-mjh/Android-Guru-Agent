package com.apex.agent.core.engine.promptvars

/**
 * 未知变量处置策略（无显式默认值时生效；显式 `{{name|default}}` 的
 * 默认值永远优先于本策略）。
 */
enum class UnknownPolicy {
    /** 原样保留占位符（默认）：用户能在输出里看到未填的坑。 */
    KEEP,

    /** 替换为空串。 */
    EMPTY,

    /** 抛 [UnknownPromptVariableException]（调用方 fail-fast）。 */
    THROW
}

/** 展开遇到未知变量且策略为 [UnknownPolicy.THROW] 时抛出。 */
class UnknownPromptVariableException(
    val variableName: String,
    val token: String
) : RuntimeException("unknown prompt variable '$variableName' (token: $token)")

/**
 * 占位符词法（预编译，全实例共享——对齐 WebTools.kt v2 的顶层 Regex 先例）：
 *
 * 分支 1 `\\[{]{2}`：转义序列——反斜杠 + 双花括号，展开为字面 `{{`；
 * 分支 2：`{{ 名称 }}`，名称大小写不敏感，允许前后空白；可选
 * `|默认值`（默认值内不允许花括号，避免与闭合定界符歧义）。
 *
 * 非法形态（空名、数字开头、未闭合、默认值含花括号）整体不匹配，
 * 原样保留——「畸形花括号不吞文本」。
 */
private val TOKEN_REGEX: Regex = Regex(
    """\\[{]{2}|[{]{2}\s*([a-zA-Z][a-zA-Z0-9_]*)\s*(?:\|([^{}]*))?[}]{2}"""
)

/**
 * ═══ 提示词变量展开器（4-b）═══
 *
 * 语法（学习 RikkaHub 的 {{key}} 双花括号 + ignoreCase，扩展了空白
 * 容忍、默认值与转义）：
 *
 * ```
 * {{model_id}}         基本展开（大小写不敏感）
 * {{ model_id }}       前后空白容忍
 * {{focus|全面审查}}    变量缺失时使用默认值（显式默认值优先于策略）
 * \{{model_id}}        转义：输出字面 {{model_id}}
 * ```
 *
 * **递归**：解析值本身可含占位符（如自定义变量 greeting = "你好
 * {{user_name}}"），逐层展开至 [maxDepth] 层，超层原样保留（防失控）。
 *
 * **环安全**：展开栈记录正在展开的变量链（A→B→C），回到链上变量时
 * 原样保留该占位符——A↔B 互指在有限步内终止。
 *
 * 非 suspend 纯函数：同文本同上下文结果确定，单测注入假钟即可精确断言。
 */
class PromptVariableExpander(
    private val registry: PromptVariableRegistry,
    private val maxDepth: Int = 3,
    private val onUnknown: UnknownPolicy = UnknownPolicy.KEEP
) {

    init {
        require(maxDepth >= 0) { "maxDepth must be >= 0 (got $maxDepth)" }
    }

    /**
     * 展开文本中的全部占位符。
     *
     * @throws UnknownPromptVariableException 当策略为 [UnknownPolicy.THROW]
     *   且遇到无默认值的未知变量
     */
    fun expand(text: String, context: PromptVariableContext): String =
        expandInternal(text, context, depth = 0, stack = emptySet())

    /** [expand] 的容错版：任何异常折叠为 null（防御式调用方使用）。 */
    fun expandOrNull(text: String, context: PromptVariableContext): String? =
        try {
            expand(text, context)
        } catch (e: Exception) {
            null
        }

    /**
     * 扫描文本引用的变量名（规范小写、按首次出现顺序去重；转义占位符
     * 不计入）。模板引擎用它提示缺失变量；输入框可用它做变量高亮。
     */
    fun findVariables(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in TOKEN_REGEX.findAll(text)) {
            if (m.value.startsWith("\\")) continue
            seen.add(m.groupValues[1].lowercase())
        }
        return seen.toList()
    }

    /**
     * 变量在给定上下文下是否可解析（模板引擎的缺失判定用；委托注册表，
     * 走完整的 context.custom > 自定义 > 内置 优先级链）。
     */
    fun isResolvable(name: String, context: PromptVariableContext): Boolean =
        registry.resolveOrNull(name, context) != null

    // ═══ 内部 ═══

    /**
     * 单层展开：一次词法扫描产出新串；解析值含 `{{` 时递归下一层。
     *
     * @param depth 已完成的展开层数（0 = 顶层原文）；达到 [maxDepth] 即
     *   停止展开、原样返回（「超层保留」）
     * @param stack 正在展开的变量链（环检测；分支独立，菱形引用不误伤）
     */
    private fun expandInternal(
        text: String,
        context: PromptVariableContext,
        depth: Int,
        stack: Set<String>
    ): String {
        if (depth >= maxDepth) return text
        val sb = StringBuilder(text.length)
        var last = 0
        for (m in TOKEN_REGEX.findAll(text)) {
            sb.append(text, last, m.range.first)
            val token = m.value
            if (token.startsWith("\\")) {
                // 转义分支：\{{ → 字面 {{
                sb.append(OPEN_BRACES)
            } else {
                appendResolved(sb, token, m.groupValues[1], m.groups[2]?.value, context, depth, stack)
            }
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    /** 单个占位符的解析与拼接（含策略与递归下降）。 */
    private fun appendResolved(
        sb: StringBuilder,
        token: String,
        rawName: String,
        rawFallback: String?,
        context: PromptVariableContext,
        depth: Int,
        stack: Set<String>
    ) {
        val name = rawName.lowercase()
        if (name in stack) {
            // 环：正在展开链上再次遇到同一变量 → 原样保留，终止
            sb.append(token)
            return
        }
        val resolved = registry.resolveOrNull(name, context)
        when {
            resolved != null -> {
                if (resolved.contains(OPEN_BRACES)) {
                    sb.append(expandInternal(resolved, context, depth + 1, stack + name))
                } else {
                    sb.append(resolved)
                }
            }
            rawFallback != null -> {
                // 显式默认值：变量缺失时启用（优先于未知策略），首尾空白剔除
                sb.append(rawFallback.trim())
            }
            else -> when (onUnknown) {
                UnknownPolicy.KEEP -> sb.append(token)
                UnknownPolicy.EMPTY -> Unit
                UnknownPolicy.THROW -> throw UnknownPromptVariableException(name, token)
            }
        }
    }

    private companion object {
        const val OPEN_BRACES = "{{"
    }
}
