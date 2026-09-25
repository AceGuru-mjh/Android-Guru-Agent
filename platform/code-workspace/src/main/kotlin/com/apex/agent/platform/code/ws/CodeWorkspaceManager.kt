package com.apex.agent.platform.code.ws

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 工作区索引（清单 + 激活 id）—— 状态流对外暴露的公开类型。 */
@Serializable
data class CodeWorkspaceIndex(
    val workspaces: List<CodeWorkspace> = emptyList(),
    val activeId: String? = null
)

/**
 * # Code Workspace Manager — 编码工作区生命周期管理
 *
 * 职责：
 * 1. **创建/列出/删除**工作区（目录 + JSON 索引 `code_workspaces/index.json`）；
 * 2. **环境探测**（build.gradle.kts → Gradle/Kotlin、package.json → Node、
 *    pyproject.toml → Python…）—— 目录文件标记嗅探，零进程开销；
 * 3. **激活态**（[activeRoot] 实现 [CodeWorkspaceRoots] 契约）—— @Volatile 原子
 *    切换，code_* 工具即时跟随；
 * 4. **激活持久化**（`index.json` 里的 activeId，重启后恢复上次工作区）。
 *
 * 与 [com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager] 的关系：
 * 共享同一父目录（`<filesDir>/linux/workspaces/`）但索引独立 —— 终端侧的
 * workspace 生命周期工具（terminal.workspaces）与本管理器互不干扰，同一物理
 * 目录可以被两边引用（这就是文件层互用）。
 */
