plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.csmem"
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

    testOptions {
        // cs-mem 单测为纯 JVM：SemanticNode/UiTreePruner 类签名引用 android.graphics.Rect，
        // 但被测路径（stableEdgeId / 指纹 / 蒸馏 / 旁路回放解析）不触碰其方法。
        // 打开 returnDefaultValues 使类加载与方法桩安全通过，无需 Robolectric。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":platform:privilege"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager (DreamRenderer)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Unit tests（stableEdgeId / 蒸馏参数提取 / 旁路回放解析 —— 纯 JVM 无 Android 依赖）
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
