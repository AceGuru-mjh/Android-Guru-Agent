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
        // NIA（android/nowinandroid）同款纪律：google() 只解析 Google 系 group，
        // 防止其它坐标误走 google() 拉到非预期构件，同时减少无谓元数据请求。
        // 本仓全部依赖核实过归属：androidx/com.android/com.google 走 google()，
        // 其余全在 mavenCentral，EasyFloat 独走 JitPack 白名单。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

// NIA 同款前置检查：JDK 不满足直接给人话报错，而不是等 AGP 在配置中期
// 抛晦涩异常（本仓 JDK 17 起步，与 CI setup-java 对齐）。
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    "Android Guru Agent requires JDK 17+ (current: ${JavaVersion.current()}). " +
        "Set JAVA_HOME to a JDK 17+ installation. CI reference: .github/workflows (setup-java temurin 17)."
}

rootProject.name = "apex-agent"

// ── 浏览器库 composite build（拆库 E1 迁移期）─────────────────────────────
// apex-browser-kit 从本仓库抽出为独立库（com.apex.browser:{core,engine,chrome}）。
// 兄弟目录存在时自动接入 composite build：本地开发「改一处两边生效」；
// CI 由 workflow 把库检出为兄弟目录（ci.yml / apk.yml / release.yml 的
// sibling checkout 步骤）。目录不存在时跳过 —— 走远端 Maven 坐标（E3 稳定期
// GitHub Packages 发布后切换）。子目录兜底位供 consumer-check 等双仓布局复用。
val browserKitSibling = file("../apex-browser-kit")
val browserKitNested = file("apex-browser-kit")
when {
    browserKitSibling.isDirectory -> includeBuild(browserKitSibling)
    browserKitNested.isDirectory -> includeBuild(browserKitNested)
}

// 主APK
include(":app")

// Liquid Glass 底层引擎 —— vendored kyant0/AndroidLiquidGlass（backdrop @ 1.0.0，
// Apache-2.0）。Maven 版需 Kotlin 2.2+/Compose 1.9+，本仓工具链消费不了 ——
// 源码内嵌，仅 ui/glass 包单点消费，详见 vendor/backdrop/README.md。
include(":vendor:backdrop")

// 核心引擎（纯Kotlin JVM，零Android依赖）
include(":core:agent-engine")
include(":core:llm-adapter")
include(":core:tool-registry")
include(":core:logging")

// Coding 模式（与 Agent 模式同级别）：编码工具集 + 编码引擎
include(":core:code-tools")
include(":core:code-engine")

// 标准任务循环文本加速核（C++17 JNI：token 估算 / 行级 diff / 模糊定位；
// 算法层 host 可测，JNI 桥 Android 平台——与 :terminal-native 同款接线）
include(":core:code-native")

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

// Terminal view — T88 Termux 级自定义 View 渲染层（Canvas 网格 / 滚动 / 选择 / IME）
// 依赖 :terminal-emulator 的纯数据模型（RenderCell/TerminalRenderSnapshot），不依赖 Compose
include(":terminal-view")

// 插件SDK
include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

// 插件APK
include(":plugins:plugin-workflow")
// 网页自动化插件：声明并分发 browser_* 工具（执行逻辑经 IApexPluginHost 回宿主 BrowserEngine）
include(":plugins:plugin-web-automation")
