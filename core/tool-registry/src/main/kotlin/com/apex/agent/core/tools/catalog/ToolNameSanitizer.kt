package com.apex.agent.core.tools.catalog

/**
 * # Tool System v4 — Provider-safe tool name mapping
 *
 * **The direct-send killer bug**: ~25 terminal tools carry dotted registry ids
 * (`terminal.exec`, `terminal.linux.bootstrap`, …). OpenAI's function-name
 * grammar is `^[a-zA-Z0-9_-]{1,64}$` — a dot makes the *entire request*
 * invalid on strict OpenAI-compatible endpoints. With no function whitelist
 * selected the app sent all ~110 tools (dots included) → 400 on every plain
 * message; manually picking a few functions in the "调用函数" menu filtered
 * the dotted tools out and "fixed" it. Hence the user's report: "直接发送对话
 * 会直接报错，还需要选择调用函数".
 *
 * This object is the single mapping point:
 * - registry id → **provider name** (dots and any other illegal chars become
 *   `_`; length clamped to 64);
 * - collisions are resolved deterministically (longer original id wins, then
 *   lexicographic order, then a numeric suffix);
 * - the engine maps provider names back to registry ids before execution, so
 *   tool calls resolve no matter what the model echoes back.
 */
object ToolNameSanitizer {

    private val ILLEGAL = Regex("[^a-zA-Z0-9_-]")
    private const val MAX_NAME_LENGTH = 64

    /**
     * Build the full bidirectional mapping for a set of registry ids.
     *
     * @param registryIds every id that could enter a request this session.
     * @return mapping of registry id → provider-safe name. Keys that map to
     *         the same sanitized name are disambiguated deterministically;
     *         [LEGACY][ToolTierPolicy.LEGACY_ALIAS_IDS] ids lose their slot
     *         to the modern dotted tool (e.g. `terminal.exec` keeps
     *         `terminal_exec`, the legacy alias gets `terminal_exec_2`) —
     *         except that the request assembly drops legacy aliases entirely,
     *         so in practice the suffix never ships.
     */
    fun buildMapping(registryIds: Collection<String>): Map<String, String> {
        // Sort: non-legacy first, then longer id first (the modern dotted id
        // "terminal.exec" is longer than nothing — the legacy flat alias IS
        // shorter than the dotted one), then lexicographic.
        val ordered = registryIds.sortedWith(
            compareByDescending<String> { ToolTierPolicy.isLegacyAlias(it).not() }
                .thenByDescending { it.length }
                .thenBy { it }
        )
        val result = linkedMapOf<String, String>()
        val taken = mutableSetOf<String>()
        for (id in ordered) {
            var name = sanitize(id)
            if (name.isEmpty()) name = "tool"
            if (name.length > MAX_NAME_LENGTH) name = name.take(MAX_NAME_LENGTH)
            if (name in taken) {
                // Deterministic disambiguation: append 2, 3, … while keeping
                // within the 64-char ceiling.
                var suffix = 2
                var candidate: String
                do {
                    val room = MAX_NAME_LENGTH - ( "_$suffix".length)
                    candidate = name.take(room) + "_$suffix"
                    suffix++
                } while (candidate in taken)
                name = candidate
            }
            taken += name
            result[id] = name
        }
        return result
    }

    /** Sanitize a single id into a provider-legal name (no de-duplication).
     *  Clamped to the 64-char provider ceiling. */
    fun sanitize(id: String): String =
        ILLEGAL.replace(id, "_").trim('_', '-').take(MAX_NAME_LENGTH).ifEmpty { "tool" }

    /**
     * Reverse lookup helper: find the registry id whose provider name is
     * [providerName]. Falls back to the raw value (a model may echo an id
     * verbatim when the tool was never renamed).
     */
    fun resolveRegistryId(providerName: String, mapping: Map<String, String>): String =
        mapping.entries.firstOrNull { it.value == providerName }?.key ?: providerName
}
