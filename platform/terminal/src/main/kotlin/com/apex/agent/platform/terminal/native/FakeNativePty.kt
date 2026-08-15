package com.apex.agent.platform.terminal.native

import com.apex.agent.platform.terminal.io.UnixSignal
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM test double for [NativePty]. No Android, no NDK, no real forkpty.
 *
 * Purpose: let the entire Runtime (SessionManager → PtyOutputPump → InputManager → EventLog →
 * EventBus → WaitEngine → ObservationEngine) be exercised in plain JVM unit tests.
 *
 * Behavior:
 *   - Maintains an in-memory "shell" per session that simulates a minimal /system/bin/sh.
 *   - Commands like `echo X`, `pwd`, `ls`, `sleep N`, `exit`, `false`, `yes` are recognized.
 *   - Unknown commands produce a "command not found" + exit code 127.
 *   - Output is queued into a per-session buffer; nativeRead drains it non-blocking.
 *   - nativeSendSignal sets a flag; the "shell" checks it between output chunks and aborts
 *     the running command with the appropriate exit code (130 for SIGINT, etc.).
 *
 * This is NOT a full shell — it's a minimal simulator sufficient for Runtime-level tests.
 * Real PTY behavior (VT sequences, job control, /proc) is out of scope; vendored Termux
 * (Phase 2) handles VT; real Android handles /proc.
 */
class FakeNativePty : NativePty {

    private data class Session(
        val id: Int,
        val shell: String,
        val cwd: String,
        var rows: Int,
        var cols: Int,
        val pid: Int,
        val outputBuffer: StringBuilder = StringBuilder(),
        val commandQueue: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
        var alive: AtomicBoolean = AtomicBoolean(true),
        var exited: AtomicBoolean = AtomicBoolean(false),
        var exitCode: AtomicInteger = AtomicInteger(-1),
        var currentCommand: String? = null,
        var commandThread: Thread? = null,
        var interrupted: AtomicBoolean = AtomicBoolean(false),
        var runningJob: AtomicBoolean = AtomicBoolean(false),
        val startTime: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val idCounter = AtomicInteger(0)
    private val pidCounter = AtomicInteger(20000)

    override fun nativeCreateSession(shell: String, cwd: String, rows: Int, cols: Int, env: Array<String>): Int {
        val id = idCounter.incrementAndGet()
        val pid = pidCounter.incrementAndGet()
        val s = Session(id, shell, cwd, rows, cols, pid)
        sessions[id] = s
        // emit initial prompt
        s.outputBuffer.append("FakeNativePty shell ready\n\$ ")
        return id
    }

    override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
        val s = sessions[sessionId] ?: return -1
        if (!s.alive.get()) return -1
        val text = String(bytes, offset, len, Charsets.UTF_8)
        s.outputBuffer.append(text)
        // If a newline arrives, the line is a command — execute async.
        val nl = text.indexOf('\n')
        if (nl >= 0) {
            val line = text.substring(0, nl).trim()
            if (line.isNotEmpty()) {
                executeCommand(s, line)
            }
        }
        return len
    }

    override fun nativeWriteRaw(sessionId: Int, text: String): Int {
        return nativeWrite(sessionId, text.toByteArray(Charsets.UTF_8), 0, text.length)
    }

    override fun nativeRead(sessionId: Int, buffer: ByteArray, maxBytes: Int): Int {
        val s = sessions[sessionId] ?: return -1
        val avail = s.outputBuffer.length
        if (avail == 0) return 0
        val n = minOf(avail, maxBytes)
        for (i in 0 until n) buffer[i] = (s.outputBuffer[i].code and 0xFF).toByte()
        s.outputBuffer.delete(0, n)
        return n
    }

    override fun nativeHasData(sessionId: Int): Boolean {
        val s = sessions[sessionId] ?: return false
        return s.outputBuffer.isNotEmpty()
    }

