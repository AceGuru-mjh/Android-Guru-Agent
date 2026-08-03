plugins {
    id("apex.android.application")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.plugin.workflow"
    
    defaultConfig {
        applicationId = "com.apex.agent.plugin.workflow"
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(libs.core.ktx)
}
