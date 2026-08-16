plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apex.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // 内部模块
    implementation(project(":core:agent-engine"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(project(":platform:privilege"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:terminal"))
    implementation(project(":platform:cs-mem"))
    implementation(project(":plugin-sdk:plugin-host"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.backdrop)
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Lucide 图标集（composablehorizons/compose-icons, MIT）：斜杠菜单分类图标更精致
    // 固定 1.1.0：与 Kotlin 2.2 toolchain 兼容（项目已升级 Kotlin 2.2.21，可后续评估 2.x）。
    implementation("com.composables:icons-lucide-android:1.1.0")
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // 其他
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Security (encrypted SharedPreferences for GitHub token)
    implementation(libs.security.crypto)

    // Shizuku (system privilege access)
    implementation("dev.rikka.shizuku:api:13.1.0")
    implementation("dev.rikka.shizuku:provider:13.1.0")

    // 赛博霓虹悬浮球：全局低侵入 WindowManager 管理（JitPack，已做仓库过滤+版本锁定）
    implementation(libs.easyfloat)
    // 物理弹力手势（SpringAnimation 按压挤压形变 / 吸附）
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// 依赖锁定：固定已解析版本，避免 JitPack/EasyFloat 在不同时刻解析到不同工件，提升 CI 复现性
dependencyLocking {
    lockAllConfigurations()
}
