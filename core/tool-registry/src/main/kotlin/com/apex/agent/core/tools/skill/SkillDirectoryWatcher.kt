package com.apex.agent.core.tools.skill

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * # 技能目录监听器（热加载的触发侧）
 *
 * [SkillRegistry] 的安装/卸载/启停经 API 走时本就发 [SkillRegistry.changes]
 * （[SkillHotReloader] 已订阅）——但**市场之外的文件变化**（热更引擎落盘、
 * 用户文件同步、adb push、手动解压 ZIP）不经过任何 API，旧实现只能重启
 * App 才能见到。本类补上这一段：
 *
 * ```
 * 目录文件变化 → 指纹变化 → SkillRegistry.reconcileDirectory()
 *   → changes 流 → SkillHotReloader 工具热同步 + UI 自动刷新
 *   → 引擎每轮重读 prompt 注入（本就是热的）
 * ```
 *
 * ## 为什么是轮询而不是 FileObserver / WatchService
 *
 * - core:tool-registry 是**纯 JVM 模块**（kotlin.jvm），不能用 android.os.FileObserver；
 * - java.nio WatchService 在 Android 上实现参差（部分 OEM 文件系统不派发事件），
 *   轮询是唯一 100% 可靠的形态；
 * - 代价已被指纹设计压到最低：每 [intervalMs] 一次 `listFiles` + 拼字符串
 *   比较，无变化时**零文件读取、零解析**；skills 目录量级（百级文件）下
 *   耗时可忽略；
 * - App 进程单例持有（SkillModule 装配），无后台常驻问题。
 *
 * ## 指纹与自激环防护
 *
 * 指纹 = 顶层 `*.json` 文件的「文件名:大小:mtime」有序拼接。变化才触发
 * 协调；协调后更新基线。[SkillRegistry.install] 会重写同名文件（mtime 变
 * 但内容不变）→ 指纹再变 → 协调发现版本未动 → 零动作 → 基线收敛。
 * **环在 reconcileDirectory 的版本比较处被切断**，不在 watcher 侧。
 *
 * ## 写入中的文件
 *
 * 协调读到半截 JSON → 解析失败 → skipped + WARN（自愈：写入完成后指纹
 * 再变，下轮协调成功）。
 *
 * ## 线程模型
 *
 * 单协程循环跑在注入 [scope]（建议 SupervisorJob + Dispatchers.IO）；
 * [stop] 只取消自己（scope 归宿主管）。协调本体在循环协程内串行执行，
 * 天然不会与自身并发；与市场页并发由 [SkillRegistry] 内部锁 + 幂等语义兜底。
 */
class SkillDirectoryWatcher(
    private val registry: SkillRegistry,
    private val skillsDir: File,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val logger: SkillHotReloadLogSink = SkillHotReloadLogSink { _, _ -> }
) {

    private val lock = Any()
    private var pollJob: Job? = null
    private var baseline: String? = null
    private var started = false

    /** 启动监听（幂等；首轮只建指纹基线不动作，之后按间隔巡检增量）。 */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            pollJob = scope.launch {
                while (isActive) {
                    runCatching { pollOnce() }
                    delay(intervalMs)
                }
            }
        }
        logger.log(SkillHotReloadLogLevel.INFO, "技能目录监听已启动：${skillsDir.absolutePath}（间隔 ${intervalMs}ms）")
    }

    /** 停止监听（幂等；scope 宿主管理，不在此取消）。 */
    fun stop() {
        val job = synchronized(lock) {
            started = false
            val j = pollJob
            pollJob = null
            j
        }
        job?.cancel()
    }

    private suspend fun pollOnce() {
        val fp = fingerprint() ?: return
        val last = baseline
        if (fp == last) return
        // 首轮（last == null）：只建基线不动作——SkillRegistry 构造时刚从
        // 同一目录加载过，立即再协调是纯冗余；变化检测从第二轮起生效。
        if (last == null) {
            baseline = fp
            return
        }
        val report = registry.reconcileDirectory()
        baseline = fingerprint() ?: fp
        if (report.added.isNotEmpty() || report.updated.isNotEmpty() || report.removed.isNotEmpty()) {
            logger.log(
                SkillHotReloadLogLevel.INFO,
                "技能目录变化已热加载：+${report.added.size} ~${report.updated.size} " +
                    "-${report.removed.size}（跳过 ${report.skipped.size}）"
            )
        }
    }

    /**
     * 目录指纹：顶层 JSON 文件的「名:大小:mtime」有序拼接。
     * 目录不存在 → null（本 tick 跳过；创建后自然恢复）。
     * 资源子目录（`<id>/`）不入指纹——manifest 变化才是协调触发器。
     */
    private fun fingerprint(): String? {
        val dir = runCatching { if (skillsDir.isDirectory) skillsDir else null }.getOrNull()
            ?: return null
        val files = runCatching {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith(".") }
                .orEmpty()
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
        return files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    companion object {
        /** 巡检间隔：2.5s（无变化时单次开销仅一次 listFiles）。 */
        const val DEFAULT_INTERVAL_MS = 2_500L
    }
}
