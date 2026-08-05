# Apex Agent

> Android-side autonomous AI agent with on-device LLM, shell tool execution, Plan/Build dual-mode engine, and a plugin SDK.

Apex Agent is a modular Android application that brings an autonomous agent runtime to the device. The current build target is `./gradlew :app:assembleDebug` green. It integrates:

- An OpenAI-compatible LLM adapter (configurable at runtime via the in-app Settings screen) with true SSE streaming
- A tool registry with a built-in `shell_execute` tool backed by `PrivilegeDetector` (auto-selects Root → Shizuku → normal shell)
- A Plan/Build dual-mode agent engine with 5-level thinking depth and streaming event output
- A privilege manager (Shizuku + Accessibility)
- A persistence engine with WorkManager watchdog
- A plugin SDK (AIDL + plugin-host) with a sample `plugin-workflow` APK

## Architecture

```
apex-agent/
├── app/                            # Application entry, Compose UI (drawer nav), Hilt DI
│   └── ui/
│       ├── ApexRoot.kt             # ModalNavigationDrawer + DrawerDestination
│       ├── ApexDrawerContent.kt    # Drawer header/nav items/status footer
│       └── screen/
│           ├── agent/              # AgentChatScreen + AgentChatViewModel (+ slash command router)
│           ├── skill/              # SkillScreen + SkillViewModel (list/enable/disable)
│           ├── memory/             # MemoryScreen (FileMemoryStore browser)
│           ├── model/              # ModelScreen (LLM config + presets)
│           ├── permissions/        # PermissionsScreen (Root/Shizuku/Accessibility/overlay/notify/storage)
│           └── settings/           # SettingsScreen (general) + SettingsViewModel
├── core/
│   ├── agent-engine/               # Pure-JVM agent loop (Plan/Build, streaming, P7 compression)
│   ├── tool-registry/              # Pure-JVM tool registry + 40 tools + SkillRegistry
│   └── llm-adapter/                # Pure-JVM OpenAI-compatible LLM client (streaming)
├── platform/
│   ├── privilege/                  # Shizuku + Accessibility + PrivilegeDetector
│   └── persistence/                 # Foreground service + WorkManager watchdog
├── plugin-sdk/
│   ├── plugin-api/                 # AIDL IApexPlugin + PluginContract
│   └── plugin-host/               # Plugin discovery/binding
└── plugins/
    └── plugin-workflow/            # Reference plugin APK
```

> Removed in this iteration: `build-logic/`, `core/memory/`, `platform/linux-runtime/`, `platform/workspace/`, `plugins/plugin-automation/`, old bottom-nav `ApexNavHost` + `chat/ChatScreen` + `chat/ChatViewModel` + `project/*` + `status/*` (superseded by the new drawer-based UI). They will be re-introduced as separate PRs once their consumers are ready.

## Tech Stack

- **Kotlin** 2.0.21 / **AGP** 8.7.3 / **compileSdk** 35 / **minSdk** 26
- **Jetpack Compose** (BOM 2024.12.01) + Material 3, via the new `org.jetbrains.kotlin.plugin.compose` plugin
- **Hilt** for dependency injection (with KSP)
- **Coroutines** for async work
- **WorkManager** + Hilt-Work for background jobs
- **Shizuku** for elevated operations without root
- **Accessibility Service** for UI automation
- **OkHttp** 4.12.0 for SSE-based LLM streaming
- **kotlinx.serialization** for JSON
- **Navigation Compose** for screen routing
- **SharedPreferences** for LLM config persistence (settings UI in-app)

## Build

```bash
chmod +x gradlew
./gradlew clean
./gradlew :app:assembleDebug --stacktrace
```

> Requires JDK 17 and Android SDK 35.

## Key Modules

