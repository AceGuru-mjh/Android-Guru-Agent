package com.apex.agent.platform.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 终端会话管理器
 *
 * 职责：
 * - 创建/销毁PTY会话
 * - 在会话中执行命令（自动等待完成）
 * - 发送交互输入
 * - 读取输出
 * - 会话状态跟踪
 *
 * 命令完成检测策略：
 * 发送命令后，持续读取输出直到检测到"输出稳定"
 * （连续N毫秒无新数据），认为命令已完成
 */
@Singleton
class TerminalManager @Inject constructor() {

    private val pty = NativePty()
    private val sessions = mutableMapOf<Int, SessionInfo>()

    companion object {
        private const val DEFAULT_ROWS = 50
        private const val DEFAULT_COLS = 120
        private const val READ_CHUNK_SIZE = 8192
        private const val SETTLE_TIME_MS = 300L    // 输出稳定判定时间
        private const val MAX_SETTLE_WAIT_MS = 2000L // 最大稳定等待
    }

    // ═══ 会话生命周期 ═══

    fun createSession(
        shell: String = "/system/bin/sh",
        workDir: String = "/data/local/tmp",
        envVars: Map<String, String> = emptyMap(),
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS
    ): Int {
        val envKeys = if (envVars.isNotEmpty()) envVars.keys.toTypedArray() else null
        val envVals = if (envVars.isNotEmpty()) envVars.values.toTypedArray() else null

        val sessionId = pty.nativeCreateSession(shell, workDir, envKeys, envVals, rows, cols)

        if (sessionId > 0) {
            sessions[sessionId] = SessionInfo(
                id = sessionId,
                shell = shell,
                workDir = workDir,
                pid = pty.nativeGetPid(sessionId)
            )
            // 等待shell启动并消费初始提示符
            Thread.sleep(100)
            drainOutput(sessionId)
        }

        return sessionId
    }

    fun closeSession(sessionId: Int) {
        pty.nativeCloseSession(sessionId)
        sessions.remove(sessionId)
    }

    fun closeAll() {
        pty.nativeCloseAll()
        sessions.clear()
    }

    fun isAlive(sessionId: Int): Boolean = pty.nativeIsAlive(sessionId)

    fun getSessionInfo(sessionId: Int): SessionInfo? = sessions[sessionId]

    fun listSessions(): List<SessionInfo> = sessions.values.toList()

    // ═══ 命令执行 ═══

    /**
     * 执行命令并等待完成
     *
     * 策略：
     * 1. 清空缓冲区
     * 2. 发送命令 + \n
     * 3. 循环读取输出，直到"输出稳定"或超时
     * 4. 返回清理后的输出
     */
    suspend fun execute(
        sessionId: Int,
        command: String,
        timeoutMs: Long = 30000
    ): CommandResult = withContext(Dispatchers.IO) {
        val info = sessions[sessionId]
            ?: return@withContext CommandResult("Error: Session $sessionId not found", sessionAlive = false)

        if (!pty.nativeIsAlive(sessionId)) {
            info.state = SessionState.DEAD
            return@withContext CommandResult("Error: Session $sessionId is dead", sessionAlive = false)
        }

        info.state = SessionState.RUNNING
        info.lastCommand = command
        info.totalCommandsExecuted++

        val startTime = System.currentTimeMillis()

        // 清空之前的残留输出
        drainOutput(sessionId)

        // 发送命令
        pty.nativeWrite(sessionId, command)

        // 等待输出稳定
        val output = StringBuilder()
        var lastDataTime = System.currentTimeMillis()
        var settleStart = 0L

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMs) {
                info.state = SessionState.IDLE
                return@withContext CommandResult(
                    output = output.toString(),
                    timedOut = true,
                    sessionAlive = pty.nativeIsAlive(sessionId),
                    durationMs = elapsed
                )
            }

            if (pty.nativeWaitForData(sessionId, 50)) {
                val chunk = pty.nativeRead(sessionId, READ_CHUNK_SIZE, true)
                if (chunk.isNotEmpty()) {
                    output.append(chunk)
                    lastDataTime = System.currentTimeMillis()
                    settleStart = 0
                }
            }

            // 检查输出是否稳定
            val now = System.currentTimeMillis()
            if (output.isNotEmpty() && now - lastDataTime > SETTLE_TIME_MS) {
                if (settleStart == 0L) {
                    settleStart = now
                } else if (now - settleStart > SETTLE_TIME_MS) {
                    // 输出已稳定，命令完成
                    break
                }
            }

            // 防止无限等待（无输出的命令如 cd, export）
            if (output.isEmpty() && now - lastDataTime > MAX_SETTLE_WAIT_MS) {
                break
            }

