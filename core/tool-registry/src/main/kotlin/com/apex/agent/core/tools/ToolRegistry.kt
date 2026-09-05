package com.apex.agent.core.tools

import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/**
 * 工具注册表
 * 管理所有可用工具（内置 + 插件提供）
 */
interface ToolRegistry {
    fun register(tool: AgentTool)
    fun unregister(toolId: String)
    fun getTool(toolId: String): AgentTool?
    fun getAllTools(): List<AgentTool>
    fun getToolDefinitions(): List<ToolDefinition>

    /**
     * 当前已注册工具数量。作为 UI 刷新函数调用菜单的变更信号：
     * [AgentChatScreen] 用它作为 `remember(key)` 的 key，避免菜单快照被永久缓存
     * （注册表在启动期构建一次，进程内若热注册新工具，下次重组时 key 变化即刷新）。
     * 提供默认实现以保持接口向后兼容（现有实现无需显式 override）。
     */
    val toolCount: Int
        get() = getAllTools().size

    // ── Tool System v2 additions（全部带默认实现，现有实现零迁移）──────────

    /**
     * 注册表结构版本号：每次 register/unregister 递增。调用方用它做
     * 快照失效（如 prompt 缓存、菜单快照），替代轮询 [toolCount] ——
     * 版本号能区分“先删 A 再加 B”这类数量不变的变更。
     */
    val registryVersion: Long
        get() = 0L

    /**
     * 带重复 id 策略的注册。默认 [DuplicateToolIdPolicy.REPLACE] 与 v1
     * `register(tool)` 语义一致（后写覆盖）；[DuplicateToolIdPolicy.REJECT]
     * 用于启动期诊断——防止两个模块各自注册 `download_file` 时静默互踩。
     *
     * @return 被覆盖的旧工具（REPLACE 且已存在同 id 时）；REJECT 策略下
     *   发生冲突时返回 null 且注册表不变。
     */
    fun register(tool: AgentTool, policy: DuplicateToolIdPolicy): AgentTool? {
        // 默认实现退化为 v1 语义（不知道策略的实现按 REPLACE 处理）。
        register(tool)
        return null
    }

    /**
     * 按 [ToolMetadata.category] 查询（类别内按 id 排序，稳定输出）。
     * 元数据由 [AgentTool.metadata] 提供——v1 工具自动走 id 推断。
     */
    fun toolsByCategory(category: ToolCategory): List<AgentTool> =
        getAllTools().filter { it.metadata.category == category }.sortedBy { it.id }

    /**
     * 工具清单按类别分组（仅含有工具的类别，按 [ToolCategory.order] 排序）。
     * Prompt 构建（分组工具清单）与函数菜单（分组 UI）共用此快照。
     */
    fun toolsGroupedByCategory(): Map<ToolCategory, List<AgentTool>> {
        val byCategory = getAllTools().groupBy { it.metadata.category }
        return ToolCategory.inDisplayOrder()
            .filter { it in byCategory }
            .associateWith { byCategory.getValue(it).sortedBy { t -> t.id } }
    }

    /**
     * 模糊搜索工具：id / 名称 / 描述 / 元数据标签中命中 [query]（大小写
     * 不敏感），按简单相关性（id 前缀 > id 包含 > 名称包含 > 标签/描述包含）
     * 排序。空 query 返回空列表。
     */
    fun searchTools(query: String): List<AgentTool> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        data class Ranked(val rank: Int, val id: String, val tool: AgentTool)

        return getAllTools()
            .mapNotNull { tool ->
                val id = tool.id.lowercase()
                val name = tool.name.lowercase()
                val meta = tool.metadata
                val rank = when {
                    id.startsWith(q) -> 0
                    id.contains(q) -> 1
                    name.contains(q) -> 2
                    meta.tags.any { it.contains(q) } -> 3
                    tool.description.lowercase().contains(q) -> 4
                    else -> return@mapNotNull null
                }
                Ranked(rank, tool.id, tool)
            }
            .sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.id })
            .map { it.tool }
    }

    /**
     * 查询单个工具的元数据（未注册返回 null——区别于推断默认值，
     * 用于“这个 id 存在吗”的判断场景）。
     */
    fun metadataOf(toolId: String): ToolMetadata? =
        getTool(toolId)?.metadata

    /**
     * 注册表变更监听：DI 构建完成后，运行期热注册（MCP server 连接、
     * 技能安装）可通知 UI / prompt 缓存失效。监听器在注册表内部锁内
     * 调用，必须快速返回且不得再调用注册表写方法。
     */
    fun addRegistrationListener(listener: ToolRegistrationListener) {}
    fun removeRegistrationListener(listener: ToolRegistrationListener) {}
}

