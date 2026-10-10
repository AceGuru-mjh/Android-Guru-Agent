plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.codenative"
    compileSdk = 35

    // 显式钉住 NDK（Kotlin/AGP 8.13.2 toolchain 升级）：与 :terminal-native 同款理由，
    // CI 按 27.0.12077973 预装，避免 AGP 默认 NDK 漂移。
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26

        // 与 :terminal-native 保持一致的三 ABI。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -Wall -Wextra -O2"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // NativeTextKernel 回退路径不触碰 android.* —— JVM 单测用默认值桩
        // （与 :terminal-native / :platform:cs-mem 相同做法）。
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
    // StandardTextKernel SPI（code-engine 是纯 JVM 模块——依赖方向无环，
    // 与 :terminal-native → :terminal-emulator 的接线同款）。
    implementation(project(":core:code-engine"))

    testImplementation(libs.junit)
}
