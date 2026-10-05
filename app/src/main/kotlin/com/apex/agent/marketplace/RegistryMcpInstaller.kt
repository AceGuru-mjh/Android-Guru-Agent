package com.apex.agent.marketplace

import com.apex.agent.core.tools.marketplace.RegistryServer
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # RegistryMcpInstaller — MCP Registry 安装决策树 + 沙箱真实下载
 *
 * 把 Registry 条目（官方 Registry / PulseMCP 同构）安装为 MCP 配置的
 * 收口点。核心升级：**npm 形态不再只落 JSON 配置**——先在 PRoot
 * Ubuntu 沙箱里真实执行 `npm install -g {identifier}@{version}` 预热（绕开
 * `npx -y` 首次冷启动撞 180s 握手超时的问题，Issue #163），配置写入
 * 失败时回滚沙箱卸载，不留半残状态。
 *
 * ## 安装决策树（单源实现见 [RegistryServer.installKind]）
 * - **REMOTE**：`remotes[]` 有端点 → 直接写 HTTP/SSE 配置（无沙箱依赖），
 *   headers 原样保留模板值（占位符需用户在「编辑」里补真值）；
 * - **NPM**：`packages[]` 有 npm+stdio 包 → ① rootfs 门禁（未装则引导
 *   terminal.ubuntu.install）② 沙箱 `npm install -g`（5 分钟超时，
 *   npm 下载耗时真实存在）③ 写 `npx -y {identifier}@{version}` 沙箱 STDIO 配置
 *   ④ 写入失败 → 回滚 `npm uninstall -g`；
 * - **UNSUPPORTED**：pypi / mcpb / oci / nuget / 非 stdio npm 包 →
 *   明确报错引导按仓库 README 手动安装，不装残配置。
 *
 * enabled 恒 false（安装 ≠ 启动，与官方 Hub / mcp.so 口径一致）；
 * 远端 headers 占位符与必填环境变量在成功文案里点名，用户到
 * 「已安装管理 → 编辑」补齐后启动。
 */
