# ATR 2.0 — Performance Targets & Verification

> Spec ref: ATR 2.0 Final Spec §49

## Targets (v1)

| # | Metric | Target | How met | Verification |
|---|---|---|---|---|
| 1 | PTY → EventBus latency | < 20 ms | PtyOutputPump reads 8KB chunks non-blocking, emits directly to SharedFlow (no intermediate queue) | Instrument `OutputProduced.timestamp` vs pump read time |
| 2 | ProcessExited event | < 100 ms | SessionManager exit watcher polls `nativeIsAlive` every 100ms; `nativeWaitExit` blocks for immediate | Timer from process death to event emit |
| 3 | Semantic snapshot | < 10 ms | SemanticStateReducer holds `MutableStateFlow<TerminalSemanticState>`; observe(SEMANTIC) reads `.value` — O(1) | Benchmark `runtime.observe(SEMANTIC)` |
| 4 | Incremental observe | 0 duplicate bytes | Cursor contract: `prev.endCursor == next.startCursor`; RingBuffer.getSince(cursor) returns exact range | Diff consecutive observe(RAW) outputs |
| 5 | UI continuous output | no main-thread block / OOM / ANR | Pump on `Dispatchers.IO`; renderer collects StateFlow (push, not poll); RingBuffer caps at 256KB | Run `yes` for 60s, monitor main thread + heap |
| 6 | RingBuffer default capacity | 256 KB | `RingTerminalBuffer.DEFAULT_CAPACITY = 256 * 1024`; configurable 64KB-4MB | Inspect `capacityBytes` |
| 7 | Concurrent subscribers | ≥ 8 | EventBus SharedFlow `extraBufferCapacity=1024`; each subscriber independent cursor | Spawn 8 subscribers, verify all receive events |

## Design choices that meet the targets

### 1. Single-reader pump (§14/§15)
Only `PtyOutputPumpImpl` calls `nativeRead`. No contention, no duplicate reads. The pump runs on `Dispatchers.IO` (never main thread). Each read produces exactly one `OutputProduced` event → RingBuffer append → VT feed → reducer update → bus emit, all on the IO dispatcher.

### 2. EventBus push, not poll (§21/§31)
`WaitEngineImpl.await()` subscribes to the EventBus Flow and uses `withTimeoutOrNull`. No `sleep + read` loops. A waiter is woken the instant a matching event emits — O(event-arrival-latency), not O(poll-interval).

### 3. Incremental SemanticState (§26)
`SemanticStateReducer.onEvent()` pattern-matches the 12 event types and updates ONLY the affected snapshot field (e.g. `OutputProduced` updates `session.cursor` + `input.state`; `ProcessExited` updates `foregroundJob` + `lastExitCode`). No full recompute. `observe(SEMANTIC)` returns `StateFlow.value` — constant time.

### 4. Cursor-based RingBuffer (§13/§22)
`getSince(cursor)` returns exactly `[cursor, cursor+available)` — no overlap with previous reads. On overrun (`cursor < oldestCursor`), returns `overrun=true` + empty bytes (never silently drops). This guarantees zero duplicate bytes across incremental observes.

### 5. Serialized writes (§17)
`InputManagerImpl` uses a per-session `Channel<WriteOp>` (unlimited capacity). A single writer coroutine drains it, calling `nativeWrite` sequentially. No write reordering, no contention. `InputControlState` arbitration (TAKEOVER) is a cheap StateFlow check before enqueue.

### 6. Memory-bounded history
- RingBuffer: 256KB default (configurable). Oldest bytes evicted on overflow; EventLog refs become `overrun`.
- EventLog: in-memory `ArrayList` per session; `tail()` takes last N. Phase 5 persistence writes only metadata + last 100 events.
- VT100Emulator: fixed `rows × cols` cell array; resize reuses.

## What's NOT optimized yet (v2+)

- **EventLog query**: `query(afterCursor)` is O(n) linear scan (filters by cursor). For very long sessions (>100K events) this could be slow; an index by cursor would help. Acceptable for v1 since `tail(N)` is the common path.
- **VT100Emulator scroll**: `scrollUp()` does `System.arraycopy` per row — O(rows×cols). For high-frequency scrolling (e.g. `yes`), this is the bottleneck. A circular row-index would make it O(cols). Acceptable for v1 (256KB RingBuffer caps visible output).
- **EventBus replay**: `subscribe(afterCursor)` replays from EventLog then live-tails. For a very old `afterCursor`, replay could be large. The `maxEvents` cap limits this.
- **Multi-session fan-out**: the exit watcher is one coroutine per session. For >50 sessions, consider a shared watcher. Acceptable for v1 (typical Agent uses 1-3 sessions).

## Verification commands (real device)

```bash
# 1. Build + run JVM tests
./gradlew :platform:terminal:test

# 2. Build the app
./gradlew :app:assembleDebug

# 3. Instrumented latency test (needs emulator)
#    adb shell am instrument -w -e class com.apex.agent.platform.terminal.PerfTest \
#      com.apex.agent.test/androidx.test.runner.AndroidJUnitRunner
```
