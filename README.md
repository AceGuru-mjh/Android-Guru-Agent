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
Hosts the agent loop (`AgentEngine` / `DefaultAgentEngine`), event stream (`AgentEvent`), and memory contract (`AgentMemory`).

### `core:tool-registry`
Defines `ToolRegistry` and built-in tools (`ShellTool`, `ProjectTools`) that the agent can invoke.

### `core:llm-adapter`
OpenAI-compatible client with streaming (SSE) support — drop in any endpoint that speaks the OpenAI chat-completions protocol.

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

## Permissions

The `app` manifest declares a number of elevated permissions (foreground service of `specialUse`, system alert window, package usage stats, manage external storage, query all packages, etc.). End users will need to grant these on-device — Shizuku is required for the privileged operations.

## Repository Layout Conventions

- All Kotlin code lives under `src/main/kotlin/...` (not `src/main/java/...`).
- Convention plugins live in `build-logic/convention/`.
- Version catalog: `gradle/libs.versions.toml`.

---

This is an initial project skeleton — module interfaces and a runnable Compose UI are in place, but several modules contain TODOs and stub implementations that should be filled in as the project evolves.
