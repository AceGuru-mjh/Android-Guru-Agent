package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DependencySource
import com.apex.agent.platform.terminal.environment.DeveloperCapability
import com.apex.agent.platform.terminal.environment.EnvironmentEvent
import com.apex.agent.platform.terminal.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.environment.EnvironmentResolver
import com.apex.agent.platform.terminal.environment.EnvironmentResolution
import com.apex.agent.platform.terminal.environment.EnvironmentResolutionContext
import com.apex.agent.platform.terminal.environment.EnvironmentSnapshot
import com.apex.agent.platform.terminal.environment.ProvisionAction
import com.apex.agent.platform.terminal.environment.ProvisionPlan
import com.apex.agent.platform.terminal.environment.ProvisionResult
import com.apex.agent.platform.terminal.pkg.PackageSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * PR #67 section 38: Adaptive Environment tests.
 *
 * JUnit 4 + pure-JVM (no Android imports). Test method names use `-` rather
 * than `/` or `.` to comply with the repo's static-analysis rule
 * (see PR #59 / #60 / #61 history of CI failures).
 *
 * Coverage map:
 *   - CommandNotFoundRuleTest              → §7, §16 (tool→cap map)
 *   - VersionMismatchRuleTest              → §8 (version patterns)
 *   - MissingHeaderRuleTest                → §9 (header→package map)
 *   - MissingLibraryRuleTest               → §9 (linker -l)
 *   - PythonModuleRuleTest                 → §12 (PIP source)
 *   - NodeModuleRuleTest                   → §13 (NPM source)
 *   - JavaEnvironmentRuleTest              → §15 (repair env var)
 *   - RustToolchainRuleTest                → §14 (killer feature)
 *   - GoToolchainRuleTest                  → §33 (GOROOT repair)
 *   - ArchitectureRuleTest                 → §17 (no auto-install)
 *   - DiagnosticRuleRegistryTest           → §11 (registry + matchAll)
 *   - EnvironmentResolver2Test             → §2, §3, §19, §21, §22, §25
 *   - EnvironmentDeltaTest                → §20 (delta + VersionChange)
 *   - AdaptiveLoopTest                     → §18 (loop + convergence)
 *   - ConvergenceTest                      → §19 (convergence predicate)
 *   - MaxIterationTest                     → §18 (hard cap)
 *   - ConfidenceTest                      → §22 (confidence constants)
 *   - EnvironmentRepairTest               → §26, §27 (repair actions)
 *   - ResolverCacheTest                   → §36 (cache + version)
 *   - ProvenanceStoreTest                 → §34 (provenance ledger)
 */

// ───────────────────────────────────────────────────────────────────────
// §7 + §16: CommandNotFoundRule
// ───────────────────────────────────────────────────────────────────────
class CommandNotFoundRuleTest {

    private val rule = CommandNotFoundRule()

    @Test fun `cmake command not found produces CMAKE diagnostic`() {
        val obs = ExecutionObservations.failed(listOf("cmake", "--version"), "bash: cmake: command not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.COMMAND_NOT_FOUND, match!!.diagnostic.type)
        assertEquals("cmake", match.diagnostic.tool)
        assertEquals(DeveloperCapability.CMAKE, match.diagnostic.capability)
        assertTrue(match.diagnostic.packageCandidates.contains("cmake"))
    }

    @Test fun `g++ command not found produces CPP_COMPILER diagnostic`() {
        val obs = ExecutionObservations.failed(listOf("g++", "main.cpp"), "bash: g++: command not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DeveloperCapability.CPP_COMPILER, match!!.diagnostic.capability)
        assertTrue(match.diagnostic.packageCandidates.contains("g++"))
    }

    @Test fun `command not found prefix form matches`() {
        val obs = ExecutionObservations.failed(listOf("foo"), "command not found: foo")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals("foo", match!!.diagnostic.tool)
    }

    @Test fun `cmake not found without command word does NOT match`() {
        // "cmake: not found" lacks the literal "command" word.
        val obs = ExecutionObservations.failed(listOf("cmake"), "cmake: not found")
        val match = rule.match(obs)
        assertNull(match)
    }

    @Test fun `empty stderr returns null`() {
        val obs = ExecutionObservations.succeeded(listOf("cmake"), "")
        val match = rule.match(obs)
        assertNull(match)
    }

    @Test fun `confidence is HIGH v0-99`() {
        val obs = ExecutionObservations.failed(listOf("cmake"), "cmake: command not found")
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.HIGH.value, match!!.diagnostic.confidence, 0.001f)
    }

    @Test fun `cargo command not found maps to RUST_TOOLCHAIN`() {
        val obs = ExecutionObservations.failed(listOf("cargo"), "cargo: command not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DeveloperCapability.RUST_TOOLCHAIN, match!!.diagnostic.capability)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §8: VersionMismatchRule
// ───────────────────────────────────────────────────────────────────────
class VersionMismatchRuleTest {

    private val rule = VersionMismatchRule()

    @Test fun `node version pattern produces VERSION_TOO_OLD`() {
        val obs = ExecutionObservations.succeeded(listOf("node", "--version"), "Node.js v18.0.0")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.VERSION_TOO_OLD, match!!.diagnostic.type)
        assertEquals(DeveloperCapability.NODE_RUNTIME, match.diagnostic.capability)
    }

    @Test fun `python version pattern produces VERSION_TOO_OLD`() {
        val obs = ExecutionObservations.succeeded(listOf("python3", "--version"), "Python 3.8.10")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.VERSION_TOO_OLD, match!!.diagnostic.type)
        assertEquals(DeveloperCapability.PYTHON_RUNTIME, match.diagnostic.capability)
    }

    @Test fun `go version pattern produces VERSION_TOO_OLD`() {
        val obs = ExecutionObservations.succeeded(listOf("go", "version"), "go version go1.20 linux/amd64")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.VERSION_TOO_OLD, match!!.diagnostic.type)
        assertEquals(DeveloperCapability.GO_TOOLCHAIN, match.diagnostic.capability)
    }

    @Test fun `no version pattern returns null`() {
        val obs = ExecutionObservations.failed(listOf("foo"), "some random error")
        assertNull(rule.match(obs))
    }

    @Test fun `confidence is MEDIUM v0-75`() {
        val obs = ExecutionObservations.succeeded(listOf("node", "--version"), "Node.js v18")
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.MEDIUM.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §9: MissingHeaderRule
// ───────────────────────────────────────────────────────────────────────
class MissingHeaderRuleTest {

    private val rule = MissingHeaderRule()

    @Test fun `openssl header maps to libssl-dev with confidence v0-92`() {
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "main.c:1:10: fatal error: openssl/ssl.h: No such file or directory"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.LIBRARY_MISSING, match!!.diagnostic.type)
        assertTrue(match.diagnostic.packageCandidates.contains("libssl-dev"))
        assertEquals(DiagnosticConfidence.MAPPED.value, match.diagnostic.confidence, 0.001f)
    }

    @Test fun `unknown header foo maps to libfoo-dev with confidence v0-55`() {
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "main.c:1:10: fatal error: foo.h: No such file or directory"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertTrue(match!!.diagnostic.packageCandidates.contains("libfoo-dev"))
        assertEquals(DiagnosticConfidence.LOW.value, match.diagnostic.confidence, 0.001f)
    }

    @Test fun `no fatal error returns null`() {
        val obs = ExecutionObservations.failed(listOf("gcc", "main.c"), "some other error")
        assertNull(rule.match(obs))
    }

    @Test fun `successful build returns null`() {
        val obs = ExecutionObservations.succeeded(listOf("gcc", "main.c"), "compiled successfully")
        assertNull(rule.match(obs))
    }
}

// ───────────────────────────────────────────────────────────────────────
// §9: MissingLibraryRule
// ───────────────────────────────────────────────────────────────────────
class MissingLibraryRuleTest {

    private val rule = MissingLibraryRule()

    @Test fun `cannot find -lssl produces candidate libssl-dev`() {
        val obs = ExecutionObservations.failed(listOf("gcc", "main.c"), "ld: cannot find -lssl")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.LIBRARY_MISSING, match!!.diagnostic.type)
        assertTrue(match.diagnostic.packageCandidates.contains("libssl-dev"))
    }

    @Test fun `undefined reference to -lcrypto produces candidate libssl-dev`() {
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "undefined reference to `EVP_EncryptInit' -lcrypto"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertTrue(match!!.diagnostic.packageCandidates.contains("libssl-dev"))
    }

    @Test fun `no linker error returns null`() {
        val obs = ExecutionObservations.failed(listOf("gcc", "main.c"), "syntax error")
        assertNull(rule.match(obs))
    }

    @Test fun `confidence is MAPPED v0-92`() {
        val obs = ExecutionObservations.failed(listOf("gcc"), "cannot find -lssl")
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.MAPPED.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §12: PythonModuleRule
// ───────────────────────────────────────────────────────────────────────
class PythonModuleRuleTest {

    private val rule = PythonModuleRule()

    @Test fun `module not found produces PACKAGE_MISSING with source PIP`() {
        val obs = ExecutionObservations.failed(
            listOf("python3", "main.py"),
            "ModuleNotFoundError: No module named 'requests'"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.PACKAGE_MISSING, match!!.diagnostic.type)
        assertEquals(DependencySource.PIP, match.diagnostic.source)
    }

    @Test fun `capability is PYTHON_PIP`() {
        val obs = ExecutionObservations.failed(
            listOf("python3"),
            "ModuleNotFoundError: No module named 'requests'"
        )
        val match = rule.match(obs)
        assertEquals(DeveloperCapability.PYTHON_PIP, match!!.diagnostic.capability)
    }

    @Test fun `no module error returns null`() {
        val obs = ExecutionObservations.failed(listOf("python3"), "syntax error")
        assertNull(rule.match(obs))
    }

    @Test fun `module name in evidence`() {
        val obs = ExecutionObservations.failed(
            listOf("python3"),
            "ModuleNotFoundError: No module named 'requests'"
        )
        val match = rule.match(obs)
        assertTrue(match!!.diagnostic.evidence.any { it.contains("requests") })
    }

    @Test fun `confidence is HIGH v0-99`() {
        val obs = ExecutionObservations.failed(
            listOf("python3"),
            "ModuleNotFoundError: No module named 'requests'"
        )
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.HIGH.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §13: NodeModuleRule
// ───────────────────────────────────────────────────────────────────────
class NodeModuleRuleTest {

    private val rule = NodeModuleRule()

    @Test fun `cannot find module produces PACKAGE_MISSING with source NPM`() {
        val obs = ExecutionObservations.failed(
            listOf("node", "app.js"),
            "Error: Cannot find module 'express'"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.PACKAGE_MISSING, match!!.diagnostic.type)
        assertEquals(DependencySource.NPM, match.diagnostic.source)
    }

    @Test fun `capability is NODE_PACKAGE_MANAGER`() {
        val obs = ExecutionObservations.failed(listOf("node"), "Cannot find module 'express'")
        val match = rule.match(obs)
        assertEquals(DeveloperCapability.NODE_PACKAGE_MANAGER, match!!.diagnostic.capability)
    }

    @Test fun `no module error returns null`() {
        val obs = ExecutionObservations.failed(listOf("node"), "syntax error")
        assertNull(rule.match(obs))
    }

    @Test fun `confidence is HIGH v0-99`() {
        val obs = ExecutionObservations.failed(listOf("node"), "Cannot find module 'express'")
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.HIGH.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §15: JavaHomeRule
// ───────────────────────────────────────────────────────────────────────
class JavaEnvironmentRuleTest {

    private val rule = JavaHomeRule()

    @Test fun `JAVA_HOME is not set produces ENVIRONMENT_VARIABLE_MISSING`() {
        val obs = ExecutionObservations.failed(listOf("mvn", "compile"), "Error: JAVA_HOME is not set")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.ENVIRONMENT_VARIABLE_MISSING, match!!.diagnostic.type)
    }

    @Test fun `capability is JAVA_RUNTIME`() {
        val obs = ExecutionObservations.failed(listOf("mvn"), "JAVA_HOME is not set")
        val match = rule.match(obs)
        assertEquals(DeveloperCapability.JAVA_RUNTIME, match!!.diagnostic.capability)
    }

    @Test fun `packageCandidates empty (repair not reinstall)`() {
        val obs = ExecutionObservations.failed(listOf("mvn"), "JAVA_HOME is not set")
        val match = rule.match(obs)
        assertTrue(match!!.diagnostic.packageCandidates.isEmpty())
    }

    @Test fun `no JAVA_HOME returns null`() {
        val obs = ExecutionObservations.failed(listOf("mvn"), "some other error")
        assertNull(rule.match(obs))
    }

    @Test fun `tool is JAVA_HOME`() {
        val obs = ExecutionObservations.failed(listOf("mvn"), "JAVA_HOME is not set")
        val match = rule.match(obs)
        assertEquals("JAVA_HOME", match!!.diagnostic.tool)
    }

    @Test fun `JAVA_HOME is not defined also matches`() {
        val obs = ExecutionObservations.failed(listOf("mvn"), "JAVA_HOME is not defined")
        val match = rule.match(obs)
        assertNotNull(match)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §14: RustToolchainRule (P67 killer feature)
// ───────────────────────────────────────────────────────────────────────
class RustToolchainRuleTest {

    private val rule = RustToolchainRule()

    @Test fun `cargo not found produces RUST_TOOLCHAIN diagnostic with source CARGO`() {
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "cargo: command not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.COMMAND_NOT_FOUND, match!!.diagnostic.type)
        assertEquals(DeveloperCapability.RUST_TOOLCHAIN, match.diagnostic.capability)
        assertEquals(DependencySource.CARGO, match.diagnostic.source)
    }

    @Test fun `linker cc not found produces COMPILER_MISSING`() {
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "error: linker 'cc' not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.COMPILER_MISSING, match!!.diagnostic.type)
    }

    @Test fun `capability is C_COMPILER for linker cc`() {
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "error: linker 'cc' not found")
        val match = rule.match(obs)
        assertEquals(DeveloperCapability.C_COMPILER, match!!.diagnostic.capability)
    }

    @Test fun `candidate gcc for linker cc`() {
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "error: linker 'cc' not found")
        val match = rule.match(obs)
        assertTrue(match!!.diagnostic.packageCandidates.contains("gcc"))
    }

    @Test fun `no rust pattern returns null`() {
        val obs = ExecutionObservations.succeeded(listOf("cargo", "--version"), "cargo 1.74")
        assertNull(rule.match(obs))
    }

    @Test fun `confidence is HIGH v0-99`() {
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "cargo: command not found")
        val match = rule.match(obs)
        assertEquals(DiagnosticConfidence.HIGH.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §33: GoToolchainRule
// ───────────────────────────────────────────────────────────────────────
class GoToolchainRuleTest {

    private val rule = GoToolchainRule()

    @Test fun `GOROOT is not set produces ENVIRONMENT_VARIABLE_MISSING`() {
        val obs = ExecutionObservations.failed(listOf("go", "build"), "GOROOT is not set")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.ENVIRONMENT_VARIABLE_MISSING, match!!.diagnostic.type)
    }

    @Test fun `capability is GO_TOOLCHAIN for GOROOT`() {
        val obs = ExecutionObservations.failed(listOf("go", "build"), "GOROOT is not set")
        val match = rule.match(obs)
        assertEquals(DeveloperCapability.GO_TOOLCHAIN, match!!.diagnostic.capability)
    }

    @Test fun `GOROOT diagnostic has empty package candidates`() {
        val obs = ExecutionObservations.failed(listOf("go", "build"), "GOROOT is not set")
        val match = rule.match(obs)
        assertTrue(match!!.diagnostic.packageCandidates.isEmpty())
    }

    @Test fun `cannot find go package produces PACKAGE_MISSING with source GO`() {
        val obs = ExecutionObservations.failed(
            listOf("go", "build"),
            "main.go:5:2: cannot find package \"github.com/foo/bar\" in any of"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.PACKAGE_MISSING, match!!.diagnostic.type)
        assertEquals(DependencySource.GO, match.diagnostic.source)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §17: ArchitectureRule
// ───────────────────────────────────────────────────────────────────────
class ArchitectureRuleTest {

    private val rule = ArchitectureRule()

    @Test fun `exec format error produces ARCHITECTURE_MISMATCH`() {
        val obs = ExecutionObservations.failed(listOf("./binary"), "bash: ./binary: Exec format error")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.ARCHITECTURE_MISMATCH, match!!.diagnostic.type)
    }

    @Test fun `cannot execute binary file produces ARCHITECTURE_MISMATCH`() {
        val obs = ExecutionObservations.failed(
            listOf("./binary"),
            "bash: ./binary: cannot execute binary file: Exec format error"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticType.ARCHITECTURE_MISMATCH, match!!.diagnostic.type)
    }

    @Test fun `architecture diagnostic has empty package candidates`() {
        val obs = ExecutionObservations.failed(listOf("./binary"), "Exec format error")
        val match = rule.match(obs)
        assertTrue(match!!.diagnostic.packageCandidates.isEmpty())
    }

    @Test fun `no exec format error returns null`() {
        val obs = ExecutionObservations.failed(listOf("./binary"), "some other error")
        assertNull(rule.match(obs))
    }
}

// ───────────────────────────────────────────────────────────────────────
// §11: DiagnosticRuleRegistry
// ───────────────────────────────────────────────────────────────────────
class DiagnosticRuleRegistryTest {

    @Test fun `register rule then all returns it`() {
        val registry = DiagnosticRuleRegistry()
        val rule = CommandNotFoundRule()
        registry.register(rule)
        assertEquals(1, registry.size())
        assertTrue(registry.all().contains(rule))
    }

    @Test fun `find by id returns the rule`() {
        val registry = DiagnosticRuleRegistry()
        val rule = CommandNotFoundRule()
        registry.register(rule)
        assertNotNull(registry.find("command-not-found"))
    }

    @Test fun `withBuiltInRules returns 11 rules`() {
        val registry = DiagnosticRuleRegistry.withBuiltInRules()
        assertEquals(11, registry.size())
    }

    @Test fun `matchAll returns multiple diagnostics for one observation`() {
        val registry = DiagnosticRuleRegistry.withBuiltInRules()
        // cargo command not found fires BOTH CommandNotFoundRule (generic) AND
        // RustToolchainRule (Rust-specific).
        val obs = ExecutionObservations.failed(listOf("cargo", "build"), "cargo: command not found")
        val matches = registry.matchAll(obs)
        assertTrue("expected at least 2 matches, got ${matches.size}", matches.size >= 2)
        assertTrue(matches.any { it.diagnostic.type == DiagnosticType.COMMAND_NOT_FOUND })
        assertTrue(matches.any { it.diagnostic.capability == DeveloperCapability.RUST_TOOLCHAIN })
    }

    @Test fun `matchAll on clean observation returns empty list`() {
        val registry = DiagnosticRuleRegistry.withBuiltInRules()
        val obs = ExecutionObservations.succeeded(listOf("echo", "hi"), "hi")
        val matches = registry.matchAll(obs)
        assertTrue(matches.isEmpty())
    }

    @Test fun `clear removes all rules`() {
        val registry = DiagnosticRuleRegistry.withBuiltInRules()
        assertEquals(11, registry.size())
        registry.clear()
        assertEquals(0, registry.size())
    }
}

// ───────────────────────────────────────────────────────────────────────
// §2, §3, §19, §21, §22, §25: AdaptiveEnvironmentResolver v2
// ───────────────────────────────────────────────────────────────────────
class EnvironmentResolver2Test {

    private val v1 = FakeV1Resolver()
    private val registry = DiagnosticRuleRegistry.withBuiltInRules()
    private val cache = ResolverCache()
    private val resolver = AdaptiveEnvironmentResolverImpl(v1, registry, cache)

    @Test fun `v1 delegation works`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        // (1) v1 pass-through method returns the v1's resolution directly.
        val v1Ctx = EnvironmentResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY
        )
        val r = resolver.resolve(v1Ctx)
        assertEquals(ProvisionPlan.EMPTY, r.plan)
        assertEquals(0, r.missingCapabilities.size)

        // (2) resolveAdaptive with no recent executions returns the baseline plan.
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertEquals(0, ar.plan.packagesToInstall.size)
        assertTrue(ar.converged)
    }

    @Test fun `diagnostics from rules augment plan`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        val obs = ExecutionObservations.failed(listOf("cmake", "--version"), "cmake: command not found")
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY,
            recentExecutions = listOf(obs)
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertTrue(ar.plan.packagesToInstall.any { it.name == "cmake" })
        assertEquals(1, ar.diagnostics.size)
        assertEquals(DiagnosticType.COMMAND_NOT_FOUND, ar.diagnostics[0].type)
        assertFalse(ar.converged)
        assertTrue(ar.delta.addedCapabilities.contains(DeveloperCapability.CMAKE))
    }

    @Test fun `low-confidence diagnostics go to diagnostics list not plan`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        // Unknown header → confidence=LOW (0.55) → goes to lowConf bucket.
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "fatal error: foo.h: No such file or directory"
        )
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY,
            recentExecutions = listOf(obs)
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertEquals(1, ar.diagnostics.size)
        // Low-confidence diagnostic should NOT have added any packages.
        assertEquals(0, ar.plan.packagesToInstall.size)
        assertTrue(ar.diagnostics[0].confidence < DiagnosticConfidence.DEFAULT_THRESHOLD_FLOAT)
    }

    @Test fun `converged when no missing capabilities`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertTrue(ar.converged)
        assertTrue(ar.delta.isEmpty)
    }

    @Test fun `summary contains CONVERGED and PLAN_PKGS`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertTrue(ar.summary.contains("CONVERGED="))
        assertTrue(ar.summary.contains("PLAN_PKGS="))
        assertTrue(ar.summary.contains("DIAGNOSTICS="))
    }

    @Test fun `JAVA_HOME diagnostic becomes SetEnvironmentVariable action`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        val obs = ExecutionObservations.failed(listOf("mvn", "compile"), "JAVA_HOME is not set")
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY,
            recentExecutions = listOf(obs)
        )
        val ar = resolver.resolveAdaptive(ctx)
        val hasSetEnv = ar.plan.actions.any { action ->
            action is ProvisionAction.SetEnvironmentVariable && action.name == "JAVA_HOME"
        }
        assertTrue("expected a SetEnvironmentVariable(JAVA_HOME) action", hasSetEnv)
        // And no packages should be installed (§15: repair env var, not reinstall).
        assertEquals(0, ar.plan.packagesToInstall.size)
    }

    @Test fun `architecture mismatch does NOT add packages to plan`() = runBlocking {
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet()
        )
        val obs = ExecutionObservations.failed(listOf("./binary"), "Exec format error")
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY,
            recentExecutions = listOf(obs)
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertEquals(0, ar.plan.packagesToInstall.size)
        // Arch mismatch → unresolved → not converged.
        assertFalse(ar.converged)
    }

    @Test fun `v1 missing capabilities propagate to delta addedCapabilities`() = runBlocking {
        // v1 says PYTHON_RUNTIME is missing → delta.addedCapabilities contains it.
        v1.resolution = EnvironmentResolution(
            plan = ProvisionPlan(
                requirements = emptyList(),
                packagesToInstall = listOf(PackageSpec("python3")),
                actions = listOf(ProvisionAction.InstallPackage(PackageSpec("python3"))),
                estimatedSize = null,
                requiresNetwork = true
            ),
            missingCapabilities = setOf(DeveloperCapability.PYTHON_RUNTIME),
            satisfiedCapabilities = emptySet()
        )
        val ctx = AdaptiveResolutionContext(
            workspaceId = "ws-1",
            projectRoot = "/tmp",
            projectRequirements = emptyList(),
            environmentSnapshot = EnvironmentSnapshot.EMPTY
        )
        val ar = resolver.resolveAdaptive(ctx)
        assertTrue(ar.delta.addedCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertFalse(ar.converged)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §20: EnvironmentDelta + VersionChange
// ───────────────────────────────────────────────────────────────────────
class EnvironmentDeltaTest {

    @Test fun `delta with added capabilities is not empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = setOf(DeveloperCapability.CMAKE),
            removedCapabilities = emptySet(),
            changedVersions = emptyMap(),
            unresolvedRequirements = emptySet()
        )
        assertFalse(delta.isEmpty)
    }

    @Test fun `delta with no changes is empty (converged)`() {
        val delta = EnvironmentDelta.EMPTY
        assertTrue(delta.isEmpty)
    }

    @Test fun `delta with unresolved requirements is not empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = emptySet(),
            removedCapabilities = emptySet(),
            changedVersions = emptyMap(),
            unresolvedRequirements = setOf("architecture-mismatch: ...")
        )
        assertFalse(delta.isEmpty)
    }

    @Test fun `VersionChange from-to`() {
        val vc = VersionChange(from = "18", to = "20")
        assertEquals("18", vc.from)
        assertEquals("20", vc.to)
    }

    @Test fun `VersionChange isUpgrade`() {
        val vc = VersionChange(from = "18", to = "20")
        assertTrue(vc.isUpgrade)
    }

    @Test fun `VersionChange isInstall`() {
        val vc = VersionChange(from = null, to = "20")
        assertTrue(vc.isInstall)
        assertFalse(vc.isUpgrade)
    }

    @Test fun `VersionChange isRemoval`() {
        val vc = VersionChange(from = "18", to = null)
        assertTrue(vc.isRemoval)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §19: Convergence predicate (separate from delta mechanics)
// ───────────────────────────────────────────────────────────────────────
class ConvergenceTest {

    @Test fun `converged true when delta empty`() {
        val delta = EnvironmentDelta.EMPTY
        val converged = delta.isEmpty
        assertTrue(converged)
    }

    @Test fun `converged false when addedCapabilities non-empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = setOf(DeveloperCapability.CMAKE),
            removedCapabilities = emptySet(),
            changedVersions = emptyMap(),
            unresolvedRequirements = emptySet()
        )
        assertFalse(delta.isEmpty)
    }

    @Test fun `converged false when unresolvedRequirements non-empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = emptySet(),
            removedCapabilities = emptySet(),
            changedVersions = emptyMap(),
            unresolvedRequirements = setOf("architecture-mismatch: ...")
        )
        assertFalse(delta.isEmpty)
    }

    @Test fun `converged false when removedCapabilities non-empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = emptySet(),
            removedCapabilities = setOf(DeveloperCapability.MAKE),
            changedVersions = emptyMap(),
            unresolvedRequirements = emptySet()
        )
        assertFalse(delta.isEmpty)
    }

    @Test fun `converged false when changedVersions non-empty`() {
        val delta = EnvironmentDelta(
            addedCapabilities = emptySet(),
            removedCapabilities = emptySet(),
            changedVersions = mapOf("node" to VersionChange("18", "20")),
            unresolvedRequirements = emptySet()
        )
        assertFalse(delta.isEmpty)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §18: Adaptive Provision Loop
// ───────────────────────────────────────────────────────────────────────
class AdaptiveLoopTest {

    @Test fun `one iteration converges when plan empty`() = runBlocking {
        val resolver = FakeAdaptiveResolver(listOf(
            AdaptiveResolution(
                plan = ProvisionPlan.EMPTY,
                delta = EnvironmentDelta.EMPTY,
                diagnostics = emptyList(),
                converged = true,
                summary = "CONVERGED=true"
            )
        ))
        val provisioner = FakeProvisioner()
        val loop = AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 3)
        val result = loop.run(
            initial = AdaptiveResolutionContext(
                workspaceId = "ws-1",
                projectRoot = "/tmp",
                projectRequirements = emptyList(),
                environmentSnapshot = EnvironmentSnapshot.EMPTY
            )
        ) { _ -> ExecutionObservations.succeeded(listOf("echo", "ok")) }
        assertEquals(1, result.iterations)
        assertTrue(result.converged)
        assertEquals(0, provisioner.provisionCount)
        assertEquals(1, result.history.size)
    }

    @Test fun `three iterations when each adds a capability`() = runBlocking {
        val nonConvergedRes = AdaptiveResolution(
            plan = ProvisionPlan(
                requirements = emptyList(),
                packagesToInstall = listOf(PackageSpec("cmake")),
                actions = listOf(ProvisionAction.InstallPackage(PackageSpec("cmake"))),
                estimatedSize = null,
                requiresNetwork = true
            ),
            delta = EnvironmentDelta(
                addedCapabilities = setOf(DeveloperCapability.CMAKE),
                removedCapabilities = emptySet(),
                changedVersions = emptyMap(),
                unresolvedRequirements = emptySet()
            ),
            diagnostics = emptyList(),
            converged = false,
            summary = "CONVERGED=false ADDED=[CMAKE]"
        )
        val resolver = FakeAdaptiveResolver(listOf(nonConvergedRes, nonConvergedRes, nonConvergedRes))
        val provisioner = FakeProvisioner()
        val loop = AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 3)
        val result = loop.run(
            initial = AdaptiveResolutionContext(
                workspaceId = "ws-1",
                projectRoot = "/tmp",
                projectRequirements = emptyList(),
                environmentSnapshot = EnvironmentSnapshot.EMPTY
            )
        ) { _ -> ExecutionObservations.succeeded(listOf("echo", "ok")) }
        assertEquals(3, result.iterations)
        assertFalse(result.converged)
        assertEquals(3, provisioner.provisionCount)
        assertEquals(3, result.history.size)
    }

    @Test fun `stops at maxIterations 3 even if not converged`() = runBlocking {
        val nonConvergedRes = AdaptiveResolution(
            plan = ProvisionPlan(
                requirements = emptyList(),
                packagesToInstall = listOf(PackageSpec("foo")),
                actions = listOf(ProvisionAction.InstallPackage(PackageSpec("foo"))),
                estimatedSize = null,
                requiresNetwork = true
            ),
            delta = EnvironmentDelta(
                addedCapabilities = setOf(DeveloperCapability.CMAKE),
                removedCapabilities = emptySet(),
                changedVersions = emptyMap(),
                unresolvedRequirements = emptySet()
            ),
            diagnostics = emptyList(),
            converged = false,
            summary = "CONVERGED=false"
        )
        // Provide more than maxIterations to verify the cap actually stops.
        val resolver = FakeAdaptiveResolver(List(10) { nonConvergedRes })
        val provisioner = FakeProvisioner()
        val loop = AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 3)
        val result = loop.run(
            initial = AdaptiveResolutionContext(
                workspaceId = "ws-1",
                projectRoot = "/tmp",
                projectRequirements = emptyList(),
                environmentSnapshot = EnvironmentSnapshot.EMPTY
            )
        ) { _ -> ExecutionObservations.succeeded(listOf("echo")) }
        assertEquals(3, result.iterations)
        assertFalse(result.converged)
        assertTrue(result.hitMaxIterations)
    }

    @Test fun `empty plan breaks early even if not converged`() = runBlocking {
        // Resolver says "not converged" but with an empty plan → loop must
        // break to avoid provisioning an empty plan.
        val res = AdaptiveResolution(
            plan = ProvisionPlan.EMPTY,
            delta = EnvironmentDelta.EMPTY,
            diagnostics = emptyList(),
            converged = false,
            summary = "CONVERGED=false but plan empty"
        )
        val resolver = FakeAdaptiveResolver(List(10) { res })
        val provisioner = FakeProvisioner()
        val loop = AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 5)
        val result = loop.run(
            initial = AdaptiveResolutionContext(
                workspaceId = "ws-1",
                projectRoot = "/tmp",
                projectRequirements = emptyList(),
                environmentSnapshot = EnvironmentSnapshot.EMPTY
            )
        ) { _ -> ExecutionObservations.succeeded(listOf("echo")) }
        assertEquals(1, result.iterations)
        assertEquals(0, provisioner.provisionCount)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §18: Max Iteration cap
// ───────────────────────────────────────────────────────────────────────
class MaxIterationTest {

    @Test fun `maxIterations 2 loop stops after 2`() = runBlocking {
        val nonConvergedRes = AdaptiveResolution(
            plan = ProvisionPlan(
                requirements = emptyList(),
                packagesToInstall = listOf(PackageSpec("foo")),
                actions = listOf(ProvisionAction.InstallPackage(PackageSpec("foo"))),
                estimatedSize = null,
                requiresNetwork = true
            ),
            delta = EnvironmentDelta(
                addedCapabilities = setOf(DeveloperCapability.CMAKE),
                removedCapabilities = emptySet(),
                changedVersions = emptyMap(),
                unresolvedRequirements = emptySet()
            ),
            diagnostics = emptyList(),
            converged = false,
            summary = "CONVERGED=false"
        )
        val resolver = FakeAdaptiveResolver(List(10) { nonConvergedRes })
        val provisioner = FakeProvisioner()
        val loop = AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 2)
        val result = loop.run(
            initial = AdaptiveResolutionContext(
                workspaceId = "ws-1",
                projectRoot = "/tmp",
                projectRequirements = emptyList(),
                environmentSnapshot = EnvironmentSnapshot.EMPTY
            )
        ) { _ -> ExecutionObservations.succeeded(listOf("echo")) }
        assertEquals(2, result.iterations)
        assertFalse(result.converged)
        assertTrue(result.hitMaxIterations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxIterations 0 throws IllegalArgumentException`() {
        val resolver = FakeAdaptiveResolver(emptyList())
        val provisioner = FakeProvisioner()
        AdaptiveProvisionLoop(resolver, provisioner, maxIterations = 0)
    }

    @Test fun `default maxIterations is 3`() {
        assertEquals(3, AdaptiveProvisionLoop.DEFAULT_MAX_ITERATIONS)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §22: Confidence constants
// ───────────────────────────────────────────────────────────────────────
class ConfidenceTest {

    @Test fun `command-not-found confidence is v0-99`() {
        assertEquals(0.99f, DiagnosticConfidence.HIGH.value, 0.001f)
    }

    @Test fun `mapped header confidence is v0-92`() {
        assertEquals(0.92f, DiagnosticConfidence.MAPPED.value, 0.001f)
    }

    @Test fun `medium confidence is v0-75`() {
        assertEquals(0.75f, DiagnosticConfidence.MEDIUM.value, 0.001f)
    }

    @Test fun `unknown header confidence is v0-55`() {
        assertEquals(0.55f, DiagnosticConfidence.LOW.value, 0.001f)
    }

    @Test fun `default threshold is v0-75`() {
        assertEquals(0.75f, DiagnosticConfidence.DEFAULT_THRESHOLD_FLOAT, 0.001f)
    }

    @Test fun `default threshold equals MEDIUM`() {
        assertEquals(DiagnosticConfidence.MEDIUM, DiagnosticConfidence.DEFAULT_THRESHOLD)
    }

    @Test fun `command not found rule uses HIGH confidence`() {
        val rule = CommandNotFoundRule()
        val obs = ExecutionObservations.failed(listOf("cmake"), "cmake: command not found")
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticConfidence.HIGH.value, match!!.diagnostic.confidence, 0.001f)
    }

    @Test fun `unknown header rule uses LOW confidence`() {
        val rule = MissingHeaderRule()
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "fatal error: foo.h: No such file or directory"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticConfidence.LOW.value, match!!.diagnostic.confidence, 0.001f)
    }

    @Test fun `known header rule uses MAPPED confidence`() {
        val rule = MissingHeaderRule()
        val obs = ExecutionObservations.failed(
            listOf("gcc", "main.c"),
            "fatal error: openssl/ssl.h: No such file or directory"
        )
        val match = rule.match(obs)
        assertNotNull(match)
        assertEquals(DiagnosticConfidence.MAPPED.value, match!!.diagnostic.confidence, 0.001f)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §26 + §27: Environment Repair Planner
// ───────────────────────────────────────────────────────────────────────
class EnvironmentRepairTest {

    private val planner = EnvironmentRepairPlanner()

    @Test fun `JAVA_HOME diagnostic produces SET_ENVIRONMENT_VARIABLE`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.ENVIRONMENT_VARIABLE_MISSING,
            tool = "JAVA_HOME",
            packageCandidates = emptyList(),
            capability = DeveloperCapability.JAVA_RUNTIME,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("JAVA_HOME is not set"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.SET_ENVIRONMENT_VARIABLE))
        assertFalse("must NOT reinstall (§15)", plan.actions.contains(RepairAction.REINSTALL_PACKAGE))
    }

    @Test fun `COMMAND_NOT_FOUND produces REINSTALL_PACKAGE`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.COMMAND_NOT_FOUND,
            tool = "cmake",
            packageCandidates = listOf("cmake"),
            capability = DeveloperCapability.CMAKE,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("cmake: command not found"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.REINSTALL_PACKAGE))
    }

    @Test fun `broken apt produces REPAIR_PACKAGE_MANAGER`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.DEPENDENCY_INSTALL_FAILED,
            tool = null,
            packageCandidates = emptyList(),
            capability = null,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("dpkg was interrupted"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.REPAIR_PACKAGE_MANAGER))
    }

    @Test fun `Python venv produces RECREATE_VENV`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.PACKAGE_MISSING,
            tool = "python-module",
            packageCandidates = listOf("requests"),
            capability = DeveloperCapability.PYTHON_PIP,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("ModuleNotFoundError: No module named 'requests'"),
            source = DependencySource.PIP
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.RECREATE_VENV))
        assertTrue(plan.reason.contains(".venv"))
    }

    @Test fun `Node module produces REGENERATE_NODE_MODULES`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.PACKAGE_MISSING,
            tool = "node-module",
            packageCandidates = listOf("express"),
            capability = DeveloperCapability.NODE_PACKAGE_MANAGER,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("Cannot find module 'express'"),
            source = DependencySource.NPM
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.REGENERATE_NODE_MODULES))
        assertTrue(plan.reason.contains("node_modules"))
    }

    @Test fun `Rust crate produces REBUILD_TOOLCHAIN`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.PACKAGE_MISSING,
            tool = "rust-crate",
            packageCandidates = listOf("serde"),
            capability = DeveloperCapability.RUST_TOOLCHAIN,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("could not find serde in registry"),
            source = DependencySource.CARGO
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.REBUILD_TOOLCHAIN))
    }

    @Test fun `architecture mismatch produces no actions`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.ARCHITECTURE_MISMATCH,
            tool = null,
            packageCandidates = emptyList(),
            capability = null,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("Exec format error"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue("arch mismatch must NOT auto-install (§17)", plan.actions.isEmpty())
    }

    @Test fun `PATH_MISCONFIGURED produces FIX_PATH`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.PATH_MISCONFIGURED,
            tool = "PATH",
            packageCandidates = emptyList(),
            capability = null,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("/usr/local/bin not in PATH"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.FIX_PATH))
    }

    @Test fun `GOROOT diagnostic produces SET_ENVIRONMENT_VARIABLE not reinstall`() {
        val d = EnvironmentDiagnostic(
            type = DiagnosticType.ENVIRONMENT_VARIABLE_MISSING,
            tool = "GOROOT",
            packageCandidates = emptyList(),
            capability = DeveloperCapability.GO_TOOLCHAIN,
            confidence = DiagnosticConfidence.HIGH.value,
            evidence = listOf("GOROOT is not set"),
            source = DependencySource.APT
        )
        val plan = planner.planFor(d, "ws-1")
        assertTrue(plan.actions.contains(RepairAction.SET_ENVIRONMENT_VARIABLE))
        assertFalse(plan.actions.contains(RepairAction.REINSTALL_PACKAGE))
    }
}

