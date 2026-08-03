package com.apex.agent.platform.linux

import com.apex.agent.platform.privilege.PrivilegeManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * proot实现（无Root）
 * 依赖Termux的proot-distro
 */
@Singleton
class ProotLinuxRuntime @Inject constructor(
    private val privilegeManager: PrivilegeManager
) : LinuxRuntime {

    override val isInstalled: Boolean
        get() = checkInstalled()
    
    override val distroName: String = "ubuntu-24.04"
    
    override val pythonVersion: String?
        get() = null // TODO: 运行时查询
    
    override val nodeVersion: String?
        get() = null

    override suspend fun execute(
        command: String,
        workDir: String,
        timeoutMs: Long
    ): LinuxExecResult {
        // 通过proot-distro执行
        val fullCommand = buildString {
            append("proot-distro login ubuntu")
            append(" --bind $workDir:/workspace")
            append(" -- /bin/bash -c 'cd /workspace && $command'")
        }
        
        val result = privilegeManager.executeShell(fullCommand, timeoutMs)
        return LinuxExecResult(
            success = result.success,
            stdout = result.output,
            stderr = "",
            exitCode = result.exitCode
        )
    }

    override fun executeStream(command: String, workDir: String): Flow<LinuxExecOutput> = flow {
        // 流式执行（简化版）
        val result = execute(command, workDir)
        emit(LinuxExecOutput.Stdout(result.stdout))
        emit(LinuxExecOutput.Finished(result.exitCode))
    }

    override suspend fun installPackages(vararg packages: String): LinuxExecResult {
        val pkgStr = packages.joinToString(" ")
        return execute("apt-get update && apt-get install -y $pkgStr", timeoutMs = 300000)
    }

    override suspend fun ensurePython(): Boolean {
        val result = execute("python3 --version")
        if (!result.success) {
            installPackages("python3", "python3-pip")
        }
        return execute("python3 --version").success
    }

    override suspend fun ensureNode(): Boolean {
        val result = execute("node --version")
        if (!result.success) {
            installPackages("nodejs", "npm")
        }
        return execute("node --version").success
    }

    override suspend fun ensureToolchain(): Boolean {
        val result = execute("gcc --version")
        if (!result.success) {
            installPackages("build-essential", "cmake", "make")
        }
        return execute("gcc --version").success
    }

    override suspend fun install(): Boolean {
        // 安装Ubuntu到proot-distro
        val result = privilegeManager.executeShell(
            "proot-distro install ubuntu",
            timeoutMs = 600000  // 10分钟
        )
        return result.success
    }

    private fun checkInstalled(): Boolean {
        // 检查proot-distro是否存在
        return try {
            val result = privilegeManager.executeShell("which proot-distro")
            result.success
        } catch (e: Exception) {
            false
        }
    }
}
