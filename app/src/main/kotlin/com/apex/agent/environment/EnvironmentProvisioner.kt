package com.apex.agent.environment

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.platform.terminal.wait.WaitCondition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer-environment provisioner. Installs JDK / Gradle / SDK / NDK / etc.
 *
 * Spec ref: ATR 2.0 Final Spec §38 / §43
 *
 * EXTRACTED from the old `TerminalViewModel.kt` (which mixed terminal settings + blacklist +
 * dependency installation + session lifecycle). The Runtime itself does NOT know about JDK /
 * Gradle / NDK / SDK mirrors — those belong here, in the `DeveloperEnvironment` module.
 *
 * Install flow (per dep):
 *   1. Run checkCommand via terminal.run + wait(PROCESS_EXITED)
 *      → if exit 0, already installed, skip
 *   2. Otherwise run installCommand(useMirror) via terminal.run
 *   3. Wait PROCESS_EXITED (reliable, no settle-time)
 *   4. Stream output to installLog StateFlow
 *   5. Re-run checkCommand to verify
 *
 * Uses the new Runtime API (terminal.run + terminal.wait + terminal.observe), NOT the old
 * TerminalManager.execute with settle-time.
 *
 * T82 断点修复：[DepCatalog] 的命令全部是 Ubuntu（proot）内的 apt 命令 ——
 * 必须跑在 linux-ubuntu backend 会话里。此前产品层把 apt 命令投进 Android
 * shell（local session）→ `apt-get: command not found`，依赖下载中心 100% 失效。
 * [ensureUbuntuSession] 先幂等拉起 Ubuntu 生命周期（install→bootstrap→READY），
 * 再创建/复用 linux-ubuntu 会话；[ubuntuLifecycle]=null 时（兼容旧构造）诚实降级。
 */
class EnvironmentProvisioner(
    private val runtime: TerminalRuntime,
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator? = null
) {
    data class InstallState(
        val depId: String? = null,
        val running: Boolean = false,
        val log: String = "",
        val done: Boolean = false,
        val exitCode: Int? = null
    )

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private val _useMirror = MutableStateFlow(false)
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(v: Boolean) { _useMirror.value = v }

    val depItems: List<DepItem> get() = DepCatalog.ALL

    // ═══ T82: Ubuntu 路由（DepCatalog apt 命令的正确执行环境）═══

    private var ubuntuSessionId: Long? = null

    /**
     * T82 断点修复：确保存在一个 linux-ubuntu backend 会话（DepCatalog 的 apt 命令
     * 只在 Ubuntu 环境里真实可执行）。幂等：已创建则复用。
     *
     * 流程：ensureReady（install→bootstrap→capability，幂等且进度不丢）→
     * create(backend="linux-ubuntu")。任一环节失败返回 null（调用方诚实降级到
     * local session —— 输出里会出现真实的 command not found，而非伪造成功）。
     */
    suspend fun ensureUbuntuSession(): Long? {
        ubuntuSessionId?.let { return it }
        val lc = ubuntuLifecycle ?: return null
        when (val r = lc.ensureReady()) {
            is UbuntuLifecycleCoordinator.EnsureResult.Ready,
            is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady -> Unit
            is UbuntuLifecycleCoordinator.EnsureResult.InProgress -> {
                appendLog("⚠️ Ubuntu 环境仍在准备中（${r.message}）— 稍后重试\n")
                return null
            }
            is UbuntuLifecycleCoordinator.EnsureResult.Failed -> {
                appendLog("❌ Ubuntu 环境准备失败（${r.stage.name}: ${r.message}）" +
                    (if (r.retryable) " — 可重试" else "") + "\n")
                return null
            }
            is UbuntuLifecycleCoordinator.EnsureResult.Cancelled -> {
                appendLog("⚠️ Ubuntu 环境准备被取消\n")
                return null
            }
        }
        val created = runtime.create(backendId = "linux-ubuntu")
        val sessionId = created.getOrNull()?.sessionId
        if (sessionId == null) {
            appendLog("❌ Ubuntu 会话创建失败：${created.exceptionOrNull()?.message}\n")
            return null
        }
        ubuntuSessionId = sessionId
        return sessionId
    }

    /** Ubuntu 生命周期状态（UI 订阅展示安装/引导进度）。 */
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState>? =
        ubuntuLifecycle?.stateFlow

    /** 依赖安装的正确 session：Ubuntu 可用走 linux-ubuntu，否则诚实降级 local。 */
    suspend fun sessionForApt(): Long = ensureUbuntuSession()
        ?: runtime.create().getOrNull()?.sessionId?.also { /* local fallback：apt 命令将真实失败（command not found）*/ }
        ?: -1L

    /** Install a single dependency. Returns the final exit code (0 = success). */
    suspend fun installDep(sessionId: Long, item: DepItem): Int {
        val cmd = item.installCommand(_useMirror.value)
        _install.value = InstallState(depId = item.id, running = true, log = "$ ${cmd}\n", done = false)

        val exitCode = runCommand(sessionId, cmd, append = true)
        _install.value = _install.value.copy(running = false, done = true, exitCode = exitCode)
        return exitCode
    }

    /** Install all dependencies in GENERAL then ANDROID group. Calls onProgress after each. */
    suspend fun installAll(sessionId: Long, onProgress: (DepItem, Int) -> Unit) {
        for ((i, dep) in depItems.withIndex()) {
            val code = installDep(sessionId, dep)
            onProgress(dep, code)
        }
    }

    /** Install only ANDROID-group deps. */
    suspend fun installAndroidOnly(sessionId: Long, onProgress: (DepItem, Int) -> Unit) {
        for (dep in depItems.filter { it.group == DepGroup.ANDROID }) {
            val code = installDep(sessionId, dep)
            onProgress(dep, code)
        }
    }

    /** Check if a dep is already installed (runs checkCommand, returns true if exit 0). */
    suspend fun isInstalled(sessionId: Long, item: DepItem): Boolean {
        val code = runCommand(sessionId, item.checkCommand, append = false)
        return code == 0
    }

    /** Run a command via the new Runtime API (run + wait + observe). Returns exit code. */
    private suspend fun runCommand(sessionId: Long, command: String, append: Boolean): Int {
        val runResult = runtime.run(sessionId, command, owner = InputOwner.SYSTEM, background = false)
        val run = runResult.getOrElse {
            appendLog("ERROR: ${it.message}\n")
            return -1
        }
        val waitResult = runtime.wait(
            sessionId = sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = 300_000L   // 5 min per command
        )
        val wait = waitResult.getOrElse {
            appendLog("ERROR: ${it.message}\n")
            return -1
        }
        val exitCode = when (wait) {
            is com.apex.agent.platform.terminal.wait.WaitResult.Matched -> {
                val ev = wait.event
                if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited)
                    ev.exitCode ?: -1
                else 0
            }
            is com.apex.agent.platform.terminal.wait.WaitResult.Timeout -> {
                appendLog("TIMEOUT\n")
                runtime.signal(sessionId, com.apex.agent.platform.terminal.io.UnixSignal.SIGKILL, InputOwner.SYSTEM, run.jobId)
                -1
            }
            is com.apex.agent.platform.terminal.wait.WaitResult.SessionGone -> -1
        }
        // observe the command's output (since its startCursor)
        val obs = runtime.observe(
            sessionId = sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = 65536
        ).getOrNull()
        if (obs != null && append) {
            appendLog(obs.raw ?: "")
        }
        return exitCode
    }

    private fun appendLog(text: String) {
        _install.value = _install.value.copy(log = _install.value.log + text)
    }
}
