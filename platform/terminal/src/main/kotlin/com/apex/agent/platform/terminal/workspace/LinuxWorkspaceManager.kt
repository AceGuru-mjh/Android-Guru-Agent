package com.apex.agent.platform.terminal.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T75: Linux Workspace 管理 —— P61 workspace 契约的首个生产实现。
 *
 * P71-P73 期间 Linux 会话共用 DI 注入的单个固定 workspace 目录（所有会话的
 * 文件互相可见，rootfs 换版本即丢工作文件）。T75 落地 workspace 生命周期：
 *
 * ```
 * <filesDir>/linux/workspaces/
 *   ├── default/          ← workspace 数据目录（bind 到 guest /workspace）
 *   ├── default.json      ← workspace 元数据（id/name/createdAt/lastUsedAt）
 *   ├── task-42/
 *   └── task-42.json
 * ```
 *
 * 关键语义：
 * - **懒创建**：resolve(id) 对合法 id 自动建目录+元数据（Agent 的
 *   terminal.create(workspaceId=…) 无需先 create —— "workspace-per-task" 零摩擦）。
 * - **活跃绑定计数**：bind(sessionId, id)/unbind(sessionId) 由 TerminalRuntime
 *   在会话创建/关闭时调用；delete 拒绝有活跃会话的 workspace（数据安全优先 ——
 *   Agent 必须先 close 会话再 delete）。计数偏保守（进程退出但未 close 的会话
 *   仍计为活跃），宁可拒绝删除也不冒丢数据风险。
 * - **legacy 迁移**：P71/T73 的单目录 `linux/workspace` 在 default 不存在时
 *   原子 rename 为 `workspaces/default`（同 filesDir 下 rename，无部分状态）；
 *   default 已存在则跳过（legacy 目录保留供人工 salvage）。
 *
 * 元数据写入为 tmp+rename 原子写（与 RootfsMetadataStore 相同模式）。
 * 本类不做跨进程文件锁 —— workspace 操作是应用内单例行为，与 rootfs
 * provision 的跨实例锁（T72）不同层级。
 */
