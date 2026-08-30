package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * T76: Bootstrap State Store —— bootstrap 状态机的持久化存储。
 *
 * 镜像 [RootfsMetadataStore] 的模式（Mutex + 原子 temp+rename + schema 版本），
 * 让 bootstrap 的阶段进度跨进程崩溃可恢复（T76 §18 / §27）。
 *
 * 持久化的内容：
 *  - 当前 [BootstrapState]（NOT_STARTED/CHECKING/.../READY/FAILED）
 *  - 已完成阶段的证据（stage → epoch ms）
 *  - 最后一次失败的原因 + stage
 *  - bootstrap 开始/完成时间戳
 *
 * 恢复语义（T76 §18）：App 重启后 [load] 返回上次状态。若状态是
 * CHECKING/CONFIGURING/.../BASE_PACKAGES（IN_PROGRESS 类），说明上次崩溃在
 * bootstrap 中途 —— [UbuntuBootstrapManager.reconcile] 检测到此情况后重新执行
 * 未完成阶段（每个阶段幂等）。绝不假报 READY。
 */
class BootstrapStateStore(
    private val stateFile: File
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    init { stateFile.parentFile?.mkdirs() }

    @Serializable
    data class BootstrapStateRecord(
        val schemaVersion: Int = CURRENT_SCHEMA,
        val state: String,                    // BootstrapState.name
        val stageEvidence: Map<String, Long> = emptyMap(),
        val failedStage: String? = null,
        val failureReason: String? = null,
        val startedAt: Long? = null,
        val finishedAt: Long? = null,
        val lastAttemptAt: Long? = null,
        val baseProfileName: String? = null,
        val installedPackages: List<String> = emptyList()
    )

    suspend fun save(record: BootstrapStateRecord): Result<Unit> = mutex.withLock {
        runCatching {
            val tmp = File(stateFile.parentFile, "${stateFile.name}.tmp")
            tmp.writeText(json.encodeToString(record))
            if (!tmp.renameTo(stateFile)) {
                tmp.copyTo(stateFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun load(): BootstrapStateRecord? = mutex.withLock {
        if (!stateFile.exists()) return@withLock null
        runCatching {
            json.decodeFromString<BootstrapStateRecord>(stateFile.readText())
        }.getOrNull()
    }

    suspend fun delete(): Result<Unit> = mutex.withLock {
        runCatching { stateFile.delete(); Unit }
    }

    suspend fun exists(): Boolean = mutex.withLock { stateFile.exists() }

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

/**
 * T76: Bootstrap 状态机（T76 §14）。
 *
 * ```
 * NOT_STARTED → CHECKING → CONFIGURING → NETWORK_CHECK → APT_UPDATE → BASE_PACKAGES → READY
 *                                                                           ↓
 *                                                                      FAILED (任一阶段失败)
 * ```
 *
 * - [NOT_STARTED]：从未 bootstrap 过。
 * - [CHECKING]：检查 rootfs 是否 READY。
 * - [CONFIGURING]：写 sources.list + 确保 env。
 * - [NETWORK_CHECK]：DNS/HTTP/HTTPS/APT 诊断。
 * - [APT_UPDATE]：apt-get update。
 * - [BASE_PACKAGES]：安装 [BasePackageProfile.essential]。
 * - [READY]：全部完成。
 * - [FAILED]：某阶段失败（保留 failedStage + reason）。
 */
enum class BootstrapState {
    NOT_STARTED, CHECKING, CONFIGURING, NETWORK_CHECK,
    APT_UPDATE, BASE_PACKAGES, READY, FAILED
}

/** 是否处于"进行中"（崩溃恢复检测用）。 */
fun BootstrapState.isInProgress(): Boolean = when (this) {
    BootstrapState.CHECKING, BootstrapState.CONFIGURING,
    BootstrapState.NETWORK_CHECK, BootstrapState.APT_UPDATE,
    BootstrapState.BASE_PACKAGES -> true
    else -> false
}
