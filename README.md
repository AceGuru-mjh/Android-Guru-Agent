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
Pure-JVM agent loop. Key types:
- `AgentEngine` — interface (`execute(input) -> Flow<AgentEvent>`, `abort()`)
- `ApexAgentEngine` — production implementation, streaming-first ReAct loop
- `AgentConfig` — runtime-tunable config (`mode`, `thinkingLevel`, `maxIterations`, `maxContextTokens`, `streaming`, `temperature`, …) with presets (`QUICK`, `STANDARD`, `CAREFUL`)
- `AgentMode` — `PLAN` vs `BUILD`
- `ThinkingLevel` — `NONE` / `LIGHT` / `STANDARD` / `DEEP` / `MAXIMUM`; each maps to a system-prompt instruction and a `thinking_budget`
- `AgentEvent` — sealed event stream: `ThinkingStart/Chunk/Complete`, `PlanGenerated/AwaitingConfirmation/Confirmed`, `StepStart`, `ToolCallStart/OutputChunk/Complete`, `ResponseChunk/Complete`, `ContextCompressed`, `IterationStart`, `UserInputRequired`, `Error`, `Complete`, `Aborted`

### `core:tool-registry`
Pure-JVM tool registry + executor + built-in tools. Currently ships `ShellExecuteTool` (parses `{"command": "..."}` JSON and delegates to the injected executor lambda).

### `core:llm-adapter`
Pure-JVM OpenAI-compatible client surface:
- `LlmClient` interface with both `chat()` (blocking) and `chatStream()` (returns `Flow<LlmStreamChunk>`)
- `LlmConfig` — typed config with presets (`openai()`, `ollama()`, `openRouter()`, `deepseek()`, `custom()`) and per-call `customHeaders` / `systemPromptPrefix`
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

## Execution Flow

```
User types message in ChatScreen
  ↓
ChatViewModel.sendMessage(text)
  ↓
ApexAgentEngine.execute(input) -> Flow<AgentEvent>
  ↓ (Plan or Build mode)
For each iteration:
  1. Build messages (system prompt + history)
  2. llmClient.chatStream(messages, tools) -> Flow<LlmStreamChunk>
  3. Accumulate content + toolCalls from stream
  4. If toolCalls: emit ToolCallStart, execute via ToolExecutor, emit ToolCallComplete
  5. If content only: emit ResponseChunk(s) then ResponseComplete, done
  ↓
ChatViewModel collects events -> updates ChatUiState
  ↓
ChatScreen re-renders streaming bubbles, tool cards, plan confirmation cards
```

## Permissions

The `app` manifest declares elevated permissions (foreground service of `specialUse`, system alert window, package usage stats, manage external storage, query all packages, etc.). End users will need to grant these on-device — Shizuku is required for the privileged shell operations when Root is unavailable.