### `core:agent-engine`
Pure-JVM agent loop with dual execution modes. Key types:
- `AgentEngine` — interface (`execute(input) -> Flow<AgentEvent>`, `abort()`)
- `ApexAgentEngine` — production implementation
  - **Build mode** (`AgentMode.BUILD`): streaming ReAct loop — Think → Act → Observe → repeat → Done
  - **Plan mode** (`AgentMode.PLAN`): Think → stream plan as `ThinkingChunk`s → parse JSON → emit `PlanGenerated` + `PlanAwaitingConfirmation` → suspend on `awaitPlanConfirmation()` (resumed by `submitPlanConfirmation()` from the UI) → execute each `PlanStep` via Build loop → emit final reflection
  - Plan-confirmation channel: a `CompletableDeferred<Boolean>` field, reset before each plan; 5-minute timeout; auto-cancel on `abort()`
  - Public API: `updateConfig(newConfig)`, `submitPlanConfirmation(confirmed)`, `clearHistory()`, `historyCount()`, `abort()`
  - **Conversation memory** (`ConversationMemory` interface): every message added to `conversationHistory` is also persisted via `memory.append()`. On engine construction, `memory.load()` rehydrates the full history so the agent remembers past turns across app restarts. `clearHistory()` wipes both in-memory and persisted state (used by the "新会话" button).
- `AgentConfig` — runtime-tunable config (`mode`, `thinkingLevel`, `maxIterations`, `maxContextTokens`, `streaming`, `temperature`, …) with presets (`QUICK`, `STANDARD`, `CAREFUL`)
- `AgentMode` — `PLAN` vs `BUILD`
- `ThinkingLevel` — `NONE` / `LIGHT` / `STANDARD` / `DEEP` / `MAXIMUM`; each maps to:
  - a system-prompt instruction injected by `buildSystemPrompt()` (e.g. STANDARD → `"Think step by step about the task. Analyze what needs to be done, choose the best tool, then execute."`)
  - a `thinking_budget` integer (0 / 256 / 1024 / 4096 / 16384) for models like Gemini that support it
  - `NONE` skips emitting `ThinkingStart` events in the Build loop
- `AgentEvent` — sealed event stream: `ThinkingStart/Chunk/Complete`, `PlanGenerated/AwaitingConfirmation/Confirmed`, `StepStart`, `ToolCallStart/OutputChunk/Complete`, `ResponseChunk/Complete`, `ContextCompressed`, `IterationStart`, `UserInputRequired`, `Error`, `Complete`, `Aborted`
- `ExecutionPlan` / `PlanStep` / `RiskLevel` — Plan-mode artifacts parsed from the LLM's JSON response (with `fallbackPlan()` for unparseable responses)

### `core:tool-registry`
Pure-JVM tool registry + executor + **35 built-in tools** (all `AgentTool` implementations, registered in `ToolModule`) across 9 categories:

| # | Tool ID | Source File | Purpose |
|---|---------|-------------|---------|
| 1 | `shell_execute` | `ShellExecuteTool.kt` | Run device shell commands (Root/Shizuku/normal) |
| 2 | `read_file` | `FileTools.kt` | Read text files with max_lines / offset_lines windowing |
| 3 | `write_file` | `FileTools.kt` | Write or append to files (creates parent dirs) |
| 4 | `list_files` | `FileTools.kt` | List directory contents with size + mtime |
| 5 | `delete_file` | `FileTools.kt` | Delete a file or empty directory |
| 6 | `search_files` | `FileToolsExtra.kt` | grep-like content search with regex + glob filter |
| 7 | `copy_move_file` | `FileToolsExtra.kt` | Copy or move file/directory (renameTo → copy+delete fallback) |
| 8 | `web_fetch` | `WebTools.kt` | Fetch URL, auto-extract readable text from HTML |
| 9 | `web_search` | `WebTools.kt` | DuckDuckGo HTML search (no API key needed) |
| 10 | `http_request` | `WebTools.kt` | Generic GET/POST/PUT/DELETE/PATCH with custom headers/body |
| 11 | `download_file` | `WebToolsExtra.kt` | Download a file from URL to device storage |
| 12 | `memorize` | `MemoryTools.kt` | Save info to long-term `FileMemoryStore` (by category) |
| 13 | `recall` | `MemoryTools.kt` | Search memories by keyword, key, or list_all |
| 14 | `forget` | `MemoryTools.kt` | Delete a memory by key or clear an entire category |
| 15 | `app_list` | `SystemTools.kt` | List user/system/all apps via `pm list packages` |
| 16 | `app_launch` | `SystemTools.kt` | Launch app by package (via `monkey` or `am start`) |
| 17 | `app_install` | `AppToolsExtra.kt` | Install APK via `pm install -r` |
| 18 | `app_uninstall` | `AppToolsExtra.kt` | Uninstall by package (`pm uninstall`, optional `-k` keep_data) |
| 19 | `app_force_stop` | `AppToolsExtra.kt` | `am force-stop` a running app |
| 20 | `app_info` | `AppToolsExtra.kt` | `dumpsys package` for version / permissions / storage / activities |
| 21 | `get_device_info` | `SystemTools.kt` | model / battery / storage / memory / network / display |
| 22 | `get_set_settings` | `SystemControlTools.kt` | `settings get/put` for system / secure / global namespaces |
| 23 | `control_media` | `SystemControlTools.kt` | volume / brightness / media_play / media_pause / media_next |
| 24 | `clipboard` | `SystemControlTools.kt` | Read/write clipboard via `service call clipboard` / `am broadcast` |
| 25 | `get_time` | `SystemControlTools.kt` | Current date, time, timezone, timestamp, day of week |
| 26 | `logcat` | `SystemControlTools.kt` | `logcat -d -t N` with optional filter / level / clear |
| 27 | `ui_tap` | `UiTools.kt` | `input tap` (or long-press via `input swipe`) |
| 28 | `ui_swipe` | `UiTools.kt` | `input swipe` or direction-based simplified scroll |
| 29 | `ui_dump` | `UiTools.kt` | `uiautomator dump` — XML view tree of current screen |
| 30 | `screenshot` | `UiTools.kt` | `screencap -p <path>` |
| 31 | `input_text` | `UiTools.kt` | `input text` (with char escaping) or `input keyevent <code>` |
| 32 | `calculate` | `UtilityTools.kt` | Evaluate math expression via `bc -l` |
| 33 | `text_transform` | `UtilityTools.kt` | base64/url encode-decode, md5/sha256, case, reverse, json_format |
| 34 | `get_location` | `SensorTools.kt` | `dumpsys location` for last known GPS/network fix |
| 35 | `notification_read` | `SensorTools.kt` | `dumpsys notification` for active notifications |

