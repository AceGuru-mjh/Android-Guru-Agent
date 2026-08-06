plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.privilege"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:tool-registry"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
