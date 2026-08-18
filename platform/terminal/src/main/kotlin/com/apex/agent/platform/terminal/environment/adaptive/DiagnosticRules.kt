package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DependencySource
import com.apex.agent.platform.terminal.environment.DeveloperCapability

/**
 * PR #67 sections 7-17: Built-in Diagnostic Rules (11 total).
 *
 * Each rule is a pure function over an ExecutionObservation's stdout+stderr.
 * Adding a new rule NEVER requires touching the AdaptiveEnvironmentResolver
 * (§11 extension principle): register it with DiagnosticRuleRegistry and the
 * resolver will pick it up automatically via `matchAll`.
 *
 * §22 Confidence constants are taken from DiagnosticConfidence so the rule
 * code stays stable:
 *   - HIGH (0.99)   : command-not-found, JAVA_HOME missing, Rust linker missing
 *   - MAPPED (0.92) : known header / library → apt -dev package mapping
 *   - MEDIUM (0.75) : version-mismatch (resolver cross-references requirement)
 *   - LOW (0.55)    : unknown header → guessed `lib<name>-dev`
 *
 * §14 Rust is the killer feature of P67 — the `cargo: command not found` and
 * `linker 'cc' not found` patterns are the most common Rust-on-Android-failure
 * signatures and were not detectable by the P66 v1 resolver.
 *
 * §24 Layer separation: rules emit `EnvironmentDiagnostic` carrying
 * `packageCandidates: List<String>` (suggestions only). The resolver turns
 * suggestions into `PackageSpec` instances; the provisioner hands them to
 * LinuxPackageManager; the package manager calls apt. 4 layers, no mixing.
 *
 * Spec: PR #67 sections 7, 8, 9, 12, 13, 14, 15, 16, 17.
 */

// ─── Section 7 + 16: Command Not Found Rule ───
// Matches both `foo: command not found` and `command not found: foo`.
// Tool name → (capability, apt package) static map (§16 + §7).
class CommandNotFoundRule : DiagnosticRule {

    override val id: String = "command-not-found"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout
        val m1 = COMMAND_NOT_FOUND_SUFFIX.find(combined)
        val m2 = COMMAND_NOT_FOUND_PREFIX.find(combined)
        val toolName = (m1?.groupValues?.get(1) ?: m2?.groupValues?.get(1)) ?: return null
        val cap = TOOL_TO_CAPABILITY[toolName]
        val pkgs = TOOL_TO_PACKAGES[toolName] ?: listOf(toolName)
        val evidence = listOf("stderr matched command-not-found for '$toolName'")
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.COMMAND_NOT_FOUND,
                tool = toolName,
                packageCandidates = pkgs,
                capability = cap,
                confidence = DiagnosticConfidence.HIGH.value,
                evidence = evidence,
                source = DependencySource.APT
            )
        )
    }

    companion object {
        // Two common phrasings:
        //   "cmake: command not found"            (suffix form)
        //   "command not found: cmake"            (prefix form)
        // Tool name may contain + - . _ (e.g. g++, pkg-config, python3.11).
        private val COMMAND_NOT_FOUND_SUFFIX =
            Regex("""([^\s:]+):\s*command not found""")
        private val COMMAND_NOT_FOUND_PREFIX =
            Regex("""command not found:\s*([^\s]+)""")

        // §16 tool→capability mapping. null means "no canonical capability"
        // (the resolver decides — e.g. pkg-config is advisory).
        private val TOOL_TO_CAPABILITY: Map<String, DeveloperCapability> = mapOf(
            "cmake" to DeveloperCapability.CMAKE,
            "g++" to DeveloperCapability.CPP_COMPILER,
            "gcc" to DeveloperCapability.C_COMPILER,
            "make" to DeveloperCapability.MAKE,
            "cargo" to DeveloperCapability.RUST_TOOLCHAIN,
            "rustc" to DeveloperCapability.RUST_TOOLCHAIN,
            "go" to DeveloperCapability.GO_TOOLCHAIN,
            "java" to DeveloperCapability.JAVA_RUNTIME,
            "javac" to DeveloperCapability.JAVAC,
            "node" to DeveloperCapability.NODE_RUNTIME,
            "npm" to DeveloperCapability.NODE_PACKAGE_MANAGER,
            "python3" to DeveloperCapability.PYTHON_RUNTIME,
            "python" to DeveloperCapability.PYTHON_RUNTIME,
            "pip" to DeveloperCapability.PYTHON_PIP,
            "pip3" to DeveloperCapability.PYTHON_PIP
        )

        // §7 tool→apt package mapping. Defaults to [tool] itself when absent.
        private val TOOL_TO_PACKAGES: Map<String, List<String>> = mapOf(
            "cmake" to listOf("cmake"),
            "g++" to listOf("g++"),
            "gcc" to listOf("gcc"),
            "make" to listOf("make"),
            "cargo" to listOf("cargo"),
            "rustc" to listOf("rustc"),
            "go" to listOf("golang-go"),
            "java" to listOf("default-jre"),
            "javac" to listOf("default-jdk"),
            "node" to listOf("nodejs"),
            "npm" to listOf("npm"),
            "python3" to listOf("python3"),
            "python" to listOf("python3"),
            "pip" to listOf("python3-pip"),
            "pip3" to listOf("python3-pip")
        )
    }
}

