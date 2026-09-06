package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolArgumentException
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.coroutines.CancellationException

/**
 * # Tool System v2 — Base Tool
 *
 * Boilerplate-eliminating base class for v2 tools. A subclass provides:
 *
 * 1. identity — `id`, `name`, `description` (constructor or overrides);
 * 2. schema — a [ToolSchema] built with the DSL (`toolSchema { … }`) — this
 *    single object is the source for `parametersSchema` (rendered JSON) AND
 *    the runtime validation rules (executor-level, before the tool runs);
 * 3. behaviour — [executeStructured], returning a [ToolResult].
 *
 * Everything else is handled here, uniformly:
 *
 * - **Argument parsing** — [ToolArguments.of] turns bad JSON into a
 *   structured INVALID_JSON result; [ToolArgumentException]s thrown by the
 *   typed readers become field-precise MISSING/INVALID_ARGUMENT results.
 * - **Crash containment** — any exception escaping [executeStructured]
 *   (except [CancellationException], which must propagate to keep coroutine
 *   cancellation honest) becomes EXECUTION_FAILED instead of a bubbling
 *   crash that would tear down the agent loop.
 * - **v1 string protocol** — `execute()` renders the structured result, so
 *   the engine, streaming executor and SafeAgentTool wrapping all keep
 *   working unchanged.
 * - **Metadata** — [buildMetadata] defaults to [ToolMetadata.infer] (id
 *   prefix rules); subclasses override for explicit declarations, e.g.
 *   `override fun buildMetadata() = ToolMetadata.meta(id) { tag("json") }`.
 */
abstract class BaseTool(
    override val id: String,
    override val name: String,
    override val description: String,
    private val declaredSchema: ToolSchema
) : StructuredAgentTool {

    /** Rendered once at construction; the DSL object stays the source of truth. */
    final override val parametersSchema: String = declaredSchema.render()

    /** v2 metadata — computed once (declared via [buildMetadata]). */
    override val metadata: ToolMetadata = buildMetadata()

    /**
     * Declare this tool's metadata. Default: id-based inference. Override to
     * pin category/risk or add search tags.
     */
    protected open fun buildMetadata(): ToolMetadata = ToolMetadata.infer(id)

    /** v1 string bridge — renders [executeStructured] and never throws. */
    final override suspend fun execute(arguments: String): String =
        executeSafe(arguments).render()

    /**
     * Entry point with full argument/crash containment. Subclasses almost
     * always want [executeStructured] (raw contract); this exists so
     * adapters calling `execute` still get a structured result.
     */
    suspend fun executeSafe(arguments: String): ToolResult {
        return try {
            executeStructured(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            ToolArguments.toResult(e)
        } catch (e: Throwable) {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "${e::class.simpleName ?: "exception"}: ${e.message ?: "no message"}"
            )
        }
    }

    /**
     * Parse-and-read convenience (nullable form). Prefer the canonical
     * outcome form in [executeStructured]:
     * ```
     * val args = when (val parsed = ToolArguments.of(arguments)) {
     *     is ToolArguments.ParseOutcome.Ok -> parsed.args
     *     is ToolArguments.ParseOutcome.Bad -> return parsed.result
     * }
     * ```
     */
    protected fun parseArguments(arguments: String): ToolArguments? =
        ToolArguments.parseOrNull(arguments)

    /** Fail-fast structured error for tools that prefer guard clauses. */
    protected fun fail(code: ToolErrorCode, message: String): ToolResult =
        ToolResult.fail(code, message)
}
