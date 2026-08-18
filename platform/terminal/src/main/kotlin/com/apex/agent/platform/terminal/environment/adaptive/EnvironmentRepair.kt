package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DependencySource
import com.apex.agent.platform.terminal.environment.DeveloperCapability
import java.util.concurrent.ConcurrentHashMap

/**
 * PR #67 sections 26, 27, 29, 30, 31, 34: Environment Repair + Provenance.
 *
 * §26 Some failures are NOT solved by installing a package — they need a
 *   REPAIR action: fix JAVA_HOME / GOROOT, recreate a broken venv, regenerate
 *   node_modules, repair a broken apt database, rebuild a Rust toolchain.
 *   EnvironmentRepairPlan captures that intent.
 *
 * §27 RepairAction enum: 7 variants. The Planner picks one (or several)
 *   based on the diagnostic's type + capability + evidence.
 *
 * §29 Workspace-scoped dependency layout (boundary statement, encoded as the
 *   WorkspaceDependencyLayout constant below): project deps live in
 *   `workspace/.venv`, `workspace/node_modules`, `workspace/target`,
 *   `workspace/build` — NEVER in the base rootfs. Repair actions for
 *   PACKAGE_MISSING diagnostics with source ∈ {PIP, NPM, CARGO, GO, MAVEN,
 *   GRADLE} therefore target the workspace, not apt.
 *
 * §30-§33 The Python/Node/Rust/Go EnvironmentProvider skeletons are referenced
 *   by the corresponding repair actions (RECREATE_VENV, REGENERATE_NODE_MODULES,
 *   REBUILD_TOOLCHAIN). Full provider implementations are deferred to a
 *   later PR; this file defines the planner + provenance store only.
 *
 * §34 CapabilityProvenance records WHERE each capability came from (which
 *   package, which source, which workspace, who installed it). ProvenanceStore
 *   is the in-memory ledger.
 *
 * §24 Layer separation: the planner PRODUCES repair plans; it does NOT
 *   execute them. Execution is the Provisioner / Provider layer's job.
 *
 * Spec: PR #67 sections 26, 27, 29, 30, 31, 32, 33, 34.
 */

// ─── Section 27: Repair Action ───
enum class RepairAction {
    REINSTALL_PACKAGE,
    FIX_PATH,
    SET_ENVIRONMENT_VARIABLE,
    REPAIR_PACKAGE_MANAGER,
    RECREATE_VENV,
    REGENERATE_NODE_MODULES,
    REBUILD_TOOLCHAIN
}

// ─── Section 26: Environment Repair Plan ───
data class EnvironmentRepairPlan(
    val actions: List<RepairAction>,
    val reason: String,
    val confidence: Float
) {
    val hasActions: Boolean get() = actions.isNotEmpty()
}

// ─── Section 29: Workspace Dependency Layout ───
// Encodes the "deps live in the workspace, not in the base rootfs" boundary.
object WorkspaceDependencyLayout {
    const val PYTHON_VENV_DIR = ".venv"
    const val NODE_MODULES_DIR = "node_modules"
    const val RUST_TARGET_DIR = "target"
    const val JAVA_BUILD_DIR = "build"
    const val GO_BUILD_DIR = "bin"

    /** All known workspace-scoped dependency directories. */
    val ALL: List<String> = listOf(
        PYTHON_VENV_DIR, NODE_MODULES_DIR, RUST_TARGET_DIR, JAVA_BUILD_DIR, GO_BUILD_DIR
    )
}

// ─── Section 26: Repair Planner ───
// Maps an EnvironmentDiagnostic to a list of RepairActions. Pure function:
// no I/O, no shell — produces a plan that the Provisioner / Provider layer
// will execute.
class EnvironmentRepairPlanner {

