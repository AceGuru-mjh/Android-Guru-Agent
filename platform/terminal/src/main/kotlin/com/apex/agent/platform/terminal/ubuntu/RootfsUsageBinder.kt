package com.apex.agent.platform.terminal.ubuntu

/**
 * T81 (U-10)：rootfs 活跃会话引用绑定。
 *
 * 背景：RootfsProvisionerImpl.markInUse()/markIdle() 此前生产零调用 ——
 * remove() 的「活跃会话保护」是死代码：proot 会话运行中可以删掉整个 rootfs
 * （session 立即变僵尸，输出全断）。
 *
 * 接线模式与 workspace.SessionWorkspaceBinder 相同：TerminalRuntime.create
 * LINUX 会话成功后 bind（引用计数 +1），close 成功后 unbind（-1）。
 * remove() 在计数 > 0 时拒绝（Busy）。
 */
interface RootfsUsageBinder {
    /** LINUX 会话创建成功后调用。幂等语义由实现保证（同 session 重复 bind 无副作用）。 */
    fun bind(sessionId: Long)

    /** 会话关闭后调用（close/shutdown 路径）。 */
    fun unbind(sessionId: Long)
}

/**
 * 生产实现：转发给 [RootfsProvisionerImpl] 的引用计数。
 * @param rootfsIdFilter 仅匹配该 rootfs 的会话计数（多 rootfs 场景预留；v1 恒 null = 全部）。
 */
class RootfsUsageBinderImpl(
    private val provisioner: RootfsProvisioner
) : RootfsUsageBinder {

    private val bound = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    override fun bind(sessionId: Long) {
        if (bound.add(sessionId)) {
            if (provisioner is RootfsProvisionerImpl) provisioner.markInUse()
        }
    }

    override fun unbind(sessionId: Long) {
        if (bound.remove(sessionId)) {
            if (provisioner is RootfsProvisionerImpl) provisioner.markIdle()
        }
    }

    /** 当前绑定的会话数（诊断/测试）。 */
    fun boundCount(): Int = bound.size
}
