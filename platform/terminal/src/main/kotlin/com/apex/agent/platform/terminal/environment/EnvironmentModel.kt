package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.PackageSpec

/**
 * PR #66: Agent-driven Developer Environment — Core Data Model.
 *
 * Foundational types shared by:
 *   - EnvironmentProfileRegistry (built-in Python/Node/Java/C++/Rust/Go profiles)
 *   - ProjectEnvironmentAnalyzer (file → requirements)
 *   - EnvironmentResolver v1 (requirements + snapshot → missing capabilities)
 *   - EnvironmentProvisioner (plan → LinuxPackageManager)
 *
 * Boundaries:
 *   - NO Ubuntu-specific types here (generic environment contract).
 *   - NO TerminalCore / PTY / PID exposure.
 *   - Agent NEVER sees packages directly; it sees Capabilities.
 *
 * Spec: PR #66 sections 3-21.
 */

// ─── Section 3: EnvironmentProfile ───
data class EnvironmentProfile(
    val id: String,
    val version: String,
    val requirements: List<EnvironmentRequirement>
)

// ─── Section 4: EnvironmentRequirement ───
data class EnvironmentRequirement(
    val id: String,
    val displayName: String,
    val detection: DetectionSpec,
    val packages: List<PackageSpec>,
    val optionalPackages: List<PackageSpec> = emptyList(),
    val versionConstraint: VersionConstraint? = null,
    val capabilities: Set<DeveloperCapability>
)

// ─── Section 5: DeveloperCapability ───
// Agent reasons in capabilities, never in apt package names.
enum class DeveloperCapability {
    PYTHON_RUNTIME,
    PYTHON_PIP,
    NODE_RUNTIME,
    NODE_PACKAGE_MANAGER,
    JAVA_RUNTIME,
    JAVAC,
    C_COMPILER,
    CPP_COMPILER,
    MAKE,
    CMAKE,
    RUST_TOOLCHAIN,
    GO_TOOLCHAIN
}

// ─── Detection: how to verify a capability exists at runtime ───
data class DetectionSpec(
    val command: String,
    val versionArg: List<String> = listOf("--version"),
    val versionRegex: String? = null
) {
    companion object {
        fun command(cmd: String, vararg versionArg: String): DetectionSpec =
            DetectionSpec(cmd, versionArg.toList())
    }
}

