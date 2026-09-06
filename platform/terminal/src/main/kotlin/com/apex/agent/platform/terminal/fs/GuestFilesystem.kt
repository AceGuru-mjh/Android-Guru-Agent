package com.apex.agent.platform.terminal.fs

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilder
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.util.Base64

/**
 * T82 — Guest Filesystem API（Termux 基线 §4.8/§11）。
 *
 * 背景：guest 文件操作此前只有一条路 —— `terminal.run "cat > f"` / `echo … | tee`。
 * 对于 Agent 这是脆的（引号地狱、here-doc 竞态、无错误语义）。本类提供结构化、
 * 二进制安全、带路径门禁的 guest fs 操作，全部经 [ProotExecutor]（PRoot +
 * `/bin/sh -c`，非交互 bounded 执行），与 apt/交互会话共享同一 rootfs/workspace/
 * home（LinuxExecutionContextFactory 构造级共享，T81 §28）。
 *
 * 二进制安全传输：读写经 base64（Ubuntu base 的 coreutils base64 支持 -w0/-d），
 * 内容永不经过 shell 引号解析 —— 写入时 base64 是唯一的 argv 内容（[shQuote]
 * 只包路径）。
 *
 * 路径门禁（deny-by-default，与 CommandPolicy 同哲学）：
 *  - 读/列/stat：任意 guest 路径；
 *  - **写/删/移/拷**（含 move/copy 的目标）：必须位于 [allowedWriteRoots]
 *    （默认 /workspace、/root、/tmp、/sdcard）。rm -rf / 之类在门禁处结构化拒绝。
 */
