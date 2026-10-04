package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.ToolRegistry

/**
 * # Standard Tool Surface — 标准任务循环的工具编排层
 *
 * 三件事：
 *
 * 1. **画像 → 工具面**：按 [StandardAgentDefinition.toolAllowlist] 前缀集
 *    过滤注册表（`""` = 通配全量；`code_git_` = 前缀；`mcp__` 动态注册的
 *    MCP 一等工具天然命中），叠加 v4 强制函数（forcedToolIds）收窄；
 * 2. **别名归一**：模型可能输出业界惯用名（read/write/edit/bash/grep/
 *    glob/todo/task…），[resolveRegistryId] 归一到注册表 id（code_read /
 *    code_write / code_edit / shell_execute / …）——**胶囊时间轴按
 *    注册表 id 分族渲染**，归一保证两套引擎的胶囊表现完全一致；
 *    未知名归一失败时 [suggestToolIds] 给出相近候选，供错误文案引导；
 * 3. **合成工具**：`task`（子代理委派）不在注册表——[syntheticTaskTool]
 *    现场合成 ToolDefinition（仅主代理画像注入；子代理不可再派子代理）。
 *
 * ## 工具面预算
 *
 * 部分网关对 tools 数量敏感（v4 降级链的根因之一）。标准面按
 * [MAX_TOOLS] 钳制：注册表工具按「编码相关优先」的稳定序截断，
 * 合成工具恒不被截断。
 */
object StandardToolSurface {

    /** 工具面规模上限（超限按优先序截断；task 合成工具不占预算）。 */
    const val MAX_TOOLS = 48

    /**
     * 业界惯用名 → 注册表 id 归一表。
     *
     * 模型读过画像提示词后倾向用短名（read/edit/bash），归一发生在
     * 执行前——ToolCallStart/Complete 事件携带归一后的 id，胶囊时间轴
     * 分族（ToolKind.fromToolName）与深潜引擎完全一致。
     */
    private val ALIAS_TO_REGISTRY: Map<String, String> = mapOf(
        "read" to "code_read",
        "read_file" to "code_read",
        "write" to "code_write",
        "write_file" to "code_write",
        "edit" to "code_edit",
        "edit_file" to "code_edit",
        "multiedit" to "code_edit",
        "bash" to "shell_execute",
        "shell" to "shell_execute",
        "execute" to "shell_execute",
        "grep" to "code_grep",
        "search" to "code_grep",
        "grep_search" to "code_grep",
        "glob" to "code_glob",
        "list" to "code_glob",
        "ls" to "code_glob",
        "todo" to "code_todo",
        "todo_write" to "code_todo",
        "todo_read" to "code_todo",
        "todowrite" to "code_todo",
        "check" to "code_check",
        "lint" to "code_check",
        "task" to "task", // 合成工具（自映射：resolve 层面已归一）
        "subagent" to "task",
        "dispatch" to "task"
    )

    /** 编码相关前缀优先序（截断时的稳定排序依据）。 */
    private val PRIORITY_PREFIXES = listOf(
        "code_", "shell_execute", "terminal.", "code_git_", "web_", "http_",
        "skill_", "mcp__"
    )

    // ═══════════════════════ 归一 API ═══════════════════════

    /**
     * 名字归一（比较用）：去空白 + 小写 + 连字符/点号 → 下划线。
     *
     * 模型偶发输出 `Read-File` / `Bash` / `TODO` 这类大小写或分隔符漂移；
     * 归一只影响比较，不改变原名（MCP/skill 动态 id 原样放行）。
     */
    private fun normalizeKey(raw: String): String =
        raw.trim().lowercase().replace('-', '_').replace('.', '_')

    /** 归一键 → 注册表 id（大小写/分隔符漂移容错层）。 */
    private val NORMALIZED_ALIAS_TO_REGISTRY: Map<String, String> by lazy {
        ALIAS_TO_REGISTRY.mapKeys { (key, _) -> normalizeKey(key) }
    }

    /**
     * 别名归一（三层容错）：精确别名表 → 大小写/分隔符归一后再查 →
     * 仍未命中原样返回（MCP/skill 动态 id 本来就是注册表 id；完全未知
     * 名也放行——ToolExecutor 会给出带相近 id 建议的错误）。
     */
    fun resolveRegistryId(name: String): String =
        ALIAS_TO_REGISTRY[name]
            ?: NORMALIZED_ALIAS_TO_REGISTRY[normalizeKey(name)]
            ?: name