// ─── Section 10: Version Constraints ───
// "command exists" is NOT enough; the version must satisfy the constraint.
data class VersionConstraint(
    val operator: VersionOperator,
    val version: String
) {
    fun satisfies(actual: String): Boolean {
        val cmp = compareVersions(actual, version)
        return when (operator) {
            VersionOperator.EQ -> cmp == 0
            VersionOperator.GE -> cmp >= 0
            VersionOperator.GT -> cmp > 0
            VersionOperator.LE -> cmp <= 0
            VersionOperator.LT -> cmp < 0
            VersionOperator.NE -> cmp != 0
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    override fun toString(): String = "$operator $version"
}

enum class VersionOperator { EQ, GE, GT, LE, LT, NE }

// ─── Section 9: Environment State ───
enum class EnvironmentState {
    UNKNOWN,
    CHECKING,
    READY,
    MISSING,
    INSTALLING,
    FAILED,
    INCOMPATIBLE
}

// ─── Section 12: Dependency Source ───
// Where a missing dependency should be resolved from.
// P67: distinguishes "apt install foo" vs "pip install foo" vs "npm install foo".
enum class DependencySource { APT, PIP, NPM, CARGO, GO, MAVEN, GRADLE }

// ─── Section 19: Environment Snapshot (high-speed detection cache) ───
// Avoid re-scanning apt on every command. Refresh only on demand.
data class EnvironmentSnapshot(
    val tools: Map<String, ToolRecord>,
    val capabilities: Map<DeveloperCapability, EnvironmentState>,
    val generatedAt: Long,
    val version: Int = 1
) {
    val isFresh: Boolean get() = System.currentTimeMillis() - generatedAt < FRESH_TTL_MS

    fun capabilityState(cap: DeveloperCapability): EnvironmentState =
        capabilities[cap] ?: EnvironmentState.UNKNOWN

    fun toolVersion(command: String): String? = tools[command]?.version

    companion object {
        const val FRESH_TTL_MS = 5 * 60 * 1000L  // 5 min
        val EMPTY = EnvironmentSnapshot(emptyMap(), emptyMap(), 0L)
    }
}

data class ToolRecord(
    val command: String,
    val path: String?,
    val version: String?,
    val capability: DeveloperCapability?,
    val source: DependencySource = DependencySource.APT
)

// ─── Section 18: Workspace Environment ───
// Records which capabilities a workspace has, without modifying Ubuntu base.
data class WorkspaceEnvironment(
    val workspaceId: String,
    val profile: EnvironmentProfile?,
    val installedCapabilities: Set<DeveloperCapability>,
    val versions: Map<String, String>,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    fun touch(now: Long): WorkspaceEnvironment = copy(lastUsedAt = now)
}

// ─── Section 14: Provision Plan (generated before installation) ───
data class ProvisionPlan(
    val requirements: List<EnvironmentRequirement>,
    val packagesToInstall: List<PackageSpec>,
    val actions: List<ProvisionAction>,
    val estimatedSize: Long?,
    val requiresNetwork: Boolean
) {
    val isEmpty: Boolean get() = packagesToInstall.isEmpty() && actions.isEmpty()

    companion object {
        val EMPTY = ProvisionPlan(emptyList(), emptyList(), emptyList(), null, false)
    }
}

// ─── Provision Action: a single step in the plan ───
sealed interface ProvisionAction {
    data class InstallPackage(val spec: PackageSpec) : ProvisionAction
    data class InstallPackages(val specs: List<PackageSpec>) : ProvisionAction
    data class SetEnvironmentVariable(val name: String, val value: String) : ProvisionAction
    data class PrependPath(val path: String) : ProvisionAction
    data class CreateVirtualEnv(val path: String, val interpreter: String) : ProvisionAction
    data class RunPostInstall(val command: List<String>) : ProvisionAction
}

// ─── Section 15: Package Deduplication ───
// Multiple requirements may need the same package (e.g. make for C++ and Rust).
// Install it only once.
object PackageDeduplicator {
    fun deduplicate(packages: List<PackageSpec>): List<PackageSpec> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<PackageSpec>()
        for (pkg in packages) {
            if (seen.add(pkg.name)) {
                out.add(pkg)
            }
        }
        return out
    }
}

// ─── Section 20: PATH / JAVA_HOME / GOROOT management ───
// Centralized; NOT scattered across providers.
data class EnvironmentVariable(val name: String, val value: String)

data class PathEntry(val path: String, val prepend: Boolean = true)

// Standard environment variable names managed by EnvironmentManager.
object EnvironmentVars {
    const val PATH = "PATH"
    const val JAVA_HOME = "JAVA_HOME"
    const val GOROOT = "GOROOT"
    const val GOPATH = "GOPATH"
    const val CARGO_HOME = "CARGO_HOME"
    const val RUSTUP_HOME = "RUSTUP_HOME"
    const val VIRTUAL_ENV = "VIRTUAL_ENV"
    const val PYTHONPATH = "PYTHONPATH"
    const val NODE_PATH = "NODE_PATH"
}

// ─── Section 21: Environment Events (observable by Agent) ───
sealed interface EnvironmentEvent {
    val workspaceId: String
    val timestamp: Long

    data class AnalyzingProject(
        override val workspaceId: String,
        override val timestamp: Long,
        val projectRoot: String
    ) : EnvironmentEvent

    data class ResolvingEnvironment(
        override val workspaceId: String,
        override val timestamp: Long,
        val missing: Set<DeveloperCapability>
    ) : EnvironmentEvent

    data class PreparingPlan(
        override val workspaceId: String,
        override val timestamp: Long,
        val plan: ProvisionPlan
    ) : EnvironmentEvent

    data class Installing(
        override val workspaceId: String,
        override val timestamp: Long,
        val packages: List<String>
    ) : EnvironmentEvent

    data class Verifying(
        override val workspaceId: String,
        override val timestamp: Long
    ) : EnvironmentEvent

    data class Ready(
        override val workspaceId: String,
        override val timestamp: Long,
        val capabilities: Set<DeveloperCapability>
    ) : EnvironmentEvent

    data class Failed(
        override val workspaceId: String,
        override val timestamp: Long,
        val reason: String
    ) : EnvironmentEvent
}

// ─── Section 16: Environment Cache (cross-project reuse) ───
// Project A installs Python+Node+CMake; Project B needs Python+CMake -> reuse.
data class EnvironmentCacheEntry(
    val key: String,
    val capabilities: Set<DeveloperCapability>,
    val versions: Map<String, String>,
    val createdAt: Long
)
