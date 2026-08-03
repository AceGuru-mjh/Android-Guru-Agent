plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Project deps — ApexAgentEngine consumes LlmClient/ToolRegistry/ToolExecutor directly.
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
