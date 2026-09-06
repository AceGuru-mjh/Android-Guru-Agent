package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture

/**
 * T82 — apt 镜像注册表（Termux `termux-change-repo` 的 21 镜像能力在本项目的
 * 对应物；基线 §5.3）。默认官方源（HTTP-first bootstrap 语义不变），镜像切换
 * 经 `terminal.linux.packages` 的 `mirror` action —— 显式动作，绝不隐式改源。
 *
 * arm64/armhf 走 ubuntu-ports 镜像目录；amd64/i386 走 ubuntu 目录。全部镜像
 * 均为知名公共镜像（TUNA/USTC/Aliyun —— 中国大陆网络环境下的实际可用性
 * 显著优于 ports.ubuntu.com）。
 *
 * https 能力列标注但**默认仍写 http**（与 UbuntuSourcesList 的 CA bootstrap
 * 次序契约一致：http → ca-certificates → https 可选切换）。
 */
object AptMirrorRegistry {

    data class Spec(
        val id: String,                 // mirror action 的选择键
        val label: String,
        val host: String,
        val ubuntuPath: String,         // amd64/i386
        val ubuntuPortsPath: String,    // arm64/armhf
        val httpsCapable: Boolean
    )

    val OFFICIAL = Spec(
        id = "official", label = "Ubuntu Official",
        host = "ports-archive-mixed", ubuntuPath = "ubuntu", ubuntuPortsPath = "ubuntu-ports",
        httpsCapable = true
    )

    val ALL: List<Spec> = listOf(
        // official 是特殊条目：host 按 arch 决定（archive/ports），见 [resolve]
        Spec("official", "Ubuntu Official (archive/ports)", "", "ubuntu", "ubuntu-ports", true),
        Spec("tuna", "Tsinghua TUNA", "mirrors.tuna.tsinghua.edu.cn", "ubuntu", "ubuntu-ports", true),
        Spec("ustc", "USTC", "mirrors.ustc.edu.cn", "ubuntu", "ubuntu-ports", true),
        Spec("aliyun", "Aliyun", "mirrors.aliyun.com", "ubuntu", "ubuntu-ports", true)
    )

    /** 按 id 解析（未知 id → null）。 */
    fun byId(id: String): Spec? = ALL.firstOrNull { it.id == id }

    /** 官方源的 host 由 arch 决定。 */
    fun officialHost(arch: CpuArchitecture): String = when (arch) {
        CpuArchitecture.ARM64, CpuArchitecture.ARM32 -> "ports.ubuntu.com"
        else -> "archive.ubuntu.com"
    }

    /** spec+arch → UbuntuSourcesList.Mirror。official 的 host 经 [officialHost]。 */
    fun mirrorFor(id: String?, arch: CpuArchitecture): UbuntuSourcesList.Mirror? {
        val spec = id?.let { byId(it) } ?: byId("official") ?: return null
        val host = if (spec.id == "official") officialHost(arch) else spec.host
        val path = when (arch) {
            CpuArchitecture.ARM64, CpuArchitecture.ARM32 -> spec.ubuntuPortsPath
            else -> spec.ubuntuPath
        }
        return UbuntuSourcesList.Mirror(host = host, path = path)
    }

    /** 可选镜像清单（诊断/工具输出）。 */
    fun available(): List<Spec> = ALL
}
