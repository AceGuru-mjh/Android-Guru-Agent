plugins {
    id("apex.android.library")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.platform.privilege"
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
}
