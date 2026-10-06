package com.apex.agent.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.builtin.ApkInstallResult
import com.apex.agent.core.tools.builtin.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * APK 安装的 Android 实现 —— 走 [PackageInstaller] session API。
 *
 * 与 `pm install`（需要 Shizuku/root）不同，本路径只需 [REQUEST_INSTALL_PACKAGES]
 * 普通权限（已在 AndroidManifest 声明）。
 *
 * 工作流：
 * 1. 创建 [PackageInstaller.Session]（MODE_FULL_INSTALL）；
 * 2. 流式写入 APK bytes 到 session；
 * 3. commit —— 系统弹出安装确认 UI（用户必须点"安装"）；
 * 4. 等待广播 [Intent.EXTRA_INSTALL_RESULT]（最长 60s 超时）。
 *
 * **结果等待**：commit 触发后，安装确认 UI 是异步的——用户可能在 60s 后才点
 * "安装"或"取消"。本实现通过 [InstallResultReceiver] 监听广播，但本工具的
 * 返回值仅表示"install 已触发，等待用户确认"，真正的安装结果由系统通知
 * 用户（不需要本工具等待）。
 *
 * **来源跟踪**：[PackageInstaller.Session.setInstallOrigin]（API 34+）记录
 * 下载 URL，便于 Android 安全审计面板展示来源。
 */
class AndroidApkInstaller(
    private val context: Context
) : ApkInstaller {

    override suspend fun install(apkPath: String, sourceUrl: String?): ApkInstallResult =
        withContext(Dispatchers.IO) {
            try {
                doInstall(apkPath, sourceUrl)
            } catch (e: Exception) {
                AppLogger.instance.error(
                    LogCategory.PLUGIN, "ApkInstaller",
                    "install failed for $apkPath: ${e.message ?: e::class.simpleName}"
                )
                ApkInstallResult(
                    success = false,
                    message = "install error: ${e::class.simpleName ?: "exception"}: ${e.message ?: "no message"}"
                )
            }
        }

    private suspend fun doInstall(apkPath: String, sourceUrl: String?): ApkInstallResult {
        val file = File(apkPath)
        if (!file.exists() || !file.isFile) {
            return ApkInstallResult(
                success = false,
                message = "APK file not found at '$apkPath'"
            )
        }

        val packageManager = context.packageManager
        val installer = packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            // 标记安装来源为本应用（Android 安全审计可见）。
            // 注意：setInstallerPackageName 自 API 21 起公开可用；
            // API 34+ 的 setInstallOrigin(Uri, String) 用于来源 URI 追踪，
            // 但反射调用兼容性差，本实现暂不接入，仅通过日志记录。
            try {
                setInstallerPackageName(context.packageName)
            } catch (_: Throwable) {
                // 某些 ROM 不允许设 installer package，忽略
            }
        }

        if (!sourceUrl.isNullOrBlank()) {
            AppLogger.instance.info(
                LogCategory.PLUGIN, "ApkInstaller",
                "installing APK from source URL: $sourceUrl (sessionId pending)"
            )
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return ApkInstallResult(
                success = false,
                message = "createSession failed: ${e.message ?: e::class.simpleName}"
            )
        }

        // 写入 APK bytes 到 session
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(file).use { input ->
                    session.openWrite(file.name, 0, file.length()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        session.fsync(output)
                    }
                }
                // commit：触发系统安装 UI（用户必须点"安装"）
                val pendingIntent = buildCommitIntent(sessionId)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            try { installer.abandonSession(sessionId) } catch (_: Throwable) {}
            return ApkInstallResult(
                success = false,
                message = "session commit failed: ${e.message ?: e::class.simpleName}"
            )
        }

        // 从 APK 路径反推包名（用于返回值展示）
        val packageName = try {
            val info = packageManager.getPackageArchiveInfo(apkPath, 0)
            info?.packageName
        } catch (_: Throwable) { null }

        return ApkInstallResult(
            success = true,
            sessionId = sessionId,
            packageName = packageName,
            requiresUserConfirmation = true,
            message = "install triggered — system install dialog shown, waiting for user confirmation. " +
                "If user accepts, package will be installed."
        )
    }

    /**
     * 构造 commit 时使用的 PendingIntent —— 指向一个透明的 BroadcastReceiver，
     * 系统安装完成后会广播结果给该 receiver（用于追踪成功/失败，但本工具
     * 不阻塞等待结果，仅触发）。
     */
    private fun buildCommitIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, ApkInstallResultReceiver::class.java).apply {
            action = ApkInstallResultReceiver.ACTION_INSTALL_RESULT
            putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "")
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra("apex.session_id", sessionId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

/**
 * 接收 PackageInstaller commit 后的结果广播。
 *
 * 系统 commit 后会发一个广播（intent action 为 [ACTION_INSTALL_RESULT]），
 * 状态可能是 [PackageInstaller.STATUS_PENDING_USER_ACTION]（要先弹安装 UI）、
 * [PackageInstaller.STATUS_SUCCESS] 或 [PackageInstaller.STATUS_FAILURE]。
 *
 * 当 STATUS_PENDING_USER_ACTION 时，广播 intent 里附带一个 Intent.EXTRA_INTENT
 * 字段——必须立即 startActivity() 这个 intent 才能弹出系统安装 UI。
 */
class ApkInstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        AppLogger.instance.info(
            LogCategory.PLUGIN, "ApkInstaller",
            "install result broadcast received: status=$status, msg=${
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            }"
        )
        // 关键：如果是 PENDING_USER_ACTION，必须立即 startActivity 启动系统确认 UI
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    AppLogger.instance.warn(
                        LogCategory.PLUGIN, "ApkInstaller",
                        "failed to launch install confirmation UI: ${e.message}"
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.apex.agent.action.INSTALL_RESULT"
    }
}
