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
     * 确保 home 就绪（mkdirs + 首次播种 + 工具链环境块幂等注入）。
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
        // 工具链环境块（JAVA_HOME/ANDROID_HOME/…）：无论 home 来自播种还是
        // 历史版本，都幂等补齐 —— 旧用户的 .bashrc 没有 -d 探测脚本，
        // JDK 装了 gradle/sdkmanager 依然找不到 JAVA_HOME。
        ensureToolchainEnvBlock()
        hostHomeDir
    }

    /** host home 绝对路径（bind 源；不触发初始化）。 */
    fun hostDir(): File = hostHomeDir

    /**
     * 幂等注入工具链环境探测块到 guest `~/.bashrc`。
     *
     * 背景（Ubuntu 环境探索验证，docs/ubuntu-environment-exploration.md §7）：
     * `terminal.linux.capabilities ensure` / `terminal.workspace.environment ensure`
     * 能经 apt 装上 default-jdk / golang 等，但 guest 环境从不导出 JAVA_HOME ——
     * PATH 里 `java` 能跑，而 gradle / sdkmanager / 一切 `JAVA_HOME` 依赖脚本
     * 全部失败（能力矩阵 7.6 规划了 env profile 持久化，此前未实现）。
     *
     * 实现：往持久 home 的 `.bashrc` 追加一个**受管代码块**（标记对包裹），
     * 块内是 `-d` 目录探测的动态 export —— 装了才导出，未装静默跳过；
     * 用户显式设置过的变量（-z 守卫）永不覆盖。bash -i（PTY 会话）每次
     * 启动 source .bashrc → 新装工具链对**新会话**即时生效；已存活的旧会话
     * 可 `source ~/.bashrc` 立即生效。host 侧 home 跨 rootfs 版本存活 →
     * rootfs 升级不丢配置。
     */
    fun ensureToolchainEnvBlock() {
        val bashrc = File(hostHomeDir, BASHRC)
        if (!bashrc.exists()) {
            // ensureReady 先播种（必然创建 .bashrc），此处防御并发删除的窗口：
            // 拿最小兜底重建，再走统一注入路径。
            bashrc.writeText(MINIMAL_BASHRC + "\n")
        }
        val content = bashrc.readText()
        if (content.contains(BLOCK_END_MARKER)) return  // 已注入（幂等）
        bashrc.writeText(content.trimEnd() + "\n\n" + TOOLCHAIN_ENV_BLOCK + "\n")
    }

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
        private const val BASHRC = ".bashrc"

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

        /**
         * 工具链环境探测块（受管，[ensureToolchainEnvBlock] 幂等注入）。
         *
         * 设计要点：
         *  - 动态 `-d` 探测：包什么时候装上，下一个 shell 启动就导出（无需
         *    host 侧回写）；未装静默跳过，永不产生悬空路径。
         *  - `-z` 守卫：用户/Agent 显式 export 过的变量绝不被覆盖。
         *  - PATH 追加带去重（case 守卫），重复 source 不膨胀。
         *  - 覆盖 apt（/usr/lib/jvm/default-java）与手工安装（~/android-sdk、
         *    /usr/local/go）两类安装位置 —— Android SDK cmdline-tools 没有
         *    apt 包，官方形态就是解压到用户目录。
         */
        internal val TOOLCHAIN_ENV_BLOCK = """
            # >>> apex-toolchain-env (managed by Android-Guru-Agent) >>>
            # Toolchain homes discovered at shell startup — installed-then-export.
            if [ -z "${'$'}JAVA_HOME" ] && [ -d /usr/lib/jvm/default-java ]; then
                export JAVA_HOME="/usr/lib/jvm/default-java"
            fi
            if [ -z "${'$'}GOROOT" ] && [ -x /usr/local/go/bin/go ]; then
                export GOROOT="/usr/local/go"
                case ":${'$'}PATH:" in *":${'$'}GOROOT/bin:"*) ;; *) export PATH="${'$'}GOROOT/bin:${'$'}PATH";; esac
            fi
            if [ -z "${'$'}ANDROID_HOME" ] && [ -d "${'$'}HOME/android-sdk/cmdline-tools" ]; then
                export ANDROID_HOME="${'$'}HOME/android-sdk"
                export ANDROID_SDK_ROOT="${'$'}ANDROID_HOME"
                case ":${'$'}PATH:" in
                    *":${'$'}ANDROID_HOME/cmdline-tools/latest/bin:"*) ;;
                    *) export PATH="${'$'}ANDROID_HOME/cmdline-tools/latest/bin:${'$'}ANDROID_HOME/platform-tools:${'$'}PATH";;
                esac
            fi
            # <<< apex-toolchain-env (managed by Android-Guru-Agent) <<<
        """.trimIndent()

        /** 受管块结束标记（幂等检测锚点；起始标记为同款 BEGIN）。 */
        internal const val BLOCK_END_MARKER = "apex-toolchain-env (managed by Android-Guru-Agent)"
    }
}
