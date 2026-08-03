plugins {
    id("apex.android.application")
    id("apex.android.compose")
    id("apex.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.agent"

    defaultConfig {
        applicationId = "com.apex.agent"
        vectorDrawables { useSupportLibrary = true }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:agent-engine"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:memory"))
    implementation(project(":platform:privilege"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:linux-runtime"))
    implementation(project(":platform:workspace"))
    implementation(project(":plugin-sdk:plugin-host"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coil
    implementation(libs.coil.compose)
}
