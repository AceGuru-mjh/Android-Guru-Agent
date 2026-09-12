plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

import java.time.Duration

android {
    namespace = "com.apex.agent.platform.terminal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // P70: real-JNI PTY tests (NativePtyJniInstrumentationTest) — run on a
        // device/emulator via :platform:terminal:connectedDebugAndroidTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -Wall -Wextra -O2"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // P71: proot/loader/talloc 预构建二进制需要在设备上以文件形式存在
        //（nativeLibraryDir exec），app 与本模块（androidTest APK）都开 legacy 打包。
        jniLibs { useLegacyPackaging = true }
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
    // T82 SDK-boundary pruning: `core:tool-registry` had ZERO Kotlin imports from
    // this module (TerminalTool is a local bridge interface precisely to avoid the
    // dependency); `core.ktx` was likewise unused. Dropping both keeps the module
    // a pure (coroutines + serialization + terminal-emulator) JVM library — the
    // graduation prerequisite recorded in docs/terminal/TERMINAL_SDK_BOUNDARY.md.
    implementation(project(":terminal-emulator"))  // ATR 2.0 VT100/ANSI emulator
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.coroutines.android)

    // P70: real-JNI instrumentation tests (forkpty against /system/bin/sh on device).
    // CI has no emulator — these are compile-checked in CI and run via
    // connectedDebugAndroidTest on a real device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}


// P68: show test stdout/stderr in CI log so println diagnostics are visible
// when tests fail. Without this, Gradle captures stdout into the HTML report
// only, not the console — making integration-test debugging impossible.
tasks.withType<Test>().configureEach {
    // 30 分钟：T72 E2E（真实 Ubuntu 下载/解包 + proot 真实 apt）在 2 核 CI runner
    // 上实测需 20+ 分钟；20 分钟预算在缓存失效（首次或输入变更）后必超时。
    // 单条 proot 探测/L2 命令自身有界（30s/60s），无限挂起已在上游修复。
    timeout.set(Duration.ofMinutes(30))
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}