// ───────────────────────────────────────────────────────────────────────
// §36: Resolver Cache
// ───────────────────────────────────────────────────────────────────────
class ResolverCacheTest {

    @Test fun `get after put round-trips command capability`() {
        val cache = ResolverCache()
        cache.putCommandCapability("cmake", DeveloperCapability.CMAKE)
        assertEquals(DeveloperCapability.CMAKE, cache.getCommandCapability("cmake"))
    }

    @Test fun `get after put round-trips diagnostic candidates`() {
        val cache = ResolverCache()
        cache.putDiagnosticCandidates("COMMAND_NOT_FOUND:cmake", listOf("cmake"))
        val candidates = cache.getDiagnosticCandidates("COMMAND_NOT_FOUND:cmake")
        assertEquals(1, candidates.size)
        assertEquals("cmake", candidates[0])
    }

    @Test fun `unknown command returns null`() {
        val cache = ResolverCache()
        assertNull(cache.getCommandCapability("unknown"))
    }

    @Test fun `unknown signature returns empty list`() {
        val cache = ResolverCache()
        assertTrue(cache.getDiagnosticCandidates("unknown").isEmpty())
    }

    @Test fun `version mismatch invalidates`() {
        val cache = ResolverCache(ResolverCacheVersion.V1)
        cache.putCommandCapability("cmake", DeveloperCapability.CMAKE)
        assertEquals(DeveloperCapability.CMAKE, cache.getCommandCapability("cmake"))
        cache.bumpTo(ResolverCacheVersion("v2"))
        // After bumping to v2, entries inserted under v1 must not surface.
        assertNull(cache.getCommandCapability("cmake"))
    }

