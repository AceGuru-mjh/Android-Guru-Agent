package com.apex.agent.core.tools

/**
 * # Tool System v3 — MCP-Aligned Tool Annotations
 *
 * Industry research (MCP spec 2025-06-18, AutoGPT Block schema, Anthropic
 * computer-use docs) converges on the same vocabulary for describing what a
 * tool *does* before running it. v2 has a coarse [ToolRisk] (LOW/MEDIUM/HIGH)
 * that answers "should we ask the user?" — but the four orthogonal questions
 * below stay tangled together:
 *
 *  - can it mutate state at all?            (readOnlyHint)
 *  - is the mutation destructive/irreversible? (destructiveHint)
 *  - is repeating the same call safe?       (idempotentHint)
 *  - does it touch the outside world?       (openWorldHint)
 *
 * Why each answer matters *independently* of risk:
 *
 *  - **Retry safety** — the v3 executor retries transient failures. An
 *    idempotent tool can be retried blindly; a non-idempotent one must not
 *    (retrying `app_uninstall` after a "timeout" that actually succeeded is
 *    data loss). `idempotentHint` drives [ToolRunPolicy.maxRetries].
 *  - **Batch replay safety** — [ToolBatchRunner] reports halt causes; a
 *    destructive step that already ran must never be re-run from scratch.
 *  - **Gate copy** — "this tool permanently deletes" is a better consent
 *    prompt than "risk: HIGH".
 *  - **Prompt honesty** — openWorld tools may return arbitrary web content;
 *    the model is told to treat their output as untrusted (prompt-injection
 *    hygiene, per MCP spec security notes).
 *
 * Per the MCP spec these are *hints, not guarantees* — a tool MAY lie and
 * the executor never treats them as a security boundary (the sandbox and
 * gate remain the boundary). They are decision inputs, the same way
 * [ToolMetadata.risk] is.
 *
 * Naming note: the MCP client-hint names (`readOnlyHint` …) are kept
 * verbatim so the mapping to the spec is auditable; `sensitiveAction`
 * follows AutoGPT's Block flag (human-in-the-loop review), which the app
 * surfaces through the existing [RiskAwareToolGate] confirm dialog.
 */
