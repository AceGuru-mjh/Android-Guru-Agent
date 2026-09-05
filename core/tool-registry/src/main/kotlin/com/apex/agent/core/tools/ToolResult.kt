package com.apex.agent.core.tools

/**
 * # Tool System v2 — Structured Results
 *
 * v1 tools return a bare `String` where failures are recognized by the
 * `"Error: "` prefix. That convention works, but it loses *why* a call
 * failed: the engine cannot distinguish a bad argument (retryable with a
 * fixed payload) from a permission denial (never retry as-is) or a sandbox
 * violation (never retry, tell the user).
 *
 * v2 adds [ToolResult] — a structured outcome with a typed [ToolErrorCode].
 * The string protocol remains a first-class citizen: [ToolResult.render]
 * produces the exact same `"Error: …"` strings the engine already parses,
 * so nothing downstream changes. Structured information is additive:
 *
 * ```
 * ToolResult.ok("42")                        →  "42"
 * ToolResult.missing("path")                 →  Error: missing required argument 'path'
 * ToolResult.denied("user denied")           →  Error: permission denied: user denied
 * ```
 *
 * [StructuredAgentTool] is the opt-in interface for tools that produce
 * [ToolResult]s. The default `execute()` implementation renders the result,
 * so a structured tool is automatically a valid v1 [AgentTool].
 */

/**
 * Machine-readable failure classification.
 *
 * The distinction matters for the agent loop: argument errors tell the LLM
 * to fix its payload; permission/sandbox errors tell it to change strategy;
 * execution errors are genuine tool-side failures worth reporting to the
 * user verbatim.
 */
enum class ToolErrorCode(
    /** Short lowercase slug used in rendered output and JSON reports. */
    val slug: String,
    /** True when retrying with a *corrected* payload can succeed. */
    val retryableWithBetterArgs: Boolean
) {
    /** Arguments were not valid JSON. */
    INVALID_JSON("invalid_json", true),

    /** A required argument was absent. */
    MISSING_ARGUMENT("missing_argument", true),

    /** An argument had the wrong type or an out-of-range value. */
    INVALID_ARGUMENT("invalid_argument", true),

    /** A referenced entity (tool, file, column, key…) does not exist. */
    NOT_FOUND("not_found", true),

    /** The user or a policy denied execution. */
    PERMISSION_DENIED("permission_denied", false),

    /** The operation left the tool's sandbox (path traversal etc.). */
    SANDBOX_VIOLATION("sandbox_violation", false),

    /** The operation exceeded its time budget. */
    TIMEOUT("timeout", true),

    /** The operation failed after starting (I/O, parse failure at depth…). */
    EXECUTION_FAILED("execution_failed", false),

    /** The operation was cancelled mid-flight (user abort). */
    CANCELLED("cancelled", false);
}

/**
 * Structured failure detail. [field] names the offending argument when the
 * error is argument-related; [suggestion] carries a corrective hint (e.g.
 * the closest valid enum value).
 */
data class ToolError(
    val code: ToolErrorCode,
    val message: String,
    val field: String? = null,
    val suggestion: String? = null
) {
    /** Render in the v1 string protocol: `Error: <slug>: <message>`. */
    fun render(): String = buildString {
        append("Error: ").append(code.slug.replace('_', ' '))
        append(": ").append(message)
        if (field != null) append(" (argument '").append(field).append("')")
        if (!suggestion.isNullOrBlank()) append(". ").append(suggestion)
    }
}

/**
 * Structured outcome of a tool invocation.
 *
 * Construction goes through the [ok]/[fail] factories; [output] is null iff
 * the call failed.
 */
class ToolResult private constructor(
    val output: String?,
    val error: ToolError?
) {
    val isSuccess: Boolean get() = error == null

    /** Render in the v1 string protocol (the engine's failure detector). */
    fun render(): String = if (isSuccess) {
        output ?: ""
    } else {
        error!!.render()
    }

    override fun equals(other: Any?): Boolean =
        other is ToolResult && other.output == output && other.error == error

    override fun hashCode(): Int = (output?.hashCode() ?: 0) * 31 + (error?.hashCode() ?: 0)

    override fun toString(): String =
        if (isSuccess) "ToolResult.Ok(${output?.take(40)}…)" else "ToolResult.Fail(${error?.code})"

    companion object {
        /** Successful result carrying [output]. */
        @JvmStatic
        fun ok(output: String): ToolResult = ToolResult(output, null)

        /** Successful result with empty output. */
        @JvmStatic
        fun ok(): ToolResult = ToolResult("", null)

        /** Failure from a structured [error]. */
        @JvmStatic
        fun fail(error: ToolError): ToolResult = ToolResult(null, error)

        /** Failure with code + message (no field/suggestion). */
        @JvmStatic
        fun fail(code: ToolErrorCode, message: String): ToolResult =
            fail(ToolError(code, message))

        /** Failure for a missing required argument. */
        @JvmStatic
        fun missing(field: String): ToolResult =
            fail(ToolError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$field'", field))

        /** Failure for an invalid argument value. */
        @JvmStatic
        fun invalid(field: String, message: String, suggestion: String? = null): ToolResult =
            fail(ToolError(ToolErrorCode.INVALID_ARGUMENT, message, field, suggestion))

        /** Failure because arguments were not parseable JSON. */
        @JvmStatic
        fun badJson(cause: String): ToolResult =
            fail(ToolError(ToolErrorCode.INVALID_JSON, "arguments are not valid JSON: $cause"))

        /** Failure because the user/policy denied execution. */
        @JvmStatic
        fun denied(message: String): ToolResult =
            fail(ToolError(ToolErrorCode.PERMISSION_DENIED, message))

        /** Failure because the operation escaped its sandbox. */
        @JvmStatic
        fun sandbox(message: String): ToolResult =
            fail(ToolError(ToolErrorCode.SANDBOX_VIOLATION, message))

        /** Failure of an in-flight operation. */
        @JvmStatic
        fun execution(message: String): ToolResult =
            fail(ToolError(ToolErrorCode.EXECUTION_FAILED, message))
    }
}

/**
 * # Tool System v2 — Structured Tool Contract
 *
 * A tool that reports outcomes as [ToolResult] instead of a bare string.
 *
 * ## Contract
 *
 * - [executeStructured] must NOT throw for expected failures — it returns
 *   `ToolResult.fail(...)`. (Infrastructure may still wrap real crashes;
 *   [BaseTool] converts any escaping throwable into an EXECUTION_FAILED
 *   result.)
 * - The default [execute] renders the result to the v1 string protocol, so
 *   every structured tool is a drop-in [AgentTool] for the engine, the
 *   streaming executor and every existing call site.
 */
interface StructuredAgentTool : AgentTool {

    /** Execute and return a structured outcome (never throws for failures). */
    suspend fun executeStructured(arguments: String): ToolResult

    /** v1 string protocol bridge — renders [executeStructured]'s outcome. */
    override suspend fun execute(arguments: String): String =
        executeStructured(arguments).render()
}