    @Test fun `invalidate clears all`() {
        val cache = ResolverCache()
        cache.putCommandCapability("cmake", DeveloperCapability.CMAKE)
        cache.putDiagnosticCandidates("COMMAND_NOT_FOUND:cmake", listOf("cmake"))
        assertEquals(2, cache.size())
        cache.invalidate()
        assertEquals(0, cache.size())
        assertNull(cache.getCommandCapability("cmake"))
        assertTrue(cache.getDiagnosticCandidates("COMMAND_NOT_FOUND:cmake").isEmpty())
    }

    @Test fun `bumpTo changes version`() {
        val cache = ResolverCache(ResolverCacheVersion.V1)
        assertEquals(ResolverCacheVersion.V1, cache.version)
        cache.bumpTo(ResolverCacheVersion("v2"))
        assertEquals(ResolverCacheVersion("v2"), cache.version)
    }

    @Test fun `bumpTo to same version is a no-op`() {
        val cache = ResolverCache(ResolverCacheVersion.V1)
        cache.putCommandCapability("cmake", DeveloperCapability.CMAKE)
        cache.bumpTo(ResolverCacheVersion.V1)
        // Same version → entry still present.
        assertEquals(DeveloperCapability.CMAKE, cache.getCommandCapability("cmake"))
    }

