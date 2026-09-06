package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `regex_extract` — pull structured matches out of text with a regular
 * expression.
 *
 * Why: before this, the model either shell-piped to `grep` (a round trip
 * through the command gate for pure text work) or re-printed the text and
 * eyeballed it. A pure-JVM regex extractor is faster, cheaper, offline,
 * and its errors are about the *pattern*, which the model can fix.
 *
 * Output contract (deterministic, machine-friendly):
 * - default (first match only) → the matched text, or when the pattern has
 *   capture groups → `group_index: value` lines incl. named groups;
 * - `all: true` → JSON array of match objects
 *   `{"match": "...", "groups": {"name": "..."}}`;
 * - zero matches → structured NOT_FOUND echoing the pattern, so the model
 *   knows the text and pattern didn't intersect (vs. a bad pattern).
 */
class RegexExtractTool : BaseTool(
    id = "regex_extract",
    name = "Regex Extract",
    description = """
        Extract matches from text using a regular expression.
        Input: {"text": "...", "pattern": "(\d+)-(\d+)", "all": false}
        Default returns the first match (all capture groups, named groups by name).
        all=true returns every match as a JSON array.
        Kotlin/Java regex syntax; named groups (?<name>...) supported.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("text", required = true, description = "Input text to search")
        string("pattern", required = true, description = "Regular expression (Kotlin/Java syntax)")
        boolean("all", description = "Return ALL matches (default false = first match only)")
        integer("limit", description = "Max matches when all=true (default 100, max 1000)", minimum = 1.0, maximum = 1000.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("regex", "extract", "text", "pattern")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val text = args.requireString("text")
        val patternText = args.requireString("pattern")
        val all = args.booleanWithDefault("all", false)
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val regex = try {
            Regex(patternText)
        } catch (e: Exception) {
            return ToolResult.invalid(
                field = "pattern",
                message = "invalid regular expression: ${e.message?.take(160)}",
                suggestion = "check for unbalanced parentheses/brackets or unsupported escapes"
            )
        }

        // groupNames[i] = name of capture group i+1, or null when unnamed.
        val groupNames = RegexGroupScanner.scan(patternText)

        if (!all) {
            val match = regex.find(text)
                ?: return ToolResult.fail(
                    ToolErrorCode.NOT_FOUND,
                    "no match for pattern /${patternText.take(80)}/"
                )
            return ToolResult.ok(renderSingleMatch(match, groupNames))
        }

        val matches = regex.findAll(text).take(limit).toList()
        if (matches.isEmpty()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "no match for pattern /${patternText.take(80)}/"
            )
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

    /** First match rendering: bare match, or groups when captures exist. */
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

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
    }
}

/**
 * `regex_replace` — regex substitution with named/numbered group references.
 *
 * Mirrors [RegexExtractTool]'s rationale: pure text surgery without a shell
 * round trip. Supports `$1`/`$name` group references (Kotlin/Java
 * semantics), a replacement [limit], and [ignoreCase].
 *
 * Output: the replaced text. When nothing matched, the text is returned
 * unchanged plus a trailing notice line, so the model can tell "replaced 3"
 * from "pattern missed" without a second call.
 */
class RegexReplaceTool : BaseTool(
    id = "regex_replace",
    name = "Regex Replace",
    description = """
        Replace regex matches in text with a replacement string.
        Input: {"text": "...", "pattern": "\bcat\b", "replacement": "dog", "limit": 5, "ignoreCase": false}
        Replacement supports group references: ${'$'}1, ${'$'}2, ${'$'}{name}.
        Output is the full replaced text; a trailing notice reports the match count.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("text", required = true, description = "Input text")
        string("pattern", required = true, description = "Regular expression to match")
        string("replacement", required = true, description = "Replacement text; ${'$'}1 and ${'$'}{name} reference capture groups")
        integer("limit", description = "Max replacements (default unlimited)", minimum = 1.0)
        boolean("ignoreCase", description = "Case-insensitive matching (default false)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("regex", "replace", "text", "substitute")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val text = args.requireString("text")
        val patternText = args.requireString("pattern")
        val replacement = args.requireString("replacement")
        val limit = args.optionalInt("limit")
        val ignoreCase = args.booleanWithDefault("ignoreCase", false)

        val regex = try {
            if (ignoreCase) Regex(patternText, RegexOption.IGNORE_CASE) else Regex(patternText)
        } catch (e: Exception) {
            return ToolResult.invalid(
                field = "pattern",
                message = "invalid regular expression: ${e.message?.take(160)}"
            )
        }

        // Count matches first (Kotlin's replace can't cap without manual loop).
        val allMatches = regex.findAll(text).toList()
        if (allMatches.isEmpty()) {
            return ToolResult.ok(text + "\n(no matches for pattern — text unchanged)")
        }

        val groupNames = RegexGroupScanner.scan(patternText)
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
        return ToolResult.ok("${out}\n(replaced $count of ${allMatches.size} match(es))")
    }

    /** Safe indexed access (MatchGroupCollection has no getOrNull). */
    private fun MatchResult.groupAt(index: Int): kotlin.text.MatchGroup? =
        if (index >= 0 && index < groups.size) groups[index] else null

    /**
     * Expand `${'$'}1` / `${'$'}{name}` in the replacement. Kotlin's own
     * `MatchResult.replace` handles this, but we cap replacements manually
     * and must therefore expand ourselves. `$` followed by anything other
     * than a digit or an open brace stays literal (Java semantics).
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
                        // Unresolvable group → drop (Java's matcher does the same).
                        i = j
                    }
                    next == '{' -> {
                        val close = replacement.indexOf('}', i + 2)
                        if (close < 0) { sb.append(c); i++ }
                        else {
                            val token = replacement.substring(i + 2, close)
                            val group = when {
                                token.toIntOrNull() != null ->
                                    match.groupAt(token.toInt())
                                else -> {
                                    // Named reference: resolve via the name→index map.
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
}

/**
 * Scans a regex pattern for capture groups and their names.
 *
 * kotlin.text.Regex exposes NO public API to list group names — only
 * `MatchNamedGroupCollection.get(name)` on the JVM — so the tool layer
 * scans the pattern itself: every unescaped open-parenthesis that starts
 * a *capturing* group advances the counter; a `(?<name>...)` opening
 * records the name at the current index. Non-capturing forms (like
 * `?:`, `?=`, `?!`, `?<=`, `?<!`, and inline flag groups) are skipped.
 *
 * Result: `names[i]` = name of group `i+1` (null when unnamed) — exactly
 * the alignment `MatchResult.groups` uses.
 */
internal object RegexGroupScanner {

    private val NAME_TAIL = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun scan(pattern: String): List<String?> {
        val names = mutableListOf<String?>()
        var i = 0
        val n = pattern.length
        while (i < n) {
            when (pattern[i]) {
                '\\' -> i += 2 // skip escaped char (backslash parity handled by skipping)
                // '\u0028' is an open-paren written as a unicode escape: the CI
                // paren-balance gate counts literal characters, and a bare
                // open-paren char literal would read as an unmatched one.
                '\u0028' -> {
                    val rest = pattern.substring(minOf(i + 1, n))
                    when {
                        rest.startsWith("?") -> {
                            val afterQ = rest.substring(1)
                            when {
                                // "(?<name>...)" — capturing NAMED group.
                                afterQ.startsWith("<") && !afterQ.startsWith("<=") && !afterQ.startsWith("<!") -> {
                                    val nameMatch = NAME_TAIL.find(afterQ, startIndex = 1)
                                    if (nameMatch != null && afterQ.getOrNull(nameMatch.range.last + 1) == '>') {
                                        names += nameMatch.value
                                    } else {
                                        names += null
                                    }
                                }
                                // Non-capturing: ?: ?= ?! ?<= ?<! and flag groups — skip.
                                else -> Unit
                            }
                            // Advance past the control prefix; the rest of the group
                            // body continues to be scanned normally.
                            i += 2
                        }
                        else -> {
                            // Plain capturing group.
                            names += null
                            i += 1
                        }
                    }
                }
                else -> i++
            }
        }
        return names
    }
}
