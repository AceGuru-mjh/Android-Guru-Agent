package com.apex.agent.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #224：导航返回栈状态机单测 —— 纯逻辑，无需 Android/Compose 组合环境
 * （mutableStateOf 在非组合上下文可直接构造读写）。
 *
 * 锁死三条核心语义，防止后续演化漂移：
 *  1. push 压栈 / pop LIFO 回退（聊天页→设置→返回 = 回聊天上下文）；
 *  2. navigateToTopLevel 替换（抽屉一级导航清空历史，不产生返回层级）；
 *  3. Saver route 往返（旋转/进程重建后 current + history 完整恢复，
 *     含 "skill" 老 route 与未知 route 的兜底映射）。
 */
class NavigationBackStackTest {

    /** SaverScope 测试替身：全放行（不校验 Bundle 可存性） */
    private object AllowAll : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun `压栈返回沿历史 LIFO 回退`() {
        val stack = NavigationBackStack(DrawerDestination.Agent)
        assertFalse(stack.canPop)

        stack.push(DrawerDestination.Settings)
        stack.push(DrawerDestination.About)
        assertTrue(stack.canPop)
        assertEquals(DrawerDestination.About, stack.current)

        assertTrue(stack.pop())
        assertEquals(DrawerDestination.Settings, stack.current)
        assertTrue(stack.pop())
        assertEquals(DrawerDestination.Agent, stack.current)
        assertFalse(stack.canPop)
    }

    @Test
    fun `连续重复压栈被忽略`() {
        val stack = NavigationBackStack(DrawerDestination.Agent)
        stack.push(DrawerDestination.Settings)
        // 「去配置」连点守卫：不产生重复层级
        stack.push(DrawerDestination.Settings)

        assertTrue(stack.pop())
        assertEquals(DrawerDestination.Agent, stack.current)
        // 只有一层历史 —— 第二次 pop 应落空
        assertFalse(stack.pop())
    }

    @Test
    fun `一级导航替换清空历史`() {
        val stack = NavigationBackStack(DrawerDestination.Agent)
        stack.push(DrawerDestination.Settings)

        // 抽屉切换一级目的地 = 全新历史（顶层切换不吃返回键层级）
        stack.navigateToTopLevel(DrawerDestination.Market)
        assertFalse(stack.canPop)
        assertFalse(stack.pop())
        assertEquals(DrawerDestination.Market, stack.current)
    }

    @Test
    fun `栈空 pop 返回 false 且状态不变`() {
        val stack = NavigationBackStack(DrawerDestination.Settings)
        assertFalse(stack.pop())
        assertEquals(DrawerDestination.Settings, stack.current)
    }

    @Test
    fun `Saver 往返保持 current 与历史`() {
        val stack = NavigationBackStack(DrawerDestination.Agent)
        stack.push(DrawerDestination.Settings)
        stack.push(DrawerDestination.About)

        val saved = with(AllowAll) { NavigationBackStack.Saver.save(stack) }
        val restored = NavigationBackStack.Saver.restore(saved!!)!!

        assertEquals(DrawerDestination.About, restored.current)
        assertTrue(restored.pop())
        assertEquals(DrawerDestination.Settings, restored.current)
        assertTrue(restored.pop())
        assertEquals(DrawerDestination.Agent, restored.current)
        assertFalse(restored.canPop)
    }

    @Test
    fun `老 skill route 兜底市场页`() {
        val restored = NavigationBackStack.Saver.restore(arrayListOf("skill"))!!
        assertEquals(DrawerDestination.Market, restored.current)
    }

    @Test
    fun `未知 route 兜底 Agent`() {
        // 历史里的未知 route 同样兜底，不抛异常
        val restored = NavigationBackStack.Saver.restore(arrayListOf("agent", "gone_route"))!!
        assertEquals(DrawerDestination.Agent, restored.current)
        assertEquals(DrawerDestination.Agent, destinationFromRoute("whatever"))
    }

    @Test
    fun `空 route 列表兜底初始态`() {
        val restored = NavigationBackStack.Saver.restore(arrayListOf())!!
        assertEquals(DrawerDestination.Agent, restored.current)
        assertFalse(restored.canPop)
    }
}
