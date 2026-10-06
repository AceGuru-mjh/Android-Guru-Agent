plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = 35

    defaultConfig {
        // 上游 backdrop 1.0.0 支持到 API 21；宿主 app minSdk 26 —— 取交集上游值，
        // 库自身无更低要求，保留 21 与上游 AAR 声明一致（Gradle 解析时取 max(21,26)=26）。
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 对齐宿主 app 的 Compose BOM（2024.12.01 = Compose 1.7.6）—— 上游 1.0.0
    // 的 API 需求（GraphicsLayer / RenderEffect / Modifier.Node / RuntimeShader
    // 桥接等）已逐项经 ui-android-1.7.6.aar + ui-graphics-android-1.7.6.aar
    // 字节码核实全部存在，无需升级 Compose。
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
}