/** 注册表变更事件（[ToolRegistry.addRegistrationListener] 回调载荷）。 */
sealed interface ToolRegistrationEvent {
    data class Registered(
        val toolId: String,
        val replaced: Boolean,
        val metadata: ToolMetadata
    ) : ToolRegistrationEvent

    data class Unregistered(val toolId: String) : ToolRegistrationEvent
}

/** see [ToolRegistry.addRegistrationListener]。 */
fun interface ToolRegistrationListener {
    fun onToolRegistrationEvent(event: ToolRegistrationEvent)
}

/**
 * 重复工具 id 的处理策略。
 *
 * v1 语义是静默 REPLACE（后写覆盖）——技能/MCP 热注册依赖它，保持默认。
 * REJECT 用于启动期装配（DI 模块）与测试：显式暴露冲突而不是让两个
 * 模块在运行时互相覆盖。
 */
enum class DuplicateToolIdPolicy {
    /** 覆盖已有同 id 工具（v1 语义）。 */
    REPLACE,

    /** 拒绝注册并保留旧工具（返回 null，注册表不变）。 */
    REJECT
}

/**
 * 工具执行器。
 *
 * 提供两种执行入口：
 * - [execute]：一次性返回完整输出（向后兼容，现有调用点不变）。
 * - [executeStream]：返回 [ToolStreamEvent] 流，允许工具逐段输出。engine 优先
 *   使用 [executeStream]，这样实现了 [StreamingAgentTool] 的工具（如
 *   shell_execute）能逐行把输出推到 UI，而非流式工具则被透明包装成
 *   “单个 Output + Complete” 的事件序列。
 *
 * ## Tool System v2 执行管线
 *
 * 两条入口共用同一套前置/后置横切逻辑：
 *
 * 1. **查找** 未命中 → 带相近 id 建议的错误（[ToolSuggester]），不再倾倒全量清单；
 * 2. **门控** [gate] 拒绝 → PERMISSION_DENIED 错误（携带模型可执行的指引）；
 * 3. **校验** 声明式 schema（DSL 或从 v1 JSON 宽松导入）→ INVALID_ARGUMENT；
 * 4. **执行** 原有 Safe/流式路径不变；
 * 5. **统计** [usageTracker] 记录成败与耗时。
 *
 * gate / tracker / 校验全部可选（默认 null / true）——注入即生效，
 * 不注入时行为与 v1 完全一致（既有测试与调用点不迁移）。
 */
interface ToolExecutor {

    /**
     * 兼容旧逻辑：一次性执行工具。
     */
    suspend fun execute(toolId: String, arguments: String): String

    /**
     * 新逻辑：流式执行工具。始终以 [Flow] 形式返回，无论工具是否实现
     * [StreamingAgentTool]：
     * - 实现了 [StreamingAgentTool]：直接转发 `tool.executeStream(...)` 的事件。
     * - 未实现：调用 `tool.execute(...)`，把结果包成单个
     *   [ToolStreamEvent.Output] + [ToolStreamEvent.Complete]（失败时为 [ToolStreamEvent.Error]）。
     */
    fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent>
}

/**
 * Agent工具接口
 */
interface AgentTool {
    val id: String
    val name: String
    val description: String
    val parametersSchema: String  // JSON Schema

    /**
     * 工具元数据（v2）：类别 / 风险 / 标签。默认按 id 推断并缓存——
     * 所有 v1 工具零改动获得元数据；v2 工具（[StructuredAgentTool] /
     * BaseTool 子类）通过覆盖此属性显式声明。
     */
    val metadata: ToolMetadata
        get() = InferredToolMetadata.forId(id)

    suspend fun execute(arguments: String): String
}

/** id → 推断元数据 的进程级缓存（[ToolMetadata.infer] 结果幂等）。 */
private object InferredToolMetadata {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ToolMetadata>()

    fun forId(id: String): ToolMetadata =
        cache.computeIfAbsent(id) { ToolMetadata.infer(id) }
}

/**
 * 默认实现
 */
