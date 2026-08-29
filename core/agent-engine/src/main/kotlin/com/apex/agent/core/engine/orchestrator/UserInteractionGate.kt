package com.apex.agent.core.engine.orchestrator

import kotlinx.coroutines.CompletableDeferred

/**
 * Single pending user-input gate for the BUILD loop.
 *
 * Extracted from [DefaultTaskOrchestrator]: at most ONE request for user
 * input is outstanding at any time (the serial batch path suspends inside an
 * `ask_user` tool call until the UI answers). This class owns the
 * [CompletableDeferred] lifecycle — creation, resume via [submit] /
 * [cancel] / [abort], and cleanup on abandonment — so the orchestrator only
 * sees a plain `await()`/`complete()` pair.
 *
 * Concurrency: [submit]/[cancel]/[abortWith] may be called from any thread
 * (UI thread, abort handler); [await] is called from the loop coroutine.
 * [CompletableDeferred] guarantees single-shot completion semantics — a
 * late `submit` after the gate moved on is a harmless no-op.
 */
internal class UserInteractionGate {

    @Volatile
    private var pending: CompletableDeferred<String>? = null

    /** True while the loop is suspended awaiting an answer. */
    val isAwaiting: Boolean
        get() = pending != null

    /** Suspend until [submit]/[cancel]/[abortWith] completes the request. */
    suspend fun await(): String {
        val deferred = CompletableDeferred<String>()
        pending = deferred
        try {
            return deferred.await()
        } finally {
            if (pending === deferred) {
                pending = null
            }
        }
    }

    /** User answered — resume the loop with [answer]. */
    fun submit(answer: String) {
        pending?.complete(answer)
    }

    /** User dismissed the prompt — resume with an empty answer. */
    fun cancel() {
        pending?.complete("")
    }

    /**
     * [DefaultTaskOrchestrator.abort] was called — complete any pending
     * request with an empty answer so the loop can observe `!isRunning`
     * and finish as Aborted.
     */
    fun abortWith() {
        pending?.complete("")
        pending = null
    }
}
