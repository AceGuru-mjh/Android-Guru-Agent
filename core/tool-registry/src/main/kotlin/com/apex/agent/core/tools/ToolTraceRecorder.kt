package com.apex.agent.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * # Tool System v3 — Structured Call Tracing
 *
 * [ToolUsageTracker] answers aggregate questions ("how often, how fast").
 * It cannot answer the questions that come up when an agent run goes
 * *wrong*: what exactly did the model call, in what order, with which
 * arguments, how did each attempt fail, and how long did the retry
 * backoff actually take? That takes per-call records.
 *
 * [ToolTraceRecorder] keeps a bounded in-memory ring of [ToolTraceSpan]s
 * (one span = one completed tool attempt, retries included) and fans
 * finished spans out to listeners (the app's diagnostics screen, a file
 * sink, tests). It is deliberately the same shape as the browser layer's
 * `BrowserTracer`: cheap to construct, capacity-bounded, append-only.
 *
 * Span lifecycle: the executor opens a span via [begin], threads the
 * handle through attempts, and completes it exactly once with
 * [complete] — the recorder stamps duration/outcome and publishes.
 * A span handle that is never completed is simply never recorded
 * (crashed/cancelled calls leave no half-written spans behind; the
 * *usage* tracker still counted the attempt).
 *
 * Output forms:
 * - [spans] snapshot (newest first) for the diagnostics UI;
 * - [render] multi-line human report;
 * - [toJson] JSON array (exported by the settings screen into the
 *   diagnostics report file).
 */
class ToolTraceRecorder(private val capacity: Int = 200) {

    /** Outcome classification for one completed attempt. */
    enum class Outcome { SUCCESS, FAILED, DENIED, SKIPPED }

