package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema

/**
 * `version_compare` — semantic-version-aware comparison.
 *
 * The agent constantly meets versions it must order correctly: an APK it
 * just downloaded vs the installed app, an Ubuntu package `apt` wants to
 * upgrade, a Gradle/Kotlin dependency pin, a GitHub release tag. Lexical
 * string comparison gets all of these wrong (`1.10.0 < 1.9.0`, `2.0.0-rc1
 * > 2.0.0`), and letting the LLM eyeball the ordering costs a reasoning
 * hop *and* misfires exactly on the tricky pairs it should not guess on.
 *
 * Implements the ordering of SemVer 2.0.0 (spec sections 10-11) over a
 * practical parser that also tolerates the version shapes this project
 * actually meets in the wild:
 *
 * - bare core (`2.1.7`), missing minor/patch (`2.1`, `2` → zero-filled);
 * - the `v` prefix (`v1.2.3`) and GitHub-style release tags;
 * - pre-release ladders (`-alpha.1 < -alpha.2 < -beta < -rc.1 < (none)`);
 * - build metadata (`+build.42`) — ignored for precedence per spec §10;
 * - non-numeric core segments degrade to a best-effort alphanumeric
 *   comparison instead of hard-failing (apt versions like `1.2.3ubuntu1`
 *   are out of strict semver but still orderable in the common case).
 *
 * Output is both machine-usable (a comparison sign) and human-checkable
 * (the parsed tuples), because the model echo of "why" is what stops it
 * from re-comparing the same pair next turn.
 */
class VersionCompareTool : BaseTool(
    id = "version_compare",
    name = "Version Compare",
    description = """
        Compare two version strings with semantic-version ordering.
        Correctly handles 1.10.0 vs 1.9.0, pre-releases (rc < release),
        v-prefixes, and ignores build metadata — cases where plain string
        comparison gives wrong answers.

        Examples:
        - {"a": "1.10.0", "b": "1.9.0"} → a is newer
        - {"a": "2.0.0-rc.1", "b": "2.0.0"} → b is newer (pre-release is older than release)
        - {"a": "v1.2.3", "b": "1.2.3+build.7"} → equal precedence
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("a", required = true, description = "First version string")
        string("b", required = true, description = "Second version string")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("version", "semver", "compare", "upgrade")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val a = args.requireString("a").trim()
        val b = args.requireString("b").trim()

        if (a.isEmpty() || b.isEmpty()) {
            return ToolResult.invalid(
                field = if (a.isEmpty()) "a" else "b",
                message = "version string must not be empty"
            )
        }

        val left = parse(a)
        val right = parse(b)
        val sign = compare(left, right)

        val verdict = when {
            sign < 0 -> "'$a' is OLDER than '$b'"
            sign > 0 -> "'$a' is NEWER than '$b'"
            else -> "'$a' and '$b' have EQUAL precedence"
        }
        return ToolResult.ok(
            "$verdict\nparsed: [$a] core=${left.core} pre=${left.pre}\n" +
                "parsed: [$b] core=${right.core} pre=${right.pre}"
        )
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    /** Parsed version: zero-filled core tuple + pre-release identifier list. */
    internal data class ParsedVersion(
        val core: List<Long>,
        val pre: List<String>
    ) {
        /** True when this is a release (no pre-release ladder). */
        val isRelease: Boolean get() = pre.isEmpty()
    }

    /**
     * Parse `v?X.Y.Z(-pre)?(+build)?` into [ParsedVersion]. Tolerant of
     * missing minor/patch (zero-filled) and non-numeric segments (they are
     * kept as pre-ladder-style alphanumeric identifiers so comparison
     * stays total instead of throwing on `1.2.3ubuntu1`).
     */
    internal fun parse(text: String): ParsedVersion {
        var body = text.trim()
        if (body.startsWith("v") || body.startsWith("V")) body = body.substring(1)

        // Strip build metadata (+…) — ignored for precedence (spec §10).
        val plus = body.indexOf('+')
        if (plus >= 0) body = body.substring(0, plus)

        // Split pre-release (-…) from the core.
        val dash = body.indexOf('-')
        val coreText = if (dash >= 0) body.substring(0, dash) else body
        val preText = if (dash >= 0) body.substring(dash + 1) else ""

        val coreSegments = coreText.split('.', '/').filter { it.isNotBlank() }
        val coreNumbers = mutableListOf<Long>()
        var overflowed = false
        for ((index, segment) in coreSegments.withIndex()) {
            val number = segment.toLongOrNull()
            if (number != null) {
                coreNumbers += number
            } else {
                // Non-numeric core segment (apt suffix style): treat the
                // whole tail as an alphanumeric pre-ladder identifier so the
                // comparison stays total; e.g. "1.2.3ubuntu1" ≈ 1.2.3-ubuntu1.
                overflowed = true
                val tail = coreSegments.subList(index, coreSegments.size)
                    .joinToString("-")
                return ParsedVersion(
                    core = zeroFill(coreNumbers),
                    pre = buildList {
                        if (preText.isNotBlank()) add(preText)
                        add(tail)
                    }
                )
            }
            if (coreNumbers.size >= 4) break
        }
        val pre = if (preText.isBlank()) emptyList() else preText.split('.').filter { it.isNotBlank() }
        return ParsedVersion(core = zeroFill(coreNumbers), pre = pre)
    }

    private fun zeroFill(numbers: List<Long>): List<Long> {
        val filled = numbers.toMutableList()
        while (filled.size < 3) filled += 0L
        return filled.take(4)
    }

    // ── Comparison (SemVer §11) ────────────────────────────────────────────

    private fun compare(left: ParsedVersion, right: ParsedVersion): Int {
        // 1. Core tuples, numerically, left to right.
        val depth = maxOf(left.core.size, right.core.size)
        for (i in 0 until depth) {
            val l = left.core.getOrElse(i) { 0L }
            val r = right.core.getOrElse(i) { 0L }
            if (l != r) return l.compareTo(r)
        }

        // 2. Pre-release: a release beats any pre-release.
        if (left.isRelease && right.isRelease) return 0
        if (left.isRelease) return 1
        if (right.isRelease) return -1

        // 3. Pre-release ladders, identifier by identifier.
        val depth2 = maxOf(left.pre.size, right.pre.size)
        for (i in 0 until depth2) {
            val l = left.pre.getOrNull(i) ?: return -1 // shorter ladder is older
            val r = right.pre.getOrNull(i) ?: return 1
            val cmp = compareIdentifiers(l, r)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** Spec §11.4: numeric identifiers compare numerically and are lower
     *  than alphanumeric ones; alphanumerics compare lexically. */
    private fun compareIdentifiers(a: String, b: String): Int {
        val aNum = a.toLongOrNull()
        val bNum = b.toLongOrNull()
        return when {
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            aNum != null -> -1
            bNum != null -> 1
            else -> a.compareTo(b)
        }
    }
}