@Singleton
class RegistryMcpInstaller @Inject constructor(
    private val mcpManager: McpManager,
    private val sandboxRunner: ProotSandboxCommandRunner
) {

    /**
     * 安装一台 Registry 服务器。返回 Result<String>：成功文案（含后续
     * 待补齐项提示）或失败原因（可直接展示）。
     */
    suspend fun install(server: RegistryServer): Result<String> =
        withContext(Dispatchers.IO) {
            when (server.installKind) {
                RegistryServer.InstallKind.REMOTE -> installRemote(server)
                RegistryServer.InstallKind.NPM -> installNpmSandbox(server)
                RegistryServer.InstallKind.UNSUPPORTED -> Result.failure(
                    Exception(unsupportedMessage(server))
                )
            }
        }

    // ── 远端直装（无沙箱依赖）──

    private suspend fun installRemote(server: RegistryServer): Result<String> {
        val config = server.toMcpServerConfig()
            ?: return Result.failure(Exception(unsupportedMessage(server)))
        // 同名配置不覆盖（与官方 Hub / mcp.so 同口径）
        if (mcpManager.getConfigs().any { it.name == config.name }) {
            return Result.failure(
                Exception("同名服务器「${config.name}」已配置——如需重装请先在「已安装管理」中删除")
            )
        }
        return mcpManager.addServer(config).map {
            buildString {
                append("已添加远端 MCP 服务器：${config.name}（${config.url}）")
                val placeholders = config.headers.values.filter { v -> v.contains('{') }
                if (placeholders.isNotEmpty()) {
                    append("；该端点需要鉴权头（含占位符），请到「已安装管理 → 编辑」补齐真值")
                }
                append("—— 安装 ≠ 启动，请在已配置列表中连接")
            }
        }
    }

    // ── npm 沙箱预装（真实下载 + 回滚）──

    private suspend fun installNpmSandbox(server: RegistryServer): Result<String> {
        val pkg = server.npmPackage
            ?: return Result.failure(Exception(unsupportedMessage(server)))
        val config = server.toMcpServerConfig()
            ?: return Result.failure(Exception(unsupportedMessage(server)))
        if (mcpManager.getConfigs().any { it.name == config.name }) {
            return Result.failure(
                Exception("同名服务器「${config.name}」已配置——如需重装请先在「已安装管理」中删除")
            )
        }
        // 供应链硬化：identifier 必须是 npm 包名形态。官方源有校验，但
        // Pulse 源是合作方提交的数据——URL / git spec / 前导 - 这类形态
        // 会让 npm 从任意源拉取执行代码，拒绝安装并引导反馈。
        if (!isSafeNpmIdentifier(pkg.identifier)) {
            return Result.failure(
                Exception(
                    "npm 包名形态非法（${pkg.identifier}）——已拒绝自动安装，" +
                        "请到仓库页核实后手动接入"
                )
            )
        }

        // ① 沙箱真实 npm install -g（预下载，绕开 npx 冷启动超时）
        // P2 修复（版本锁定）：npm 预热与启动配置（npx -y identifier@version，
        // 见 GenericRegistryApi.toMcpServerConfig）同版本 —— 旧实现预热装
        // latest 而启动拉指定版本（或反之），「免冷启动」承诺漂移：次日上游
        // 发新版后预热失效，重新掉回 180s 冷启动窗口。
        val pinnedIdentifier = if (pkg.version.isBlank()) pkg.identifier else "${pkg.identifier}@${pkg.version}"
        val install = sandboxRunner.run(
            command = listOf("npm", "install", "-g", pinnedIdentifier),
            timeoutMs = NPM_INSTALL_TIMEOUT_MS
        )
        if (!install.success) {
            return Result.failure(
                Exception(
                    "沙箱内 npm install 失败（退出码 ${install.exitCode}）：" +
                        install.stderr.takeLast(400).trim().ifEmpty { "无 stderr 输出" } +
                        "——请确认 Ubuntu 已安装 nodejs npm（apt install -y nodejs npm）"
                )
            )
        }

        // ② 写配置（enabled=false：安装 ≠ 启动）
        val added = mcpManager.addServer(config)
        if (added.isFailure) {
            // ③ 回滚：不留「沙箱里装了包但配置没落」的半残状态。
            // 回滚结果要如实报告——uninstall 超时/失败时不能谎称已卸载
            // （误导排查方向）；runCatching 会吞 CancellationException，
            // 用显式 catch 保取消语义诚实。
            var rollbackConfirmed = false
            try {
                rollbackConfirmed = sandboxRunner.run(
                    command = listOf("npm", "uninstall", "-g", pkg.identifier),
                    timeoutMs = NPM_UNINSTALL_TIMEOUT_MS
                ).success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 回滚尽力而为：失败不掩盖主错误，仅在文案里如实说明
            }
            return Result.failure(
                Exception(
                    "配置写入失败" +
                        (if (rollbackConfirmed) {
                            "（npm 包已从沙箱卸载）"
                        } else {
                            "（回滚未确认——如需清理可在沙箱执行：npm uninstall -g ${pkg.identifier}）"
                        }) +
                        "：" + (added.exceptionOrNull()?.message ?: "未知错误")
                )
            )
        }
        return Result.success(
            buildString {
                append("已安装 MCP 服务器：${config.name}（npm 包 $pinnedIdentifier 已预装进沙箱，启动免冷启动）")
                if (pkg.requiredEnvVarNames.isNotEmpty()) {
                    append("；启动前需在「已安装管理 → 编辑」补齐环境变量：")
                    append(pkg.requiredEnvVarNames.joinToString("、"))
                }
                if (pkg.packageArguments.isNotEmpty()) {
                    append("；该包声明了启动参数（")
                    append(pkg.packageArguments.joinToString(" "))
                    append("）——如启动报缺参，请到「已安装管理 → 编辑」补齐")
                }
                append("—— 安装 ≠ 启动，请在已配置列表中连接")
            }
        )
    }

    /**
     * npm 包名形态校验（供应链硬化）：限定 npm 允许的字符集且至多一个
     * `/`（@scope/name），拒绝 URL（`://`）、git spec、前导 `-`/`.`、
     * `..` 路径段与超长串。
     */
    private fun isSafeNpmIdentifier(id: String): Boolean =
        id.length <= 214 &&
            !id.startsWith("-") &&
            !id.startsWith(".") &&
            !id.contains("://") &&
            !id.contains("..") &&
            id.count { it == '/' } <= 1 &&
            id.all { ch ->
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '@' || ch == '/' || ch == '.' || ch == '_' || ch == '-'
            }

    private fun unsupportedMessage(server: RegistryServer): String {
        val pkg = server.unsupportedPackage
        return if (pkg != null) {
            "「${server.displayName}」的发行包形态是 ${pkg.registryType}" +
                "（v1 暂只支持 npm / 远端端点）——请按仓库说明手动接入" +
                server.repositoryUrl.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        } else {
            "「${server.displayName}」未提供可自动安装的包或端点——请按仓库说明手动接入" +
                server.repositoryUrl.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        }
    }

    private companion object {
        /** npm install 全局超时：移动网络下载依赖，给足 5 分钟。 */
        const val NPM_INSTALL_TIMEOUT_MS = 300_000L

        /** npm uninstall 超时（回滚路径，尽力而为）。 */
        const val NPM_UNINSTALL_TIMEOUT_MS = 60_000L
    }
}
