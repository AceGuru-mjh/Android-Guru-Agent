package com.apex.agent.slash

/**
 * Structured representation of a slash command typed or selected in the
 * Agent chat input.
 *
 * Grammar (whitespace-tolerant):
 *
 *     /<type>:<id> [key=value ...] [positional user text ...]
 *
 * Examples:
 *     /skill:code_interpreter
 *     /skill:web_search query=Android Agent latest news
 *     /mcp:github repo=owner/name
 *     /connector:google_drive action=open
 *     /plugin:pdf_reader file=/sdcard/a.pdf
 *
 * Parsing is intentionally defensive: a missing colon, an unknown type, or
 * a malformed `key=value` token never throws — they degrade gracefully to
 * [SlashCommand.Unknown] (for the command shape) or positional user text
 * (for individual tokens). This keeps the chat input forgiving for the
 * end user.
 *
 * This file is pure JVM (no Compose / Android dependencies) so it can be
 * unit-tested in isolation.
 */
sealed interface SlashCommand {

    /** Stable type tag, e.g. `"skill"`, `"mcp"`, `"connector"`, `"plugin"`. */
    val type: String

    /** Command identifier, e.g. `"code_interpreter"`, `"github"`. */
    val id: String

    /** Parsed `key=value` arguments (insertion-ordered). */
    val args: Map<String, String>

    /** Leftover positional tokens after kv args, e.g. `"Android latest news"`. */
    val userExtra: String

    data class Skill(
        override val id: String,
        override val args: Map<String, String> = emptyMap(),
        override val userExtra: String = ""
    ) : SlashCommand {
        override val type: String = TYPE
        companion object { const val TYPE = "skill" }
    }

    data class Mcp(
        override val id: String,
        override val args: Map<String, String> = emptyMap(),
        override val userExtra: String = ""
    ) : SlashCommand {
        override val type: String = TYPE
        companion object { const val TYPE = "mcp" }
    }

    data class Connector(
        override val id: String,
        override val args: Map<String, String> = emptyMap(),
        override val userExtra: String = ""
    ) : SlashCommand {
        override val type: String = TYPE
        companion object { const val TYPE = "connector" }
    }

    data class Plugin(
        override val id: String,
        override val args: Map<String, String> = emptyMap(),
        override val userExtra: String = ""
    ) : SlashCommand {
        override val type: String = TYPE
        companion object { const val TYPE = "plugin" }
    }

    /**
     * Fallback for anything that starts with `/` but does not match the
     * `/<knownType>:<id>` shape. The raw input is preserved verbatim so the
     * agent can still see exactly what the user typed.
     */
    data class Unknown(
        val raw: String
    ) : SlashCommand {
        override val type: String = ""
        override val id: String = ""
        override val args: Map<String, String> = emptyMap()
        override val userExtra: String = ""
    }

    companion object {
        /** All supported command type prefixes, in slash-menu order. */
        val SUPPORTED_TYPES: List<String> = listOf(
            Skill.TYPE, Mcp.TYPE, Connector.TYPE, Plugin.TYPE
        )

        /**
         * `true` when [raw] begins (after trimming leading whitespace) with
         * a forward slash. Used by the ViewModel to decide whether to route
         * a message through the slash pipeline.
         */
        fun looksLikeSlash(raw: String): Boolean = raw.trimStart().startsWith("/")
    }
}

/**
 * Parses a raw input string into a structured [SlashCommand].
 *
 * Recognized shape (whitespace-tolerant):
 *
 *     /<type>:<id> [key=value ...] [positional user text ...]
 *
 * Anything that doesn't start with `/`, lacks a colon, has an empty id, or
 * uses an unknown type becomes [SlashCommand.Unknown]. Malformed kv tokens
 * (missing `=` or illegal key characters) are treated as positional user
 * text rather than dropped.
 */
object SlashCommandParser {

    /** A `key=value` token; key must start with a letter/underscore. */
    private val KV_PATTERN = Regex("""^([A-Za-z_][A-Za-z0-9_.\-]*)=(.*)$""")