// ─── Section 8: Version Mismatch Rule ───
// Detects Node / Python / Go version patterns in stderr or stdout. The rule
// itself has no requirement to compare against, so it emits a generic
// VERSION_TOO_OLD with the detected version in evidence; the resolver v2
// cross-references the project's versionConstraint to re-classify as
// TOO_OLD / TOO_NEW / satisfied.
class VersionMismatchRule : DiagnosticRule {

    override val id: String = "version-mismatch"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        val combined = observation.stderr + "\n" + observation.stdout
        // Try Node first.
        NODE_VERSION.find(combined)?.let { mr ->
            val version = mr.groupValues[1]
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.VERSION_TOO_OLD,
                    tool = "node",
                    packageCandidates = listOf("nodejs"),
                    capability = DeveloperCapability.NODE_RUNTIME,
                    confidence = DiagnosticConfidence.MEDIUM.value,
                    evidence = listOf("detected Node version $version (requirement unknown — resolver decides)"),
                    source = DependencySource.APT
                )
            )
        }
        PYTHON_VERSION.find(combined)?.let { mr ->
            val version = mr.groupValues[1]
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.VERSION_TOO_OLD,
                    tool = "python",
                    packageCandidates = listOf("python3"),
                    capability = DeveloperCapability.PYTHON_RUNTIME,
                    confidence = DiagnosticConfidence.MEDIUM.value,
                    evidence = listOf("detected Python version $version (requirement unknown — resolver decides)"),
                    source = DependencySource.APT
                )
            )
        }
        GO_VERSION.find(combined)?.let { mr ->
            val version = mr.groupValues[1]
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.VERSION_TOO_OLD,
                    tool = "go",
                    packageCandidates = listOf("golang-go"),
                    capability = DeveloperCapability.GO_TOOLCHAIN,
                    confidence = DiagnosticConfidence.MEDIUM.value,
                    evidence = listOf("detected Go version $version (requirement unknown — resolver decides)"),
                    source = DependencySource.APT
                )
            )
        }
        return null
    }

    companion object {
        // "Node.js v18.0.0"  /  "node version v18"  /  "Node v18"
        private val NODE_VERSION = Regex("""Node(?:\.js)?\s+v?(\d+(?:\.\d+)*)""")

        // "Python 3.8.10"  /  "python3.8"
        private val PYTHON_VERSION = Regex("""Python\s+(\d+\.\d+(?:\.\d+)?)""")

        // "go1.20.5"  /  "go version go1.20"
        private val GO_VERSION = Regex("""go(\d+\.\d+)""")
    }
}

// ─── Section 9: Missing Header Rule ───
// gcc/clang fatal-error pattern: `fatal error: openssl/ssl.h: No such file or directory`.
// Maps header→apt -dev package. Unknown header → guessed `lib<name>-dev` with LOW confidence.
class MissingHeaderRule : DiagnosticRule {

