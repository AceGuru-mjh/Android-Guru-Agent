package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════════
//  进程抽象层
// ══════════════════════════════════════════════════════════════════════

/**
 * 被启动起来的 MCP 服务器进程句柄（stdio 型）。
 *
 * 抽象而不是直接用 [Process]，是为了让不同宿主替换"怎么把这个命令跑起来"：
 * - JVM / Android 默认实现 [JvmProcessLauncher]：走 `ProcessBuilder`；
 * - 后续可换成「Ubuntu / PRoot 沙箱」内的 launcher（本项目 platform:terminal
 *   已具备真 rootfs），从而支持 `npx -y @modelcontextprotocol/server-x` 这类命令。
 *
 * 这也是 Operit 的做法的关键结论：**MCP 不一定要是远端 HTTP 服务器**，
 * 官方 MCP 配置里绝大多数 server 其实是 `command` + `args` + `env` 的本地进程，
 * 双方通过 stdin/stdout 上的换行分隔 JSON-RPC 通信。
 */
interface McpProcessHandle {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    fun isAlive(): Boolean
    fun destroy()
}

/** 进程启动器接口：把 [command]（含参数）在 [env] 环境下运行起来。 */
fun interface McpProcessLauncher {
    /**
     * @param command 命令 + 参数（第一个元素为可执行文件）
     * @param env 环境变量
     * @param workingDir 工作目录，可为 null
     * @throws Exception 命令不存在或无法执行时抛出（调用方负责转成友好错误）
     */
    fun launch(command: List<String>, env: Map<String, String>, workingDir: File?): McpProcessHandle
}

/**
 * 默认实现：JDK `ProcessBuilder`。
 *
 * - 把子进程 stderr 合并到 stdout 会污染 JSON-RPC 流，因此这里 stderr 独立暴露；
 * - 继承 `IO` 环境变量基础上覆盖 [env]，避免 PATH 丢失导致找不到可执行文件。
 */
object JvmProcessLauncher : McpProcessLauncher {
    override fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDir: File?
    ): McpProcessHandle {
        require(command.isNotEmpty()) { "命令为空" }
        val builder = ProcessBuilder(command)
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }
        if (workingDir != null) builder.directory(workingDir)
        builder.redirectErrorStream(false)
        val process = builder.start()
        return ProcessAdapter(process)
    }

    private class ProcessAdapter(private val process: Process) : McpProcessHandle {
        override val stdin: OutputStream get() = process.outputStream
        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream
        override fun isAlive(): Boolean = process.isAlive
        override fun destroy() = process.destroy()
    }
}

// ══════════════════════════════════════════════════════════════════════
//  传输句柄
// ══════════════════════════════════════════════════════════════════════

/**
 * MCP 传输句柄 —— 屏蔽"对面到底是一个远端 HTTP 服务还是本地子进程"。
 *
 * 之前 [McpClient] 把所有字节搬运硬编成 HTTP POST，导致 `transport=STDIO`
 * 只是一个枚举值（`/* 暂未实现 */`）：选了 STDIO 仍然去 POST 那根 URL，
 * 于是"支持三种传输"是假的。现在 HTTP/SSE 与 STDIO 各自实现同一接口。
 */
interface McpTransportHandle {
    /**
     * 发送一条 JSON-RPC 报文。
     *
     * @param id 请求 id；为 null 表示**通知**（`notifications/*`），不等待响应
     * @param payload 序列化后的报文全文
     * @return 响应对象；通知返回 null
     */
    suspend fun send(id: Int?, payload: String): JsonObject?

    /** 判断底层通道是否仍然可用（进程存活 / 连接未断）。 */
    fun isHealthy(): Boolean

    fun close()
}

/**
 * stdio 传输：本地进程 + 换行分隔 JSON-RPC（NDJSON）。
 *
 * 实现要点：
 * - 单一读线程把子进程 stdout 里的每一行按 `id` 投放到 [pending]，请求线程在
 *   监视器上等待自己那条 —— 标准 MCP stdio 允许**乱序/穿插通知**，必须按 id 匹配；
 * - 不是 JSON 的行（很多 server 会往 stdout 打日志）只跳过、不抛错，
 *   这也是官方 SDK 的兼容做法；
 * - 进程死掉时等待中的请求会立刻返回 null，上层折叠成明确错误而不是挂死。
 */
class McpStdioTransport internal constructor(
    private val handle: McpProcessHandle,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestTimeoutMs: Long = 60_000L
) : McpTransportHandle {

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(handle.stdin, StandardCharsets.UTF_8))
    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(handle.stdout, StandardCharsets.UTF_8))

    private val pending = ConcurrentHashMap<Int, String>()
    private val monitor = Object()

    @Volatile
    private var closed = false

    private val pump: Thread = thread(name = "mcp-stdio-pump", isDaemon = true) { pumpLoop() }

    constructor(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        launcher: McpProcessLauncher = JvmProcessLauncher,
        workingDir: File? = null,
        requestTimeoutMs: Long = 60_000L
    ) : this(
        handle = launcher.launch(command, env, workingDir),
        requestTimeoutMs = requestTimeoutMs
    )

    override suspend fun send(id: Int?, payload: String): JsonObject? = withContext(Dispatchers.IO) {
        if (closed) throw McpException("MCP 本地进程已关闭")
        if (!handle.isAlive()) throw McpException("MCP 本地进程已退出")

        // 报文必须是单行：内容里的换行会撕裂 NDJSON 帧
        val line = payload.replace('\n', ' ').replace('\r', ' ')
        writer.write(line)
        writer.write('\n'.code)
        writer.flush()

        if (id == null) return@withContext null

        val raw = awaitResponse(id) ?: throw McpException(
            if (handle.isAlive()) "等待 MCP 进程响应超时（${requestTimeoutMs}ms）"
            else "MCP 进程在响应前退出"
        )
        return@withContext try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: throw McpException("MCP 进程返回了非对象响应")
        } catch (e: SerializationException) {
            throw McpException("MCP 进程返回了非法 JSON：${raw.take(120)}")
        }
    }

    override fun isHealthy(): Boolean = !closed && handle.isAlive()

    override fun close() {
        closed = true
        synchronized(monitor) { monitor.notifyAll() }
        runCatching { writer.close() }
        runCatching { handle.destroy() }
        runCatching { pump.interrupt() }
    }

    private fun awaitResponse(id: Int): String? {
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        synchronized(monitor) {
            while (true) {
                pending.remove(id)?.let { return it }
                if (closed || !handle.isAlive()) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                monitor.wait(remaining.coerceAtMost(250L))
            }
        }
    }

    private fun pumpLoop() {
        while (!closed) {
            val line = try {
                reader.readLine() ?: break
            } catch (_: Exception) {
                break
            }
            if (line.isBlank()) continue
            val id = extractId(line) ?: continue   // 通知 / 非 JSON 行：直接丢弃
            synchronized(monitor) {
                pending[id] = line
                monitor.notifyAll()
            }
        }
        // 进程 EOF：唤醒所有等待者，让它们尽快拿到失败结论而不是干等超时
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun extractId(line: String): Int? = runCatching {
        val obj = json.parseToJsonElement(line) as? JsonObject ?: return null
        obj["id"]?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
