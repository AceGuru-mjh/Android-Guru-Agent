package com.apex.agent.core.tools.marketplace

import okhttp3.OkHttpClient

/**
 * ═══ 官方 MCP Registry 源（registry.modelcontextprotocol.io）═══
 *
 * MCP 官方注册表 —— 目前收录量最大的结构化 MCP 服务器目录（9,000+），
 * 元数据由各仓库的 server.json 直连汇聚：`packages[].identifier` 告诉你
 * 怎么装（npm 包名），`remotes[].url` 给出免沙箱直连的远端端点。
 *
 * 本类是 [GenericRegistryApi] 的无认证接线（官方端点公开只读）：
 * - 目录浏览：`GET /v0/servers`（cursor 分页，默认 30 条/页）；
 * - 服务端搜索：`search` 参数（同时兼容分页）；
 * - `version=latest` 恒定开启（否则同一服务器按版本重复出现）。
 *
 * 安装决策见 [RegistryServer.installKind]（远端直装 / npm 沙箱预装 /
 * 不支持），真实下载编排见 app 层的 RegistryMcpInstaller。
 *
 * 错误契约：任何失败 `Result.failure` 不抛异常（与其他四源一致）。
 */
class OfficialRegistrySource(
    httpClient: OkHttpClient
) {
    private val api = GenericRegistryApi(httpClient, BASE_URL)

    /**
     * 拉取目录一页。[cursor] 来自上页 nextCursor（null = 首页）；
     * [search] 非空走服务端搜索。
     */
    suspend fun listServers(
        cursor: String? = null,
        limit: Int = GenericRegistryApi.DEFAULT_PAGE_SIZE,
        search: String? = null
    ): Result<RegistryPage> = api.listServers(cursor = cursor, limit = limit, search = search)

    companion object {
        /** 官方 Registry 端点（v0 稳定版，无认证）。 */
        const val BASE_URL = "https://registry.modelcontextprotocol.io/v0"
    }
}