class DefaultToolRegistry : ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()
    private val listeners = mutableListOf<ToolRegistrationListener>()
    private var version = 0L

    override fun register(tool: AgentTool) {
        register(tool, DuplicateToolIdPolicy.REPLACE)
    }

    override fun register(tool: AgentTool, policy: DuplicateToolIdPolicy): AgentTool? {
        synchronized(this) {
            val existing = tools[tool.id]
            if (existing != null && policy == DuplicateToolIdPolicy.REJECT) {
                return null
            }
            tools[tool.id] = tool
            version++
            val event = ToolRegistrationEvent.Registered(
                toolId = tool.id,
                replaced = existing != null,
                metadata = tool.metadata
            )
            listeners.toList().forEach { it.onToolRegistrationEvent(event) }
            return existing
        }
    }

    override fun unregister(toolId: String) {
        synchronized(this) {
            if (tools.remove(toolId) != null) {
                version++
                val event = ToolRegistrationEvent.Unregistered(toolId)
                listeners.toList().forEach { it.onToolRegistrationEvent(event) }
            }
        }
    }

    override fun getTool(toolId: String): AgentTool? = synchronized(this) { tools[toolId] }

    override fun getAllTools(): List<AgentTool> = synchronized(this) { tools.values.toList() }

    override fun getToolDefinitions(): List<ToolDefinition> {
        return synchronized(this) {
            tools.values.map { tool ->
                ToolDefinition(
                    name = tool.id,
                    description = tool.description,
                    parameters = tool.parametersSchema
                )
            }
        }
    }

    override val registryVersion: Long
        get() = synchronized(this) { version }

    override fun addRegistrationListener(listener: ToolRegistrationListener) {
        synchronized(this) { listeners += listener }
    }

    override fun removeRegistrationListener(listener: ToolRegistrationListener) {
        synchronized(this) { listeners -= listener }
    }

    // O(1) 直接读 map 大小，避免 getAllTools() 复制整个 values 列表。
    override val toolCount: Int
        get() = synchronized(this) { tools.size }
}

/**
 * v2 执行管线共享逻辑：查找 → 门控 → schema 校验 → 执行 → 统计。
 * [DefaultToolExecutor] 的两个入口（execute / executeStream）都经过它，
 * 保证横切行为一致。
 */
internal class ToolExecutionPipeline(
    private val registry: ToolRegistry,
    private val gate: ToolExecutionGate?,
    private val schemaValidation: Boolean
) {
    sealed interface PreCheck {
        /** 前置检查通过，携带解析后的工具引用（避免二次查找的注册竞态）。 */
        data class Ready(val tool: AgentTool) : PreCheck

        /** 前置检查失败，[message] 已是 v1 字符串协议（Error: 前缀）。 */
        data class Failed(val message: String) : PreCheck
    }

    /** 前置检查（查找/门控/校验）。 */
    suspend fun preCheck(toolId: String, arguments: String): PreCheck {
        val tool = registry.getTool(toolId)
            ?: return PreCheck.Failed(notFoundMessage(toolId, registry))

        if (gate != null) {
            when (val decision = gate.check(tool, arguments)) {
                is GateDecision.Allow -> Unit
                is GateDecision.Deny -> return PreCheck.Failed(
                    "Error: permission denied: ${decision.reason}"
                )
            }
        }

        if (schemaValidation) {
            val violations = validateSchema(tool, arguments)
            if (violations != null) {
                return PreCheck.Failed(
                    "Error: invalid argument: $violations. Fix the arguments and retry."
                )
            }
        }
        return PreCheck.Ready(tool)
    }

    /**
     * schema 校验：null = 通过（或不适用）；非 null = 违规摘要。
     *
     * - 任意工具：从 `parametersSchema` 渲染字符串宽松导入（v1 JSON 也
     *   能导入）；解析失败 → 不校验（绝不误伤手写 schema 的 v1 工具）。
     * - 空参数（""）仅在声明了 required 参数时才判违规。
     */
    private fun validateSchema(tool: AgentTool, arguments: String): String? {
        val schema = ToolSchemaCache.forTool(tool) ?: return null
        val text = arguments.trim().ifEmpty { "{}" }
        val validation = schema.validateArguments(text)
        return if (validation.isValid) null else validation.summary()
    }

    companion object {
        /**
         * 未找到工具的错误信息：v2 只带相近建议（[ToolSuggester]），不再
         * 倾倒全量 id 清单（40+ 个 id 只会教模型继续猜）。
         */
        fun notFoundMessage(toolId: String, registry: ToolRegistry): String {
            val suggestion = ToolSuggester.suggestionLine(
                toolId, registry.getAllTools().map { it.id }
            )
            return buildString {
                append("Error: Tool '").append(toolId).append("' not found (")
                append(registry.toolCount).append(" tools registered)")
                if (suggestion != null) {
                    append(". ").append(suggestion)
                } else {
                    append(". Available categories: ")
                    append(registry.toolsGroupedByCategory().keys.joinToString(", ") { it.name })
                }
                append(". Do not guess tool ids.")
            }
        }
    }
}

