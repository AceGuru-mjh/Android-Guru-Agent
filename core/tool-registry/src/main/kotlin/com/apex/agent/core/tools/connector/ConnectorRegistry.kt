package com.apex.agent.core.tools.connector

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 连接器定义（Connector）
 *
 * 连接器 = 对外部服务（API / SSH / 数据库 / 网盘等）的标准化访问配置。
 * 与 Skill 的 connector 类型不同，本模型是**独立的一等公民**：
 * 用户可在市场页添加/开关/删除，启用的连接器会出现在输入框 "/" 菜单
 * （`/connector:<id>` 斜杠命令，由 SlashCommandRouter 路由）。
 *
 * @param id       连接器唯一 id（如 `github`、`google_drive`）
 * @param name     显示名
 * @param type     类型（api / ssh / database / storage / messaging / other）
 * @param endpoint 服务端点（URL / host / 数据库地址）
 * @param apiKey   认证密钥（可为空；序列化时原样落盘，由上层决定是否加密）
 * @param extra    扩展配置（键值对，如 port、username）
 * @param enabled  是否启用；仅启用的连接器出现在 "/" 菜单
 * @param builtin  是否内置示例（内置连接器删除时只隐藏，不落盘）
 */
@Serializable
data class ConnectorDef(
    val id: String,
    val name: String,
    val type: String = "api",
    val endpoint: String = "",
    val apiKey: String? = null,
    val extra: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtin: Boolean = false
)

/**
 * 连接器注册表（市场页「连接器」页签 + 斜杠菜单「连接器」分类的数据源）。
 *
 * 相比旧实现（SlashMenuProvider/MarketScreen 两处硬编码 Google Drive/Notion/SSH 示例、
 * 必然漂移），本类把连接器升级为可持久化管理的一等公民。
 *
 * ## 持久化
 * `connectors.json`：kotlinx.serialization 正规序列化（天然转义），
 * 原子写（临时文件 + 重命名）。损坏时自动备份为 `.corrupt` 并回退内置示例。
 *
 * ## 并发
 * 所有可变状态由 [lock] 监视器锁保护，读路径返回快照，线程安全。
 *
 * ## 内置示例
 * builtin=true 的条目随代码分发：首次启动自动出现、可开关、
 * 删除时从内存隐藏且不落盘删除标记（重启后恢复，符合"示例"定位）。
 */