    override val id: String = "missing-header"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val mr = HEADER_PATTERN.find(observation.stderr) ?: return null
        val header = mr.groupValues[1].trim()
        val mapped = HEADER_TO_PACKAGE[header]
        val isKnown = mapped != null
        val pkg = mapped ?: guessDevPackage(header)
        val confidence = if (isKnown) DiagnosticConfidence.MAPPED.value else DiagnosticConfidence.LOW.value
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.LIBRARY_MISSING,
                tool = null,
                packageCandidates = listOf(pkg),
                capability = null,   // resolver decides which compiler is implicated
                confidence = confidence,
                evidence = listOf("missing header: $header", "candidate apt package: $pkg"),
                source = DependencySource.APT
            )
        )
    }

    private fun guessDevPackage(header: String): String {
        // `openssl/ssl.h` → `libssl-dev`;  `foo.h` → `libfoo-dev`.
        val base = header.substringBefore('/').substringBeforeLast('.').lowercase()
        return if (base.startsWith("lib")) "${base}-dev" else "lib$base-dev"
    }

    companion object {
        // gcc:  "fatal error: openssl/ssl.h: No such file or directory"
        // clang: "fatal error: 'openssl/ssl.h' file not found"
        private val HEADER_PATTERN =
            Regex("""fatal error:\s*['"]?([^\s'":]+?)['"]?\s*(?::\s*)?(?:No such file or directory|file not found)""")

        // §9 known header→package table.
        private val HEADER_TO_PACKAGE: Map<String, String> = mapOf(
            "openssl/ssl.h" to "libssl-dev",
            "sqlite3.h" to "libsqlite3-dev",
            "curl/curl.h" to "libcurl4-openssl-dev",
            "zlib.h" to "zlib1g-dev",
            "ffi.h" to "libffi-dev",
            "expat.h" to "libexpat1-dev",
            "jpeglib.h" to "libjpeg-dev",
            "png.h" to "libpng-dev"
        )
    }
}

// ─── Section 9: Missing Library Rule (-lXXX) ───
// Linker line: "cannot find -lssl"  /  "undefined reference to `SSL_library_init`"
class MissingLibraryRule : DiagnosticRule {

    override val id: String = "missing-library"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val matches = LIBRARY_PATTERN.findAll(observation.stderr).toList()
        if (matches.isEmpty()) return null
        val candidates = matches
            .mapNotNull { LIBRARY_TO_PACKAGE[it.groupValues[1]] }
            .distinct()
        val evidence = matches.map { "missing -l${it.groupValues[1]}" }
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.LIBRARY_MISSING,
                tool = null,
                packageCandidates = candidates,
                capability = null,
                confidence = DiagnosticConfidence.MAPPED.value,
                evidence = evidence,
                source = DependencySource.APT
            )
        )
    }

    companion object {
        // "cannot find -lssl"  /  "ld: library not found -lssl"  /  "undefined reference to ... -lssl"
        private val LIBRARY_PATTERN = Regex("""-l(\w+)""")

        private val LIBRARY_TO_PACKAGE: Map<String, String> = mapOf(
            "ssl" to "libssl-dev",
            "crypto" to "libssl-dev",
            "z" to "zlib1g-dev",
            "curl" to "libcurl4-openssl-dev",
            "sqlite3" to "libsqlite3-dev"
        )
    }
}

// ─── Section 9: Missing Build Tool Rule ───
// Catches pkg-config / ninja / meson "command not found" — these don't map to
// a DeveloperCapability but still need an apt install. capability=null so the
// resolver treats them as advisory (like pkg-config in the C++ profile).
class MissingBuildToolRule : DiagnosticRule {

    override val id: String = "missing-build-tool"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout
        for ((tool, pkg) in BUILD_TOOL_TO_PACKAGE) {
            val p = Regex("""$tool:\s*command not found""")
            if (p.containsMatchIn(combined)) {
                return DiagnosticMatch(
                    EnvironmentDiagnostic(
                        type = DiagnosticType.BUILD_TOOL_MISSING,
                        tool = tool,
                        packageCandidates = listOf(pkg),
                        capability = null,
                        confidence = DiagnosticConfidence.HIGH.value,
                        evidence = listOf("$tool: command not found"),
                        source = DependencySource.APT
                    )
                )
            }
        }
        return null
    }

    companion object {
        // Note: make / cmake / g++ / gcc are handled by CommandNotFoundRule,
        // not here, to avoid double-firing on the same observation.
        private val BUILD_TOOL_TO_PACKAGE: Map<String, String> = mapOf(
            "pkg-config" to "pkg-config",
            "ninja" to "ninja-build",
            "meson" to "meson",
            "automake" to "automake",
            "autoconf" to "autoconf"
        )
    }
}

