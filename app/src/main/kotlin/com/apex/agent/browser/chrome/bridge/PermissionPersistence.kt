package com.apex.agent.browser.chrome.bridge

import android.content.Context
import com.apex.agent.browser.chrome.WebPermissionResource

/*
 * PermissionPersistence —— PermissionGrantStore 的 Android 持久化实现（P1）。
 *
 * 存储格式：SharedPreferences 的 StringSet，每项 "host\tRESOURCE"（host 不含
 * 制表符，资源名是枚举常量，无转义歧义）。进程重启后「总是允许」依然生效。
 *
 * 用法（NeonChromeWiring 一并传入即可）：
 *   val wiring = NeonChromeWiring(
 *       appContext = context,
 *       grantStore = SharedPreferencesGrantStore(context),  // ← P1 新增，可选
 *       tabOps = EngineTabOps(engine),
 *   )
 *
 * 引擎设置界面撤销定位授权时，建议同时清理 WebView 自身的 retain：
 *   GeolocationPermissions.getInstance().clear(host, null)
 */

class SharedPreferencesGrantStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFS_NAME,
) : PermissionGrantStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun isGranted(originHost: String, resource: WebPermissionResource): Boolean {
        return prefs.getStringSet(KEY_GRANTS, emptySet())
            .orEmpty()
            .contains(cell(originHost, resource))
    }

    override fun remember(originHost: String, resources: Set<WebPermissionResource>) {
        if (resources.isEmpty()) return
        mutate { current ->
            current.apply {
                resources.forEach { add(cell(originHost, it)) }
            }
        }
    }

    override fun revoke(originHost: String, resources: Set<WebPermissionResource>?) {
        val current = prefs.getStringSet(KEY_GRANTS, emptySet()).orEmpty().toMutableSet()
        if (resources == null) {
            current.removeAll { stored -> stored.substringBefore('\t') == originHost }
        } else {
            current.removeAll { stored ->
                val host = stored.substringBefore('\t')
                val resName = stored.substringAfter('\t', "")
                host == originHost && resources.any { it.name == resName }
            }
        }
        prefs.edit().putStringSet(KEY_GRANTS, current).apply()
    }

    override fun rememberedOrigins(): Map<String, Set<WebPermissionResource>> {
        val result = mutableMapOf<String, MutableSet<WebPermissionResource>>()
        prefs.getStringSet(KEY_GRANTS, emptySet()).orEmpty().forEach { stored ->
            val host = stored.substringBefore('\t')
            val resName = stored.substringAfter('\t', "")
            val resource = WebPermissionResource.entries.firstOrNull { it.name == resName }
            if (resource != null) {
                result.getOrPut(host) { mutableSetOf() }.add(resource)
            }
        }
        return result
    }

    /* ---------------- 内部 ---------------- */

    private fun mutate(block: (MutableSet<String>) -> Unit) {
        // SharedPreferences.getStringSet 返回的集合不可直接复用，拷贝后整体写回。
        val current = prefs.getStringSet(KEY_GRANTS, emptySet()).orEmpty().toMutableSet()
        block(current)
        prefs.edit().putStringSet(KEY_GRANTS, current).apply()
    }

    private fun cell(host: String, resource: WebPermissionResource): String = "$host\t${resource.name}"

    private companion object {
        const val KEY_GRANTS = "origin_grants"
        const val DEFAULT_PREFS_NAME = "neon_chrome_permission_grants"
    }
}
