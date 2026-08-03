# Apex Agent

> Android-side autonomous AI agent with on-device LLM, tool registry, plugin SDK, and persistent background execution.

Apex Agent is a modular Android application that brings an autonomous agent runtime to the device. It integrates an LLM adapter (OpenAI-compatible), a tool registry with built-in shell/project tools, a memory store, a privilege manager (Shizuku + Accessibility), a persistence engine with watchdog, a proot-based Linux runtime, a workspace manager, and a plugin SDK that allows external plugins to extend the agent's capabilities.

## Architecture

The project is organized as a multi-module Gradle build:

```
apex-agent/
├── app/                       # Application entry, UI, services, DI
├── build-logic/               # Convention plugins (AGP / Compose / Hilt)
├── core/
│   ├── agent-engine/          # Agent loop, events, memory contract
│   ├── tool-registry/         # Tool registry + built-in tools
│   ├── llm-adapter/           # OpenAI-compatible LLM client
│   └── memory/                # Memory store abstraction
├── platform/
│   ├── privilege/             # Shizuku + Accessibility privilege manager
│   ├── persistence/           # Foreground persistence + watchdog
│   ├── linux-runtime/         # proot-based Linux runtime
│   └── workspace/             # Project/workspace manager
├── plugin-sdk/
│   ├── plugin-api/            # AIDL + plugin contract
│   └── plugin-host/          # Plugin manager (loads external plugins)
└── plugins/
    └── plugin-workflow/       # Workflow automation plugin (sample)
```

## Tech Stack

- **Kotlin** 2.1.0 / **AGP** 8.7.3 / **compileSdk** 36 / **minSdk** 26
- **Jetpack Compose** (BOM 2025.01.01) + Material 3
- **Hilt** for dependency injection (with KSP)
- **Room** for persistence
- **Coroutines** for async work
- **WorkManager** + Hilt-Work for background jobs
- **Shizuku** for elevated operations without root
- **Accessibility Service** for UI automation
- **DataStore** for preferences
- **Retrofit / OkHttp** (with SSE) for LLM API calls
- **kotlinx.serialization** for JSON
- **Coil** for image loading
- **Navigation Compose** for screen routing

## Build

The project uses Gradle convention plugins defined in `build-logic/`. To build:

```bash
./gradlew assembleDebug
```

> The project requires JDK 17 and Android SDK 36.

## Key Modules

### `core:agent-engine`
Hosts the agent loop with **Plan / Build dual modes**, **5-level thinking depth**, **streaming output**, and **automatic context compression**.

Key types:
- `AgentEngine` — interface (`execute(input) -> Flow<AgentEvent>`, `abort()`)
- `ApexAgentEngine` — production implementation (replaces the old `DefaultAgentEngine`)
- `AgentConfig` — runtime-tunable config (`mode`, `thinkingLevel`, `maxIterations`, `compressionThreshold`, `streaming`, `temperature`, …) with presets (`QUICK`, `STANDARD`, `CAREFUL`)
- `AgentMode` — `PLAN` (think → plan → user-confirm → execute steps → reflect) vs `BUILD` (ReAct loop: think → act → observe → repeat)
- `ThinkingLevel` — `NONE` / `LIGHT` / `STANDARD` / `DEEP` / `MAXIMUM`; each maps to a system-prompt instruction and a `thinking_budget`
- `AgentEvent` — sealed event stream consumed by the UI: `ThinkingStart/Chunk/Complete`, `PlanGenerated/AwaitingConfirmation/Confirmed`, `StepStart`, `ToolCallStart/OutputChunk/Complete`, `ResponseChunk/Complete`, `ContextCompressed`, `IterationStart`, `UserInputRequired`, `Error`, `Complete`, `Aborted`
- `ExecutionPlan` / `PlanStep` / `RiskLevel` — Plan-mode artifacts
- `ContextCompressor` — interface with three implementations:
  - `LlmSummaryCompressor` — uses an LLM call to summarize older turns
  - `TruncationCompressor` — fast in-process truncation (no extra LLM call)
  - `HybridCompressor` — truncates oversized tool outputs first, then escalates to LLM summarization when message count exceeds threshold
- `AgentMemory` — memory contract

### `core:tool-registry`
Defines `ToolRegistry`, `ToolExecutor`, `AgentTool`, and built-in tools (`ShellTool`, `ProjectTools`) that the agent can invoke.

