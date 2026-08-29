package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.workspaces — T75（Workspace 管理）
 *
 * linux-ubuntu 会话的隔离文件区管理。每个 workspace bind 到 guest /workspace，
 * 内容跨会话持久；终端会话通过 terminal.create(workspaceId=…) 绑定。
 *
 * 动作：
 *   - list    列出全部 workspace（含活跃会话数；廉价，无目录遍历）
 *   - create  显式创建（幂等）；id 缺省时由 name 生成 slug
 *   - inspect 单个详情（含 sizeBytes，需目录遍历 —— 按需调用）
 *   - delete  删除（有活跃会话 → WorkspaceError:Busy，先 terminal.close）
 *
 * 用户 home（guest /root）不属于本工具管辖 —— 它是全局持久区（跨 rootfs
 * 版本存活），无生命周期。
 *
 * JSON Schema (input):  { action: "list"|"create"|"delete"|"inspect",
 *                         id?: string, name?: string }
 * JSON Schema (output): action=list → { workspaces: [ { id, name?, createdAt,
 *                        lastUsedAt?, activeSessions, state } ] }；
 *                        create/inspect → { id, name?, createdAt, lastUsedAt?,
 *                        activeSessions, state, sizeBytes? }；
 *                        delete → { deleted: true, id }
 * Errors: WorkspaceError:InvalidId | WorkspaceError:NotFound |
 *         WorkspaceError:Busy | WorkspaceError:InvalidInput
 */
class TerminalWorkspacesTool(
    private val workspaces: LinuxWorkspaceManager
) : TerminalTool {
    override val id: String = "terminal.workspaces"
    override val name: String = id
    override val description: String = """
        Manage isolated workspaces for linux-ubuntu terminal sessions. A workspace is a
        persistent file area bound to guest /workspace; sessions attach via
        terminal.create(backend="linux-ubuntu", workspaceId=...). Files in different
        workspaces are invisible to each other. Actions: list (all workspaces with active
        session counts), create (idempotent; id or name required, name is slugified),
        inspect (single workspace incl. size in bytes), delete (refuses while sessions are
        attached — close them first).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"action":{"type":"string","enum":["list","create","delete","inspect"],"default":"list","description":"list = all workspaces with active session counts; create = explicit create (idempotent, id or name); inspect = one workspace incl. sizeBytes; delete = remove (refuses while sessions attached)"},"id":{"type":"string","description":"Workspace id: ^[a-z0-9][a-z0-9_-]{0,63}$"},"name":{"type":"string","description":"Human-readable name (create only; slugified into id when id omitted)"}},"required":["action"]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { throw IllegalArgumentException("TerminalError:InvalidInput — 参数不是合法 JSON 对象") }
        val action = json["action"]?.jsonPrimitive?.content ?: "list"
        val id = json["id"]?.jsonPrimitive?.contentOrNull
        val name = json["name"]?.jsonPrimitive?.contentOrNull

        return when (action) {
            "list" -> doList()
            "create" -> doCreate(id, name)
            "inspect" -> doInspect(id)
            "delete" -> doDelete(id)
            else -> throw IllegalArgumentException(
                "TerminalError:InvalidInput — 未知 action '$action'（可用: list/create/delete/inspect）"
            )
        }
    }

    private fun doList(): String {
        val list = workspaces.list()
        return buildJsonObject {
            put("workspaces", buildJsonArray {
                for (w in list) add(snapshotJson(w))
            })
        }.toString()
    }

    private fun doCreate(id: String?, name: String?): String {
        val snapshot = workspaces.create(id, name).getOrElse { e ->
            throw IllegalArgumentException(e.message ?: "WorkspaceError:CreateFailed")
        }
        return buildJsonObject {
            put("created", JsonPrimitive(true))
            put("workspace", snapshotJson(snapshot))
        }.toString()
    }

    private fun doInspect(id: String?): String {
        val wsId = id?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("WorkspaceError:InvalidInput — inspect 需要 id")
        val snapshot = workspaces.inspect(wsId).getOrElse { e ->
            throw IllegalArgumentException(e.message ?: "WorkspaceError:NotFound")
        }
        return buildJsonObject {
            put("workspace", snapshotJson(snapshot, includeSize = true))
        }.toString()
    }

    private fun doDelete(id: String?): String {
        val wsId = id?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("WorkspaceError:InvalidInput — delete 需要 id")
        workspaces.delete(wsId).getOrElse { e ->
            throw IllegalArgumentException(e.message ?: "WorkspaceError:DeleteFailed")
        }
        return buildJsonObject {
            put("deleted", JsonPrimitive(true))
            put("id", JsonPrimitive(wsId))
        }.toString()
    }

    private fun snapshotJson(
        w: com.apex.agent.platform.terminal.workspace.WorkspaceSnapshot,
        includeSize: Boolean = false
    ) = buildJsonObject {
        put("id", JsonPrimitive(w.id.value))
        w.name?.let { put("name", JsonPrimitive(it)) }
        put("createdAt", JsonPrimitive(w.createdAt))
        w.lastUsedAt?.let { put("lastUsedAt", JsonPrimitive(it)) }
        put("activeSessions", JsonPrimitive(w.sessionCount))
        put("state", JsonPrimitive(w.state.name))
        if (includeSize) put("sizeBytes", JsonPrimitive(w.detailSizeBytes ?: 0L))
    }
}
