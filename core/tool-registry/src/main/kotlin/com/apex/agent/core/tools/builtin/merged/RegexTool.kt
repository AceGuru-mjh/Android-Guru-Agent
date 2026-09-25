package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.RegexGroupScanner
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `regex` — 正则五合一工具（#171 四族合并）。
 *
 * 合并自 `regex_extract` 与 `regex_replace`（#171），并补齐模型高频要的
 * test / match_all / split。旧工具保留注册；旧 id 进 LEGACY_ALIAS_IDS。
 *
 * Why: 正则抽取/替换此前是两个独立工具 + shell `grep` 一条旁路（命令门
 * 往返）。合并后一个 schema 覆盖"测试/抽取/替换/全量匹配/切分"，且各操作
 * 共享同一套错误语义（坏 pattern 点名字段 + 修复建议）。
 *
 * Operations（`op` 参数）：
 * - `test`      — 是否匹配（true/false + 命中数）；
 * - `extract`   — 第一个匹配（含捕获组，命名组按名输出）；
 * - `replace`   — 替换（`$1`/`$name` 组引用，limit 限次，ignore_case）；
 * - `match_all` — 全部匹配 → JSON 数组（`{"match":..., "groups":{...}}`）；
 * - `split`     — 按正则切分（limit 限段数）。
 *
 * 无效正则返回 INVALID_ARGUMENT（字段 pattern + 截断的引擎错误 + 修复
 * 建议），模型可自修。零匹配：extract/match_all 返回 NOT_FOUND 回显
 * pattern（区分"没命中"与"坏 pattern"）；replace 原文返回 + 尾注。
 */
