package com.apex.agent.ui.screen.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.skill.SafeZipExtractor
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
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

/**
 * 终端设置 / 黑名单白名单 / 环境依赖下载中心 的 ViewModel。
 *
 * 设计要点：
 * - 命令黑名单/白名单持久化于 SharedPreferences，供 TerminalManager 执行前校验。
 * - 环境依赖下载中心只负责 **Android 官方工具链**（cmdline-tools / NDK / Platform-tools /
 *   Build-tools）：从 "官方源 + 镜像源" 下载官方 zip，解压到 app 内统一管理目录
 *   (filesDir/sdk)，并把 ANDROID_HOME / PATH 等环境变量注入到交互式 PTY 会话，
 *   使 agent 与用户都能"下载完直接可用"，无需手动配置。通用工具(jdk/git/gradle)
 *   在裸 Android 上无官方可跑二进制，已从下载中心移除（改由用户在 Termux 自行安装）。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalManager: TerminalManager,
    private val httpClient: OkHttpClient
) : ViewModel() {

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)
    private val downloader = SdkDownloader(httpClient)

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
     * 一个可下载的 Android 官方工具依赖项。
     * 不再跑安装命令，而是从 [sources] 中选定一个源下载官方 zip，
     * 解压到 [installDir]，并按 [env] 注入环境变量，使终端/agent 直接可用。
     *
     * @param id 唯一标识
     * @param name 展示名
     * @param sources 下载源列表（第一个为官方，其后是镜像；UI 可下拉选择）
     * @param installDir 解压目标相对 sdkRoot 的子目录（如 "cmdline-tools"、"ndk/27.0.12077973"）
     * @param zipTopDir 官方 zip 解压后的顶层目录名；解压后若该顶层目录与 [installDir] 末段
     *                  不一致，则自动把顶层目录内容归位到 [installDir]（如 cmdline-tools 官方包）
     * @param env 该依赖注入到 PTY 会话的环境变量（ANDROID_HOME、PATH 增量等）
     * @param marker 解压完成后存在即视为已安装的相对路径（如 "bin/sdkmanager"）
     */
    data class DepItem(
        val id: String,
        val name: String,
        val group: DepGroup,
        val sources: List<DownloadSource>,
        val installDir: String,
        val zipTopDir: String? = null,
        val env: Map<String, String> = emptyMap(),
        val marker: String
    )

    data class DownloadSource(
        val label: String,   // 如 "Google 官方" / "清华大学镜像"
        val url: String
    )

    enum class DepGroup { ANDROID }

    // sdkRoot：所有 Android 官方工具链统一解压目录（filesDir/sdk）。
    // 这些组件均有官方 zip 可下载 + 解压 + 配置 ANDROID_HOME/PATH，真正能自动配好。
    // 通用工具(jdk/git/gradle)裸 Android 无官方可跑二进制，已从下载中心移除。
    val sdkRoot: File = File(context.filesDir, "sdk")

    val depItems: List<DepItem> = listOf(
        DepItem(
            id = "cmdline-tools", name = "Android SDK Command-line Tools",
            group = DepGroup.ANDROID,
            sources = listOf(
                DownloadSource("Google 官方", "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"),
                DownloadSource("清华大学镜像", "https://mirrors.tuna.tsinghua.edu.cn/AndroidSDK/commandlinetools-linux-11076708_latest.zip"),
                DownloadSource("阿里云镜像", "https://mirrors.aliyun.com/android.googlesource.com/commandlinetools-linux-11076708_latest.zip")
            ),
            installDir = "cmdline-tools",
            zipTopDir = "cmdline-tools",
            env = mapOf("ANDROID_HOME" to sdkRoot.absolutePath),
            marker = "bin/sdkmanager"
        ),
        DepItem(
            id = "ndk", name = "NDK 27.0.12077973",
            group = DepGroup.ANDROID,
            sources = listOf(
                DownloadSource("Google 官方", "https://dl.google.com/android/repository/android-ndk-r27c-linux.zip"),
                DownloadSource("清华大学镜像", "https://mirrors.tuna.tsinghua.edu.cn/AndroidSDK/android-ndk-r27c-linux.zip")
            ),
            installDir = "ndk/27.0.12077973",
            zipTopDir = "android-ndk-r27c",
            env = mapOf("ANDROID_NDK_HOME" to File(sdkRoot, "ndk/27.0.12077973").absolutePath),
            marker = "ndk-build"
        ),
        DepItem(
            id = "platform-tools", name = "Platform Tools (adb)",
            group = DepGroup.ANDROID,
            sources = listOf(
                DownloadSource("Google 官方", "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"),
                DownloadSource("清华大学镜像", "https://mirrors.tuna.tsinghua.edu.cn/AndroidSDK/platform-tools-latest-linux.zip")
            ),
            installDir = "platform-tools",
            zipTopDir = "platform-tools",
            env = emptyMap(),
            marker = "adb"
        ),
        DepItem(
            id = "build-tools", name = "Build-Tools 35.0.0",
            group = DepGroup.ANDROID,
            sources = listOf(
                DownloadSource("Google 官方", "https://dl.google.com/android/repository/build-tools_r35.0.0-linux.zip"),
                DownloadSource("清华大学镜像", "https://mirrors.tuna.tsinghua.edu.cn/AndroidSDK/build-tools_r35.0.0-linux.zip")
            ),
            installDir = "build-tools/35.0.0",
            zipTopDir = "android-35",
            env = emptyMap(),
            marker = "aapt"
        )
    )

    // 默认是否优先使用镜像源（UI 仍可按项选择具体源）
    private val _useMirror = MutableStateFlow(prefs.getBoolean("dep_use_mirror", true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean("dep_use_mirror", on).apply()
        _useMirror.value = on
    }

    // 安装执行状态：runningId 当前正在下的依赖；progress 0..100；log 过程日志
    data class InstallState(
        val runningId: String? = null,
        val progress: Int = 0,
        val log: String = ""
    )

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** 已安装依赖 id 集合（按 marker 文件落盘判定，持久化）。 */
    private val _installed = MutableStateFlow(loadInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private fun loadInstalled(): Set<String> =
        prefs.getStringSet("dep_installed", emptySet()) ?: emptySet()

    private fun markInstalled(id: String) {
        val next = _installed.value + id
        prefs.edit().putStringSet("dep_installed", next).apply()
        _installed.value = next
    }

    /**
     * 累积的环境变量（所有已安装依赖的 env 合并），注入到交互式 PTY 会话，
     * 使 agent 与用户在终端里直接可用 sdkmanager/adb/ndk-build 等。
     * PATH 由各依赖的 bin 目录 prepend 而成。
     */
    fun buildSessionEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val pathExtras = mutableListOf<String>()
        for (id in _installed.value) {
            val item = depItems.firstOrNull { it.id == id } ?: continue
            item.env.forEach { (k, v) -> env[k] = v }
            val bin = File(sdkRoot, item.installDir).resolve("bin")
            if (bin.exists()) pathExtras += bin.absolutePath
        }
        if (pathExtras.isNotEmpty()) {
            val existing = System.getenv("PATH") ?: ""
            env["PATH"] = (pathExtras + existing).joinToString(":")
        }
        return env
    }

    private var sessionId: Int = -1

    private fun ensureSession(): Int {
        if (sessionId <= 0 || !terminalManager.isAlive(sessionId)) {
            sessionId = terminalManager.createSession()
        }
        return sessionId
    }

    // ═══ 交互式终端（人 / Agent 共用同一 PTY 会话）═══
    // 与依赖安装共用 TerminalManager，但使用独立会话，避免 execute() 的 prompt 检测
    // 与交互输入互相干扰。输出区采用"拉模型"：协程轮询 readOutput 追加到 _output。
    private var interactiveSid: Int = -1
    private var pollJob: kotlinx.coroutines.Job? = null

    data class InteractiveState(
        val output: String = "",
        val alive: Boolean = false,
        val busy: Boolean = false   // 命令执行中（用于禁用输入框/显示状态）
    )

    private val _interactive = MutableStateFlow(InteractiveState())
    val interactive: StateFlow<InteractiveState> = _interactive.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** 确保交互会话存在并启动输出轮询循环。 */
    fun ensureInteractiveSession() {
        if (interactiveSid > 0 && terminalManager.isAlive(interactiveSid)) {
            _interactive.update { it.copy(alive = true) }
            return
        }
        // 注入已安装依赖的环境变量（ANDROID_HOME / PATH 等），使终端/agent 直接可用
        interactiveSid = terminalManager.createSession(envVars = buildSessionEnv())
        if (interactiveSid <= 0) {
            _interactive.update {
                it.copy(alive = false, output = it.output + "\n❌ 无法创建终端会话（设备不支持 PTY）\n")
            }
            return
        }
        _interactive.update { it.copy(alive = true) }
        startPollLoop()
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (interactiveSid > 0 && terminalManager.isAlive(interactiveSid)) {
                val chunk = terminalManager.readOutput(interactiveSid, maxBytes = 4096, stripAnsi = false)
                if (chunk.isNotEmpty()) {
                    _interactive.update { st ->
                        var next = st.output + chunk
                        // 按 maxLines 上限保留尾部，避免无限增长
                        val maxChars = _settings.value.maxLines * 200
                        if (next.length > maxChars) next = next.takeLast(maxChars)
                        st.copy(output = next)
                    }
                } else {
                    kotlinx.coroutines.delay(80)
                }
            }
            _interactive.update { it.copy(alive = false) }
        }
    }

    /**
     * 发送一条命令到交互会话（回显 + 执行 + 实时回流）。
     * 经 isCommandAllowed 校验，被黑名单/白名单拒绝则仅回显拒绝信息，不真正执行。
     */
    fun sendCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        ensureInteractiveSession()
        if (interactiveSid <= 0) return

        _interactive.update { it.copy(output = it.output + "\n$ ${cmd}\n") }
        _history.update { (it + cmd).takeLast(50) }

        if (!isCommandAllowed(cmd)) {
            _interactive.update { it.copy(output = it.output + "⛔ 命令被黑名单/白名单策略拒绝：请到终端设置调整。\n") }
            return
        }

        _interactive.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            terminalManager.sendLine(interactiveSid, cmd)
            // 命令已在轮询循环中实时回流；短暂标记 busy，待 shell 回到可输入态即解除。
            // 这里用简单延时解除（交互式无法精确感知 prompt），避免误判阻塞 UI。
            kotlinx.coroutines.delay(300)
            _interactive.update { it.copy(busy = false) }
        }
    }

    /** 发送特殊按键（如 Ctrl+C 中断、Ctrl+D 等），key 见 TerminalManager.SpecialKey。 */
    fun sendSpecialKey(key: com.apex.agent.platform.terminal.TerminalManager.SpecialKey) {
        if (interactiveSid <= 0) ensureInteractiveSession()
        if (interactiveSid <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            terminalManager.sendKey(interactiveSid, key)
        }
    }

    /** Ctrl+C 中断当前前台进程。 */
    fun interrupt() = sendSpecialKey(com.apex.agent.platform.terminal.TerminalManager.SpecialKey.CTRL_C)

    /** 清空输出显示（不影响会话进程）。 */
    fun clearOutput() = _interactive.update { it.copy(output = "") }

    /** 关闭并重建交互会话（保留历史命令列表）。 */
    fun newSession() {
        pollJob?.cancel()
        if (interactiveSid > 0) terminalManager.closeSession(interactiveSid)
        interactiveSid = -1
        _interactive.update { it.copy(alive = false, busy = false) }
        ensureInteractiveSession()
        _interactive.update { it.copy(output = it.output + "\n── 新会话已创建 ──\n") }
    }

    /**
     * 下载并安装单个依赖项：选源 → 下载 zip（带进度）→ 解压（SafeZipExtractor 防 zip bomb）
     * → 归位顶层目录 → 校验 marker → 标记已装并合并 env。完成后交互终端即可直接使用。
     *
     * @param sourceIndex 选中的下载源下标（0=官方，其余为镜像）；负数表示按镜像偏好自动选。
     */
    fun installDep(item: DepItem, sourceIndex: Int = -1) {
        val src = when {
            sourceIndex >= 0 && sourceIndex < item.sources.size -> item.sources[sourceIndex]
            _useMirror.value && item.sources.size > 1 -> item.sources[1]
            else -> item.sources.first()
        }
        viewModelScope.launch {
            _install.update { it.copy(runningId = item.id, progress = 0, log = it.log + "\n▶ [${item.name}] 从「${src.label}」下载 ${src.url}\n") }
            val zipFile = File(context.cacheDir, "${item.id}.zip")
            val result = downloader.download(src.url, zipFile) { p ->
                _install.update { it.copy(progress = p) }
            }
            if (!result.ok) {
                _install.update { it.copy(runningId = null, log = it.log + "❌ 下载失败：${result.message}\n") }
                return@launch
            }
            _install.update { it.copy(log = it.log + "✓ 下载完成，开始解压…\n") }
            val target = File(sdkRoot, item.installDir)
            try {
                withContext(Dispatchers.IO) {
                    SafeZipExtractor().extract(zipFile, target)
                }
                // 官方 zip 顶层目录名为 zipTopDir，需归位到 installDir 目标
                if (item.zipTopDir != null) {
                    val top = File(target, item.zipTopDir)
                    if (top.exists() && top.isDirectory) {
                        top.listFiles()?.forEach { f ->
                            val dest = File(target, f.name)
                            if (!dest.exists()) f.renameTo(dest)
                        }
                        top.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                _install.update { it.copy(runningId = null, log = it.log + "❌ 解压失败：${e.message}\n") }
                return@launch
            }
            val markerFile = File(target, item.marker)
            if (!markerFile.exists()) {
                _install.update { it.copy(runningId = null, log = it.log + "⚠️ 解压完成但未找到预期文件 ${item.marker}，请检查目录结构。\n") }
                return@launch
            }
            markInstalled(item.id)
            _install.update { it.copy(runningId = null, progress = 100, log = it.log + "✅ 已安装到 ${target.absolutePath}，环境变量已注入终端会话。\n") }
            // 重建交互会话以立即带上新 env，使 agent 与用户在终端里直接可用（打断现有输出可接受）
            newSession()
        }
    }

    /** 一键安装全部依赖（按清单顺序串行下载）。 */
    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = "__all__", log = it.log + "\n▶ 开始安装全部 Android 工具链…\n") }
            depItems.forEachIndexed { index, item ->
                onProgress(index, depItems.size)
                installDepInternal(item)
            }
            _install.update { it.copy(runningId = null, log = it.log + "\n✅ 全部依赖安装完成。\n") }
            // 统一重建交互会话，使全部环境变量一次性注入，终端/Agent 直接可用
            newSession()
        }
    }

    /** Android 开发依赖一键装（本下载中心仅 ANDROID 分组，等价于 installAll）。 */
    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) = installAll(onProgress)

    private suspend fun installDepInternal(item: DepItem) {
        val src = if (_useMirror.value && item.sources.size > 1) item.sources[1] else item.sources.first()
        _install.update { it.copy(runningId = item.id, progress = 0, log = it.log + "\n▶ [${item.name}] 从「${src.label}」下载…\n") }
        val zipFile = File(context.cacheDir, "${item.id}.zip")
        val result = downloader.download(src.url, zipFile) { p -> _install.update { it.copy(progress = p) } }
        if (!result.ok) { _install.update { it.copy(log = it.log + "❌ 下载失败：${result.message}\n") }; return }
        val target = File(sdkRoot, item.installDir)
        try {
            withContext(Dispatchers.IO) { SafeZipExtractor().extract(zipFile, target) }
            if (item.zipTopDir != null) {
                val top = File(target, item.zipTopDir)
                if (top.exists() && top.isDirectory) {
                    top.listFiles()?.forEach { f ->
                        val dest = File(target, f.name)
                        if (!dest.exists()) f.renameTo(dest)
                    }
                    top.deleteRecursively()
                }
            }
        } catch (e: Exception) { _install.update { it.copy(log = it.log + "❌ 解压失败：${e.message}\n") }; return }
        if (!File(target, item.marker).exists()) { _install.update { it.copy(log = it.log + "⚠️ 未找到 ${item.marker}。\n") }; return }
        markInstalled(item.id)
        _install.update { it.copy(progress = 100, log = it.log + "✅ ${item.name} 已安装。\n") }
    }

    override fun onCleared() {
        pollJob?.cancel()
        if (interactiveSid > 0) terminalManager.closeSession(interactiveSid)
        if (sessionId > 0) terminalManager.closeSession(sessionId)
    }
}
