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
        prefs.edit().clear().apply()
        _connectionState.value = GithubConnectionState(false)
    }

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
    }
}

data class GithubConnectionState(
    val isConnected: Boolean = false,
    val token: String? = null,
    val username: String? = null,
    val connectedAt: Long = 0
)
