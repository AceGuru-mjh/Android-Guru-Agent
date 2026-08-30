package com.apex.agent.platform.terminal.ubuntu

/**
 * T76: Base Package Profile —— Ubuntu Agent 基础环境的默认包清单。
 *
 * 不装桌面环境/大型工具链。只装 Agent 长期工作真正需要的 CLI 基础（T76 §15）：
 *  - 系统基础：ca-certificates（HTTPS 必需）、curl、wget、bash、coreutils、findutils、
 *    grep、sed、awk、tar、gzip、bzip2、xz-utils、unzip、zip、procps、psmisc、file、less
 *  - Agent 工作流：git、python3、python3-pip
 *
 * 设计为可扩展 profile —— 未来可加 NodeProfile / RustProfile 等，但 T76 默认
 * 只装这一套。绝不"为了看起来完整"默认装几十/上百个包。
 *
 * bootstrap 的 BASE_PACKAGES 阶段安装本 profile 中 [essential] 的包；
 * [recommended] 由 Agent 按需通过 terminal.linux.packages 安装（不自动装）。
 */
data class BasePackageProfile(
    /** 必装包（bootstrap 自动安装）。 */
    val essential: List<String>,
    /** 推荐包（bootstrap 不自动装，Agent 按需）。 */
    val recommended: List<String>,
    val name: String = "ubuntu-base-cli"
) {

    /** essential 包数（测试/诊断用）。 */
    val essentialCount: Int get() = essential.size

    companion object {
        /**
         * T76 默认 profile —— Ubuntu 24.04 Agent 基础 CLI 环境。
         *
         * 分组（语义可读，非功能性）：
         *  - 网络与 TLS：ca-certificates, curl, wget
         *  - shell 与文本：bash, coreutils, findutils, grep, sed, awk, less, file
         *  - 归档：tar, gzip, bzip2, xz-utils, unzip, zip
         *  - 进程与系统：procps, psmisc
         *  - 版本控制：git
         *  - 运行时：python3, python3-pip
         */
        val DEFAULT = BasePackageProfile(
            essential = listOf(
                // 网络 / TLS
                "ca-certificates", "curl", "wget",
                // shell / 文本
                "bash", "coreutils", "findutils", "grep", "sed", "awk", "less", "file",
                // 归档
                "tar", "gzip", "bzip2", "xz-utils", "unzip", "zip",
                // 进程 / 系统
                "procps", "psmisc",
                // 版本控制
                "git",
                // 运行时
                "python3", "python3-pip"
            ),
            recommended = listOf(
                "openssh-client", "vim-tiny", "nano-tiny",
                "dnsutils", "iproute2", "iputils-ping",
                "jq", "tree"
            )
        )

        /** 最小 profile（仅网络 + shell 骨架 —— 用于受限磁盘/快速 bootstrap）。 */
        val MINIMAL = BasePackageProfile(
            essential = listOf("ca-certificates", "curl", "bash", "coreutils"),
            recommended = emptyList(),
            name = "ubuntu-minimal"
        )
    }
}
