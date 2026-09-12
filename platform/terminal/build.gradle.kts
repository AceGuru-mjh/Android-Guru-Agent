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
    // ─── CI 挂死根因修复（jstack 确证）：IO 线程池被泄漏泵耗尽 ───
    // 大量测试类创建 Runtime/Session 后从不 close —— 每个泄漏会话的输出泵在
    // Dispatchers.IO 上跑「nativeWaitForData 阻塞轮询」永不退出。默认 IO 池
    // 64 线程，约 64 个泄漏泵后池满：InputManager 写协程等一切 IO 派发永久
    // 排队，首个 IO 写测试（deferred.await）挂死至 30 分钟任务超时。
    // 本地复现：单 JVM 全量 161 类 92s 挂死，jstack 59/64 IO 线程卡在
    // FakeNativePty.nativeWaitForData 的 sleep 循环；io.parallelism=96 后
    // 全量 1088 测试 84s 零挂死跑完 —— 双保险修复：
    forkEvery = 16          // ① 每 16 类重启测试 JVM：泄漏线程随之消亡，单 JVM 泄漏上限 ~16类×4泵=64
    systemProperty("kotlinx.coroutines.io.parallelism", "96")  // ② IO 池扩至 96，留出余量
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}