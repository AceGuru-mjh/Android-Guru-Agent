package com.apex.agent.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecretRedactor 单测（纯 JVM）。
 *
 * 覆盖：登记/脱敏、幂等、短密钥忽略、多密钥与前缀包含关系、
 * 注销/清空、空文本短路。
 */
class SecretRedactorTest {

    @Test
    fun `登记后所有出现处替换为掩码`() {
        val r = SecretRedactor()
        r.register(listOf("ghp_verysecret123"))

        val out = r.redact("token=ghp_verysecret123; header ghp_verysecret123 end")
        assertEquals("token=${SecretRedactor.MASK}; header ${SecretRedactor.MASK} end", out)
        assertFalse(out.contains("ghp_verysecret123"))
    }

    @Test
    fun `短于 6 位的密钥不登记（防误杀普通文本）`() {
        val r = SecretRedactor()
        r.register(listOf("abc", "ab12", ""))

        assertEquals(0, r.registeredCount())
        // 原文原样返回（"abc" 出现在普通单词里也不替换）。
        assertEquals("abc table", r.redact("abc table"))
    }

    @Test
    fun `脱敏幂等 —— 已脱敏文本再过一次不变`() {
        val r = SecretRedactor()
        r.register(listOf("supersecret999"))

        val once = r.redact("a supersecret999 b")
        val twice = r.redact(once)
        assertEquals(once, twice)
        assertEquals("a ${SecretRedactor.MASK} b", twice)
    }

    @Test
    fun `多密钥与前缀包含 —— 长密钥优先替换不被短密钥肢解`() {
        val r = SecretRedactor()
        // short 是 long 的前缀：若先替换 short，long 会残留 "789extra" 碎片。
        r.register(listOf("prefix-short", "prefix-short-789extra"))

        val out = r.redact("value=prefix-short-789extra;")
        assertEquals("value=${SecretRedactor.MASK};", out)
        assertFalse(out.contains("789extra"))
    }

    @Test
    fun `注销后不再脱敏该密钥`() {
        val r = SecretRedactor()
        r.register(listOf("secret-one-111", "secret-two-222"))
        assertTrue(r.redact("secret-one-111").contains(SecretRedactor.MASK))

        r.unregister("secret-one-111")
        assertEquals(1, r.registeredCount())
        assertEquals("secret-one-111", r.redact("secret-one-111"))
        assertTrue(r.redact("secret-two-222").contains(SecretRedactor.MASK))
    }

    @Test
    fun `clear 清空全部登记`() {
        val r = SecretRedactor()
        r.register(listOf("secret-one-111", "secret-two-222"))
        r.clear()
        assertEquals(0, r.registeredCount())
        assertEquals("secret-one-111 secret-two-222", r.redact("secret-one-111 secret-two-222"))
    }

    @Test
    fun `空文本与未登记时零开销透传`() {
        val r = SecretRedactor()
        assertEquals("", r.redact(""))
        assertEquals("plain text", r.redact("plain text"))
    }

    @Test
    fun `redactFn 引用与 redact 行为一致（Flow 集成用）`() {
        val r = SecretRedactor()
        r.register(listOf("shared-secret-777"))
        val fn = r.redactFn
        assertEquals(r.redact("x shared-secret-777"), fn("x shared-secret-777"))
    }
}