    @Test fun `default version is V1`() {
        val cache = ResolverCache()
        assertEquals(ResolverCacheVersion.V1, cache.version)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §34: Provenance Store
// ───────────────────────────────────────────────────────────────────────
class ProvenanceStoreTest {

    @Test fun `record then get`() {
        val store = ProvenanceStore()
        val p = CapabilityProvenance(
            capability = DeveloperCapability.CMAKE,
            source = DependencySource.APT,
            packageName = "cmake",
            version = "3.27",
            installedBy = "EnvironmentProvisioner",
            workspace = "ws-1"
        )
        store.record("ws-1", p)
        assertEquals(p, store.get("ws-1", DeveloperCapability.CMAKE))
    }

    @Test fun `all returns map per workspace`() {
        val store = ProvenanceStore()
        val p1 = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "p", "ws-1"
        )
        val p2 = CapabilityProvenance(
            DeveloperCapability.NODE_RUNTIME, DependencySource.APT,
            "nodejs", "20", "p", "ws-1"
        )
        store.record("ws-1", p1)
        store.record("ws-1", p2)
        val all = store.all("ws-1")
        assertEquals(2, all.size)
        assertEquals(p1, all[DeveloperCapability.CMAKE])
        assertEquals(p2, all[DeveloperCapability.NODE_RUNTIME])
    }

    @Test fun `unknown capability returns null`() {
        val store = ProvenanceStore()
        assertNull(store.get("ws-1", DeveloperCapability.CMAKE))
    }

    @Test fun `unknown workspace returns empty map`() {
        val store = ProvenanceStore()
        assertEquals(0, store.all("does-not-exist").size)
    }

    @Test fun `workspaces are isolated`() {
        val store = ProvenanceStore()
        val p1 = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "p", "ws-1"
        )
        store.record("ws-1", p1)
        assertNull(store.get("ws-2", DeveloperCapability.CMAKE))
        assertEquals(0, store.all("ws-2").size)
    }