    fun parse(raw: String): SlashCommand {
        val trimmed = raw.trim()
        if (!SlashCommand.looksLikeSlash(trimmed)) return SlashCommand.Unknown(raw)

        // Split the leading command token ("/<type>:<id>") from the remainder.
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val cmdPart = if (firstSpace >= 0) trimmed.substring(0, firstSpace) else trimmed
        val rest = if (firstSpace >= 0) trimmed.substring(firstSpace).trim() else ""

        val colon = cmdPart.indexOf(':')
        if (colon <= 1) return SlashCommand.Unknown(raw) // no colon, or "/:id" with empty type

        val type = cmdPart.substring(1, colon).trim().lowercase() // skip leading '/'
        val id = cmdPart.substring(colon + 1).trim()
        if (type.isEmpty() || id.isEmpty()) return SlashCommand.Unknown(raw)
        if (SlashCommand.SUPPORTED_TYPES.none { it == type }) {
            return SlashCommand.Unknown(raw)
        }

        val (kvArgs, userExtra) = parseArgs(rest)

        return when (type) {
            SlashCommand.Skill.TYPE     -> SlashCommand.Skill(id, kvArgs, userExtra)
            SlashCommand.Mcp.TYPE       -> SlashCommand.Mcp(id, kvArgs, userExtra)
            SlashCommand.Connector.TYPE -> SlashCommand.Connector(id, kvArgs, userExtra)
            SlashCommand.Plugin.TYPE    -> SlashCommand.Plugin(id, kvArgs, userExtra)
            else                        -> SlashCommand.Unknown(raw)
        }
    }

    /**
     * Splits [rest] into ordered `key=value` args and positional user text.
     *
     * kv parsing stops as soon as the first positional token is seen — once
     * free text begins, subsequent `key=value`-shaped tokens are treated as
     * user text too (mirrors how most CLIs behave).
     *
     * 缺陷 7 修复：用 sh-like 词法分析器（[tokenize]）替代 `rest.split(Regex("\\s+"))`，
     * 支持单/双引号与反斜杠转义，使 `query="a b c"` / `'key=val'` 被正确识别为
     * 单个 token 而非被空格切断。引号在 token 化阶段被消费（sh 语义），因此 kv
     * 捕获组直接拿到去引号后的值，无需额外 strip。无引号输入与旧 `split` 行为等价
     * （空白序列视为单分隔符、反斜杠在引号外保留为普通字符，保持既有兼容）。
     */
    private fun parseArgs(rest: String): Pair<Map<String, String>, String> {
        if (rest.isEmpty()) return emptyMap<String, String>() to ""

        val tokens = tokenize(rest)
        val kv = LinkedHashMap<String, String>()
        val positional = ArrayList<String>()
        var positionalStarted = false

        for (tok in tokens) {
            if (tok.isEmpty()) continue
            val m = KV_PATTERN.matchEntire(tok)
            if (m != null && !positionalStarted) {
                kv[m.groupValues[1]] = m.groupValues[2]
            } else {
                positionalStarted = true
                positional.add(tok)
            }
        }
        return kv to positional.joinToString(" ")
    }

    /**
     * sh-like 词法分析：在未引用的空白处切分，单/双引号内的内容作为同一 token
     * （引号被消费，不进入结果），双引号内支持反斜杠转义，单引号内不转义。
     * 引号外的反斜杠保留为普通字符（保持与旧 `split` 对无引号输入的兼容，
     * 不破坏 Windows 路径 / 正则等含 `\` 的 token）。
     */
    private fun tokenize(rest: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inToken = false
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '"' -> {
                    inToken = true
                    i++
                    while (i < rest.length && rest[i] != '"') {
                        if (rest[i] == '\\' && i + 1 < rest.length) {
                            sb.append(rest[i + 1]); i += 2
                        } else {
                            sb.append(rest[i]); i++
                        }
                    }
                    if (i < rest.length) i++ // 跳过闭合双引号（若存在）
                }
                c == '\'' -> {
                    inToken = true
                    i++
                    while (i < rest.length && rest[i] != '\'') {
                        sb.append(rest[i]); i++ // 单引号内反斜杠不转义
                    }
                    if (i < rest.length) i++ // 跳过闭合单引号（若存在）
                }
                c.isWhitespace() -> {
                    if (inToken) {
                        tokens.add(sb.toString()); sb.clear(); inToken = false
                    }
                    i++
                }
                else -> {
                    inToken = true
                    sb.append(c); i++
                }
            }
        }
        if (inToken) tokens.add(sb.toString())
        return tokens
    }
}
