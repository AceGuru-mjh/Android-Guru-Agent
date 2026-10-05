package com.apex.agent.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub 连接与偏好管理（加密存储 + 状态流）。
 *
 * ## 默认仓库契约（v3 S3，S4 设置页已按此契约接入）
 * - [defaultRepo]：`""` 未设置 | `"owner"` 用户主页 | `"owner/repo"` 仓库
 *  （落盘恒为 [GithubRepoNormalizer.canonical] 归一化后的值，永不存用户原始输入）；
 * - [saveDefaultRepo]：suspend 契约 —— 输入任意形态（完整链接 / SSH / 裸
 *  owner / owner/repo），归一化失败返回 null（不落盘），成功返回 canonical；
 * - [clearDefaultRepo]：回未设置；
 * - [saveDefaultRepoCanonical]：非挂起变体（连接对话框路径 —— 对话框已自行
 *  归一化，直存 canonical，与 [saveToken] 同步写盘风格一致）。
 *
 * 默认仓库是偏好而非凭据：[disconnect] 清凭据但保留该值（换账号后可在
 * 设置页一键清除，避免误断开连后丢配置）。
 */
@Singleton
class GithubTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "github_secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // P2 修复（静默降级告警）：加密存储初始化失败（keystore 损坏/厂商
            // ROM 异常）时降级明文 prefs —— 旧实现完全静默，用户不知道 Token
            // 以明文落盘。至少留一条 WARN 日志（诊断与安全审计可见）。
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "GithubTokenManager",
                    "EncryptedSharedPreferences 初始化失败，GitHub Token 将以明文存储" +
                        "（${e.javaClass.simpleName}: ${e.message}）—— 建议在系统设置中" +
                        "清除应用数据后重新连接 GitHub"
                )
            }
            context.getSharedPreferences("github_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _connectionState = MutableStateFlow(loadState())
    val connectionState: StateFlow<GithubConnectionState> = _connectionState.asStateFlow()

    /**
     * 默认工作仓库（独立流，见类 KDoc 契约）：空串 = 未设置。
     * 启动时从加密存储加载；[GithubConnectionState] 不携带该字段（S4 仅消费本流）。
     */
    private val _defaultRepo = MutableStateFlow(loadDefaultRepo())
    val defaultRepo: StateFlow<String> = _defaultRepo.asStateFlow()

    fun saveToken(token: String, username: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username ?: "")
            .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            .apply()
        _connectionState.value = GithubConnectionState(true, token, username, System.currentTimeMillis())
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)?.ifBlank { null }
    fun isConnected(): Boolean = !getToken().isNullOrBlank()

    fun disconnect() {
        // 默认仓库是偏好非凭据：凭据清空时保留（换号/误断开不丢配置）
        val preservedRepo = runCatching { prefs.getString(KEY_DEFAULT_REPO, null).orEmpty() }
            .getOrDefault("")
        prefs.edit().clear().apply()
        if (preservedRepo.isNotBlank()) {
            runCatching { prefs.edit().putString(KEY_DEFAULT_REPO, preservedRepo).apply() }
        }
        _connectionState.value = GithubConnectionState(false)
    }

    // ═══ 默认仓库（v3 S3 契约实现，见类 KDoc）═══

    /**
     * 保存默认仓库（suspend 契约）：任意形态输入 → 归一化。
     * @return canonical（`owner` / `owner/repo`）；输入无法识别时 null（不落盘）。
     */
    suspend fun saveDefaultRepo(input: String): String? = withContext(Dispatchers.IO) {
        val ref = GithubRepoNormalizer.normalize(input)
            ?: return@withContext null
        val canonical = GithubRepoNormalizer.canonical(ref)
        persistDefaultRepo(canonical)
        canonical
    }

    /** 清除默认仓库（回到未设置）。 */
    fun clearDefaultRepo() {
        persistDefaultRepo("")
    }

    /**
     * 保存**已归一化**的默认仓库（连接对话框路径：对话框已实时归一化反馈，
     * 确认时透传 canonical）。仍经 [GithubRepoNormalizer] 往返校验防御。
     * @return false = 入参非 canonical 形态（拒绝落盘，调用方可回退提示）。
     */
    fun saveDefaultRepoCanonical(canonical: String): Boolean {
        val ref = GithubRepoNormalizer.normalize(canonical) ?: return false
        if (GithubRepoNormalizer.canonical(ref) != canonical) return false
        persistDefaultRepo(canonical)
        return true
    }

    /** 落盘 + 流更新（防御式：IO 异常折叠 + 留痕，状态流仍尽力同步）。 */
    private fun persistDefaultRepo(canonical: String) {
        val error = runCatching {
            prefs.edit().putString(KEY_DEFAULT_REPO, canonical).apply()
        }.exceptionOrNull()
        if (error != null) {
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "GithubTokenManager",
                    "默认仓库落盘失败（${error.javaClass.simpleName}: ${error.message}）"
                )
            }
        }
        _defaultRepo.value = canonical
    }

    private fun loadDefaultRepo(): String =
        runCatching { prefs.getString(KEY_DEFAULT_REPO, null).orEmpty() }
            .getOrDefault("")

    // v2：共享单例 client（旧实现每次 validateToken 都 new 一个带独立连接池的
    // OkHttpClient，且非 2xx 分支不关 response —— 连接泄漏）
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ApexAgent/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val loginRegex = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"")
                    loginRegex.find(body)?.groupValues?.get(1)
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun loadState(): GithubConnectionState {
        val token = prefs.getString(KEY_TOKEN, null)
        return GithubConnectionState(
            isConnected = !token.isNullOrBlank(),
            token = token,
            username = prefs.getString(KEY_USERNAME, null)?.ifBlank { null },
            connectedAt = prefs.getLong(KEY_CONNECTED_AT, 0)
        )
    }

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_USERNAME = "github_username"
        private const val KEY_CONNECTED_AT = "github_connected_at"
        private const val KEY_DEFAULT_REPO = "github_default_repo"
    }
}

data class GithubConnectionState(
    val isConnected: Boolean = false,
    val token: String? = null,
    val username: String? = null,
    val connectedAt: Long = 0
)
