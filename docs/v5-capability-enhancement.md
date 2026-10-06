# v5 Capability Enhancement — Agent Self-Setup, Download+Install, More Capabilities

> **Status**: Implemented (Wave 1)
> **Author**: Agent capability enhancement task
> **Date**: 2026-10-06

## 1. Motivation

The user's original ask:

> 全面增强 agent 能力，能让 agent 自行帮你完成设置，开启自己软件任何设置以及更多能力，
> 然后增加 download 工具，可以利用网页自动化安装应用，然后还要有更多能力

Three concrete gaps:

1. **Agent cannot configure itself.** All runtime settings (mode, thinking level, retry policy, context window, theme, language, model profiles, role bindings, custom mode presets) are user-only via the Settings UI. The agent has no tool to read or modify them — every onboarding follow-up "let me lower max_iterations" requires the user to leave the chat and dig into Settings.

2. **App install requires Shizuku/root.** The existing `app_install` tool wraps `pm install -r <path>`, which needs a privileged shell (`uid=2000` or root). On stock devices this always fails. There is no combined "download APK from URL + install" one-shot either — the user (and the LLM) must chain `download_file` → `app_install` via `tool_batch_run`, and even then the install step breaks on unprivileged devices.

3. **Capability surface is incomplete.** The agent can't read contacts, send SMS, list calendar events, set alarms, pick files via SAF, capture photos, or post system notifications — all common "smartphone agent" primitives. Some `#172` device tools (`torch`, `vibrate`, `battery_status`, `network_info`, `tts_speak`, `share_content`, `deep_link`) landed but the obvious gaps remained.

## 2. Design

### 2.1 Architecture constraints

- **`core/tool-registry` is pure JVM** — no Android imports in tool files.
  All Android-specific work goes through injected lambdas (`suspend (T) -> R`).
- **`BaseTool` v2** is the canonical base class (DSL schema, structured `ToolResult`, crash containment). All new tools extend it.
- **`SafeAgentTool(...)` wraps every registration** so no tool throws to the executor.
- **Field-level whitelist** for agent self-setup: only `AgentSettingsPatch`-declared fields can be modified; security-sensitive fields (`permissionMode`, `permissionRules`, `mcpScopeIsolation`) are never exposed.

### 2.2 New tools (18 total)

**Group A — Agent Self-Setup (9 tools, `ToolCategory.AGENT`)**

| Tool | Risk | Description |
|---|---|---|
| `agent_setting_get` | LOW (readOnly) | Read full `AgentSettingsSnapshot` JSON |
| `agent_setting_set` | MEDIUM (idempotentWrite) | Incremental patch (only non-null fields) |
| `agent_profile_list` | LOW (readOnly) | List model profile summaries (no API keys) |
| `agent_profile_set_default` | MEDIUM (idempotentWrite) | Switch default profile (next LLM call uses it) |
| `agent_provider_set_key` | HIGH (sensitiveAction) | Write API key to encrypted store |
| `agent_role_list` | LOW (readOnly) | Read role bindings (primary/vision/reasoning/fast/summary) |
| `agent_role_activate` | MEDIUM (idempotentWrite) | Bind profile to a role |
| `agent_mode_preset_list` | LOW (readOnly) | List built-in + user presets |
| `agent_mode_preset_select` | MEDIUM (idempotentWrite) | Select active CUSTOM mode preset |

`agent_setting_get` is added to `CORE_TOOL_IDS` so the LLM sees its own config on every turn (no `tool_search` round-trip needed).

**Group B — Download + Install APK (2 tools, `ToolCategory.APP`)**

| Tool | Risk | Description |
|---|---|---|
| `app_install_apk` | HIGH (destructive) | Install local APK via Android `PackageInstaller` session API (no Shizuku/root) |
| `app_download_install` | HIGH (openWorld+destructive) | One-shot: URL → download → optional hash verify → install |

Both use Android `PackageInstaller.Session` API — only the `REQUEST_INSTALL_PACKAGES` normal permission (already declared). The system install confirmation dialog always shows; the user must tap "Install". No silent install risk.

`app_download_install` validates:
- URL must be `http(s)`
- Filename must be a single-segment `.apk` (no path traversal)
- MIME type: refuses `text/*` responses (likely HTML error pages)
- Size cap: 500 MB (Content-Length pre-check + streaming byte counter)
- Optional `sha256` / `md5` hash verification; mismatch deletes the downloaded file

