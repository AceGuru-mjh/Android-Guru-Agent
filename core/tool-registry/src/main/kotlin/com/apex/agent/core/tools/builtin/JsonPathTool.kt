package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `json_path` — query JSON documents with a practical JSONPath subset.
 *
 * Why: agents receive JSON constantly (tool outputs, API responses,
 * settings files). v1's only lever was `text_transform`'s pretty-printer —
 * extracting `store.book[2].title` meant the model had to *re-print* the
 * whole document and eyeball it, burning context on every hop. This tool
 * makes extraction a single deterministic call.
 *
 * Supported syntax (deliberately a subset — the full JSONPath spec has
 * corners no model needs, and every unsupported corner is an error the
 * model can read):
 *
 * ```
 * $                 root (optional prefix)
 * .name  ['name']   object member
 * ..name            recursive descent (all matching descendants)
 * [0]  [-1]         array index (negative = from end)
 * [1:3]  [:2]       array slice (python-style, end-exclusive)
 * [0,2]             index union
 * [*]               wildcard over array/object values
 * [?(@.price<10)]   filter: comparisons on @.field (or @ itself)
 *                  operators: ==  !=  <  <=  >  >=  &&  ||
 *                  values: numbers, "strings", true/false, null
 * ```
 *
 * Output: single match → compact JSON; multiple → one JSON array (one
 * element per line when human-readable). No match → structured NOT_FOUND
 * with the path echoed, so the model can correct the path instead of
 * guessing.
 */
