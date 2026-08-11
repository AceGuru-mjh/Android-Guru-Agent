pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "apex-agent"

// 主APK
include(":app")

// 核心引擎（纯Kotlin JVM，零Android依赖）
include(":core:agent-engine")
include(":core:llm-adapter")
include(":core:tool-registry")
include(":core:logging")

// Android平台层
include(":platform:privilege")
include(":platform:persistence")
include(":platform:terminal")

// 插件SDK
include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

// 插件APK
include(":plugins:plugin-workflow")
