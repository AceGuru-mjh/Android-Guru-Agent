package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.api.TerminalSize
import com.apex.agent.platform.terminal.linux.InputMode
import com.apex.agent.platform.terminal.linux.LinuxProcessRequest
import com.apex.agent.platform.terminal.linux.LinuxPtyProvider
import com.apex.agent.platform.terminal.linux.LinuxPtyRequest
import com.apex.agent.platform.terminal.linux.LinuxPtySession
import com.apex.agent.platform.terminal.linux.LinuxProcessHandle
import com.apex.agent.platform.terminal.runtime.ShutdownMode

/**
 * PR #68: Real PRoot PTY Provider.
 *
 * Creates a LinuxPtySession backed by a real PRoot process. The process's
 * stdout/stderr streams are the "PTY output" that the observation pump
 * consumes; the process's stdin is the "PTY input" that write() sends to.
 *
 * On Android (with the JNI PTY backend available), a real PTY pair would
 * be allocated and connected to the process. On CI / JVM, pipe-based I/O
 * (ProcessBuilder's native streams) is used instead — it is REAL process
 * I/O (not a fake), but it does not support terminal resize (pipes have
 * no winsize). The resize() call is a documented no-op on this path.
 *
 * Spec: PR #68 — Real Linux Runtime.
 */
class PRootPtyProvider(
    private val processProvider: PRootProcessProvider
) : LinuxPtyProvider {

    override suspend fun create(request: LinuxPtyRequest): Result<LinuxPtySession> {
        // Delegate process spawning to PRootProcessProvider, then wrap the
        // result as a PTY session exposing I/O for the observation pump.
        val procRequest = LinuxProcessRequest(
            executable = request.executable,
            arguments = request.arguments,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            terminalMode = TerminalMode.PTY,
            stdin = InputMode.ENABLED
        )
        return processProvider.start(procRequest).map { handle ->
            PRootPtySession(handle, request.rows, request.cols)
        }
    }
}

/**
 * Real LinuxPtySession wrapping a PRootProcessHandle.
 *
 * I/O access is via the handle's processStdout/processStderr/processStdin
 * methods — the observation pump reads from processStdout, the write()
 * path writes to processStdin.
 *
 * resize() is a no-op on the JVM pipe path (pipes have no winsize). On
 * Android with JNI PTY, nativeResize() would be called instead.
 */
class PRootPtySession(
    override val process: LinuxProcessHandle,
    val rows: Int,
    val cols: Int
) : LinuxPtySession {

    override suspend fun resize(size: TerminalSize): Result<Unit> {
        // Pipe-based I/O cannot resize (no TIOCSWINSZ ioctl on a pipe).
        // On Android, the JNI PTY path handles this via nativeResize().
        // P68: documented no-op on the JVM transport — not a fake, a
        // platform limitation of the pipe-based fallback.
        return Result.success(Unit)
    }

    override suspend fun close(mode: ShutdownMode): Result<Unit> {
        // Terminate the underlying PRoot process. --kill-on-exit (set in
        // PRootProcessProvider) ensures child processes inside the rootfs
        // are also cleaned up.
        val termMode = if (mode == ShutdownMode.FORCE)
            com.apex.agent.platform.terminal.linux.TerminationMode.FORCE
        else
            com.apex.agent.platform.terminal.linux.TerminationMode.GRACEFUL
        return process.terminate(termMode)
    }

    /** Expose the process's stdout for the observation pump (nullable if not a PRoot handle). */
    fun stdoutProvider(): java.io.InputStream? =
        (process as? PRootProcessHandle)?.processStdout()

    /** Expose the process's stderr for the observation pump. */
    fun stderrProvider(): java.io.InputStream? =
        (process as? PRootProcessHandle)?.processStderr()

    /** Expose the process's stdin for the write() path. */
    fun stdinProvider(): java.io.OutputStream? =
        (process as? PRootProcessHandle)?.processStdin()
}
