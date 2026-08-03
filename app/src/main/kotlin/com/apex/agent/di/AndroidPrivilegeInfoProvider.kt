package com.apex.agent.di

import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.platform.privilege.PrivilegeDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 端 PrivilegeInfoProvider 实现
 *
 * 包装 PrivilegeDetector.getPrivilegeLevel()，
 * 让纯JVM的 agent-engine 能感知当前设备的权限等级。
 */
@Singleton
class AndroidPrivilegeInfoProvider @Inject constructor() : PrivilegeInfoProvider {

    override fun currentLevel(): String {
        return try {
            PrivilegeDetector.getPrivilegeLevel().name
        } catch (e: Exception) {
            "NORMAL_SHELL"
        }
    }
}
