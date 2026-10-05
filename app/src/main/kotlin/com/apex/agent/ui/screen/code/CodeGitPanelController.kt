package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.git.indicatesNotRepo
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个变更文件（porcelain v1 状态码 + 路径）。 */
data class GitFileChange(
    /** porcelain 状态码原文（"M " / " M" / "A" / "??" / "UU" / "R " …）。 */
    val statusCode: String,
    /** 仓库相对路径（rename 已取新路径展示，旧路径在 diff 里可见）。 */
    val path: String
) {
    /** 单字母徽标（取状态码首非空字符；"??" 显示 "N" = new）。 */
    val badge: String
        get() = when {
            statusCode.startsWith("??") -> "N"
            else -> statusCode.trim().take(1).ifEmpty { "M" }
        }
}

/** Git 面板状态（controller 独立 StateFlow，不走 uiState——面板自治）。 */
data class CodeGitPanelState(
    val loading: Boolean = false,
    /** 是否 git 仓库（false = 显示初始化引导）。 */
    val isRepo: Boolean = false,
    val branch: String? = null,
    val changes: List<GitFileChange> = emptyList(),
    /** 已加载的 diff（path → 文本；点击文件行时按需拉取）。 */
    val diffs: Map<String, String> = emptyMap(),
    val committing: Boolean = false,
    /** 最近一次操作结果（提交成功摘要/错误；null = 无）。 */
    val message: String? = null,
    val messageIsError: Boolean = false
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

/**
 * # Code Git Panel Controller — 右上角 Git 工作区控制器（v6）
 *
 * 用户规格：「在 coding 模式右上角设立一个工作区，显示变动的文件和
 * git 功能」。数据/操作全在本类（God-file 预算：CodeViewModel 只接线）：
 *
 * - [refresh]：`git status --porcelain=v1 -b` → 分支 + 变更文件列表；
 * - [loadDiff]：`git diff -- <path>`（未跟踪文件 = 空结果，UI 显示新文件）；
 * - [commitAll]：`git add -A` + `git -c user.name/email commit -m …`，
 *   身份注入与 GitCommitTool 同源（agent 署名，不动用户全局配置）；
 * - [initRepo]：非仓库时一键 `git init`；
 * - ProotGitCommandRunner 恒定把**激活工作区** bind 到 guest /workspace
 *   —— 命令天然作用于当前工作区，切换工作区后 refresh 即可。
 *
 * 全部错误折叠为状态（message/messageIsError），绝不向上抛——面板是
 * 观察窗，不能因 git 缺失/超时打断会话。
 */
class CodeGitPanelController(
    private val gitRunner: GitCommandRunner,
    private val scope: CoroutineScope,
    /** IO 调度器注入（测试传 TestScheduler 派发器，虚拟时间推进）。 */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    private val _state = MutableStateFlow(CodeGitPanelState())
    val state: StateFlow<CodeGitPanelState> = _state

    /**
     * 面板打开/手动刷新入口。
     *
     * @param clearMessage 是否清除回执条 —— commitAll 成功后的自动刷新传
     *   false（成功回执要保留到用户读完；手动刷新才清）。
     */
    fun refresh(clearMessage: Boolean = true) {
        _state.update { if (clearMessage) it.copy(loading = true, message = null) else it.copy(loading = true) }
        scope.launch {
            val parsed = withContext(ioDispatcher) { runStatus() }
            _state.update { current ->
                parsed.fold(
                    onSuccess = { st ->
                        current.copy(
                            loading = false,
                            isRepo = st.isRepo,
                            branch = st.branch,
                            changes = st.changes,
                            diffs = emptyMap()
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            loading = false,
                            message = err.message ?: "git status failed",
                            messageIsError = true
                        )
                    }
                )
            }
        }
    }

    /** 按需加载单文件 diff（面板点击行展开时调用；结果进 diffs 缓存）。 */
    fun loadDiff(path: String) {
        if (_state.value.diffs.containsKey(path)) return
        scope.launch {
            val diff = withContext(ioDispatcher) { runDiff(path) }
            _state.update { it.copy(diffs = it.diffs + (path to diff)) }
        }
    }

    /** 提交全部变更（含未跟踪；身份注入，不动用户全局 git config）。 */
    fun commitAll(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || _state.value.committing) return
        _state.update { it.copy(committing = true, message = null) }
        scope.launch {
            val result = withContext(ioDispatcher) { runCommit(trimmed) }
            _state.update { current ->
                current.copy(
                    committing = false,
                    message = if (result.isSuccess) trimmed.take(80) else result.message,
                    messageIsError = !result.isSuccess
                )
            }
            if (result.isSuccess) refresh(clearMessage = false)
        }
    }

    /** 非仓库时一键初始化。 */
    fun initRepo() {
        scope.launch {
            val init = withContext(ioDispatcher) {
                runCatching { gitRunner.run(listOf("init")) }
                    .fold(onSuccess = { it.exitCode == 0 }, onFailure = { false })
            }
            if (init) refresh() else {
                _state.update {
                    it.copy(message = "git init failed", messageIsError = true)
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    // ═══ 内部：命令执行与解析（IO 隔离 + 防御式解析）═══

    private data class StatusParse(
        val isRepo: Boolean,
        val branch: String?,
        val changes: List<GitFileChange>
    )

    private suspend fun runStatus(): Result<StatusParse> = runCatching {
        val result = gitRunner.run(listOf("status", "--porcelain=v1", "-b"))
        if (result.indicatesNotRepo()) {
            return@runCatching StatusParse(isRepo = false, branch = null, changes = emptyList())
        }
        if (result.exitCode != 0) {
            error("git status exit ${result.exitCode}: ${result.stderr.take(200)}")
        }
        var branch: String? = null
        val changes = mutableListOf<GitFileChange>()
        result.stdout.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (line.startsWith("## ")) {
                    // "## main...origin/main [ahead 1]" → main
                    branch = line.removePrefix("## ")
                        .substringBefore("...")
                        .substringBefore(' ')
                        .takeIf { it.isNotBlank() }
                } else if (line.length >= STATUS_CODE_LEN) {
                    val code = line.take(2)
                    val rawPath = line.substring(STATUS_CODE_LEN)
                    // rename「old -> new」取新路径展示
                    val path = rawPath.substringAfterLast(" -> ", rawPath)
                    changes += GitFileChange(statusCode = code, path = path)
                }
            }
        StatusParse(isRepo = true, branch = branch, changes = changes.toList())
    }

    private suspend fun runDiff(path: String): String = runCatching {
        val result = gitRunner.run(listOf("diff", "HEAD", "--", path))
        when {
            result.exitCode == 0 && result.stdout.isNotBlank() -> result.stdout.take(MAX_DIFF_CHARS)
            // 无 HEAD（初始提交前）→ 对比暂存区
            result.exitCode != 0 -> gitRunner.run(listOf("diff", "--", path))
                .stdout.takeIf { it.isNotBlank() } ?: ""
            else -> ""
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        AppLogger.instance.warn(LogCategory.SYSTEM, TAG, "diff failed: ${e.message}")
        ""
    }

    private data class CommitOutcome(val isSuccess: Boolean, val message: String)

    private suspend fun runCommit(message: String): CommitOutcome {
        // add -A（含未跟踪）；-c 身份注入（agent 署名，与 GitCommitTool 同源）
        val add = gitRunner.run(listOf("add", "-A"))
        if (add.exitCode != 0) {
            return CommitOutcome(false, "git add failed: ${add.stderr.take(160)}")
        }
        val commit = gitRunner.run(
            listOf(
                "-c", "user.name=$COMMIT_AUTHOR_NAME",
                "-c", "user.email=$COMMIT_AUTHOR_EMAIL",
                "commit", "-m", message, "--no-verify"
            )
        )
        return if (commit.exitCode == 0) {
            CommitOutcome(true, message)
        } else {
            // 「没有变更可提交」等非致命退出码原样带回
            CommitOutcome(false, (commit.stderr.ifBlank { commit.stdout }).take(200))
        }
    }

    private companion object {
        const val TAG = "CodeGitPanel"
        const val STATUS_CODE_LEN = 3
        const val MAX_DIFF_CHARS = 8_000
        const val COMMIT_AUTHOR_NAME = "Guru Agent"
        const val COMMIT_AUTHOR_EMAIL = "agent@guru.local"
    }
}