    override fun nativeWaitForData(sessionId: Int, timeoutMs: Long): Boolean {
        val s = sessions[sessionId] ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (s.outputBuffer.isNotEmpty()) return true
            Thread.sleep(5)
        }
        return s.outputBuffer.isNotEmpty()
    }

    override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
        val s = sessions[sessionId] ?: return false
        s.interrupted.set(true)
        // SIGINT(2)→130, SIGTERM(15)→143, SIGKILL(9)→137, SIGHUP(1)→129, SIGQUIT(3)→131
        val exit = 128 + signal
        s.exitCode.set(exit)
        s.runningJob.set(false)
        return true
    }

    override fun nativeResize(sessionId: Int, rows: Int, cols: Int): Boolean {
        val s = sessions[sessionId] ?: return false
        s.rows = rows; s.cols = cols
        return true
    }

    override fun nativeIsAlive(sessionId: Int): Boolean {
        val s = sessions[sessionId] ?: return false
        return s.alive.get()
    }

    override fun nativeGetPid(sessionId: Int): Int {
        return sessions[sessionId]?.pid ?: -1
    }

    override fun nativeGetExitCode(sessionId: Int): Int {
        val s = sessions[sessionId] ?: return -1
        return if (s.exited.get()) s.exitCode.get() else -1
    }

    override fun nativeWaitExit(sessionId: Int, timeoutMs: Long): Int {
        val s = sessions[sessionId] ?: return -1
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (s.exited.get()) return s.exitCode.get()
            Thread.sleep(10)
        }
        return -1
    }

    override fun nativeCloseSession(sessionId: Int): Boolean {
        val s = sessions.remove(sessionId) ?: return false
        s.alive.set(false)
        s.exited.set(true)
        return true
    }

    override fun nativeCloseAll() {
        for (id in sessions.keys.toList()) nativeCloseSession(id)
    }

    override fun nativeActiveCount(): Int = sessions.size

    override fun nativeListSessionIds(): IntArray = sessions.keys.toIntArray()

    // ─── minimal shell simulator ───

    private fun executeCommand(s: Session, line: String) {
        s.currentCommand = line
        s.runningJob.set(true)
        s.interrupted.set(false)
        // Async so commands like `sleep` can be interrupted.
        s.commandThread = Thread({
            try {
                val out = StringBuilder()
                val parts = line.split(Regex("\\s+"))
                val cmd = parts.firstOrNull() ?: ""
                val args = parts.drop(1)
                var exit = 0
                when {
                    cmd == "echo" -> out.append(args.joinToString(" ")).append('\n')
                    cmd == "pwd" -> out.append(s.cwd).append('\n')
                    cmd == "ls" -> out.append(".\n..\n")
                    cmd == "cd" -> { if (args.isNotEmpty()) s.cwd = args[0] }
                    cmd == "exit" -> { s.alive.set(false); s.exited.set(true); s.exitCode.set(0) }
                    cmd == "false" -> exit = 1
                    cmd == "true" -> exit = 0
                    cmd == "sleep" -> {
                        val secs = args.firstOrNull()?.toLongOrNull() ?: 1
                        val end = System.currentTimeMillis() + secs * 1000
                        while (System.currentTimeMillis() < end) {
                            if (s.interrupted.get()) { exit = 130; break }
                            Thread.sleep(50)
                        }
                    }
                    cmd == "yes" -> {
                        // produce output continuously until interrupted
                        val word = args.firstOrNull() ?: "y"
                        var count = 0
                        while (count < 10_000 && !s.interrupted.get()) {
                            synchronized(s.outputBuffer) { s.outputBuffer.append(word).append('\n') }
                            count++
                            Thread.sleep(2)
                        }
                        exit = if (s.interrupted.get()) 130 else 0
                    }
                    cmd == "gradlew" || cmd == "./gradlew" -> {
                        // simulate a build with progress lines
                        for (pct in 0..100 step 10) {
                            if (s.interrupted.get()) { exit = 130; break }
                            out.append("BUILD [${pct}%]\n")
                            Thread.sleep(20)
                        }
                        if (exit == 0) out.append("BUILD SUCCESSFUL\n")
                    }
                    else -> { out.append("$cmd: command not found\n"); exit = 127 }
                }
                if (out.isNotEmpty()) synchronized(s.outputBuffer) { s.outputBuffer.append(out) }
                if (!s.exited.get()) {
                    s.outputBuffer.append("\$ ")
                }
                s.exitCode.set(if (s.interrupted.get() && exit == 0) 130 else exit)
            } catch (e: Throwable) {
                s.exitCode.set(1)
                s.outputBuffer.append("error: ${e.message}\n\$ ")
            } finally {
                s.runningJob.set(false)
                s.currentCommand = null
                if (!s.alive.get()) s.exited.set(true)
            }
        }, "fake-pty-${s.id}-cmd").apply { isDaemon = true; start() }
    }

    /** Test helper: inject raw output bytes (simulates a program writing to stdout). */
    fun injectOutput(sessionId: Int, text: String) {
        val s = sessions[sessionId] ?: return
        synchronized(s.outputBuffer) { s.outputBuffer.append(text) }
    }
}
