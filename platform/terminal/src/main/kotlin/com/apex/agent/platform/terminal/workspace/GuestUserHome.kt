package com.apex.agent.platform.terminal.workspace

import java.io.File

/**
 * T75: Guest 用户 home —— 跨 rootfs 生命周期的持久化用户数据模型。
 *
 * P71-P73 期间 guest 的 HOME=/root 落在 rootfs **内部** —— rootfs 换版本
 * （invalidate / install 新版本 / repair）会把用户在 /root 下的所有文件连同
 * 旧 rootfs 一起删掉。T75 的用户模型：host 侧持久目录 bind 到 guest /root，
 * **用户数据与系统镜像分离**（Termux proot-distro 的 --bind home:/root 同款语义）：
 *
 * ```
 * <filesDir>/linux/home/   ← host 侧真实存储（跨 rootfs 版本存活）
 * guest /root              ← bind 目标（会话内视角）
 * ```
 *
 * 首次初始化（home 为空时）从 rootfs 的 /etc/skel 播种（bash 等 Debian 系包的
 * 标准 skel 文件）；rootfs 无 skel（Ubuntu Base 最小镜像可能没有）则写一个
 * 最小 .bashrc 兜底。非空 home 永不覆盖（幂等）。
 *
 * proot 下 guest 恒为 fake root（uid 0 视图），因此不需要多用户切换 ——
 * "用户模型"在本环境的全部实质就是 HOME 的持久化与播种。
 */
class GuestUserHome(
    /** host 侧持久 home 目录（`<filesDir>/linux/home`）。 */
    private val hostHomeDir: File
) {

    /**
     * 确保 home 就绪（mkdirs + 首次播种）。
     * @param rootfsDir 当前 rootfs 目录（skel 来源；仅播种时读取）
     * @return host home 目录
     */
    fun ensureReady(rootfsDir: File): Result<File> = runCatching {
        if (!hostHomeDir.isDirectory && !hostHomeDir.mkdirs() && !hostHomeDir.isDirectory) {
            throw IllegalStateException("UserHomeError:CreateFailed — 无法创建 ${hostHomeDir.absolutePath}")
        }
        if (isEmptyDir(hostHomeDir)) {
            seed(rootfsDir)
        }
        hostHomeDir
    }

    /** host home 绝对路径（bind 源；不触发初始化）。 */
    fun hostDir(): File = hostHomeDir

    private fun seed(rootfsDir: File) {
        val skel = File(rootfsDir, SKEL_PATH)
        if (skel.isDirectory) {
            val entries = skel.listFiles().orEmpty()
            for (entry in entries) {
                copyRecursivelySeeding(entry, File(hostHomeDir, entry.name))
            }
        }
        // 无论 skel 是否存在/是否含 .bashrc —— 交互提示符必须可用（最小兜底）
        if (!File(hostHomeDir, ".bashrc").exists()) {
            File(hostHomeDir, ".bashrc").writeText(MINIMAL_BASHRC)
        }
    }

    /** skel 播种 copy：不覆盖已存在目标（防御并发/半初始化状态）。 */
    private fun copyRecursivelySeeding(src: File, dst: File) {
        if (dst.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles().orEmpty().forEach { child ->
                copyRecursivelySeeding(child, File(dst, child.name))
            }
        } else {
            runCatching { src.copyTo(dst) }
        }
    }

    private fun isEmptyDir(dir: File): Boolean = dir.listFiles().orEmpty().isEmpty()

    companion object {
        /** guest 侧 home 路径（bind 目标）。 */
        const val GUEST_PATH = "/root"
        private const val SKEL_PATH = "etc/skel"

        /** 无 skel 时的最小 .bashrc —— 交互提示符 + 最常用别名，仅此而已。 */
        internal val MINIMAL_BASHRC = """
            # ~/.bashrc — Android-Guru-Agent guest home（rootfs 无 skel，最小兜底）
            [[ ${'$'}- == *i* ]] || return
            PS1='\[\e[1;32m\]root@ubuntu\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]# '
            alias ll='ls -alF'
            alias la='ls -A'
            export HISTCONTROL=ignoreboth
            export HISTSIZE=500
        """.trimIndent()
    }
}