class GuestFilesystem(
    private val contextFactory: LinuxExecutionContextFactory,
    private val executor: ProotExecutor,
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    /** 路径门禁：写操作允许的 guest 根（前缀匹配）。 */
    private val allowedWriteRoots: List<String> = DEFAULT_WRITE_ROOTS,
    private val environment: LinuxEnvironmentManager = LinuxEnvironmentManager(),
    private val timeoutMs: Long = 15_000L
) {

    // ─── 结果类型 ───

    sealed class FsResult<out T> {
        data class Ok<T>(val value: T, val durationMs: Long) : FsResult<T>()
        data class Err(val code: String, val message: String, val exitCode: Int? = null) : FsResult<Nothing>()
    }

    data class FileInfo(
        val path: String,
        val type: String,          // file | dir | link | other:<desc>
        val sizeBytes: Long,
        val modifiedEpochSec: Long
    )

    data class DirEntry(val name: String, val type: String)   // f | d | l

    data class ReadResult(val text: String, val bytes: ByteArray, val base64: String)

    // ─── 操作 ───

    /** 读文件（base64 往返 —— 二进制安全；text 为 UTF-8 解码）。 */
    suspend fun read(path: String): FsResult<ReadResult> {
        val guestPath = normalize(path)
        if (guestPath.isEmpty()) return FsResult.Err("FsError:InvalidPath", "empty path")
        val r = exec("""base64 -w0 ${shQuote(guestPath)}""")
        return when (r) {
            is FsResult.Ok -> {
                val b64 = r.value.stdout.trim()
                if (b64.isEmpty()) FsResult.Ok(ReadResult("", ByteArray(0), ""), r.durationMs)
                else runCatching { Base64.getDecoder().decode(b64) }.fold(
                    onSuccess = { bytes ->
                        FsResult.Ok(ReadResult(decodeUtf8(bytes), bytes, b64), r.durationMs)
                    },
                    onFailure = { FsResult.Err("FsError:DecodeFailed", "guest base64 output invalid: ${it.message}") }
                )
            }
            is FsResult.Err -> r
        }
    }

    /** 读文本（便捷）。 */
    suspend fun readText(path: String): FsResult<String> = read(path).mapValue { it.text }

    /** 写文件（覆盖）。内容经 base64 —— 引号/换行/NUL 全安全。 */
    suspend fun write(path: String, content: String): FsResult<Unit> =
        writeBytes(path, content.toByteArray(Charsets.UTF_8))

    suspend fun writeBytes(path: String, bytes: ByteArray): FsResult<Unit> {
        val guard = guardWritable(path) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(path))
        if (bytes.size > MAX_WRITE_BYTES) {
            return FsResult.Err("FsError:TooLarge", "content ${bytes.size}B > $MAX_WRITE_BYTES — split writes")
        }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val r = exec("printf %s ${shQuote(b64)} | base64 -d > ${shQuote(guard)}")
        return r.mapValue { }
    }

    /** 追加写。 */
    suspend fun append(path: String, content: String): FsResult<Unit> {
        val guard = guardWritable(path) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(path))
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return FsResult.Err("FsError:TooLarge", "content ${bytes.size}B > $MAX_WRITE_BYTES")
        }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val r = exec("printf %s ${shQuote(b64)} | base64 -d >> ${shQuote(guard)}")
        return r.mapValue { }
    }

    /** 列目录（一层；f/d/l 类型）。 */
    suspend fun list(path: String): FsResult<List<DirEntry>> {
        val guestPath = normalize(path).ifEmpty { "/" }
        val r = exec("""find ${shQuote(guestPath)} -maxdepth 1 -mindepth 1 -printf '%y %f\n' | sort -k2""")
        return when (r) {
            is FsResult.Ok -> {
                val entries = r.value.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val sp = line.indexOf(' ')
                    if (sp <= 0) return@mapNotNull null
                    val t = line.substring(0, sp)
                    val n = line.substring(sp + 1)
                    DirEntry(name = n, type = when (t) { "f" -> "f"; "d" -> "d"; "l" -> "l"; else -> "o" })
                }
                FsResult.Ok(entries, r.durationMs)
            }
            is FsResult.Err -> r
        }
    }

    /** stat。 */
    suspend fun stat(path: String): FsResult<FileInfo> {
        val guestPath = normalize(path)
        val r = exec("""stat -c '%F|%s|%Y' ${shQuote(guestPath)}""")
        return when (r) {
            is FsResult.Ok -> {
                val parts = r.value.stdout.trim().split('|')
                if (parts.size < 3) {
                    FsResult.Err("FsError:ParseFailed", "unexpected stat output: '${r.value.stdout.take(120)}'")
                } else {
                    val kindDesc = parts[0]
                    val type = when {
                        kindDesc.startsWith("directory") -> "dir"
                        kindDesc.startsWith("symbolic link") -> "link"
                        kindDesc.contains("file") -> "file"
                        else -> "other:$kindDesc"
                    }
                    FsResult.Ok(
                        FileInfo(
                            path = guestPath,
                            type = type,
                            sizeBytes = parts[1].toLongOrNull() ?: -1L,
                            modifiedEpochSec = parts[2].toLongOrNull() ?: -1L
                        ),
                        r.durationMs
                    )
                }
            }
            is FsResult.Err -> r
        }
    }

    /** 存在性。 */
    suspend fun exists(path: String): FsResult<Boolean> {
        val r = exec("test -e ${shQuote(normalize(path))} && printf yes || printf no")
        return r.mapValue { it.stdout.trim() == "yes" }
    }

    /** mkdir（-p 语义）。 */
    suspend fun mkdir(path: String): FsResult<Unit> {
        val guard = guardWritable(path) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(path))
        val r = exec("mkdir -p ${shQuote(guard)}")
        return r.mapValue { }
    }

    /** 删除（recursive 语义显式）。 */
    suspend fun remove(path: String, recursive: Boolean): FsResult<Unit> {
        val guard = guardWritable(path) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(path))
        if (guard == "/" || guard.isEmpty()) return FsResult.Err("FsError:PathDenied", "refusing to remove root")
        val cmd = if (recursive) "rm -rf ${shQuote(guard)}" else "rm -f ${shQuote(guard)}"
        val r = exec(cmd)
        return r.mapValue { }
    }

    /** move。src/dst 都过写门禁（move 离开沙箱根 = 等价写外部 → 拒绝）。 */
    suspend fun move(src: String, dst: String): FsResult<Unit> {
        val s = guardWritable(src) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(src))
        val d = guardWritable(dst) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(dst))
        val r = exec("mv ${shQuote(s)} ${shQuote(d)}")
        return r.mapValue { }
    }

    /** copy。 */
    suspend fun copy(src: String, dst: String): FsResult<Unit> {
        val d = guardWritable(dst) ?: return FsResult.Err("FsError:PathDenied", writeDeniedMessage(dst))
        val r = exec("cp -a ${shQuote(normalize(src))} ${shQuote(d)}")
        return r.mapValue { }
    }

    // ─── 执行核心 ───

    private suspend fun exec(shellCommand: String): FsResult<ProotExecutor.Execution> {
        val ctx = contextFactory.resolve().getOrElse { e ->
            return FsResult.Err("FsError:ContextUnavailable", e.message ?: "linux context resolve failed")
        }
        val launch = PRootLaunchRequest(
            rootfs = ctx.rootfs,
            executable = "/bin/sh",
            arguments = listOf("-c", shellCommand),
            workingDirectory = null,
            environment = environment.aptGuestEnv(),   // 非交互基线（TERM=dumb）
            binds = listOf(ctx.homeBind)
        )
        val command: PRootCommand = commandBuilder.build(
            launch,
            AbsolutePath(ctx.prootBinary.absolutePath),
            AbsolutePath(ctx.rootfsDir.absolutePath),
            AbsolutePath(ctx.workspaceDir.absolutePath)
        )
        val result = executor.execute(command, timeoutMs = timeoutMs)
        val ok = !result.timedOut && result.exitCode == 0
        return if (ok) {
            FsResult.Ok(result, result.durationMs)
        } else {
            val msg = result.stderr.trim().take(300).ifEmpty { "exit=${result.exitCode}${if (result.timedOut) " timedOut" else ""}" }
            FsResult.Err(
                code = if (result.timedOut) "FsError:Timeout" else "FsError:GuestCommandFailed",
                message = msg,
                exitCode = if (result.timedOut) null else result.exitCode
            )
        }
    }

    // ─── 路径门禁与工具 ───

    /** 写门禁：返回归一化路径（在允许根下），否则 null。 */
    internal fun guardWritable(path: String): String? {
        val p = normalize(path)
        if (p.isEmpty()) return null
        val allowed = allowedWriteRoots.any { root ->
            val r = root.trimEnd('/')
            p == r || p.startsWith("$r/")
        }
        return if (allowed) p else null
    }

    private fun writeDeniedMessage(path: String): String =
        "path '$path' outside writable roots $allowedWriteRoots (agent file writes are sandboxed; " +
            "shell via terminal.run remains unrestricted-by-policy)"

    internal fun normalize(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return ""
        // 拒绝 NUL/换行（argv 注入面）—— shQuote 已处理引号，但换行会破坏 -c 脚本结构。
        if (p.any { it == '\u0000' || it == '\n' }) return ""
        return if (p.startsWith("/")) p else "/workspace/$p"
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        runCatching { String(bytes, Charsets.UTF_8) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }

    private inline fun <T, R> FsResult<T>.mapValue(f: (T) -> R): FsResult<R> = when (this) {
        is FsResult.Ok -> FsResult.Ok(f(value), durationMs)
        is FsResult.Err -> this
    }

    companion object {
        /** Agent 写操作允许的 guest 根。 */
        val DEFAULT_WRITE_ROOTS = listOf("/workspace", "/root", "/tmp", "/sdcard")
        /** 单次写上限（argv 约束 + base64 膨胀安全边际）。 */
        const val MAX_WRITE_BYTES = 96 * 1024
    }
}

/** POSIX 单引号转义（值内 ' → '\''）。 */
internal fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