            // 检查会话是否死亡
            if (!pty.nativeIsAlive(sessionId)) {
                break
            }
        }

        // 最终读取残余数据
        val remaining = pty.nativeRead(sessionId, READ_CHUNK_SIZE, true)
        if (remaining.isNotEmpty()) output.append(remaining)

        info.state = SessionState.IDLE
        val duration = System.currentTimeMillis() - startTime

        CommandResult(
            output = output.toString().trimEnd(),
            sessionAlive = pty.nativeIsAlive(sessionId),
            durationMs = duration
        )
    }

    // ═══ 交互式输入 ═══

    /**
     * 发送原始输入（不自动加换行）
     */
    fun sendRaw(sessionId: Int, input: String): Boolean {
        return pty.nativeWriteRaw(sessionId, input)
    }

    /**
     * 发送一行输入（自动加换行）
     */
    fun sendLine(sessionId: Int, line: String): Boolean {
        return pty.nativeWrite(sessionId, line)
    }

    /**
     * 发送特殊按键
     */
    fun sendKey(sessionId: Int, key: SpecialKey): Boolean {
        val sequence = when (key) {
            SpecialKey.ENTER -> "\n"
            SpecialKey.CTRL_C -> "\u0003"
            SpecialKey.CTRL_D -> "\u0004"
            SpecialKey.CTRL_Z -> "\u001A"
            SpecialKey.CTRL_L -> "\u000C"
            SpecialKey.TAB -> "\t"
            SpecialKey.ESCAPE -> "\u001B"
            SpecialKey.UP -> "\u001B[A"
            SpecialKey.DOWN -> "\u001B[B"
            SpecialKey.RIGHT -> "\u001B[C"
            SpecialKey.LEFT -> "\u001B[D"
            SpecialKey.HOME -> "\u001B[H"
            SpecialKey.END -> "\u001B[F"
            SpecialKey.PAGE_UP -> "\u001B[5~"
            SpecialKey.PAGE_DOWN -> "\u001B[6~"
            SpecialKey.DELETE -> "\u001B[3~"
            SpecialKey.BACKSPACE -> "\u007F"
        }
        return pty.nativeWriteRaw(sessionId, sequence)
    }

    /**
     * 发送信号
     */
    fun sendSignal(sessionId: Int, signal: UnixSignal): Boolean {
        val sigNum = when (signal) {
            UnixSignal.SIGINT -> 2
            UnixSignal.SIGQUIT -> 3
            UnixSignal.SIGTERM -> 15
            UnixSignal.SIGKILL -> 9
            UnixSignal.SIGHUP -> 1
        }
        return pty.nativeSendSignal(sessionId, sigNum)
    }

    // ═══ 输出读取 ═══

    /**
     * 读取当前可用输出（非阻塞）
     */
    fun readOutput(sessionId: Int, maxBytes: Int = READ_CHUNK_SIZE, stripAnsi: Boolean = true): String {
        return pty.nativeRead(sessionId, maxBytes, stripAnsi)
    }

    /**
     * 等待并读取输出
     */
    suspend fun waitAndRead(sessionId: Int, timeoutMs: Int = 5000, maxBytes: Int = READ_CHUNK_SIZE): String {
        return withContext(Dispatchers.IO) {
            if (pty.nativeWaitForData(sessionId, timeoutMs)) {
                pty.nativeRead(sessionId, maxBytes, true)
            } else {
                ""
            }
        }
    }

    /**
     * 清空输出缓冲区
     */
    fun drainOutput(sessionId: Int) {
        var total = 0
        while (pty.nativeHasData(sessionId) && total < 65536) {
            val chunk = pty.nativeRead(sessionId, READ_CHUNK_SIZE, false)
            total += chunk.length
            if (chunk.isEmpty()) break
        }
    }

    // ═══ 终端控制 ═══

    fun resize(sessionId: Int, rows: Int, cols: Int) {
        pty.nativeResize(sessionId, rows, cols)
    }

    fun getPid(sessionId: Int): Int = pty.nativeGetPid(sessionId)

    fun activeCount(): Int = pty.nativeActiveCount()
}

enum class SpecialKey {
    ENTER, CTRL_C, CTRL_D, CTRL_Z, CTRL_L,
    TAB, ESCAPE,
    UP, DOWN, RIGHT, LEFT,
    HOME, END, PAGE_UP, PAGE_DOWN,
    DELETE, BACKSPACE
}

enum class UnixSignal {
    SIGINT, SIGQUIT, SIGTERM, SIGKILL, SIGHUP
}
