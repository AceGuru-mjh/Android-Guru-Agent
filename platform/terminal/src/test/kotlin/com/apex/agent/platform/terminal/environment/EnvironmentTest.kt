package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageErrorCode
import com.apex.agent.platform.terminal.pkg.PackageInfo
import com.apex.agent.platform.terminal.pkg.PackageInstallOptions
import com.apex.agent.platform.terminal.pkg.PackageOperation
import com.apex.agent.platform.terminal.pkg.PackageOperationError
import com.apex.agent.platform.terminal.pkg.PackageOperationEvent
import com.apex.agent.platform.terminal.pkg.PackageOperationResult
import com.apex.agent.platform.terminal.pkg.PackageOperationState
import com.apex.agent.platform.terminal.pkg.PackageOperationType
import com.apex.agent.platform.terminal.pkg.PackageRemoveOptions
import com.apex.agent.platform.terminal.pkg.PackageSearchResult
import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.pkg.PackageUpdateOptions
import com.apex.agent.platform.terminal.pkg.PackageUpgradeOptions
import com.apex.agent.platform.terminal.pkg.PackageManagerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * PR #66: Environment layer tests.
 *
 * JUnit 4 + pure-JVM (no Android imports). Test method names use `-` rather
 * than `/` or `.` to comply with the repo's static-analysis rule
 * (see PR #60 / #61 history).
 *
 * Coverage map:
 *   - EnvironmentProfileRegistryTest          → §12 (built-in 6 profiles)
 *   - ProjectEnvironmentAnalyzerTest          → §6, §7, §28 (markers, boundary)
 *   - EnvironmentResolverTest                 → §8, §10, §11, §15 (dedup)
 *   - EnvironmentProvisionerTest             → §13, §17, §20, §22
 *   - EnvironmentManagerTest                  → §16, §18, §20
 *   - EnvironmentSnapshotCacheTest            → §19 (fresh / stale)
 */

// ───────────────────────────────────────────────────────────────────────
// §12: EnvironmentProfileRegistry
// ───────────────────────────────────────────────────────────────────────
class EnvironmentProfileRegistryTest {

    private val registry: EnvironmentProfileRegistry = BuiltInProfileRegistry()

    @Test fun `profile registry has 6 profiles`() {
        assertEquals(6, registry.all().size)
    }

    @Test fun `find by id returns correct profile`() {
        assertNotNull(registry.find(ProfileIds.PYTHON))
        assertEquals(ProfileIds.PYTHON, registry.find(ProfileIds.PYTHON)?.id)
        assertNotNull(registry.find(ProfileIds.NODE))
        assertNotNull(registry.find(ProfileIds.JDK))
        assertNotNull(registry.find(ProfileIds.CPP))
        assertNotNull(registry.find(ProfileIds.RUST))
        assertNotNull(registry.find(ProfileIds.GO))
    }

    @Test fun `find unknown id returns null`() {
        assertNull(registry.find("flutter"))
        assertNull(registry.find(""))
        assertNull(registry.find("PYTHON"))
    }

    @Test fun `python profile declares PYTHON_RUNTIME and PYTHON_PIP`() {
        val python = registry.find(ProfileIds.PYTHON)!!
        val caps = python.requirements.flatMap { it.capabilities }.toSet()
        assertTrue(caps.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertTrue(caps.contains(DeveloperCapability.PYTHON_PIP))
    }

    @Test fun `cpp profile declares C_COMPILER and CPP_COMPILER and MAKE and CMAKE`() {
        val cpp = registry.find(ProfileIds.CPP)!!
        val caps = cpp.requirements.flatMap { it.capabilities }.toSet()
        assertTrue(caps.contains(DeveloperCapability.C_COMPILER))
        assertTrue(caps.contains(DeveloperCapability.CPP_COMPILER))
        assertTrue(caps.contains(DeveloperCapability.MAKE))
        assertTrue(caps.contains(DeveloperCapability.CMAKE))
    }

    @Test fun `jdk profile declares JAVA_RUNTIME and JAVAC`() {
        val jdk = registry.find(ProfileIds.JDK)!!
        val caps = jdk.requirements.flatMap { it.capabilities }.toSet()
        assertTrue(caps.contains(DeveloperCapability.JAVA_RUNTIME))
        assertTrue(caps.contains(DeveloperCapability.JAVAC))
    }

    @Test fun `version constraints present on Python Node JDK Rust Go`() {
        val constraints = listOf(
            ProfileIds.PYTHON to "3.11",
            ProfileIds.NODE to "20",
            ProfileIds.JDK to "21",
            ProfileIds.RUST to "1.72",
            ProfileIds.GO to "1.22"
        )
        for ((id, expected) in constraints) {
            val profile = registry.find(id)!!
            val req = profile.requirements.first { it.versionConstraint != null }
            assertEquals(expected, req.versionConstraint!!.version)
            assertEquals(VersionOperator.GE, req.versionConstraint!!.operator)
        }
    }

    @Test fun `register adds new profile without touching built-ins`() {
        val before = registry.all().size
        val custom = EnvironmentProfile(
            id = "custom-x",
            version = "0.1",
            requirements = emptyList()
        )
        registry.register(custom)
        assertEquals(before + 1, registry.all().size)
        assertSame(custom, registry.find("custom-x"))
        assertNotNull(registry.find(ProfileIds.PYTHON))
    }

    @Test fun `python profile lists multiple packages for toolchain separation`() {
        val python = registry.find(ProfileIds.PYTHON)!!
        val req = python.requirements.first()
        val names = req.packages.map { it.name }
        assertTrue(names.contains("python3"))
        assertTrue(names.contains("python3-pip"))
        assertTrue(names.contains("python3-venv"))
        assertTrue(names.contains("python3-dev"))
        assertTrue("§11 toolchain vs package separation: >1 package", names.size > 1)
    }

    @Test fun `cpp profile lists all expected toolchain packages`() {
        val cpp = registry.find(ProfileIds.CPP)!!
        val allNames = cpp.requirements.flatMap { it.packages.map { p -> p.name } }
        assertTrue(allNames.contains("gcc"))
        assertTrue(allNames.contains("g++"))
        assertTrue(allNames.contains("make"))
        assertTrue(allNames.contains("cmake"))
        assertTrue(allNames.contains("pkg-config"))
    }

    @Test fun `jdk profile includes a JAVA_HOME env-var requirement with no packages`() {
        val jdk = registry.find(ProfileIds.JDK)!!
        // Two requirements: jdk (with default-jdk) + java-home (no packages)
        assertEquals(2, jdk.requirements.size)
        val javaHome = jdk.requirements.first { it.id == "java-home" }
        assertTrue(javaHome.packages.isEmpty())
        assertTrue(javaHome.capabilities.isEmpty())
        assertTrue(javaHome.detection.command.startsWith("ENV:"))
    }
}

// ───────────────────────────────────────────────────────────────────────
// §6, §7, §28: ProjectEnvironmentAnalyzer
// ───────────────────────────────────────────────────────────────────────
class ProjectEnvironmentAnalyzerTest {

    private val registry: EnvironmentProfileRegistry = BuiltInProfileRegistry()
    private val analyzer = DefaultProjectEnvironmentAnalyzer(registry)

    private fun tempDir(): Path = Files.createTempDirectory("p66-analyzer-")

    private fun writeFile(dir: Path, name: String): Path =
        Files.createFile(dir.resolve(name))

    @Test fun `detects Python from requirements-txt`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "requirements.txt")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.PYTHON))
        assertTrue(analysis.requirements.isNotEmpty())
    }

    @Test fun `detects Python from pyproject-toml`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "pyproject.toml")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.PYTHON))
    }

    @Test fun `detects Node from package-json`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "package.json")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.NODE))
    }

    @Test fun `detects Java from pom-xml`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "pom.xml")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.JDK))
    }

    @Test fun `detects Cpp from CMakeLists-txt`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "CMakeLists.txt")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.CPP))
    }

    @Test fun `detects Cpp from a cpp source file`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "main.cpp")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.CPP))
    }

    @Test fun `detects Rust from Cargo-toml`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "Cargo.toml")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.RUST))
    }

    @Test fun `detects Go from go-mod`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "go.mod")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.GO))
    }

    @Test fun `detects multiple languages in one project`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "package.json")
        writeFile(dir, "requirements.txt")
        writeFile(dir, "Cargo.toml")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.PYTHON))
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.NODE))
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.RUST))
        assertEquals(3, analysis.detectedLanguages.size)
    }

    @Test fun `lockfiles recorded separately from requirements`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "package.json")
        writeFile(dir, "package-lock.json")
        writeFile(dir, "Cargo.toml")
        writeFile(dir, "Cargo.lock")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.lockfiles.contains("package-lock.json"))
        assertTrue(analysis.lockfiles.contains("Cargo.lock"))
        // Non-lockfile markers should NOT appear in lockfiles.
        assertFalse(analysis.lockfiles.contains("package.json"))
        assertFalse(analysis.lockfiles.contains("Cargo.toml"))
    }

    @Test fun `lockfile alone still triggers profile`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "package-lock.json")
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.NODE))
        assertTrue(analysis.lockfiles.contains("package-lock.json"))
    }

    @Test fun `analyzer never installs anything - returns requirements only`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "requirements.txt")
        val analysis = analyzer.analyze(dir.toString())
        // No side effects on filesystem (only the temp file we created).
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.PYTHON))
        assertTrue(analysis.requirements.all { it is EnvironmentRequirement })
        // Directory must still exist untouched.
        assertTrue(Files.isDirectory(dir))
        assertTrue(Files.exists(dir.resolve("requirements.txt")))
    }

    @Test fun `analyzer on missing path returns empty analysis`() = runBlocking {
        val analysis = analyzer.analyze("/nonexistent/path/that/should/not/exist")
        assertTrue(analysis.detectedLanguages.isEmpty())
        assertTrue(analysis.requirements.isEmpty())
        assertTrue(analysis.lockfiles.isEmpty())
    }

    @Test fun `analyzer dedups profile requirements when multiple markers match`() = runBlocking {
        val dir = tempDir()
        writeFile(dir, "requirements.txt")
        writeFile(dir, "pyproject.toml")
        writeFile(dir, "setup.py")
        val analysis = analyzer.analyze(dir.toString())
        // Python profile contributes ONE requirement (python3) despite
        // three matching markers.
        assertEquals(1, analysis.requirements.size)
        assertEquals(ProfileIds.PYTHON, analysis.requirements.first().id)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §8, §10, §11, §15: EnvironmentResolver
// ───────────────────────────────────────────────────────────────────────
class EnvironmentResolverTest {

    private val resolver = DefaultEnvironmentResolver()
    private val registry: EnvironmentProfileRegistry = BuiltInProfileRegistry()

    private fun snapshot(
        tools: Map<String, ToolRecord> = emptyMap(),
        capabilities: Map<DeveloperCapability, EnvironmentState> = emptyMap(),
        generatedAt: Long = System.currentTimeMillis()
    ): EnvironmentSnapshot = EnvironmentSnapshot(tools, capabilities, generatedAt)

    private fun pythonRequirement() =
        registry.find(ProfileIds.PYTHON)!!.requirements.first()

    private fun ctx(
        reqs: List<EnvironmentRequirement>,
        snap: EnvironmentSnapshot,
        workspaceId: String = "ws-test"
    ): EnvironmentResolutionContext = EnvironmentResolutionContext(
        workspaceId = workspaceId,
        projectRoot = "/tmp/project",
        projectRequirements = reqs,
        environmentSnapshot = snap
    )

    @Test fun `empty snapshot means all capabilities missing`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val snap = snapshot(capabilities = emptyMap())
        val res = resolver.resolve(ctx(reqs, snap))
        assertTrue(res.missingCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertTrue(res.missingCapabilities.contains(DeveloperCapability.PYTHON_PIP))
        assertTrue(res.satisfiedCapabilities.isEmpty())
        assertFalse(res.plan.packagesToInstall.isEmpty())
    }

    @Test fun `full snapshot means all satisfied`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val snap = snapshot(
            capabilities = mapOf(
                DeveloperCapability.PYTHON_RUNTIME to EnvironmentState.READY,
                DeveloperCapability.PYTHON_PIP to EnvironmentState.READY
            ),
            tools = mapOf(
                "python3" to ToolRecord(
                    command = "python3",
                    path = "/usr/bin/python3",
                    version = "3.12.3",
                    capability = DeveloperCapability.PYTHON_RUNTIME
                )
            )
        )
        val res = resolver.resolve(ctx(reqs, snap))
        assertTrue(res.satisfiedCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertTrue(res.satisfiedCapabilities.contains(DeveloperCapability.PYTHON_PIP))
        assertTrue(res.missingCapabilities.isEmpty())
        assertTrue(res.plan.packagesToInstall.isEmpty())
        assertFalse(res.plan.requiresNetwork)
    }

    @Test fun `partial snapshot means only missing capabilities are listed`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val snap = snapshot(
            capabilities = mapOf(
                DeveloperCapability.PYTHON_RUNTIME to EnvironmentState.READY,
                DeveloperCapability.PYTHON_PIP to EnvironmentState.MISSING
            ),
            tools = mapOf(
                "python3" to ToolRecord(
                    command = "python3", path = null, version = "3.12.3",
                    capability = DeveloperCapability.PYTHON_RUNTIME
                )
            )
        )
        val res = resolver.resolve(ctx(reqs, snap))
        assertTrue(res.satisfiedCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertTrue(res.missingCapabilities.contains(DeveloperCapability.PYTHON_PIP))
    }

    @Test fun `version constraint GE 3-11 satisfied by 3-12`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val snap = snapshot(
            capabilities = mapOf(
                DeveloperCapability.PYTHON_RUNTIME to EnvironmentState.READY,
                DeveloperCapability.PYTHON_PIP to EnvironmentState.READY
            ),
            tools = mapOf(
                "python3" to ToolRecord(
                    command = "python3", path = null, version = "3.12.3",
                    capability = DeveloperCapability.PYTHON_RUNTIME
                )
            )
        )
        val res = resolver.resolve(ctx(reqs, snap))
        assertFalse(res.hasIncompatible)
        assertTrue(res.satisfiedCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
    }

    @Test fun `version constraint GE 3-11 not satisfied by 3-10 marks INCOMPATIBLE`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val snap = snapshot(
            capabilities = mapOf(
                DeveloperCapability.PYTHON_RUNTIME to EnvironmentState.READY,
                DeveloperCapability.PYTHON_PIP to EnvironmentState.READY
            ),
            tools = mapOf(
                "python3" to ToolRecord(
                    command = "python3", path = null, version = "3.10.2",
                    capability = DeveloperCapability.PYTHON_RUNTIME
                )
            )
        )
        val res = resolver.resolve(ctx(reqs, snap))
        assertTrue(res.hasIncompatible)
        assertTrue(res.incompatibleCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        assertTrue(res.missingCapabilities.contains(DeveloperCapability.PYTHON_RUNTIME))
        // Pip has no version constraint — should be satisfied.
        assertTrue(res.satisfiedCapabilities.contains(DeveloperCapability.PYTHON_PIP))
    }

    @Test fun `packages are deduplicated across requirements`() = runBlocking {
        val sharedPkg = PackageSpec("shared-pkg")
        val otherPkg = PackageSpec("other-pkg")
        val thirdPkg = PackageSpec("third-pkg")
        val reqs = listOf(
            EnvironmentRequirement(
                id = "req-1",
                displayName = "Req 1",
                detection = DetectionSpec.command("tool1"),
                packages = listOf(sharedPkg, otherPkg),
                capabilities = setOf(DeveloperCapability.C_COMPILER)
            ),
            EnvironmentRequirement(
                id = "req-2",
                displayName = "Req 2",
                detection = DetectionSpec.command("tool2"),
                packages = listOf(sharedPkg, thirdPkg),
                capabilities = setOf(DeveloperCapability.CPP_COMPILER)
            )
        )
        val snap = snapshot(capabilities = emptyMap())
        val res = resolver.resolve(ctx(reqs, snap))
        val names = res.plan.packagesToInstall.map { it.name }
        // shared-pkg appears once even though both requirements listed it.
        assertEquals(1, names.count { it == "shared-pkg" })
        assertTrue(names.contains("other-pkg"))
        assertTrue(names.contains("third-pkg"))
        assertEquals(3, names.size)
    }

    @Test fun `JAVA_HOME env requirement becomes SetEnvironmentVariable action`() = runBlocking {
        val reqs = registry.find(ProfileIds.JDK)!!.requirements
        val snap = snapshot(
            capabilities = mapOf(
                DeveloperCapability.JAVA_RUNTIME to EnvironmentState.READY,
                DeveloperCapability.JAVAC to EnvironmentState.READY
            ),
            tools = mapOf(
                "java" to ToolRecord(
                    command = "java", path = null, version = "21",
                    capability = DeveloperCapability.JAVA_RUNTIME
                )
            )
        )
        val res = resolver.resolve(ctx(reqs, snap))
        val envAction = res.plan.actions
            .filterIsInstance<ProvisionAction.SetEnvironmentVariable>()
            .firstOrNull()
        assertNotNull(envAction)
        assertEquals(EnvironmentVars.JAVA_HOME, envAction!!.name)
        assertTrue(envAction.value.isNotEmpty())
    }

    @Test fun `stale snapshot is still consumed by resolver - freshness is callers job`() = runBlocking {
        val reqs = listOf(pythonRequirement())
        val staleSnap = snapshot(capabilities = emptyMap(), generatedAt = 0L)
        // Resolver itself does not check freshness; the caller does,
        // via EnvironmentSnapshotCache. Verify resolver still produces a plan.
        val res = resolver.resolve(ctx(reqs, staleSnap))
        assertTrue(res.hasMissing)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §13, §17, §20, §22: Environment Provisioner
// ───────────────────────────────────────────────────────────────────────
class EnvironmentProvisionerTest {

    private fun emptyPlan(): ProvisionPlan = ProvisionPlan.EMPTY
    private fun planWith(vararg pkgs: String): ProvisionPlan {
        val specs = pkgs.map { PackageSpec(it) }
        val reqs = listOf(
            EnvironmentRequirement(
                id = "test-req",
                displayName = "Test",
                detection = DetectionSpec.command("test"),
                packages = specs,
                capabilities = setOf(DeveloperCapability.PYTHON_RUNTIME)
            )
        )
        return ProvisionPlan(
            requirements = reqs,
            packagesToInstall = specs,
            actions = specs.map { ProvisionAction.InstallPackage(it) },
            estimatedSize = null,
            requiresNetwork = true
        )
    }

    @Test fun `empty plan returns success with no installs`() = runBlocking {
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        val result = prov.provision(emptyPlan(), "ws-empty")
        assertTrue(result.succeeded)
        assertTrue(result.installedPackages.isEmpty())
        assertNull(result.error)
    }

    @Test fun `plan with 2 packages installs both`() = runBlocking {
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        val result = prov.provision(planWith("gcc", "make"), "ws-2")
        assertTrue(result.succeeded)
        assertEquals(2, result.installedPackages.size)
        assertTrue(result.installedPackages.contains("gcc"))
        assertTrue(result.installedPackages.contains("make"))
    }

    @Test fun `failed install returns succeeded-false`() = runBlocking {
        val pm = StubPackageManager(succeeds = false)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        val result = prov.provision(planWith("rustc", "cargo"), "ws-fail")
        assertFalse(result.succeeded)
        assertNotNull(result.error)
    }

    @Test fun `SetEnvironmentVariable action applied via EnvironmentManager`() = runBlocking {
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        val plan = ProvisionPlan(
            requirements = emptyList(),
            packagesToInstall = emptyList(),
            actions = listOf(
                ProvisionAction.SetEnvironmentVariable(EnvironmentVars.JAVA_HOME, "/jvm/default"),
                ProvisionAction.PrependPath("/usr/local/bin")
            ),
            estimatedSize = null,
            requiresNetwork = false
        )
        val result = prov.provision(plan, "ws-env")
        assertTrue(result.succeeded)
        assertEquals("/jvm/default", em.variables("ws-env")[EnvironmentVars.JAVA_HOME])
        assertEquals("/usr/local/bin", em.path("ws-env").first())
    }

    @Test fun `events flow emits Installing then Verifying then Ready`() = runBlocking {
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        val captured = mutableListOf<EnvironmentEvent>()

        // Subscribe BEFORE provision so the SharedFlow has a live collector.
        val collectorJob = launch {
            prov.events().collect { captured.add(it) }
        }
        // Let the collector register on the SharedFlow.
        delay(50)

        prov.provision(planWith("gcc"), "ws-events")
        // Allow the collector to drain the buffered events.
        delay(150)
        collectorJob.cancel()

        assertTrue("must emit Installing", captured.any { it is EnvironmentEvent.Installing })
        assertTrue("must emit Verifying", captured.any { it is EnvironmentEvent.Verifying })
        assertTrue("must emit Ready", captured.any { it is EnvironmentEvent.Ready })
    }

    @Test fun `provisioner routes installs through LinuxPackageManager not apt`() = runBlocking {
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val prov = LinuxEnvironmentProvisioner(pm, em)
        prov.provision(planWith("python3"), "ws-bound")
        // §22/§24 boundary: install was invoked exactly once on the PM.
        assertEquals(1, pm.installInvocationCount)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §16, §18, §20: EnvironmentManager
// ───────────────────────────────────────────────────────────────────────
class EnvironmentManagerTest {

    @Test fun `set and get environment variable`() {
        val em = EnvironmentManager()
        em.set("ws-1", EnvironmentVars.JAVA_HOME, "/usr/lib/jvm/default-java")
        assertEquals("/usr/lib/jvm/default-java", em.variables("ws-1")[EnvironmentVars.JAVA_HOME])
    }

    @Test fun `prependPath adds to front of ordered list`() {
        val em = EnvironmentManager()
        em.prependPath("ws-1", "/first")
        em.prependPath("ws-1", "/second")
        val path = em.path("ws-1")
        assertEquals(listOf("/second", "/first"), path)
    }

    @Test fun `prependPath dedups existing entry`() {
        val em = EnvironmentManager()
        em.prependPath("ws-1", "/a")
        em.prependPath("ws-1", "/b")
        em.prependPath("ws-1", "/a")  // re-prepend
        val path = em.path("ws-1")
        assertEquals(listOf("/a", "/b"), path)
    }

    @Test fun `cache entry round-trips via recordCache`() {
        val em = EnvironmentManager()
        val caps = setOf(DeveloperCapability.PYTHON_RUNTIME, DeveloperCapability.PYTHON_PIP)
        val versions = mapOf("python3" to "3.12.3")
        em.recordCache("ws-1", caps, versions)
        val entry = em.cacheEntry("ws-1")
        assertNotNull(entry)
        assertEquals(caps, entry!!.capabilities)
        assertEquals("3.12.3", entry.versions["python3"])
    }

    @Test fun `different workspaces are isolated`() {
        val em = EnvironmentManager()
        em.set("ws-a", "VAR", "value-a")
        em.set("ws-b", "VAR", "value-b")
        assertEquals("value-a", em.variables("ws-a")["VAR"])
        assertEquals("value-b", em.variables("ws-b")["VAR"])
        em.prependPath("ws-a", "/a-path")
        em.prependPath("ws-b", "/b-path")
        assertTrue(em.path("ws-a").contains("/a-path"))
        assertTrue(em.path("ws-b").contains("/b-path"))
        assertFalse(em.path("ws-a").contains("/b-path"))
        assertFalse(em.path("ws-b").contains("/a-path"))
    }

    @Test fun `snapshot reflects workspace state`() {
        val em = EnvironmentManager()
        em.set("ws-1", EnvironmentVars.JAVA_HOME, "/jvm")
        em.recordCache("ws-1", setOf(DeveloperCapability.JAVA_RUNTIME), mapOf("java" to "21"))
        val snap = em.snapshot()
        assertTrue(snap.containsKey("ws-1"))
        val ws = snap["ws-1"]!!
        assertEquals("ws-1", ws.workspaceId)
        assertTrue(ws.installedCapabilities.contains(DeveloperCapability.JAVA_RUNTIME))
        assertEquals("21", ws.versions["java"])
    }

    @Test fun `workspaceIds lists all known workspaces`() {
        val em = EnvironmentManager()
        em.set("ws-1", "V", "1")
        em.set("ws-2", "V", "2")
        em.set("ws-3", "V", "3")
        assertEquals(setOf("ws-1", "ws-2", "ws-3"), em.workspaceIds())
        em.remove("ws-2")
        assertEquals(setOf("ws-1", "ws-3"), em.workspaceIds())
    }
}

// ───────────────────────────────────────────────────────────────────────
// §19: EnvironmentSnapshotCache
// ───────────────────────────────────────────────────────────────────────
class EnvironmentSnapshotCacheTest {

    @Test fun `fresh snapshot is returned by get`() {
        val cache = EnvironmentSnapshotCache()
        val snap = EnvironmentSnapshot(
            tools = emptyMap(),
            capabilities = mapOf(
                DeveloperCapability.PYTHON_RUNTIME to EnvironmentState.READY
            ),
            generatedAt = System.currentTimeMillis()
        )
        cache.set(snap)
        val out = cache.get()
        assertSame(snap, out)
        assertTrue(cache.isPresent)
    }

    @Test fun `stale snapshot returns null after TTL`() {
        val cache = EnvironmentSnapshotCache()
        // generatedAt=0 forces (now - 0) >> FRESH_TTL_MS.
        val stale = EnvironmentSnapshot(
            tools = emptyMap(),
            capabilities = emptyMap(),
            generatedAt = 0L
        )
        cache.set(stale)
        val out = cache.get()
        assertNull(out)
        // isPresent still true (something is cached) — caller may clear().
        assertTrue(cache.isPresent)
        cache.clear()
        assertFalse(cache.isPresent)
    }

    @Test fun `empty cache returns null and is not present`() {
        val cache = EnvironmentSnapshotCache()
        assertNull(cache.get())
        assertFalse(cache.isPresent)
    }

    @Test fun `snapshot freshness flag matches 5 minute TTL`() {
        // §19: 5 min TTL encoded in EnvironmentSnapshot.FRESH_TTL_MS.
        assertEquals(5 * 60 * 1000L, EnvironmentSnapshot.FRESH_TTL_MS)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §22: Boundary tests — confirm NO types exist for the forbidden surface.
// ───────────────────────────────────────────────────────────────────────
class EnvironmentBoundaryTest {

    @Test fun `no Docker type exists in environment package`() {
        val cls = try { Class.forName("com.apex.agent.platform.terminal.environment.Docker") } catch (_: Throwable) { null }
        assertNull("§22: P66 must NOT define Docker types", cls)
    }

    @Test fun `no Kubernetes type exists in environment package`() {
        val cls = try { Class.forName("com.apex.agent.platform.terminal.environment.Kubernetes") } catch (_: Throwable) { null }
        assertNull("§22: P66 must NOT define Kubernetes types", cls)
    }

    @Test fun `no FlutterSdk type exists in environment package`() {
        val cls = try { Class.forName("com.apex.agent.platform.terminal.environment.FlutterSdk") } catch (_: Throwable) { null }
        assertNull("§22: P66 must NOT define Flutter-SDK-full types", cls)
    }

    @Test fun `no ContainerIsolation type exists in environment package`() {
        val cls = try { Class.forName("com.apex.agent.platform.terminal.environment.ContainerIsolation") } catch (_: Throwable) { null }
        assertNull("§22: P66 must NOT define container-isolation types", cls)
    }
}

// ───────────────────────────────────────────────────────────────────────
// §13: End-to-end Agent auto-install flow (analyze → resolve → provision).
// ───────────────────────────────────────────────────────────────────────
class EnvironmentAutoInstallFlowTest {

    @Test fun `analyze-then-resolve-then-provision for Python project`() = runBlocking {
        val registry: EnvironmentProfileRegistry = BuiltInProfileRegistry()
        val analyzer = DefaultProjectEnvironmentAnalyzer(registry)
        val resolver = DefaultEnvironmentResolver()
        val pm = StubPackageManager(succeeds = true)
        val em = EnvironmentManager()
        val provisioner = LinuxEnvironmentProvisioner(pm, em)

        // 1. Analyze — set up a temp project dir with a Python marker.
        val dir = Files.createTempDirectory("p66-flow-")
        Files.createFile(dir.resolve("requirements.txt"))
        val analysis = analyzer.analyze(dir.toString())
        assertTrue(analysis.detectedLanguages.contains(ProfileIds.PYTHON))

        // 2. Resolve against an empty snapshot — everything missing.
        val snap = EnvironmentSnapshot.EMPTY
        val resolution = resolver.resolve(
            EnvironmentResolutionContext(
                workspaceId = "ws-flow",
                projectRoot = dir.toString(),
                projectRequirements = analysis.requirements,
                environmentSnapshot = snap
            )
        )
        assertTrue(resolution.hasMissing)
        assertTrue(resolution.plan.packagesToInstall.isNotEmpty())

        // 3. Provision — installs packages, applies actions.
        val result = provisioner.provision(resolution.plan, "ws-flow")
        assertTrue(result.succeeded)
        assertTrue(result.installedPackages.isNotEmpty())
        // Stub manager records install count.
        assertEquals(1, pm.installInvocationCount)
    }
}

// ───────────────────────────────────────────────────────────────────────
// Stub LinuxPackageManager for the provisioner tests. Keeps the boundary
// explicit: provisioner talks ONLY to LinuxPackageManager, never to apt.
// ───────────────────────────────────────────────────────────────────────
private class StubPackageManager(
    private val succeeds: Boolean
) : LinuxPackageManager {

    var installInvocationCount: Int = 0
        private set

    private val events = MutableSharedFlow<PackageOperationEvent>(extraBufferCapacity = 256)

    override suspend fun status(): PackageManagerStatus = PackageManagerStatus(
        available = true,
        manager = "stub",
        version = "0",
        databaseState = com.apex.agent.platform.terminal.pkg.PackageDatabaseState.HEALTHY,
        lockState = com.apex.agent.platform.terminal.pkg.PackageLockState.FREE,
        metadataState = com.apex.agent.platform.terminal.pkg.PackageMetadataState.CURRENT,
        brokenPackages = emptyList()
    )

    override suspend fun update(options: PackageUpdateOptions): PackageOperation =
        op(PackageOperationType.UPDATE, emptyList(), succeeded = true)

    override suspend fun install(
        packages: List<PackageSpec>,
        options: PackageInstallOptions
    ): PackageOperation {
        installInvocationCount += 1
        return if (succeeds) {
            op(
                type = PackageOperationType.INSTALL,
                packages = packages,
                succeeded = true,
                installed = packages.map { it.name }
            )
        } else {
            op(
                type = PackageOperationType.INSTALL,
                packages = packages,
                succeeded = false,
                error = PackageOperationError(PackageErrorCode.UNKNOWN, "stub failure", false)
            )
        }
    }

    override suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions): PackageOperation =
        op(PackageOperationType.REMOVE, packages, succeeded = true)

    override suspend fun upgrade(packages: List<PackageSpec>, options: PackageUpgradeOptions): PackageOperation =
        op(PackageOperationType.UPGRADE, packages, succeeded = true)

    override suspend fun search(query: String): PackageSearchResult =
        PackageSearchResult(query, emptyList())

    override suspend fun info(packageName: String): PackageInfo =
        PackageInfo(packageName, null, null, false, null, null, null)

    override suspend fun isInstalled(packageName: String): Boolean = false
    override suspend fun installedVersion(packageName: String): String? = null
    override suspend fun repair(): PackageOperation =
        op(PackageOperationType.REPAIR, emptyList(), succeeded = true)

    override fun operations(): Flow<PackageOperationEvent> = events.asSharedFlow()

    private fun op(
        type: PackageOperationType,
        packages: List<PackageSpec>,
        succeeded: Boolean,
        installed: List<String> = emptyList(),
        error: PackageOperationError? = null
    ): PackageOperation {
        val state = if (succeeded) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED
        val now = System.currentTimeMillis()
        return PackageOperation(
            id = "stub-${type.name.lowercase()}-$now",
            type = type,
            state = state,
            requestedPackages = packages,
            startedAt = now,
            finishedAt = now,
            exitCode = if (succeeded) 0 else 1,
            result = if (succeeded) PackageOperationResult(installed = installed, durationMs = 0) else null,
            error = error
        )
    }
}
