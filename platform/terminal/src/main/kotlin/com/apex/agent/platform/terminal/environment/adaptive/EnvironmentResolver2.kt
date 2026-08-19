package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DependencySource
import com.apex.agent.platform.terminal.environment.DeveloperCapability
import com.apex.agent.platform.terminal.environment.EnvironmentRequirement
import com.apex.agent.platform.terminal.environment.EnvironmentResolver
import com.apex.agent.platform.terminal.environment.EnvironmentResolution
import com.apex.agent.platform.terminal.environment.EnvironmentResolutionContext
import com.apex.agent.platform.terminal.environment.EnvironmentSnapshot
import com.apex.agent.platform.terminal.environment.EnvironmentState
import com.apex.agent.platform.terminal.environment.PackageDeduplicator
import com.apex.agent.platform.terminal.environment.ProvisionAction
import com.apex.agent.platform.terminal.environment.ProvisionPlan
import com.apex.agent.platform.terminal.environment.EnvironmentVars
import com.apex.agent.platform.terminal.pkg.PackageSpec

/**
 * PR #67 section 2: Adaptive Environment Resolver (v2).
 *
 * EXTENDS — does NOT replace — the P66 v1 `EnvironmentResolver`. v1 looks at
 * project files + a static EnvironmentSnapshot; v2 ADDS execution-result-based
 * diagnosis on top: it reads `recentExecutions` (commands the Agent already ran
 * in the Terminal), runs the registered DiagnosticRules, and augments the v1
 * ProvisionPlan with the rules' findings.
 *
 * §21 No blind install: diagnostics below the confidence threshold
 * (§22 default 0.75) are surfaced in `resolution.diagnostics` but do NOT add
 * packages to the plan — the Agent / user must be asked first.
 *
 * §22 Confidence:
 *   - 0.99 command-not-found, JAVA_HOME missing, Rust linker missing  → auto-install
 *   - 0.92 known header/library mapping                              → auto-install
 *   - 0.55 unknown header / fuzzy stderr                             → ask agent
 *
 * §23 Layer separation: this resolver only JUDGES. It does NOT call apt,
 * pip, npm, cargo, or any package manager. The Provisioner (P66) executes
 * the plan via LinuxPackageManager; rules never reach the package manager.
 *
 * §24 4 layers must not mix:
 *   Layer 1 — Terminal runs commands and produces ExecutionObservation.
 *   Layer 2 — AdaptiveEnvironmentResolver judges observations + requirements → plan.
 *   Layer 3 — EnvironmentProvisioner executes plan via LinuxPackageManager.
 *   Layer 4 — LinuxPackageManager calls apt/dpkg.
 *   This file is Layer 2.
 *
 * §19 Convergence: the resolver computes an EnvironmentDelta vs the previous
 * snapshot in the context. `converged` = delta has no addedCapabilities AND
 * no unresolvedRequirements (other delta fields are always empty in v2's
 * first cut — they are reserved for future version-tracking work).
 *
 * §25 Summary: a single human+machine-readable line at the end of
 * `AdaptiveResolution.summary`, e.g.
 *   `CONVERGED=false ADDED=[CMAKE] UNRESOLVED=[] DIAGNOSTICS=1 PLAN_PKGS=[cmake]`
 *
 * Spec: PR #67 sections 2, 3, 19, 21, 22, 23, 24, 25.
 */

// ─── Section 2: AdaptiveEnvironmentResolver Contract ───
interface AdaptiveEnvironmentResolver {
    /** v2 entry point: includes recent executions + diagnostics. */
    suspend fun resolveAdaptive(context: AdaptiveResolutionContext): AdaptiveResolution

    /** v1 pass-through. Composes (does not inherit) the P66 v1 resolver so
     *  existing v1 callers keep working unchanged. */
    suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution
}

// ─── Section 3: AdaptiveResolutionContext v2 ───
data class AdaptiveResolutionContext(
    val workspaceId: String,
    val projectRoot: String,
    val projectRequirements: List<EnvironmentRequirement>,
    val environmentSnapshot: EnvironmentSnapshot,
    val recentExecutions: List<ExecutionObservation> = emptyList(),
    val diagnostics: List<EnvironmentDiagnostic> = emptyList()
)

// ─── Section 25: AdaptiveResolution (v2 result) ───
data class AdaptiveResolution(
    val plan: ProvisionPlan,
    val delta: EnvironmentDelta,
    val diagnostics: List<EnvironmentDiagnostic>,
    val converged: Boolean,
    val summary: String
)

