package com.apex.agent.browser.chrome.bridge

import com.apex.agent.browser.chrome.PermissionDecision
import com.apex.agent.browser.chrome.WebPermissionResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * PermissionGate 单测（P1）：按 origin 记忆 / 资源级隔离 / 撤销 / 裁决落库规则。
 * 纯 JVM——SharedPreferences 持久化在 Android 侧由编译验证覆盖。
 */
class PermissionGateTest {

    private val cam = WebPermissionResource.CAMERA
    private val mic = WebPermissionResource.MICROPHONE
    private val loc = WebPermissionResource.LOCATION

    /* ---- InMemoryPermissionGrantStore ---- */

    @Test
    fun `remember 后 isGranted 生效`() {
        val store = InMemoryPermissionGrantStore()
        assertFalse(store.isGranted("a.example.com", cam))
        store.remember("a.example.com", setOf(cam))
        assertTrue(store.isGranted("a.example.com", cam))
    }

    @Test
    fun `资源级与 origin 级隔离`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam, mic))
        assertTrue(store.isGranted("a.example.com", cam))
        assertTrue(store.isGranted("a.example.com", mic))
        assertFalse(store.isGranted("a.example.com", loc)) // 未记忆的资源
        assertFalse(store.isGranted("b.example.com", cam)) // 未记忆的 origin
    }

    @Test
    fun `revoke 单资源只清该资源`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam, mic))
        store.revoke("a.example.com", setOf(mic))
        assertTrue(store.isGranted("a.example.com", cam))
        assertFalse(store.isGranted("a.example.com", mic))
    }

    @Test
    fun `revoke null 清空该 origin 全部记忆`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam, mic))
        store.remember("b.example.com", setOf(cam))
        store.revoke("a.example.com", null)
        assertFalse(store.isGranted("a.example.com", cam))
        assertFalse(store.isGranted("a.example.com", mic))
        assertTrue(store.isGranted("b.example.com", cam)) // 其他 origin 不受影响
    }

    @Test
    fun `remember 幂等且可追加`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam))
        store.remember("a.example.com", setOf(cam))
        store.remember("a.example.com", setOf(mic))
        assertEquals(setOf(cam, mic), store.rememberedOrigins()["a.example.com"])
    }

    @Test
    fun `rememberedOrigins 返回快照`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam))
        store.remember("b.example.com", setOf(loc))
        val snapshot = store.rememberedOrigins()
        assertEquals(setOf(cam), snapshot["a.example.com"])
        assertEquals(setOf(loc), snapshot["b.example.com"])
    }

    /* ---- PermissionGate 决策面 ---- */

    @Test
    fun `autoGrantable - 全部资源已记忆才放行`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam))
        assertTrue(PermissionGate.autoGrantable(store, "a.example.com", setOf(cam)))
        assertFalse(PermissionGate.autoGrantable(store, "a.example.com", setOf(cam, mic))) // 部分记忆
        assertFalse(PermissionGate.autoGrantable(store, "b.example.com", setOf(cam))) // 无记忆
    }

    @Test
    fun `autoGrantable - 空资源集与空 store 都不放行`() {
        assertFalse(PermissionGate.autoGrantable(InMemoryPermissionGrantStore(), "a.example.com", emptySet()))
        assertFalse(PermissionGate.autoGrantable(null, "a.example.com", setOf(cam)))
    }

    @Test
    fun `applyDecision - GRANT_ALWAYS 落库`() {
        val store = InMemoryPermissionGrantStore()
        PermissionGate.applyDecision(store, "a.example.com", setOf(cam, mic), PermissionDecision.GRANT_ALWAYS)
        assertTrue(store.isGranted("a.example.com", cam))
        assertTrue(store.isGranted("a.example.com", mic))
    }

    @Test
    fun `applyDecision - GRANT_ONCE 不落库`() {
        val store = InMemoryPermissionGrantStore()
        PermissionGate.applyDecision(store, "a.example.com", setOf(cam), PermissionDecision.GRANT_ONCE)
        assertFalse(store.isGranted("a.example.com", cam))
    }

    @Test
    fun `applyDecision - DENY 清除既有记忆`() {
        val store = InMemoryPermissionGrantStore()
        store.remember("a.example.com", setOf(cam))
        PermissionGate.applyDecision(store, "a.example.com", setOf(cam), PermissionDecision.DENY)
        assertFalse(store.isGranted("a.example.com", cam))
    }

    @Test
    fun `applyDecision - store 为 null 时全部无副作用`() {
        PermissionGate.applyDecision(null, "a.example.com", setOf(cam), PermissionDecision.GRANT_ALWAYS)
        PermissionGate.applyDecision(null, "a.example.com", setOf(cam), PermissionDecision.DENY)
        // 不崩溃即通过
    }
}
