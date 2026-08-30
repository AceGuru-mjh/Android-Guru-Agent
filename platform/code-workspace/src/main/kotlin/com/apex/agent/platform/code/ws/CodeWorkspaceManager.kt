package com.apex.agent.platform.code.ws

import android.content.Context
import com.apex.agent.core.codetools.fs.CodeWorkspaceFileSystem
import com.apex.agent.platform.terminal.environment.ProjectAnalysis
import com.apex.agent.platform.terminal.environment.ProjectEnvironmentAnalyzer
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Code Workspace Manager（Spec §6/§7/§53）。
 *
 * **与 [LinuxWorkspaceManager] 融合，不重造第二套 Linux workspace**（Spec §82）。
 * - host 目录解析 / 创建 / 列表 / 删除 → 全部委托 [linuxWorkspaceManager]
 * - Code 专属元数据（检测到的环境 / git 分支 / lsp 状态 / 最近文件）→ 独立 JSON
 *   `<filesDir>/code/workspaces/<id>.json`，与 Linux 的 `<id>.json` 平行不互扰
 * - 当前激活 workspace → 进程内 [activeId]，挂到 [CodeWorkspaceFileSystem] 供工具消费
 *
 * 生命周期（Spec §7）：Create → Open → Close → Delete；Recent → Restore。
 * 恢复链路（Spec §12）：[restore] 读最近 activeId → [open] → 由 CodeViewModel
 * 触发 terminal session + LSP 重连。
 */
