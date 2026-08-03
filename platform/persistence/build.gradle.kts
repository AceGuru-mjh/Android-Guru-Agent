plugins {
    id("apex.android.library")
    id("apex.android.hilt")
}

android {
    namespace = "com.apex.agent.platform.persistence"
}

dependencies {
    implementation(project(":platform:privilege"))
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
}
