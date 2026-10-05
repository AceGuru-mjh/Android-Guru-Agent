package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * #205 MCP 启动事件追踪器 —— 每台服务器一份环形事件历史。
 *
 * **为什么需要**：[McpStartupListener] 是即发即弃的回调，弹窗关掉（或
 * 连接完成后再想看）事件就没了。真实启动失败的用户排障路径是「刚才那台
 * 为什么没连上」—— 追踪器把事件留底：
 * - 连接瞬间：监听器写 UI 弹窗 + 落 tracker；
 * - 事后：installed 列表点「时间线」→ [snapshot] 回放（pid/argv/
 *   serverInfo/stderr 逐条可查，全部是当时真实发生的记录）。
 *
 * ## 设计约束
 * - **线程模型**：所有可变状态在 [lock] 监视器内（连接路径在 IO 线程回调，
 *   读路径在主线程快照）；
 * - **环形淘汰**：每台服务器最多 [capacity] 条，超了丢最旧（保序不丢新）；
 * - **时钟注入**：[record] 用注入的 [clock] 重打时间戳（生产环境即
 *   System.currentTimeMillis，测试注入假钟可精确断言）；
 * - **截断**：detail 超 [maxDetailLength]（默认 512）截断加省略号 —— stderr
 *   一行可能是一整个 stack trace，时间线列表不该被一条日志撑爆。
 */
class McpStartupTracker(
    /** 每台服务器的最大留存条数。 */
    private val capacity: Int = 32,
    /** 时间源（测试注入假钟）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单条 detail 的最大长度。 */
    private val maxDetailLength: Int = 512
) {
    private val lock = Any()
    private val buffers = HashMap<String, ArrayDeque<McpStartupEvent>>()

    /** #206：tools 清单的最近一次记录（去重比较用 —— 不再反解 detail 字符串）。 */
    private val lastTools = HashMap<String, List<String>>()

    private val _events = MutableSharedFlow<McpStartupEvent>(extraBufferCapacity = 64)
    /** 热流：record 时向活跃订阅者投递（无订阅者时静默丢弃，不缓存重放）。 */
    val events: SharedFlow<McpStartupEvent> = _events.asSharedFlow()

    /**
     * 记录一条事件：重打时间戳（注入钟）、截断超长 detail、环形淘汰。
     * 空服务器名直接忽略（防御性 —— 事件构造点的 name 来自配置键）。
     */
    fun record(event: McpStartupEvent) {
        if (event.serverName.isBlank()) return
        val stamped = event.copy(
            timestampMs = clock(),
            detail = if (event.detail.length > maxDetailLength) {
                event.detail.take(maxDetailLength) + "…"
            } else {
                event.detail
            }
        )
        synchronized(lock) {
            val queue = buffers.getOrPut(event.serverName) { ArrayDeque() }
            queue.addLast(stamped)
            while (queue.size > capacity) queue.removeFirst()
        }
        _events.tryEmit(stamped)
    }

    /**
     * tools/list 结果去重重记：清单（排序去重后）与上次一致则不记录 ——
     * connect 成功后的发现与手动重连的发现不会在时间线上重复两遍。
     * #206：比较基准存 [lastTools]（结构化状态），不再反解析 detail 文本
     * （旧思路在工具数 >6 截断后永远比对失败）。
     */
    fun recordToolsIfChanged(serverName: String, toolNames: List<String>) {
        val canonical = toolNames.distinct().sorted()
        if (canonical.isEmpty()) return
        synchronized(lock) {
            val last = lastTools[serverName]
            if (last == canonical) return
            lastTools[serverName] = canonical
        }
        record(
            McpStartupEvent(
                serverName = serverName,
                stage = McpStartupStage.TOOLS_DISCOVERED,
                detail = "发现 ${canonical.size} 个工具：" +
                    canonical.take(6).joinToString("、") +
                    if (canonical.size > 6) " …" else ""
            )
        )
    }

    /** 某台服务器的事件快照（保序拷贝；无记录返回空表）。 */
    fun snapshot(serverName: String): List<McpStartupEvent> = synchronized(lock) {
        buffers[serverName]?.toList() ?: emptyList()
    }

            /** 是否有任意记录（时间线入口的可见性判定）。 */
    fun hasEvents(serverName: String): Boolean =
        synchronized(lock) { !buffers[serverName].isNullOrEmpty() }

    /** 清空某台服务器的历史（removeServer 时调用，防泄漏）。 */
    fun clear(serverName: String) {
        synchronized(lock) {
            buffers.remove(serverName)
            lastTools.remove(serverName)
        }
    }

    /** 清空全部。 */
    fun clearAll() {
        synchronized(lock) {
            buffers.clear()
            lastTools.clear()
        }
    }
}
