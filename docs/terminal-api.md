# Terminal Public API Contract

> **Frozen at P60 (API Version 1.0)**
> After merge: can ADD optional fields/methods; MUST NOT change existing semantics.

## Architecture

```
Agent → Terminal (root) → TerminalSession → JobHandle / Observation / Input
```

Agent never touches: PTY, PID, ProcessHandle, TerminalCore, ObservationEngine, SessionManager, JobManager, RecoveryCoordinator.

## Lifecycle

```
terminal.createSession(SessionRequest) → TerminalSession
  ↓
session.execute(ExecutionRequest) → JobHandle
  ↓
session.observe(ObservationRequest) → ObservationResult
  ↓
session.sendInput(TerminalInput)
  ↓
job.await() → JobResult
  ↓
session.close()  // idempotent
```

## Session API

| Method | Returns | Notes |
|---|---|---|
| `createSession(SessionRequest)` | `Result<TerminalSession>` | Creates workspace |
| `getSession(SessionId)` | `TerminalSession?` | Query by ID |
| `listSessions()` | `List<SessionSummary>` | All active |
| `shutdown()` | `Result<Unit>` | Close all |
| `capabilities()` | `TerminalCapabilities` | Backend caps |
| `apiVersion()` | `String` | "1.0" |

## Session Methods

| Method | Returns | Cancellation |
|---|---|---|
| `execute(ExecutionRequest)` | `Result<JobHandle>` | Non-blocking |
| `sendInput(TerminalInput)` | `Result<Unit>` | Writes to PTY |
| `observe(ObservationRequest)` | `Result<ObservationResult>` | Incremental |
| `snapshot()` | `SessionSnapshot` | Full state |
| `resize(TerminalSize)` | `Result<Unit>` | PTY+VT+Screen sync |
| `stop()` | `Result<Unit>` | Stop jobs, session alive |
| `close()` | `Result<Unit>` | Idempotent, full cleanup |

## Job Handle

| Method | Returns | Notes |
|---|---|---|
| `cancel()` | `Result<Unit>` | Request stop (CANCELLING → CANCELLED) |
| `snapshot()` | `JobSnapshot` | Immutable |
| `await()` | `Result<JobResult>` | Blocks until terminal. Cancelling await ≠ cancelling Job |

## Error Model

All errors use `TerminalError(code, message, retryable)`. Agent matches on `code`, NOT message.

| Code | Retryable | Meaning |
|---|---|---|
| SESSION_NOT_FOUND | No | Session doesn't exist |
| SESSION_NOT_RUNNING | No | Session not in RUNNING state |
| SESSION_ALREADY_CLOSED | No | Idempotent close returns success |
| JOB_NOT_FOUND | No | Job doesn't exist |
| TIMEOUT | Yes | Operation timed out |
| CANCELLED | No | Operation cancelled |
| BACKEND_UNAVAILABLE | Yes | Backend temporarily unavailable |
| CURSOR_EXPIRED | Yes | Observation cursor too old → getSnapshot() |
| INVALID_CURSOR | No | Cursor from different session |
| UNSUPPORTED | No | Backend lacks capability |

## Observation

```
observe(cursor=null) → ObservationResult.Snapshot
observe(cursor="abc") → ObservationResult.Delta (incremental)
observe(cursor="expired") → ObservationResult.CursorExpired (re-sync)
```

Cursor is **opaque string** — Agent must not parse it. Must not cross sessions.

## Threading Contract

- `Terminal`: thread-safe (any thread/coroutine)
- `TerminalSession`: thread-safe
- `JobHandle`: thread-safe
- `TerminalSnapshot` + all public models: immutable (safe to share)
- Callbacks: dispatched on separate dispatcher, never under lock

## Reentrancy

Callbacks (`onJobFinished`, etc.) must NOT execute while holding internal locks.
State mutation → event queued → lock released → callback dispatched.

## Backend Contract

```
Terminal → ExecutionBackend
  ├── AndroidBackend (forkpty + JNI)
  ├── TermuxBackend (future)
  └── UbuntuBackend (future, via PRoot)
```

Agent never knows which backend is active. Backend swap = zero Agent code changes.

## API Freeze Rules

After P60:
- ✅ ADD: optional fields, new capabilities, new observation types
- ⚠️ CAREFUL: change field semantics, lifecycle, error codes
- ❌ FORBIDDEN: delete API, change parameter meaning, expose PID/PTY/Process to Agent

## Example

```kotlin
val terminal: Terminal = ...
val session = terminal.createSession(SessionRequest(workingDirectory = "/sdcard")).getOrThrow()
val job = session.execute(ExecutionRequest(command = "echo hello")).getOrThrow()
val result = job.await().getOrThrow()
// result.exitInfo?.exitCode == 0
session.close()
```
