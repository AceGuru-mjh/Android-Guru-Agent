package com.apex.agent.core.tools.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * # Tool System v4 — Session tool activation
 *
 * Tools the model "opened" through `tool_open` stay callable for the rest of
 * the session (the engine re-assembles the request each iteration, so an
 * activated id joins the very next request automatically — the operit
 * `use_package` pattern with native function calling instead of XML docs).
 *
 * Concurrency: the engine loop, the catalog tools and (in future) MCP
 * registration may all touch this from different dispatchers; a
 * [ConcurrentHashMap] plus a StateFlow mirror keeps it lock-free and
 * observable for UI/debug.
 *
 * Lifecycle: the engine calls [reset] at the start of every task so
 * activations never leak across conversations.
 */
class ToolActivationStore(
    /** Hard cap on simultaneously activated tools (FIFO eviction). */
    private val maxActive: Int = 24
) {
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val insertionOrder = ArrayDeque<String>()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    /** Observable snapshot (unmodifiable copy). */
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    /**
     * Activate a tool id.
     * @return true when the id was newly activated; false when it was already
     *         active (idempotent success for the caller).
     */
    fun activate(toolId: String): Boolean {
        val changed = synchronized(this) {
            if (toolId in active) return false
            active.add(toolId)
            insertionOrder.addLast(toolId)
            // FIFO eviction keeps the request payload bounded.
            while (insertionOrder.size > maxActive) {
                val evicted = insertionOrder.removeFirst()
                active.remove(evicted)
            }
            true
        }
        if (changed) publish()
        return changed
    }

    /** Deactivate a tool id (no-op when absent). */
    fun deactivate(toolId: String) {
        val changed = synchronized(this) {
            if (toolId in active) {
                active.remove(toolId)
                insertionOrder.remove(toolId)
                true
            } else false
        }
        if (changed) publish()
    }

    /** Current activated ids (insertion order preserved). */
    fun snapshot(): Set<String> = synchronized(this) { insertionOrder.toSet() }

    /** Clear all activations — the engine calls this on task start. */
    fun reset() {
        synchronized(this) {
            active.clear()
            insertionOrder.clear()
        }
        publish()
    }

    private fun publish() {
        _activeIds.value = snapshot()
    }
}
