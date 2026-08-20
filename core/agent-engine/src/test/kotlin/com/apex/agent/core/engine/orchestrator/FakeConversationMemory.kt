package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.llm.LlmMessage

/**
 * A68.1 — In-memory [ConversationMemory] for orchestrator tests.
 *
 * Captures every [load] / [append] / [save] / [clear] call so tests can
 * assert on persistence behaviour without touching SharedPreferences or
 * filesystem.
 *
 * Lives ONLY in the test source set.
 */
class FakeConversationMemory : ConversationMemory {

    private val store = mutableListOf<LlmMessage>()

    var loadCallCount = 0
        private set
    var saveCallCount = 0
        private set
    var appendCallCount = 0
        private set
    var clearCallCount = 0
        private set

    override fun load(): List<LlmMessage> {
        loadCallCount++
        return store.toList()
    }

    override fun append(message: LlmMessage) {
        appendCallCount++
        store.add(message)
    }

    override fun save(messages: List<LlmMessage>) {
        saveCallCount++
        store.clear()
        store.addAll(messages)
    }

    override fun clear() {
        clearCallCount++
        store.clear()
    }

    override fun count(): Int = store.size

    /** Test helper: direct read access to the backing list. */
    fun snapshot(): List<LlmMessage> = store.toList()
}
