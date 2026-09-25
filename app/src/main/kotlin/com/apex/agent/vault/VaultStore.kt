package com.apex.agent.vault

/**
 * 金库持久化接口 —— 存储层与业务层（[VaultRepository]）的唯一边界。
 *
 * 只交换整库 JSON（[VaultEntry] 数组），由 Android 侧实现
 * （[EncryptedPrefsVaultStore]）负责加密；业务层保持纯 Kotlin 可 JVM 单测。
 */
interface VaultStore {

    /** 读取整库 JSON 数组；空库返回 "[]"（永不返回 null/抛异常，坏数据由仓库层兜底）。 */
    fun loadRaw(): String

    /** 原子写入整库 JSON（单键整体 putString —— SharedPreferences 单次提交本身原子）。 */
    fun saveRaw(json: String)
}

/** 进程内实现 —— JVM 单测 / 预览用，无任何 Android 依赖。 */
class InMemoryVaultStore : VaultStore {

    @Volatile
    private var json: String = "[]"

    override fun loadRaw(): String = json

    override fun saveRaw(json: String) {
        this.json = json
    }
}
