package com.apex.agent.platform.mcphost

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * # 逆向 MCP Host 配置
 *
 * 三重安全模型的第一层：**Token 鉴权 + 分类白名单 + 工具黑名单**。
 * 外部 AI（PC 端 Cline/Claude Desktop 等）能调用的工具面由本配置收窄：
 * - 分类白名单（[allowedCategories]）：只放行 `ToolCategory.name` 命中的分类；
 * - 工具黑名单（[blockedToolIds]）：分类放行后仍强制禁用的个别工具；
 * - `vault_*` 家族在 [McpHostBridge] 内**写死硬拦截**（安全红线，
 *   即使配置放开也拦截——金库密钥绝不经外部 AI 通道出入）。
 */
@Serializable
data class McpHostConfig(
    /** 总开关（false 时服务器不监听）。 */
    val enabled: Boolean = false,
    /** 监听端口（0.0.0.0 全网卡；局域网内 PC 访问）。 */
    val port: Int = 8765,
    /** Bearer Token：首启生成 32 位随机，可重置；空 = 拒绝所有外部请求。 */
    val token: String = "",
    /**
     * 分类白名单（`ToolCategory.name` 集合）。
     * 默认只读友好集；SHELL/TERMINAL/APP/UI/GITHUB/SECURITY 等高风险分类默认关。
     * `CONTEXT` 为上下文回顾类工具预留（该分类当前未落地，属前瞻性放行）。
     */
    val allowedCategories: List<String> = listOf(
        "UTILITY", "FILE", "WEB", "SYSTEM", "SENSOR", "MEMORY", "CONTEXT"
    ),
    /**
     * 工具黑名单：分类放行后仍强制禁用的工具 id（安全默认——
     * 剪贴板写入 / 内容分享等高敏副作用工具即使所在分类被放行也不暴露）。
     * `share_content` 为未来共享工具预留的防御性条目。
     */
    val blockedToolIds: List<String> = listOf(
        "vault_save", "vault_paste", "vault_delete", "vault_list", "share_content"
    ),
    /** 每远程地址每分钟请求上限（令牌桶限速）。 */
    val rateLimitPerMinute: Int = 60
) {
    companion object {
        /** 端口合法范围（避开特权端口与 ephemeral 区间边缘）。 */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private val random = SecureRandom()
        private const val TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** 生成 32 位随机 Token（SecureRandom，~190 bit 熵）。 */
        fun generateToken(): String {
            val chars = CharArray(32)
            for (i in chars.indices) {
                chars[i] = TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]
            }
            return String(chars)
        }

        /** 端口校验 + 收敛（非法端口回落默认 8765）。 */
        fun sanitizePort(port: Int): Int =
            if (port in MIN_PORT..MAX_PORT) port else 8765

        /** 限速收敛（<=0 视为关闭限速的下限保护：至少 1/min，避免除零/永拒）。 */
        fun sanitizeRateLimit(rate: Int): Int = if (rate < 1) 1 else rate
    }

    /** 派生：规范化端口（配置篡改防御，读取路径统一过此校验）。 */
    val effectivePort: Int get() = sanitizePort(port)

    /** 派生：规范化限速。 */
    val effectiveRateLimitPerMinute: Int get() = sanitizeRateLimit(rateLimitPerMinute)

    /** 分类白名单快速判定（大小写不敏感）。 */
    fun isCategoryAllowed(categoryName: String): Boolean =
        allowedCategories.any { it.equals(categoryName, ignoreCase = true) }

    /** 工具黑名单判定。 */
    fun isToolBlocked(toolId: String): Boolean = toolId in blockedToolIds
}

/**
 * 配置持久化接口。app 层用 SharedPreferences + JSON 实现注入；
 * 纯 JVM 测试 / 平台模块用内存实现。
 */
interface McpHostConfigStore {
    fun load(): McpHostConfig
    fun save(config: McpHostConfig)
}

/** 内存实现（测试与无持久化环境用）。 */
class InMemoryMcpHostConfigStore(initial: McpHostConfig = McpHostConfig()) : McpHostConfigStore {
    @Volatile
    private var current: McpHostConfig = initial

    override fun load(): McpHostConfig = current

    override fun save(config: McpHostConfig) {
        current = config
    }
}
