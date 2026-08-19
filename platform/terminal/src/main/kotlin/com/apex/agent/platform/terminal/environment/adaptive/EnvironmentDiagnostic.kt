package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DependencySource
import com.apex.agent.platform.terminal.environment.DeveloperCapability

/**
 * PR #67 sections 5, 6, 22: Environment Diagnostic.
 *
 * A Diagnostic is what the adaptive resolver produces when an ExecutionObservation
 * reveals that something is missing or misconfigured. It is the "judge's verdict":
 * the resolver decides WHAT is wrong and WHICH packages/env-vars could fix it,
 * but it does NOT call apt / pip / npm itself (§23). The Provisioner executes
 * the resulting plan (P66), not this layer.
 *
 * §21 Confidence: each diagnostic carries a confidence in [0.0, 1.0]. The
 * resolver puts high-confidence diagnostics straight into the ProvisionPlan;
 * low-confidence ones go only into the `diagnostics` list so the Agent /
 * user can be asked before any install (§21: no blind installs).
 *
 * §22 Confidence constants (per spec):
 *   - COMMAND_NOT_FOUND                → 0.99 (HIGH)
 *   - Known header→package mapping      → 0.92 (MAPPED)
 *   - Fuzzy / unknown stderr pattern   → 0.55 (LOW)
 *
 * §24 Layer separation: a diagnostic carries `packageCandidates` as
 * STRINGS — they are SUGGESTIONS, not a `PackageSpec`. The resolver turns
 * the suggestions into proper `PackageSpec` instances via the v1 / v2
 * resolution pipeline; the LinuxPackageManager only ever sees PackageSpecs.
 *
 * Spec: PR #67 sections 5, 6, 22, 23, 24.
 */

// ─── Section 6: Diagnostic Type (14 variants) ───
enum class DiagnosticType {
    COMMAND_NOT_FOUND,
    VERSION_TOO_OLD,
    VERSION_TOO_NEW,
    PACKAGE_MISSING,
    LIBRARY_MISSING,
    COMPILER_MISSING,
    BUILD_TOOL_MISSING,
    RUNTIME_MISSING,
    ENVIRONMENT_VARIABLE_MISSING,
    PATH_MISCONFIGURED,
    PERMISSION_PROBLEM,
    ARCHITECTURE_MISMATCH,
    DEPENDENCY_INSTALL_FAILED,
    UNKNOWN
}

// ─── Section 22: Confidence constants ───
// Rules MUST use these constants instead of magic floats so that the
// confidence thresholds in tests stay stable across the codebase.
enum class DiagnosticConfidence(val value: Float) {
    HIGH(0.99f),     // command-not-found, well-known tool map (§22 row 1)
    MAPPED(0.92f),   // known header/library→package mapping (§22 row 2)
    MEDIUM(0.75f),   // default admission threshold (§21)
    LOW(0.55f),      // unknown header / fuzzy stderr (§22 row 3)
    UNKNOWN(0.0f);   // sentinel: rule produced no usable evidence

    companion object {
        /** Default threshold above which a diagnostic is admitted into the
         *  ProvisionPlan. Below this, the resolver surfaces the diagnostic to
         *  the Agent but does NOT auto-install (§21 no blind installs). */
        const val DEFAULT_THRESHOLD_FLOAT = 0.75f

        val DEFAULT_THRESHOLD = MEDIUM
    }
}

// ─── Section 5: Environment Diagnostic ───
// The "judge's verdict" — see file KDoc.
data class EnvironmentDiagnostic(
    val type: DiagnosticType,
    val tool: String?,
    val packageCandidates: List<String>,
    val capability: DeveloperCapability?,
    val confidence: Float,
    val evidence: List<String>,
    val source: DependencySource = DependencySource.APT
) {
    /** True when this diagnostic's confidence is at-or-above the given threshold. */
    fun isHighConfidence(threshold: Float = DiagnosticConfidence.DEFAULT_THRESHOLD_FLOAT): Boolean =
        confidence >= threshold
}

// ─── Section 10: Diagnostic Match ───
// Wraps a single diagnostic produced by a single rule's `match()`. Wrapping
// in a class (instead of returning the diagnostic directly) leaves room for
// the registry to attach rule metadata later without breaking the contract.
data class DiagnosticMatch(
    val diagnostic: EnvironmentDiagnostic
)
