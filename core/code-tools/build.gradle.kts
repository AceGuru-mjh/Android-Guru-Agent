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

dependencies {
    // BaseTool / ToolSchema / ToolResult / ToolMetadata —— 工具系统 v2 契约
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