    /** One completed tool-call attempt (retry attempts get their own span). */
    data class ToolTraceSpan(
        val callId: Long,
        val toolId: String,
        val startedAtEpochMs: Long,
        val durationMs: Long,
        val outcome: Outcome,
        val attempt: Int,
        val argsDigest: String,
        val errorSlug: String?,
        val truncated: Boolean
    ) {
        /** One-line human rendering: `#17 clipboard ok 12ms attempt=1 args(28)`. */
        fun render(): String {
            val outcomeMark = when (outcome) {
                Outcome.SUCCESS -> "ok"
                Outcome.FAILED -> "FAIL"
                Outcome.DENIED -> "denied"
                Outcome.SKIPPED -> "skipped"
            }
            val retryMark = if (attempt > 1) " attempt=$attempt" else ""
            val err = errorSlug?.let { " err=$it" } ?: ""
            return "#$callId $toolId $outcomeMark ${durationMs}ms$argsDigest$retryMark$err"
        }

        /** JSON object form used by [ToolTraceRecorder.toJson]. */
        fun toJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("callId", callId)
            put("toolId", toolId)
            put("startedAtMs", startedAtEpochMs)
            put("durationMs", durationMs)
            put("outcome", outcome.name.lowercase())
            put("attempt", attempt)
            put("argsDigest", argsDigest)
            errorSlug?.let { put("error", it) }
            put("truncated", truncated)
        }
    }

    /** In-flight handle returned by [begin], completed exactly once. */
    class SpanHandle internal constructor(
        val callId: Long,
        val toolId: String,
        internal val startedAtEpochMs: Long,
        internal val startedAtNano: Long,
        internal val attempt: Int,
        internal val argsDigest: String,
        internal val completed: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)
    )

    /** Listener for finished spans (diagnostics UI, file sink, tests). */
    fun interface TraceListener {
        fun onSpan(span: ToolTraceSpan)
    }

    private val spans = ConcurrentLinkedDeque<ToolTraceSpan>()
    private val listeners = CopyOnWriteListenerList()
    private val nextCallId = AtomicLong(1L)

    /**
     * Open a span for a prospective call.
     *
     * [arguments] are digested immediately (never stored raw — traces end
     * up in diagnostics exports, and raw arguments may contain secrets the
     * user pasted into a tool call; the digest keeps length + fingerprint
     * for correlation while staying export-safe).
     */
    fun begin(toolId: String, arguments: String, attempt: Int = 1): SpanHandle {
        val id = nextCallId.getAndIncrement()
        return SpanHandle(
            callId = id,
            toolId = toolId,
            startedAtEpochMs = System.currentTimeMillis(),
            startedAtNano = System.nanoTime(),
            attempt = attempt,
            argsDigest = digestArgs(arguments)
        )
    }

    /** Complete a span as [Outcome.SUCCESS] (idempotent: first call wins). */
    fun complete(handle: SpanHandle?, outcome: Outcome = Outcome.SUCCESS, errorSlug: String? = null) {
        // 幂等完成：同一 handle 的第二次 complete 是调用方 bug 的信号，静默丢弃
        // —— trace 里永远一条 span 对应一次真实尝试，不产生幻影重试记录。
        if (handle == null || !handle.completed.compareAndSet(false, true)) return
        val span = ToolTraceSpan(
            callId = handle.callId,
            toolId = handle.toolId,
            startedAtEpochMs = handle.startedAtEpochMs,
            durationMs = (System.nanoTime() - handle.startedAtNano) / 1_000_000,
            outcome = outcome,
            attempt = handle.attempt,
            argsDigest = handle.argsDigest,
            errorSlug = errorSlug,
            truncated = false
        )
        publish(span)
    }

    /** Complete a span as [Outcome.FAILED] with an error slug. */
    fun completeFailure(handle: SpanHandle?, errorSlug: String?) {
        complete(handle, Outcome.FAILED, errorSlug)
    }

    /** Complete a span as [Outcome.DENIED] (gate / breaker / rate limit). */
    fun completeDenied(handle: SpanHandle?, reason: String?) {
        complete(handle, Outcome.DENIED, reason?.take(40))
    }

    /** Register a listener for finished spans (idempotent). */
    fun addListener(listener: TraceListener) = listeners.add(listener)

    /** Unregister a listener. */
    fun removeListener(listener: TraceListener) = listeners.remove(listener)

    /** Snapshot of recorded spans, newest first (deque head = newest). */
    fun spans(): List<ToolTraceSpan> = spans.toList()

    /** Number of recorded spans (bounded by [capacity]). */
    fun size(): Int = spans.size

    /** Drop all recorded spans (test reset / session boundary). */
    fun reset() = spans.clear()

    /** Spans for one tool id, newest first. */
    fun spansFor(toolId: String): List<ToolTraceSpan> =
        spans.toList().filter { it.toolId == toolId }

    /**
     * Multi-line human report (diagnostics screen): newest [limit] spans,
     * newest first, plus a one-line outcome histogram.
     */
    fun render(limit: Int = 40): String {
        val snapshot = spans()
        if (snapshot.isEmpty()) return "no tool calls traced"
        val histogram = snapshot.groupingBy { it.outcome }.eachCount()
        return buildString {
            appendLine("tool trace (${snapshot.size} spans): " +
                "ok=${histogram[Outcome.SUCCESS] ?: 0} " +
                "fail=${histogram[Outcome.FAILED] ?: 0} " +
                "denied=${histogram[Outcome.DENIED] ?: 0} " +
                "skipped=${histogram[Outcome.SKIPPED] ?: 0}")
            snapshot.take(limit).forEach { appendLine("- ${it.render()}") }
            if (snapshot.size > limit) appendLine("… ${snapshot.size - limit} older spans omitted")
        }
    }

    /** JSON array of the newest [limit] spans (diagnostics export). */
    fun toJson(limit: Int = 200): JsonArray {
        val items = spans().take(limit).map { it.toJson() }
        return buildJsonArray { items.forEach { add(it) } }
    }

    private fun publish(span: ToolTraceSpan) {
        spans.addFirst(span)
        while (spans.size > capacity) {
            spans.pollLast()
        }
        listeners.dispatch(span)
    }

    private fun digestArgs(arguments: String): String {
        val trimmed = arguments.trim()
        if (trimmed.isEmpty()) return ""
        val printable = trimmed.count { !it.isWhitespace() }
        return " args(${trimmed.length}ch/$printable)"
    }

    /** Minimal copy-on-write listener list (append-mostly, tiny fanout). */
    private class CopyOnWriteListenerList {
        @Volatile
        private var current: Array<TraceListener> = emptyArray()

        fun add(listener: TraceListener) {
            synchronized(this) {
                if (listener !in current) current = current + listener
            }
        }

        fun remove(listener: TraceListener) {
            synchronized(this) {
                current = current.filterNot { it == listener }.toTypedArray()
            }
        }

        fun dispatch(span: ToolTraceSpan) {
            current.forEach { it.onSpan(span) }
        }
    }
}

/**
 * Monotonic per-process call id source, exposed for components that want
 * stable correlation across the tracer and the usage tracker without
 * reaching into recorder internals (the batch runner marks batch steps
 * with `batch.<callId>` in its own output).
 */
object ToolCallIds {
    private val counter = AtomicLong(1L)

    /** Next id (starts at 1; 0 is reserved as "unassigned"). */
    fun next(): Long = counter.getAndIncrement()

    /** Current value without consuming (diagnostics). */
    fun peek(): Long = counter.get()

    /** Reset (test isolation only). */
    fun reset() = counter.set(1L)
}