### `core:llm-adapter`
OpenAI-compatible LLM client surface with streaming (SSE) support:
- `LlmClient` interface with both `chat()` (blocking) and `chatStream()` (returns `Flow<LlmStreamChunk>`)
- `OpenAiCompatibleClient` — original blocking+SSE client (used by default in DI)
- `StreamingOpenAiClient` — alternative streaming-first implementation with proper OkHttp 5.x Kotlin extensions (`toRequestBody`, `await()` suspend wrapper around `Call.enqueue`)
- Supporting types: `LlmMessage` (sealed: `System` / `User` / `Assistant` / `ToolResult`), `LlmResponse`, `LlmStreamChunk`, `ToolCall`, `ToolDefinition`, `Usage`

### `platform:privilege`
Wraps Shizuku (sudosu-style elevated operations) and an `AccessibilityService` for UI automation. `DefaultPrivilegeManager` routes operations to the appropriate backend.

### `platform:linux-runtime`
proot-based Linux runtime — useful when the agent needs a real Linux userland (e.g., for shell tools that depend on GNU coreutils).

### `platform:workspace`
Manages isolated work directories per project, including sandboxed file access.

### `plugin-sdk`
- `plugin-api`: AIDL interface (`IApexPlugin.aidl`) + contract types that plugins implement.
- `plugin-host`: Discovers, loads, and binds external plugins.

### `plugins:plugin-workflow`
A reference plugin that exposes a workflow automation service to the agent.

## Execution Modes

### Plan Mode (`AgentMode.PLAN`)
User describes a task → Agent reasons (depth-controlled) → emits an `ExecutionPlan` → UI shows the plan and asks for confirmation (`PlanAwaitingConfirmation`) → on confirm, executes each `PlanStep` sequentially → emits a final reflection. Best for complex multi-step tasks where the user wants to review the plan first.

### Build Mode (`AgentMode.BUILD`)
User describes a task → Agent enters a ReAct loop (Think → Call Tool → Observe Result → Repeat) → emits `ResponseChunk`s as the final answer streams in. Best for quick tasks where the user trusts the agent to act. Mode switches are available at runtime from the chat screen's top bar.

## Thinking Depth

The `ThinkingLevel` enum exposes 5 levels (`NONE`, `LIGHT`, `STANDARD`, `DEEP`, `MAXIMUM`). Each level injects a different system-prompt instruction and a corresponding `thinking_budget` for models that support it (e.g. Gemini). The chat UI exposes a dropdown to switch levels on the fly; the engine picks up the new config via `ApexAgentEngine.updateConfig()`.

## Streaming Output

Every LLM call routes through `LlmClient.chatStream()`, which yields `Flow<LlmStreamChunk>`. The engine translates chunks into `AgentEvent.ThinkingChunk` / `ResponseChunk` / `ToolOutputChunk` events, which the `ChatViewModel` collects and appends incrementally to `currentThinking` / `currentResponse` strings. The Compose UI renders these as growing bubbles with a typing cursor.

## Context Compression

When `estimateTokens(conversationHistory)` exceeds `maxContextTokens * compressionThreshold` (default 80% of 128k), the engine calls `ContextCompressor.compress(history, preserveRecent=5)`. The default `HybridCompressor` first truncates oversized `ToolResult` content, then — if the message count still exceeds `preserveRecent + 10` — escalates to `LlmSummaryCompressor`, which asks the LLM for a ≤500-word summary and replaces the older half of the history with a single `[CONTEXT SUMMARY]` system message. The UI shows a `📦 Context compressed: N→M tokens` system message.

## Permissions

The `app` manifest declares a number of elevated permissions (foreground service of `specialUse`, system alert window, package usage stats, manage external storage, query all packages, etc.). End users will need to grant these on-device — Shizuku is required for the privileged operations.

## Repository Layout Conventions

- All Kotlin code lives under `src/main/kotlin/...` (not `src/main/java/...`).
- Convention plugins live in `build-logic/convention/`.
- Version catalog: `gradle/libs.versions.toml`.

---

This is an initial project skeleton — the engine, streaming LLM client, Plan/Build UI, and context compressor are now in place, but several modules still contain TODOs and stub implementations (e.g. `WorkflowPluginService.handleSaveWorkflow`, `ProotLinuxRuntime` binary path, `DefaultPrivilegeManager` Shizuku validation) that should be filled in as the project evolves.
