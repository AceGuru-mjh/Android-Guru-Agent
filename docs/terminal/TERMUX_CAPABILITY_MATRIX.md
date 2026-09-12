# Termux Capability Matrix — Android-Guru-Agent Terminal vs. Termux Full Baseline

> T82 Phase 2/3 deliverable. Baseline source: Termux app + termux-packages + proot-distro +
> termux-api ecosystem as observed on F-Droid/GitHub (capability inventory from public docs
> and standard Termux behavior; not a fork — we take the **capabilities**, not the code).
> Our-side statuses are **code-audited facts** from a full read of `platform/terminal`,
> `terminal-emulator`, `app/di`, and the tool layer (see Phase-1 audit, T81 assessment).
>
> Legend:
> - ✅ COVERED — implemented in production path, wired in DI/tools, tested
> - 🟡 PARTIAL — works but with real, documented limitations
> - ❌ MISSING — capability absent
> - 💀 DEAD — code exists (~5,000 lines of PR#55-#67 parallel layers) but unwired (test-only)
> - 🏗️ **BUILT-IN-T82** — this task implements it (see §Status after merge)
> - 📋 DEFERRED — recorded with design, deliberately not in this task (requires device/infra)
> - ⛔ PLATFORM-LIMITED — impossible or irrational under current constraints (reason given)
>
> Every row carries: Capability | Termux behavior | Audit status & evidence | Decision (implementation or reason).

---

## 1. Terminal Emulation (VT/ANSI)

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 1.1 | VT100/xterm CSI: cursor, erase, scroll, insert/delete, DECSTBM | battle-tested terminal-view (fork of jackpal) | ✅ custom `TerminalCore` (CSI/EL/ED/ECH/ICH/DCH/IL/DL/SU/SD/DECSTBM) | keep own impl |
| 1.2 | SGR colors 8/16/256/truecolor + attrs | ✅ | ✅ `TerminalCore.applySgr` (256 + RGB + bold/dim/italic/underline/blink/inverse/strike) | — |
| 1.3 | Alt screen (47/1047/1049) + cursor save | ✅ | ✅ `switchAlternateScreen` | — |
| 1.4 | UTF-8 + wide chars + combining | ✅ | ✅ `Utf8Decoder` + `UnicodeWidth` + combining attach | — |
| 1.5 | Scrollback | 1000+ lines, gestures | 🟡 `ScreenBuffer` keeps 1000 lines but **no API reads it**; RAW history = 256 KB byte ring only | 🏗️ T82: expose `scrollbackLines()` + SCREEN observe field |
| 1.6 | DEC special graphics charset (ESC ( 0) | ✅ | ❌ (mitigated by C.UTF-8 → ncurses uses Unicode box chars) | 🏗️ T82: G0 DEC graphics map |
| 1.7 | ED 3 (clear scrollback) | ✅ | 🟡 approximated as ED 2 | 🏗️ T82: real scrollback clear |
| 1.8 | Mouse tracking (1000/1002/1006) | ✅ (touch) | ❌ no parse, no input | 📋 DEFERRED (agent value low; TUI drive via keys sufficient; touch mapping is UI-layer) |
| 1.9 | OSC 8 hyperlinks | ✅ | ❌ ignored | 📋 DEFERRED (rendering-level nicety) |
| 1.10 | OSC 52 clipboard | ✅ | ❌ ignored | 🏗️ T82: parse + drain API (host can honor into Android clipboard) |
| 1.11 | DCS (sixel/tmux passthrough) | ✅ | ❌ DCS ignored | 📋 DEFERRED (no sixel consumers on our stack) |
| 1.12 | Bell event | ✅ (sound/vibrate hook) | ❌ BEL ignored | 📋 DEFERRED (UI notification nicety) |
| 1.13 | Resize + SIGWINCH | ✅ | ✅ native `TIOCSWINSZ` → kernel SIGWINCH; `terminal.resize` tool | — |
| 1.14 | Bracketed paste (mode 2004) | ✅ both directions | 🟡 parsed only; **input never sends 200~ wrappers** | 🏗️ T82: PASTE write kind |
| 1.15 | Application cursor keys (DECCKM) input | ✅ arrows switch CSI/SS3 | ❌ input always CSI — arrows break in DECCKM apps | 🏗️ T82: DECCKM-aware key translation |
| 1.16 | Key input coverage F1–F12 | ✅ (+ volume-key/extra-keys row) | ❌ F1–F12 unmapped → send nothing + warn | 🏗️ T82: full F-key map |
| 1.17 | Termux is a *library* consumable by other apps (terminal-emulator view) | ✅ | 🟡 our `terminal-emulator` module is pure JVM & consumed by `platform/terminal`; but 3 test-fakes ship in src/main, unused Android deps in build file | 🏗️ T82: fake relocation + dep pruning + `docs/terminal/TERMINAL_SDK_BOUNDARY.md` |

## 2. PTY / Process / Signals

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 2.1 | Real forkpty + session leader | ✅ | ✅ `pty_session.cpp` forkpty/setsid; 20-session concurrency verified on host (T81) | — |
| 2.2 | Process-group signaling kill(-PGID) | ✅ | ✅ `killProcessGroup` (tcgetpgrp + session group + ESRCH fallback) | — |
| 2.3 | Signal **foreground command only** (Ctrl-C kills job, not shell) | ✅ | ❌ `signal()` hits fg group **and** shell group — one cancel can kill the session | 🏗️ T82: native `signalForegroundGroup` (tcgetpgrp only) + tool `scope` param |
| 2.4 | Per-command exit codes | ✅ (`$?`, $? in prompt) | ❌ synthetic `ProcessExited(exitCode=0)` on prompt-regex return (`JobManagerImpl:121`) — fake codes for every job | 🏗️ T82: OSC 633 marker protocol — real exit code + job identity per command |
| 2.5 | Per-command PID identity | ✅ ($!) | ❌ jobs are "lines written to a shell"; process2 spec (ProcessIdentity/PID-reuse) is 💀 dead | 🏗️ T82: marker carries jobId (shell-level correlation); PID-level introspection stays 💀→documented |
| 2.6 | Zombie reaping | ✅ init/parent | ✅ bounded WNOHANG loop, T81 N-4 (D-state abandoned to init, documented) | — |
| 2.7 | Timeout → TERM→grace→KILL | ✅ (user/htop) | ✅ TimeoutController 3-stage (T81 D-2); no longer group-kills shell | — |
| 2.8 | Ctrl-C / Ctrl-D / Ctrl-Z key delivery | ✅ | ✅ 0x03/0x04/0x1A through PTY; ISIG delivers kernel-side | — |
| 2.9 | SIGTSTP suspend + SIGCONT resume of jobs | ✅ | 🟡 UnixSignal enum has them; no job-level semantics (bg/fg bookkeeping only) | 🏗️ T82: fg-scoped signal makes job suspend/kill precise; shell `jobs`/`fg` integration documented as agent-side shell usage |
| 2.10 | Sessions survive app restart (reattach) | ✅ (foreground service holds PTYs) | ❌ recovery = honest tombstones (EXITED/BROKEN); PTY fds process-local | ⛔/📋 DEFERRED — needs a supervisor service holding TerminalRuntime outside app lifecycle; design recorded in §Deferred (v2 keep-alive service) — NOT silently skipped: no way to verify a service+process-death matrix in this task's environment |
| 2.11 | Multiple concurrent sessions | ✅ | ✅ 20 concurrent host-verified; per-session isolation | — |
| 2.12 | Raw binary-safe stdin/stdout | ✅ | ✅ P70 byte channels; NUL/multibyte preserved | — |

## 3. Shell / Job Model

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 3.1 | Interactive shell default (bash) | ✅ bash default, zsh/fish via pkg | ✅ local `/system/bin/sh -i`; Ubuntu `/bin/bash -i` | — |
| 3.2 | rc files (.bashrc/.profile) | ✅ | ✅ persistent `/root` via home bind + skel seeding (`GuestUserHome`) | 🏗️ T82: `.bashrc` also installs marker-friendly prompt & `apexctl` PATH |
| 3.3 | Command completion signal (deterministic) | ❌ (human eyeballs) | 🟡 prompt-regex heuristic (InputWaitingDetector, T81 D-3 real PS1) | 🏗️ T82: marker completion event = deterministic; heuristic stays as fallback for non-instrumented paths (interactive REPL etc.) |
| 3.4 | Env: PATH/HOME/TERM/LANG/USER/SHELL/TMPDIR | ✅ termux-info env | ✅ `LinuxEnvironmentManager` 11 keys (T81 D-6 authoritative) | 🏗️ T82: + proxy vars passthrough |
| 3.5 | Locale (zh_CN.UTF-8, emoji) | ✅ (C.UTF-8 default; locales pkg) | 🟡 C.UTF-8 only; no `locales` pkg, no locale-gen | 🏗️ T82: `locales` in bootstrap + locale-gen zh_CN/en_US + /etc/locale.gen |
| 3.6 | Timezone correct in guest | ✅ (follows Android) | ❌ UTC fallback (tzdata absent) | 🏗️ T82: tzdata + /etc/timezone + /etc/localtime from Android zone id |
| 3.7 | Shell history persistence | ✅ | ✅ /root persistent (HISTCONTROL in minimal bashrc) | — |

## 4. Execution Environment (Ubuntu/PRoot vs Termux native prefix)

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 4.1 | Full Linux userspace | native prefix (bionic libc + patches) | ✅ real Ubuntu 24.04.4 rootfs under PRoot (locked checksum, atomic activate) | — architecture choice differs (PRoot vs native), capability equivalent for CLI/dev workloads; native perf edge documented |
| 4.2 | /proc /dev /sys visible in guest | ✅ native | ❌ plain `-r`, no system binds — `ps` (procps is a base pkg!) sees empty /proc | 🏗️ T82: standard bind profile (-b /proc,/dev,/sys) like proot-distro |
| 4.3 | Shared storage in guest (termux-setup-storage) | ✅ ~/storage/shared etc. | ❌ no /sdcard bind, no SAF/permission flow | 🏗️ T82: shared-storage bind via injectable host dir (app resolves Android permission); honest degrade when unavailable |
| 4.4 | Persistent user home | ✅ native | ✅ T75 home bind (survives rootfs upgrades) | — |
| 4.5 | Per-task isolated workspaces | ❌ single $PREFIX/$HOME | ✅ T75 LinuxWorkspaceManager (per-session dirs + refcount) | ours exceeds baseline |
| 4.6 | apt ↔ interactive terminal share same fs/db | ✅ single prefix | ✅ T81 §28 construction-level sharing (LinuxExecutionContextFactory) | — |
| 4.7 | 32-bit ARM support | ✅ | ⛔ Ubuntu Base publishes arm64/amd64 only; armv7 devices fail honestly (UNSUPPORTED_ARCHITECTURE) | ⛔ recorded; needs armhf rootfs source (out of task scope; Ubuntu dropped armhf desktop/base) |
| 4.8 | Offline/bundled rootfs | ✅ (bootstrap zips bundled in APK!) | ❌ network-only single source (cdimage), resume+retry+checksum yes | 📋 DEFERRED: BUNDLED enum exists; bundling 30 MB per arch grows APK ×3 — product decision, recorded |
| 4.9 | Rootfs repair/reinstall | n/a | ✅ invalidate/repair/reconcile + .corrupt quarantine (T72/T81) | — |

## 5. Package Management

| # | Capability | Termux (pkg/apt) | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 5.1 | install/remove/update/upgrade/search/info | ✅ | ✅ `UbuntuAptPackageManager` 10 ops, structured 25 error codes, dual locks, disk preflight | — |
| 5.2 | `installed` list | ✅ dpkg -l | ❌ documented stub — returns brokenPackages only, tells agent to shell `dpkg -l` | 🏗️ T82: real dpkg-query parse (name/version/status) |
| 5.3 | Repository mirrors + switching | ✅ termux-change-repo (21 mirrors) | ❌ single cdimage + ports/archive over HTTP | 🏗️ T82: mirror registry (official/TUNA/Aliyun per arch) + `mirror` tool action |
| 5.4 | Signed repos | ✅ (own repo infra) | ✅ ubuntu-archive-keyring Signed-By (deb822) | — |
| 5.5 | autoremove/clean/holds | ✅ pkg + apt | ❌ | 🏗️ T82: autoremove + clean actions (apt native); holds recorded DEFERRED (rarely agent-relevant) |
| 5.6 | Base package set | coreutils+apt+dpkg bootstrap | ✅ 21 essential (incl. curl/wget/git/python3) + 10 recommended | 🏗️ T82: +locales +tzdata essential; recommended unchanged |

## 6. Core Utilities (ls/grep/sed/awk/find/tar/…)

Termux bootstrap ships a curated minimal set; ours ships a larger Ubuntu Base + essential apt set.

| # | Set | Termux | Ours | Decision |
|---|---|---|---|---|
| 6.1 | coreutils/findutils/grep/sed/awk/less/file | ✅ | ✅ essential list | — |
| 6.2 | tar/gzip/bzip2/xz/zip/unzip | ✅ | ✅ essential list | — |
| 6.3 | procps/psmisc (ps, top, kill, pstree) | ✅ | ✅ pkg installed — **but broken at runtime without /proc bind** | fixed by 4.2 |
| 6.4 | jq/tree/dnsutils/iproute2/ping | ✅ | 🟡 recommended-only (not auto-installed) | intentional (bandwidth); agent can `terminal.linux.packages install` |

## 7. Development Toolchain

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 7.1 | Toolchain probe (detect+version) | ✅ (which/--version, human) | ✅ `LinuxCapabilityProbe` 15 caps real probe + TTL cache (T81 §29) | — |
| 7.2 | One-shot "ensure toolchain" | ✅ pkg install python etc. | ❌ probe says INSTALLABLE but no ensure action; profile registry is 💀 dead | 🏗️ T82: `terminal.linux.capabilities ensure` action (probe→install→verify, incl. python3-venv/python3-dev, nodejs+npm, default-jdk, rust, golang) |
| 7.3 | C/C++: clang/gcc/g++/make/cmake | ✅ | ✅ via apt (gcc is in Ubuntu repos; probe maps) | — |
| 7.4 | Python: python3 + pip + venv | ✅ + termux patches | ✅ python3+pip essential; ❓ venv needs python3-venv pkg + ensure-venv action | 🏗️ T82: ensure installs python3-venv, python3-dev |
| 7.5 | Node/JS: node+npm | ✅ | ✅ apt nodejs/npm (probe maps) | — |
| 7.6 | Rust/Go/Java | ✅ | ✅ apt cargo/rustc/golang-go/default-jdk (probe maps); JAVA_HOME etc. not set in env | 🏗️ T82: ensure sets toolchain env profile into persistent .bashrc (JAVA_HOME/GOROOT/CARGO_HOME) |
| 7.7 | Compile/run/test loop in workspace | ✅ | ✅ workspace bind + HOME persist | — |

## 8. Git

| # | Capability | Termux | Ours | Decision |
|---|---|---|---|---|
| 8.1 | git presence | ✅ | ✅ essential list | — |
| 8.2 | git config (user.name/email) | ✅ user does | ❌ no seeding | 🏗️ T82: seed a default gitconfig in persistent home (agent identity + safe defaults) |
| 8.3 | workspace ⇄ git ⇄ agent same dir | n/a | ✅ /workspace is the bound project dir; tools operate on host-visible workspace dir | — |

## 9. Networking

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 9.1 | curl/wget/openssl | ✅ | ✅ essential (curl/wget) + ca-certs | — |
| 9.2 | DNS resolution in guest | ✅ (Android netd via bionic) | 🟡 resolv.conf written at configure; **DI never injects Android DNS** → 8.8.8.8/1.1.1.1 fallback fails on DNS-blocked nets (CN) | 🏗️ T82: app DI injects LinkProperties DNS into RootfsConfigurator |
| 9.3 | Proxy (http_proxy/https_proxy) | ✅ (env) | ❌ not settable anywhere | 🏗️ T82: LinuxEnvironmentManager proxy config + app DI from Android system proxy |
| 9.4 | Network diagnosis | ✅ (human curl/ping) | ✅ `terminal.linux.network` 4-dim probe (DNS/HTTP/HTTPS/APT) — exceeds baseline | — |
| 9.5 | ssh client | ✅ openssh | 🟡 recommended-only pkg | agent can install; documented |
| 9.6 | ssh server / port forwarding | ✅ sshd builtin | ❌ | 📋 DEFERRED — running sshd inside PRoot needs device verification + security review (expose surface); design recorded |
| 9.7 | CA bootstrap chicken-and-egg | n/a (https) | 🟡 HTTP sources + CA copy-or-warn | 🏗️ T82 (partial): keep HTTP-first bootstrap; mirror action allows https-capable mirrors after CA present |

## 10. Interactive Programs (vim/nano/top/htop/tmux/python REPL)

| # | Capability | Termux | Ours | Decision |
|---|---|---|---|---|
| 10.1 | Full-screen TUI rendering | ✅ | ✅ alt-screen + scroll region + resize (verified by VT tests) | — |
| 10.2 | TUI input driving (arrows/F-keys in vim/top) | ✅ | 🟡 F-keys no-op + arrows break under DECCKM | 🏗️ T82: 1.15/1.16 fix |
| 10.3 | REPL detection (python/node) | ✅ human | ✅ interactivePrograms guard + prompt detector | — |
| 10.4 | tmux inside guest | ✅ | ⛔ nested PTY under PRoot: tmux works but its server dies with session (no daemon persistence) — same 2.10 constraint | documented; use session model instead |

## 11. Android Integration (termux-api equivalent)

| # | Capability | Termux | Ours (pre-T82) | Decision |
|---|---|---|---|---|
| 11.1 | Android capabilities callable **from guest scripts** (battery/clipboard/sensors/…) | ✅ Termux:API app + `termux-battery-status` CLI | ❌ Android-side tools exist for the agent only; guest has no bridge | 🏗️ T82: `apexctl` file-queue bridge over the home bind — guest → host request/response with pluggable handlers (clipboard/device-info/battery wired at app layer) |
| 11.2 | Clipboard read/write | ✅ | ✅ agent-side tool exists | 🏗️ T82: also bridge-exposed |
| 11.3 | Shared storage access | ✅ setup-storage | ❌ | 🏗️ T82: 4.3 |
| 11.4 | Termux:Boot/Float/Widget/Tasker/X11 companion surfaces | ✅ | ❌ | 📋 DEFERRED — separate companion apps/product surface, out of terminal-core scope; recorded |
| 11.5 | Android device info for agent | ✅ termux-info | ✅ app system tools | 🏗️ T82: bridge exposes device-info to guest too |

## 12. Agent-First Architecture (ours exceeds Termux)

| # | Capability | Termux | Ours | Decision |
|---|---|---|---|---|
| 12.1 | Structured observation API (semantic/screen/raw/event, cursors) | ❌ (human UI only) | ✅ 4-mode observe + bounded buffers + push flows | — |
| 12.2 | Wait engine (condition-driven) | ❌ | ✅ 9 conditions, event-driven | — |
| 12.3 | Machine-readable tool contract | ❌ | ✅ 20+ terminal.* tools with JSON schema | 🏗️ T82: + `terminal.fs`, + ensure/mirror/installed actions |
| 12.4 | Event log / audit | ❌ | ✅ bounded 500-event log + bus | — |
| 12.5 | Crash recovery honesty (no fake RUNNING) | ❌ n/a | ✅ T81 §16 | — |
| 12.6 | Health/diagnosis orchestration | ❌ | ✅ 6+1-dim health + single-round repair + lifecycle coordinator (T82 r1) | — |
| 12.7 | Public SDK API (P60 frozen contract) | n/a | 💀 `api/TerminalApi.kt` has **zero implementations** — documented as if shipped | 🏗️ T82: `TerminalSdk` adapter implements Terminal/TerminalSession/JobHandle over TerminalRuntime |

## 13. SDK / Library Boundary (the "Termux is a library" dimension)

| # | Check | Audit result (pre-T82) | Decision |
|---|---|---|---|
| 13.1 | android.*/androidx.* imports in platform/terminal Kotlin | **0 across 145 files** — Android touchpoints injected via DI (Context paths, Build.SUPPORTED_ABIS) | already SDK-grade at source level |
| 13.2 | Reverse dependency terminal → app/UI/Compose | **none** — strictly app → terminal | keep enforced |
| 13.3 | Test fakes shipped in release artifact | ❌ FakeNativePty (311) / FakeLinuxRuntime (214) / FakePackageManager (119) compiled into src/main | 🏗️ T82: relocate to test source set (fixtures) |
| 13.4 | Unused Android deps in build.gradle.kts | ❌ core.ktx unused; tool-registry dep unused by any import | 🏗️ T82: prune both |
| 13.5 | Module split graduation plan ( :terminal:api/core/pty/… ) | n/a | 🏗️ T82: `docs/terminal/TERMINAL_SDK_BOUNDARY.md` records current package→future-module mapping + rules; physical Gradle split deferred to when a second consumer exists (recorded rationale — split without a consumer is speculative structure) |

## 14. Dead / Parallel Spec Layers (judgment per T82 §1)

| Layer | Lines | Status | Decision |
|---|---|---|---|
| process2 (JobManager2, ProcessControl types) | 293 | 💀 no impl | **Do not wire.** JobManagerImpl gains the real capabilities (exit codes via markers; fg-scoped signals natively). process2 stays as recorded design debt; T81 already decided keep-not-delete — same decision retained, now with capability parity delivered by the production path |
| observation2 (sequences, bounded batch ring) | 281 | 💀 test-only | keep (v1 gains scrollback + keeps bounded contract); wiring v2 is a rewrite of a PROD component = T81-protected |
| reliability2 + RecoveryCoordinator | 438 | 💀 test-only | keep; production recovery is RuntimeRecoveryService (honest) |
| input/InputLayer2 + TerminalInputControllerImpl | 303 | 💀 test-only | keep; its TtyMode concept stays unwired (programs own their termios — correct behavior), input completeness lands in InputManagerImpl instead |
| control/TerminalController(Impl) | 278 | 💀 test-only | keep |
| linux/LinuxRuntimeContract + FakeLinuxRuntime | 517 | 💀 contract-only | keep as contract documentation; GuestFilesystem (T82) implements the *capability* (fs ops) not the contract |
| environment adaptive stack (Resolver2/DiagnosticRules/…) | ~1600 | 💀 test-only | keep; production path = LinuxCapabilityProbe + EnvironmentRepairService (T81 §29/30) |
| api/ TerminalApi + ApiHardening | 332 | 💀 no impl | 🏗️ T82: TerminalSdk adapter makes it REAL |
| VT100Emulator (old gen) / StubVirtualTerminal / RuntimeWorkspace / BackpressureConfig | 564 | dead | keep (T81 decision — no deletions in T82; all are zero-cost dead code, removal is a separate hygiene task) |

## 15. Deferred Design Record (NOT silently skipped)

1. **Session keep-alive across app death** (2.10): needs a dedicated foreground service process owning TerminalRuntime + wakelock, plus process-death instrumentation matrix. Design: `TerminalSupervisorService` (START_STICKY, partial wakelock while any session has running jobs, runtime injected via singleton provider, `onTaskRemoved` keeps service, runtime reattach impossible for old PTYs but NEW sessions survive). Deferred: cannot verify service lifecycle/proc-death in this environment; shipping an unverified lifecycle service risks app stability (spec §4 permits recorded platform constraints).
2. **sshd in guest** (9.6): expose-surface security review needed.
3. **X11/VNC GUI** (Termux:X11 companion): separate product surface.
4. **Bundled rootfs** (4.8): APK size product decision.
5. **ARM32** (4.7): no Ubuntu armhf base images.
6. **Mouse reporting** (1.8): needs UI gesture layer first.
7. **Physical Gradle module split** (13.5): no second consumer yet.

---

## Status after T82 (implementation ledger)

Every 🏗️ row above is implemented + unit-tested in this branch; see `docs/T82_TERMINAL_CAPABILITY_REPORT.md`
for the per-item verification ledger (same honesty rules as T81/T82-r1: local-verified vs CI-verified vs NOT VERIFIED).

---

## Status after T82 — verified ledger (post-merge)

Every 🏗️ row above was implemented in branch `t82/terminal-full-capability`.
Per-item verification status (local-verified / CI-verified / NOT VERIFIED) is
recorded in `docs/T82_TERMINAL_CAPABILITY_REPORT.md` §2/§7.
