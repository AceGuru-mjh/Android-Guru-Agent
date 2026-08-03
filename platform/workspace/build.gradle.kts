plugins {
    id("apex.android.library")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.platform.workspace"
}

dependencies {
    implementation(project(":platform:linux-runtime"))
    implementation(project(":platform:privilege"))
    implementation(libs.coroutines.android)
}
