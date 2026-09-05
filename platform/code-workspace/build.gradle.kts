plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.code.workspace"
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

// Code Workspace Android 平台层
// 职责：CodeWorkspace 模型、CodeWorkspaceManager（与 LinuxWorkspaceManager 融合，
// 不重造第二套 Linux workspace）、AndroidCodeWorkspaceMemory（per-workspaceId
// SharedPrefs 分键，避免项目间污染）、CodeWorkspaceRecovery（挂 RuntimeRecoveryService）。
// 文件 IO 由 :core:code-tools 的 CodeWorkspaceFileSystem 完成（host java.io.File，
// 非 shell）；本模块只负责 workspaceId → host File 路径的解析与生命周期。
dependencies {
    implementation(project(":core:code-tools"))
    implementation(project(":core:code-engine"))
    // AndroidCodeWorkspaceMemory implements CodeConversationMemory which extends
    // ConversationMemory (from :core:agent-engine) and serializes LlmMessage/ToolCall
    // (from :core:llm-adapter). Gradle `implementation` is NOT transitive, so the
    // supertype and the serialized types must be on this module's own compile classpath.
    implementation(project(":core:agent-engine"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":platform:terminal"))
    implementation(project(":core:logging"))

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