    @Test fun `remove capability removes entry`() {
        val store = ProvenanceStore()
        val p = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "p", "ws-1"
        )
        store.record("ws-1", p)
        assertEquals(p, store.remove("ws-1", DeveloperCapability.CMAKE))
        assertNull(store.get("ws-1", DeveloperCapability.CMAKE))
    }

    @Test fun `clearWorkspace removes all entries for that workspace`() {
        val store = ProvenanceStore()
        val p1 = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "p", "ws-1"
        )
        val p2 = CapabilityProvenance(
            DeveloperCapability.NODE_RUNTIME, DependencySource.APT,
            "nodejs", "20", "p", "ws-2"
        )
        store.record("ws-1", p1)
        store.record("ws-2", p2)
        store.clearWorkspace("ws-1")
        assertEquals(0, store.all("ws-1").size)
        assertEquals(1, store.all("ws-2").size)
    }

    @Test fun `workspaceIds lists all workspaces`() {
        val store = ProvenanceStore()
        val p1 = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "p", "ws-1"
        )
        val p2 = CapabilityProvenance(
            DeveloperCapability.NODE_RUNTIME, DependencySource.APT,
            "nodejs", "20", "p", "ws-2"
        )
        store.record("ws-1", p1)
        store.record("ws-2", p2)
        val ids = store.workspaceIds()
        assertTrue(ids.contains("ws-1"))
        assertTrue(ids.contains("ws-2"))
    }

    @Test fun `isWorkspaceScoped is true for non-APT sources`() {
        val p = CapabilityProvenance(
            DeveloperCapability.PYTHON_PIP, DependencySource.PIP,
            "requests", "2.31", "PythonEnvironmentProvider", "ws-1"
        )
        assertTrue(p.isWorkspaceScoped)
    }

    @Test fun `isWorkspaceScoped is false for APT source`() {
        val p = CapabilityProvenance(
            DeveloperCapability.CMAKE, DependencySource.APT,
            "cmake", "3.27", "EnvironmentProvisioner", "ws-1"
        )
        assertFalse(p.isWorkspaceScoped)
    }
}

