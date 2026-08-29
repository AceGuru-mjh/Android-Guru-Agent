package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.proot.RootfsValidator

/**
 * PR #69 §21: ProvisionedRootfsProvider — the concrete RootfsProvider that
 * LinuxPRootBackend (P71) consumes.
 *
 * This is the bridge between the Provisioning layer (T72) and the execution
 * backend layer (P71). LinuxPRootBackend's constructor takes a `RootfsProvider`;
 * production passes an instance of THIS class (T73 DI wiring). Its `current()`
 * returns the active, provisioned Ubuntu rootfs (or null if not yet installed);
 * its `verify()` validates the active rootfs's layout.
 *
 * §21: RootfsProvider and RootfsProvisioner are DECOUPLED. The provisioner
 * installs + manages the rootfs lifecycle; the provider is a thin read-only
 * facade the backend queries. The backend never knows about download/
 * extract/activate — those live in the provisioner.
 *
 * §4: NO TerminalCore / Session / PTY modification. This class only
 * implements the two RootfsProvider methods.
 */
class ProvisionedRootfsProvider(
    private val provisioner: RootfsProvisioner,
    private val validator: RootfsValidator? = null
) : RootfsProvider {

    /**
     * §21: returns the currently-active rootfs, or null if none installed.
     * LinuxPRootBackend.availability() calls this; if null, the backend reports
     * NeedsRootfs (caller should invoke the provisioner via terminal.ubuntu.install).
     */
    override suspend fun current(): RootfsDescriptor? = provisioner.current()

    /**
     * §13: validates the active rootfs. Delegates to the P68 RootfsValidator
     * if configured; falls back to a layout check otherwise.
     */
    override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> {
        val validator = this.validator
        if (validator != null) {
            return validator.validate(rootfs).map { validation ->
                RootfsVerification(
                    valid = validation.valid,
                    state = if (validation.valid)
                        com.apex.agent.platform.terminal.linux.RootfsState.AVAILABLE
                    else com.apex.agent.platform.terminal.linux.RootfsState.INVALID,
                    issues = validation.errors.map { it.name }
                )
            }
        }
        // Fallback: light layout check
        val loc = rootfs.location ?: return Result.success(
            RootfsVerification(false, com.apex.agent.platform.terminal.linux.RootfsState.INVALID, listOf("no location"))
        )
        val dir = java.io.File(loc.value)
        if (!dir.exists()) return Result.success(
            RootfsVerification(false, com.apex.agent.platform.terminal.linux.RootfsState.INVALID, listOf("rootfs dir missing"))
        )
        val required = listOf("bin", "etc", "usr")
        val missing = required.filter { !java.io.File(dir, it).exists() }
        return Result.success(
            if (missing.isEmpty()) RootfsVerification(true, com.apex.agent.platform.terminal.linux.RootfsState.AVAILABLE, emptyList())
            else RootfsVerification(false, com.apex.agent.platform.terminal.linux.RootfsState.INVALID, missing.map { "/$it" })
        )
    }
}
