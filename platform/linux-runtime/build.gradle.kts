plugins {
    id("apex.android.library")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.platform.linux"
}

dependencies {
    implementation(project(":platform:privilege"))
    implementation(libs.coroutines.android)
}
