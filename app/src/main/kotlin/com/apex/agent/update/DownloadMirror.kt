package com.apex.agent.update

import android.content.Context
import android.os.SystemClock
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 下载镜像（高速节点）注册表 —— 关于页更新面板的下载源选择。
 *
 * 背景：GitHub Releases 直连在国内网络环境经常 10KB/s 甚至超时；公共反代镜像
 * （ghfast.top 等）以前缀拼接方式代理 `github.com` 资产，可把下载提速一个数量级。
 *
 * 设计约束：
 * - **零新依赖**：镜像清单内置（拉远端清单本身可能就被墙，鸡生蛋）；
 * - **只改写 github.com**：version.json 中的资产均为 github.com/releases/download
 *   直链，其余域（raw.githubusercontent.com 清单等）保持原样；
 * - **AUTO 档**：测速选优 —— 无测速数据时现场探测一次，全部失败回退直连。
 */
enum class DownloadMirror(val id: String, val prefix: String) {
    /** 测速选优：取延迟最低的加速节点，全挂则直连。 */
    AUTO("auto", ""),

    /** GitHub 官方直连（海外网络 / 有代理环境首选）。 */
    DIRECT("direct", ""),

    /** 公共加速镜像（中国大陆通常最快）。 */
    GHFAST("ghfast", "https://ghfast.top/"),

    /** 公共加速镜像（备用节点）。 */
    GHPROXY_NET("ghproxy-net", "https://ghproxy.net/"),

    /** 公共加速镜像（备用节点）。 */
    GHPROXY_COM("gh-proxy", "https://gh-proxy.com/");

    companion object {
        /** 用户可选列表（AUTO 置顶单独展示，这里只列实体节点）。 */
        val NODES: List<DownloadMirror> = entries.filter { it != AUTO }

        fun byId(id: String?): DownloadMirror =
            entries.firstOrNull { it.id == id } ?: AUTO
    }

    /** 该节点是否为加速镜像（直连/AUTO 返回 false）。 */
    val isAccelerated: Boolean get() = prefix.isNotEmpty()

    /**
     * 把 github.com 资产直链改写为镜像加速链接 —— 所有下载 URL 的唯一出口。
     * 非 github.com 域 / 直连档时原样返回，绝不拼出坏 URL。
     *
     * 实现为枚举成员而非扩展函数：顶层 `fun DownloadMirror.apply(...)` 会与
     * Kotlin 内置作用域函数 apply 重名，调用点易波误读，且 import 时可能
     * 遮蔽 stdlib。
     */
    fun rewrite(assetUrl: String): String {
        if (prefix.isEmpty()) return assetUrl
        if (!assetUrl.startsWith("https://github.com/")) return assetUrl
        return prefix + assetUrl
    }
}

/**
 * 镜像测速 —— 对真实资产 URL 发 HEAD 探测（不跟随重定向，测的是镜像节点自身的
 * 网络 RTT，而非跳转到 GitHub 后的假延迟）。失败的节点不进入结果表。
 */
class MirrorSpeedProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    /**
     * 并发探测所有实体节点。返回 `mirror → RTT(ms)`；收到任何 HTTP 响应（含
     * 302/403 —— 各站对 HEAD 的策略不一）即视为连通。
     */
    suspend fun probeAll(assetUrl: String): Map<DownloadMirror, Long> =
        withContext(Dispatchers.IO) {
            DownloadMirror.NODES.map { mirror ->
                async { probe(mirror, assetUrl)?.let { mirror to it } }
            }.awaitAll().filterNotNull().toMap()
        }

    private fun probe(mirror: DownloadMirror, assetUrl: String): Long? = runCatching {
        val startedAt = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(mirror.rewrite(assetUrl)).head().build()
        client.newCall(request).execute().use { response ->
            response.close()
            SystemClock.elapsedRealtime() - startedAt
        }
    }.onFailure { e ->
        AppLogger.instance.debug(
            LogCategory.SYSTEM,
            "MirrorProbe",
            "镜像 ${mirror.id} 探测失败：${e.message}"
        )
    }.getOrNull()
}

/** 镜像选择持久化（SharedPreferences 轻量档位值，无需 DataStore 上手）。 */
object MirrorPrefs {
    private const val PREFS = "apex_update_prefs"
    private const val KEY_MIRROR = "download_mirror_id"

    fun load(context: Context): DownloadMirror = DownloadMirror.byId(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MIRROR, DownloadMirror.AUTO.id)
    )

    fun save(context: Context, mirror: DownloadMirror) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MIRROR, mirror.id).apply()
    }
}

/**
 * 解析 AUTO 档到具体节点：取测速结果中最快者；无数据/全失败回退直连。
 * （AUTO 是"档位"而非节点 —— 真正发起下载前必须经此函数落定。）
 */
fun resolveAuto(speeds: Map<DownloadMirror, Long>): DownloadMirror =
    speeds.entries.minByOrNull { it.value }?.key ?: DownloadMirror.DIRECT
