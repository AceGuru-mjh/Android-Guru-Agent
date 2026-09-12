package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.coroutines.delay

/**
 * `wait` — pause between actions (Anthropic computer-use contract).
 *
 * Why a *tool* instead of telling the model "sleep via shell": the
 * computer-use toolset ships `wait` as a first-class action because UI
 * state settles asynchronously — an animation must finish, a page must
 * paint, a keyboard must close — and the natural modelling is a bounded,
 * cancellable pause the agent plans for explicitly. On this project the
 * gap is sharper: `shell sleep` routes through the permission gate and a
 * shell round-trip; `terminal.wait` waits for *terminal output*, not for
 * wall-clock time.
 *
 * Contract (mirrors Anthropic's `wait`, 2026-08 toolset):
 * - `duration_ms` capped at 300_000 (5 minutes) — longer wants a task
 *   scheduler, not an agent loop;
 * - cancellation propagates (user abort kills the wait, no zombie);
 * - the result reports *actual* elapsed time, so a truncated wait (slow
 *   dispatch, GC pause) is visible rather than silently misreported.
 *
 * Idempotent, read-only, no side effects — the safest tool in the box.
 */
class WaitTool : BaseTool(
    id = "wait",
    name = "Wait",
    description = """
        Pause for a bounded duration before the next action. Use it when
        UI state needs time to settle (animation, page load, keyboard
        dismiss, app launch) and an immediate follow-up action would race
        it. Cancellation-safe: a user abort interrupts the wait instantly.

        Examples:
        - {"duration_ms": 500} - let a screen transition settle
        - {"duration_ms": 2000} - wait for a slow page/app to paint
        - {"duration_ms": 300000} - the maximum (5 minutes)
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer(
            "duration_ms",
            required = true,
            description = "How long to wait, milliseconds (1..300000)",
            minimum = 1.0,
            maximum = 300_000.0
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("wait", "delay", "settle", "timing")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val durationMs = args.requireLong("duration_ms")

        if (durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) {
            return ToolResult.invalid(
                field = "duration_ms",
                message = "duration_ms must be between $MIN_DURATION_MS and $MAX_DURATION_MS " +
                    "(milliseconds)",
                suggestion = "for longer pauses, split the work: do other useful tool calls " +
                    "first, or ask the user to re-invoke later"
            )
        }

        // Cancellation (user abort or the v3 per-tool timeout) must propagate
        // honestly — BaseTool's crash containment deliberately rethrows
        // CancellationException, and so does this tool: a torn-down loop
        // must not keep processing a "wait finished" result.
        val startedAt = System.nanoTime()
        delay(durationMs)

        val elapsed = elapsedMs(startedAt)
        return if (elapsed + SLOP_MS >= durationMs) {
            ToolResult.ok("OK: waited ${elapsed}ms")
        } else {
            ToolResult.ok(
                "OK: waited ${elapsed}ms (requested ${durationMs}ms; " +
                    "the loop woke early — re-check the state you were waiting for)"
            )
        }
    }

    private fun elapsedMs(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000

    private companion object {
        const val MIN_DURATION_MS = 1L
        const val MAX_DURATION_MS = 300_000L
        const val SLOP_MS = 25L
    }
}
