plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// Code Mode 工具层（纯 JVM，零 Android 依赖）
// 镜像 :core:tool-registry 的分层：可独立单测、被 Android 层按需引入。
// 包含：CodeWorkspaceFileSystem（host IO，非 shell）、CodeDiff（纯 Kotlin Myers）、
// Problem/ProblemsAggregator 模型、LSP JSON-RPC 协议类型、9 个 code_* 文件工具。
// git_* 工具因依赖 TerminalRuntime，放在 :platform:code-intelligence（Android）。
dependencies {
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
