package com.apex.agent.core.codetools.tools

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * # code_todo — 编码任务清单（业界标准 todowrite 契约）
 *
 * 复杂编码任务（多文件重构/功能实现）的进度外化：模型把计划拆成条目，
 * 每完成一项就更新状态。价值：
 *
 * 1. **行为纪律** —— 状态机规则（in_progress 唯一、completed 不可回退除非显式
 *    cancelled）写进工具描述，防"跳步"与"谎报完成"；
 * 2. **用户可见性** —— UI（Code 屏）订阅渲染 checklist，长任务的进展一眼可见；
 * 3. **上下文锚点** —— 状态全量覆盖式更新（每次传完整清单），本身就是任务
 *    状态的快照，压缩后也不丢。
 *
 * 会话作用域：实例由 CodeModule 提供 @Singleton，clear() 由 ViewModel 在新会话
 * 开始时调用；[snapshot] 供 UI 轮询/订阅渲染。
 */
class CodeTodoTool : BaseTool(
    id = "code_todo",
    name = "Code Todo",
    description = """
        Create and maintain a structured task list for the current coding session.
        Tracks progress, organizes multi-step work, and surfaces status to the user.

        ## When to use
        - Any task with 3+ steps or non-trivial multi-part work
        - The user provides multiple tasks (numbered or comma-separated)
        - A new instruction arrives mid-task — capture it in the list so it is
          not lost, then continue the current work
        - Starting work on an item — mark it in_progress BEFORE beginning it

        ## When NOT to use
        - A single trivial task
        - A purely conversational or informational exchange

        ## States
        - pending — not started yet
        - in_progress — currently being worked on (exactly one at a time)
        - completed — done and verified
        - cancelled — abandoned (keep it visible with the reason)

        ## Rules
        - Update the list in real time as you work; never batch updates.
        - Mark an item completed ONLY after verification succeeded
          (build/tests/read-back) — never based on intent or expectation.
        - If blocked, keep the item in_progress and add a new item describing
          the blocker.
        - Keep user-provided command text verbatim in item content.
        - Items must be concrete and actionable ("run tests for X",
          not "work on Y").
        - The list is fully replaced on every call — always send the complete,
          current list.

        Priorities: high (blockers/core functionality) > medium > low (polish).

        When in doubt, keep a todo list.
    """.trimIndent(),
    declaredSchema = toolSchema {
        // ARRAY/OBJECT 由 raw element 读入（DSL 层声明 array 供 schema 渲染）
        string("todos", required = true, description = "JSON array: [{\"content\": \"...\", \"status\": \"pending|in_progress|completed|cancelled\", \"priority\": \"high|medium|low\"}]")
    }
) {

    /** 一条待办。 */
    data class Todo(
        val content: String,
        val status: String,
        val priority: String
    )

    private val state = AtomicReference<List<Todo>>(emptyList())
    private val json = Json { ignoreUnknownKeys = true }

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.AGENT)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("todo")
        // 注解修正：code_todo 维护会话态（整体覆盖清单），不是只读 ——
        // readOnly 谎报会让重试/批放行策略按"无副作用"处理。
        annotations(com.apex.agent.core.tools.ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val todosRaw = args.requireString("todos")

        val parsed = try {
            json.parseToJsonElement(todosRaw)
        } catch (e: Exception) {
            return ToolResult.fail(ToolErrorCode.INVALID_JSON, "todos is not valid JSON: ${e.message}")
        }
        val array = try {
            parsed.jsonArray
        } catch (e: Exception) {
            // 容错：单对象 → 包装成数组
            try {
                JsonArray(listOf(parsed.jsonObject))
            } catch (e2: Exception) {
                return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "todos must be a JSON array of objects")
            }
        }

        val todos = mutableListOf<Todo>()
        for (element in array) {
            val obj = try {
                element.jsonObject
            } catch (e: Exception) {
                return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "todos entries must be objects")
            }
            val content = obj["content"]?.jsonPrimitive?.content
                ?: return ToolResult.fail(ToolErrorCode.MISSING_ARGUMENT, "todo entry missing 'content'")
            val status = obj["status"]?.jsonPrimitive?.content ?: "pending"
            if (status !in VALID_STATUSES) {
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "invalid status '$status' — must be one of ${VALID_STATUSES.joinToString("/")}"
                )
            }
            val priority = obj["priority"]?.jsonPrimitive?.content ?: "medium"
            if (priority !in VALID_PRIORITIES) {
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "invalid priority '$priority' — must be one of ${VALID_PRIORITIES.joinToString("/")}"
                )
            }
            todos += Todo(content.take(200), status, priority)
        }
        if (todos.size > 50) {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "too many todos (${todos.size}, max 50)")
        }

        state.set(todos.toList())
        return ToolResult.ok(render(todos))
    }

    /** 当前快照（UI 渲染用）。 */
    fun snapshot(): List<Todo> = state.get()

    /** 会话重置。 */
    fun clear() = state.set(emptyList())

    /**
     * 会话恢复（#152）：用持久层快照整体替换当前清单。
     *
     * 与 [clear] 对称的写入口——CodeViewModel 恢复编码会话时回填 todos，
     * 使进程被杀后 Todo 面板与持久快照一致（防御性 toList 拷贝，
     * 与 execute 的写入语义一致）。
     */
    fun restore(todos: List<Todo>) = state.set(todos.toList())

    private fun render(todos: List<Todo>): String {
        if (todos.isEmpty()) return "todo list cleared"
        val lines = todos.mapIndexed { i, t ->
            val mark = when (t.status) {
                "completed" -> "[x]"
                "in_progress" -> "[>]"
                "cancelled" -> "[-]"
                else -> "[ ]"
            }
            val pr = if (t.priority == "high") " (!)" else ""
            "$mark ${i + 1}. ${t.content}$pr"
        }
        val done = todos.count { it.status == "completed" }
        val total = todos.size
        return "todo: $done/$total completed\n" + lines.joinToString("\n") +
            "\n(next: read the result carefully and keep statuses honest)"
    }

    private companion object {
        val VALID_STATUSES = setOf("pending", "in_progress", "completed", "cancelled")
        val VALID_PRIORITIES = setOf("high", "medium", "low")
    }
}