class ConnectorRegistry(
    private val configDir: File
) {
    private val connectors = LinkedHashMap<String, ConnectorDef>()
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 变更通知：市场页与斜杠菜单订阅后自动刷新。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        runCatching { configDir.mkdirs() }
        loadConnectors()
        // 内置示例只在磁盘无该 id 时补充（用户删除后不再自动复活）
        ensureBuiltins()
    }

    /** 添加或更新连接器（自定义连接器 builtin 强制为 false）。 */
    fun add(def: ConnectorDef): Result<Unit> {
        if (def.id.isBlank()) return Result.failure(Exception("Connector id is empty"))
        if (def.name.isBlank()) return Result.failure(Exception("Connector name is empty"))
        if (!def.id.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) {
            return Result.failure(Exception("Connector id 只允许字母/数字/._-，不能含空格或路径分隔符"))
        }
        val error = synchronized(lock) {
            runCatching {
                connectors[def.id] = def.copy(builtin = false)
                saveLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /** 删除连接器（内置示例仅从内存隐藏）。 */
    fun remove(id: String): Boolean {
        val removed = synchronized(lock) {
            val def = connectors[id] ?: return false
            connectors.remove(id)
            if (!def.builtin) runCatching { saveLocked() }
            true
        }
        if (removed) notifyChanged()
        return removed
    }

    /** 启用/禁用。 */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val changed = synchronized(lock) {
            val def = connectors[id] ?: return false
            connectors[id] = def.copy(enabled = enabled)
            runCatching { saveLocked() }
            def.enabled != enabled
        }
        if (changed) notifyChanged()
        return true
    }

    /** 全部连接器（含禁用的，快照读）。 */
    fun getAll(): List<ConnectorDef> = synchronized(lock) { connectors.values.toList() }

    /** 启用的连接器（斜杠菜单可见性依据）。 */
    fun getEnabled(): List<ConnectorDef> =
        synchronized(lock) { connectors.values.filter { it.enabled } }

    fun get(id: String): ConnectorDef? = synchronized(lock) { connectors[id] }

    private fun ensureBuiltins() {
        synchronized(lock) {
            val existing = connectors.keys
            BUILTIN_CONNECTORS
                .filter { it.id !in existing }
                .forEach { connectors[it.id] = it }
        }
    }

    private fun saveLocked() {
        val file = File(configDir, "connectors.json")
        val jsonStr = json.encodeToString(connectors.values.filter { !it.builtin })
        // 原子写：临时文件 + 重命名
        val tmp = File(configDir, "connectors.json.tmp")
        tmp.writeText(jsonStr)
        if (!tmp.renameTo(file)) {
            file.writeText(jsonStr)
            tmp.delete()
        }
    }

    private fun loadConnectors() {
        val file = File(configDir, "connectors.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<ConnectorDef>>(content)
            synchronized(lock) {
                loaded.forEach { connectors[it.id] = it }
            }
        } catch (_: Exception) {
            // 损坏配置：备份现场，保留内置示例
            runCatching { file.copyTo(File(configDir, "connectors.json.corrupt"), overwrite = true) }
        }
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        /** 内置示例连接器（随代码分发，便于用户理解连接器形态）。 */
        val BUILTIN_CONNECTORS: List<ConnectorDef> = listOf(
            ConnectorDef(
                id = "google_drive",
                name = "Google Drive",
                type = "storage",
                endpoint = "https://www.googleapis.com/drive/v3",
                builtin = true
            ),
            ConnectorDef(
                id = "notion",
                name = "Notion",
                type = "api",
                endpoint = "https://api.notion.com/v1",
                builtin = true
            ),
            ConnectorDef(
                id = "ssh",
                name = "SSH",
                type = "ssh",
                endpoint = "",
                builtin = true
            ),
            // ── 消息通道连接器（OpenClaw 同款 channel 模型）─────────────
            // ConnectorMessenger 可按 id 直接发消息（connector_send_message /
            // mcp__im__im_send）；每种通道支持两种 mode（extra.mode）：
            //   wechat : wecom(默认，群机器人 webhook) | clawbot(微信 ClawBot 插件 → OpenClaw Gateway)
            //   feishu : webhook(默认，群机器人)       | app(自建应用：App ID + App Secret)
            //   qq     : QQ 开放平台官方机器人（AppID + clientSecret）
            // 详见 docs/im-connectors.md。
            ConnectorDef(
                id = "wechat",
                name = "微信（ClawBot / 企业微信群机器人）",
                type = "messaging",
                endpoint = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send",
                extra = mapOf(
                    "hint" to "默认企业微信群机器人：apiKey 填 Webhook key（或 endpoint 填完整 webhook URL）。" +
                        "用微信 ClawBot 插件时改填 extra.mode=clawbot 并把 endpoint 换成 OpenClaw Gateway 地址",
                    "mode" to "wecom"
                ),
                builtin = true
            ),
            ConnectorDef(
                id = "feishu",
                name = "飞书（自建应用 / 自定义机器人）",
                type = "messaging",
                endpoint = "https://open.feishu.cn/open-apis/bot/v2/hook/",
                extra = mapOf(
                    "hint" to "默认自定义机器人：apiKey 填 hook token（开启签名校验时补 extra.sign_secret）。" +
                        "自建应用填 extra.mode=app + extra.app_id + apiKey(App Secret) + extra.target",
                    "mode" to "webhook"
                ),
                builtin = true
            ),
            ConnectorDef(
                id = "qq",
                name = "QQ 机器人（QQ 开放平台）",
                type = "messaging",
                endpoint = "https://api.bot.qq.com",
                extra = mapOf(
                    "hint" to "QQ 开放平台创建机器人后填 extra.app_id(AppID) + apiKey(clientSecret) + " +
                        "extra.target(group:<群 openid> / user:<用户 openid> / channel:<子频道 id>)",
                    "mode" to "openapi"
                ),
                builtin = true
            ),
            ConnectorDef(
                id = "telegram",
                name = "Telegram Bot",
                type = "messaging",
                endpoint = "https://api.telegram.org",
                extra = mapOf("hint" to "apiKey 填 Bot token，extra.chat_id 填目标 chat id"),
                builtin = true
            )
        )
    }
}