The `ToolModule` DI module also provides a dedicated `OkHttpClient` (15s connect / 30s read timeouts, follows redirects) for the web tools — separate from the LLM streaming client. `FileMemoryStore` is backed by `context.filesDir/agent_memory/<category>/<key>.json` and persists across app restarts.

This is distinct from the `ConversationMemory` (in `core:agent-engine`) which persists the conversation history — the tools above let the agent explicitly save and recall structured facts/preferences.

### `core:llm-adapter`
Pure-JVM OpenAI-compatible client surface:
- `LlmClient` interface with both `chat()` (blocking) and `chatStream()` (returns `Flow<LlmStreamChunk>`)
- `LlmConfig` — typed config with presets (`openai()`, `ollama()`, `openRouter()`, `deepseek()`, `custom()`) and per-call `customHeaders` / `systemPromptPrefix` / `reasoningEffort`
- `ReasoningEffort` — enum for model-native thinking intensity (`NONE` / `LOW` / `MEDIUM` / `HIGH` / `MAX`). When not `NONE`, `StreamingOpenAiClient` injects `reasoning_effort` into the request body (OpenAI o-series / DeepSeek-R1 / Qwen3-thinking). `MAX` also raises `max_completion_tokens` to ≥8192 to give the thinking chain room.
- `LlmClientFactory` — constructs an `OkHttpClient` (with read-timeout from config) and wires it into `StreamingOpenAiClient`
- `StreamingOpenAiClient` — full SSE streaming implementation with OkHttp 4.x API (`RequestBody.create`, `MediaType.parse`) and a private `Call.await()` suspend extension
- `NoOpLlmClient` — placeholder used by `LlmModule` until the user configures API settings
- `LlmException` — typed errors for API failures
- Supporting types: `LlmMessage` (sealed: `System` / `User` / `Assistant` / `ToolResult`), `LlmResponse`, `LlmStreamChunk`, `ToolCall`, `ToolDefinition`, `Usage`

### `platform:privilege`
- `PrivilegeDetector` — runtime detection of Root (su binary scan + `su --version` exec) and Shizuku (class-load probe), plus a `executeShell(command)` that auto-selects Root → normal shell. Returns `ShellExecResult(success, output, exitCode, via)`.
- `PrivilegeManager` / `DefaultPrivilegeManager` — Shizuku-bound privilege facade
- `ApexAccessibilityService` — UI automation backend

### `platform:persistence`
Foreground-service persistence with a WorkManager watchdog (`WatchdogWorker`) that re-launches the core service if it's killed.

