plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.agent.plugin.api"
}

dependencies {
    implementation(libs.serialization.json)
}