**Group C — More Capabilities (7 tools)**

| Tool | Category | Risk | Permission | Description |
|---|---|---|---|---|
| `contact_search` | SYSTEM | LOW (sensitiveRead) | `READ_CONTACTS` | Fuzzy search by name/phone/email |
| `sms_send` | SYSTEM | HIGH (sensitiveAction, openWorld) | `SEND_SMS` | Send SMS (multi-segment for long) |
| `calendar_event_list` | SYSTEM | LOW (sensitiveRead) | `READ_CALENDAR` | List events from now to `+days_ahead` |
| `alarm_set` | SYSTEM | MEDIUM (mutating) | `com.android.alarm.permission.SET_ALARM` | Launch AlarmClock intent |
| `file_pick` | FILE | MEDIUM (mutating) | (none, SAF) | Open SAF picker (fire-and-forget) |
| `image_capture` | SENSOR | MEDIUM (sensitiveAction) | `CAMERA` | Launch camera intent |
| `notification_post` | SYSTEM | MEDIUM (idempotentWrite) | `POST_NOTIFICATIONS` | Post silent notification |

### 2.3 File map

**Pure JVM (core/tool-registry module)**:

- `src/main/kotlin/.../tools/builtin/AgentSetupTools.kt` — 9 self-setup tools + `AgentSettingsSnapshot` / `AgentSettingsPatch` / `AgentSettingsHost`
- `src/main/kotlin/.../tools/builtin/AppDownloadInstallTool.kt` — 2 download/install tools + `ApkInstaller` interface + `ApkInstallResult`
- `src/main/kotlin/.../tools/builtin/MoreCapabilitiesTools.kt` — 7 capability tools + data classes
- `src/main/kotlin/.../tools/catalog/ToolTierPolicy.kt` — `agent_setting_get` added to `CORE_TOOL_IDS`
- `src/main/kotlin/.../tools/ToolMetadata.kt` — `inferCategory` / `inferRisk` extended for new id prefixes
- `src/main/kotlin/.../tools/ToolAnnotations.kt` — `infer` extended for new id prefixes
- `src/test/kotlin/.../tools/builtin/AgentSetupToolsTest.kt` — 19 tests
- `src/test/kotlin/.../tools/builtin/AppDownloadInstallToolTest.kt` — 12 tests (MockWebServer)
- `src/test/kotlin/.../tools/builtin/MoreCapabilitiesToolsTest.kt` — 24 tests
- `build.gradle.kts` — added `testImplementation(libs.mockwebserver)`

**Android-side (app module)**:

- `src/main/kotlin/.../tools/ApkInstaller.kt` — `AndroidApkInstaller` (PackageInstaller session) + `ApkInstallResultReceiver` (broadcast)
- `src/main/kotlin/.../tools/AgentSetupHost.kt` — `AgentSetupHost` (SettingsRepository bridge) + extension functions
- `src/main/kotlin/.../tools/MoreCapabilitiesHost.kt` — `ContactsReader`, `SmsSender`, `CalendarReader`, `AlarmSetter`, `FilePicker`, `CameraCapturer`, `NotificationPoster`
- `src/main/kotlin/.../di/ToolModule.kt` — registers all 18 new tools (sections §14e / §14f / §14g)
- `src/main/AndroidManifest.xml` — declares 5 new permissions + `ApkInstallResultReceiver` receiver

### 2.4 Security model

| Concern | Mitigation |
|---|---|
| LLM raises its own privilege (e.g. sets `permissionMode=BYPASS`) | `AgentSettingsPatch` only exposes safe fields; security-sensitive fields are NOT in the patch |
| LLM installs malicious APKs | HIGH risk → session-level user confirmation; system install dialog always shown (no silent install) |
| LLM leaks stored API keys | `agent_provider_set_key` is write-only — never returns key content; result only says "stored, length N". `SecretRedactingExecutor` additionally redacts outputs |
| LLM sends SMS spam | HIGH risk → session-level user confirmation; phone number regex `^\+?\d{7,15}$` blocks malformed inputs |
| LLM abuses file picker / camera | MEDIUM risk → user confirmation; both fire-and-forget via Intent (user must explicitly pick file / take photo) |
| LLM posts noisy notifications | MEDIUM risk → silent channel by default; user can cancel via notification id/tag |

