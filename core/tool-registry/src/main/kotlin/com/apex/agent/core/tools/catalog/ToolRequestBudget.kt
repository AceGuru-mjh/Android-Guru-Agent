package com.apex.agent.core.tools.catalog

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry

/**
 * # Tool System v4 — Request tool plan (budgeted, provider-safe)
 *
 * Single assembly point for "which tools go into THIS request".
 *
 * Fixes three failure classes at once:
 * 1. **Payload explosion** — every request used to carry all ~110 schemas
 *    (~70–100 KB). The plan clamps count ([MAX_TOOLS]) and serialized size
 *    ([MAX_TOTAL_BYTES]);
 * 2. **Invalid provider names** — dotted ids are mapped through
 *    [ToolNameSanitizer]; the plan also carries the reverse map so the
 *    engine resolves model-echoed names back to registry ids;
 * 3. **Broken schemas / giant descriptions** — [ToolSchemaSanitizer] repairs
 *    and clamps every entry.
 *
 * Ordering is deterministic (sorted by provider name) — provider-side prompt
 * caches key on the tools array, so stable order = cheaper requests
 * (rikkahub-agent lesson).
 */
object ToolRequestBudget {

    /** Upper bound on tools per request (catalog covers the rest). */
    const val MAX_TOOLS = 64

    /** Upper bound on the serialized tools array (bytes). */
    const val MAX_TOTAL_BYTES = 48_000

    /**
     * @param tools the assembled request list (provider-safe names,
     *        sanitized schemas, clamped descriptions, name-sorted);
     * @param providerNameToId reverse map for execution routing;
     * @param visibleRegistryIds registry ids listed in the system prompt;
     * @param totalBytes serialized size of the tools array;
     * @param droppedByBudget ids excluded after the budget filled
     *        (empty in normal operation — catalog covers them).
     */
    data class RequestToolPlan(
        val tools: List<ToolDefinition>,
        val providerNameToId: Map<String, String>,
        val visibleRegistryIds: Set<String>,
        val totalBytes: Int,
        val droppedByBudget: List<String>
    )

    /**
     * Default plan: CORE ∪ session-activated (∪ everything when
     * [exposeAll]); legacy aliases never ship; budget-clamped; catalog
     * meta-tools guaranteed present.
     *
     * @param coreOnly degradation level 1: pure CORE set, no session
     *        activations, no exposeAll — used by the engine after a
     *        provider rejected the previous tool payload.
     */
    fun planDefault(
        registry: ToolRegistry,
        activation: ToolActivationStore,
        exposeAll: Boolean = false,
        coreOnly: Boolean = false
    ): RequestToolPlan {
        val all = registry.getAllTools()
        val byId = all.associateBy { it.id }
        val candidateIds = buildSet {
            all.forEach { tool ->
                if (!ToolTierPolicy.isLegacyAlias(tool.id)) add(tool.id)
            }
            when {
                coreOnly -> retainAll(ToolTierPolicy.CORE_TOOL_IDS)
                !exposeAll ->
                    retainAll(ToolTierPolicy.CORE_TOOL_IDS + activation.snapshot())
                // exposeAll: keep the full (non-legacy) set
            }
        }
        return assemble(all, byId, candidateIds)
    }

    /**
     * Forced plan ("调用函数" selection): ONLY the forced tools ship and the
     * engine requests `tool_choice = required` (or the specific function),
     * so the model MUST call one of them — the user-visible semantics of the
     * function selector in v4.
     */
    fun planForced(
        registry: ToolRegistry,
        forcedIds: Set<String>
    ): RequestToolPlan {
        val all = registry.getAllTools()
        val byId = all.associateBy { it.id }
        // Keep registered tools only; preserve the caller's order.
        val candidateIds = forcedIds.filter { it in byId }
        val missing = forcedIds - candidateIds.toSet()
        val plan = assemble(all, byId, LinkedHashSet(candidateIds))
        return if (missing.isEmpty()) plan else plan.copy(
            droppedByBudget = plan.droppedByBudget + missing.map { "$it (unknown id)" }
        )
    }

    // ── assembly ───────────────────────────────────────────────

    private fun assemble(
        all: List<AgentTool>,
        byId: Map<String, AgentTool>,
        candidateIds: Set<String>
    ): RequestToolPlan {
        // Name mapping covers every candidate (deterministic, collision-safe).
        val nameMap = ToolNameSanitizer.buildMapping(candidateIds)

        // Stable final order: sorted by provider name (prompt-cache friendly).
        val ordered = candidateIds
            .mapNotNull { id -> byId[id]?.let { id to it } }
            .sortedBy { (id, _) -> nameMap[id] }

        val out = mutableListOf<ToolDefinition>()
        val reverse = linkedMapOf<String, String>()
        val visible = linkedSetOf<String>()
        val dropped = mutableListOf<String>()
        var totalBytes = 0

        for ((id, tool) in ordered) {
            if (out.size >= MAX_TOOLS) {
                dropped += id
                continue
            }
            val providerName = nameMap.getValue(id)
            val schema = ToolSchemaSanitizer.sanitize(tool.parametersSchema)
            val description = ToolSchemaSanitizer.clampDescription(tool.description)
            val entry = ToolDefinition(
                name = providerName,
                description = description,
                parameters = schema
            )
            val entryBytes = schema.length + description.length + providerName.length + 48
            if (out.isNotEmpty() && totalBytes + entryBytes > MAX_TOTAL_BYTES) {
                dropped += id
                continue
            }
            out += entry
            reverse[providerName] = id
            visible += id
            totalBytes += entryBytes
        }

        return RequestToolPlan(
            tools = out,
            providerNameToId = reverse,
            visibleRegistryIds = visible,
            totalBytes = totalBytes,
            droppedByBudget = dropped
        )
    }
}