class CodeWorkspaceManager(
    private val workspacesBaseDir: File,
    private val indexDir: File
) : CodeWorkspaceRoots {

    @Serializable
    private data class Index(
        val workspaces: List<CodeWorkspace> = emptyList(),
        val activeId: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val indexFile: File get() = File(indexDir, "index.json")

    /** 全量状态（工作区清单 + 激活 id）。 */
    private val _state = MutableStateFlow(loadIndex().toPublic())

    /** 公开状态流（快照型，避免私有类型泄漏）。 */
    val state: StateFlow<CodeWorkspaceIndex> get() = _state

    private fun Index.toPublic() = CodeWorkspaceIndex(workspaces = workspaces, activeId = activeId)

    private fun CodeWorkspaceIndex.toPrivate() = Index(workspaces = workspaces, activeId = activeId)

    private val activeRootRef = AtomicReference<File?>(null)

    init {
        workspacesBaseDir.mkdirs()
        indexDir.mkdirs()
        // 恢复激活：索引里的 activeId 对应目录存在则生效
        val restored = _state.value
        val active = restored.workspaces.firstOrNull { it.workspaceId == restored.activeId }
        if (active != null && File(active.hostRootPath).isDirectory) {
            activeRootRef.set(File(active.hostRootPath))
        } else if (restored.workspaces.isEmpty()) {
            // 首启：确保 default 工作区存在（与 Agent 文件工具 / Ubuntu 会话同源）
            runCatching { ensureDefault() }
        } else {
            val first = restored.workspaces.first()
            activate(first.workspaceId)
        }
    }

    // ── CodeWorkspaceRoots 契约 ──────────────────────────────────────

    override fun activeRoot(): File? = activeRootRef.get()

    /** 当前激活工作区（null = 无）。 */
    fun activeWorkspace(): CodeWorkspace? {
        val s = _state.value
        return s.workspaces.firstOrNull { it.workspaceId == s.activeId }
    }

    // ── 生命周期 API ─────────────────────────────────────────────────

    /**
     * 创建新工作区。
     *
     * @return 新建的工作区；重名时返回 null（幂等拒绝，不静默复用）。
     */
    fun create(name: String): CodeWorkspace? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        val id = slugify(trimmed) + "-" + String.format("%x", System.currentTimeMillis() and 0xFFFFFF)
        val dir = File(workspacesBaseDir, id)
        if (dir.exists()) return null
        if (!dir.mkdirs()) return null

        val ws = CodeWorkspace(
            workspaceId = id,
            name = trimmed,
            hostRootPath = dir.canonicalPath,
            createdAt = System.currentTimeMillis()
        )
        val detected = detectEnvironment(dir)
        val withEnv = ws.copy(
            detectedEnvironment = detected.summary,
            detectedLanguages = detected.languages,
            buildSystem = detected.buildSystem
        )
        updateIndex { it.copy(workspaces = it.workspaces + withEnv) }
        AppLogger.instance.info(LogCategory.SYSTEM, TAG, "Code workspace created: $id ($trimmed)")
        return withEnv
    }

    /** 删除工作区（目录 + 索引条目）。激活中的工作区被删则回落到第一个。 */
    fun delete(workspaceId: String): Boolean {
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return false
        val dir = File(target.hostRootPath)
        if (dir.exists()) dir.deleteRecursively()

        val remaining = current.workspaces.filter { it.workspaceId != workspaceId }
        val wasActive = current.activeId == workspaceId
        updateIndex { it.copy(workspaces = remaining, activeId = if (wasActive) null else it.activeId) }
        if (wasActive) {
            activeRootRef.set(null)
            if (remaining.isNotEmpty()) activate(remaining.first().workspaceId)
        }
        return true
    }

    /** 激活工作区（更新 lastUsedAt + 持久化 activeId）。 */
    fun activate(workspaceId: String): CodeWorkspace? {
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return null
        val dir = File(target.hostRootPath)
        if (!dir.isDirectory) return null

        val touched = target.copy(lastUsedAt = System.currentTimeMillis())
        updateIndex { idx ->
            Index(
                workspaces = idx.workspaces.map { if (it.workspaceId == workspaceId) touched else it },
                activeId = workspaceId
            )
        }
        activeRootRef.set(dir)
        return touched
    }

    /** 全部工作区（列表 UI 用）。 */
    fun list(): List<CodeWorkspace> = _state.value.workspaces

    /** 刷新指定工作区的环境探测（项目结构变化后调用）。 */
    fun refreshEnvironment(workspaceId: String) {
        val current = _state.value
        val target = current.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return
        val detected = detectEnvironment(File(target.hostRootPath))
        updateIndex { idx ->
            Index(
                workspaces = idx.workspaces.map {
                    if (it.workspaceId == workspaceId) it.copy(
                        detectedEnvironment = detected.summary,
                        detectedLanguages = detected.languages,
                        buildSystem = detected.buildSystem
                    ) else it
                },
                activeId = idx.activeId
            )
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 首启兜底：default 工作区（与 Agent 文件沙箱同源目录）。 */
    private fun ensureDefault(): CodeWorkspace {
        val dir = File(workspacesBaseDir, DEFAULT_ID)
        dir.mkdirs()
        val detected = detectEnvironment(dir)
        val ws = CodeWorkspace(
            workspaceId = DEFAULT_ID,
            name = "Default",
            hostRootPath = dir.canonicalPath,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            detectedEnvironment = detected.summary,
            detectedLanguages = detected.languages,
            buildSystem = detected.buildSystem
        )
        updateIndex { Index(workspaces = listOf(ws), activeId = DEFAULT_ID) }
        activeRootRef.set(dir)
        return ws
    }

    private fun updateIndex(transform: (Index) -> Index) {
        val next = transform(_state.value.toPrivate())
        _state.value = next.toPublic()
        persist(next)
    }

    private fun loadIndex(): Index = try {
        if (indexFile.exists()) {
            Json { ignoreUnknownKeys = true }.decodeFromString(Index.serializer(), indexFile.readText())
        } else Index()
    } catch (e: Exception) {
        runCatching { indexFile.copyTo(File(indexDir, "index.json.corrupt"), overwrite = true) }
        Index()
    }

    private fun persist(index: Index) {
        try {
            val raw = json.encodeToString(Index.serializer(), index)
            val tmp = File(indexDir, "index.json.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(raw)
                tmp.delete()
            }
        } catch (e: Exception) {
            AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "persist index failed: ${e.message}")
        }
    }

    private fun slugify(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-").trim('-').take(24)
            .ifEmpty { "ws" }

    // ── 环境探测（文件标记嗅探，零进程开销）─────────────────────────

    data class EnvironmentProbe(
        val languages: List<String>,
        val buildSystem: String?,
        val summary: String?
    )

    fun detectEnvironment(root: File): EnvironmentProbe {
        if (!root.isDirectory) return EnvironmentProbe(emptyList(), null, null)
        val langs = mutableListOf<String>()
        var build: String? = null

        val has = { name: String -> File(root, name).exists() }

        when {
            has("build.gradle.kts") || has("build.gradle") || has("settings.gradle.kts") -> {
                build = "gradle"
                if (has("settings.gradle.kts") || File(root, "app").isDirectory) langs += "Kotlin"
                if (File(root, "gradle/wrapper").isDirectory) Unit // wrapper 不算语言
            }
            has("package.json") -> {
                build = "npm"
                if (has("tsconfig.json")) langs += "TypeScript"
                langs += if ("TypeScript" in langs) "JavaScript" else "JavaScript"
            }
            has("pyproject.toml") -> { build = "pip"; langs += "Python" }
            has("requirements.txt") -> { build = if (build == null) "pip" else build; langs += "Python" }
            has("Cargo.toml") -> { build = "cargo"; langs += "Rust" }
            has("go.mod") -> { build = "go"; langs += "Go" }
            has("pom.xml") -> { build = "maven"; langs += "Java" }
            has("CMakeLists.txt") -> { build = "cmake"; langs += "C/C++" }
            has("Makefile") -> { build = build ?: "make" }
        }
        if (has("pubspec.yaml")) { langs += "Dart"; build = build ?: "flutter" }
        if (File(root, ".git").isDirectory && "git" !in langs) {
            // git 不是语言，但 summary 里可以提示仓库存在
        }

        val summary = when {
            langs.isEmpty() && build == null -> if (root.listFiles()?.isEmpty() == true) "空工作区" else null
            langs.isEmpty() -> build
            build == null -> langs.joinToString(" · ")
            else -> "${langs.joinToString(" · ")} · $build"
        }
        return EnvironmentProbe(langs.distinct(), build, summary)
    }

    /** 项目统计摘要（行数/文件数，供系统提示词 JIT 上下文）。 */
    fun projectStats(root: File): String? {
        if (!root.isDirectory) return null
        var files = 0
        var lines = 0L
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || files > 2000) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    if (f.name !in IGNORED) walk(f, depth + 1)
                } else if (f.extension in CODE_EXTS && f.length() < 1_000_000) {
                    files++
                    if (files <= 2000) lines += runCatching { f.readLines().size.toLong() }.getOrDefault(0L)
                }
            }
        }
        walk(root, 0)
        if (files == 0) return null
        return "$files 个代码文件 · 约 ${lines / 1000}k 行"
    }

    private companion object {
        const val TAG = "CodeWorkspaceManager"
        const val DEFAULT_ID = "default"
        val IGNORED = setOf(".git", ".gradle", "build", "node_modules", ".idea", "__pycache__")
        val CODE_EXTS = setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "c", "cpp", "h", "xml", "md")
    }
}