### 2.5 Backwards compatibility

- All existing tools continue to work unchanged.
- New tools land in CATALOG (discoverable via `tool_search` / `tool_open`) — except `agent_setting_get` which is CORE (always exposed).
- `app_install` (Shizuku/root path) remains registered — agents on rooted devices can still use it for silent installs.
- `download_file` remains the canonical file-download tool; `app_download_install` is specifically for APK install workflow.
- No existing test cases break — new tools are additive.

## 3. Verification

### 3.1 Unit tests (55 new tests)

```
core/tool-registry/src/test/kotlin/com/apex/agent/core/tools/builtin/
├── AgentSetupToolsTest.kt       (19 tests — snapshot read, patch validation, enum enforcement)
├── AppDownloadInstallToolTest.kt (12 tests — path safety, hash mismatch, MIME check, MockWebServer)
└── MoreCapabilitiesToolsTest.kt (24 tests — phone regex, hour/minute bounds, days_of_week validation)
```

Run with:
```bash
./gradlew :core:tool-registry:test
```

### 3.2 Integration verification (manual, on-device)

1. Build & install the APK on a stock (non-rooted) device.
2. In chat, ask: *"What are your current settings?"*
   → Expect agent to call `agent_setting_get` and report mode, max_iterations, etc.
3. Ask: *"Lower your max_iterations to 5."*
   → Expect `agent_setting_set` call; risk gate prompts user once; on accept, settings persist (visible in Settings UI).
4. Ask: *"Install the F-Droid app from https://f-droid.org/FDroid.apk"*
   → Expect `app_download_install` to download, then system install dialog appears.
5. Ask: *"Set an alarm for 7am tomorrow."*
   → Expect `alarm_set` to launch the system clock app with prefilled fields.
6. Ask: *"Search contacts for John."*
   → Expect `contact_search` to require READ_CONTACTS permission (Android permission flow triggers on first call).

### 3.3 Build verification

```bash
# Pure JVM compile check (no Android SDK needed)
./scripts/compile_core_jvm.sh

# Full Android build (requires Android SDK)
./gradlew assembleDebug
```

## 4. Roadmap (future enhancements)

**Wave 2 candidates** (not yet implemented):
- `clipboard_history` — read clipboard history (needs `ClipboardOnPrimaryClipChangedListener` + persistence)
- `wifi_state` / `bluetooth_state` — read toggles (write is restricted on Android 10+)
- `screen_record_start` / `screen_record_stop` — needs MediaProjection (foreground service + user grant)
- `audio_record` — needs MediaRecorder + RECORD_AUDIO permission
- `app_grant_permission` / `app_revoke_permission` — needs root or `pm grant` via Shizuku
- `agent_role_create` / `agent_role_delete` — full role CRUD (currently only activate is exposed)
- `notification_cancel` — companion to `notification_post`
- `file_pick` and `image_capture` currently use fire-and-forget Intent pattern; full Activity Result API integration would let the agent receive the picked URI / captured photo path as a tool result.

## 5. Decision log

| Decision | Rationale |
|---|---|
| Use `PackageInstaller` session API instead of `pm install` | No Shizuku/root needed; works on stock devices; system install dialog always shown (no silent install risk) |
| `agent_setting_get` in CORE, others in CATALOG | LLM should always know its own config; write tools add ~5KB schema each, keep them off-CORE |
| Whitelist patch fields, exclude `permissionMode`/`permissionRules`/`mcpScopeIsolation` | LLM should not raise its own privilege — security boundary stays user-controlled |
| `sms_send` HIGH risk + sensitiveAction + openWorldHint | Real cost (SMS fees) + third-party communication + retry-not-safe |
| Fire-and-forget for `file_pick` / `image_capture` | Activity Result API integration requires Activity registration; deferred to Wave 2 |
| Don't expose `READ_SMS` / `RECEIVE_SMS` | Privacy: reading SMS is much more sensitive than sending; agent has no need to read SMS for the user's stated use cases |
| `notification_post` defaults to silent channel | Avoids "agent spammed notifications" complaints; user can pair with `vibrate` / `tts_speak` for audible alerts |
