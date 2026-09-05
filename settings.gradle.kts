pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 仅允许 EasyFloat 所在 group 走 JitPack，避免其它依赖误查 JitPack（按需构建不稳定源）
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }
            filter {
                includeGroup("com.github.princekin-f")
            }
        }
    }
}

rootProject.name = "apex-agent"

// 主APK
include(":app")

// 核心引擎（纯Kotlin JVM，零Android依赖）
include(":core:agent-engine")
include(":core:llm-adapter")
include(":core:tool-registry")
include(":core:logging")
// Code Mode 核心层（纯Kotlin JVM）—— Coding Agent 引擎 + 代码工具 + LSP 协议类型
include(":core:code-engine")
include(":core:code-tools")

// Android平台层
include(":platform:privilege")
include(":platform:persistence")
include(":platform:terminal")
include(":platform:cs-mem")
// Code Mode Android平台层 —— Workspace 管理 + Code Intelligence (LSP/Git/Diagnostics)
include(":platform:code-workspace")
include(":platform:code-intelligence")

// Terminal Runtime 2.0 — vendored VT100/ANSI emulator (ATR Phase 2)
include(":terminal-emulator")

// 插件SDK
include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

// 插件APK
include(":plugins:plugin-workflow")
