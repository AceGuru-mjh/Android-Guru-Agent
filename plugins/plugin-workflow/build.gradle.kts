import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.plugin.workflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apex.agent.plugin.workflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 迁移到
// 项目级 compilerOptions DSL（语义等价：等价于对全部 Kotlin 编译任务生效）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(libs.core.ktx)
    implementation(libs.serialization.json)
    // #256 防回退：WorkflowStore 纯逻辑层 JVM 单测（此前插件模块零测试，
    // 假成功桩存活一个多月无报警）。
    testImplementation(libs.junit)
}
