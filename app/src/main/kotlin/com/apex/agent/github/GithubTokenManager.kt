package com.apex.agent.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ApexAgent/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            } else null
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
