package com.apex.agent.core.tools

import kotlinx.coroutines.flow.Flow

/**
 * Events emitted by a streaming tool's [StreamingAgentTool.executeStream].
 *
 * A streaming tool emits zero or more [Output] chunks while it runs, then a
 * single terminal event — either [Complete] (success) or [Error] (failure).
 * The terminal event is optional for purely chunk-based tools: the
 * [ToolExecutor.executeStream] flow simply completing is also a valid
 * termination signal (the engine treats flow completion as "done" and uses
 * the accumulated [Output] chunks as the final result).
 *
 * This mirrors the existing `AgentEvent.ToolOutputChunk` / `ToolCallComplete`
 * pair at the engine layer, but stays in the pure-JVM tool-registry module so
 * tools can be unit-tested without the agent engine.
 */
sealed interface ToolStreamEvent {

    /**
     * An incremental output chunk. Multiple chunks are concatenated by the
     * engine and surfaced to the UI as they arrive (via
     * `AgentEvent.ToolOutputChunk`), so the user sees live progress instead
     * of waiting for the whole tool to finish.
     */
    data class Output(val chunk: String) : ToolStreamEvent

    /**
     * Terminal success event. [output] carries the full final output; the
     * engine uses it as a fallback when no [Output] chunks were emitted
     * (defensive — some tools may only emit a final result). When [Output]
     * chunks already carried everything, [output] should match their
     * concatenation and the engine ignores it.
     */
    data class Complete(val output: String) : ToolStreamEvent

    /**
     * Terminal failure event. [message] is appended to the accumulated output
     * and the engine marks the tool call as unsuccessful.
     */
    data class Error(val message: String) : ToolStreamEvent
}

/**
 * Optional streaming capability for an [AgentTool].
 *
 * A tool that can produce output incrementally (e.g. `shell_execute` reading
 * a long-running process line-by-line) implements this interface in addition
 * to [AgentTool]. The [ToolExecutor] detects `StreamingAgentTool` at runtime
 * and prefers [executeStream] over the blocking [AgentTool.execute]; tools
 * that don't implement it are wrapped transparently (their single `execute`
 * result is emitted as one [ToolStreamEvent.Output] chunk followed by
 * [ToolStreamEvent.Complete]).
 *
 * Implementing this interface is the only change a tool needs to gain live
 * UI output — no engine or ViewModel edits per tool.
 */
interface StreamingAgentTool : AgentTool {

    /**
     * Streamed execution. Implementations should:
     * - emit [ToolStreamEvent.Output] chunks as output arrives;
     * - finish with [ToolStreamEvent.Complete] (or [ToolStreamEvent.Error]);
     * - be cancellable — honor coroutine cancellation so `abort()` stops the
     *   underlying work (e.g. `Process.destroy()` for shell tools).
     *
     * The returned [Flow] is collected on an IO dispatcher by the executor.
     */
    fun executeStream(arguments: String): Flow<ToolStreamEvent>
}
