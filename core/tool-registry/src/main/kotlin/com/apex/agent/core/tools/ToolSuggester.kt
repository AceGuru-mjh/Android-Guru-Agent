package com.apex.agent.core.tools

/**
 * # Tool System v2 — Name Suggestion
 *
 * v1's unknown-tool error dumps the *entire* tool id list into the prompt:
 * with 40+ tools that's a wall of ids the model has to re-read, and it
 * teaches nothing about which one it *meant*. v1.5 papers over it in the
 * engine; v2 fixes it at the source — the executor now answers a bad tool
 * id with the few closest ids:
 *
 * ```
 * Error: Tool 'regex_extact' not found. Did you mean: regex_extract?
 * ```
 *
 * [ToolSuggester] implements the matching: bounded Levenshtein distance
 * against every candidate id, plus prefix/substring boosts so short typos
 * (`regex_`) still surface the right family. Pure functions, no state.
 */

object ToolSuggester {

    /** Max suggestions returned by [suggest]. */
    const val DEFAULT_MAX_SUGGESTIONS = 3

    /**
     * Rank [candidates] by similarity to [input] and return the best
     * [maxSuggestions] matches (possibly empty when nothing is close).
     *
     * Scoring (lower = better rank):
     * 1. exact match (score 0) — excluded by callers since they only ask
     *    after a lookup miss;
     * 2. candidate starts with input (score 1) — model truncated the id;
     * 3. candidate contains input (score 2) — model dropped a segment;
     * 4. Levenshtein distance normalized by length (score 3 + ratio).
     */
    fun suggest(
        input: String,
        candidates: Collection<String>,
        maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS
    ): List<String> {
        if (input.isBlank() || candidates.isEmpty()) return emptyList()
        val needle = input.trim().lowercase()

        data class Scored(val id: String, val score: Double)

        val scored = candidates
            .filter { it.isNotBlank() }
            .map { candidate ->
                val hay = candidate.lowercase()
                val score = when {
                    hay == needle -> 0.0
                    hay.startsWith(needle) -> 1.0
                    hay.contains(needle) -> 2.0
                    else -> {
                        val dist = boundedLevenshtein(needle, hay, MAX_DISTANCE)
                        if (dist == null) Double.MAX_VALUE
                        else 3.0 + dist.toDouble() / maxOf(needle.length, hay.length)
                    }
                }
                Scored(candidate, score)
            }
            .filter { it.score < Double.MAX_VALUE }
            .sortedWith(compareBy<Scored> { it.score }.thenBy { it.id.length })

        return scored.take(maxSuggestions).map { it.id }
    }

    /**
     * Ready-to-embed suggestion line for an unknown-tool error, or null
     * when nothing is close enough to suggest.
     */
    fun suggestionLine(input: String, candidates: Collection<String>): String? {
        val matches = suggest(input, candidates)
        if (matches.isEmpty()) return null
        return "Did you mean: ${matches.joinToString(", ")}?"
    }

    /**
     * Levenshtein distance with an early exit: returns null as soon as the
     * edit distance is known to exceed [limit] (prunes the candidate set
     * without paying the full DP for hopeless pairs).
     *
     * Two-row DP, O(min-length) extra memory.
     */
    fun boundedLevenshtein(a: String, b: String, limit: Int): Int? {
        if (a == b) return 0
        val lenA = a.length
        val lenB = b.length
        if (kotlin.math.abs(lenA - lenB) > limit) return null

        // Ensure `a` is the shorter string → smaller row width.
        val (s, t) = if (lenA <= lenB) a to b else b to a
        val n = s.length
        val m = t.length

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (j in 1..m) {
            curr[0] = j
            var rowMin = curr[0]
            for (i in 1..n) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                curr[i] = minOf(
                    prev[i] + 1,        // deletion
                    curr[i - 1] + 1,    // insertion
                    prev[i - 1] + cost  // substitution
                )
                if (curr[i] < rowMin) rowMin = curr[i]
            }
            if (rowMin > limit) return null // every path already too costly
            val swap = prev
            prev = curr
            curr = swap
        }
        val distance = prev[n]
        return if (distance <= limit) distance else null
    }

    /** Distance cutoff used by [suggest] (length-scaled). */
    private const val MAX_DISTANCE = 3
}
