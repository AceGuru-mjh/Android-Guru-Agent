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
    // 白名单判定 / 工具执行管线（v3 权限门）经 tool-registry 复用；
    // tool-registry 自带 llm-adapter（ToolDefinition）传递依赖。
    implementation(project(":core:tool-registry"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
