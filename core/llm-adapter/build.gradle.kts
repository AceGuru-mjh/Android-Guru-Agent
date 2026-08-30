plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    // T72 — first test dependencies in this module (registry/router/validator/error tests).
    // Pure JVM, JUnit4 + kotlinx-coroutines-test, mirroring :core:agent-engine.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
