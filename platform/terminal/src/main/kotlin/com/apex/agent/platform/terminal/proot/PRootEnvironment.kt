package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.LinuxEnvironment
import com.apex.agent.platform.terminal.linux.LinuxUser
import com.apex.agent.platform.terminal.linux.RuntimeConfiguration
import com.apex.agent.platform.terminal.runtime.ShellInfo
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath

/**
 * PR #68: Real PRoot Environment.
 *
 * Provides Linux-side environment variables (PATH/HOME/SHELL/TMPDIR/LANG)
 * that the PRoot process will see INSIDE the rootfs. NOT Android env vars
 * (those are stripped by ProcessBuilder.environment().clear()).
 *
 * Spec: PR #68 — Real Linux Runtime.
 */
class PRootEnvironment(
    private val rootfsPath: AbsolutePath,
    private val config: RuntimeConfiguration = RuntimeConfiguration.DEFAULT
) : LinuxEnvironment {

    override fun user(): LinuxUser = LinuxUser(
        uid = 0,
        gid = 0,
        username = "root",
        home = WorkspacePath.home(),
        isRoot = true
    )

    override fun homeDirectory(): WorkspacePath = WorkspacePath.home()
    override fun workingDirectory(): WorkspacePath = config.workingDirectory ?: WorkspacePath.work()

    override fun shell(): ShellInfo = ShellInfo("/bin/sh", "sh", null)

    override fun pathEntries(): List<WorkspacePath> = listOf(
        WorkspacePath("workspace:/usr/local/bin"),
        WorkspacePath("workspace:/usr/bin"),
        WorkspacePath("workspace:/bin")
    )

    override fun path(): String? = "/usr/local/bin:/usr/bin:/bin"

    override fun get(name: String): String? = snapshot()[name]

    override fun snapshot(): Map<String, String> {
        // Linux env vars (NOT Android). PRoot's -E injects these into the
        // rootfs namespace. HOME is /home/root (inside rootfs), not Android
        // /data/data/...; PATH is Linux /usr/local/bin:/usr/bin:/bin.
        val env = mutableMapOf(
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "HOME" to "/home/root",
            "SHELL" to "/bin/sh",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color"
        )
        // Request overrides take precedence (e.g. caller-set JAVA_HOME).
        env.putAll(config.environment)
        return env
    }
}
