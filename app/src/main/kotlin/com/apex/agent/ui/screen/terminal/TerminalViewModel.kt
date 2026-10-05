package com.apex.agent.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.KeyEventMapping
import com.apex.agent.platform.terminal.io.KeySequenceEncoder
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.terminalemulator.MouseEncoder
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.terminalemulator.encodeFocusEvent
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 交互式终端 ViewModel（P83 — Terminal 产品化）。
 *
 * ## P0-1（结构治理）：职责拆分
 *
 * 本类此前把多会话生命周期、styled/semantic 收集、输入行缓冲、设置持久化、
 * 黑白名单、扩展键、Ubuntu 生命周期联动、依赖安装路由、剪贴板通知 9 类职责
 * 挤在 1193 行里（文件里甚至自注「VM 行数预算纪律」）。现拆为五个协作组件，
 * VM 只保留**编排与会话生命周期决策**：
 *
 *  - [SessionRegistry]：多会话元数据簿（tabs / 活跃 id / backend / title /
 *    存活修剪 —— P1-1 合并双表）；
 *  - [TerminalRenderOrchestrator]：活跃会话的 styled/semantic/OSC52 收集
 *    路由 + 会话快照缓存（P0-2 切换不闪空屏）；
 *  - [LineMirror]：交互行镜像（黑白名单提交时刻检查的输入侧契约 —— P1-2
 *    把「什么操作会破坏镜像」收口到一处）；
 *  - [TerminalSettingsStore]：终端设置 / 扩展键持久化；
 *  - [CommandPolicyStore]：命令黑白名单持久化 + 门禁判定。
 *
 * 保留在 VM 的职责：多会话 create/close/restart 决策、输入的模式感知编码
 * （DECCKM 箭头 / bracketed paste / xterm 修饰协议）、Ubuntu 生命周期联动、
 * 依赖安装路由（[EnvironmentProvisioner]）、通知 —— P1-4 类型化为
 * [TerminalNotice]，VM 在发出时刻决定语义，UI 不再做关键词嗅探。
 *
 * 数据流（Spec §41 事件驱动，非轮询）：
 *   PTY → PtyOutputPump → VT(TerminalCore) → ObservationEngine.styledState →
 *   (sample 33ms) → renderState → Compose grid。
 *
 * Spec ref: ATR 2.0 Final Spec §41 / §43 + P83 Terminal Finalization。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalRuntime: TerminalRuntime,
    // T82: Ubuntu 产品级生命周期 —— 依赖安装中心的路由底座（apt 命令只能跑在 Ubuntu 会话）。
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator,
    // i18n：通知/安装日志文案（非 Compose 场景，LanguageManager 按当前语言取词）
    private val lang: LanguageManager
) : ViewModel() {

    // ═══════════════ P0-1：协作组件（构造顺序敏感：prefs/registry 先于各 store）═══════════════

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    /** 多会话元数据簿（tabs/活跃 id/backend/title —— UI 唯一数据源）。 */
    internal val registry = SessionRegistry(terminalRuntime)

    /** 活跃会话渲染收集路由 + 会话快照缓存。 */
    internal val renderOrchestrator = TerminalRenderOrchestrator(terminalRuntime)

    /** 交互行镜像（黑白名单提交时刻检查；失效语义见 [LineMirror]）。 */
    private val lineMirror = LineMirror()

    /** 终端设置 / 扩展键持久化。 */
    private val settingsStore = TerminalSettingsStore(prefs)

    /** 命令黑白名单持久化 + 门禁判定。 */
    private val policyStore = CommandPolicyStore(prefs)

    /** 环境依赖安装中心（内嵌 ATR 2.0 [EnvironmentProvisioner] —— 用新 Runtime API，非旧 TerminalManager）。 */
    internal val depCenter = TerminalDepCenter(
        scope = viewModelScope,
        prefs = prefs,
        runtime = terminalRuntime,
        provisioner = EnvironmentProvisioner(terminalRuntime, ubuntuLifecycle),
        registry = registry,
        lang = lang,
        onSessionsChanged = ::refreshSessions
    )

    /** T87：命令历史（提交时刻记录；设置抽屉可查看/清空）。 */
    private val commandHistory =
        com.apex.agent.ui.screen.terminal.history.TerminalCommandHistory(context)

    // T82: Ubuntu 生命周期状态（安装/引导进度，UI 可订阅）。
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState> =
        ubuntuLifecycle.stateFlow

    // ═══════════════ 会话状态流（委托 registry / orchestrator）═══════════════

    /** 顶部 tab 的会话列表。 */
    val sessions: StateFlow<List<SessionTab>> = registry.sessionTabs

    /** 活跃会话 id。 */
    val activeSessionId: StateFlow<Long?> = registry.activeSessionId

    /** 活跃会话的 styled 屏（颜色/光标/scrollback；null = 未启动）。 */
    val renderState: StateFlow<TerminalRenderSnapshot?> = renderOrchestrator.renderState

    /** 活跃会话的语义状态（会话状态 / 前台 job / prompt）。 */
    val semanticState: StateFlow<TerminalSemanticState?> = renderOrchestrator.semanticState

    /** 活跃会话最新快照的便捷读（模式感知编码读 DECCKM / bracketed paste 等标志）。 */
    private val renderSnapshot: TerminalRenderSnapshot?
        get() = renderOrchestrator.renderState.value

    // ═══════════════ 通知（P1-4 类型化）═══════════════

    /**
     * 终端操作反馈（toast 级消息，渲染在状态条）。
     *
     * P1-4：旧版是裸 `String?`，UI 侧靠 `contains("失败")/contains("error")`
     * 关键词嗅探决定红/绿配色 —— 文案一改错误样式就静默丢失，中英混合判断
     * 不可靠。现在 VM 在**发出时刻**决定 [Kind]，UI 只读 kind 选色。
     */
    data class TerminalNotice(
        val text: String,
        val kind: Kind
    ) {
        enum class Kind { INFO, ERROR, FALLBACK }
    }

    private val _notice = MutableStateFlow<TerminalNotice?>(null)
    val notice: StateFlow<TerminalNotice?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    private fun notifyInfo(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.INFO)
    }

    private fun notifyError(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.ERROR)
    }

    /** 降级类（Ubuntu 失败 → 自动切 Android Shell：非错误但值得警示）。 */
    private fun notifyFallback(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.FALLBACK)
    }

    /** 非 stringResource 来源的反馈（UbuntuLifecycleCoordinator 直出消息）。 */
    private fun notifyRaw(text: String, kind: TerminalNotice.Kind) {
        if (text.isNotBlank()) _notice.value = TerminalNotice(text, kind)
    }

    // ═══════════════ 交互终端：会话管理 ═══════════════════════

    /**
     * T85: Ubuntu 优先默认会话策略的挂起标记 —— 等待环境 READY 期间用户未手工
     * 创建过会话时，READY 到达后自动拉起 Ubuntu 会话（见 [init] 的第二个收集器）。
     * 用户一旦自己建了会话（含环境面板的「先用 Android Shell」）即失效。
     */
    @Volatile
    private var autoUbuntuSessionPending = false

    /** 会话创建互斥（UI 手动新建 / READY 自动拉起 / 依赖安装降级共享）。 */
    private val createMutex = Mutex()

    private var pollJob: kotlinx.coroutines.Job? = null
    private var creating = false

    init {
        // Crash recovery (Spec §39): restore persisted sessions on startup.
        viewModelScope.launch {
            // T94：recover 链含 SessionMetadataStore.loadAll（逐文件 readText）
            // + /proc/<pid> 存在性检查 —— 全是磁盘 IO，包 IO 域避免主线程
            // StrictMode 违例（与 create/close 链同型修复）。
            val recovered = withContext(Dispatchers.IO) { terminalRuntime.recover() }
            if (recovered.isNotEmpty()) {
                // #223：恢复的会话现已真实进入 snapshot()（EXITED/BROKEN 只读
                // 视图）—— tab 列表可见、选中即见「已中断 + 重启会话」覆盖层。
                // 旧实现打出 "Recovered N" 后列表里什么都看不到（恢复态从未
                // 注册进任何地方）；日志与列表现在终于一致。
                Log.i("TerminalVM", "Recovered ${recovered.size} interrupted sessions (visible as dead tabs)")
                notifyFallback(R.string.term_notice_recovered, recovered.size)
            }
            refreshSessionsInternal()
            if (registry.sessionTabs.value.none { it.isAlive }) {
                // T85: Ubuntu 优先 —— 有完整 Linux 环境绝不默认降级到 Android toybox。
                // - READY → 直接建 Ubuntu 会话（bash / gcc / python3 真实可用）；
                // - 未 READY → 挂起等待（ApexApp 启动时已自动预备；这里 join 单飞
                //   兜底「冷启动直接进终端页」的竞态），READY 后自动建；
                // - FAILED → 停在环境面板等用户重试，不偷偷降级。
                if (ubuntuLifecycle.stateFlow.value.phase ==
                    UbuntuLifecycleCoordinator.Phase.READY
                ) {
                    createMutex.withLock { createSessionInternal(backendId = BACKEND_UBUNTU) }
                } else {
                    autoUbuntuSessionPending = true
                    // join 自动预备（幂等单飞）。★ 降级兜底（输入失灵根因）：
                    // ensureReady 失败/超时且用户未手工建过会话时，自动拉起
                    // LOCAL 会话，不再停在空屏等用户。
                    val r = withContext(Dispatchers.IO) {
                        runCatching { ubuntuLifecycle.ensureReady() }.getOrNull()
                    }
                    // 仅「明确失败」才降级：InProgress（超时续跑）是环境面板的
                    // 正常进行态（进度可见 + 「先用 Android Shell」按钮可达）。
                    val failed = r == null || r is UbuntuLifecycleCoordinator.EnsureResult.Failed
                    if (failed && autoUbuntuSessionPending &&
                        registry.sessionTabs.value.none { it.isAlive }
                    ) {
                        autoUbuntuSessionPending = false
                        val reason = (r as? UbuntuLifecycleCoordinator.EnsureResult.Failed)
                            ?.message?.take(80)
                        notifyFallback(R.string.term_notice_fallback_session, reason ?: "…")
                        createMutex.withLock { createSessionInternal(backendId = BACKEND_LOCAL) }
                    }
                }
            } else {
                registry.sessionTabs.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
            startSessionPolling()
        }

        // T85: 环境 READY 到达 → 若用户仍未手工建过会话，自动拉起 Ubuntu 会话。
        // 首次安装（后台解包 2~5 分钟）期间用户停在环境面板，无需任何点击。
        viewModelScope.launch {
            ubuntuLifecycle.stateFlow.collect { st ->
                if (st.phase == UbuntuLifecycleCoordinator.Phase.READY &&
                    autoUbuntuSessionPending &&
                    registry.sessionTabs.value.none { it.isAlive }
                ) {
                    autoUbuntuSessionPending = false
                    createMutex.withLock { createSessionInternal(backendId = BACKEND_UBUNTU) }
                }
            }
        }
    }

    /** 从 runtime 拉取会话列表（状态/存活 + registry 记录的 backend 标签）。 */
    fun refreshSessions() {
        viewModelScope.launch { refreshSessionsInternal() }
    }

    private suspend fun refreshSessionsInternal() {
        when (val handoff = registry.refresh()) {
            SessionRegistry.Handoff.Keep -> Unit
            is SessionRegistry.Handoff.Select -> activateSession(handoff.id)
            SessionRegistry.Handoff.Cleared -> activateSession(null)
        }
        // P1-1/P0-2：会话消失时同步修剪快照缓存（防内存累积）。
        renderOrchestrator.pruneTo(registry.liveIds())
    }

    private fun startSessionPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // 事件驱动刷新（事件只做触发器，刷新走 snapshot(SESSIONS)）；
            // 流不可用（fake runtime）时退回 2s 轮询兜底。
            val lifecycle = terminalRuntime.sessionLifecycleEvents()
            if (lifecycle != null) {
                lifecycle.collect { refreshSessionsInternal() }
            } else {
                while (isActive) {
                    delay(2000)
                    refreshSessions()
                }
            }
        }
    }

    /** 切换活跃会话（用户点击 tab / 活跃会话消失后的自动接管）。 */
    fun selectSession(id: Long) {
        if (registry.select(id)) activateSession(id)
    }

    /**
     * 会话激活的统一接线：行镜像失效（跨会话残留治理）+ 渲染收集器切换。
     *
     * P0-2：[TerminalRenderOrchestrator] 内部用快照缓存播种 —— 切换帧直接
     * 显示上一已知内容，消灭旧版「切会话闪一帧『终端未启动』占位」。
     */
    private fun activateSession(id: Long?) {
        // P2（跨会话残留）：交互行缓冲随会话切换失效 —— A 会话敲到一半的命令
        // 残留在镜像里，切到 B 后按空回车会拿旧命令做黑白名单检查，命中则空
        // 回车被拦截并弹指向旧命令的「已拦截」提示。
        lineMirror.reset()
        renderOrchestrator.observe(
            viewModelScope, id,
            onSessionTitle = ::onSessionTitle,
            onOsc52Clipboard = ::applyOsc52Clipboard
        )
    }

    /** 渲染快照携带的 OSC 0/1/2 标题 → registry（变化才刷新 tab 列表）。 */
    private fun onSessionTitle(sid: Long, title: String) {
        if (registry.recordTitle(sid, title)) refreshSessions()
    }

    /** OSC 52 落地：护栏 —— 超长（>1MB）丢弃留痕；写失败静默降级。 */
    private fun applyOsc52Clipboard(text: String) {
        if (text.isEmpty()) return
        if (text.length > MAX_OSC52_CLIPBOARD_CHARS) {
            Log.w("TerminalVM", "OSC 52 clipboard request dropped (${text.length} chars > limit)")
            return
        }
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }.onFailure { Log.w("TerminalVM", "OSC 52 clipboard write failed: ${it.message}") }
    }

    fun createSession(backendId: String) {
        if (creating) return
        creating = true
        // 用户手工新建（含环境面板「先用 Android Shell」）→ 取消 READY 自动拉起
        autoUbuntuSessionPending = false
        viewModelScope.launch {
            try {
                createMutex.withLock { createSessionInternal(backendId) }
            } finally {
                creating = false
            }
        }
    }

    // ═══ T87：本地 Shell profile（mksh rc 播种）═══

    /** 本地 shell home（$filesDir/linux/shell —— 可写，untrusted_app 域内）。 */
    private val localShellHome by lazy {
        java.io.File(context.filesDir, "linux/shell")
    }

    /**
     * 确保 mksh rc 就绪并返回注入 env（HOME/ENV/TERM/COLORTERM）。
     *
     * T94：suspend + withContext(IO) —— ensureShellHome(mkdirs)/isFile/
     * writeText 全是磁盘 IO，旧实现在 viewModelScope（Main.immediate）直跑
     * 是 StrictMode 违例（create 链同型问题已修，此路径漏网）。
     *
     * 失败（磁盘满等）→ 空 map：会话照常创建（回到旧行为 —— 裸提示符），
     * 绝不因 profile 失败拒绝创建 shell。
     */
    private suspend fun ensureLocalShellProfile(): Map<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val home = com.apex.agent.platform.terminal.profile.GuestShellProfile.ensureShellHome(localShellHome)
                val rc = java.io.File(home, com.apex.agent.platform.terminal.profile.GuestShellProfile.RC_FILENAME)
                if (!rc.isFile || rc.length() == 0L) {
                    rc.writeText(com.apex.agent.platform.terminal.profile.GuestShellProfile.generate(android.os.Build.MODEL ?: "android"))
                }
                com.apex.agent.platform.terminal.profile.GuestShellProfile.shellEnv(home.absolutePath, rc.absolutePath)
            }.getOrDefault(emptyMap())
        }

    private suspend fun createSessionInternal(backendId: String) {
        if (backendId == BACKEND_UBUNTU) {
            // Ubuntu 会话：先确保 rootfs + bootstrap 就绪（长时操作，进度经
            // ubuntuLifecycleState 流回横幅）。取消/失败 → 诚实中止。
            // T84：withContext(IO) —— ensureReady 链含 capability 探测（阻塞
            // proot exec），provisioner/bootstrap 已内嵌 IO，此处兜住协调器自身
            // 的 probeFn/repairFn 端口（Main.immediate 调用曾直接吃满主线程）。
            val r = withContext(Dispatchers.IO) {
                ubuntuLifecycle.ensureReady()
            }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                notifyError(R.string.term_notice_ubuntu_unavailable, r.message.take(120))
                return
            }
        }
        // P1 修复（主线程 fork/exec）：terminalRuntime.create 链路含
        // LinuxPRootBackend.availability()/prepare() —— 真实 ProcessBuilder fork
        //（proot --version 探针，create 内各做一次共 2 次）、符号链接创建、home
        // skel 拷贝、workspace mkdirs、forkpty 本身。旧实现直接跑在
        // viewModelScope(Main.immediate)，慢设备上卡顿/StrictMode 违例/ANR 风险。
        // T87：LOCAL 会话注入 mksh profile（可写 HOME + $ENV rc + TERM）——
        // Shell 模式补 user@host:cwd 提示符、历史记录、cmds/help 命令发现。
        val localEnv = if (backendId == BACKEND_LOCAL) ensureLocalShellProfile() else emptyMap()
        val created = withContext(Dispatchers.IO) {
            terminalRuntime.create(backendId = backendId, env = localEnv)
        }
        val result = created.getOrElse { e ->
            notifyError(R.string.term_notice_create_failed, e.message?.take(120) ?: "")
            return
        }
        registry.recordBackend(result.sessionId, result.backendId, result.runtimeType)
        refreshSessionsInternal()
        selectSession(result.sessionId)
    }

    fun closeSession(id: Long) {
        viewModelScope.launch {
            // T92：close 移入 IO —— native close 含 HUP→50ms→TERM→100ms→KILL→150ms
            // 串行 sleep + 全局 session mutex，主线程执行会掉帧（create 同型问题已修，
            // 此路径漏修）。
            withContext(Dispatchers.IO) {
                terminalRuntime.close(id, force = true)
            }
            // P1-1：registry/快照缓存统一驱逐（替代旧 sessionBackends +
            // sessionTitles 两处 remove；快照缓存防止已关会话的最后一帧复播）。
            registry.evict(id)
            renderOrchestrator.evict(id)
            // 关闭的是当前会话时同步清交互行缓冲（语义同 selectSession 的清理）
            if (registry.activeSessionId.value == id) {
                lineMirror.reset()
            }
            refreshSessionsInternal()
            // close 异步生效的竞态兜底（快照里会话还在）：显式切走
            if (registry.activeSessionId.value == id) {
                registry.sessionTabs.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
        }
    }

    // ═══════════════════════ 交互终端：输入 / resize ═══════════════════════

    /**
     * 写入用户文本（IME 提交 / 硬件键盘字符），RAW 直通 PTY。
     *
     * 回车（IME 以 \r 文本下发，:terminal-view 已把 \n 归一为 \r）视为行提交：
     * 命中黑白名单 → 拦截整个写入（含回车），命令不执行。
     * 行镜像语义（P1-2）：[LineMirror.feedForSubmit] 无换行即镜像追加；含换行
     * 返回候选命令交门禁检查 —— 拦截时镜像原状保留，放行时 [LineMirror.commitSubmit]。
     */
    fun sendInput(text: String) {
        // 旧实现是无提示的 `?: return`：会话没了的情况下用户敲半天没反应还以为键盘坏了，
        // 状态条也不给任何线索。这里给出明确反馈。
        val sid = registry.activeSessionId.value
        if (sid == null) {
            notifyInfo(R.string.term_notice_no_session_input)
            return
        }
        if (text.isEmpty()) return

        val candidate = lineMirror.feedForSubmit(text)
        if (candidate != null) {
            if (candidate.isNotBlank() && !policyStore.isCommandAllowed(candidate)) {
                notifyError(R.string.term_notice_blocked, candidate.take(40))
                return // 不写入（含回车）—— readline 行保持未提交；镜像保留继续同步追加
            }
            // T87：提交时刻记入历史（通过门禁的命令才有资格入史）
            if (candidate.isNotBlank()) commandHistory.record(candidate)
            lineMirror.commitSubmit(text)
        }

        viewModelScope.launch {
            terminalRuntime.write(sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW, text = text)
                .onFailure { e ->
                    // T87（Ubuntu「输入失败」诚实化）：WriteFailed 的最常见根因是
                    // 会话进程已退出（master EIO）—— 旧文案「输入失败:WriteFailed」
                    // 让用户以为是键盘/输入链路坏了。按错误语义分流：会话死 →
                    // 「会话已退出」+ 重启指引；其余（策略拦截等）→ 原文。
                    val msg = e.message ?: ""
                    val dead = msg.contains("WriteFailed") || msg.contains("SessionNotFound") ||
                        msg.contains("SessionClosed") || msg.contains("session closed")
                    if (dead) {
                        notifyError(R.string.term_notice_session_dead)
                    } else {
                        notifyError(R.string.term_notice_input_failed, msg.take(80))
                    }
                }
        }
    }

    /**
     * T87：重启当前会话（保留 backend）—— 死会话覆盖层的「重启会话」按钮。
     *
     * 语义：close（force）→ 同 backend 重建。Agent 创建的会话（backend 记录为
     * "agent"）按 runtimeType 映射回真实 backendId（LINUX → Ubuntu，否则 LOCAL）。
     */
    fun restartActiveSession() {
        val active = registry.activeSessionId.value ?: return
        val tab = registry.sessionTabs.value.firstOrNull { it.id == active } ?: return
        val backend = when {
            tab.backendId == BACKEND_UBUNTU || tab.backendId == BACKEND_LOCAL -> tab.backendId
            tab.runtimeType == "LINUX" -> BACKEND_UBUNTU
            else -> BACKEND_LOCAL
        }
        viewModelScope.launch {
            // T92：close 移入 IO（同 closeSession —— 主线程串行 sleep 掉帧）。
            withContext(Dispatchers.IO) {
                terminalRuntime.close(active, force = true)
            }
            registry.evict(active)
            renderOrchestrator.evict(active)
            if (registry.activeSessionId.value == active) lineMirror.reset()
            refreshSessionsInternal()
            createMutex.withLock { createSessionInternal(backend) }
        }
    }

    /**
     * 发送特殊键：箭头按 DECCKM 编码（ESC O x / ESC [ x），其余经 TerminalKey
     *（InputManager 映射，模式无关）。粘贴按 bracketed-paste 包裹。
     *
     * ENTER = 行提交（黑白名单检查，拦截则不写入）；BACKSPACE = 行镜像退格；
     * 其余特殊键（方向/历史/TAB…）行状态不可知 → 行镜像失效（下次回车不检查）。
     *
     * T88（3）：[mods] 为 xterm 修饰位掩码（KeyEventMapping.MOD_* / emulator
     * KeyModifiers 同值）——非零时走 [encodeKeyWithMods] 完整修饰协议
     *（Shift+方向 = ESC[1;2A 词选择、Ctrl+F 键等）；零 = 旧路径不变（默认值
     * 保证既有单参调用方/函数引用完全兼容）。
     */
    fun sendKey(key: TerminalKey, mods: Int = 0) {
        val sid = registry.activeSessionId.value ?: return
        viewModelScope.launch {
            when (key) {
                TerminalKey.ENTER -> {
                    val candidate = lineMirror.toString().trim()
                    if (candidate.isNotBlank() && !policyStore.isCommandAllowed(candidate)) {
                        notifyError(R.string.term_notice_blocked, candidate.take(40))
                        return@launch
                    }
                    // T87：提交时刻记入历史
                    if (candidate.isNotBlank()) commandHistory.record(candidate)
                    lineMirror.reset()
                }
                TerminalKey.BACKSPACE -> lineMirror.backspace()
                else -> lineMirror.reset()
            }
            if (mods != 0) {
                val bytes = encodeKeyWithMods(key, mods)
                if (bytes != null && bytes.isNotEmpty()) {
                    terminalRuntime.write(
                        sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                        text = String(bytes, Charsets.ISO_8859_1)
                    )
                    return@launch
                }
            }
            if (key == TerminalKey.ARROW_UP || key == TerminalKey.ARROW_DOWN ||
                key == TerminalKey.ARROW_LEFT || key == TerminalKey.ARROW_RIGHT
            ) {
                val bytes = KeySequenceEncoder.encodeKey(
                    key, renderSnapshot?.applicationCursor ?: false
                )
                terminalRuntime.write(
                    sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                    text = String(bytes, Charsets.ISO_8859_1)
                )
            } else {
                terminalRuntime.write(
                    sid, InputOwner.USER, TerminalRuntime.WriteKind.KEY, key = key
                )
            }
        }
    }

    /**
     * T88（3）：键身份 + 修饰位 → xterm 参数化序列（KeyEventMapping 完整协议）。
     *
     * 平台 [TerminalKey] → Android keycode 对照，再经 [KeyEventMapping.encode]
     * 出 `ESC[1;m{final}` 形参化序列（DECCKM/DECKPAM 模式感知）。无对照
     *（CTRL_C 等信号语义键）或编码器无映射 → null（调用方回落旧路径）。
     */
    private fun encodeKeyWithMods(key: TerminalKey, mods: Int): ByteArray? {
        val keyCode = when (key) {
            TerminalKey.ARROW_UP -> KeyEventMapping.KEYCODE_DPAD_UP
            TerminalKey.ARROW_DOWN -> KeyEventMapping.KEYCODE_DPAD_DOWN
            TerminalKey.ARROW_LEFT -> KeyEventMapping.KEYCODE_DPAD_LEFT
            TerminalKey.ARROW_RIGHT -> KeyEventMapping.KEYCODE_DPAD_RIGHT
            TerminalKey.HOME -> KeyEventMapping.KEYCODE_MOVE_HOME
            TerminalKey.END -> KeyEventMapping.KEYCODE_MOVE_END
            TerminalKey.PAGE_UP -> KeyEventMapping.KEYCODE_PAGE_UP
            TerminalKey.PAGE_DOWN -> KeyEventMapping.KEYCODE_PAGE_DOWN
            TerminalKey.INSERT -> KeyEventMapping.KEYCODE_INSERT
            TerminalKey.DELETE -> KeyEventMapping.KEYCODE_FORWARD_DEL
            TerminalKey.ENTER -> KeyEventMapping.KEYCODE_ENTER
            TerminalKey.TAB -> KeyEventMapping.KEYCODE_TAB
            TerminalKey.BACKSPACE -> KeyEventMapping.KEYCODE_DEL
            TerminalKey.ESC -> KeyEventMapping.KEYCODE_ESCAPE
            in TerminalKey.F1..TerminalKey.F12 ->
                KeyEventMapping.KEYCODE_F1 + (key.ordinal - TerminalKey.F1.ordinal)
            else -> 0
        }
        if (keyCode == 0) return null
        val modes = KeyEventMapping.KeyModes(
            applicationCursor = renderSnapshot?.applicationCursor ?: false,
            applicationKeypad = renderSnapshot?.applicationKeypad ?: false,
            numLock = true
        )
        return KeyEventMapping.encode(keyCode, mods, modes)
    }

    /** Ctrl+字母（工具栏 CTRL 锁存 / 硬件 Ctrl 组合）。
     *
     * Ctrl+C / Ctrl+U 等会终止/清除 readline 当前行 → 行镜像失效。
     */
    fun sendControlChar(ch: Char) {
        val sid = registry.activeSessionId.value ?: return
        val bytes = KeySequenceEncoder.controlByte(ch) ?: return
        lineMirror.reset()
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /** 粘贴（bracketed-paste 感知）。
     *
     * 首行命令命中黑白名单 → 拦截整次粘贴（bracketed-paste OFF 时粘贴即执行，
     * 必须拦在写入前）；首行检查放行后行镜像失效（多行粘贴行状态不可知）。
     */
    fun pasteText(text: String) {
        val sid = registry.activeSessionId.value ?: return
        if (text.isEmpty()) return
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (firstLine.isNotBlank() && !policyStore.isCommandAllowed(firstLine)) {
            notifyError(R.string.term_notice_paste_blocked, firstLine.take(40))
            return
        }
        lineMirror.reset()
        viewModelScope.launch {
            val bytes = KeySequenceEncoder.encodePaste(
                text, renderSnapshot?.bracketedPaste ?: false
            )
            // P1（CJK 乱码）：bytes 直通 —— 旧实现把 UTF-8 字节经 ISO-8859-1 转
            // String 再按 UTF-8 重编码（Runtime 侧 InputManager 按 UTF-8 写 PTY），
            // 剪贴板里的中文/emoji/重音字符全部变成 "ä½ " 类乱码。
            // Runtime 的 RAW 路径已支持 bytes 直通（T85，注释明言「消除双重编码」），
            // UI 调用方此前没有同步切换 —— 现在对齐。
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                bytes = bytes
            )
        }
    }

    /** 视图尺寸变化 → PTY resize（SIGWINCH + VT 同步）。 */
    fun resizeTerminal(rows: Int, cols: Int) {
        val sid = registry.activeSessionId.value ?: return
        if (rows < 2 || cols < 4) return
        viewModelScope.launch {
            terminalRuntime.resize(sid, rows, cols)
        }
    }

    // ═══════════════════ T86：Termux 对齐输入扩展（硬件键/鼠标/焦点/字号）═══════════════════

    /**
     * 硬件键盘完整映射（KeyEventMapping —— xterm 修饰键协议）。
     *
     * 覆盖旧 [sendKey] 路径之外的键位：Shift/Alt/Ctrl+方向键（`ESC[1;5A` 类）、
     * F1-F12、小键盘（DECKPAM 感知）、Shift+Tab、Alt+字符（meta 化）。
     * 无映射（返回 null）时 UI 放行给 IME/系统。
     *
     * 行镜像语义与 [sendKey] 一致：ENTER=提交检查、DEL=退格、其余失效。
     */
    fun sendHardwareKey(keyCode: Int, mods: Int, unicodeChar: Int = 0) {
        val sid = registry.activeSessionId.value ?: return
        val modes = KeyEventMapping.KeyModes(
            applicationCursor = renderSnapshot?.applicationCursor ?: false,
            applicationKeypad = renderSnapshot?.applicationKeypad ?: false,
            numLock = true
        )
        val bytes = KeyEventMapping.encode(keyCode, mods, modes, unicodeChar) ?: return
        when (keyCode) {
            KeyEventMapping.KEYCODE_ENTER -> {
                val candidate = lineMirror.toString().trim()
                if (candidate.isNotBlank() && !policyStore.isCommandAllowed(candidate)) {
                    notifyError(R.string.term_notice_blocked, candidate.take(40))
                    return
                }
                lineMirror.reset()
            }
            KeyEventMapping.KEYCODE_DEL -> lineMirror.backspace()
            else -> lineMirror.reset()
        }
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /**
     * 鼠标事件（触摸/手写笔 → MouseEncoder → PTY）。
     *
     * guest 开启 DECSET 1000/1002/1003 后，vim/tmux/htop 把触摸点击当鼠标用。
     * 坐标 1-based（xterm 习惯）；未开启跟踪时编码器返回 null → 静默忽略。
     */
    fun sendMouseEvent(type: TerminalMouseEventType, button: Int, mods: Int, col: Int, row: Int) {
        val sid = registry.activeSessionId.value ?: return
        val mode = renderSnapshot?.mouseMode ?: return
        val bytes = MouseEncoder.encode(type, button, mods, col, row, mode) ?: return
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /**
     * 滚轮路由：跟踪开启 → 滚轮当鼠标事件进 PTY（vim 里滚 = 移动光标）；
     * 备用屏 + 1007 → 方向键；否则返回 false 让 UI 滚动视口（正常行为）。
     *
     * @return true = 已编码进 PTY（UI 不要再滚视口）
     */
    fun sendWheel(up: Boolean): Boolean {
        val sid = registry.activeSessionId.value ?: return false
        val render = renderSnapshot ?: return false
        val bytes = when {
            render.mouseMode.enabled -> MouseEncoder.encode(
                if (up) TerminalMouseEventType.WHEEL_UP else TerminalMouseEventType.WHEEL_DOWN,
                0, 0, render.cursorCol + 1, render.cursorRow + 1, render.mouseMode
            )
            render.mouseMode.altScroll && render.alternateScreen -> MouseEncoder.altScrollArrow(up)
            else -> null
        } ?: return false
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
        return true
    }

    /**
     * 窗口焦点变化 → ESC[I / ESC[O（DECSET 1004）。
     * vim FocusGained/FocusLost、tmux focus-events 依赖此序列。
     */
    fun notifyTerminalFocus(gained: Boolean) {
        val sid = registry.activeSessionId.value ?: return
        val mode = renderSnapshot?.focusMode ?: return
        val bytes = encodeFocusEvent(gained, mode) ?: return
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    // ═══════════════════════ Ubuntu 生命周期入口 ═══════════════════════

    /** 一键解包 Ubuntu（横幅按钮）—— ensureReady 全链：离线解包 → 配置 → bootstrap（可降级）。 */
    fun installUbuntu() {
        viewModelScope.launch {
            // T84：IO —— 完整 rootfs（~300MB+ 档，解压分钟级）绝不能压 Main。
            val r = withContext(Dispatchers.IO) { ubuntuLifecycle.ensureReady() }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                notifyError(R.string.term_notice_unpack_failed, r.message.take(160))
            }
        }
    }

    /** 环境中心：取消进行中的安装（下载字节保留，下次断点续传）。 */
    fun cancelUbuntuInstall() {
        viewModelScope.launch {
            val r = ubuntuLifecycle.cancelInstall()
            if (!r.cancelled) notifyRaw(r.message, TerminalNotice.Kind.INFO)
        }
    }

    /** 环境中心：产品级修复（不触发大下载；detect → repair → verify）。 */
    fun repairUbuntu() {
        viewModelScope.launch {
            // T84：IO —— repair 链是文件/子进程操作。
            val r = withContext(Dispatchers.IO) { ubuntuLifecycle.repair() }
            if (r.verifiedHealthy) notifyInfo(R.string.term_notice_repair_ok)
            else notifyError(R.string.term_notice_repair_unresolved, r.detail ?: r.actions.joinToString().take(120))
        }
    }

    /** 环境中心：删除 Ubuntu rootfs（用户 home/workspace 保留）。 */
    fun removeUbuntu() {
        viewModelScope.launch {
            // T84：IO —— 删除 1GB+ 版本目录是重 IO。
            val r = withContext(Dispatchers.IO) { ubuntuLifecycle.removeRootfs() }
            notifyRaw(r.message, TerminalNotice.Kind.INFO)
            if (r.removed) refreshRootfsSize()
        }
    }

    /** rootfs 磁盘占用（bytes；null = 未安装）—— 环境中心/存储管理展示。 */
    private val _rootfsSize = MutableStateFlow<Long?>(null)
    val rootfsSize: StateFlow<Long?> = _rootfsSize.asStateFlow()

    init { refreshRootfsSize() }

    fun refreshRootfsSize() {
        viewModelScope.launch {
            _rootfsSize.value = ubuntuLifecycle.rootfsSizeBytes()
        }
    }

    /**
     * Ubuntu 安装/引导聚合进度（install:percent/bytes + bootstrap:stage/message）。
     * 冷流持续收集，状态进入 READY/NOT_INSTALLED 时清空展示。
     */
    private val _ubuntuProgress = MutableStateFlow<UbuntuLifecycleCoordinator.LifecycleProgress?>(null)
    val ubuntuProgress: StateFlow<UbuntuLifecycleCoordinator.LifecycleProgress?> = _ubuntuProgress.asStateFlow()

    init {
        viewModelScope.launch {
            ubuntuLifecycle.progressFlow().collect { p ->
                _ubuntuProgress.value = p
                // 安装完成后刷新占用（下载/解压会显著改变磁盘占用）
                // P1 修复（布尔优先级）：&& 先于 || 结合 —— 旧写法
                // `a && b || c` 等价于 `(a && b) || c`，任何以 REMOVED 结尾的
                // stage（含未来 bootstrap 可能新增的移除态）都会触发刷新；
                // 显式括号表达意图：install 域内的 READY / REMOVED 才刷新。
                if (p.stage.startsWith("install:") &&
                    (p.stage.endsWith("READY") || p.stage.endsWith("REMOVED"))
                ) {
                    refreshRootfsSize()
                }
            }
        }
    }

    // ═══ 终端设置（P0-1：持久化与状态在 TerminalSettingsStore，VM 只委托）═══

    data class TerminalSettings(
        val fontSize: Int = 13,
        val monochrome: Boolean = false,
        /** 键盘辅助行（ESC/TAB/CTRL/箭头…）显隐 —— 小屏手机可隐藏换取显示区。 */
        val showKeybar: Boolean = true,
        /**
         * 响铃（BEL 0x07）时振动一下 —— Termux/ConnectBot 的常规反馈，
         * tab 补全失败、Ctrl+G、命令报错都会发 BEL。默认开。
         */
        val vibrateOnBell: Boolean = true,
        /**
         * 终端页保持屏幕常亮 —— 看长任务输出（编译 / apt / 训练日志）时不会被息屏打断。
         * Termux 默认持有 wakelock，此项对齐该行为（默认关，交用户选择）。
         */
        val keepScreenOn: Boolean = false
    ) {
        /** 字号合法区间（双指捏合缩放也走这个钳制）。 */
        companion object {
            const val MIN_FONT_SIZE = 8
            const val MAX_FONT_SIZE = 24
        }
    }

    val settings: StateFlow<TerminalSettings> = settingsStore.settings

    fun updateSettings(block: TerminalSettings.() -> TerminalSettings) =
        settingsStore.update(block)

    /** 字号调整（钳制在 [TerminalSettings.MIN_FONT_SIZE]..[TerminalSettings.MAX_FONT_SIZE]）。 */
    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE)
        if (clamped == settingsStore.settings.value.fontSize) return
        settingsStore.update { copy(fontSize = clamped) }
    }

    // ═══ T87：命令历史（委托 TerminalCommandHistory）═══

    /** 历史（最新在前；Termux history 的可视化等价物）。 */
    val commandHistoryEntries: StateFlow<List<String>> = commandHistory.entries

    /** 清空历史（设置抽屉「清空」确认后调用）。 */
    fun clearCommandHistory() = commandHistory.clear()

    // ═══ T87：扩展键（用户自定义宏行 —— Termux extra-keys 等价物）═══
    // 持久化读写缝拆在 TerminalExtraKeysStore.kt（守 1200 行预算）。

    /** 扩展键（用户宏；空 = 不渲染扩展行）。 */
    val extraKeys: StateFlow<List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey>> =
        settingsStore.extraKeys

    /** 追加一个扩展键（spec 形如 `标签=cmd:apt-get update`；非法 spec 静默拒绝）。 */
    fun addExtraKey(spec: String) = settingsStore.addExtraKey(spec)

    /** 移除指定标签的扩展键。 */
    fun removeExtraKey(label: String) = settingsStore.removeExtraKey(label)

    /** 重置为默认布局（设置抽屉「恢复默认」）。 */
    fun resetExtraKeys() = settingsStore.resetExtraKeys()

    // ═══ 终端配色（T87：TerminalColorSchemeSettings 持久化 + 热切换）═══
    // 零方案代码进 VM（SRP）：id/boldAsBright 透传给渲染树。
    private val schemeSettings = com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeSettings(
        com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeSettings.PrefsStore(
            context, "apex_terminal"
        )
    )

    /** 当前配色方案 id（渲染树解析为完整方案）。 */
    val colorSchemeId: StateFlow<String> = schemeSettings.schemeId

    /** bold → 亮色提升（xterm 传统；ls/ls 彩色输出依赖）。 */
    val boldAsBright: StateFlow<Boolean> = schemeSettings.boldAsBright

    /** 切换配色方案（未知 id 拒绝；渲染树经 StateFlow 自动换色）。 */
    fun setColorScheme(id: String) {
        schemeSettings.setSchemeId(id)
    }

    /** bold-as-bright 开关。 */
    fun setBoldAsBright(enabled: Boolean) {
        schemeSettings.setBoldAsBright(enabled)
    }

    /** 当前配色方案本地化显示名（zh → nameZh；供设置抽屉展示）。 */
    fun currentSchemeDisplayName(): String =
        schemeSettings.scheme.displayName(isZhLanguage())

    /** 语言判定（zh 显式 → 中文；system → 设备 Locale；en → 英文）。 */
    private fun isZhLanguage(): Boolean = when (lang.language.value) {
        "zh" -> true
        "en" -> false
        else -> runCatching {
            val locales = context.resources.configuration.locales
            locales.size() > 0 && locales[0].language == "zh"
        }.getOrDefault(false)
    }

    // ═══ 黑名单 / 白名单命令（P0-1：持久化与门禁在 CommandPolicyStore）═══

    val blacklist: StateFlow<Set<String>> = policyStore.blacklist

    val whitelist: StateFlow<Set<String>> = policyStore.whitelist

    fun addBlacklist(cmd: String) = policyStore.addBlacklist(cmd)
    fun removeBlacklist(cmd: String) = policyStore.removeBlacklist(cmd)
    fun addWhitelist(cmd: String) = policyStore.addWhitelist(cmd)
    fun removeWhitelist(cmd: String) = policyStore.removeWhitelist(cmd)

    /** 交互输入的命令头检查（语义详见 [CommandPolicyStore.isCommandAllowed]）。 */
    fun isCommandAllowed(command: String): Boolean = policyStore.isCommandAllowed(command)

    // ═══ 环境依赖下载中心（P0-1：编排在 [TerminalDepCenter]，VM 只入口委托）═══

    val depItems: List<TerminalDepCenter.DepItem> = depCenter.depItems

    val useMirror: StateFlow<Boolean> = depCenter.useMirror

    fun setUseMirror(on: Boolean) = depCenter.setUseMirror(on)

    val install: StateFlow<TerminalDepCenter.InstallState> = depCenter.install

    fun installDep(item: TerminalDepCenter.DepItem) = depCenter.installDep(item)

    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) = depCenter.installAll(onProgress)

    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) = depCenter.installAndroidOnly(onProgress)

    override fun onCleared() {
        // Runtime owns session lifecycle; explicit close via terminal.close() by Agent/UI.
        // 这里不主动 close，因为 Runtime 是单例，session 可能被其他消费者复用。
    }

    companion object {
        const val BACKEND_LOCAL = "local"
        const val BACKEND_UBUNTU = "linux-ubuntu"

        /** OSC 52 剪贴板长度上限（1M 字符 ≈ 1MB UTF-8 —— Termux 同款基本护栏）。 */
        private const val MAX_OSC52_CLIPBOARD_CHARS = 1 shl 20
    }
}
