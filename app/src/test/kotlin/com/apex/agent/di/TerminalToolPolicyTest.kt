package com.apex.agent.di

import com.apex.agent.core.tools.ToolRunPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ubuntu 环境探索验证（docs/ubuntu-environment-exploration.md §6）回归测试：
 *
 * 终端/环境类工具的执行器预算必须 ≥ 工具自身内部预算 —— 否则
 * `DefaultToolRunPolicyResolver` 的 mutating() 60s 推断会先杀掉工具
 * （Agent 调 `terminal.ubuntu.ensure` 100% 在 60s 超时，环境链路事实不可用）。
 *
 * 本测试锁住三件事：
 *  1. 长预算环境工具**必须**在覆盖表里（新增环境工具忘了配策略会被抓住）；
 *  2. 每个策略的超时 ≥ 对应工具的内部预算常量；
 *  3. 环境工具不做盲重试（幂等由工具状态机保证，IN_PROGRESS 可续跑）。
 */
class TerminalToolPolicyTest {

    /** 工具自身内部预算（与 platform:terminal 源码常量对齐，漂移时这里要同步）。 */
    private val internalBudgetsMs = mapOf(
        // UbuntuLifecycleCoordinator.DEFAULT_ENSURE_TIMEOUT_MS = 1_800_000
        "terminal.ubuntu.ensure" to 1_800_000L,
        // ProjectEnvironmentCoordinator.ensure 默认 timeoutMs = 900_000
        "terminal.workspace.environment" to 900_000L,
        // UbuntuAptPackageManager.DEFAULT_APT_TIMEOUT_MS = 600_000（ensure 动作复用）
        "terminal.linux.capabilities" to 600_000L,
        // network diagnose 端到端探针 = 一次真实 apt-get update（600s apt 超时）
        "terminal.linux.network" to 600_000L,
        // UbuntuBootstrapManager.DEFAULT_BOOTSTRAP_TIMEOUT_MS = 600_000
        "terminal.linux.bootstrap" to 600_000L,
        // apt 包安装/升级写操作
        "terminal.linux.packages" to 600_000L
    )

    @Test
    fun `all long-budget environment tools are covered by explicit policies`() {
        val mustCover = listOf(
            "terminal.ubuntu.install",
            "terminal.ubuntu.ensure",
            "terminal.linux.bootstrap",
            "terminal.linux.packages",
            "terminal.linux.capabilities",
            "terminal.linux.network",
            "terminal.linux.repair",
            "terminal.linux.status",
            "terminal.workspace.environment",
            "terminal.exec",
            "terminal.wait"
        )
        for (id in mustCover) {
            assertNotNull(
                "工具 $id 缺少执行器超时策略覆盖（将回落 60s mutating 预算被截杀）",
                TERMINAL_TOOL_RUN_POLICIES[id]
            )
        }
    }

    @Test
    fun `executor budget covers tool-internal budget with margin`() {
        for ((id, internalMs) in internalBudgetsMs) {
            val policy = TERMINAL_TOOL_RUN_POLICIES[id]
            assertNotNull("工具 $id 缺少策略", policy)
            assertTrue(
                "工具 $id 执行器预算 ${policy!!.timeoutMs}ms < 内部预算 $internalMs" +
                    "ms —— 执行器会先于工具自身超时逻辑杀掉调用",
                policy.timeoutMs >= internalMs
            )
        }
    }

    @Test
    fun `environment tools never blind-retry`() {
        val noRetryIds = TERMINAL_TOOL_RUN_POLICIES.keys -
            setOf("screenshot")  // 唯一允许一次重试的例外（系统限速补偿）
        for (id in noRetryIds) {
            assertEquals(
                "工具 $id 不应盲重试（幂等由工具状态机保证）",
                0,
                TERMINAL_TOOL_RUN_POLICIES[id]!!.maxRetries
            )
        }
    }

    @Test
    fun `resolver applies the override map and mutating default stays 60s`() {
        // 守护测试：默认推断确实是 60s —— 覆盖表存在的意义（否则无 bug 可修）。
        // 注意：未知 id 走 risk 兜底（terminal.* → MEDIUM → mutating 注解 → 60s；
        // 但 terminal.linux.status / terminal.ubuntu.status 被推断为 readOnly →
        // quickRead 30s —— 这正是它们需要显式覆盖的原因）。
        val resolver = com.apex.agent.core.tools.DefaultToolRunPolicyResolver(
            overrides = TERMINAL_TOOL_RUN_POLICIES
        )
        val fakeMutating = FakeEnvTool(
            "terminal.future.env.tool",
            annotations = com.apex.agent.core.tools.ToolAnnotations.mutating()
        )
        assertEquals(60_000L, resolver.resolve(fakeMutating).timeoutMs)

        // 覆盖后预算生效
        assertEquals(
            TERMINAL_TOOL_RUN_POLICIES["terminal.ubuntu.ensure"]!!.timeoutMs,
            resolver.resolve(FakeEnvTool("terminal.ubuntu.ensure")).timeoutMs
        )
        // 快照类工具的推断陷阱：无覆盖时 terminal.linux.status 只有 quickRead 30s
        //（全量 6 维检查可远超 30s）—— 覆盖表把它抬到 300s。
        val noOverride = com.apex.agent.core.tools.DefaultToolRunPolicyResolver()
        assertEquals(30_000L, noOverride.resolve(FakeEnvTool("terminal.linux.status")).timeoutMs)
        assertEquals(300_000L, resolver.resolve(FakeEnvTool("terminal.linux.status")).timeoutMs)
    }

    private class FakeEnvTool(
        override val id: String,
        private val annotations: com.apex.agent.core.tools.ToolAnnotations? = null
    ) : com.apex.agent.core.tools.AgentTool {
        override val name: String = id
        override val description: String = "fake env tool"
        override val parametersSchema: String = "{}"
        override val metadata: com.apex.agent.core.tools.ToolMetadata =
            if (annotations == null) com.apex.agent.core.tools.ToolMetadata.infer(id)
            else com.apex.agent.core.tools.ToolMetadata(
                id, com.apex.agent.core.tools.ToolCategory.TERMINAL,
                com.apex.agent.core.tools.ToolRisk.MEDIUM, emptyList(), annotations
            )

        override suspend fun execute(arguments: String): String = "{}"
    }
}
