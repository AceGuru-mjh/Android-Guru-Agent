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

// Coding 模式（与 Agent 模式同级别）：编码工具集 + 编码引擎
include(":core:code-tools")
include(":core:code-engine")

// Android平台层
include(":platform:privilege")
include(":platform:persistence")
include(":platform:terminal")
include(":platform:cs-mem")
include(":platform:code-workspace")

// 逆向 MCP Host（#173）：手机作为 MCP Server，PC 端 AI 经 streamable HTTP 控制手机
include(":platform:mcp-host")

// Terminal Runtime 2.0 — vendored VT100/ANSI emulator (ATR Phase 2)
include(":terminal-emulator")

// Terminal native hot path — vendored apex-vt-native C++17 VT engine (JNI)
// 上游：AceGuru-mjh/apex-vt-native（129 项奇偶校验测试 + NDK CI）
include(":terminal-native")

// 插件SDK
include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

// 插件APK
include(":plugins:plugin-workflow")
// 网页自动化插件：声明并分发 browser_* 工具（执行逻辑经 IApexPluginHost 回宿主 BrowserEngine）
include(":plugins:plugin-web-automation")