// ─── Section 15: Java Home Rule ───
// "JAVA_HOME is not set"  /  "JAVA_HOME is not defined".
// KEY DESIGN (§15): do NOT reinstall the JDK — repair the env var instead.
// The diagnostic carries an empty packageCandidates list; the
// EnvironmentRepairPlanner turns this into a SET_ENVIRONMENT_VARIABLE action.
class JavaHomeRule : DiagnosticRule {

    override val id: String = "java-home-missing"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout
        if (!JAVA_HOME_MISSING.containsMatchIn(combined)) return null
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.ENVIRONMENT_VARIABLE_MISSING,
                tool = "JAVA_HOME",
                packageCandidates = emptyList(),  // §15: repair env var, NOT reinstall
                capability = DeveloperCapability.JAVA_RUNTIME,
                confidence = DiagnosticConfidence.HIGH.value,
                evidence = listOf("JAVA_HOME is not set / not defined"),
                source = DependencySource.APT
            )
        )
    }

    companion object {
        private val JAVA_HOME_MISSING =
            Regex("""JAVA_HOME\s+is\s+(?:not\s+set|not\s+defined|undefined)""", RegexOption.IGNORE_CASE)
    }
}

// ─── Section 12: Python Module Rule ───
// "ModuleNotFoundError: No module named 'requests'"  →  source=PIP, capability=PYTHON_PIP.
// KEY: candidate is the module NAME, not an apt package — pip will install it.
class PythonModuleRule : DiagnosticRule {

    override val id: String = "python-module-missing"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val mr = MODULE_NOT_FOUND.find(observation.stderr) ?: return null
        val module = mr.groupValues[1]
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.PACKAGE_MISSING,
                tool = "python-module",
                packageCandidates = listOf(module),   // module name, not apt package
                capability = DeveloperCapability.PYTHON_PIP,
                confidence = DiagnosticConfidence.HIGH.value,
                evidence = listOf("ModuleNotFoundError: No module named '$module'"),
                source = DependencySource.PIP   // §12: PIP, not APT
            )
        )
    }

    companion object {
        private val MODULE_NOT_FOUND =
            Regex("""ModuleNotFoundError:\s*No module named ['"]([^'"]+)['"]""")
    }
}

// ─── Section 13: Node Module Rule ───
// "Cannot find module 'express'"  /  "Error: Cannot find module 'express'".
// source=NPM, capability=NODE_PACKAGE_MANAGER.
class NodeModuleRule : DiagnosticRule {

    override val id: String = "node-module-missing"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val mr = CANNOT_FIND_MODULE.find(observation.stderr) ?: return null
        val module = mr.groupValues[1]
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.PACKAGE_MISSING,
                tool = "node-module",
                packageCandidates = listOf(module),
                capability = DeveloperCapability.NODE_PACKAGE_MANAGER,
                confidence = DiagnosticConfidence.HIGH.value,
                evidence = listOf("Cannot find module '$module'"),
                source = DependencySource.NPM   // §13: NPM, not APT
            )
        )
    }

    companion object {
        private val CANNOT_FIND_MODULE =
            Regex("""(?:Error:\s*)?Cannot find module ['"]([^'"]+)['"]""")
    }
}

// ─── Section 14: Rust Toolchain Rule (P67 killer feature) ───
// `cargo: command not found`   → RUST_TOOLCHAIN, source=CARGO, candidates=[cargo, rustc]
// `linker 'cc' not found`      → COMPILER_MISSING, capability=C_COMPILER, candidate=[gcc]
class RustToolchainRule : DiagnosticRule {

    override val id: String = "rust-toolchain-missing"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout

        // §14: cargo not found
        if (CARGO_NOT_FOUND.containsMatchIn(combined)) {
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.COMMAND_NOT_FOUND,
                    tool = "cargo",
                    packageCandidates = listOf("cargo", "rustc"),
                    capability = DeveloperCapability.RUST_TOOLCHAIN,
                    confidence = DiagnosticConfidence.HIGH.value,
                    evidence = listOf("cargo: command not found"),
                    source = DependencySource.CARGO   // §14: source=CARGO, not APT
                )
            )
        }

        // §14: linker 'cc' not found — Rust can't link because no C compiler
        if (LINKER_CC_NOT_FOUND.containsMatchIn(combined)) {
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.COMPILER_MISSING,
                    tool = "cc",
                    packageCandidates = listOf("gcc"),
                    capability = DeveloperCapability.C_COMPILER,
                    confidence = DiagnosticConfidence.HIGH.value,
                    evidence = listOf("linker 'cc' not found"),
                    source = DependencySource.APT
                )
            )
        }

        return null
    }

    companion object {
        private val CARGO_NOT_FOUND = Regex("""cargo:\s*command not found""")
        private val LINKER_CC_NOT_FOUND = Regex("""linker\s+'?cc'?\s+not\s+found""")
    }
}

// ─── Section 33: Go Toolchain Rule ───
// `GOROOT is not set`       → ENVIRONMENT_VARIABLE_MISSING, capability=GO_TOOLCHAIN
// `cannot find package "x" in any of`  → PACKAGE_MISSING, source=GO
class GoToolchainRule : DiagnosticRule {

    override val id: String = "go-toolchain-missing"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout

        if (GOROOT_MISSING.containsMatchIn(combined)) {
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.ENVIRONMENT_VARIABLE_MISSING,
                    tool = "GOROOT",
                    packageCandidates = emptyList(),  // repair env var, NOT reinstall
                    capability = DeveloperCapability.GO_TOOLCHAIN,
                    confidence = DiagnosticConfidence.HIGH.value,
                    evidence = listOf("GOROOT is not set / not defined"),
                    source = DependencySource.APT
                )
            )
        }

        val pkgMatch = CANNOT_FIND_GO_PACKAGE.find(combined)
        if (pkgMatch != null) {
            val pkg = pkgMatch.groupValues[1]
            return DiagnosticMatch(
                EnvironmentDiagnostic(
                    type = DiagnosticType.PACKAGE_MISSING,
                    tool = "go-package",
                    packageCandidates = listOf(pkg),
                    capability = DeveloperCapability.GO_TOOLCHAIN,
                    confidence = DiagnosticConfidence.MEDIUM.value,
                    evidence = listOf("cannot find package \"$pkg\" in any of \$GOROOT/src"),
                    source = DependencySource.GO   // §33: GO, not APT
                )
            )
        }

        return null
    }

    companion object {
        private val GOROOT_MISSING =
            Regex("""GOROOT\s+is\s+(?:not\s+set|not\s+defined|undefined)""", RegexOption.IGNORE_CASE)
        private val CANNOT_FIND_GO_PACKAGE =
            Regex("""cannot find package ["']([^"']+)["'] in any of""")
    }
}

// ─── Section 17: Architecture Mismatch Rule ───
// "Exec format error"  /  "Cannot execute binary file: Exec format error".
// KEY (§17): do NOT auto-install any package — the wrong-arch binary cannot
// be fixed by apt. The Agent / user must be asked.
class ArchitectureRule : DiagnosticRule {

    override val id: String = "architecture-mismatch"

    override fun match(observation: ExecutionObservation): DiagnosticMatch? {
        if (observation.exitCode == 0) return null
        val combined = observation.stderr + "\n" + observation.stdout
        if (!ARCH_MISMATCH.containsMatchIn(combined)) return null
        return DiagnosticMatch(
            EnvironmentDiagnostic(
                type = DiagnosticType.ARCHITECTURE_MISMATCH,
                tool = null,
                packageCandidates = emptyList(),  // §17: do NOT auto-install
                capability = null,
                confidence = DiagnosticConfidence.HIGH.value,
                evidence = listOf("Exec format error — wrong CPU architecture"),
                source = DependencySource.APT
            )
        )
    }

    companion object {
        // `Exec format error`  /  `Exec format error:`  /  `Cannot execute binary file: Exec format error`
        private val ARCH_MISMATCH =
            Regex("""(?:Cannot execute binary file:\s*)?Exec format error""")
    }
}