class JsonPathTool : BaseTool(
    id = "json_path",
    name = "JSON Path Query",
    description = """
        Query a JSON document with JSONPath and return matching values.
        Syntax: $.a.b, ..recursive, [0] / [-1] index, [1:3] slice, [0,2] union,
        [*] wildcard, [?(@.price<10)] filter (== != < <= > >= && ||).
        Input: {"json": "<JSON text>", "path": "$.store.book[0].title"}
        A single match returns compact JSON; multiple matches return a JSON array.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("json", required = true, description = "The JSON document to query (raw text)")
        string("path", required = true, description = "JSONPath expression, e.g. \$.store.book[?(@.price<10)].title")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("json", "query", "extract", "path")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val jsonText = args.requireString("json")
        val path = args.requireString("path")

        val root = try {
            Json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            return ToolResult.invalid(
                field = "json",
                message = "'json' is not valid JSON: ${e.message?.take(120)}"
            )
        }

        val segments = try {
            JsonPathParser.parse(path)
        } catch (e: JsonPathSyntaxException) {
            return ToolResult.invalid("path", e.message ?: "invalid JSONPath", "supported: \$.a.b, ..name, [0], [1:3], [0,2], [*], [?(@.x<10)]")
        }

        val matches = evaluate(root, segments)
        if (matches.isEmpty()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "no match for path '$path'"
            )
        }
        return ToolResult.ok(renderMatches(matches))
    }

    /** Render matched values: single → compact; multiple → per-line JSON array. */
    private fun renderMatches(matches: List<JsonElement>): String = when (matches.size) {
        1 -> matches[0].toString()
        else -> JsonArray(matches).let { array ->
            // One element per line keeps multi-match output scannable in chat UI.
            array.joinToString(prefix = "[\n  ", separator = ",\n  ", postfix = "\n]")
        }
    }

    /** Evaluate parsed segments against [root]; breadth-first over candidates. */
    internal fun evaluate(root: JsonElement, segments: List<PathSegment>): List<JsonElement> {
        var current = listOf(root)
        for (segment in segments) {
            val next = mutableListOf<JsonElement>()
            for (element in current) {
                when (segment) {
                    is PathSegment.Member -> {
                        (element as? JsonObject)?.get(segment.name)?.let { next += it }
                    }
                    is PathSegment.RecursiveMember -> {
                        collectRecursive(element, segment.name, next)
                    }
                    is PathSegment.Index -> {
                        val array = element as? JsonArray
                        val idx = segment.index
                        if (array != null) {
                            val resolved = if (idx < 0) array.size + idx else idx
                            if (resolved in array.indices) next += array[resolved]
                        }
                    }
                    is PathSegment.Slice -> {
                        val array = element as? JsonArray
                        if (array != null) next += slice(array, segment)
                    }
                    is PathSegment.Union -> {
                        val array = element as? JsonArray
                        if (array != null) {
                            segment.indices.forEach { raw ->
                                val resolved = if (raw < 0) array.size + raw else raw
                                if (resolved in array.indices) next += array[resolved]
                            }
                        }
                    }
                    is PathSegment.Wildcard -> when (element) {
                        is JsonArray -> next.addAll(element)
                        is JsonObject -> next.addAll(element.values)
                        else -> Unit
                    }
                    is PathSegment.Filter -> {
                        val candidates = when (element) {
                            is JsonArray -> element
                            is JsonObject -> JsonArray(element.values.toList())
                            else -> continue
                        }
                        candidates.forEach { candidate ->
                            if (segment.predicate.matches(candidate)) next += candidate
                        }
                    }
                }
            }
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    /** Python-style slice: negative bounds, clamp to array range, end-exclusive. */
    private fun slice(array: JsonArray, s: PathSegment.Slice): List<JsonElement> {
        val n = array.size
        val from = (if (s.from < 0) n + s.from else s.from).coerceIn(0, n)
        val to = (if (s.to < 0) n + s.to else s.to).coerceIn(0, n)
        if (to <= from) return emptyList()
        return array.subList(from, to)
    }

    /** Recursive descent: every descendant object member named [name]. */
    private fun collectRecursive(element: JsonElement, name: String, out: MutableList<JsonElement>) {
        when (element) {
            is JsonObject -> {
                element[name]?.let { out += it }
                element.values.forEach { collectRecursive(it, name, out) }
            }
            is JsonArray -> element.forEach { collectRecursive(it, name, out) }
            else -> Unit
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Path model + parser
// ═══════════════════════════════════════════════════════════════════════════

/** One parsed JSONPath step. */
internal sealed interface PathSegment {
    data class Member(val name: String) : PathSegment
    data class RecursiveMember(val name: String) : PathSegment
    data class Index(val index: Int) : PathSegment
    data class Slice(val from: Int, val to: Int) : PathSegment
    data class Union(val indices: List<Int>) : PathSegment
    data object Wildcard : PathSegment
    data class Filter(val predicate: FilterPredicate) : PathSegment
}

/** Thrown by [JsonPathParser.parse] on unsupported/malformed paths. */
internal class JsonPathSyntaxException(message: String) : Exception(message)

/**
 * Hand-written JSONPath tokenizer/parser. No regex chasing — a single pass
 * over the path with explicit position tracking, so error messages carry
 * the offending snippet.
 */
internal object JsonPathParser {

    fun parse(path: String): List<PathSegment> {
        var s = path.trim()
        if (s.isEmpty()) throw JsonPathSyntaxException("empty path")
        if (s == "$") return emptyList()
        if (s.startsWith("$")) s = s.substring(1)
        // Strip one leading dot — but NEVER break a leading "..": "$..author"
        // is recursive descent from the root, not member access.
        if (s.startsWith(".") && !s.startsWith("..")) s = s.substring(1)

        val segments = mutableListOf<PathSegment>()
        var i = 0
        val n = s.length

        fun fail(at: Int, why: String): Nothing =
            throw JsonPathSyntaxException("$why at position $at: …${s.substring(maxOf(0, at - 6), minOf(n, at + 6))}…")

        while (i < n) {
            when {
                // ..name — recursive descent
                s[i] == '.' && i + 1 < n && s[i + 1] == '.' -> {
                    i += 2
                    val start = i
                    while (i < n && s[i] != '.' && s[i] != '[') i++
                    val name = s.substring(start, i)
                    if (name.isEmpty()) fail(i, "missing name after '..'")
                    segments += PathSegment.RecursiveMember(name)
                }
                // ['name'] — bracketed member
                s[i] == '[' && i + 1 < n && s[i + 1] == '\'' -> {
                    val endQuote = s.indexOf('\'', i + 2)
                    if (endQuote < 0 || endQuote + 1 >= n || s[endQuote + 1] != ']') {
                        fail(i, "unterminated ['name']")
                    }
                    segments += PathSegment.Member(s.substring(i + 2, endQuote))
                    i = endQuote + 2
                }
                // [ … ] — bracket expressions
                s[i] == '[' -> {
                    val close = findBracketClose(s, i)
                    if (close < 0) fail(i, "unterminated '['")
                    val inner = s.substring(i + 1, close).trim()
                    segments += parseBracket(inner, i)
                    i = close + 1
                }
                // .name — plain member (leading dot consumed here)
                s[i] == '.' -> {
                    i++
                    val start = i
                    while (i < n && s[i] != '.' && s[i] != '[') i++
                    val name = s.substring(start, i)
                    if (name.isEmpty()) fail(i, "missing member name after '.'")
                    segments += PathSegment.Member(name)
                }
                // bare name at start (no leading dot — "$name" or "name")
                else -> {
                    val start = i
                    while (i < n && s[i] != '.' && s[i] != '[') i++
                    segments += PathSegment.Member(s.substring(start, i))
                }
            }
        }
        return segments
    }

    /** Find the ']' matching the '[' at [start], respecting quotes + parens. */
    private fun findBracketClose(s: String, start: Int): Int {
        var depth = 0
        var inQuote = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote -> if (c == '\'') inQuote = false
                c == '\'' -> inQuote = true
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun parseBracket(inner: String, at: Int): PathSegment {
        if (inner == "*") return PathSegment.Wildcard
        if (inner.startsWith("?")) {
            val body = inner.removePrefix("?").trim()
            if (!body.startsWith("(") || !body.endsWith(")")) {
                throw JsonPathSyntaxException("filter must look like [?(@.x<10)] at position $at")
            }
            return PathSegment.Filter(FilterParser.parse(body.removeSurrounding("(", ")")))
        }
        if (inner.contains(',')) {
            val indices = inner.split(',').map { part ->
                part.trim().toIntOrNull()
                    ?: throw JsonPathSyntaxException("union index '$part' is not an integer")
            }
            return PathSegment.Union(indices)
        }
        if (inner.contains(':')) {
            val parts = inner.split(':')
            if (parts.size != 2) throw JsonPathSyntaxException("slice must have exactly one ':'")
            val from = parts[0].trim().toIntOrNull() ?: 0
            val to = parts[1].trim().toIntOrNull() ?: Int.MAX_VALUE
            return PathSegment.Slice(from, to)
        }
        val index = inner.toIntOrNull()
            ?: throw JsonPathSyntaxException("bracket '$inner' is not index/slice/union/filter")
        return PathSegment.Index(index)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Filter expressions
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Filter predicate tree: comparisons combined with && / ||.
 * `[?(@.price<10 && @.in_stock)]`
 */
internal sealed interface FilterPredicate {
    fun matches(candidate: JsonElement): Boolean

    data class Comparison(val field: String?, val op: String, val literal: LiteralValue) : FilterPredicate {
        override fun matches(candidate: JsonElement): Boolean {
            val left = if (field == null) {
                candidate
            } else {
                (candidate as? JsonObject)?.get(field) ?: return false
            }
            return literal.compare(left, op)
        }
    }

    data class And(val left: FilterPredicate, val right: FilterPredicate) : FilterPredicate {
        override fun matches(candidate: JsonElement): Boolean = left.matches(candidate) && right.matches(candidate)
    }

    data class Or(val left: FilterPredicate, val right: FilterPredicate) : FilterPredicate {
        override fun matches(candidate: JsonElement): Boolean = left.matches(candidate) || right.matches(candidate)
    }
}

/** Filter literal: number / string / boolean / null. */
internal sealed interface LiteralValue {
    data class Num(val value: Double) : LiteralValue
    data class Str(val value: String) : LiteralValue
    data class Bool(val value: Boolean) : LiteralValue
    data object Null : LiteralValue

    fun compare(left: JsonElement, op: String): Boolean {
        val leftPrimitive = left as? JsonPrimitive ?: return false
        return when (this) {
            is Num -> {
                val lv = leftPrimitive.doubleOrNull ?: return false
                when (op) {
                    "==" -> lv == value
                    "!=" -> lv != value
                    "<" -> lv < value
                    "<=" -> lv <= value
                    ">" -> lv > value
                    ">=" -> lv >= value
                    else -> false
                }
            }
            is Str -> {
                val lv = leftPrimitive.content
                when (op) {
                    "==" -> lv == value
                    "!=" -> lv != value
                    else -> false // ordering strings is a rabbit hole; equality suffices
                }
            }
            is Bool -> {
                val lv = leftPrimitive.content.toBooleanStrictOrNull() ?: return false
                when (op) {
                    "==" -> lv == value
                    "!=" -> lv != value
                    else -> false
                }
            }
            Null -> when (op) {
                "==" -> leftPrimitive.content == "null"
                "!=" -> leftPrimitive.content != "null"
                else -> false
            }
        }
    }
}

/**
 * Filter expression parser: `@.price < 10 && (@.x == "a" || @.y >= 2)`.
 * Recursive descent with precedence (&& binds tighter than ||), no
 * parentheses support beyond the outer wrapper… actually parens ARE
 * supported via sub-parsing.
 */
internal object FilterParser {

    fun parse(expression: String): FilterPredicate {
        val tokens = tokenize(expression)
        val parser = TokenCursor(tokens)
        val result = parser.parseOr()
        if (!parser.isAtEnd) {
            throw JsonPathSyntaxException("unexpected trailing tokens in filter: '$expression'")
        }
        return result
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            when {
                s[i].isWhitespace() -> i++
                s[i] == '(' -> { tokens += Token.LParen; i++ }
                s[i] == ')' -> { tokens += Token.RParen; i++ }
                s.startsWith("&&", i) -> { tokens += Token.And; i += 2 }
                s.startsWith("||", i) -> { tokens += Token.Or; i += 2 }
                s.startsWith("==", i) -> { tokens += Token.Op("=="); i += 2 }
                s.startsWith("!=", i) -> { tokens += Token.Op("!="); i += 2 }
                s.startsWith("<=", i) -> { tokens += Token.Op("<="); i += 2 }
                s.startsWith(">=", i) -> { tokens += Token.Op(">="); i += 2 }
                s[i] == '<' -> { tokens += Token.Op("<"); i++ }
                s[i] == '>' -> { tokens += Token.Op(">"); i++ }
                s[i] == '@' -> {
                    i++
                    if (i < n && s[i] == '.') {
                        i++
                        val start = i
                        while (i < n && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                        if (start == i) throw JsonPathSyntaxException("field name missing after '@.'")
                        tokens += Token.Field(s.substring(start, i))
                    } else {
                        tokens += Token.Self
                    }
                }
                s[i] == '"' -> {
                    val end = s.indexOf('"', i + 1)
                    if (end < 0) throw JsonPathSyntaxException("unterminated string literal")
                    tokens += Token.Literal(LiteralValue.Str(s.substring(i + 1, end)))
                    i = end + 1
                }
                s[i] == '\'' -> {
                    val end = s.indexOf('\'', i + 1)
                    if (end < 0) throw JsonPathSyntaxException("unterminated string literal")
                    tokens += Token.Literal(LiteralValue.Str(s.substring(i + 1, end)))
                    i = end + 1
                }
                s[i].isDigit() || (s[i] == '-' && i + 1 < n && s[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < n && (s[i].isDigit() || s[i] == '.')) i++
                    val num = s.substring(start, i).toDoubleOrNull()
                        ?: throw JsonPathSyntaxException("bad number '${s.substring(start, i)}'")
                    tokens += Token.Literal(LiteralValue.Num(num))
                }
                s.startsWith("true", i) -> { tokens += Token.Literal(LiteralValue.Bool(true)); i += 4 }
                s.startsWith("false", i) -> { tokens += Token.Literal(LiteralValue.Bool(false)); i += 5 }
                s.startsWith("null", i) -> { tokens += Token.Literal(LiteralValue.Null); i += 4 }
                else -> throw JsonPathSyntaxException("unexpected character '${s[i]}' in filter")
            }
        }
        return tokens
    }

    private sealed interface Token {
        data object LParen : Token
        data object RParen : Token
        data object And : Token
        data object Or : Token
        data object Self : Token
        data class Op(val op: String) : Token
        data class Field(val name: String) : Token
        data class Literal(val value: LiteralValue) : Token
    }

    private class TokenCursor(private val tokens: List<Token>) {
        var pos = 0
        val isAtEnd: Boolean get() = pos >= tokens.size

        fun peek(): Token? = tokens.getOrNull(pos)
        fun next(): Token = tokens[pos++]

        fun parseOr(): FilterPredicate {
            var left = parseAnd()
            while (peek() == Token.Or) {
                next()
                left = FilterPredicate.Or(left, parseAnd())
            }
            return left
        }

        fun parseAnd(): FilterPredicate {
            var left = parseAtom()
            while (peek() == Token.And) {
                next()
                left = FilterPredicate.And(left, parseAtom())
            }
            return left
        }

        fun parseAtom(): FilterPredicate {
            val token = next()
            return when (token) {
                Token.LParen -> {
                    val inner = parseOr()
                    if (next() != Token.RParen) throw JsonPathSyntaxException("filter must close its '(' group with ')'")
                    inner
                }
                Token.Self, is Token.Field -> {
                    val field = (token as? Token.Field)?.name
                    val opToken = next()
                    val op = (opToken as? Token.Op)?.op
                        ?: throw JsonPathSyntaxException("expected comparison operator")
                    val literalToken = next()
                    val literal = (literalToken as? Token.Literal)?.value
                        ?: throw JsonPathSyntaxException("expected literal after operator")
                    FilterPredicate.Comparison(field, op, literal)
                }
                else -> throw JsonPathSyntaxException("unexpected token in filter")
            }
        }
    }
}
