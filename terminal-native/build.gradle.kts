plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.vtnative"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // 与 :platform:terminal（PTY native 层）保持一致的三 ABI。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -Wall -Wextra -O2"
                arguments += "-DANDROID_STL=c++_shared"
                // 只构建 JNI 桥（host 测试由上游仓库 CI 覆盖）。
                arguments += "-DAPEX_VT_BUILD_JNI=ON"
                arguments += "-DAPEX_VT_BUILD_TESTS=OFF"
                arguments += "-DAPEX_VT_BUILD_FUZZ=OFF"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // VtEngineFactory 回退路径调用 android.util.Log —— JVM 单测用默认值桩
        // （与 :platform:cs-mem 相同的做法，无需 Robolectric）。
        unitTests.isReturnDefaultValues = true
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
    // TerminalEngine 接口 + RenderCell/ScreenMutation 投影类型来自
    // :terminal-emulator（纯 JVM，零 Android 依赖 —— 依赖方向无环）。
    implementation(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
