package com.apex.agent.platform.terminal.environment

import java.io.File

/**
 * PR #66 section 6 + 7: Project Environment Analyzer.
 *
 * Scans a workspace project root for well-known marker files, maps each marker
 * to a developer profile id, and returns the union of `EnvironmentRequirement`s
 * for every detected profile. Lockfiles (package-lock.json, Cargo.lock, …) are
 * surfaced separately in `ProjectAnalysis.lockfiles` for higher confidence,
 * but the requirement is still driven by the non-lockfile marker if present.
 *
 * §7 Boundary — the analyzer RETURNS REQUIREMENTS ONLY. It must NEVER install
 * anything, NEVER mutate the filesystem, NEVER spawn a shell. Pure detection.
 *
 * Spec: PR #66 sections 6, 7, 28.
 */

// ─── Section 6: Analyzer Contract ───
interface ProjectEnvironmentAnalyzer {
    suspend fun analyze(projectRoot: String): ProjectAnalysis
}

// ─── Section 6: Project Analysis Result ───
data class ProjectAnalysis(
    val projectRoot: String,
    val detectedLanguages: Set<String>,
    val requirements: List<EnvironmentRequirement>,
    val lockfiles: List<String>
) {
    val hasDetectedAnything: Boolean get() = detectedLanguages.isNotEmpty()
}

// ─── Section 6: File Marker Table ───
// Each entry maps an exact filename — or, for C/C++ source files, an extension
// glob — to a profile id. `isLockfile` flags the higher-confidence lockfiles.
private data class FileMarker(
    val filename: String,
    val profileId: String,
    val isLockfile: Boolean = false
)

private val FILE_MARKERS: List<FileMarker> = listOf(
    // Python
    FileMarker("requirements.txt", ProfileIds.PYTHON),
    FileMarker("pyproject.toml", ProfileIds.PYTHON),
    FileMarker("setup.py", ProfileIds.PYTHON),
    FileMarker("Pipfile", ProfileIds.PYTHON),
    FileMarker("Pipfile.lock", ProfileIds.PYTHON, isLockfile = true),
    // Node
    FileMarker("package.json", ProfileIds.NODE),
    FileMarker("package-lock.json", ProfileIds.NODE, isLockfile = true),
    FileMarker("pnpm-lock.yaml", ProfileIds.NODE, isLockfile = true),
    FileMarker("yarn.lock", ProfileIds.NODE, isLockfile = true),
    // Java
    FileMarker("pom.xml", ProfileIds.JDK),
    FileMarker("build.gradle", ProfileIds.JDK),
    FileMarker("build.gradle.kts", ProfileIds.JDK),
    FileMarker("gradlew", ProfileIds.JDK),
    // C/C++ (plus extension globs handled separately)
    FileMarker("CMakeLists.txt", ProfileIds.CPP),
    FileMarker("Makefile", ProfileIds.CPP),
    FileMarker("meson.build", ProfileIds.CPP),
    // Rust
    FileMarker("Cargo.toml", ProfileIds.RUST),
    FileMarker("Cargo.lock", ProfileIds.RUST, isLockfile = true),
    // Go
    FileMarker("go.mod", ProfileIds.GO),
    FileMarker("go.sum", ProfileIds.GO, isLockfile = true)
)

// C/C++ source-extension glob (§6: *.cpp / *.cc / *.c).
private val CPP_SOURCE_EXTENSIONS = setOf("cpp", "cc", "c")

// ─── Section 6: Default Analyzer Implementation ───
class DefaultProjectEnvironmentAnalyzer(
    private val registry: EnvironmentProfileRegistry
) : ProjectEnvironmentAnalyzer {

    override suspend fun analyze(projectRoot: String): ProjectAnalysis {
        val rootDir = File(projectRoot)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return ProjectAnalysis(projectRoot, emptySet(), emptyList(), emptyList())
        }

        val files = rootDir.listFiles()?.toList() ?: emptyList()
        val fileNames = files.map { it.name }.toSet()

        val detectedProfileIds = linkedSetOf<String>()
        val lockfiles = mutableListOf<String>()

        // 1. Exact-name match against the marker table.
        for (file in files) {
            val marker = FILE_MARKERS.firstOrNull { it.filename == file.name } ?: continue
            detectedProfileIds.add(marker.profileId)
            if (marker.isLockfile) {
                lockfiles.add(file.name)
            }
        }

        // 2. C/C++ source-extension glob — any *.cpp/*.cc/*.c in the root
        //    triggers the cpp profile even without a Makefile/CMakeLists.
        for (name in fileNames) {
            val ext = name.substringAfterLast('.', "")
            if (ext.lowercase() in CPP_SOURCE_EXTENSIONS) {
                detectedProfileIds.add(ProfileIds.CPP)
            }
        }

        // 3. For each detected profile id, look it up in the registry and
        //    collect its requirements. §7: requirements only — never install.
        val requirements = mutableListOf<EnvironmentRequirement>()
        for (profileId in detectedProfileIds) {
            val profile = registry.find(profileId) ?: continue
            requirements.addAll(profile.requirements)
        }

        return ProjectAnalysis(
            projectRoot = projectRoot,
            detectedLanguages = detectedProfileIds.toSet(),
            requirements = requirements,
            lockfiles = lockfiles.toList()
        )
    }
}
