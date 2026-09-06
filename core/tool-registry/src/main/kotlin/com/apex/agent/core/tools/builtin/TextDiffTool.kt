package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `text_diff` — line-level diff between two texts (Myers O((N+M)D)).
 *
 * Why: the agent's core verification loop is "edit → read back → confirm".
 * For single files `edit_file` returns the changed hunk, but comparing two
 * texts (draft vs. revised, file vs. clipboard, before/after a transform)
 * required either `shell diff` (command-gate round trip, not available in
 * sandboxed contexts) or dumping both texts and asking the model to eyeball
 * them — expensive and error-prone. A pure-JVM diff makes the comparison
 * one deterministic call with three output formats:
 *
 * - `unified` — classic `--- a / +++ b / @@ hunks` with [contextLines];
 * - `stat` — one line: `N files changed…`-style add/del counts (+ per-hunk
 *   counts in the JSON report);
 * - `json` — machine-readable op list `{"op": "equal|insert|delete",
 *   "lines": [...]}` for programmatic consumption.
 *
 * Line splitting: [lineSplit] normalizes CRLF/CR → LF and treats a
 * trailing newline as end-of-input (so "a\n" vs "a" is NOT a diff — that
 * only produces noise for the model).
 */
class TextDiffTool : BaseTool(
    id = "text_diff",
    name = "Text Diff",
    description = """
        Line-level diff of two texts (Myers algorithm). Formats: unified (default), stat, json.
        Input: {"text1": "old", "text2": "new", "format": "unified", "contextLines": 3}
        - unified: classic ---/+++/@@ hunks (contextLines controls hunk padding)
        - stat:    "3 lines changed (+2 / -1)" summary
        - json:    machine-readable [{"op":"insert|delete|equal","lines":[...]}]
        Trailing-newline and CRLF differences are ignored (no noise hunks).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("text1", required = true, description = "Old text (the '-' side)")
        string("text2", required = true, description = "New text (the '+' side)")
        string("format", description = "Output format: unified | stat | json (default unified)", enumValues = listOf("unified", "stat", "json"))
        integer("contextLines", description = "Context lines around hunks in unified format (default 3)", minimum = 0.0, maximum = 20.0)
        string("label1", description = "Label for the '-' side (default 'text1')")
        string("label2", description = "Label for the '+' side (default 'text2')")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("diff", "text", "compare", "myers")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val text1 = args.requireString("text1")
        val text2 = args.requireString("text2")
        val format = args.stringWithDefault("format", "unified")
        val contextLines = args.intWithDefault("contextLines", 3).coerceIn(0, 20)
        val label1 = args.stringWithDefault("label1", "text1")
        val label2 = args.stringWithDefault("label2", "text2")

        if (format !in setOf("unified", "stat", "json")) {
            return ToolResult.invalid("format", "unknown format '$format'", "use unified, stat or json")
        }

        val a = lineSplit(text1)
        val b = lineSplit(text2)
        val ops = MyersDiff.diff(a, b)

        return when (format) {
            "stat" -> ToolResult.ok(renderStat(ops))
            "json" -> ToolResult.ok(renderJson(ops))
            else -> ToolResult.ok(renderUnified(ops, label1, label2, contextLines))
        }
    }

    /** Normalize line endings; drop the phantom line after a trailing newline. */
    internal fun lineSplit(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        // "a\n" → ["a", ""] — the last empty element is the trailing newline,
        // not a real line. "a\n\n" → ["a", "", ""] — keep ONE empty (real blank).
        return if (normalized.endsWith('\n') && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun renderStat(ops: List<DiffOp>): String {
        val inserted = ops.filterIsInstance<DiffOp.Insert>().sumOf { it.lines.size }
        val deleted = ops.filterIsInstance<DiffOp.Delete>().sumOf { it.lines.size }
        if (inserted == 0 && deleted == 0) return "identical (no changes)"
        return "$inserted line(s) added, $deleted line(s) removed"
    }

    private fun renderJson(ops: List<DiffOp>): String {
        val array = buildJsonArray {
            ops.forEach { op ->
                add(
                    buildJsonObject {
                        put("op", when (op) {
                            is DiffOp.Equal -> "equal"
                            is DiffOp.Insert -> "insert"
                            is DiffOp.Delete -> "delete"
                        })
                        putJsonArray("lines") { op.lines.forEach { add(JsonPrimitive(it)) } }
                    }
                )
            }
        }
        return array.toString()
    }

    /** Unified diff rendering with change-hunk grouping. */
    private fun renderUnified(
        ops: List<DiffOp>,
        label1: String,
        label2: String,
        context: Int
    ): String {
        if (ops.all { it is DiffOp.Equal }) return "identical (no changes)"

        val hunks = HunkBuilder.build(ops, context)
        return buildString {
            appendLine("--- $label1")
            appendLine("+++ $label2")
            hunks.forEach { hunk ->
                appendLine("@@ -${hunk.aStart},${hunk.aCount} +${hunk.bStart},${hunk.bCount} @@")
                hunk.lines.forEach { line ->
                    val prefix = when (line.marker) {
                        HunkLine.Marker.CONTEXT -> ' '
                        HunkLine.Marker.DELETE -> '-'
                        HunkLine.Marker.INSERT -> '+'
                    }
                    appendLine("$prefix${line.text}")
                }
            }
        }.trimEnd()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Myers diff + hunk model
// ═══════════════════════════════════════════════════════════════════════════

/** One diff run: equal / inserted / deleted lines. */
internal sealed interface DiffOp {
    val lines: List<String>

    data class Equal(override val lines: List<String>) : DiffOp
    data class Insert(override val lines: List<String>) : DiffOp
    data class Delete(override val lines: List<String>) : DiffOp
}

/** Single backtracking step, with the line it consumes. */
private sealed interface Step {
    /** a[ai] == b[bi] common line. */
    data class Diag(val ai: Int, val bi: Int) : Step

    /** b[bi] inserted. */
    data class Ins(val bi: Int) : Step

    /** a[ai] deleted. */
    data class Del(val ai: Int) : Step
}

/**
 * Myers O((N+M)D) greedy diff (the 1986 paper's algorithm — the same core
 * GNU diff uses). D is the minimal edit script size; for typical agent
 * inputs (small deltas between similar texts) D is tiny and the algorithm
 * is effectively linear.
 *
 * Implementation follows the canonical "trace of V-arrays" formulation:
 * each round snapshot holds the furthest-reaching x per diagonal BEFORE
 * the round's updates, which is exactly what backtracking needs.
 */
internal object MyersDiff {

    fun diff(a: List<String>, b: List<String>): List<DiffOp> {
        val n = a.size
        val m = b.size
        val max = n + m
        val v = IntArray(2 * max + 1) // v[k + max] = furthest x on diagonal k
        val rounds = mutableListOf<IntArray>()

        if (max > 0) {
            outer@ for (d in 0..max) {
                rounds += v.copyOf() // snapshot BEFORE round d (backtrack contract)
                for (k in -d..d step 2) {
                    val idx = k + max
                    val x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                        v[idx + 1]      // move down (insert)
                    } else {
                        v[idx - 1] + 1  // move right (delete)
                    }
                    var xx = x
                    var yy = x - k
                    while (xx < n && yy < m && a[xx] == b[yy]) {
                        xx++
                        yy++
                    }
                    v[idx] = xx
                    if (xx >= n && yy >= m) break@outer
                }
            }
        } else {
            rounds += v.copyOf()
        }

        // ── Backtrack (n,m) → (0,0), collecting steps in reverse order. ──
        val steps = ArrayDeque<Step>()
        var x = n
        var y = m
        for (d in rounds.indices.reversed()) {
            if (x == 0 && y == 0) break
            val vv = rounds[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv.at(k - 1 + max) < vv.at(k + 1 + max))) {
                k + 1
            } else {
                k - 1
            }
            val prevX = vv.at(prevK + max)
            val prevY = prevX - prevK

            // Walk the snake back to its head.
            while (x > prevX && y > prevY && x > 0 && y > 0) {
                steps.addLast(Step.Diag(x - 1, y - 1))
                x--
                y--
            }
            // One edit step separates round d-1's furthest path from the snake head.
            if (d > 0 && (prevX != x || prevY != y)) {
                if (prevX == x && y > 0) {
                    steps.addLast(Step.Ins(y - 1))
                } else if (prevY == y && x > 0) {
                    steps.addLast(Step.Del(x - 1))
                }
            }
            x = prevX
            y = prevY
        }

        // Steps were collected end→start; replay in document order, merging runs.
        val ops = mutableListOf<DiffOp>()
        for (step in steps.asReversed()) {
            val op: DiffOp = when (step) {
                is Step.Diag -> DiffOp.Equal(listOf(a[step.ai]))
                is Step.Ins -> DiffOp.Insert(listOf(b[step.bi]))
                is Step.Del -> DiffOp.Delete(listOf(a[step.ai]))
            }
            mergeRun(ops, op)
        }
        return ops
    }

    /** Append [op], merging with the previous run when the type matches. */
    private fun mergeRun(ops: MutableList<DiffOp>, op: DiffOp) {
        val last = ops.lastOrNull()
        val merged = when {
            last is DiffOp.Equal && op is DiffOp.Equal -> DiffOp.Equal(last.lines + op.lines)
            last is DiffOp.Insert && op is DiffOp.Insert -> DiffOp.Insert(last.lines + op.lines)
            last is DiffOp.Delete && op is DiffOp.Delete -> DiffOp.Delete(last.lines + op.lines)
            else -> null
        }
        if (merged != null) {
            ops[ops.lastIndex] = merged
        } else {
            ops += op
        }
    }

    /** Bounded read (the k-range invariants already keep indices in range;
     *  the clamp is defense against edge-case arithmetic drift). */
    private fun IntArray.at(index: Int): Int = this[index.coerceIn(0, size - 1)]
}

/** One unified-diff hunk. */
internal data class DiffHunk(
    val aStart: Int,
    val aCount: Int,
    val bStart: Int,
    val bCount: Int,
    val lines: List<HunkLine>
)

internal data class HunkLine(val marker: HunkLine.Marker, val text: String) {
    enum class Marker { CONTEXT, DELETE, INSERT }
}

/** Groups flat diff ops into unified hunks with context windows. */
internal object HunkBuilder {

    fun build(ops: List<DiffOp>, context: Int): List<DiffHunk> {
        // First: map ops to per-line records with a/b coordinates.
        data class LineRecord(
            val marker: HunkLine.Marker,
            val text: String,
            val aLine: Int, // 1-based, 0 when not on this side
            val bLine: Int
        )

        val records = mutableListOf<LineRecord>()
        var aLine = 1
        var bLine = 1
        ops.forEach { op ->
            when (op) {
                is DiffOp.Equal -> op.lines.forEach {
                    records += LineRecord(HunkLine.Marker.CONTEXT, it, aLine, bLine)
                    aLine++
                    bLine++
                }
                is DiffOp.Delete -> op.lines.forEach {
                    records += LineRecord(HunkLine.Marker.DELETE, it, aLine, 0)
                    aLine++
                }
                is DiffOp.Insert -> op.lines.forEach {
                    records += LineRecord(HunkLine.Marker.INSERT, it, 0, bLine)
                    bLine++
                }
            }
        }

        // Mark lines that must appear: changed lines ± context.
        val include = BooleanArray(records.size)
        records.forEachIndexed { index, record ->
            if (record.marker != HunkLine.Marker.CONTEXT) {
                val from = maxOf(0, index - context)
                val to = minOf(records.lastIndex, index + context)
                for (i in from..to) include[i] = true
            }
        }

        // Split into contiguous hunk blocks.
        val hunks = mutableListOf<DiffHunk>()
        var i = 0
        while (i < records.size) {
            if (!include[i]) {
                i++
                continue
            }
            var j = i
            while (j < records.size && include[j]) j++
            val block = records.subList(i, j)

            val aLines = block.filter { it.marker != HunkLine.Marker.INSERT }
            val bLines = block.filter { it.marker != HunkLine.Marker.DELETE }
            hunks += DiffHunk(
                aStart = aLines.firstOrNull()?.aLine ?: aLine,
                aCount = aLines.size,
                bStart = bLines.firstOrNull()?.bLine ?: bLine,
                bCount = bLines.size,
                lines = block.map { HunkLine(it.marker, it.text) }
            )
            i = j
        }
        return hunks
    }
}