### `plugin-sdk`
- `plugin-api`: AIDL `IApexPlugin.aidl` + `PluginContract` constants
- `plugin-host`: `PluginManager` — discovers installed plugins via `PackageManager`, binds to their `PLUGIN` action services, and bridges their tools into the host's `ToolRegistry`

### `plugins:plugin-workflow`
Reference plugin APK that exposes `workflow/save`, `workflow/execute`, `workflow/list` tools to the host agent via AIDL.

## In-App Configuration

The Settings screen lets the user pick a preset (OpenAI / DeepSeek / OpenRouter / Ollama / Custom), enter Base URL + API Key + Model, tune Temperature, test the connection (sends `Say 'OK' in one word.`), and save the config to `SharedPreferences("apex_settings")`. The `LlmModule` DI provider reads from the same prefs at app start; if invalid, a `NoOpLlmClient` is injected that responds with a friendly "please configure" message instead of crashing.

## Conversation Memory

The agent persists its `conversationHistory` across app restarts via `SharedPrefsConversationMemory` (backed by `SharedPreferences("apex_memory")`). Every message — user input, assistant replies, tool calls, tool results, plan-mode step prompts — is appended to storage as it enters the history. On engine construction, `memory.load()` rehydrates the full conversation so the agent picks up where it left off.

The chat top bar shows a **"记忆 N"** badge with the current persisted message count, and a **"+"** (new chat) button that calls `ChatViewModel.newChat()` → `ApexAgentEngine.clearHistory()` → wipes both in-memory and persisted state.

Serialization uses a small `StoredMessage` DTO (role + content + optional toolCallId + toolCalls list) serialized via `kotlinx.serialization`'s `ListSerializer`. Schema evolution is handled gracefully — a decode failure clears the store and starts fresh rather than crashing.

## Context Compression (P7)

When `TokenEstimator.estimateHistory(conversationHistory)` exceeds `maxContextTokens × compressionThreshold` (default 80% of 128k), the engine calls `HybridCompressor.compress(history, preserveRecent=5)`. The hybrid compressor runs three layers in order, stopping as soon as the threshold is met:

1. **Layer 1 — Tool output truncation** (zero-cost, always runs). `ToolOutputTruncator.smartTruncate(output, toolName)` picks a strategy based on content type:
   - JSON → keep head + last 200 chars (closing braces)
   - List output (>20 short lines) → keep first 15 + last 10 lines
   - `read_file` / `project_read_file` → head-only (code usually has key info up top)
   - Default → head 1200 + tail 600 chars with `[... N chars omitted ...]` marker
   - This layer also runs inline on every tool result *before* it enters history (via `toolTruncator.smartTruncate` in `executeBuildLoop`), so it's the first defense regardless of whether `maybeCompressContext` fires.

2. **Layer 2 — Sliding window** (zero-cost). `SlidingWindowCompressor` keeps the system prompt + last `preserveRecentTurns` messages, replaces the middle with a single `[CONTEXT COMPRESSED]` system message containing a rule-based summary (user requests, tool names used, key tool results, last assistant response).

3. **Layer 3 — LLM summary** (one extra LLM call). `LlmSummaryCompressor` sends the to-be-compressed slice to the LLM with a structured prompt (`## Task / ## Progress / ## State / ## Key Data`, ≤400 words), replaces it with the LLM-generated summary. Falls back to a rule-based summary if the LLM call fails.

After compression, the engine emits `AgentEvent.ContextCompressed(beforeTokens, afterTokens, strategy, summary, messagesRemoved, messagesTruncated)`. The ChatViewModel renders this as a system message: `📦 Context compressed: 105000→80000 tokens (HYBRID, removed 20 msgs, truncated 3)`.

The compressed history is also synced back to `ConversationMemory` via `memory.save(conversationHistory)` so the next app restart loads the already-compressed state.

**Protection rules** (never compressed):
- System prompt (always index 0)
- User's original task description (when it's the most recent user message)
- Last `preserveRecentTurns` messages (default 5)
- Currently-executing tool call (mid-iteration)

## Native Thinking Intensity

Distinct from `ThinkingLevel` (which only edits the system prompt text), the **ReasoningEffort** enum controls the model's *native* reasoning parameter:

