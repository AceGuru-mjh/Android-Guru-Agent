package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DeveloperCapability
import com.apex.agent.platform.terminal.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.environment.ProvisionPlan

/**
 * PR #67 sections 18, 19, 20: Adaptive Provision Loop + EnvironmentDelta.
 *
 * §18 The loop iterates: resolve → provision → execute → diagnose → resolve …
 *   It stops on convergence OR after `maxIterations` (default 3, configurable).
 *   The hard iteration limit prevents an infinite loop when the resolver keeps
 *   discovering new diagnostics (e.g. each install reveals a new missing lib).
 *
 * §19 Convergence: after each iteration compute EnvironmentDelta; the loop
 *   stops when delta.isEmpty (no addedCapabilities, no removedCapabilities,
 *   no changedVersions, no unresolvedRequirements).
 *
 * §20 EnvironmentDelta v2:
 *   - addedCapabilities    : capabilities the latest plan would make READY
 *   - removedCapabilities  : capabilities no longer needed (rare in v2's first cut)
 *   - changedVersions      : Map<tool, VersionChange>  (e.g. node 18→20)
 *   - unresolvedRequirements: Strings for things the resolver can't auto-fix
 *     (low-confidence diagnostics, arch mismatch, workspace-scoped deps, etc.)
 *
 * §24 Layer separation: this loop is the orchestrator. It calls the resolver
 *   (Layer 2) to JUDGE, the provisioner (Layer 3) to EXECUTE, and a
 *   caller-supplied `executor` lambda that runs in the Terminal (Layer 1).
 *   The loop itself never calls apt or spawns a shell.
 *
 * §37 Security: the executor lambda returns a structured ExecutionObservation
 *   (List<String> command + strings). The loop never interpolates into a
 *   shell string.
 *
 * Spec: PR #67 sections 18, 19, 20, 24, 37.
 */

// ─── Section 20: VersionChange ───
// `from` is null when the tool is being newly installed (no prior version);
// `to` is null when the tool is being removed.
data class VersionChange(
    val from: String?,
    val to: String?
) {
    val isUpgrade: Boolean get() = from != null && to != null && from != to
    val isInstall: Boolean get() = from == null && to != null
    val isRemoval: Boolean get() = from != null && to == null
}

// ─── Section 20: EnvironmentDelta v2 ───
data class EnvironmentDelta(
    val addedCapabilities: Set<DeveloperCapability>,
    val removedCapabilities: Set<DeveloperCapability>,
    val changedVersions: Map<String, VersionChange>,
    val unresolvedRequirements: Set<String>
) {
    // §19: convergence predicate.
    val isEmpty: Boolean get() =
        addedCapabilities.isEmpty() &&
            removedCapabilities.isEmpty() &&
            changedVersions.isEmpty() &&
            unresolvedRequirements.isEmpty()

    companion object {
        val EMPTY = EnvironmentDelta(
            addedCapabilities = emptySet(),
            removedCapabilities = emptySet(),
            changedVersions = emptyMap(),
            unresolvedRequirements = emptySet()
        )
    }
}

// ─── Section 18: Adaptive Provision Loop ───
// Orchestrates: resolve (Layer 2) → provision (Layer 3) → execute (Layer 1).
class AdaptiveProvisionLoop(
    private val resolver: AdaptiveEnvironmentResolver,
    private val provisioner: EnvironmentProvisioner,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS
) {
    init {
        require(maxIterations > 0) { "maxIterations must be > 0 (got $maxIterations)" }
    }

    /**
     * Run the loop. The caller supplies an `executor` lambda that takes the
     * current ProvisionPlan, runs the relevant verification command in the
     * Terminal, and returns the resulting ExecutionObservation. The loop
     * feeds that observation back into the next iteration's context.
     */
    suspend fun run(
        initial: AdaptiveResolutionContext,
        executor: suspend (ProvisionPlan) -> ExecutionObservation
    ): AdaptiveLoopResult {
        var context = initial
        val history = mutableListOf<AdaptiveResolution>()
        var lastResolution: AdaptiveResolution? = null
        var iterations = 0

        while (iterations < maxIterations) {
            iterations += 1
            val resolution = resolver.resolveAdaptive(context)
            history.add(resolution)
            lastResolution = resolution

            // §19 convergence: stop as soon as the resolver says converged.
            if (resolution.converged) break
            // §18 safety: an empty plan means nothing to provision — stop.
            if (resolution.plan.isEmpty) break

            // §24 Layer 3: provision executes the plan via LinuxPackageManager.
            provisioner.provision(resolution.plan, context.workspaceId)

            // §24 Layer 1: executor runs the verification command in the
            // Terminal. The observation is fed back into the next iteration.
            val observation = executor(resolution.plan)

            // §3: build next-iteration context with the new observation
            // appended. Diagnostics from this iteration are propagated too
            // so the next iteration can see what was already diagnosed.
            context = context.copy(
                recentExecutions = context.recentExecutions + observation,
                diagnostics = resolution.diagnostics
            )
        }

        val finalResolution = lastResolution ?: error("loop did not execute (maxIterations=$maxIterations)")
        return AdaptiveLoopResult(
            iterations = iterations,
            finalResolution = finalResolution,
            converged = finalResolution.converged,
            history = history.toList()
        )
    }

    companion object {
        // §18 default. 3 iterations cover the common "install foo → foo needs
        // libbar → install libbar → works" chain without exploding.
        const val DEFAULT_MAX_ITERATIONS = 3
    }
}

// ─── Section 18: Loop Result ───
data class AdaptiveLoopResult(
    val iterations: Int,
    val finalResolution: AdaptiveResolution,
    val converged: Boolean,
    val history: List<AdaptiveResolution>
) {
    /** True iff the loop hit `maxIterations` without converging. */
    val hitMaxIterations: Boolean get() = !converged && history.size == iterations
}