    /**
     * 给未知工具名找相近候选（前 3 个，保持 available 原序）。
     *
     * 比较前做宽松归一（小写 + `-`/`.` → `_` + 双方去 `code_` 前缀），
     * 命中条件：编辑距离 ≤ 2 或前缀/包含匹配。纯函数，供
     * [StandardPrompts.unknownToolResult] 拼修正建议。
     */
    fun suggestToolIds(attempted: String, available: List<String>): List<String> {
        val target = normalizeKey(attempted).removePrefix("code_")
        if (target.isEmpty()) return emptyList()
        return available.filter { candidate ->
            val normalized = normalizeKey(candidate).removePrefix("code_")
            normalized.isNotEmpty() && (
                levenshteinWithin(target, normalized, limit = 2) ||
                    normalized.startsWith(target) ||
                    normalized.contains(target) ||
                    target.startsWith(normalized)
                )
        }.take(3)
    }

    /** 有界编辑距离（超过 limit 提前剪枝；短名也能安全比较）。 */
    private fun levenshteinWithin(a: String, b: String, limit: Int): Boolean {
        if (a.isEmpty()) return b.length <= limit
        if (b.isEmpty()) return a.length <= limit
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return false
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length] <= limit
    }

    /** 是否合成 task 工具（派发子代理的唯一入口）。 */
    fun isSyntheticTaskTool(name: String): Boolean =
        resolveRegistryId(name) == SYNTHETIC_TASK_ID

    /** 合成工具 id。 */
    const val SYNTHETIC_TASK_ID = "task"

    // ═══════════════════════ 工具面构建 ═══════════════════════

    /**
     * 为画像构建请求工具面（ToolDefinition 列表）。
     *
     * @param definition 画像（白名单 + 是否注入合成工具）
     * @param registry 工具注册表（共享单例）
     * @param forcedToolIds v4 强制函数集（非空时只暴露选中工具 + task）
     * @param exposeAll true = 忽略画像白名单（用户显式"全量暴露"）
     * @param includeSyntheticTools 是否注入合成 task 工具（默认取画像配置；
     *   子代理上下文必须显式传 false——子代理不能再派子代理）
     */
    fun buildToolPlan(
        definition: StandardAgentDefinition,
        registry: ToolRegistry,
        forcedToolIds: Set<String> = emptySet(),
        exposeAll: Boolean = false,
        includeSyntheticTools: Boolean = definition.includeSyntheticTools
    ): List<ToolDefinition> {
        val all = registry.getToolDefinitions()
        val forced = forcedToolIds.mapNotNull { id -> all.firstOrNull { it.name == id } }

        val base: List<ToolDefinition> = when {
            forced.isNotEmpty() -> forced
            exposeAll || definition.toolAllowlist.any { it.isEmpty() } -> all
            else -> all.filter { def ->
                val id = registryIdOf(def, all)
                definition.toolAllowlist.any { prefix ->
                    prefix.isEmpty() || id.startsWith(prefix) || def.name.startsWith(prefix)
                }
            }
        }

        val curated = (if (forced.isNotEmpty()) base else base.sortedWith(
            compareByDescending<ToolDefinition> { def ->
                val id = def.name
                PRIORITY_PREFIXES.indexOfFirst { id.startsWith(it) }
                    .let { if (it < 0) Int.MIN_VALUE else -it }
            }.thenBy { it.name }
        )).take(MAX_TOOLS)

        val result = curated.toMutableList()
        if (includeSyntheticTools && forced.isEmpty()) {
            result.add(syntheticTaskTool())
        }
        return result
    }

    /** ToolDefinition.name 与注册表 id 的对应（注册表实现保证同名）。 */
    private fun registryIdOf(def: ToolDefinition, all: List<ToolDefinition>): String = def.name

    /**
     * 工具面文本指引（拼进系统提示词的 "## Tool Surface" 段）。
     *
     * 列编码主力工具 + 别名说明 + task 用法 + 画像边界（只读画像显式
     * 声明写工具被移除的原因，防模型幻觉调用不存在的工具）。
     */
    fun buildToolGuide(
        definition: StandardAgentDefinition,
        planToolIds: List<String>
    ): String = buildString {
        val displayIds = planToolIds.filter { it != SYNTHETIC_TASK_ID }
        if (displayIds.isEmpty()) {
            appendLine("No tools are available this turn — answer directly in text.")
            if (definition.isReadOnly) {
                appendLine("(You are a READ-ONLY agent; the write/exec surface is absent by design.)")
            }
            return@buildString
        }
        appendLine("Available tools this turn (call by exact name):")
        displayIds.chunked(6).forEach { chunk ->
            appendLine("  " + chunk.joinToString(", "))
        }
        appendLine()
        appendLine("Common aliases are auto-resolved (read→code_read, write→code_write,")
        appendLine("edit→code_edit, bash→shell_execute, grep→code_grep, glob→code_glob,")
        appendLine("todo→code_todo, check→code_check) — but prefer the exact names above.")
        if (planToolIds.contains(SYNTHETIC_TASK_ID)) {
            appendLine()
            appendLine("`task` dispatches an isolated sub-agent. It does NOT see this")
            appendLine("conversation — the prompt you pass must be fully self-contained")
            appendLine("(goal, scope, expected output format, write-code-or-research-only,")
            appendLine("how to verify). subagent_type:")
            appendLine("- explore = read-only code investigation (returns path:line evidence)")
            appendLine("- research = web-grounded investigation (returns sourced conclusions)")
            appendLine("- general = full-surface autonomous executor for self-contained")
            appendLine("  multi-step tasks")
            appendLine("Launch independent tasks in parallel. The sub-agent's result is")
            appendLine("invisible to the user — relay the conclusion yourself. Delegate")
            appendLine("exploration and research to keep your context lean, but keep the")
            appendLine("primary edit loop to yourself.")
        }
        if (definition.isReadOnly) {
            appendLine()
            appendLine(
                "You are a READ-ONLY agent: write/exec tools are intentionally absent " +
                    "from your surface. Do not attempt them."
            )
        }
    }.trim()

    /**
     * 合成 task 工具定义（schema 与 [StandardSubAgentRequest] 对齐）。
     *
     * subagent_type 取值域 = explore / research / general 三种（与
     * [StandardAgentKind.fromKey] 的解析域一致），描述对齐业界标准
     * task 工具的用法精华：自包含 prompt / 明确写码或纯调研 /
     * 结果仅主代理可见需自己转述。
     */
    fun syntheticTaskTool(): ToolDefinition = ToolDefinition(
        name = SYNTHETIC_TASK_ID,
        description = """
            Dispatch an isolated sub-agent to run a self-contained task and
            return its conclusion as this tool's result. The sub-agent has its
            own fresh context — it does NOT see this conversation — its own
            tool surface and a turn budget. Three types:
            - explore: read-only code investigation; returns path:line evidence.
            - research: web-grounded investigation; returns sourced conclusions.
            - general: full-surface autonomous executor for self-contained
              multi-step tasks (can read, write, edit, and run commands).

            Usage notes:
            - Launch multiple independent tasks in parallel when they don't
              depend on each other.
            - The sub-agent does NOT see this conversation — the prompt must
              be fully self-contained (goal, scope, exact expected output
              format). State explicitly whether it should WRITE CODE or only
              research, and how to verify its work.
            - Its result is only visible to you — summarize it for the user
              yourself.
            - Delegate exploration and research to keep your context lean, but
              keep the primary edit loop to yourself unless the task is
              genuinely independent.
        """.trimIndent(),
        parameters = """
            {
              "type": "object",
              "properties": {
                "description": {
                  "type": "string",
                  "description": "One-line task summary (shown in logs and result header)"
                },
                "prompt": {
                  "type": "string",
                  "description": "Full self-contained instruction: goal, scope, expected output format, whether to write code or research only, how to verify. The sub-agent sees ONLY this."
                },
                "subagent_type": {
                  "type": "string",
                  "enum": ["explore", "research", "general"],
                  "description": "explore = read-only code investigation, research = web-grounded investigation, general = full-surface autonomous executor (default: explore)"
                }
              },
              "required": ["description", "prompt"]
            }
        """.trimIndent()
    )

    /**
     * 工具面里是否包含 todo 工具（决定提示词是否拼 Todo 指引段）。
     */
    fun planHasTodo(planToolIds: List<String>): Boolean =
        planToolIds.any { it == "code_todo" || it == "todo" }
}
