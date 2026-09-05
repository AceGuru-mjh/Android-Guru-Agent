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

// Code Mode 引擎层（纯 JVM，零 Android 依赖）
// 镜像 :core:agent-engine：CodeAgentEngine 包装 ApexAgentEngine 并注入 code 专属
// AgentConfig（code 系统提示 + 收窄的 enabledToolIds）。CodeConversationMemory 接口
// 留纯 JVM，按 workspaceId 分键的 Android 实现见 :platform:code-workspace。
dependencies {
    implementation(project(":core:agent-engine"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:code-tools"))
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
