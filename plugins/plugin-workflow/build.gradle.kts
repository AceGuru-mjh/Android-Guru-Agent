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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(libs.core.ktx)
    implementation(libs.serialization.json)
    // #256 防回退：WorkflowStore 纯逻辑层 JVM 单测（此前插件模块零测试，
    // 假成功桩存活一个多月无报警）。
    testImplementation(libs.junit)
}
