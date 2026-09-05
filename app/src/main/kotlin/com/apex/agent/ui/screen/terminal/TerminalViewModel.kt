package com.apex.agent.ui.screen.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.wait.WaitCondition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 终端设置 / 黑名单白名单 / 环境依赖下载中心 的 ViewModel。
 *
 * ATR 2.0 重构：底层从 [com.apex.agent.platform.terminal.TerminalManager]（已删除）切换到
 * [TerminalRuntime] + [EnvironmentProvisioner]。
 *
 * 设计要点：
 * - 命令黑名单/白名单持久化于 SharedPreferences，供 PolicyEngine 执行前校验。
 * - 环境依赖清单内置官方源 + 镜像地址，可一键全装 / 独立装 / Android 开发依赖一键装。
 * - "安装"动作 = 生成安装命令并通过 [TerminalRuntime] 在终端会话中执行（run + wait +
 *   observe），输出实时回流到 [installLog]，UI 直接展示。
 * - 公开 API 与旧版完全兼容（TerminalScreen.kt 零改动）。
 *
 * Spec ref: ATR 2.0 Final Spec §41 / §43
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalRuntime: TerminalRuntime
) : ViewModel() {

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    /** 环境依赖安装器（ATR 2.0 — 用新 Runtime API，非旧 TerminalManager）。 */
    private val provisioner = EnvironmentProvisioner(terminalRuntime)

    init {
        // Crash recovery (Spec §39): restore persisted sessions on startup.
        viewModelScope.launch {
            val recovered = terminalRuntime.recover()
            if (recovered.isNotEmpty()) {
                android.util.Log.i("TerminalVM", "Recovered ${recovered.size} sessions from persistence")
            }
        }
    }

    // ═══ 终端设置 ═══
    data class TerminalSettings(
        val fontSize: Int = 13,
        val maxLines: Int = 1000,
        val monochrome: Boolean = false
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    fun updateSettings(block: TerminalSettings.() -> TerminalSettings) {
        val next = _settings.value.block()
        prefs.edit()
            .putInt("term_font_size", next.fontSize)
            .putInt("term_max_lines", next.maxLines)
            .putBoolean("term_monochrome", next.monochrome)
            .apply()
        _settings.value = next
    }

    private fun loadSettings() = TerminalSettings(
        fontSize = prefs.getInt("term_font_size", 13),
        maxLines = prefs.getInt("term_max_lines", 1000),
        monochrome = prefs.getBoolean("term_monochrome", false)
    )

    // ═══ 黑名单 / 白名单命令 ═══
    private val _blacklist = MutableStateFlow(loadSet("cmd_blacklist"))
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    private val _whitelist = MutableStateFlow(loadSet("cmd_whitelist"))
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    fun addBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { add(normalize(cmd)) }
    fun removeBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { remove(normalize(cmd)) }
    fun addWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { add(normalize(cmd)) }
    fun removeWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { remove(normalize(cmd)) }

    fun isCommandAllowed(command: String): Boolean {
        val head = command.trim().substringBefore(' ').lowercase()
        if (head.isEmpty()) return true
        if (_blacklist.value.any { head == it || command.lowercase().startsWith(it) }) return false
        val wl = _whitelist.value
        if (wl.isNotEmpty()) {
            return wl.any { head == it || command.lowercase().startsWith(it) }
        }
        return true
    }

    private fun normalize(cmd: String) = cmd.trim().lowercase().substringBefore(' ')

    private fun loadSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun editSet(key: String, flow: MutableStateFlow<Set<String>>, mutate: MutableSet<String>.() -> Unit) {
        val next = flow.value.toMutableSet().apply(mutate)
        prefs.edit().putStringSet(key, next).apply()
        flow.value = next
    }

    // ═══ 环境依赖下载中心 ═══
    /**
     * 一个可安装的环境依赖项（UI 兼容类型 — 委托给 environment/DepItem）。
     */
    data class DepItem(
        val id: String,
        val name: String,
        val group: DepGroup,
        val installOfficial: String,
        val installMirror: String,
        val checkCommand: String
    )

    enum class DepGroup { GENERAL, ANDROID }

    // v2 修复：旧清单是 Windows 的 winget/scoop 命令，在 Android PTY 里必然失败。
    // 现在委托给 environment/DepCatalog 单一数据源（apt/Ubuntu proot 命令），
    // 同时消除 TerminalViewModel 与 DepCatalog 两份清单漂移。
    val depItems: List<DepItem> =
        com.apex.agent.environment.DepCatalog.ALL.map {
            DepItem(it.id, it.name, DepGroup.valueOf(it.group.name), it.installOfficial, it.installMirror, it.checkCommand)
        }

    // 镜像源开关
    private val _useMirror = MutableStateFlow(prefs.getBoolean("dep_use_mirror", true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean("dep_use_mirror", on).apply()
        _useMirror.value = on
        provisioner.setUseMirror(on)
    }

    // 安装执行状态
    data class InstallState(
        val runningId: String? = null,
        val log: String = "",
        val useMirror: Boolean = true
    )

    private val _install = MutableStateFlow(InstallState(useMirror = _useMirror.value))
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private var sessionId: Long? = null

    // ═══ 终端屏幕状态（供 renderer 订阅，Spec §41）═══
    private val _semanticState = MutableStateFlow<TerminalSemanticState?>(null)
    val semanticState: StateFlow<TerminalSemanticState?> = _semanticState.asStateFlow()

    /** 真实终端屏幕文本（observe SCREEN，供 TerminalRenderer 渲染）。Spec §41。 */
    private val _screenText = MutableStateFlow("")
    val screenText: StateFlow<String> = _screenText.asStateFlow()

    /**
     * 订阅屏幕状态（事件驱动，非轮询）。Spec §41 — PTY output → VT → Flow → Compose.
     * PtyOutputPump pushes screen updates via ObservationEngine.screenState; we collect the Flow.
     */
    fun observeScreenState() {
        val sid = sessionId ?: return
        viewModelScope.launch {
            // Collect push-based semantic state (no polling)
            terminalRuntime.semanticStateFlow(sid)?.collect { state ->
                _semanticState.value = state
            }
        }
        viewModelScope.launch {
            // Collect push-based screen state (no polling — emits on every VT update)
            terminalRuntime.screenStateFlow(sid)?.collect { screen ->
                _screenText.value = screen.renderedText ?: ""
            }
        }
    }

    private suspend fun ensureSession(): Long? {
        if (sessionId == null) {
            val r = terminalRuntime.create()
            if (r.isSuccess) {
                sessionId = r.getOrThrow().sessionId
                observeScreenState()
            }
        }
        return sessionId
    }

    /** 安装单个依赖项。 */
    fun installDep(item: DepItem) {
        val useMirror = _useMirror.value
        val cmd = if (useMirror) item.installMirror else item.installOfficial
        runCommand(item.id, cmd)
    }

    /** 一键安装全部环境依赖。 */
    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = "__all__", log = it.log + "▶ 开始安装全部环境依赖（镜像=${_useMirror.value}）…\n") }
            depItems.forEachIndexed { index, item ->
                onProgress(index, depItems.size)
                val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                execAndAppend(item.id, cmd)
            }
            _install.update { it.copy(runningId = null, log = it.log + "\n✅ 全部依赖安装命令已执行完毕。请查看上方输出确认结果。\n") }
        }
    }

    /** Android 开发依赖一键装。 */
    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val items = depItems.filter { it.group == DepGroup.ANDROID }
            _install.update { it.copy(runningId = "__android__", log = it.log + "▶ 开始安装 Android 开发依赖（镜像=${_useMirror.value}）…\n") }
            items.forEachIndexed { index, item ->
                onProgress(index, items.size)
                val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                execAndAppend(item.id, cmd)
            }
            _install.update { it.copy(runningId = null, log = it.log + "\n✅ Android 开发依赖安装命令已执行完毕。\n") }
        }
    }

    private fun runCommand(id: String, cmd: String) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = id, log = it.log + "\n▶ [$id] $cmd\n") }
            execAndAppend(id, cmd)
            _install.update { it.copy(runningId = null) }
        }
    }

    /**
     * 用新 Runtime API 执行命令（run + wait + observe），非旧 TerminalManager.execute。
     * settle-time 已删除，完成靠 waitpid 确认（Spec §4.1）。
     */
    private suspend fun execAndAppend(id: String, cmd: String) {
        val sid = ensureSession() ?: run {
            _install.update { it.copy(log = it.log + "❌ 无法创建终端会话（设备不支持 PTY）\n") }
            return
        }
        val output = withContext(Dispatchers.IO) {
            // 1. run (非阻塞)
            val runResult = terminalRuntime.run(sid, cmd, InputOwner.SYSTEM, background = false)
            val run = runResult.getOrElse { return@withContext "❌ run 失败: ${it.message}\n" }
            // 2. wait PROCESS_EXITED (可靠，非 settle-time)
            val waitResult = terminalRuntime.wait(sid, WaitCondition.ProcessExited(jobId = run.jobId), 120_000)
            val wait = waitResult.getOrElse { return@withContext "❌ wait 失败: ${it.message}\n" }
            val exitCode = when (wait) {
                is com.apex.agent.platform.terminal.wait.WaitResult.Matched -> {
                    val ev = wait.event
                    if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode ?: -1 else 0
                }
                is com.apex.agent.platform.terminal.wait.WaitResult.Timeout -> {
                    terminalRuntime.signal(sid, com.apex.agent.platform.terminal.io.UnixSignal.SIGKILL, InputOwner.SYSTEM, run.jobId)
                    return@withContext "⚠️ 超时（120s），可能仍在后台进行。\n"
                }
                is com.apex.agent.platform.terminal.wait.WaitResult.SessionGone -> return@withContext "❌ 会话已关闭\n"
            }
            // 3. observe RAW output since startCursor
            val obs = terminalRuntime.observe(sid, TerminalRuntime.ObserveMode.RAW, run.startCursor, 65536)
                .getOrNull()?.raw ?: ""
            val tail = if (obs.length > 4000) "…(已截断)\n" + obs.takeLast(4000) else obs
            tail + if (exitCode != 0) "\n[exit=$exitCode]\n" else "\n"
        }
        _install.update { it.copy(log = it.log + output) }
    }

    override fun onCleared() {
        // Runtime owns session lifecycle; explicit close via terminal.close() by Agent/UI.
        // 这里不主动 close，因为 Runtime 是单例，session 可能被其他消费者复用。
    }
}
