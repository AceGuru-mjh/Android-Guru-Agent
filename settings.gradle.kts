pluginManagement {
    includeBuild("build-logic")
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

include(":app")

include(":core:agent-engine")
include(":core:tool-registry")
include(":core:llm-adapter")
include(":core:memory")

include(":platform:privilege")
include(":platform:persistence")
include(":platform:linux-runtime")
include(":platform:workspace")

include(":plugin-sdk:plugin-api")
include(":plugin-sdk:plugin-host")

include(":plugins:plugin-workflow")
include(":plugins:plugin-automation")
