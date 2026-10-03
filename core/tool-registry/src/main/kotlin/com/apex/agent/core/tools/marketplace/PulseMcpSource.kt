package com.apex.agent.core.tools.marketplace

import okhttp3.OkHttpClient

/**
 * ═══ PulseMCP Sub-Registry 源（api.pulsemcp.com）═══
 *
 * PulseMCP 的 Sub-Registry API 实现与官方 MCP Registry 同一份
 * Generic Registry API 规格（server.json 直连管道 + PulseMCP 自有
 * 服务器目录 + 访问量/官方性等富化元数据），差异只有两点：
 * - 端点为 `https://api.pulsemcp.com/v0.1`；
 * - **合伙人制认证**：每请求需 `X-API-Key` 与 `X-Tenant-ID` 头
 *  （无凭据时服务端返回 401）。
 *
 * 凭据经 [credentialsProvider] 注入（宿主 app 层的加密存储，模式同
 * [ModelScopeSource] 的 gitHubTokenProvider —— 传 provider 而非构造
 * 快照，用户保存/更换凭据后即时生效）。无凭据时 [listServers] 直接
 * 返回带指引的 failure，不发注定 401 的请求。
 *
 * 条目模型与安装决策复用 [RegistryServer]（与官方 Registry 完全同构）。
 */
class PulseMcpSource(
    httpClient: OkHttpClient,
    private val credentialsProvider: () -> PulseCredentials?
) {
    /** PulseMCP 合作凭据（用户从官网申请后填入）。 */
    data class PulseCredentials(
        val apiKey: String,
        val tenantId: String
    )

    private val api = GenericRegistryApi(httpClient, BASE_URL) {
        val creds = credentialsProvider()
        if (creds == null) {
            emptyMap()
        } else {
            linkedMapOf(
                "X-API-Key" to creds.apiKey,
                "X-Tenant-ID" to creds.tenantId
            )
        }
    }

    /**
     * 拉取目录一页（凭据缺失 → 引导性 failure，不发 401 注定失败的请求）。
     */
    suspend fun listServers(
        cursor: String? = null,
        limit: Int = GenericRegistryApi.DEFAULT_PAGE_SIZE,
        search: String? = null
    ): Result<RegistryPage> {
        val creds = credentialsProvider()
        if (creds == null || creds.apiKey.isBlank() || creds.tenantId.isBlank()) {
            return Result.failure(Exception(noCredentialsMessage()))
        }
        return api.listServers(cursor = cursor, limit = limit, search = search)
    }

    companion object {
        /** PulseMCP Sub-Registry 端点（v0.1）。 */
        const val BASE_URL = "https://api.pulsemcp.com/v0.1"

        fun noCredentialsMessage(): String =
            "PulseMCP 是合作制 API——请先在区块顶部的凭据入口填入 " +
                "X-API-Key 与 X-Tenant-ID（向 pulsemcp.com 申请），或改用官方 Registry 源"
    }
}