| Level | OpenAI o-series | DeepSeek-R1 / Qwen3-thinking | Effect |
|-------|-----------------|------------------------------|--------|
| `NONE` | (field omitted) | (field omitted) | Model default behavior |
| `LOW` | `reasoning_effort: "low"` | `reasoning_effort: "low"` | Fast, token-efficient |
| `MEDIUM` | `reasoning_effort: "medium"` | `reasoning_effort: "medium"` | Balanced |
| `HIGH` | `reasoning_effort: "high"` | `reasoning_effort: "high"` | Deep reasoning |
| `MAX` | `reasoning_effort: "high"` + `max_completion_tokens: ≥8192` | `reasoning_effort: "high"` + extended budget | Maximum thinking chain |

The level is selected via a horizontal **FilterChip** row above the input box in the chat screen (labeled "原生思考:"). Selecting a chip immediately persists the choice to `SharedPreferences("apex_settings", key="llm_reasoning_effort")`. The `LlmModule` reads this on next `LlmClient` construction and `StreamingOpenAiClient.buildRequestBody()` injects `reasoning_effort` into the JSON body when the value is not `NONE`.

This is orthogonal to `ThinkingLevel` — you can use `ThinkingLevel.NONE` (no system-prompt instruction) + `ReasoningEffort.MAX` (let the model's native thinking do all the work), or `ThinkingLevel.DEEP` + `ReasoningEffort.NONE` (prompt-guided reasoning only), or both together.

## Drawer Navigation + Slash Commands

The old bottom-navigation `ApexNavHost` has been replaced with a `ModalNavigationDrawer`-based layout (`ApexRoot`). Tap the ☰ icon (top-left) or swipe from the left edge to open the drawer, which contains:

- **Agent** — main chat interface (`AgentChatScreen`)
- **Skill** — installed skill list + enable/disable (`SkillScreen`)
- **记忆** — long-term memory browser (`MemoryScreen`)
- **模型** — LLM config + presets + test connection (`ModelScreen`)
- **权限** — Root/Shizuku/Accessibility/overlay/notification/storage status (`PermissionsScreen`)
- **设置** — general app settings (`SettingsScreen`)

The drawer footer shows the current `mode` (BUILD/PLAN), `thinkingLevel`, and `historyDepth` (persisted memory count) pulled live from `AgentChatViewModel`.

The Agent chat input bar exposes two distinct affordances on its left side:

- **`/` button** (`SlashCommandButton`) — a 36dp bordered box that opens a `Popup` with four collapsible categories: **Skills**, **MCP**, **连接器**, **插件**. Each category expands with an animated 90° arrow rotation and lists concrete commands (e.g. `/skill:code_interpreter`, `/mcp:github`, `/connector:google_drive`, `/plugin:pdf_reader`). Selecting a command **appends** it to the current input (preserving any text the user has already typed) rather than overwriting it.
- **`+` button** (`AttachButton`) — opens the file/image picker for multimodal attachments. (The earlier `PlusMenuBottomSheet` that carried Skill/MCP/插件/连接器 entries has been superseded by the `/` button; the `+` button is now exclusively for attachments.)

### Slash command grammar & routing

Commands follow a forgiving, whitespace-tolerant grammar:

```
/<type>:<id> [key=value ...] [positional user text ...]
```

Examples:

| Input | Parsed |
|-------|--------|
| `/skill:code_interpreter` | `Skill(id=code_interpreter)` |
| `/skill:web_search query=Android latest news` | `Skill(id=web_search, args={query=Android}, userExtra="latest news")` |
| `/mcp:github repo=owner/name` | `Mcp(id=github, args={repo=owner/name})` |
| `/help` | `Unknown(raw=/help)` — forwarded verbatim to the agent |

Parsing and routing live in the `com.apex.agent.slash` package (pure JVM, no Compose/Android dependencies, unit-testable):

- `SlashCommand` — sealed model: `Skill` / `Mcp` / `Connector` / `Plugin` / `Unknown`
- `SlashCommandParser` — defensive parser; malformed shapes degrade to `Unknown`, malformed `key=value` tokens fall back to positional user text
- `SlashRouteContext` — immutable runtime context the router consults for connection-aware commands (currently `githubConnected` / `githubUsername`)
- `SlashCommandRouter` — maps a parsed command + context to a `SlashCommandRoute(systemMessage, agentPrompt, requestGithubConnect)`
- Unit tests: `app/src/test/kotlin/com/apex/agent/slash/` (`SlashCommandParserTest`, `SlashCommandRouterTest`)

`AgentChatViewModel.handleSlashCommand()` snapshots the current GitHub connection state into a `SlashRouteContext`, calls the router, appends `systemMessage` as an `AgentUiMessage.System` bubble, and hands `agentPrompt` to `AgentEngine.execute(...)`. This replaces the previous inline `when(type)` string concatenation and makes the command surface independently testable and extensible for future command types (`tool:`, `help:`, …).

### `/mcp:github` real binding

`/mcp:github` is the first slash command wired to a **real** backend capability: the 7 GitHub tools registered in `ToolModule` when a token is present (`github_get_user`, `github_list_repos`, `github_read_file`, `github_write_file`, `github_create_issue`, `github_list_issues`, `github_search_code`).

The router branches on the connection state carried in `SlashRouteContext`:

| State | Behavior |
|-------|----------|
| **Connected** | `systemMessage` = `"🔌 已启用 GitHub MCP 上下文（用户: <login>）"`; `agentPrompt` explicitly enumerates the 7 `github_*` tool IDs and instructs the agent to prefer them; `AgentEngine.execute()` runs normally. User args (`repo=owner/name`) and extra text are forwarded. |
| **Not connected** | `systemMessage` = `"⚠️ GitHub 未连接，请通过输入栏 GitHub 图标连接后再使用 /mcp:github"`; `agentPrompt` is empty; the router sets `requestGithubConnect = true`. The ViewModel emits a one-shot `requestGithubConnect` `SharedFlow` event, which `AgentChatScreen` collects to open the existing `GithubTokenDialog` — so the slash command itself bootstraps the connection flow instead of forcing the user to find the input-bar GitHub icon. `AgentEngine.execute()` is **not** called (no hollow prompt). |

This closes the gap flagged in the slash-command audit: `/mcp:github` is no longer a semantic hint — it either activates a real GitHub tool context or guides the user through the connect flow.

## Skill System

A **Skill** is a composable capability that bundles tools, prompt injections, and auto-setup actions. Skills are stored as JSON manifests (schema `apex-skill-v1`) in `context.filesDir/skills/<id>.json` and managed by `SkillRegistry` (`core/tool-registry/.../skill/SkillRegistry.kt`).

### Skill types

| Type | `implementation.type` | Description |
|------|----------------------|-------------|
| **Composite** | `"composite"` | Chains existing tools via `steps[]`. Each step has a `tool` + `args` map with `{{var}}` / `{{prev_output}}` template substitution. Executed by `SkillToolAdapter`. |
| **Prompt** | `"prompt"` (via `promptInjection` field) | Doesn't add new tools; injects a system prompt into `buildSystemPrompt()` when the skill is enabled. |
| **Script** | `"script"` | Embeds a Python/Shell script. `SkillToolAdapter` runs it via `shell_execute`. |
| **Connector** | `"connector"` | Connects to an external service (URL/SSH). Stub for now. |

### Skill management tools (5)

Registered in `ToolModule` as tools #36-#40:

| Tool | Purpose |
|------|---------|
| `skill_search` | Search GitHub `apex-skill` repos + built-in templates |
| `skill_install` | Install from URL / template (web_scraper, file_organizer, code_runner, data_analyzer) / content |
| `skill_create` | Agent writes a new skill manifest from scratch |
| `skill_list` | List installed skills + status |
| `skill_uninstall` | Remove by ID |

### Agent-driven skill download flow

```
User: "帮我下载一个网页爬虫 skill"
  ↓
Agent calls: skill_search({"query": "web scraper"})
  → returns GitHub repos + builtin templates
  ↓
Agent calls: skill_install({"source": "template", "template": "web_scraper"})
  → downloads/loads manifest JSON
  → validates schema
  → persists to filesDir/skills/web_scraper.json
  → executes auto_setup (create_directory ./scrape_output)
  ↓
Agent calls: skill_list({})
  → confirms installation
  ↓
Agent replies: "✅ 已安装'网页数据爬取' skill. 新增工具: web_scrape"
```

Note: dynamically-registered skill tools (via `SkillToolAdapter`) require an engine restart to appear in the `ToolRegistry` since the registry is built once at app startup. Future PR may add a hot-reload mechanism.

## Execution Flow

### Build mode (default)

```
User types message in ChatScreen
  ↓
ChatViewModel.sendMessage(text)
  ↓
ApexAgentEngine.execute(input) -> Flow<AgentEvent>  (mode = BUILD)
  ↓
For each iteration (up to config.maxIterations):
  1. Build messages (system prompt + history)
  2. llmClient.chatStream(messages, tools) -> Flow<LlmStreamChunk>
  3. Accumulate content + toolCalls (with ToolCallAccumulator for streamed args)
  4. If toolCalls: emit ToolCallStart, execute via ToolExecutor, emit ToolCallComplete, loop
  5. If content only: emit ResponseChunk(s) then ResponseComplete, done
  ↓
ChatViewModel collects events -> updates ChatUiState
  ↓
ChatScreen re-renders streaming bubbles, tool cards
```

### Tool output streaming

Tool execution is no longer an opaque "start → wait → complete" block. The `ToolExecutor` exposes `executeStream(toolId, arguments): Flow<ToolStreamEvent>`, and `ApexAgentEngine` collects it so tool output surfaces live in the UI:

```
ToolCallStart(toolName, arguments)
  ↓
toolExecutor.executeStream(...) -> Flow<ToolStreamEvent>
  ├─ Output(chunk)  → emit ToolOutputChunk(callId, chunk)   (zero or more, as output arrives)
  ├─ Complete(output)                                        (terminal — success)
  └─ Error(message)                                          (terminal — failure)
  ↓
ToolCallComplete(callId, toolName, output, success, durationMs)
```

The streaming layer is opt-in per tool:

- **`StreamingAgentTool`** (new interface, extends `AgentTool`) — a tool that can produce output incrementally implements `executeStream(arguments): Flow<ToolStreamEvent>`. The executor detects it at runtime and forwards its events verbatim. This is the path a future `shell_execute` streaming variant will take (reading a long-running process line-by-line).
- **Plain `AgentTool`** — unchanged. The executor transparently wraps `execute()` into a single `Output(result)` + `Complete(result)` (or `Error` if the result is `"Error"`-prefixed), so every existing tool works without modification and the engine/UI code path is unified.

On the UI side, `AgentChatViewModel` handles `AgentEvent.ToolOutputChunk` by appending to `currentToolCall.output` (capped to the last 4000 chars to bound recomposition on long outputs like `logcat`). `RunningToolCallCard` renders that live output in a monospace, vertically-scrollable area under the tool header — so the user sees `ping` / `logcat` / build output appear as it happens instead of staring at a spinner until the tool finishes.

Cancellation propagates through the flow: `abort()` cancels the engine job, which cancels the `collect`, which cancels the tool's flow — so a streaming tool that honors coroutine cancellation (e.g. destroying its `Process`) stops immediately.

> **Status:** the streaming **infrastructure** is complete end-to-end (executor → engine → ViewModel → UI). The first concrete streaming tool (`shell_execute` reading stdout line-by-line across the Root/Shizuku/shell privilege tiers) is the next PR — it requires extending the privilege-layer executor lambda from `suspend (String) -> String` to a streaming variant, which is a larger, separately-scoped change.

### Plan mode

```
User types message (with mode = PLAN in top bar)
  ↓
ApexAgentEngine.executePlanMode(input, emit):
  1. Emit ThinkingStart(0, thinkingLevel)
  2. Stream plan-prompt response as ThinkingChunk(s)  (UI shows thinking bubble)
  3. Emit ThinkingComplete(fullPlanText)
  4. parseExecutionPlan(response) -> ExecutionPlan (JSON parse, fallback to single-step)
  5. Emit PlanGenerated(plan)
  6. Emit PlanAwaitingConfirmation(plan)   <- UI shows PlanConfirmationCard with Execute/Cancel
  7. awaitPlanConfirmation() suspends on CompletableDeferred<Boolean> (5-min timeout)
  8. ChatViewModel.confirmPlan(true/false) -> ApexAgentEngine.submitPlanConfirmation()
  9. On true: Emit PlanConfirmed, loop over plan.steps:
       - Emit StepStart(index, description)
       - Inject step prompt as User message
       - Run one Build-loop iteration (which streams ResponseChunk + executes tools)
  10. Emit final reflection (ResponseChunk + ResponseComplete)
  ↓
ChatViewModel renders thinking bubble, plan card, step indicators, tool cards, final summary
```

### Thinking depth

The `ThinkingLevel` selected in the top-bar dropdown controls the system prompt injected by `buildSystemPrompt()`:
- `NONE` — no thinking instruction; no `ThinkingStart` emitted in Build mode
- `LIGHT` — `"Briefly think about what to do next in 1-2 sentences, then act."`
- `STANDARD` — `"Think step by step about the task..."`
- `DEEP` — multi-step instruction (analyze → consider 2-3 approaches → compare → assess risks → choose)
- `MAXIMUM` — exhaustive 8-step reasoning chain with self-critique

The level is hot-swappable via `ChatViewModel.setThinkingLevel()` which calls `ApexAgentEngine.updateConfig()`. Changes apply on the next iteration / next task.

## Permissions

The `app` manifest declares elevated permissions (foreground service of `specialUse`, system alert window, package usage stats, manage external storage, query all packages, etc.). End users will need to grant these on-device — Shizuku is required for the privileged shell operations when Root is unavailable.

### Privilege execution chain (Root → Shizuku → Shell)

`PrivilegeDetector.executeShell(command)` now routes through **three** priority levels, fixing the previous gap where Shizuku was declared as a dependency but never actually wired into the execution path:

| Priority | Channel | Mechanism | Capability |
|----------|---------|-----------|------------|
| 1 (highest) | **Root** | `Runtime.exec("su -c " + command)` | Full system access: `/system`, `/data`, mount, SELinux, iptables, ptrace |
| 2 | **Shizuku** | `Shizuku.newProcess(arrayOf("sh","-c",command), null, null)` via `ShizukuCommandExecutor` | ADB-level (uid=2000): `pm install/uninstall`, `am start/stop`, `settings get/put`, `dumpsys`, `input tap/swipe/text`, `screencap`, `/sdcard` read-write, `getprop` |
| 3 (fallback) | **Normal shell** | `Runtime.exec("sh -c " + command)` | Sandbox only — basic file ops in `/sdcard`, no system commands |

Before this fix, the chain was Root → Shell (Shizuku was completely skipped). Now Shizuku is the middle tier, so devices without Root can still execute privileged commands (`pm list packages`, `am start`, `dumpsys battery`, etc.) by installing the Shizuku app.

### Shizuku integration components

- **`ShizukuCommandExecutor`** (`platform/privilege/.../shizuku/`) — the actual executor. `isAvailable()` pings the binder, `hasPermission()` checks `Shizuku.checkSelfPermission()`, `execute(command)` runs via `Shizuku.newProcess()` with timeout. Returns `ShizukuExecResult(success, output, exitCode)`.
- **`PrivilegeDetector`** — rewritten to call `ShizukuCommandExecutor.execute()` when `detectShizuku()` returns true. New `getPrivilegeLevel()` returns a `PrivilegeLevel` enum (`ROOT` / `SHIZUKU` / `NORMAL_SHELL`). The old `detectShizuku()` only checked if the Shizuku class was loadable (reflection); the new one checks both binder liveness and permission grant.
- **`ApexApp`** — `onCreate()` registers `Shizuku.addBinderReceivedListenerSticky` / `addBinderDeadListener` / `addRequestPermissionResultListener` for logging + lifecycle awareness.
- **`PermissionsScreen`** — the Shizuku card is now a dedicated `ShizukuPermissionCard` with three states:
  - **Not running** → button "安装/启动" opens the Shizuku app (or its download page if not installed)
  - **Running but not authorized** → button "授权" calls `Shizuku.requestPermission(1001)`
  - **Authorized** → "已就绪" badge

### Agent awareness (PrivilegeInfoProvider)

`agent-engine` is a pure-JVM module and cannot import Android code (`PrivilegeDetector`). To let the engine's system prompt mention the current privilege level, a `PrivilegeInfoProvider` interface is defined in `agent-engine` and implemented as `AndroidPrivilegeInfoProvider` in the app module (wrapping `PrivilegeDetector.getPrivilegeLevel().name`). The engine injects it via constructor and `buildSystemPrompt()` now includes a `## Device Privilege Level: ROOT/SHIZUKU/NORMAL_SHELL` section with capability/limitation hints:

- **ROOT** → "Full system access: /system, /data, mount, SELinux, iptables, etc."
- **SHIZUKU** → "You CAN: pm install/uninstall, am start/stop, settings put/get, dumpsys, input..., screencap, /sdcard. You CANNOT: modify /system, access other apps' /data/data, mount, iptables, SELinux."
- **NORMAL_SHELL** → "Limited to /sdcard and your sandbox. Suggest the user install Shizuku (https://shizuku.rikka.app/)."

This lets the agent proactively suggest installing Shizuku when the user asks for a privileged operation that the normal shell can't do.

