package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A68.1 — Deterministic fake [ToolExecutor] for orchestrator tests.
 *
 * Routes tool calls to a [ScriptedTool] registered by `toolName`. Each tool
 * either:
 * - returns a fixed stream of [ToolStreamEvent]s (success case),
 * - throws a specified exception (failure case),
 * - or sleeps for `delayMs` before completing (timeout test case).
 *
 * Captures every call into [callLog] for assertions.
 *
 * Lives ONLY in the test source set.
 */
class FakeToolExecutor : ToolExecutor {

    /** Sealed type for scripting a tool's behaviour. */
    sealed class ScriptedTool {
        /** Stream these events in order. */
        data class Events(val events: List<ToolStreamEvent>) : ScriptedTool()

        /** Throw [error] when executeStream is called. */
        data class Throw(val error: Throwable) : ScriptedTool()

        /**
         * Sleep for [delayMs] then emit [finalEvent]. Used for per-tool timeout
         * tests — set [delayMs] > orchestrator's `toolTimeoutMs` to force a
         * timeout.
         */
        data class Delay(
            val delayMs: Long,
            val finalEvent: ToolStreamEvent = ToolStreamEvent.Complete("done after delay")
        ) : ScriptedTool()
    }

    private val tools = mutableMapOf<String, ScriptedTool>()
    private val _callLog = mutableListOf<Pair<String, String>>()
    val callLog: List<Pair<String, String>> get() = _callLog.toList()

    /** Register a scripted behaviour for a tool name. */
    fun register(toolName: String, behaviour: ScriptedTool) {
        tools[toolName] = behaviour
    }

    /** Convenience: register a simple successful tool that emits one Output + Complete. */
    fun registerSuccess(toolName: String, output: String) {
        tools[toolName] = ScriptedTool.Events(
            listOf(
                ToolStreamEvent.Output(output),
                ToolStreamEvent.Complete(output)
            )
        )
    }

    /** Convenience: register a tool that emits a streaming progress + output. */
    fun registerStreaming(toolName: String, chunks: List<String>, percent: Float? = null) {
        val events = mutableListOf<ToolStreamEvent>()
        chunks.forEachIndexed { i, chunk ->
            events.add(ToolStreamEvent.Output(chunk))
            if (percent != null) {
                events.add(ToolStreamEvent.Progress(percent = (i + 1) / chunks.size.toFloat(), message = null))
            }
        }
        events.add(ToolStreamEvent.Complete(chunks.joinToString("")))
        tools[toolName] = ScriptedTool.Events(events)
    }

    /** Convenience: register a tool that fails with [ToolStreamEvent.Error]. */
    fun registerError(toolName: String, errorMessage: String) {
        tools[toolName] = ScriptedTool.Events(
            listOf(ToolStreamEvent.Error(errorMessage))
        )
    }

    /** Convenience: register a tool that throws [error] (not via Error event). */
    fun registerThrow(toolName: String, error: Throwable) {
        tools[toolName] = ScriptedTool.Throw(error)
    }

    /** Convenience: register a tool that sleeps [delayMs] before completing. */
    fun registerDelayed(toolName: String, delayMs: Long) {
        tools[toolName] = ScriptedTool.Delay(delayMs)
    }

    override suspend fun execute(toolId: String, arguments: String): String {
        _callLog.add(toolId to arguments)
        return when (val scripted = tools[toolId]) {
            is ScriptedTool.Events -> scripted.events
                .filterIsInstance<ToolStreamEvent.Output>()
                .joinToString("") { it.chunk }
                .ifEmpty {
                    (scripted.events.lastOrNull() as? ToolStreamEvent.Complete)?.output ?: ""
                }
            is ScriptedTool.Throw -> throw scripted.error
            is ScriptedTool.Delay -> {
                delay(scripted.delayMs)
                (scripted.finalEvent as? ToolStreamEvent.Complete)?.output ?: "done"
            }
            null -> "Error: Tool '$toolId' not registered in FakeToolExecutor"
        }
    }

    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        _callLog.add(toolId to arguments)
        val scripted = tools[toolId]
            ?: ScriptedTool.Events(listOf(ToolStreamEvent.Error("Tool '$toolId' not registered")))
        when (scripted) {
            is ScriptedTool.Events -> scripted.events.forEach { emit(it) }
            is ScriptedTool.Throw -> throw scripted.error
            is ScriptedTool.Delay -> {
                delay(scripted.delayMs)
                emit(scripted.finalEvent)
            }
        }
    }
}
