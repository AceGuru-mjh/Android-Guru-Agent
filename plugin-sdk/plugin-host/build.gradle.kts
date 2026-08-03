plugins {
    id("apex.android.library")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.plugin.host"
}

dependencies {
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(project(":core:tool-registry"))
    implementation(libs.coroutines.android)
}
