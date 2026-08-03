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
├── app/                            # Application entry, Compose UI, Hilt DI
├── core/
│   ├── agent-engine/               # Pure-JVM agent loop (Plan/Build, streaming, events)
│   ├── tool-registry/              # Pure-JVM tool registry + ShellExecuteTool
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

> Removed in this iteration: `build-logic/` (convention plugins — replaced with direct plugin aliases), `core/memory/`, `platform/linux-runtime/`, `platform/workspace/`, `plugins/plugin-automation/`. They will be re-introduced as separate PRs once their consumers are ready.

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
Pure-JVM tool registry + executor + 14 built-in tools (all `AgentTool` implementations, registered in `ToolModule`):

| # | Tool ID | Source File | Purpose |
|---|---------|-------------|---------|
| 1 | `shell_execute` | `ShellExecuteTool.kt` | Run device shell commands (Root/Shizuku/normal) |
| 2 | `read_file` | `FileTools.kt` | Read text files with max_lines / offset_lines windowing |
| 3 | `write_file` | `FileTools.kt` | Write or append to files (creates parent dirs) |
| 4 | `list_files` | `FileTools.kt` | List directory contents with size + mtime |
| 5 | `delete_file` | `FileTools.kt` | Delete a file or empty directory |
| 6 | `web_fetch` | `WebTools.kt` | Fetch URL, auto-extract readable text from HTML |
| 7 | `web_search` | `WebTools.kt` | DuckDuckGo HTML search (no API key needed) |
| 8 | `http_request` | `WebTools.kt` | Generic GET/POST/PUT/DELETE/PATCH with custom headers/body |
| 9 | `memorize` | `MemoryTools.kt` | Save info to long-term `FileMemoryStore` (by category) |
| 10 | `recall` | `MemoryTools.kt` | Search memories by keyword, key, or list_all |
| 11 | `forget` | `MemoryTools.kt` | Delete a memory by key or clear an entire category |
| 12 | `get_device_info` | `SystemTools.kt` | model / battery / storage / memory / network / display |
| 13 | `app_list` | `SystemTools.kt` | List user/system/all apps via `pm list packages` |
| 14 | `app_launch` | `SystemTools.kt` | Launch app by package (via `monkey` or `am start`) |

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
