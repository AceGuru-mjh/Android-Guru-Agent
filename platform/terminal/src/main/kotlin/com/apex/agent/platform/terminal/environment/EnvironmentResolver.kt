package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.PackageSpec

/**
 * PR #66 section 8: Environment Resolver v1.
 *
 * Inputs: project requirements (from ProjectEnvironmentAnalyzer) + an
 * EnvironmentSnapshot (from EnvironmentSnapshotCache). Outputs a
 * ProvisionPlan + the partition of capabilities into
 *   satisfied / missing / incompatible.
 *
 * §19 Snapshot freshness: callers should pass a fresh EnvironmentSnapshot.
 * The resolver itself does NOT poll apt — that is the snapshot's job.
 *
 * §11 Toolchain/Package separation: the resolver aggregates packages from
 * multiple requirements and deduplicates them (e.g. `make` shared by C++ and
 * a hypothetical Rust profile that lists make) via PackageDeduplicator before
 * returning the plan. The provisioner installs the deduped set ONCE.
 *
 * §22 Boundary: resolver does NOT call apt directly, does NOT spawn shells,
 * does NOT manage Docker/K8s/Android-SDK/Flutter/IDEs.
 *
 * Spec: PR #66 sections 8, 10, 11, 13, 19, 22.
 */

// ─── Section 8: Resolver Contract ───
interface EnvironmentResolver {
    suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution
}

// ─── Section 8: Resolution Context ───
data class EnvironmentResolutionContext(
    val workspaceId: String,
    val projectRoot: String,
    val projectRequirements: List<EnvironmentRequirement>,
    val environmentSnapshot: EnvironmentSnapshot
)

// ─── Section 8: Resolution Result ───
data class EnvironmentResolution(
    val plan: ProvisionPlan,
    val missingCapabilities: Set<DeveloperCapability>,
    val satisfiedCapabilities: Set<DeveloperCapability>,
    val incompatibleCapabilities: Set<DeveloperCapability> = emptySet()
) {
    val hasMissing: Boolean get() = missingCapabilities.isNotEmpty()
    val hasIncompatible: Boolean get() = incompatibleCapabilities.isNotEmpty()
}

// ─── Section 8: DefaultEnvironmentResolver (v1 algorithm) ───
// For each requirement, classify each declared capability against the
// snapshot:
//   READY + versionConstraint satisfied          → satisfied
//   READY + versionConstraint NOT satisfied       → incompatible + missing
//   MISSING / UNKNOWN / other                    → missing
// Then collect packages, dedup, and emit the ProvisionPlan.
class DefaultEnvironmentResolver : EnvironmentResolver {

    override suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution {
        val snapshot = context.environmentSnapshot
        val satisfied = linkedSetOf<DeveloperCapability>()
        val missing = linkedSetOf<DeveloperCapability>()
        val incompatible = linkedSetOf<DeveloperCapability>()
        val packages = mutableListOf<PackageSpec>()
        val actions = mutableListOf<ProvisionAction>()

        for (req in context.projectRequirements) {
            // §20: ENV: marker → emit a SetEnvironmentVariable action.
            // (Used by the JDK profile for JAVA_HOME.)
            if (req.packages.isEmpty() && req.detection.command.startsWith(ENV_PREFIX)) {
                parseEnvAction(req.detection.command)?.let { actions.add(it) }
                continue
            }

            // Advisory requirement (e.g. pkg-config): no capabilities to check
            // but still want its packages installed.
            if (req.capabilities.isEmpty()) {
                packages.addAll(req.packages)
                continue
            }

            for (cap in req.capabilities) {
                val state = snapshot.capabilityState(cap)
                when (state) {
                    EnvironmentState.READY -> {
                        val constraint = req.versionConstraint
                        if (constraint == null) {
                            satisfied.add(cap)
                        } else {
                            val actualVersion = snapshot.toolVersion(req.detection.command)
                            if (actualVersion == null) {
                                // Tool exists but version unknown — treat as
                                // missing (conservative) to force a re-install
                                // path; provisioner will short-circuit if pkg
                                // already present.
                                missing.add(cap)
                                addPackagesOnce(packages, req.packages)
                            } else if (constraint.satisfies(actualVersion)) {
                                satisfied.add(cap)
                            } else {
                                incompatible.add(cap)
                                missing.add(cap)
                                addPackagesOnce(packages, req.packages)
                            }
                        }
                    }
                    EnvironmentState.MISSING,
                    EnvironmentState.UNKNOWN,
                    EnvironmentState.CHECKING,
                    EnvironmentState.INSTALLING,
                    EnvironmentState.FAILED,
                    EnvironmentState.INCOMPATIBLE -> {
                        if (state == EnvironmentState.INCOMPATIBLE) incompatible.add(cap)
                        missing.add(cap)
                        addPackagesOnce(packages, req.packages)
                    }
                }
            }
        }

        // §11 + §15: dedup across requirements before handing to provisioner.
        val dedupedPackages = PackageDeduplicator.deduplicate(packages)

        // Reflect each unique package as an InstallPackage action for
        // traceability. The provisioner treats InstallPackage/InstallPackages
        // actions as already-done (it calls packageManager.install once with
        // plan.packagesToInstall).
        for (pkg in dedupedPackages) {
            actions.add(ProvisionAction.InstallPackage(pkg))
        }

        val requiresNetwork = dedupedPackages.isNotEmpty()
        val plan = ProvisionPlan(
            requirements = context.projectRequirements,
            packagesToInstall = dedupedPackages,
            actions = actions,
            estimatedSize = null,           // §8: resolver doesn't know sizes
            requiresNetwork = requiresNetwork
        )

        return EnvironmentResolution(
            plan = plan,
            missingCapabilities = missing.toSet(),
            satisfiedCapabilities = satisfied.toSet(),
            incompatibleCapabilities = incompatible.toSet()
        )
    }

    private fun addPackagesOnce(target: MutableList<PackageSpec>, source: List<PackageSpec>) {
        for (pkg in source) {
            if (target.none { it.name == pkg.name }) target.add(pkg)
        }
    }

    private fun parseEnvAction(command: String): ProvisionAction.SetEnvironmentVariable? {
        val spec = command.removePrefix(ENV_PREFIX)
        val eq = spec.indexOf('=')
        if (eq <= 0 || eq == spec.length - 1) return null
        val name = spec.substring(0, eq)
        val value = spec.substring(eq + 1)
        return ProvisionAction.SetEnvironmentVariable(name, value)
    }

    companion object {
        private const val ENV_PREFIX = "ENV:"
    }
}