    fun planFor(diagnostic: EnvironmentDiagnostic, workspaceId: String): EnvironmentRepairPlan {
        val actions = mutableListOf<RepairAction>()
        val reason = StringBuilder()
        val evidence = diagnostic.evidence.joinToString("; ")

        when (diagnostic.type) {
            DiagnosticType.COMMAND_NOT_FOUND,
            DiagnosticType.LIBRARY_MISSING,
            DiagnosticType.COMPILER_MISSING,
            DiagnosticType.BUILD_TOOL_MISSING -> {
                // §21 + §27: high-confidence → reinstall the missing package.
                actions.add(RepairAction.REINSTALL_PACKAGE)
                reason.append("Reinstall missing package(s): ${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
            }
            DiagnosticType.ENVIRONMENT_VARIABLE_MISSING -> {
                // §15 + §33: NEVER reinstall — set the env var instead.
                actions.add(RepairAction.SET_ENVIRONMENT_VARIABLE)
                reason.append("Set missing env var ${diagnostic.tool}; evidence=$evidence")
            }
            DiagnosticType.PATH_MISCONFIGURED -> {
                actions.add(RepairAction.FIX_PATH)
                reason.append("Repair PATH; evidence=$evidence")
            }
            DiagnosticType.DEPENDENCY_INSTALL_FAILED -> {
                // Broken apt / dpkg → repair the package manager first.
                actions.add(RepairAction.REPAIR_PACKAGE_MANAGER)
                reason.append("Package manager broken; evidence=$evidence")
            }
            DiagnosticType.PACKAGE_MISSING -> {
                // Workspace-scoped: pip / npm / cargo / go module.
                when (diagnostic.source) {
                    DependencySource.PIP -> {
                        actions.add(RepairAction.RECREATE_VENV)
                        reason.append("Recreate Python venv in ${WorkspaceDependencyLayout.PYTHON_VENV_DIR}; missing module(s)=${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
                    }
                    DependencySource.NPM -> {
                        actions.add(RepairAction.REGENERATE_NODE_MODULES)
                        reason.append("Regenerate ${WorkspaceDependencyLayout.NODE_MODULES_DIR}; missing module(s)=${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
                    }
                    DependencySource.CARGO -> {
                        actions.add(RepairAction.REBUILD_TOOLCHAIN)
                        reason.append("Rebuild Rust target dir ${WorkspaceDependencyLayout.RUST_TARGET_DIR}; missing crate(s)=${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
                    }
                    DependencySource.GO,
                    DependencySource.MAVEN,
                    DependencySource.GRADLE -> {
                        actions.add(RepairAction.REINSTALL_PACKAGE)
                        reason.append("Reinstall workspace-scoped ${diagnostic.source.name.lowercase()} dep: ${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
                    }
                    DependencySource.APT -> {
                        actions.add(RepairAction.REINSTALL_PACKAGE)
                        reason.append("Reinstall apt package(s): ${diagnostic.packageCandidates.joinToString(",")}; evidence=$evidence")
                    }
                }
            }
            DiagnosticType.ARCHITECTURE_MISMATCH -> {
                // §17: do NOT auto-install — the Agent / user must be asked.
                // Empty actions list signals "ask agent".
                reason.append("Architecture mismatch — ask agent/user before any install; evidence=$evidence")
            }
            DiagnosticType.VERSION_TOO_OLD,
            DiagnosticType.VERSION_TOO_NEW,
            DiagnosticType.RUNTIME_MISSING,
            DiagnosticType.PERMISSION_PROBLEM,
            DiagnosticType.UNKNOWN -> {
                reason.append("${diagnostic.type.name.lowercase()} — no automatic repair; evidence=$evidence")
            }
        }

        return EnvironmentRepairPlan(
            actions = actions.toList(),
            reason = reason.toString(),
            confidence = diagnostic.confidence
        )
    }
}

// ─── Section 34: Capability Provenance ───
// Records WHERE each capability came from: which apt package, which source,
// which workspace, who installed it. Used for diagnostics + audit.
data class CapabilityProvenance(
    val capability: DeveloperCapability,
    val source: DependencySource,
    val package: String?,
    val version: String?,
    val installedBy: String,
    val workspace: String
) {
    /** True when the capability was provided by a workspace-scoped source
     *  (pip/npm/cargo/go/maven/gradle) rather than the base apt rootfs. */
    val isWorkspaceScoped: Boolean get() = source != DependencySource.APT
}

// ─── Section 34: Provenance Store ───
// In-memory, per-workspace ledger. ConcurrentHashMap for thread safety.
// `installedBy` records the component that recorded the provenance — e.g.
// "EnvironmentProvisioner", "RustToolchainProvider", or a component name from
// a future PR.
class ProvenanceStore {

    private val byWorkspace: ConcurrentHashMap<String, MutableMap<DeveloperCapability, CapabilityProvenance>> =
        ConcurrentHashMap()

    fun record(workspaceId: String, provenance: CapabilityProvenance) {
        store(workspaceId)[provenance.capability] = provenance
    }

    fun get(workspaceId: String, capability: DeveloperCapability): CapabilityProvenance? =
        byWorkspace[workspaceId]?.get(capability)

    fun all(workspaceId: String): Map<DeveloperCapability, CapabilityProvenance> =
        byWorkspace[workspaceId]?.toMap() ?: emptyMap()

    fun remove(workspaceId: String, capability: DeveloperCapability): CapabilityProvenance? =
        byWorkspace[workspaceId]?.remove(capability)

    fun clearWorkspace(workspaceId: String) {
        byWorkspace.remove(workspaceId)
    }

    fun workspaceIds(): Set<String> = byWorkspace.keys.toSet()

    private fun store(workspaceId: String): MutableMap<DeveloperCapability, CapabilityProvenance> =
        byWorkspace.computeIfAbsent(workspaceId) { java.util.concurrent.ConcurrentHashMap() }
}
