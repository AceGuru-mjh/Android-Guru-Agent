package com.apex.agent.ui.screen.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.platform.terminal.TerminalManager
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
 * 设计要点：
 * - 命令黑名单/白名单持久化于 SharedPreferences，供 TerminalManager 执行前校验。
 * - 环境依赖清单内置官方源 + 镜像地址，可一键全装 / 独立装 / Android 开发依赖一键装。
 * - "安装"动作 = 生成安装命令并通过已注入的 [TerminalManager] 在终端会话中执行，
 *   输出实时回流到 [installLog]，UI 直接展示（不臆造装不成功的自动下载器）。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalManager: TerminalManager
) : ViewModel() {

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

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
    // 语义：白名单非空时，只允许执行白名单中的命令前缀；黑名单中的命令前缀永远禁止。
    // 匹配规则：以空格切分命令，取第一段（如 "rm" / "adb"）做前缀匹配，大小写不敏感。
    private val _blacklist = MutableStateFlow(loadSet("cmd_blacklist"))
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    private val _whitelist = MutableStateFlow(loadSet("cmd_whitelist"))
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    fun addBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { add(normalize(cmd)) }
    fun removeBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { remove(normalize(cmd)) }
    fun addWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { add(normalize(cmd)) }
    fun removeWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { remove(normalize(cmd)) }

    /** 命令校验：被黑名单命中 → 拒绝；白名单非空且未命中 → 拒绝；否则放行。 */
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
     * 一个可安装的环境依赖项。
     * @param id 唯一标识
     * @param name 展示名
     * @param group 分组（通用 / Android 开发）
     * @param officialUrl 官方源命令
     * @param mirrorCommand 镜像源命令（更快/国内可达）
     * @param checkCommand 用于检测是否已安装的命令
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

    val depItems: List<DepItem> = listOf(
        DepItem(
            id = "jdk17", name = "JDK 17", group = DepGroup.GENERAL,
            installOfficial = "winget install Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements",
            installMirror = "scoop install adopt17-hotspot",
            checkCommand = "java -version"
        ),
        DepItem(
            id = "git", name = "Git", group = DepGroup.GENERAL,
            installOfficial = "winget install Git.Git --accept-source-agreements --accept-package-agreements",
            installMirror = "scoop install git",
            checkCommand = "git --version"
        ),
        DepItem(
            id = "gradle", name = "Gradle 8.10", group = DepGroup.GENERAL,
            installOfficial = "winget install Gradle.Gradle --version 8.10 --accept-source-agreements",
            installMirror = "scoop install gradle@8.10",
            checkCommand = "gradle --version"
        ),
        DepItem(
            id = "android-sdk", name = "Android SDK (cmdline-tools)", group = DepGroup.ANDROID,
            installOfficial = "winget install Google.AndroidSDK --accept-source-agreements --accept-package-agreements",
            installMirror = "scoop install android-sdk",
            checkCommand = "sdkmanager --version"
        ),
        DepItem(
            id = "ndk", name = "NDK 27.0.12077973", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"ndk;27.0.12077973\"",
            installMirror = "sdkmanager \"ndk;27.0.12077973\"",
            checkCommand = "ls \$ANDROID_HOME/ndk/27.0.12077973 >/dev/null 2>&1 && echo NDK_OK"
        ),
        DepItem(
            id = "platform-tools", name = "Platform Tools (adb)", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"platform-tools\"",
            installMirror = "scoop install adb",
            checkCommand = "adb --version"
        ),
        DepItem(
            id = "build-tools", name = "Build-Tools 35.0.0", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"build-tools;35.0.0\"",
            installMirror = "sdkmanager \"build-tools;35.0.0\"",
            checkCommand = "ls \$ANDROID_HOME/build-tools/35.0.0 >/dev/null 2>&1 && echo BT_OK"
        )
    )

    // 镜像源开关：true 时用镜像命令，false 用官方命令
    private val _useMirror = MutableStateFlow(prefs.getBoolean("dep_use_mirror", true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean("dep_use_mirror", on).apply()
        _useMirror.value = on
    }

    // 安装执行状态
    data class InstallState(
        val runningId: String? = null,
        val log: String = "",
        val useMirror: Boolean = true
    )

    private val _install = MutableStateFlow(InstallState(useMirror = _useMirror.value))
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private var sessionId: Int = -1

    private fun ensureSession(): Int {
        if (sessionId <= 0 || !terminalManager.isAlive(sessionId)) {
            sessionId = terminalManager.createSession()
        }
        return sessionId
    }

    /** 安装单个依赖项（用官方/镜像命令）。 */
    fun installDep(item: DepItem) {
        val useMirror = _useMirror.value
        val cmd = if (useMirror) item.installMirror else item.installOfficial
        runCommand(item.id, cmd)
    }

    /** 一键安装全部环境依赖（按清单顺序串行执行）。 */
    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = "__all__", log = "▶ 开始安装全部环境依赖（镜像=${_useMirror.value}）…\n") }
            depItems.forEachIndexed { index, item ->
                onProgress(index, depItems.size)
                val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                execAndAppend(item.id, cmd)
            }
            _install.update { it.copy(runningId = null, log = it.log + "\n✅ 全部依赖安装命令已执行完毕。请查看上方输出确认结果。\n") }
        }
    }

    /** Android 开发依赖一键装（仅 ANDROID 分组）。 */
    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val items = depItems.filter { it.group == DepItem.DepGroup.ANDROID }
            _install.update { it.copy(runningId = "__android__", log = "▶ 开始安装 Android 开发依赖（镜像=${_useMirror.value}）…\n") }
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

    private suspend fun execAndAppend(id: String, cmd: String) {
        val sid = ensureSession()
        if (sid <= 0) {
            _install.update { it.copy(log = it.log + "❌ 无法创建终端会话（设备不支持 PTY）\n") }
            return
        }
        val result = withContext(Dispatchers.IO) {
            terminalManager.execute(sid, cmd, timeoutMs = 120_000)
        }
        val tail = if (result.output.length > 4000) "…(已截断)\n" + result.output.takeLast(4000) else result.output
        _install.update { st ->
            st.copy(log = st.log + tail + if (result.timedOut) "\n⚠️ 超时（120s），可能仍在后台进行。\n" else "\n")
        }
    }

    override fun onCleared() {
        if (sessionId > 0) terminalManager.closeSession(sessionId)
    }
}
