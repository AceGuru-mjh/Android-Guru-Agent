# Terminal Public API Contract

> **Frozen at P60 (API Version 1.0)**
> After merge: can ADD optional fields/methods; MUST NOT change existing semantics.
>
> **Scope note (T87)**: the freeze above covers the **runtime kernel API**
> (`Terminal` / `TerminalSession` / `JobHandle` semantics). The **agent tool
> surface** (`terminal.*` tool ids) evolves additively (new tools allowed,
> existing parameter meaning never changed) and is machine-verified against
> the implementation by `scripts/check_terminal_tools.sh` — see the
> [Agent Tool Surface](#agent-tool-surface-t87-snapshot--machine-verified)
> table below.

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
TerminalRuntime.create(backendId=…) → ExecutionBackendRegistry
  ├── LocalShellBackend  (id="local", forkpty + execv("/system/bin/sh","-i"))  [P71, golden]
  └── LinuxPRootBackend  (id="linux-ubuntu", forkpty + execv(libproot.so … /bin/bash -i))  [P71+T73]
```

T73 wiring: `TerminalRuntime.create()` routes through the registry (default
`backendId="local"` — byte-identical to the pre-P71 spawn, locked by
ExecutionBackendGoldenTest). Agent discovers backends via `terminal.backends()`
(availability: READY / NEEDS_ROOTFS / FAILED) and provisions the Ubuntu rootfs
via `terminal.ubuntu.install` (idempotent, resumable). Backend session metadata
(`backendId`/`rootfsId`/`workspaceId`/`guestCwd`/workspace binds) is persisted
with the session (SessionRecord schema v3) so crash recovery distinguishes local
vs Ubuntu sessions.

Agent never knows which backend is active. Backend swap = zero Agent code changes.

## Workspace & User Model (T75)

Linux sessions get two host-backed persistent mounts on top of the rootfs:

```
<filesDir>/linux/workspaces/<id>/  --bind-->  guest /workspace   (per-workspace isolation)
<filesDir>/linux/home/              --bind-->  guest /root        (persistent user home)
```

- **Workspaces** (`terminal.workspaces` tool: list / create / inspect / delete):
  each `terminal.create(backend="linux-ubuntu", workspaceId=…)` session binds its
  own isolated area at guest `/workspace`. Unknown valid ids
  (`^[a-z0-9][a-z0-9_-]{0,63}$`) are auto-created (workspace-per-task with zero
  friction). `delete` refuses while sessions are attached (close first). The
  P71/T73 single-directory workspace is atomically migrated to `default` on
  first use.
- **User home**: guest `/root` is a host-side bind, NOT inside the rootfs — user
  files survive rootfs version replacement (invalidate / reinstall). First use
  seeds `/etc/skel` from the rootfs (or a minimal `.bashrc` fallback). Guest env
  carries `HOME=/root`, `USER=root`, `LOGNAME=root`.
- LOCAL sessions reject `workspaceId` (InvalidInput — explicit over silent).


## P83 Additions (Terminal Finalization)

- **Project-aware environment** (`terminal.workspace.environment` tool):
  - `analyze` (read-only): scans a workspace's marker files
    (requirements.txt / package.json / build.gradle.kts / Cargo.toml / go.mod /
    CMakeLists.txt / Makefile / *.c|cc|cpp …) → detected language profiles →
    per-requirement capability status (probe / dpkg-verified). Never installs.
  - `ensure`: Ubuntu lifecycle `ensureReady` → analyze → one batched
    `apt install` for missing toolchains (python3+pip+venv, nodejs+npm,
    default-jdk, gcc/g++/make/cmake, rustc+cargo, golang-go) → probe
    invalidate → honest re-verify (READY / INSTALLED / STILL_MISSING /
    INSTALL_FAILED / UNKNOWN per requirement; ENV: pseudo-requirements such as
    JAVA_HOME are reported as `ENV_ADVISORY`, not installed).
- **Interactive Terminal UI** (app): styled grid renderer over the same VT core
  (per-cell ANSI/256/TrueColor, blinking cursor, scrollback follow + manual
  scroll, cell-level long-press selection & clipboard copy), IME + hardware-key
  input (DECCKM-aware arrows, Ctrl+letter, bracketed paste), view-driven PTY
  resize (SIGWINCH), multi-session tabs with backend badges, Ubuntu lifecycle
  banner (install / progress / retry), status chips (session state / foreground
  job / waiting-input prompt detection).
- **Unified workspace**: the Agent file tools (`read_file` / `write_file` /
  `list_files` / `edit_file` / `search_files` / `glob_files` / `copy_move` /
  `delete_file` / `file_hash`) now sandbox to the **default Linux workspace**
  (`<filesDir>/linux/workspaces/default`, guest `/workspace`) — the same file
  area the Ubuntu terminal sessions see. The legacy flat
  `<filesDir>/workspace` sandbox is migrated once (content moved into the
  default workspace; skipped when the target is non-empty — old dir retained
  for manual salvage).
- **VT core**: `renderSnapshot()` styled projection (colors / attributes /
  cursor / DEC modes / styled scrollback) exposed via
  `TerminalRuntime.styledScreenFlow()` — computed only while a UI collector is
  attached (backpressure contract); `CSI 3 J` now erases only the scrollback;
  HTS / CBT implemented; F1-F12 key encodings added; `changedRows` wired via
  drained dirty-region mutations.

## Agent Tool Surface (T87 snapshot — machine-verified)

The single question this section answers with certainty:
**"which terminal tools can the Agent actually call, right now?"**

### Integration path (how a tool-call reaches the PTY)

```
Agent (LLM function-call)
  → ToolRegistry                 (core:tool-registry — catalog & dispatch)
    → SafeAgentTool              (error containment: never throws at the Agent)
      → TerminalToolAdapter      (app/di — AgentTool ⇄ TerminalTool bridge)
        → TerminalXxxTool        (platform:terminal tools/v2 — JSON schema,
          │                        parameter validation, semantics)
          └→ TerminalRuntime     (P60 frozen kernel — sessions / jobs /
              │                        observation / input arbitration)
              └→ ExecutionBackendRegistry
                    ├── LocalShellBackend   (id="local", forkpty + /system/bin/sh)
                    └── LinuxPRootBackend   (id="linux-ubuntu", rootfs + /bin/bash)
```

Every box above is wired at startup by `app/di/ToolModule.kt`. A tool class
that exists in `tools/v2` but is missing from that wiring is **dead code** —
which is exactly what the contract gate below exists to catch.

### Authoritative tool table

This table is the contract. `scripts/check_terminal_tools.sh` (runs in the
Quality Gate workflow) verifies three-way agreement on every push:

1. every id below is really implemented in `platform/terminal tools`;
2. every v2 class below is really registered in `app/di/ToolModule.kt`;
3. no implemented tool is missing here, and no ghost entry lingers here.

<!-- terminal-tool-surface begin (authoritative; verified by scripts/check_terminal_tools.sh) -->
| Tool ID | Class | Group | Purpose |
|---|---|---|---|
| `terminal.exec` | TerminalExecTool | One-shot | Structured single command: stdout/stderr split, real exit code, channel routing (Ubuntu sandbox when ready, else su > Shizuku > local-sh), cd memory, approval gate |
| `terminal.create` | TerminalCreateTool | Session | Open a PTY session (backend + workspace selectable) |
| `terminal.run` | TerminalRunTool | Session | Execute a command inside a session (job model) |
| `terminal.observe` | TerminalObserveTool | Session | Incremental output observation (cursor-based, SEMANTIC / EVENT / SCREEN / RAW modes, scrollback readable since T82) |
| `terminal.wait` | TerminalWaitTool | Session | Await a condition (exit / idle / prompt / text) |
| `terminal.write` | TerminalWriteTool | Session | Send input (RAW / LINE / PASTE — bracketed paste T82) |
| `terminal.signal` | TerminalSignalTool | Session | Signal with scope=ALL or JOB — Ctrl-C kills the foreground group, not the shell (T82 native `signalForegroundGroup`) |
| `terminal.resize` | TerminalResizeTool | Session | PTY+VT+screen resize (SIGWINCH) |
| `terminal.snapshot` | TerminalSnapshotTool | Session | Full session state snapshot |
| `terminal.close` | TerminalCloseTool | Session | Idempotent close, full cleanup |
| `terminal.backends` | TerminalBackendsTool | Discovery | Backend availability: READY / NEEDS_ROOTFS / FAILED |
| `terminal.diagnostics` | TerminalDiagnosticsTool | Discovery | T87 self-diagnosis: sessions / backends / exec probe over the same engine the Agent will use |
| `terminal.ubuntu.install` | TerminalUbuntuInstallTool | Ubuntu | Idempotent, resumable rootfs provisioning |
| `terminal.ubuntu.ensure` | TerminalUbuntuEnsureTool | Ubuntu | T82 one-shot lifecycle: install → bootstrap → capability aggregate |
| `terminal.ubuntu.status` | TerminalUbuntuStatusTool | Ubuntu | Read-only Ubuntu lifecycle snapshot |
| `terminal.linux.status` | TerminalLinuxStatusTool | Linux env | 6-dimension health snapshot + bootstrap state |
| `terminal.linux.bootstrap` | TerminalLinuxBootstrapTool | Linux env | rootfs → sources → network → apt-update → base-packages → READY |
| `terminal.linux.network` | TerminalLinuxNetworkTool | Linux env | DNS / HTTP / HTTPS / APT_REPOSITORY per-probe diagnosis |
| `terminal.linux.packages` | TerminalLinuxPackagesTool | Linux env | Structured apt API (update/install/remove/upgrade/search, real dpkg-query installed list, mirror switch, autoremove/clean) |
| `terminal.linux.capabilities` | TerminalLinuxCapabilitiesTool | Linux env | Honest capability probe + `ensure` action (probe → install missing → re-probe) |
| `terminal.linux.repair` | TerminalLinuxRepairTool | Linux env | Single-round automatic repair orchestration |
| `terminal.workspaces` | TerminalWorkspacesTool | Workspace | list / create / inspect / delete isolated workspaces |
| `terminal.workspace.environment` | TerminalWorkspaceEnvironmentTool | Workspace | Project-aware toolchain analyze (read-only) / ensure (batched apt install + honest re-verify) |
| `terminal.fs` | TerminalFsTool | Files | Structured guest file API (write-sandboxed, base64 binary-safe) |
| `terminal.bridge` | TerminalBridgeTool | Files | apexctl Android capability bridge (termux-api equivalent: clipboard / device info / battery …) |
| `terminal_exec` | LegacyExecTool | Legacy | @Deprecated compat alias — registered |
| `terminal_send` | LegacySendTool | Legacy | @Deprecated compat alias — registered |
| `terminal_read` | LegacyReadTool | Legacy | @Deprecated compat alias — registered |
| `terminal_list` | LegacyListTool | Legacy | @Deprecated compat alias — registered |
| `terminal_close` | LegacyCloseTool | Legacy | Superseded by `terminal.close` — intentionally NOT registered |
| `terminal_signal` | LegacySignalTool | Legacy | Superseded by `terminal.signal` — intentionally NOT registered |
<!-- terminal-tool-surface end -->

### Capability increments since P83 (T82 → T87)

- **T82 — Termux-baseline full capability**: real exit codes via OSC 633
  shell markers (no more heuristic exit=0); foreground-group signals
  (`signalForegroundGroup`, tcgetpgrp-only — one Ctrl-C no longer kills the
  shell); F1-F12 / DECCKM SS3 arrows / bracketed paste; scrollback becomes
  readable (`scrollbackLines` in SCREEN observe); ED 3 / DEC graphics /
  OSC 52 clipboard / LNM; `/proc` `/dev` `/sys` binds via
  `SystemBindProfile.STANDARD`; shared-storage bridge (guest `/sdcard`);
  `terminal.fs` + `terminal.bridge`; apt mirror registry
  (official / TUNA / USTC / Aliyun); locale + timezone + Android DNS +
  guest proxy injection; real `dpkg-query` installed list; one-shot
  toolchain ensure. P60 frozen SDK adapters landed (`api/TerminalSdk`).
- **T85**: parity & UX rework (waiting-input suppression fixed as
  "stash + silent replay", no frame loss).
- **T86**: mouse reporting, focus events, OSC 8 hyperlinks, width tables,
  full key matrix.
- **T87**: terminal experience overhaul from 7 real-user pain points —
  31 color schemes, Termux-style extra keys & history, exec failure-path
  regression tests (`SessionSpawnErrorTest`), structured spawn errors
  (no zombie sessions).

### Differentiation vs operit-class baselines

| Dimension | operit-class baseline | This project |
|---|---|---|
| One-shot execution | opaque run_command | `terminal.exec`: stdout/stderr split + real waitpid exit code + duration + channel routing |
| Session lifecycle | none | nine-tool session kit (create → … → close) with job model & crash recovery |
| Exit codes | heuristic (can lie) | OSC 633 marker protocol — honest per-command status |
| Error model | generic exceptions | `TerminalError` code enum + layered Linux error codes; Agent matches codes, not messages |
| Signal control | kill everything | `signal` scope=JOB — Ctrl-C stops the foreground job, shell survives |
| Output observation | single blob | four observe modes + opaque cursor increments + scrollback API |
| Linux environment | none | Ubuntu 24.04 rootfs, apt, isolated workspaces, mirrors, DNS/proxy/locale injection |
| Android bridge | none | `terminal.bridge` (termux-api equivalent) |
| Agent-callability guarantee | trust me | three-way contract gate in CI (implementation = registration = this table) |

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
