package com.apex.agent.core.tools.hook

import com.apex.agent.core.tools.ToolArguments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #165 — [HookRegistry] 单元测试。
 *
 * 覆盖：pattern 匹配语义 / dispatch 的 BLOCK·Modified·异常隔离·顺序 /
 * hooks.json 持久化往返与损坏重建 / 内置钩子幂等合并 / DeclarativeHook
 * 的 LOG 格式化（含 builtin-prompt-guard 的 100KB 提示行）/ 审计日志
 * 追加与 256KB 滚动 / fire-and-forget。
 *
 * 纯 JVM（JUnit4 + runTest + TemporaryFolder），无 Android 依赖——
 * 与 tool-registry 模块的既有测试（ToolV3ExecutorTest 等）同构。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HookRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ pattern 匹配 ═════════════════════════════════════════════════

    @Test
    fun `matcher accepts exact ids`() {
        assertTrue(HookRegistry.matches("shell_execute", "shell_execute"))
        assertFalse(HookRegistry.matches("shell_execute", "shell_exec"))
    }

    @Test
    fun `matcher accepts prefix with trailing star`() {
        assertTrue(HookRegistry.matches("code_git_*", "code_git_commit"))
        assertTrue(HookRegistry.matches("code_git_*", "code_git_"))
        assertFalse(HookRegistry.matches("code_git_*", "code_github"))
        assertFalse(HookRegistry.matches("code_git_*", "shell_execute"))
    }

    @Test
    fun `matcher single star matches everything`() {
        assertTrue(HookRegistry.matches("*", "shell_execute"))
        assertTrue(HookRegistry.matches("*", "mcp__github__create_issue"))
    }

    @Test
    fun `matcher rejects empty pattern and is case sensitive`() {
        assertFalse(HookRegistry.matches("", "shell_execute"))
        assertFalse(HookRegistry.matches("Shell_Execute", "shell_execute"))
        // 中间通配不支持：按精确匹配处理，永远不命中。
        assertFalse(HookRegistry.matches("code_*_commit", "code_git_commit"))
    }

    // ═══ dispatch：拦截 / 改写 / 异常隔离 / 顺序 ═══════════════════════

    @Test
    fun `declarative BLOCK hook intercepts pre-tool-use with reason`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        registry.getConfigs().first { it.id == HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG }.let {
            // 先关掉内置，隔离声明式链路（下同）。
            registry.setEnabled(it.id, false)
        }
        registry.register(
            declarative(
                HookRegistry.HookConfig(
                    id = "block-shell", name = "拦截 shell",
                    event = HookEventType.PRE_TOOL_USE,
                    pattern = "shell_*",
                    action = HookRegistry.HookAction.BLOCK,
                    reason = "shell is forbidden in this session"
                )
            )
        )

        val result = registry.dispatch(
            HookEvent.PreToolUse("shell_execute", args("""{"cmd": "ls"}"""))
        )
        assertTrue(result.blocked)
        assertEquals("shell is forbidden in this session", result.blockReason)
        assertEquals(1, result.firedCount)
        assertNull(result.modifiedArgs)
    }

    @Test
    fun `BLOCK hook with non-matching pattern does not intercept`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        registry.register(
            declarative(
                HookRegistry.HookConfig(
                    id = "block-git", name = "拦截 git",
                    event = HookEventType.PRE_TOOL_USE,
                    pattern = "code_git_*",
                    action = HookRegistry.HookAction.BLOCK,
                    reason = "no git"
                )
            )
        )

        val result = registry.dispatch(HookEvent.PreToolUse("shell_execute", args("{}")))
        assertFalse(result.blocked)
        assertEquals("pattern 不匹配的声明式钩子不算触发", 0, result.firedCount)
    }

    @Test
    fun `default reason is provided when BLOCK config leaves it blank`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        registry.register(
            declarative(
                HookRegistry.HookConfig(
                    id = "block-any", name = "拦截一切",
                    event = HookEventType.PRE_TOOL_USE,
                    pattern = "*",
                    action = HookRegistry.HookAction.BLOCK
                )
            )
        )

        val result = registry.dispatch(HookEvent.PreToolUse("read_file", args("{}")))
        assertTrue(result.blocked)
        assertTrue(result.blockReason!!.contains("block-any"))
    }

    @Test
    fun `programmatic Modified outcome replaces args and chains to later hooks`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        val seenBySecond = mutableListOf<ToolArguments>()
        registry.register(object : ToolHook {
            override val id = "modifier"
            override suspend fun onEvent(event: HookEvent) =
                HookOutcome.Modified(args("""{"path": "rewritten.txt"}"""))
        })
        registry.register(object : ToolHook {
            override val id = "observer"
            override suspend fun onEvent(event: HookEvent): HookOutcome {
                if (event is HookEvent.PreToolUse) seenBySecond += event.args
                return HookOutcome.Pass
            }
        })

        val result = registry.dispatch(HookEvent.PreToolUse("read_file", args("""{"path": "a.txt"}""")))
        assertFalse(result.blocked)
        assertEquals("""{"path": "rewritten.txt"}""", result.modifiedArgs!!.raw)
        // 链式语义：后注册的钩子看到的是前面钩子改写后的参数。
        assertEquals(1, seenBySecond.size)
        assertEquals("rewritten.txt", seenBySecond[0].requireString("path"))
    }

    @Test
    fun `hook exception is isolated and the chain continues`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        val fired = mutableListOf<String>()
        registry.register(object : ToolHook {
            override val id = "boom"
            override suspend fun onEvent(event: HookEvent): HookOutcome =
                throw IllegalStateException("hook exploded")
        })
        registry.register(object : ToolHook {
            override val id = "after-boom"
            override suspend fun onEvent(event: HookEvent): HookOutcome {
                fired += id
                return HookOutcome.Pass
            }
        })

        val result = registry.dispatch(HookEvent.PostToolUse("read_file", args("{}"), "ok", false, 1L))
        assertFalse(result.blocked)
        assertEquals("坏钩子被隔离，好钩子照常触发（且坏钩子计入 fired）", 2, result.firedCount)
        assertEquals(listOf("after-boom"), fired)
    }

    @Test
    fun `hooks fire in registration order and lower order first`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        val fired = mutableListOf<String>()
        registry.register(recorder("a", fired), order = 0)
        registry.register(recorder("b", fired), order = 0)
        registry.register(recorder("c", fired), order = 0)
        registry.register(recorder("first", fired), order = -1)

        registry.dispatch(HookEvent.Stop("s1"))
        assertEquals(listOf("first", "a", "b", "c"), fired)
    }

    @Test
    fun `first BLOCK wins and short-circuits later hooks`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        val fired = mutableListOf<String>()
        registry.register(object : ToolHook {
            override val id = "blocker"
            override suspend fun onEvent(event: HookEvent): HookOutcome {
                fired += id
                return HookOutcome.Blocked("first")
            }
        })
        registry.register(object : ToolHook {
            override val id = "never"
            override suspend fun onEvent(event: HookEvent): HookOutcome {
                fired += id
                return HookOutcome.Blocked("second")
            }
        })

        val result = registry.dispatch(HookEvent.PreToolUse("read_file", args("{}")))
        assertTrue(result.blocked)
        assertEquals("首个 Deny 胜出（对齐 CompositeToolGate）", "first", result.blockReason)
        assertEquals(listOf("blocker"), fired)
    }

    @Test
    fun `BLOCK on non-pre-tool-use events is treated as pass by design`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        registry.register(object : ToolHook {
            override val id = "prompt-blocker"
            override suspend fun onEvent(event: HookEvent): HookOutcome =
                HookOutcome.Blocked("should be ignored")
        })

        // UserPromptSubmit：Blocked 按设计视为 Pass（见 HookOutcome KDoc）。
        val promptResult = registry.dispatch(HookEvent.UserPromptSubmit("hello"))
        assertFalse(promptResult.blocked)
        assertEquals(1, promptResult.firedCount)
        // PostToolUse：Modified 无载荷可改写，同样忽略。
        registry.register(object : ToolHook {
            override val id = "post-modifier"
            override suspend fun onEvent(event: HookEvent): HookOutcome =
                HookOutcome.Modified(args("{}"))
        })
        val postResult = registry.dispatch(HookEvent.PostToolUse("t", args("{}"), "r", false, 0L))
        assertNull(postResult.modifiedArgs)
    }

    @Test
    fun `unregister removes a programmatic hook`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        disableBuiltins(registry)
        registry.register(recorder("a", mutableListOf()))
        registry.unregister("a")
        val result = registry.dispatch(HookEvent.Stop("s"))
        assertEquals(0, result.firedCount)
    }

    // ═══ 持久化：内置预置 / 往返 / 损坏重建 / 幂等合并 ═════════════════

    @Test
    fun `init presets the three builtin hooks into hooks json`() {
        val dir = tmp.newFolder()
        val registry = HookRegistry(dir) { }

        val configs = registry.getConfigs()
        assertEquals(3, configs.size)
        assertTrue(configs.all { it.system })
        assertTrue(configs.any { it.id == HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG })
        assertTrue(configs.any { it.id == HookRegistry.BUILTIN_ID_SESSION_SUMMARY })
        assertTrue(configs.any { it.id == HookRegistry.BUILTIN_ID_PROMPT_GUARD })
        assertTrue("内置钩子默认 enabled", configs.all { it.enabled })
        assertTrue("预置即落盘", java.io.File(dir, "hooks.json").exists())
    }

    @Test
    fun `custom hooks survive a json round-trip`() {
        val dir = tmp.newFolder()
        HookRegistry(dir) { }.getConfigs() // 触发内置落盘
        // 直接写入一条用户自定义配置（模拟另一进程/手编文件）。
        val userConfig = HookRegistry.HookConfig(
            id = "my-block", name = "我的拦截",
            event = HookEventType.PRE_TOOL_USE,
            pattern = "shell_*",
            action = HookRegistry.HookAction.BLOCK,
            reason = "user rule",
            enabled = false
        )
        val file = java.io.File(dir, "hooks.json")
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        file.writeText(json.encodeToString(listOf(userConfig)))

        val reloaded = HookRegistry(dir) { }.getConfigs()
        assertEquals("自定义 + 三内置", 4, reloaded.size)
        val mine = reloaded.first { it.id == "my-block" }
        assertEquals(HookEventType.PRE_TOOL_USE, mine.event)
        assertEquals("shell_*", mine.pattern)
        assertFalse(mine.enabled)
    }

    @Test
    fun `corrupt json is backed up and rebuilt with builtins`() {
        val dir = tmp.newFolder()
        val file = java.io.File(dir, "hooks.json")
        file.writeText("{ this is not valid json ]")

        val registry = HookRegistry(dir) { }
        assertEquals("损坏后重建：只剩三内置", 3, registry.getConfigs().size)
        assertTrue("现场保留为 .corrupt", java.io.File(dir, "hooks.json.corrupt").exists())
    }

    @Test
    fun `ensureBuiltinHooks is idempotent and keeps user-enabled preference`() {
        val dir = tmp.newFolder()
        val registry = HookRegistry(dir) { }
        registry.setEnabled(HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG, false)

        // 二次 ensure（升级路径）：定义刷新但用户 enabled 保留。
        val result = registry.ensureBuiltinHooks()
        assertTrue(result.isSuccess)
        val configs = registry.getConfigs()
        assertFalse(
            "用户手动禁用的内置钩子不被升级重新打开",
            configs.first { it.id == HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG }.enabled
        )
        assertEquals(3, configs.size)

        // 偏好也落在盘上（新实例读回仍是禁用）。
        val reloaded = HookRegistry(dir) { }
        assertFalse(
            reloaded.getConfigs().first { it.id == HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG }.enabled
        )
        assertEquals(3, reloaded.getConfigs().size)
    }

    @Test
    fun `user-created hook with a builtin id is never hijacked`() {
        val dir = tmp.newFolder()
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        java.io.File(dir, "hooks.json").writeText(
            json.encodeToString(
                listOf(
                    HookRegistry.HookConfig(
                        id = HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG,
                        name = "用户自建同名钩子",
                        event = HookEventType.STOP,
                        system = false
                    )
                )
            )
        )

        val registry = HookRegistry(dir) { }
        val mine = registry.getConfigs().first { it.id == HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG }
        assertEquals("用户自建条目不被内置定义覆盖", HookEventType.STOP, mine.event)
        assertFalse(mine.system)
    }

    @Test
    fun `disabled declarative hook does not fire`() = runTest {
        val registry = HookRegistry(tmp.newFolder()) { }
        registry.setEnabled(HookRegistry.BUILTIN_ID_TOOL_USAGE_LOG, false)

        val result = registry.dispatch(
            HookEvent.PostToolUse("read_file", args("{}"), "ok", false, 5L)
        )
        assertEquals("禁用的钩子不参与派发（连 fired 都不计）", 0, result.firedCount)
        assertFalse(result.blocked)
    }

    // ═══ DeclarativeHook LOG 格式化 ═══════════════════════════════════

    @Test
    fun `LOG action writes a formatted line with event tool and preview`() = runTest {
        val lines = mutableListOf<String>()
        val hook = DeclarativeHook(
            HookRegistry.HookConfig(
                id = "audit", name = "审计",
                event = HookEventType.POST_TOOL_USE,
                pattern = "*"
            )
        ) { lines += it }

        hook.onEvent(
            HookEvent.PostToolUse(
                "shell_execute", args("""{"cmd": "ls -la"}"""),
                "total 0\nfoo.txt", isError = false, durationMs = 42L
            )
        )

        assertEquals(1, lines.size)
        val line = lines[0]
        assertTrue(line.startsWith("["))
        assertTrue(line.contains("] POST_TOOL_USE "))
        assertTrue(line.contains("tool=shell_execute"))
        assertTrue(line.contains("ok=true"))
        assertTrue(line.contains("durationMs=42"))
        // 结果预览：换行压平。
        assertTrue(line.contains("result=total 0\\nfoo.txt"))
    }

    @Test
    fun `prompt log line includes length preview and 100KB hint`() = runTest {
        val lines = mutableListOf<String>()
        val hook = DeclarativeHook(
            HookRegistry.HookConfig(
                id = HookRegistry.BUILTIN_ID_PROMPT_GUARD, name = "输入长度提醒",
                event = HookEventType.USER_PROMPT_SUBMIT,
                pattern = "*"
            )
        ) { lines += it }

        val normal = "a".repeat(500)
        hook.onEvent(HookEvent.UserPromptSubmit(normal))
        assertTrue(lines[0].contains("length=500"))
        assertFalse(lines[0].contains("100KB"))

        val huge = "b".repeat((HookRegistry.PROMPT_HINT_THRESHOLD + 1).toInt())
        hook.onEvent(HookEvent.UserPromptSubmit(huge))
        // 超长提示只留 200 字符预览，但长度真实记录 + 追加提示行。
        assertTrue(lines[1].contains("length=${huge.length}"))
        assertTrue(lines[1].contains("[提示超过 100KB"))
        assertFalse("预览被截断，不倾倒全文", lines[1].contains("b".repeat(300)))
    }

    @Test
    fun `builtin audit hook appends to hooks log through the registry`() = runTest {
        val dir = tmp.newFolder()
        val registry = HookRegistry(dir) { }
        registry.dispatch(HookEvent.PostToolUse("read_file", args("{}"), "ok", false, 1L))

        val log = java.io.File(dir, "hooks.log")
        assertTrue(log.exists())
        val content = log.readText()
        assertTrue(content.contains("POST_TOOL_USE"))
        assertTrue(content.contains("tool=read_file"))
    }

    @Test
    fun `hooks log rolls at 256KB`() = runTest {
        val dir = tmp.newFolder()
        val log = java.io.File(dir, "hooks.log")
        log.writeText("x".repeat((HookRegistry.LOG_MAX_BYTES + 1).toInt()))
        val registry = HookRegistry(dir) { }

        registry.dispatch(HookEvent.PostToolUse("read_file", args("{}"), "ok", false, 1L))

        assertTrue("旧日志滚动为 .1", java.io.File(dir, "hooks.log.1").exists())
        assertTrue("滚动后重开新档", log.length() < HookRegistry.LOG_MAX_BYTES)
        assertTrue(log.readText().contains("tool=read_file"))
    }

    // ═══ fire-and-forget ═══════════════════════════════════════════════

    @Test
    fun `dispatchFireAndForget runs asynchronously on the injected scope`() = runTest {
        val fired = mutableListOf<String>()
        // 尾随 lambda 绑定的是 errorLog（末参）——scope 必须命名参数注入。
        // 用测试调度器构建作用域而非 backgroundScope：后者挂起的任务在
        // 本版本（coroutines-test 1.9.0）不被 advanceUntilIdle 推进
        // （实测复现），StandardTestDispatcher(testScheduler) 才可推进。
        val registry = HookRegistry(
            tmp.newFolder(),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        ) { }
        disableBuiltins(registry)
        registry.register(recorder("ff", fired))

        registry.dispatchFireAndForget(HookEvent.SessionEnd("s1"))
        advanceUntilIdle()

        assertEquals(listOf("ff"), fired)
    }

    @Test
    fun `dispatchFireAndForget without a scope drops the event without throwing`() {
        val errors = mutableListOf<String>()
        val registry = HookRegistry(tmp.newFolder(), scope = null, errorLog = { errors += it })

        registry.dispatchFireAndForget(HookEvent.SessionEnd("s1"))

        assertTrue("无 scope 丢弃事件必须留痕", errors.any { it.contains("CoroutineScope") })
    }

    // ═══ helpers ═══════════════════════════════════════════════════════

    private fun args(json: String): ToolArguments = ToolArguments.parseOrNull(json)!!

    /** 关掉三个内置（隔离声明式链路的测试噪声）。 */
    private fun disableBuiltins(registry: HookRegistry) {
        registry.getConfigs().filter { it.system }.forEach {
            registry.setEnabled(it.id, false)
        }
    }

    /** 声明式配置 → DeclarativeHook（注册表层过滤已由 dispatch 覆盖）。 */
    private fun declarative(config: HookRegistry.HookConfig): ToolHook =
        DeclarativeHook(config) { }

    /** 记录触发顺序的编程式钩子。 */
    private fun recorder(name: String, sink: MutableList<String>): ToolHook =
        object : ToolHook {
            override val id = name
            override suspend fun onEvent(event: HookEvent): HookOutcome {
                sink += name
                return HookOutcome.Pass
            }
        }
}
