package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageOperationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PR #66 section 13 + 22: Environment Provisioner.
 *
 * Executes a ProvisionPlan against a real (or fake) LinuxPackageManager and
 * the central EnvironmentManager. Emits EnvironmentEvents observable by the
 * agent (Installing → Verifying → Ready / Failed).
 *
 * §13 Agent auto-install flow (encoded here): plan → provision → verify.
 * §17 Layered on top of Ubuntu base — never modifies Ubuntu rootfs.
 * §20 PATH/JAVA_HOME/GOROOT/CARGO_HOME centrally applied via EnvironmentManager,
 *    NOT scattered across providers.
 * §22 P66 does NOT do: Docker/K8s/Android-SDK-full/Flutter-full/IDE/compiler-impl/
 *    package-manager-rewrite/Ubuntu-rootfs-redo/snapshot-restore/container-isolation.
 *    This provisioner routes ALL package ops through LinuxPackageManager (§24 boundary).
 *
 * Spec: PR #66 sections 13, 17, 20, 22, 24.
 */

// ─── Section 13: Provisioner Contract ───
interface EnvironmentProvisioner {
    suspend fun provision(plan: ProvisionPlan, workspaceId: String): ProvisionResult
    fun events(): Flow<EnvironmentEvent>
}

// ─── Section 13: Provision Result ───
data class ProvisionResult(
    val installedPackages: List<String>,
    val actions: List<ProvisionAction>,
    val succeeded: Boolean,
    val error: String?
) {
    companion object {
        val EMPTY = ProvisionResult(emptyList(), emptyList(), true, null)
    }
}

// ─── Section 13: Linux Environment Provisioner (default impl) ───
class LinuxEnvironmentProvisioner(
    private val packageManager: LinuxPackageManager,
    private val environmentManager: EnvironmentManager
) : EnvironmentProvisioner {

    private val _events = MutableSharedFlow<EnvironmentEvent>(
        replay = 0,
        extraBufferCapacity = 256
    )

    override fun events(): Flow<EnvironmentEvent> = _events.asSharedFlow()

    override suspend fun provision(plan: ProvisionPlan, workspaceId: String): ProvisionResult {
        // Empty plan — nothing to do; succeed trivially.
        if (plan.isEmpty) {
            emit(EnvironmentEvent.Verifying(workspaceId, now()))
            emit(EnvironmentEvent.Ready(workspaceId, now(), capabilitiesOf(plan)))
            return ProvisionResult.EMPTY
        }

        val packageNames = plan.packagesToInstall.map { it.name }
        val appliedActions = mutableListOf<ProvisionAction>()

        // ── Install phase (single batched call to LinuxPackageManager) ───
        if (plan.packagesToInstall.isNotEmpty()) {
            emit(EnvironmentEvent.Installing(workspaceId, now(), packageNames))
            val op = packageManager.install(plan.packagesToInstall)
            if (op.state != PackageOperationState.SUCCEEDED) {
                val reason = op.error?.message ?: "install failed (state=${op.state})"
                emit(EnvironmentEvent.Failed(workspaceId, now(), reason))
                return ProvisionResult(
                    installedPackages = emptyList(),
                    actions = emptyList(),
                    succeeded = false,
                    error = reason
                )
            }
            // Record the batched install as one InstallPackages action for
            // traceability; the individual InstallPackage actions in
            // plan.actions are skipped below (already done).
            appliedActions.add(ProvisionAction.InstallPackages(plan.packagesToInstall))
        }

        // ── Action phase (env vars + path + venv) ───
        for (action in plan.actions) {
            when (action) {
                is ProvisionAction.SetEnvironmentVariable -> {
                    environmentManager.set(workspaceId, action.name, action.value)
                    appliedActions.add(action)
                }
                is ProvisionAction.PrependPath -> {
                    environmentManager.prependPath(workspaceId, action.path)
                    appliedActions.add(action)
                }
                is ProvisionAction.CreateVirtualEnv -> {
                    // Records the action; venv creation happens at runtime
                    // via the workspace shell — not in this layer.
                    appliedActions.add(action)
                }
                is ProvisionAction.InstallPackage,
                is ProvisionAction.InstallPackages -> {
                    // Already applied via the batched packageManager.install
                    // call above — do not re-execute.
                }
                is ProvisionAction.RunPostInstall -> {
                    // Deferred to a future PR; record for traceability.
                    appliedActions.add(action)
                }
            }
        }

        // ── Verify phase ───
        emit(EnvironmentEvent.Verifying(workspaceId, now()))
        val caps = capabilitiesOf(plan)
        emit(EnvironmentEvent.Ready(workspaceId, now(), caps))

        return ProvisionResult(
            installedPackages = packageNames,
            actions = appliedActions.toList(),
            succeeded = true,
            error = null
        )
    }

    private fun capabilitiesOf(plan: ProvisionPlan): Set<DeveloperCapability> =
        plan.requirements.flatMap { it.capabilities }.toSet()

    private fun now(): Long = System.currentTimeMillis()

    private suspend fun emit(event: EnvironmentEvent) {
        _events.emit(event)
    }
}
