package com.apex.agent.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 金库 Android 持久化实现 —— 完整复刻 [com.apex.agent.github.GithubTokenManager]
 * 的 EncryptedSharedPreferences 模式：
 *
 * - 正常路径：MasterKey（AES256_GCM）+ PrefKey AES256_SIV / PrefValue AES256_GCM，
 *   文件名 `vault_secure_prefs` —— 落盘内容全部密文，root 下直接读 XML 也只见密文；
 * - 失败退化：密钥损坏 / Keystore 异常时退回普通 SP（`vault_prefs_fallback`），
 *   金库仍可用（诚实降级优于整库锁死）；
 * - 原子性：整库单键 `vault_entries_json` putString + commit()——
 *   SharedPreferences 单次提交即单文件原子重写，无半写状态。
 */
@Singleton
class EncryptedPrefsVaultStore @Inject constructor(
    @ApplicationContext private val context: Context
) : VaultStore {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "vault_secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 与 GithubTokenManager 同款退化策略：Keystore/主密钥不可用时
            // 不让金库整体不可用，降级为普通 SP（进程内仍受沙箱保护）。
            context.getSharedPreferences("vault_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    override fun loadRaw(): String = prefs.getString(KEY_ENTRIES, null) ?: "[]"

    override fun saveRaw(json: String) {
        // commit()（同步落盘）：保证“写操作返回即已持久化”，
        // 进程被杀也不丢刚储放的密钥；金库写频极低，阻塞开销可忽略。
        prefs.edit().putString(KEY_ENTRIES, json).commit()
    }

    companion object {
        private const val KEY_ENTRIES = "vault_entries_json"
    }
}
