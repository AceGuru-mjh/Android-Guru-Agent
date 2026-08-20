package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.runtime.ShellInfo
import com.apex.agent.platform.terminal.runtime.ShellProvider
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File

/**
 * PR #68: Real PRoot Shell Provider.
 *
 * Probes the rootfs filesystem for /bin/bash and /bin/sh and reports which
 * shells are actually present. NOT hardcoded — checks the real rootfs.
 *
 * Spec: PR #68 — Real Linux Runtime.
 */
class PRootShellProvider(
    private val rootfsPath: AbsolutePath
) : ShellProvider {

    override suspend fun defaultShell(): ShellInfo {
        // Prefer bash if present; fall back to sh.
        val bash = File(rootfsPath.value, "bin/bash")
        if (bash.exists() && bash.canExecute()) {
            return ShellInfo("/bin/bash", "bash", null)
        }
        return ShellInfo("/bin/sh", "sh", null)
    }

    override suspend fun availableShells(): List<ShellInfo> {
        val shells = mutableListOf<ShellInfo>()
        val bash = File(rootfsPath.value, "bin/bash")
        if (bash.exists() && bash.canExecute()) {
            shells.add(ShellInfo("/bin/bash", "bash", null))
        }
        val sh = File(rootfsPath.value, "bin/sh")
        if (sh.exists() && sh.canExecute()) {
            shells.add(ShellInfo("/bin/sh", "sh", null))
        }
        if (shells.isEmpty()) {
            // Rootfs has no shells — report a default so callers don't NPE.
            shells.add(ShellInfo("/bin/sh", "sh", null))
        }
        return shells
    }
}