class LinuxWorkspaceManager(
    /** workspace 根目录（`<filesDir>/linux/workspaces`）。 */
    private val rootDir: File,
    /** P71/T73 的单 workspace 目录（`<filesDir>/linux/workspace`）；null = 无迁移。 */
    private val legacyDir: File? = null
) : SessionWorkspaceBinder {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val lock = Any()

    /** sessionId → workspaceId（活跃会话绑定）。 */
    private val activeBindings = ConcurrentHashMap<Long, String>()

    @Volatile
    private var legacyMigrationDone = false

    // ───────── 解析（懒创建，bind 源目录）─────────

    /**
     * 解析 workspace id 为 host 目录（确保存在 + 元数据就绪）。
     * null/blank → [DEFAULT_ID]。非法 id → 失败（WorkspaceError:InvalidId）。
     */
    fun resolve(id: String?): Result<File> {
        val wsId = id?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ID
        if (!ID_PATTERN.matches(wsId)) {
            return Result.failure(IllegalArgumentException(invalidIdMessage(id)))
        }
        return synchronized(lock) {
            migrateLegacyIfNeeded()
            val dir = dataDir(wsId)
            runCatching {
                rootDir.mkdirs()
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                    throw IllegalStateException("WorkspaceError:CreateFailed — 无法创建 ${dir.absolutePath}")
                }
                if (!metadataFile(wsId).exists()) {
                    writeMetadata(WorkspaceMetadataRecord(id = wsId, createdAt = now()))
                }
            }.map { dir }
        }
    }

    // ───────── Agent 管理面 ─────────

    /** 显式创建（幂等：已存在则返回现有快照）。id 为空时由 name 生成 slug。 */
    fun create(id: String?, name: String? = null): Result<WorkspaceSnapshot> {
        val explicitId = id?.trim()?.takeIf { it.isNotEmpty() }
        val wsId = explicitId ?: name?.let { slugify(it) }
            ?: return Result.failure(
                IllegalArgumentException("WorkspaceError:InvalidInput — 需要 id 或 name 之一")
            )
        if (!ID_PATTERN.matches(wsId)) {
            return Result.failure(IllegalArgumentException(invalidIdMessage(wsId)))
        }
        val dir = resolve(wsId).getOrElse { e -> return Result.failure(e) }
        return synchronized(lock) {
            // 已有元数据 → 幂等返回（保留原 createdAt/name，只允许补 name）
            val existing = readMetadata(wsId)
            if (existing == null) {
                writeMetadata(WorkspaceMetadataRecord(id = wsId, name = name, createdAt = now()))
            } else if (name != null && existing.name == null) {
                writeMetadata(existing.copy(name = name))
            }
            Result.success(snapshotOf(wsId, readMetadata(wsId)!!))
        }
    }

    /** 列出全部 workspace（含活跃会话数）。 */
    fun list(): List<WorkspaceSnapshot> {
        synchronized(lock) {
            migrateLegacyIfNeeded()
            val ids = rootDir.listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?.filter { ID_PATTERN.matches(it) }
                ?: emptyList()
            return ids.mapNotNull { id -> readMetadata(id)?.let { snapshotOf(id, it) } }
                .sortedBy { it.createdAt }
        }
    }

    /** 详情（含目录大小，需遍历 —— 仅按需调用）。 */
    fun inspect(id: String): Result<WorkspaceSnapshot> {
        val wsId = normalize(id)
            ?: return Result.failure(IllegalArgumentException(invalidIdMessage(id)))
        synchronized(lock) {
            val meta = readMetadata(wsId)
                ?: return Result.failure(IllegalStateException("WorkspaceError:NotFound — '$wsId'"))
            return Result.success(snapshotOf(wsId, meta).let { snap ->
                snap.copy(detailSizeBytes = dirSize(dataDir(wsId)))
            })
        }
    }

    /**
     * 删除 workspace（目录 + 元数据）。
     * 有活跃会话绑定 → 失败（WorkspaceError:Busy）—— 先 close 会话。
     * default 可删（下次 resolve 惰性重建为空目录）。
     */
    fun delete(id: String): Result<Unit> {
        val wsId = normalize(id)
            ?: return Result.failure(IllegalArgumentException(invalidIdMessage(id)))
        return synchronized(lock) {
            activeBindingsFor(wsId).let { sessions ->
                if (sessions.isNotEmpty()) {
                    return Result.failure(IllegalStateException(
                        "WorkspaceError:Busy — workspace '$wsId' 有活跃会话 ${sessions.sorted()}，" +
                            "先 terminal.close 再删除"
                    ))
                }
            }
            if (readMetadata(wsId) == null) {
                return Result.failure(IllegalStateException("WorkspaceError:NotFound — '$wsId'"))
            }
            runCatching {
                dataDir(wsId).deleteRecursively()
                metadataFile(wsId).delete()
            }.map { }
        }
    }

    // ───────── SessionWorkspaceBinder（TerminalRuntime 会话生命周期钩子）─────────

    override fun bind(sessionId: Long, workspaceId: String) {
        val wsId = normalize(workspaceId) ?: return  // 非法 id：不绑定（prepare 已校验过，防御）
        activeBindings[sessionId] = wsId
        synchronized(lock) {
            readMetadata(wsId)?.let { writeMetadata(it.copy(lastUsedAt = now())) }
        }
    }

    override fun unbind(sessionId: Long) {
        activeBindings.remove(sessionId)
    }

    /** 当前绑定到某 workspace 的会话数（测试/诊断用）。 */
    fun activeSessionCount(workspaceId: String): Int = activeBindingsFor(workspaceId).size

    // ───────── 内部 ─────────

    private fun normalize(id: String?): String? =
        id?.trim()?.takeIf { it.isNotEmpty() && ID_PATTERN.matches(it) }

    private fun invalidIdMessage(id: String?): String =
        "WorkspaceError:InvalidId — '${id ?: ""}'（规则: $ID_PATTERN_TEXT；" +
            "小写字母/数字开头，可含 - _，总长 1-64）"

    private fun dataDir(id: String): File = File(rootDir, id)

    private fun metadataFile(id: String): File = File(rootDir, "$id.json")

    private fun activeBindingsFor(wsId: String): List<Long> =
        activeBindings.entries.filter { it.value == wsId }.map { it.key }

    private fun readMetadata(id: String): WorkspaceMetadataRecord? {
        val f = metadataFile(id)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<WorkspaceMetadataRecord>(f.readText()) }.getOrNull()
    }

    private fun writeMetadata(record: WorkspaceMetadataRecord) {
        val target = metadataFile(record.id)
        val tmp = File(rootDir, "${record.id}.json.tmp")
        tmp.writeText(json.encodeToString(record))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun snapshotOf(id: String, meta: WorkspaceMetadataRecord): WorkspaceSnapshot {
        val dir = dataDir(id)
        val active = activeSessionCount(id)
        return WorkspaceSnapshot(
            id = WorkspaceId(id),
            root = AbsolutePath(dir.absolutePath),
            state = if (dir.isDirectory) WorkspaceState.READY else WorkspaceState.FAILED,
            sharing = WorkspaceSharing.PERSISTENT,
            layout = WorkspaceLayout(),
            sessionCount = active,
            createdAt = meta.createdAt,
            name = meta.name,
            lastUsedAt = meta.lastUsedAt
        )
    }

    /**
     * legacy 单目录 → default 的一次性迁移。
     * default 已存在 → 跳过（保留 legacy 供人工 salvage）；rename 原子（同盘）。
     */
    private fun migrateLegacyIfNeeded() {
        if (legacyMigrationDone || legacyDir == null) return
        val legacy = legacyDir
        if (!legacy.isDirectory) { legacyMigrationDone = true; return }
        val target = dataDir(DEFAULT_ID)
        if (target.exists()) { legacyMigrationDone = true; return }
        runCatching {
            rootDir.mkdirs()
            if (legacy.renameTo(target)) legacyMigrationDone = true
            // rename 失败（跨设备等）→ 下次再试；不强行 copy（数据量未知）
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun now(): Long = System.currentTimeMillis()

    @Serializable
    internal data class WorkspaceMetadataRecord(
        val id: String,
        val name: String? = null,
        val createdAt: Long,
        val lastUsedAt: Long? = null
    )

    companion object {
        const val DEFAULT_ID = "default"
        const val ID_PATTERN_TEXT = "^[a-z0-9][a-z0-9_-]{0,63}$"
        val ID_PATTERN = Regex(ID_PATTERN_TEXT)

        /** name → 合法 id（小写、非法字符折叠为 -、截断 64）。 */
        fun slugify(name: String): String {
            val slug = name.trim().lowercase()
                .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }
                .joinToString("")
                .trim('-')
                .take(64)
            return slug.ifEmpty { DEFAULT_ID }
        }
    }
}

/**
 * T75: 会话 ↔ workspace 绑定钩子。
 *
 * TerminalRuntimeImpl 在会话创建成功后 bind、close 成功后 unbind。
 * 由 [LinuxWorkspaceManager] 实现；LOCAL 会话不触发（无 workspace 概念）。
 * 放在 workspace 包以避免 runtime ↔ workspace 的双向依赖。
 */
interface SessionWorkspaceBinder {
    fun bind(sessionId: Long, workspaceId: String)
    fun unbind(sessionId: Long)
}
