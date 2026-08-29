package com.apex.agent.core.engine

import com.apex.agent.core.llm.ToolCall

/**
 * Accumulates streamed tool-call fragments emitted by an LLM into complete
 * [ToolCall]s.
 *
 * Streaming APIs deliver a single logical tool call as a sequence of chunks
 * (id first, then name + argument fragments). Both [ApexAgentEngine] and
 * [com.apex.agent.core.engine.orchestrator.DefaultTaskOrchestrator] need this
 * exact accumulation logic; it previously existed as two private duplicates
 * and is now unified here (single source of truth).
 *
 * Thread-safety: instances are confined to the collector coroutine of one
 * streaming response — no synchronization is required or provided.
 */
internal class StreamingToolCallAccumulator(
    val id: String,
    initialName: String = ""
) {
    /**
     * Latest non-blank name seen for this call. Streaming chunks may repeat
     * the name (or leave it blank after the first fragment); only non-blank
     * fragments update it.
     */
    var name: String = initialName
        private set

    private val argumentsBuilder = StringBuilder()

    /**
     * Append one streamed fragment.
     *
     * @param fragmentName tool name carried by this chunk ("" when the chunk
     *   only carries argument bytes)
     * @param fragmentArguments argument bytes carried by this chunk
     */
    fun append(fragmentName: String, fragmentArguments: String) {
        if (fragmentName.isNotBlank()) name = fragmentName
        argumentsBuilder.append(fragmentArguments)
    }

    /** Append argument bytes only (name carried by earlier chunks). */
    fun appendArguments(fragmentArguments: String) {
        argumentsBuilder.append(fragmentArguments)
    }

    /** Materialize the accumulated fragments into an immutable [ToolCall]. */
    fun build(): ToolCall = ToolCall(
        id = id,
        name = name,
        arguments = argumentsBuilder.toString()
    )
}
