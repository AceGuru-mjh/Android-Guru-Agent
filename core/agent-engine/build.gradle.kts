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
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // A68.1 — first test dependencies in this module.
    // Tests use JUnit4 + kotlinx-coroutines-test (runTest), mirroring the
    // pattern in :core:tool-registry. No MockK/Turbine — the version catalog
    // doesn't declare them, and hand-rolled fakes are sufficient for the
    // deterministic state-machine + loop tests A68.1 needs.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