// ───────────────────────────────────────────────────────────────────────
// Fakes + helpers (used by the tests above)
// ───────────────────────────────────────────────────────────────────────

/** Minimal v1 fake: returns whatever the test assigns to `resolution`. */
class FakeV1Resolver(
    var resolution: EnvironmentResolution = EnvironmentResolution(
        plan = ProvisionPlan.EMPTY,
        missingCapabilities = emptySet(),
        satisfiedCapabilities = emptySet(),
        incompatibleCapabilities = emptySet()
    )
) : EnvironmentResolver {
    override suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution = resolution
}

/** Minimal provisioner fake: just counts provision() calls. */
class FakeProvisioner : EnvironmentProvisioner {
    var provisionCount: Int = 0
        private set
    private val _events = MutableSharedFlow<EnvironmentEvent>(replay = 0, extraBufferCapacity = 256)

    override suspend fun provision(plan: ProvisionPlan, workspaceId: String): ProvisionResult {
        provisionCount += 1
        return ProvisionResult.EMPTY
    }

    override fun events(): Flow<EnvironmentEvent> = _events.asSharedFlow()
}

/** Minimal adaptive-resolver fake: returns a sequence of preset resolutions. */
class FakeAdaptiveResolver(
    private val resolutions: List<AdaptiveResolution>
) : AdaptiveEnvironmentResolver {
    private var index = 0
    var callCount: Int = 0
        private set

    override suspend fun resolveAdaptive(context: AdaptiveResolutionContext): AdaptiveResolution {
        val r = resolutions.getOrElse(index) { resolutions.last() }
        index += 1
        callCount += 1
        return r
    }

    override suspend fun resolve(context: EnvironmentResolutionContext): EnvironmentResolution =
        EnvironmentResolution(
            plan = ProvisionPlan.EMPTY,
            missingCapabilities = emptySet(),
            satisfiedCapabilities = emptySet(),
            incompatibleCapabilities = emptySet()
        )
}
