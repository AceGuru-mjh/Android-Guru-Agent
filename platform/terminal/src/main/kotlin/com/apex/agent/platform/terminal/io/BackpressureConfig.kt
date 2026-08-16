package com.apex.agent.platform.terminal.io

/**
 * Backpressure configuration (Spec PR #52 §4).
 *
 * Bounded retention for 3 layers — prevents unbounded memory growth when Agent isn't observing
 * (e.g. `yes` producing MB/s while Agent idle).
 *
 *   EVENT_BUFFER   — EventLog capacity (events per session)
 *   SCREEN_STATE   — VT screen cells (rows × cols, fixed; scrollback is separate)
 *   RAW_OUTPUT     — RingBuffer byte capacity (already exists, 256KB default)
 *
 * Overflow behavior per layer:
 *   EVENT_BUFFER: oldest events evicted; subscriber with old cursor gets CursorExpired
 *   SCREEN_STATE: scrollback trimmed to maxScrollbackRows
 *   RAW_OUTPUT: oldest bytes evicted; observe(afterCursor=old) gets overrun+availableFrom
 *
 * This is NOT a perf optimization — it's correctness (bounded memory). Spec §4: "不能简单地
 * StringBuilder.append() 无限增长".
 */
data class BackpressureConfig(
    val eventBufferLimit: Int = 10_000,           // max events retained per session
    val rawOutputBytes: Int = 256 * 1024,         // RingBuffer capacity (256KB default)
    val maxScrollbackRows: Int = 1000,            // VT scrollback lines retained
    val screenStateRefreshMinIntervalMs: Long = 16 // throttle screenState Flow emission (60 FPS cap)
) {
    companion object {
        /** Default config (balanced for Agent + UI). */
        val DEFAULT = BackpressureConfig()

        /** High-volume config (long-running build logs). */
        val HIGH_VOLUME = BackpressureConfig(
            eventBufferLimit = 50_000,
            rawOutputBytes = 4 * 1024 * 1024,  // 4 MB
            maxScrollbackRows = 5000
        )

        /** Low-memory config (constrained devices). */
        val LOW_MEMORY = BackpressureConfig(
            eventBufferLimit = 2_000,
            rawOutputBytes = 64 * 1024,  // 64 KB
            maxScrollbackRows = 200
        )
    }
}
