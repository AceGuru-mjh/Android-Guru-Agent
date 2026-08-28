package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A68.3 — Tool-call dependency graph.
 *
 * When the LLM emits MULTIPLE tool calls in one response, the orchestrator
 * may execute them in parallel — but only when the calls are genuinely
 * independent. [ToolCallGraph] derives the dependency structure:
 *
 * 1. **Explicit dependencies** — a tool call may declare
 *    `"depends_on": ["<other call id>", …]` (or `"dependsOn"` / `"after"`)
 *    in its arguments JSON. This is the LLM's way of saying "run this only
 *    after that".
 *
 * 2. **Implicit same-tool ordering** — two calls to the SAME tool are
 *    chained in their emission order (call #1 → call #2). Rationale: the
 *    orchestrator cannot know whether a tool is side-effect free, and
 *    racing two `shell_exec`/`file_write` calls against each other
 *    produces nondeterministic results. This is configurable via
 *    [chainSameTool] — read-only fan-out workloads can set it to false.
 *
 * The graph computes [parallelLevels] via Kahn's algorithm: level 0 nodes
 * have no dependencies and run concurrently; level N+1 nodes depend only on
 * nodes in levels ≤ N. A dependency on a failed node marks the dependent
 * as SKIPPED (transitively) — that's the "partial failure" semantics of
 * A68.3, implemented by [markSkippedFromFailures].
 *
 * Safety: if explicit dependencies form a CYCLE (LLM said A depends on B
 * and B depends on A) the graph reports [hasCycle] and the orchestrator
 * falls back to fully serial execution in emission order — degraded but
 * always correct.
 */
class ToolCallGraph private constructor(
    val nodes: List<Node>,
    chainSameTool: Boolean = true
) {

    /** One tool call plus its derived dependency edges. */
    data class Node(
        val call: ToolCall,
        val callIndex: Int,
        /** callIds this node waits on (explicit + implicit). */
        val dependencies: Set<String>
    ) {
        val callId: String get() = call.id
        val toolName: String get() = call.name
    }

    private val byId: Map<String, Node> = nodes.associateBy { it.callId }
    private val dependents: Map<String, Set<String>> = buildDependentsMap(nodes)

    /** True when explicit `depends_on` references form a cycle. */
    val hasCycle: Boolean

    /** Explicit `depends_on` ids that referenced calls NOT in this batch. */
    val unresolvedDependencies: Set<String>

    init {
        // Cycle detection over the dependency edges (Kahn's algorithm
        // reuse: if a full topological order exists, there is no cycle).
        var remaining = nodes.map { it.callId to it.dependencies.filterTo(mutableSetOf()) { d -> byId.containsKey(d) }.toMutableSet() }.toMap().toMutableMap()
        val ordered = mutableListOf<String>()
        val queue = ArrayDeque(remaining.filter { it.value.isEmpty() }.keys)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            ordered.add(id)
            remaining.remove(id)
            for ((other, deps) in remaining) {
                deps.remove(id)
                if (deps.isEmpty() && other !in ordered && other !in queue) queue.add(other)
            }
        }
        hasCycle = remaining.isNotEmpty()

        unresolvedDependencies = nodes.flatMap { n ->
            n.dependencies.filter { !byId.containsKey(it) }
        }.toSet()
    }

    /** Reverse edge map: callId → set of callIds that depend on it. */
    private fun buildDependentsMap(nodes: List<Node>): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        nodes.forEach { n ->
            n.dependencies.forEach { dep ->
                map.getOrPut(dep) { mutableSetOf() }.add(n.callId)
            }
        }
        return map
    }

    /**
     * Kahn levels: each level's nodes depend only on nodes in strictly
     * earlier levels. Nodes within one level may run concurrently.
     *
     * When [hasCycle] is true, this returns each node as its own level in
     * emission order (fully serial fallback).
     */
    fun parallelLevels(): List<List<Node>> {
        if (hasCycle) return nodes.map { listOf(it) }

        val resolved = nodes.map { n ->
            n.callId to n.dependencies.filterTo(mutableSetOf()) { byId.containsKey(it) }.toMutableSet()
        }.toMap().toMutableMap()

        val levels = mutableListOf<List<Node>>()
        val placed = mutableSetOf<String>()
        while (placed.size < nodes.size) {
            val level = nodes.filter { n ->
                n.callId !in placed && resolved.getValue(n.callId).all { it in placed }
            }
            // Kahn guarantee: at least one node is ready (no cycle).
            level.forEach { placed.add(it.callId) }
            levels.add(level)
        }
        return levels
    }

    /**
     * Given the set of FAILED callIds, compute the transitive closure of
     * callIds that must be SKIPPED because they (transitively) depend on a
     * failure. Independent branches keep running — that's partial-failure
     * isolation.
     */
    fun markSkippedFromFailures(failed: Set<String>): Set<String> {
        val skipped = mutableSetOf<String>()
        var frontier = failed.toList()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (f in frontier) {
                dependents[f]?.forEach { dependent ->
                    if (dependent !in skipped) {
                        skipped.add(dependent)
                        next.add(dependent)
                    }
                }
            }
            frontier = next
        }
        return skipped
    }

    /** All callIds that (directly) depend on [callId]. */
    fun directDependentsOf(callId: String): Set<String> = dependents[callId] ?: emptySet()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Build a graph from a batch of tool calls.
         *
         * @param chainSameTool when true (default), same-tool calls are
         *   chained in emission order (conservative side-effect safety).
         */
        fun fromToolCalls(calls: List<ToolCall>, chainSameTool: Boolean = true): ToolCallGraph {
            val nodes = calls.mapIndexed { index, call ->
                Node(
                    call = call,
                    callIndex = index,
                    dependencies = extractDependencies(call) +
                        implicitSameToolDeps(calls, index, chainSameTool)
                )
            }
            return ToolCallGraph(nodes, chainSameTool)
        }

        /**
         * Extract explicit `depends_on` / `dependsOn` / `after` ids from a
         * call's arguments JSON. Accepts a single string or an array of
         * strings. Malformed JSON → no explicit deps (tool args are
         * LLM-generated, never trust them to be valid).
         */
        internal fun extractDependencies(call: ToolCall): Set<String> {
            if (call.arguments.isBlank()) return emptySet()
            return try {
                val obj = json.parseToJsonElement(call.arguments)
                if (obj !is JsonObject) return emptySet()
                val raw = obj["depends_on"] ?: obj["dependsOn"] ?: obj["after"]
                    ?: return emptySet()
                when (raw) {
                    is JsonArray -> raw.mapNotNull { el ->
                        (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }.toSet()
                    is JsonPrimitive -> setOfNotNull(raw.contentOrNull?.takeIf { it.isNotBlank() })
                    else -> emptySet()
                }
            } catch (e: Throwable) {
                emptySet()
            }
        }

        /**
         * Implicit ordering: later same-tool calls depend on earlier ones
         * (when [chainSameTool] is on).
         */
        private fun implicitSameToolDeps(calls: List<ToolCall>, index: Int, enabled: Boolean): Set<String> {
            if (!enabled) return emptySet()
            return calls.take(index)
                .filter { it.name == calls[index].name }
                .map { it.id }
                .toSet()
        }
    }
}
