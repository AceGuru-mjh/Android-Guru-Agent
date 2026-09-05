plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.code.intelligence"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Code Intelligence Android 平台层
// 职责：git_* 工具（走 TerminalRuntime，git 二进制在 proot guest 内）、
// LanguageServerManager（lazy start / reuse / idle shutdown / crash recovery）、
// LspSession + TerminalLspTransport（JSON-RPC over TerminalRuntime 到 guest 内
// apt 装的 clangd/gopls/pyright/rust-analyzer）、ProblemsAggregator 实现、
// Tier-1 tree-sitter 符号索引（评估后定 native/纯 Java 方案）。
// LSP 协议类型与 ProblemsAggregator 接口留 :core:code-tools（纯 JVM，可单测）。
dependencies {
    implementation(project(":core:code-tools"))
    implementation(project(":core:code-engine"))
    // Git/Build/Intelligence tools implement AgentTool (from :core:tool-registry).
    // Gradle `implementation` is NOT transitive, so the implemented interface must
    // be on this module's own compile classpath.
    implementation(project(":core:tool-registry"))
    implementation(project(":platform:terminal"))
    implementation(project(":platform:code-workspace"))
    implementation(project(":core:logging"))

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
