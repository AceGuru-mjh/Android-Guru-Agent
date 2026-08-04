package com.apex.agent.core.engine

/**
 * 权限信息提供者
 *
 * 解耦 agent-engine（纯JVM）与 platform:privilege（Android）。
 * agent-engine 不能直接导入 PrivilegeDetector（Android代码），
 * 所以定义这个接口让 app 层实现并注入。
 *
 * 实现方在 app 模块：AndroidPrivilegeInfoProvider，包装 PrivilegeDetector.getPrivilegeLevel()
 */
interface PrivilegeInfoProvider {

    /**
     * 返回当前最高可用权限等级的字符串标识。
     *
     * 约定值（与 platform:privilege 的 PrivilegeLevel enum name 一致）：
     * - "ROOT"         — su 权限，全能
     * - "SHIZUKU"      — ADB 级（uid=2000），pm/am/settings/dumpsys/input 等
     * - "NORMAL_SHELL" — 普通 shell，只能访问自己 sandbox
     */
    fun currentLevel(): String
}
