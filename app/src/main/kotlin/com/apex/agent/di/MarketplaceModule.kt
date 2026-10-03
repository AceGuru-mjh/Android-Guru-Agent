package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.marketplace.McpSoSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.marketplace.OfficialRegistrySource
import com.apex.agent.core.tools.marketplace.OperitPluginSource
import com.apex.agent.core.tools.marketplace.PulseMcpSource
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.marketplace.PulseMcpCredentialsStore
import com.apex.agent.marketplace.ProotSandboxCommandRunner
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketplaceModule {

    /**
     * 魔搭（ModelScope）技能源：对接官方 modelscope/modelscope-skills 仓库。
     * GitHub token 可选注入（已登录用户走认证限流配额，未登录走匿名配额）。
     */
    @Provides
    @Singleton
    fun provideModelScopeSource(
        httpClient: OkHttpClient,
        githubTokenManager: GithubTokenManager
    ): ModelScopeSource {
        // P2（市场审计）：传 provider 而非快照 —— @Singleton 构造时固化 token 的话，
        // 用户登录/更换 GitHub token 后源永不感知（匿名配额继续降级）。
        return ModelScopeSource(httpClient, gitHubTokenProvider = { githubTokenManager.getToken() })
    }

    /**
     * ClawHub（clawhub.ai）技能仓库源：公开只读 API，无需认证。
     * 供市场 Skills 页签（浏览/搜索/下载安装）与 [com.apex.agent.marketplace.MarketInstallManager] 使用。
     */
    @Provides
    @Singleton
    fun provideClawHubSource(httpClient: OkHttpClient): ClawHubSource {
        return ClawHubSource(httpClient)
    }

    /**
     * 官方 Hub 仓库源（apex-skill-hub + apex-mcp-hub 双目录）：
     * - 技能：62 个生活/通用技能（APK 内置瘦身后迁出），市场「官方仓库」直装；
     * - MCP：沙箱/远端服务器目录，市场「官方 MCP 仓库」安装 → 配置 → 启动。
     * raw.githubusercontent.com 只读，无需认证。
     */
    @Provides
    @Singleton
    fun provideHubSource(httpClient: OkHttpClient): HubSource {
        return HubSource(httpClient)
    }

    /**
     * mcp.so 社区目录源（1.5 万+ MCP 服务器的聚合长尾目录）：
     * 市场 MCP 页签的社区源 —— 目录浏览（分页）+ 详情页 mcpServers
     * 配置一键安装（STDIO 自动路由 PRoot 沙箱）。HTML 路由公开只读，
     * 无需认证；任何失败折叠为 Result.failure（错误契约同 Hub 源）。
     */
    @Provides
    @Singleton
    fun provideMcpSoSource(httpClient: OkHttpClient): McpSoSource {
        return McpSoSource(httpClient)
    }

    /**
     * 官方 MCP Registry 源（registry.modelcontextprotocol.io，9000+
     * 结构化服务器元数据）：公开只读无需认证；cursor 分页 + 服务端
     * search。安装决策树（远端直装 / npm 沙箱预装）由
     * [com.apex.agent.marketplace.RegistryMcpInstaller] 编排。
     */
    @Provides
    @Singleton
    fun provideOfficialRegistrySource(httpClient: OkHttpClient): OfficialRegistrySource {
        return OfficialRegistrySource(httpClient)
    }

    /**
     * PulseMCP Sub-Registry 源（api.pulsemcp.com）：与官方 Registry 同规格，
     * 但需 X-API-Key / X-Tenant-ID 合作凭据（凭据经加密存储注入
     * provider —— 保存/更换后即时生效，与 ModelScope 的 token 模式同构）。
     */
    @Provides
    @Singleton
    fun providePulseMcpSource(
        httpClient: OkHttpClient,
        credentialsStore: PulseMcpCredentialsStore
    ): PulseMcpSource {
        // app 层凭据类型 → core 层凭据类型（core 不依赖 app）
        return PulseMcpSource(httpClient) {
            credentialsStore.credentials()?.let {
                PulseMcpSource.PulseCredentials(apiKey = it.apiKey, tenantId = it.tenantId)
            }
        }
    }

    /**
     * Operit 社区插件源（GitHub 聚合）：topic:operit-plugin 与 operit mcp
     * 双搜索合并；安装时拉仓库 mcp.json / package.json 探测 MCP 形态。
     * GitHub token 可选注入提配额（与 ModelScope 同构）。
     */
    @Provides
    @Singleton
    fun provideOperitPluginSource(
        httpClient: OkHttpClient,
        githubTokenManager: GithubTokenManager
    ): OperitPluginSource {
        return OperitPluginSource(httpClient, gitHubTokenProvider = { githubTokenManager.getToken() })
    }

    /**
     * 市场沙箱命令执行器：npm install/uninstall 等一次性命令进 PRoot
     * Ubuntu 执行（真实下载落地）。rootfsBaseDir / hostEnvironment 与
     * McpModule / CodeModule 的接线同款约定（TerminalModule 提供）。
     */
    @Provides
    @Singleton
    fun provideProotSandboxCommandRunner(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        capabilitySource: PRootCapabilitySource
    ): ProotSandboxCommandRunner {
        return ProotSandboxCommandRunner(
            hostEnv = hostEnvironment.hostEnv(),
            libprootPath = hostEnvironment.prootBinary.absolutePath,
            rootfsDir = rootfsBaseDir,
            isRootfsReady = { File(rootfsBaseDir, "current").exists() },
            persistentHomeDir = File(context.filesDir, "linux/home"),
            // T92：argv 能力门（与终端会话/apt/MCP launcher 同款版本自适应）
            capabilities = capabilitySource::invoke
        )
    }
}
