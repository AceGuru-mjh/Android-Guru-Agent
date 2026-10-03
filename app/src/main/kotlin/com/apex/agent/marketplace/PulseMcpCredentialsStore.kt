package com.apex.agent.marketplace

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # PulseMcpCredentialsStore — PulseMCP 合作凭据存储
 *
 * PulseMCP Sub-Registry 是合伙制 API（X-API-Key + X-Tenant-ID），凭据
 * 由用户从 pulsemcp.com 申请后填入。存储走 EncryptedSharedPreferences
 * （模式与 GithubTokenManager 一致）：keystore 损坏时降级明文 prefs
 * 并留 WARN 日志（不静默）。
 */
@Singleton
class PulseMcpCredentialsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    /** 一对 PulseMCP 凭据（任一为空 = 未配置）。 */
    data class PulseCredentials(
        val apiKey: String,
        val tenantId: String
    ) {
        val isComplete: Boolean get() = apiKey.isNotBlank() && tenantId.isNotBlank()
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 与 GithubTokenManager 同款降级：明文落盘必须留 WARN 痕迹
            runCatching {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "PulseMcpCredentialsStore",
                    "EncryptedSharedPreferences 初始化失败，PulseMCP 凭据将以明文存储" +
                        "（${e.javaClass.simpleName}: ${e.message}）"
                )
            }
            context.getSharedPreferences("$PREFS_NAME-fallback", Context.MODE_PRIVATE)
        }
    }

    /** 保存凭据（空串按清除处理）。 */
    fun save(apiKey: String, tenantId: String) {
        prefs.edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_TENANT_ID, tenantId.trim())
            .apply()
    }

    /** 读取凭据；未配置或残缺返回 null。 */
    fun credentials(): PulseCredentials? {
        val creds = PulseCredentials(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            tenantId = prefs.getString(KEY_TENANT_ID, "").orEmpty()
        )
        return creds.takeIf { it.isComplete }
    }

    /** 是否已配置完整凭据（UI 徽标 / 源可用性判定用）。 */
    fun isConfigured(): Boolean = credentials() != null

    /** 清除凭据。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "pulse_mcp_secure_prefs"
        const val KEY_API_KEY = "pulse_api_key"
        const val KEY_TENANT_ID = "pulse_tenant_id"
    }
}