@Singleton
class CodeWorkspaceManager @Inject constructor(
    private val context: Context,
    private val linuxWorkspaceManager: LinuxWorkspaceManager,
    private val environmentAnalyzer: ProjectEnvironmentAnalyzer
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val codeMetaDir = File(context.filesDir, "code/workspaces").apply { mkdirs() }

    @Volatile private var activeId: String? = null
    private val activeFs = ConcurrentHashMap<String, CodeWorkspaceFileSystem>()

    // ═══ Create ═══

    suspend fun create(name: String, repositoryPath: String? = null): Result<CodeWorkspace> {
        val slug = LinuxWorkspaceManager.slugify(name)
        val snap = linuxWorkspaceManager.create(null, name).getOrElse { return Result.failure(it) }
        val now = System.currentTimeMillis()
        val ws = CodeWorkspace(
            workspaceId = slug, name = name,
            hostRootPath = snap.root.value, createdAt = now, lastUsedAt = now
        )
        writeMeta(ws)
        // 新 workspace 默认不立即检测环境；open() 时检测
        return Result.success(ws)
    }

    // ═══ Open ═══

    /**
     * 打开 workspace：解析 host dir + 检测环境 + 加载元数据 + 设为 active。
     * 不启动 LSP（Spec §20 lazy）—— 由 CodeAgentEngine/ViewModel 按需触发。
     */
    suspend fun open(workspaceId: String): Result<CodeWorkspace> {
        val hostDir = linuxWorkspaceManager.resolve(workspaceId).getOrElse { return Result.failure(it) }
        val analysis = runCatching { environmentAnalyzer.analyze(hostDir.absolutePath) }.getOrNull()
        val now = System.currentTimeMillis()
        val existing = readMeta(workspaceId)
        val ws = (existing ?: CodeWorkspace(
            workspaceId = workspaceId, name = workspaceId,
            hostRootPath = hostDir.absolutePath, createdAt = now
        )).copy(
            hostRootPath = hostDir.absolutePath,
            detectedEnvironment = analysis?.detectedLanguages?.joinToString("/")?.ifBlank { null },
            detectedLanguages = analysis?.detectedLanguages?.toList() ?: emptyList(),
            buildSystem = inferBuildSystem(analysis, hostDir),
            sessionState = CodeWorkspace.SessionState.OPEN,
            lastUsedAt = now
        )
        writeMeta(ws)
        activeId = workspaceId
        activeFs[workspaceId] = CodeWorkspaceFileSystem(hostDir)
        return Result.success(ws)
    }

    // ═══ List / Inspect ═══

    fun list(): List<CodeWorkspaceSummary> {
        return linuxWorkspaceManager.list().mapNotNull { snap ->
            val meta = readMeta(snap.id.value) ?: return@mapNotNull null
            CodeWorkspaceSummary(
                workspaceId = snap.id.value,
                name = meta.name,
                detectedEnvironment = meta.detectedEnvironment,
                isOpen = meta.sessionState == CodeWorkspace.SessionState.OPEN,
                lastUsedAt = meta.lastUsedAt,
                createdAt = meta.createdAt
            )
        }.sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    fun inspect(workspaceId: String): Result<CodeWorkspace> {
        val meta = readMeta(workspaceId) ?: return Result.failure(IllegalStateException("not found: $workspaceId"))
        return Result.success(meta)
    }

    // ═══ Close / Delete ═══

    fun close(workspaceId: String) {
        if (activeId == workspaceId) activeId = null
        activeFs.remove(workspaceId)
        readMeta(workspaceId)?.let { ws ->
            writeMeta(ws.copy(sessionState = CodeWorkspace.SessionState.CLOSED))
        }
    }

    fun delete(workspaceId: String): Result<Unit> {
        activeFs.remove(workspaceId)
        if (activeId == workspaceId) activeId = null
        val r = linuxWorkspaceManager.delete(workspaceId)
        if (r.isSuccess) File(codeMetaDir, "$workspaceId.json").delete()
        return r
    }

    // ═══ Active / FS ═══

    fun active(): CodeWorkspace? = activeId?.let { readMeta(it) }

    fun activeId(): String? = activeId

    /**
     * 取当前 active workspace 的 [CodeWorkspaceFileSystem]。
     * 被 [com.apex.agent.core.codetools.tools.WorkspaceFsProvider] 实现委托。
     * 无 active workspace → null（工具据此返回 noWorkspace() 错误）。
     */
    fun activeFileSystem(): CodeWorkspaceFileSystem? = activeId?.let { activeFs[it] }

    /** 刷新元数据（git/lsp/diagnostics 变化时由 ViewModel 调用）。 */
    fun update(workspaceId: String, transform: (CodeWorkspace) -> CodeWorkspace) {
        readMeta(workspaceId)?.let { ws -> writeMeta(transform(ws)) }
    }

    fun updateLastActiveFile(workspaceId: String, file: String?) {
        update(workspaceId) { it.copy(lastActiveFile = file, lastUsedAt = System.currentTimeMillis()) }
    }

    // ═══ Restore（Spec §12 恢复链路） ═══

    /**
     * App 被杀后恢复：读 list() 中最近 lastUsedAt 的 workspace，返回 id 供 ViewModel
     * 调 open() 恢复。terminal session / LSP / 对话记忆的恢复由各自子系统负责。
     */
    fun restoreLast(): CodeWorkspaceSummary? = list().firstOrNull()

    // ═══ 内部 ═══

    private fun metaFile(id: String) = File(codeMetaDir, "$id.json")

    private fun readMeta(id: String): CodeWorkspace? {
        val f = metaFile(id)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<CodeWorkspace>(f.readText()) }.getOrNull()
    }

    private fun writeMeta(ws: CodeWorkspace) {
        val target = metaFile(ws.workspaceId)
        val tmp = File(codeMetaDir, "${ws.workspaceId}.json.tmp")
        tmp.writeText(json.encodeToString(ws))
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    private fun inferBuildSystem(analysis: ProjectAnalysis?, hostDir: File): String? {
        if (analysis == null) return null
        return when {
            File(hostDir, "settings.gradle").exists() || File(hostDir, "settings.gradle.kts").exists() ||
                File(hostDir, "build.gradle").exists() || File(hostDir, "build.gradle.kts").exists() -> "gradle"
            File(hostDir, "package.json").exists() -> "npm"
            File(hostDir, "pom.xml").exists() -> "maven"
            File(hostDir, "Cargo.toml").exists() -> "cargo"
            File(hostDir, "go.mod").exists() -> "go"
            File(hostDir, "requirements.txt").exists() || File(hostDir, "pyproject.toml").exists() -> "pip"
            File(hostDir, "CMakeLists.txt").exists() -> "cmake"
            else -> null
        }
    }
}
