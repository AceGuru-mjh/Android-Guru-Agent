package com.apex.agent.platform.terminal.environment.adaptive

/**
 * PR #67 sections 10, 11: Diagnostic Rule + Rule Registry.
 *
 * §10 A DiagnosticRule inspects an ExecutionObservation and returns either
 *   `null` (rule did not match) or a `DiagnosticMatch` wrapping an
 *   `EnvironmentDiagnostic`. Rules are pure functions — no I/O, no shell.
 *
 * §11 DiagnosticRuleRegistry is the extension point: new rules can be added
 *   WITHOUT modifying the AdaptiveEnvironmentResolver. The resolver calls
 *   `registry.matchAll(observation)` and aggregates whatever comes back.
 *
 * §24 Layer separation: rules read ExecutionObservation (Terminal layer's
 *   product) and emit EnvironmentDiagnostic (resolver layer's product). They
 *   never touch LinuxPackageManager, apt, or the filesystem.
 *
 * §37 Security: rules run regexes against the already-recorded stdout /
 *   stderr strings; they never spawn shells or interpolate observations
 *   into shell commands.
 *
 * Spec: PR #67 sections 10, 11, 24, 37.
 */

// ─── Section 10: Diagnostic Rule Contract ───
interface DiagnosticRule {
    /** Stable identifier (e.g. "command-not-found", "missing-header"). */
    val id: String

    /**
     * Inspect the observation's stdout+stderr and return a `DiagnosticMatch`
     * if this rule recognizes the failure mode, or `null` otherwise.
     *
     * Implementations MUST be pure (no I/O, no shared mutable state).
     */
    fun match(observation: ExecutionObservation): DiagnosticMatch?
}

// ─── Section 11: Diagnostic Rule Registry ───
// Open extension point. Add rules in any order; the resolver calls matchAll()
// which returns ALL matching diagnostics for one observation (a single
// failed command can trigger several rules simultaneously — e.g. one rule
// flags command-not-found, another flags the missing toolchain).
class DiagnosticRuleRegistry {

    private val rules: MutableList<DiagnosticRule> = mutableListOf()
    private val byId: MutableMap<String, DiagnosticRule> = mutableMapOf()

    /** Register a rule. Idempotent on `rule.id` (later registration wins). */
    fun register(rule: DiagnosticRule) {
        synchronized(rules) {
            byId[rule.id]?.let { existing ->
                rules.remove(existing)
            }
            rules.add(rule)
            byId[rule.id] = rule
        }
    }

    /** Snapshot of all currently-registered rules, in registration order. */
    fun all(): List<DiagnosticRule> = synchronized(rules) { rules.toList() }

    /** Run every rule against the observation and collect all matches. */
    fun matchAll(observation: ExecutionObservation): List<DiagnosticMatch> {
        val snapshot = all()
        val out = mutableListOf<DiagnosticMatch>()
        for (rule in snapshot) {
            rule.match(observation)?.let(out::add)
        }
        return out.toList()
    }

    /** Number of registered rules. */
    fun size(): Int = synchronized(rules) { rules.size }

    /** Find a rule by id, or null. */
    fun find(id: String): DiagnosticRule? = synchronized(rules) { byId[id] }

    /** Remove all rules (primarily for tests). */
    fun clear() {
        synchronized(rules) {
            rules.clear()
            byId.clear()
        }
    }

    companion object {
        /**
         * Build a registry pre-loaded with the 11 built-in P67 rules
         * defined in `DiagnosticRules.kt`. This is what production code uses
         * by default; tests may construct an empty registry and register
         * only the rule under test.
         */
        fun withBuiltInRules(): DiagnosticRuleRegistry {
            val r = DiagnosticRuleRegistry()
            r.register(CommandNotFoundRule())
            r.register(VersionMismatchRule())
            r.register(MissingHeaderRule())
            r.register(MissingLibraryRule())
            r.register(MissingBuildToolRule())
            r.register(JavaHomeRule())
            r.register(PythonModuleRule())
            r.register(NodeModuleRule())
            r.register(RustToolchainRule())
            r.register(GoToolchainRule())
            r.register(ArchitectureRule())
            return r
        }
    }
}
