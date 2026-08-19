package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.PackageSpec

/**
 * PR #66 section 12: Environment Profile Registry.
 *
 * Holds 6 built-in developer profiles (Python, Node, Java, C/C++, Rust, Go).
 * Adding Flutter / Android SDK later requires NO Resolver changes — registry
 * just grows; resolver stays generic and capability-driven.
 *
 * Boundaries:
 *   - Profiles declaratively state requirements + capabilities.
 *   - Profiles never install anything themselves.
 *   - Registry is a pure lookup table; no I/O, no shell.
 *
 * Spec: PR #66 sections 11, 12, 17.
 */

// ─── Section 12: Profile IDs (stable constants) ───
object ProfileIds {
    const val PYTHON = "python"
    const val NODE = "node"
    const val JDK = "jdk"
    const val CPP = "cpp"
    const val RUST = "rust"
    const val GO = "go"
}

// ─── Section 12: Environment Profile Registry Contract ───
interface EnvironmentProfileRegistry {
    fun find(id: String): EnvironmentProfile?
    fun all(): List<EnvironmentProfile>
    fun register(profile: EnvironmentProfile)
}

// ─── Section 12 + 11: Built-in registry, pre-seeded with 6 profiles ───
// §11: toolchain vs package separation — each profile lists MULTIPLE apt
// packages per environment (Python = python3 + pip + venv + dev;
// C++ = gcc + g++ + make + cmake + pkg-config).
class BuiltInProfileRegistry : EnvironmentProfileRegistry {

    private val profiles: MutableMap<String, EnvironmentProfile> = LinkedHashMap()

    init {
        register(pythonProfile())
        register(nodeProfile())
        register(jdkProfile())
        register(cppProfile())
        register(rustProfile())
        register(goProfile())
    }

    override fun find(id: String): EnvironmentProfile? = profiles[id]
    override fun all(): List<EnvironmentProfile> = profiles.values.toList()
    override fun register(profile: EnvironmentProfile) {
        profiles[profile.id] = profile
    }

    // ─── Python: python3 + pip + venv + dev; GE 3.11 ───
    private fun pythonProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.PYTHON,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "python3",
                displayName = "Python 3 Runtime",
                detection = DetectionSpec.command("python3", "--version"),
                packages = listOf(
                    PackageSpec("python3"),
                    PackageSpec("python3-pip"),
                    PackageSpec("python3-venv"),
                    PackageSpec("python3-dev")
                ),
                capabilities = setOf(
                    DeveloperCapability.PYTHON_RUNTIME,
                    DeveloperCapability.PYTHON_PIP
                ),
                versionConstraint = VersionConstraint(VersionOperator.GE, "3.11")
            )
        )
    )

    // ─── Node: nodejs + npm; GE 20 ───
    private fun nodeProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.NODE,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "node",
                displayName = "Node.js Runtime",
                detection = DetectionSpec.command("node", "--version"),
                packages = listOf(
                    PackageSpec("nodejs"),
                    PackageSpec("npm")
                ),
                capabilities = setOf(
                    DeveloperCapability.NODE_RUNTIME,
                    DeveloperCapability.NODE_PACKAGE_MANAGER
                ),
                versionConstraint = VersionConstraint(VersionOperator.GE, "20")
            )
        )
    )

    // ─── JDK: default-jdk (GE 21) + JAVA_HOME env-var action (no package) ───
    // §20: JAVA_HOME is centrally managed via EnvironmentManager, NOT scattered
    // across providers. The JAVA_HOME requirement carries an ENV: marker in
    // its detection.command; the Resolver turns it into a SetEnvironmentVariable
    // action consumed by the Provisioner (which calls EnvironmentManager.set).
    private fun jdkProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.JDK,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "jdk",
                displayName = "Java Development Kit",
                detection = DetectionSpec.command("java", "--version"),
                packages = listOf(PackageSpec("default-jdk")),
                capabilities = setOf(
                    DeveloperCapability.JAVA_RUNTIME,
                    DeveloperCapability.JAVAC
                ),
                versionConstraint = VersionConstraint(VersionOperator.GE, "21")
            ),
            EnvironmentRequirement(
                id = "java-home",
                displayName = "JAVA_HOME Environment Variable",
                detection = DetectionSpec(
                    command = "ENV:${EnvironmentVars.JAVA_HOME}=/usr/lib/jvm/default-java",
                    versionArg = emptyList()
                ),
                packages = emptyList(),
                capabilities = emptySet()
            )
        )
    )

    // ─── C/C++: gcc + g++ + make + cmake + pkg-config (optional) ───
    // §11: each toolchain member is a SEPARATE requirement (not one mega-list)
    // so the Resolver can attribute missing capabilities back to the right tool.
    private fun cppProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.CPP,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "gcc",
                displayName = "GNU C Compiler",
                detection = DetectionSpec.command("gcc", "--version"),
                packages = listOf(PackageSpec("gcc")),
                capabilities = setOf(DeveloperCapability.C_COMPILER)
            ),
            EnvironmentRequirement(
                id = "g++",
                displayName = "GNU C++ Compiler",
                detection = DetectionSpec.command("g++", "--version"),
                packages = listOf(PackageSpec("g++")),
                capabilities = setOf(DeveloperCapability.CPP_COMPILER)
            ),
            EnvironmentRequirement(
                id = "make",
                displayName = "GNU Make",
                detection = DetectionSpec.command("make", "--version"),
                packages = listOf(PackageSpec("make")),
                capabilities = setOf(DeveloperCapability.MAKE)
            ),
            EnvironmentRequirement(
                id = "cmake",
                displayName = "CMake",
                detection = DetectionSpec.command("cmake", "--version"),
                packages = listOf(PackageSpec("cmake")),
                capabilities = setOf(DeveloperCapability.CMAKE)
            ),
            EnvironmentRequirement(
                id = "pkg-config",
                displayName = "pkg-config (optional helper)",
                detection = DetectionSpec.command("pkg-config", "--version"),
                packages = listOf(PackageSpec("pkg-config")),
                // Advisory: no capability declared in the DeveloperCapability
                // enum, so the Resolver installs the package but never adds
                // it to missingCapabilities.
                capabilities = emptySet()
            )
        )
    )

    // ─── Rust: rustc + cargo; GE 1.72 ───
    private fun rustProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.RUST,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "rust",
                displayName = "Rust Toolchain",
                detection = DetectionSpec.command("rustc", "--version"),
                packages = listOf(
                    PackageSpec("rustc"),
                    PackageSpec("cargo")
                ),
                capabilities = setOf(DeveloperCapability.RUST_TOOLCHAIN),
                versionConstraint = VersionConstraint(VersionOperator.GE, "1.72")
            )
        )
    )

    // ─── Go: golang-go; GE 1.22 ───
    private fun goProfile(): EnvironmentProfile = EnvironmentProfile(
        id = ProfileIds.GO,
        version = "1.0",
        requirements = listOf(
            EnvironmentRequirement(
                id = "go",
                displayName = "Go Toolchain",
                detection = DetectionSpec.command("go", "version"),
                packages = listOf(PackageSpec("golang-go")),
                capabilities = setOf(DeveloperCapability.GO_TOOLCHAIN),
                versionConstraint = VersionConstraint(VersionOperator.GE, "1.22")
            )
        )
    )
}