/** 工具的 schema 缓存：渲染字符串 → 解析一次，进程内复用（幂等）。 */
internal object ToolSchemaCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<AgentTool, ToolSchema?>()

    fun forTool(tool: AgentTool): ToolSchema? =
        cache.computeIfAbsent(tool) { ToolSchema.fromRendered(it.parametersSchema) }
}

class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val gate: ToolExecutionGate? = null,
    private val usageTracker: ToolUsageTracker? = null,
    private val schemaValidation: Boolean = true
) : ToolExecutor {

    private val pipeline = ToolExecutionPipeline(registry, gate, schemaValidation)

    override suspend fun execute(toolId: String, arguments: String): String {
        val tool = when (val pre = pipeline.preCheck(toolId, arguments)) {
            is ToolExecutionPipeline.PreCheck.Failed -> return pre.message
            is ToolExecutionPipeline.PreCheck.Ready -> pre.tool
        }

        val invocation = usageTracker?.begin(toolId)
        return try {
            val result = tool.execute(arguments)
            if (invocation != null) {
                if (result.startsWith("Error")) {
                    usageTracker.failure(invocation, result)
                } else {
                    usageTracker.success(invocation)
                }
            }
            result
        } catch (e: CancellationException) {
            usageTracker?.failure(invocation, "cancelled")
            throw e
        } catch (e: SecurityException) {
            usageTracker?.failure(invocation, e.message)
            "Error: 权限不足，无法执行。${e.message ?: toolId}"
        } catch (e: IOException) {
            usageTracker?.failure(invocation, e.message)
            "Error: 权限不足或 I/O 失败，无法执行。${e.message ?: toolId}"
        } catch (e: Throwable) {
            usageTracker?.failure(invocation, "${e::class.simpleName}: ${e.message}")
            "Error: 工具执行失败。${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * 流式执行入口。
     *
     * - 前置检查（查找/门控/校验）失败：发射一条 [ToolStreamEvent.Error] 后结束。
     * - 工具实现了 [StreamingAgentTool]：透传其事件流（手动 collect 以
     *   观察终端事件，统计成败）。
     * - 普通工具：调用 `tool.execute(...)` 并把结果包成单个
     *   [ToolStreamEvent.Output] + [ToolStreamEvent.Complete]（结果以
     *   "Error" 开头时改发 [ToolStreamEvent.Error]）。
     *
     * 异常处理与 [execute] 一致：CancellationException 重抛，其他异常转成
     * [ToolStreamEvent.Error]，保证收集方永远拿到一个完整的事件序列。
     * 整个流在 [Dispatchers.IO] 上执行，避免阻塞调用方。
     */
    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        val tool = when (val pre = pipeline.preCheck(toolId, arguments)) {
            is ToolExecutionPipeline.PreCheck.Failed -> {
                emit(ToolStreamEvent.Error(pre.message))
                return@flow
            }
            is ToolExecutionPipeline.PreCheck.Ready -> pre.tool
        }

        val invocation = usageTracker?.begin(toolId)
        try {
            if (tool is StreamingAgentTool) {
                var sawError = false
                var lastError: String? = null
                tool.executeStream(arguments).collect { event ->
                    if (event is ToolStreamEvent.Error) {
                        sawError = true
                        lastError = event.message
                    }
                    emit(event)
                }
                if (invocation != null) {
                    if (sawError) {
                        usageTracker.failure(invocation, lastError)
                    } else {
                        usageTracker.success(invocation)
                    }
                }
            } else {
                val result = tool.execute(arguments)
                if (result.startsWith("Error")) {
                    usageTracker?.failure(invocation, result)
                    emit(ToolStreamEvent.Error(result))
                } else {
                    usageTracker?.success(invocation)
                    if (result.isNotEmpty()) {
                        emit(ToolStreamEvent.Output(result))
                    }
                    emit(ToolStreamEvent.Complete(result))
                }
            }
        } catch (e: CancellationException) {
            usageTracker?.failure(invocation, "cancelled")
            throw e
        } catch (e: SecurityException) {
            usageTracker?.failure(invocation, e.message)
            emit(ToolStreamEvent.Error("Error: 权限不足，无法执行。${e.message ?: toolId}"))
        } catch (e: IOException) {
            usageTracker?.failure(invocation, e.message)
            emit(ToolStreamEvent.Error("Error: 权限不足或 I/O 失败，无法执行。${e.message ?: toolId}"))
        } catch (e: Throwable) {
            usageTracker?.failure(invocation, "${e::class.simpleName}: ${e.message}")
            emit(ToolStreamEvent.Error("Error: 工具执行失败。${e.message ?: e::class.simpleName}"))
        }
    }.flowOn(Dispatchers.IO)
}