class RegexTool : BaseTool(
    id = "regex",
    name = "Regex Toolkit",
    description = """
        Regex operations in one tool: test, extract (capture groups), replace
        (group references), match_all (JSON array), split.
        Input: {"op":"test","text":"...","pattern":"\\d+"}
        | {"op":"extract","text":"...","pattern":"(?<year>\\d{4})-(?<month>\\d{2})"}
        | {"op":"replace","text":"...","pattern":"\\bcat\\b","replacement":"dog","limit":5}
        | {"op":"match_all","text":"...","pattern":"(\\w+)@(\\w+)","limit":100}
        | {"op":"split","text":"a,b,c","pattern":",\s*"}
        op: test | extract | replace | match_all | split.
        Kotlin/Java regex syntax; named groups (?<name>...) supported;
        replacement supports ${'$'}1 and ${'$'}{name}; ignoreCase optional.
        extract/match_all with no match → not_found echoing the pattern.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("op", required = true, description = "test | extract | replace | match_all | split", enumValues = listOf("test", "extract", "replace", "match_all", "split"))
        string("text", required = true, description = "Input text")
        string("pattern", required = true, description = "Regular expression (Kotlin/Java syntax)")
        string("replacement", description = "Replacement text for op=replace; ${'$'}1 and ${'$'}{name} reference capture groups")
        integer("limit", description = "Max matches (match_all, default 100, max 1000) / replacements (replace) / segments (split, default 100)", minimum = 1.0, maximum = 1000.0)
        boolean("ignoreCase", description = "Case-insensitive matching (default false)", defaultValue = false)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("regex", "extract", "replace", "match", "split", "text", "pattern")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val op = args.requireString("op")
        if (op !in OPERATIONS) {
            return ToolResult.invalid("op", "unknown op '$op'", "use ${OPERATIONS.joinToString(" | ")}")
        }
        val text = args.requireString("text")
        val patternText = args.requireString("pattern")
        val ignoreCase = args.booleanWithDefault("ignoreCase", false)

        val regex = try {
            if (ignoreCase) Regex(patternText, RegexOption.IGNORE_CASE) else Regex(patternText)
        } catch (e: Exception) {
            return ToolResult.invalid(
                field = "pattern",
                message = "invalid regular expression: ${e.message?.take(160)}",
                suggestion = "check for unbalanced parentheses/brackets or unsupported escapes"
            )
        }
        // groupNames[i] = 捕获组 i+1 的名字（未命名为 null），与 groups 对齐。
        val groupNames = RegexGroupScanner.scan(patternText)

        return when (op) {
            "test" -> opTest(text, patternText, regex)
            "extract" -> opExtract(text, patternText, regex, groupNames)
            "replace" -> opReplace(args, text, patternText, regex, groupNames)
            "match_all" -> opMatchAll(args, text, patternText, regex, groupNames)
            "split" -> opSplit(args, text, regex)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable op $op")
        }
    }

    // ── test ───────────────────────────────────────────────────────────────

    private fun opTest(text: String, patternText: String, regex: Regex): ToolResult {
        val matches = regex.findAll(text).toList()
        return ToolResult.ok(
            if (matches.isEmpty()) {
                "false (no match for /${patternText.take(80)}/)"
            } else {
                "true (${matches.size} match(es) for /${patternText.take(80)}/, first: \"${matches[0].value.take(80)}\")"
            }
        )
    }

    // ── extract（移植自 regex_extract 默认模式）────────────────────────────

    private fun opExtract(text: String, patternText: String, regex: Regex, groupNames: List<String?>): ToolResult {
        val match = regex.find(text)
            ?: return noMatch(patternText)
        return ToolResult.ok(renderSingleMatch(match, groupNames))
    }

    /** 首个匹配渲染：无捕获组 → 裸匹配串；有 → match + 各组行。 */
    private fun renderSingleMatch(match: MatchResult, groupNames: List<String?>): String {
        val captureCount = match.groups.count { it != null } - 1
        if (captureCount <= 0) return match.value
        return buildString {
            appendLine("match: ${match.value}")
            match.groups.forEachIndexed { index, group ->
                if (index == 0 || group == null) return@forEachIndexed
                val name = groupNames.getOrNull(index - 1) ?: "group_$index"
                appendLine("$name: ${group.value}")
            }
        }.trimEnd()
    }

    // ── replace（移植自 regex_replace）─────────────────────────────────────

    private fun opReplace(
        args: ToolArguments,
        text: String,
        patternText: String,
        regex: Regex,
        groupNames: List<String?>
    ): ToolResult {
        val replacement = args.requireString("replacement")
        val limit = args.optionalInt("limit")

        val allMatches = regex.findAll(text).toList()
        if (allMatches.isEmpty()) {
            return ToolResult.ok(text + "\n(no matches for pattern — text unchanged)")
        }

        val effectiveLimit = limit?.coerceAtLeast(1) ?: Int.MAX_VALUE
        var count = 0
        val out = buildString {
            var lastIndex = 0
            for (match in allMatches) {
                if (count >= effectiveLimit) break
                append(text, lastIndex, match.range.first)
                append(expandGroups(match, replacement, groupNames))
                lastIndex = match.range.last + 1
                count++
            }
            append(text, lastIndex, text.length)
        }
        return ToolResult.ok("$out\n(replaced $count of ${allMatches.size} match(es))")
    }

    /** 安全索引访问（MatchGroupCollection 无 getOrNull）。 */
    private fun MatchResult.groupAt(index: Int): kotlin.text.MatchGroup? =
        if (index >= 0 && index < groups.size) groups[index] else null

    /**
     * 展开 `${'$'}1` / `${'$'}{name}`。因需手动限次替换，故自行展开。
     * `$` 后非数字/花括号保持字面（Java 语义）；不可解析的组静默丢弃。
     */
    private fun expandGroups(match: MatchResult, replacement: String, groupNames: List<String?>): String {
        val sb = StringBuilder(replacement.length)
        var i = 0
        val n = replacement.length
        while (i < n) {
            val c = replacement[i]
            if (c == '$' && i + 1 < n) {
                val next = replacement[i + 1]
                when {
                    next == '$' -> { sb.append('$'); i += 2 }
                    next.isDigit() -> {
                        var j = i + 1
                        while (j < n && replacement[j].isDigit()) j++
                        val groupIndex = replacement.substring(i + 1, j).toIntOrNull() ?: 0
                        val group = match.groupAt(groupIndex)
                        if (group != null) sb.append(group.value)
                        i = j
                    }
                    next == '{' -> {
                        val close = replacement.indexOf('}', i + 2)
                        if (close < 0) { sb.append(c); i++ }
                        else {
                            val token = replacement.substring(i + 2, close)
                            val group = when {
                                token.toIntOrNull() != null -> match.groupAt(token.toInt())
                                else -> {
                                    val idx = groupNames.indexOfFirst { it == token }
                                    if (idx >= 0) match.groupAt(idx + 1) else null
                                }
                            }
                            if (group != null) sb.append(group.value)
                            i = close + 1
                        }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ── match_all（移植自 regex_extract all 模式）─────────────────────────

    private fun opMatchAll(
        args: ToolArguments,
        text: String,
        patternText: String,
        regex: Regex,
        groupNames: List<String?>
    ): ToolResult {
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val matches = regex.findAll(text).take(limit).toList()
        if (matches.isEmpty()) {
            return noMatch(patternText)
        }
        val jsonLines = matches.joinToString(",\n  ", prefix = "[\n  ", postfix = "\n]") { match ->
            val obj = buildJsonObject {
                put("match", match.value)
                put("groups", buildJsonObject {
                    match.groups.forEachIndexed { index, group ->
                        if (index == 0 || group == null) return@forEachIndexed
                        val gname = groupNames.getOrNull(index - 1)
                        put(gname ?: "group_$index", group.value)
                    }
                })
            }
            obj.toString()
        }
        return ToolResult.ok("${matches.size} match(es):\n$jsonLines")
    }

    // ── split（新增：补齐正则族最后一块）──────────────────────────────────

    private fun opSplit(args: ToolArguments, text: String, regex: Regex): ToolResult {
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val parts = if (text.isEmpty()) listOf("") else regex.split(text, limit)
        return ToolResult.ok(
            buildString {
                appendLine("parts: ${parts.size}")
                parts.forEachIndexed { i, part ->
                    appendLine("[$i] $part")
                }
            }.trimEnd()
        )
    }

    private fun noMatch(patternText: String): ToolResult = ToolResult.fail(
        ToolErrorCode.NOT_FOUND,
        "no match for pattern /${patternText.take(80)}/"
    )

    private companion object {
        val OPERATIONS = setOf("test", "extract", "replace", "match_all", "split")
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
    }
}