data class ToolAnnotations(
    /**
     * If true, the tool does not mutate its environment (files, device
     * state, network side effects). Read-only tools are safe to call
     * speculatively — the executor may retry them and the batch runner may
     * re-run them without asking.
     */
    val readOnlyHint: Boolean = false,

    /**
     * If true, the tool's effects are destructive/irreversible when they
     * succeed (delete, uninstall, force-stop, overwrite). When the tool
     * *fails*, effects are unspecified. Drives consent copy and disables
     * auto-retry unless the tool is also idempotent.
     */
    val destructiveHint: Boolean = true,

    /**
     * If true, calling the tool repeatedly with the same arguments leaves
     * the environment in the same state as calling it once (mkdirs, "put"
     * settings, whole-file overwrite). Safe for blind retry; the primary
     * input to [ToolRunPolicy] retry budgeting.
     */
    val idempotentHint: Boolean = false,

    /**
     * If true, the tool interacts with an open world beyond the app sandbox
     * (web fetch, MCP servers, GitHub API). Its output must be treated as
     * untrusted content — never as instructions to the agent.
     */
    val openWorldHint: Boolean = false,

    /**
     * If true, the tool performs an action the user should explicitly
     * sanction each session (AutoGPT semantics). The app's risk gate maps
     * this to a human-in-the-loop confirm; a *sensitive* read (e.g. reading
     * notifications) is not destructive but still deserves a prompt.
     */
    val sensitiveAction: Boolean = false
) {

    /**
     * Whether the v3 executor may automatically retry this tool when a
     * transient error occurs (timeout / I/O flake).
     *
     * Rules, in order:
     * 1. Read-only tools are always retry-safe — a repeated read has no
     *    side effects, worst case we burn one extra call.
     * 2. Idempotent writes are retry-safe — replay converges.
     * 3. Destructive non-idempotent tools are never blindly retried: a
     *    "timeout" might have succeeded server-side; replaying deletes twice.
     */
    val retrySafe: Boolean
        get() = readOnlyHint || idempotentHint

    /** Compact one-line rendering for reports: `ro idem !destr open sens`. */
    fun summary(): String = buildString {
        if (readOnlyHint) append("ro ")
        if (idempotentHint) append("idem ")
        if (destructiveHint && !readOnlyHint) append("destr ")
        if (openWorldHint) append("open ")
        if (sensitiveAction) append("sensitive")
        if (isEmpty()) append("mutating")
    }.trim()

    companion object {

        /** Sensible defaults for a pure read (query/list/dump/get). */
        @JvmStatic
        fun readOnly(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true
        )

        /** Defaults for an overwrite-style write (whole-file, settings put). */
        @JvmStatic
        fun idempotentWrite(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true
        )

        /** Defaults for an append/patch-style write (edit_file, clipboard). */
        @JvmStatic
        fun mutating(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false
        )

        /** Defaults for a destructive one-shot action (delete, uninstall). */
        @JvmStatic
        fun destructive(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false
        )

        /** Defaults for a network/open-world read (fetch, search, MCP). */
        @JvmStatic
        fun openWorldRead(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true
        )

        /**
         * Infer annotations from id + risk — the v2 zero-migration path.
         *
         * Every v1/v2 tool gets annotations without touching its code; tools
         * that opt in via [ToolMetadata.Builder.annotations] override the
         * inference. Families mirror the curated table in the engine's
         * recovery journal (T76 idempotency registry) so the two layers
         * agree by construction on the common 80 ids.
         */
        @JvmStatic
        fun infer(toolId: String, risk: ToolRisk): ToolAnnotations = when {
            // ── Read-only families (deterministic queries) ──
            toolId == "get_time" || toolId == "get_device_info" ||
                toolId == "app_list" || toolId == "app_info" ||
                toolId == "ui_dump" || toolId == "logcat" ||
                toolId == "notification_read" ||
                toolId == "list_files" || toolId == "glob_files" ||
                toolId == "search_files" || toolId == "read_file" ||
                toolId.startsWith("memory_") ||
                toolId.startsWith("terminal.linux.status") ||
                toolId.startsWith("terminal.ubuntu.status") ||
                toolId.startsWith("terminal.snapshot") ||
                toolId.startsWith("terminal.observe") ||
                toolId.startsWith("terminal.backends") ||
                (toolId.startsWith("terminal.workspaces") && toolId.endsWith("list")) -> readOnly()

            // ── Open-world reads (web / MCP / GitHub) ──
            toolId == "web_fetch" || toolId == "web_search" ||
                toolId == "http_request" ||
                toolId == "mcp_list" || toolId == "mcp_connect" ||
                (toolId.startsWith("github_") && (
                    toolId.startsWith("github_get") ||
                        toolId.startsWith("github_read") ||
                        toolId.startsWith("github_search") ||
                        toolId.startsWith("github_list")
                    )) -> openWorldRead()

            // ── Destructive one-shots ──
            toolId == "delete_file" || toolId == "app_uninstall" ||
                toolId == "app_force_stop" ||
                toolId.startsWith("terminal.close") || toolId.startsWith("terminal.signal") ||
                toolId.startsWith("skill_uninstall") -> destructive()

            // ── Idempotent overwrite writes ──
            toolId == "write_file" || toolId == "download_file" ||
                toolId == "app_launch" || toolId == "app_install" ||
                toolId.startsWith("terminal.write") ||
                toolId.startsWith("terminal.resize") -> idempotentWrite()

            // ── Mutating, non-idempotent ──
            toolId == "edit_file" || toolId == "copy_move_file" ||
                toolId == "input_text" || toolId == "ui_tap" || toolId == "ui_swipe" ||
                toolId == "clipboard" || toolId == "screenshot" -> mutating()

            // ── Risk-level fallback for unknown/dynamic ids ──
            else -> when (risk) {
                ToolRisk.LOW -> readOnly()
                ToolRisk.MEDIUM -> mutating()
                ToolRisk.HIGH -> destructive()
            }
        }
    }
}
