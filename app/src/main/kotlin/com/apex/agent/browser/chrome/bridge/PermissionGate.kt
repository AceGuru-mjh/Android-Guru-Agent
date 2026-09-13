package com.apex.agent.browser.chrome.bridge

import com.apex.agent.browser.chrome.PermissionDecision
import com.apex.agent.browser.chrome.WebPermissionResource
import java.util.concurrent.ConcurrentHashMap

/*
 * PermissionGate —— 「总是允许」按 origin 记忆（P1 权限持久化的纯逻辑核）。
 *
 * 之前的行为：用户点「总是允许」只对当次 WebView 会话生效（定位靠 WebView retain，
 * 相机/麦克风则完全不记忆），下次访问同一站点还要再弹一次。
 * 现在引入 [PermissionGrantStore]：GRANT_ALWAYS 落库、重复请求自动放行、
 * 显式 DENY / 撤销 API 可清除记忆。
 *
 * 本文件刻意零 android 依赖（SharedPreferences 实现在 PermissionPersistence.kt），
 * 因此记忆规则可以全部在 JVM 上单测。
 */

/** 按 origin 记忆的授权存储。实现方负责线程安全与持久化。 */
interface PermissionGrantStore {

    /** 该 origin 是否对指定资源持有「总是允许」。 */
    fun isGranted(originHost: String, resource: WebPermissionResource): Boolean

    /** 记忆一批「总是允许」（幂等，集合并入）。 */
    fun remember(originHost: String, resources: Set<WebPermissionResource>)

    /**
     * 撤销记忆。resources 为 null 时清除该 origin 的全部记忆；
     * 引擎设置界面可用它实现「站点权限管理」。
     */
    fun revoke(originHost: String, resources: Set<WebPermissionResource>? = null)

    /** 当前全部记忆（host → 资源集合），供设置界面展示。 */
    fun rememberedOrigins(): Map<String, Set<WebPermissionResource>>
}

/** 线程安全的内存实现：JVM 单测与「不持久化」场景的缺省值。 */
class InMemoryPermissionGrantStore : PermissionGrantStore {

    private val grants = ConcurrentHashMap<String, MutableSet<WebPermissionResource>>()

    override fun isGranted(originHost: String, resource: WebPermissionResource): Boolean =
        grants[originHost]?.contains(resource) == true

    override fun remember(originHost: String, resources: Set<WebPermissionResource>) {
        if (resources.isEmpty()) return
        val set = grants.computeIfAbsent(originHost) { ConcurrentHashMap.newKeySet() }
        synchronized(set) { set.addAll(resources) }
    }

    override fun revoke(originHost: String, resources: Set<WebPermissionResource>?) {
        if (resources == null) {
            grants.remove(originHost)
            return
        }
        val set = grants[originHost] ?: return
        synchronized(set) { set.removeAll(resources) }
        if (set.isEmpty()) grants.remove(originHost, set)
    }

    override fun rememberedOrigins(): Map<String, Set<WebPermissionResource>> =
        grants.mapValues { (_, v) -> synchronized(v) { v.toSet() } }
}

/** 记忆规则的纯函数决策面（store 为 null 时全部退化为「问用户」）。 */
object PermissionGate {

    /**
     * 请求的全部资源都已被记忆 → 可自动放行，不打扰用户。
     * 空资源集返回 false（异常请求交人类兜底）。
     */
    fun autoGrantable(
        store: PermissionGrantStore?,
        originHost: String,
        resources: Set<WebPermissionResource>,
    ): Boolean {
        if (store == null || resources.isEmpty()) return false
        return resources.all { store.isGranted(originHost, it) }
    }

    /**
     * 把一次用户裁决落库：
     * - GRANT_ALWAYS → 记忆该批资源（下次同 origin 同资源静默放行）；
     * - DENY         → 撤销该批资源的记忆（用户最新意图优先）；
     * - GRANT_ONCE   → 不落库（会话内单次授权，行为与之前完全一致）。
     */
    fun applyDecision(
        store: PermissionGrantStore?,
        originHost: String,
        resources: Set<WebPermissionResource>,
        decision: PermissionDecision,
    ) {
        if (store == null || resources.isEmpty()) return
        when (decision) {
            PermissionDecision.GRANT_ALWAYS -> store.remember(originHost, resources)
            PermissionDecision.DENY -> store.revoke(originHost, resources)
            PermissionDecision.GRANT_ONCE -> Unit
        }
    }
}