// ─── Section 2: Default v2 Implementation ───
// Composes a v1 resolver + a DiagnosticRuleRegistry + a ResolverCache.
class AdaptiveEnvironmentResolverImpl(
    private val v1: EnvironmentResolver,
    private val ruleRegistry: DiagnosticRuleRegistry,
    private val cache: ResolverCache,
    private val confidenceThreshold: Float = DiagnosticConfidence.DEFAULT_THRESHOLD_FLOAT
) : AdaptiveEnvironmentResolver {

    override suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution =
        v1.resolve(context)

    override suspend fun resolveAdaptive(context: AdaptiveResolutionContext): AdaptiveResolution {
        // ── (a) v1 baseline: static analysis of project files + snapshot ───
        val baseline = v1.resolve(
            EnvironmentResolutionContext(
                workspaceId = context.workspaceId,
                projectRoot = context.projectRoot,
                projectRequirements = context.projectRequirements,
                environmentSnapshot = context.environmentSnapshot
            )
        )

        // ── (b) Run all rules over recentExecutions → collect diagnostics ───
        val collected = mutableListOf<EnvironmentDiagnostic>()
        for (exec in context.recentExecutions) {
            val matches = ruleRegistry.matchAll(exec)
            for (m in matches) collected.add(m.diagnostic)
        }
        // Caller-supplied pre-computed diagnostics (e.g. from prior loop iter)
        collected.addAll(context.diagnostics)

        // ── (c) Partition: high-confidence (auto-install) vs low (ask) ───
        val highConf = collected.filter { it.confidence >= confidenceThreshold }
        val lowConf = collected.filter { it.confidence < confidenceThreshold }

        // ── (d) Augment plan with high-confidence diagnostics ───
        val augmentedPackages = mutableListOf<PackageSpec>()
        val augmentedActions = mutableListOf<ProvisionAction>()
        val augmentedCaps = linkedSetOf<DeveloperCapability>()
        val unresolved = linkedSetOf<String>()

        for (d in highConf) {
            when (d.type) {
                DiagnosticType.ENVIRONMENT_VARIABLE_MISSING -> {
                    // §15 + §33: repair env var, do NOT reinstall JDK / Go.
                    val envVar = envVarFor(d)
                    if (envVar == null) {
                        unresolved.add("env-var: ${d.tool}")
                        continue
                    }
                    val (name, value) = envVar
                    augmentedActions.add(ProvisionAction.SetEnvironmentVariable(name, value))
                    // No new capability: env-var repair doesn't add a tool.
                    if (d.capability != null && d.capability !in context.environmentSnapshot.capabilities.keys) {
                        // Cap was missing because env var was wrong, not because
                        // the tool isn't installed. Mark as resolved (no-op add).
                    }
                }
                DiagnosticType.ARCHITECTURE_MISMATCH -> {
                    // §17: do NOT auto-install. Surface to Agent.
                    unresolved.add("architecture-mismatch: ${d.evidence.joinToString("; ")}")
                }
                DiagnosticType.VERSION_TOO_OLD,
                DiagnosticType.VERSION_TOO_NEW -> {
                    // Resolver v2 can't compare without the requirement; surface.
                    unresolved.add("version-mismatch (${d.tool}): ${d.evidence.joinToString("; ")}")
                }
                DiagnosticType.PACKAGE_MISSING -> {
                    // Python module / Node module / Go package — workspace-scoped,
                    // NOT a base-rootfs install (§29). Surface to Agent.
                    val pkgs = d.packageCandidates.joinToString(",")
                    unresolved.add("${d.source.name.lowercase()}-workspace-dep: $pkgs")
                }
                DiagnosticType.RUNTIME_MISSING,
                DiagnosticType.PERMISSION_PROBLEM,
                DiagnosticType.PATH_MISCONFIGURED,
                DiagnosticType.DEPENDENCY_INSTALL_FAILED,
                DiagnosticType.UNKNOWN -> {
                    unresolved.add("${d.type.name.lowercase()}: ${d.evidence.joinToString("; ")}")
                }
                DiagnosticType.COMMAND_NOT_FOUND,
                DiagnosticType.LIBRARY_MISSING,
                DiagnosticType.COMPILER_MISSING,
                DiagnosticType.BUILD_TOOL_MISSING -> {
                    // §23 + §24: apt-installable candidates → add to plan.
                    if (d.source == DependencySource.APT) {
                        for (pkg in d.packageCandidates) {
                            augmentedPackages.add(PackageSpec(pkg))
                        }
                        if (d.capability != null) augmentedCaps.add(d.capability)
                    } else {
                        // Non-apt (e.g. RustToolchainRule's source=CARGO on
                        // `cargo: command not found`). Cargo itself is apt-
                        // installable, but the resolver defers to the
                        // workspace-scoped RustToolchainProvider skeleton.
                        unresolved.add("${d.source.name.lowercase()}-toolchain: ${d.packageCandidates.joinToString(",")}")
                    }
                }
            }
        }

        // ── (e) Compute EnvironmentDelta vs the previous snapshot ───
        // §19: added = capabilities this resolution would make READY that the
        // snapshot doesn't already have as READY.
        val prevReadyCaps = context.environmentSnapshot.capabilities
            .filterValues { it == EnvironmentState.READY }.keys
        val baselineMissing = baseline.missingCapabilities.toSet()
        // Capabilities added by THIS iteration = baseline-missing ones (v1
        // already plans to install them) + new diagnostic-driven ones.
        val allAdded = linkedSetOf<DeveloperCapability>().apply {
            addAll(baselineMissing)
            addAll(augmentedCaps)
        }
        val addedCapabilities = allAdded - prevReadyCaps

        // Removed / changed are always empty in v2's first cut (no removal
        // path; version tracking reserved for a future PR — see §20 KDoc).
        val removedCapabilities = emptySet<DeveloperCapability>()
        val changedVersions = emptyMap<String, VersionChange>()

        // Unresolved from v1's incompatible caps + new unresolved set.
        val unresolvedAll = linkedSetOf<String>().apply {
            for (cap in baseline.incompatibleCapabilities) add("incompatible: $cap")
            addAll(unresolved)
            // Low-confidence diagnostics are surfaced in `diagnostics` but
            // ALSO counted as unresolved so the loop doesn't auto-converge
            // while known-broken observations remain unexplained.
            for (d in lowConf) add("low-confidence (${d.type.name.lowercase()}): ${d.evidence.joinToString("; ")}")
        }

        val delta = EnvironmentDelta(
            addedCapabilities = addedCapabilities.toSet(),
            removedCapabilities = removedCapabilities,
            changedVersions = changedVersions,
            unresolvedRequirements = unresolvedAll.toSet()
        )

        // ── (f) Merge baseline plan + augmented packages ───
        val mergedPackages = PackageDeduplicator.deduplicate(
            baseline.plan.packagesToInstall + augmentedPackages
        )
        val mergedActions = mutableListOf<ProvisionAction>().apply {
            addAll(baseline.plan.actions)
            // Add InstallPackage actions for diagnostics-driven packages that
            // weren't already in the baseline plan (for traceability).
            for (pkg in augmentedPackages) {
                if (baseline.plan.packagesToInstall.none { it.name == pkg.name }) {
                    add(ProvisionAction.InstallPackage(pkg))
                }
            }
            addAll(augmentedActions)
        }

        val mergedPlan = ProvisionPlan(
            requirements = baseline.plan.requirements,
            packagesToInstall = mergedPackages,
            actions = mergedActions.toList(),
            estimatedSize = null,
            requiresNetwork = mergedPackages.isNotEmpty() || augmentedActions.isNotEmpty()
        )

        // ── (g) Cache: remember command→capability mappings for reuse (§36) ───
        for (d in highConf) {
            val tool = d.tool ?: continue
            val cap = d.capability ?: continue
            cache.putCommandCapability(tool, cap)
            if (d.packageCandidates.isNotEmpty()) {
                cache.putDiagnosticCandidates(d.type.name + ":" + tool, d.packageCandidates)
            }
        }

        // ── (h) Convergence + summary (§19 + §25) ───
        val converged = delta.isEmpty
        val summary = buildSummary(mergedPlan, delta, collected, converged)

        return AdaptiveResolution(
            plan = mergedPlan,
            delta = delta,
            diagnostics = collected.toList(),
            converged = converged,
            summary = summary
        )
    }

    private fun envVarFor(d: EnvironmentDiagnostic): Pair<String, String>? {
        return when (d.tool) {
            EnvironmentVars.JAVA_HOME -> EnvironmentVars.JAVA_HOME to "/usr/lib/jvm/default-java"
            EnvironmentVars.GOROOT -> EnvironmentVars.GOROOT to "/usr/lib/go"
            EnvironmentVars.CARGO_HOME -> EnvironmentVars.CARGO_HOME to "/root/.cargo"
            EnvironmentVars.RUSTUP_HOME -> EnvironmentVars.RUSTUP_HOME to "/root/.rustup"
            EnvironmentVars.GOPATH -> EnvironmentVars.GOPATH to "/root/go"
            else -> null
        }
    }

    private fun buildSummary(
        plan: ProvisionPlan,
        delta: EnvironmentDelta,
        diagnostics: List<EnvironmentDiagnostic>,
        converged: Boolean
    ): String {
        val pkgs = plan.packagesToInstall.joinToString(",") { it.name }
        val added = delta.addedCapabilities.joinToString(",") { it.name }
        val unresolved = delta.unresolvedRequirements.joinToString(";")
        return "CONVERGED=$converged ADDED=[$added] UNRESOLVED=[$unresolved] " +
            "DIAGNOSTICS=${diagnostics.size} PLAN_PKGS=[$pkgs]"
    }
}
