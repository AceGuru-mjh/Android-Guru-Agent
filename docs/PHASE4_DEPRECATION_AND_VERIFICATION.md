# ATR 2.0 — Phase 4 Deprecation & ONE-SESSION Verification

> Spec ref: ATR 2.0 Final Spec §4.3 / §36 (UI architecture) / §45 Phase 4 / §46 (terminal_exec fix)

## 1. Files to DELETE (Phase 4, post-review)

These files are superseded by the new Runtime and should be deleted once the compat layer
is confirmed working. **The PR ships them untouched** (compile-safe); deletion happens in
a follow-up commit after you review.

| File | Superseded by | Reason |
|---|---|---|
| `platform/terminal/src/main/cpp/ansi_filter.h` | `VT100Emulator` | strip-only ANSI; VT100Emulator parses it properly |
| `platform/terminal/src/main/cpp/ansi_filter.cpp` | `VT100Emulator` | same |
| `platform/terminal/src/main/kotlin/.../AnsiStripper.kt` | `VT100Emulator` | Kotlin-side backup stripper, now redundant |
| `app/.../tools/StreamingTerminalExecTool.kt` | `terminal.run` + `terminal.observe` | duplicate `id=terminal_exec` (§46 collision) |
| `platform/terminal/.../tools/TerminalExecTool.kt` | `LegacyExecTool` | old non-streaming exec; LegacyExecTool replaces |
| `platform/terminal/.../tools/TerminalSendTool.kt` | `LegacySendTool` / `TerminalWriteTool` | alias exists |
| `platform/terminal/.../tools/TerminalReadTool.kt` | `LegacyReadTool` / `TerminalObserveTool` | alias exists |
| `platform/terminal/.../tools/TerminalListTool.kt` | `LegacyListTool` / `TerminalSnapshotTool` | alias exists |
| `platform/terminal/.../PtySessionState.kt` | `session/SessionState.kt` + `job/JobState.kt` + `state/*Snapshot.kt` | split into proper modules |
| `platform/terminal/.../TerminalManager.kt` | `compat/LegacyTerminalManager.kt` | demoted to compat facade |

**Also remove from `CMakeLists.txt`**: the `ansi_filter.cpp` source line.

## 2. Files to MODIFY (Phase 5 integration, post-review)

| File | Change |
|---|---|
| `app/.../di/ToolModule.kt` | Apply `ToolRegistrationGuide`: register 9 new + 6 legacy tools; remove old 6 + streaming registration (L139, L204-209) |
| `settings.gradle.kts` | Add `include(":terminal-emulator")` |
| `platform/terminal/build.gradle.kts` | Add `implementation(project(":terminal-emulator"))` |
| `platform/terminal/.../NativePty.kt` | Keep as `object NativePty` (JNI); `JniNativePty` adapter wraps it |
| `platform/terminal/.../di/TerminalModule.kt` | Swap `provideNativePty()` from `FakeNativePty` to `JniNativePty` |
| `app/.../ui/screen/terminal/TerminalScreen.kt` | Replace old settings panel with the new pure renderer |

## 3. ONE SESSION / ONE PTY / ONE SCREEN / ONE EVENT STREAM verification

Spec §36 / §41: the final architecture must have exactly one of each per Session, shared
by Agent and Human UI. Verify post-integration:

### 3.1 ONE SESSION
- `SessionManagerImpl.assembly(sessionId)` returns a single `SessionAssembly`.
- Both `terminal.observe` (Agent) and `TerminalViewModel.semanticState` (UI) read from the
  SAME `SemanticStateReducer` instance → same `sessionId`, same `pid`.

### 3.2 ONE PTY
- `PtyOutputPumpImpl` is the SOLE reader of `nativeRead(sessionId)`.
- `InputManagerImpl` is the SOLE writer (serialized via `Channel<WriteOp>`).
- UI's `TerminalInputController` calls `runtime.write(owner=USER)` → goes through InputManager,
  NOT a separate `nativeWrite`.
- Agent's `terminal.write` calls `runtime.write(owner=AGENT)` → same InputManager.

### 3.3 ONE SCREEN
- `RealVirtualTerminal` is shared: `PtyOutputPump` feeds it; `TerminalRenderer` reads
  `virtualTerminal.snapshot()` via the SemanticState.
- There is NO separate UI-side terminal buffer.

### 3.4 ONE EVENT STREAM
- `TerminalEventLog` is the single source of truth (append-only, sharded by sessionId).
- `TerminalEventBus` broadcasts to ALL subscribers (Agent, UI, Recorder) from the SAME log.
- Each subscriber has an INDEPENDENT cursor (§23); none can advance another's.

### 3.5 Verification checklist (run after Phase 5 integration)

```
[ ] Create session via terminal.create → sessionId=12
[ ] UI binds to same sessionId=12 (TerminalViewModel.sessionId == 12)
[ ] Agent runs `terminal.run(12, "echo hello")` → UI sees "hello" in renderer
[ ] UI types a key → Agent's terminal.observe sees the InputWritten event
[ ] Agent sends SIGINT → UI shows INTERRUPTED state
[ ] Multiple terminal.observe calls with different afterCursor → no duplicate bytes
[ ] close(12) → both Agent and UI see SessionClosed
```

## 4. What the PR does NOT do (explicitly deferred)

To keep the PR compile-safe and reviewable:
- Does NOT delete any existing file (list above).
- Does NOT modify `ToolModule.kt` (registration swap is Phase 5).
- Does NOT modify `settings.gradle.kts` / `build.gradle.kts` (gradle wiring is Phase 5
  OR included as a separate clearly-marked commit).
- Does NOT modify the existing `TerminalManager.kt` / `NativePty.kt` / native cpp.
- Does NOT register the 9 new tools to ToolRegistry.

The PR ADDS:
- All new Runtime files (runtime/session/job/io/events/buffer/state/screen/wait/policy/errors/compat).
- `:terminal-emulator` module with `VT100Emulator`.
- 9 new Agent tools + 6 legacy compat tools (as files, not registered).
- `EnvironmentProvisioner` + `DepItem` + `SdkDownloader` (extracted, not yet used by UI).
- Hilt `TerminalModule` (provides Runtime; `provideNativePty` returns `FakeNativePty` for now —
  swap to `JniNativePty` after review).
- `JniNativePty` adapter (TODO-stubbed; wire to existing `object NativePty` after review).
- `ToolRegistrationGuide` (documents the exact `ToolModule.kt` rewrite).
- Rewritten `TerminalViewModel` + `TerminalSettingsViewModel` + `TerminalScreen` + `TerminalRenderer`
  + `TerminalInputController`.
