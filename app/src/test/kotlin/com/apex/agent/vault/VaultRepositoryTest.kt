package com.apex.agent.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VaultRepository 单测（纯 JVM，InMemoryVaultStore）。
 *
 * 覆盖：save/upsert、脱敏快照、delete（id/label）、label 冲突合并、
 * 用量统计、持久化往返、脱敏器联动（删除后旧密钥退出登记表）。
 */
class VaultRepositoryTest {

    private fun newRepo(store: VaultStore = InMemoryVaultStore()): VaultRepository =
        VaultRepository(store)

    @Test
    fun `save 新增条目并可按标签取回`() {
        val repo = newRepo()
        val saved = repo.save(VaultRepository.newEntry("github-token", "CI 发布用", "ghp_secret123456", VaultOrigin.HUMAN))

        assertEquals(1, repo.entryCount())
        assertEquals("github-token", saved.label)
        assertEquals("ghp_secret123456", repo.resolveSecret("github-token"))
        assertEquals(VaultOrigin.HUMAN, saved.origin)
        assertTrue(saved.createdAt > 0)
    }

    @Test
    fun `snapshots 脱敏视图绝不含 secret`() {
        val repo = newRepo()
        val secret = "ghp_supersecret_value_987654"
        repo.save(VaultRepository.newEntry("github-token", "note-here", secret, VaultOrigin.HUMAN))

        val snaps = repo.snapshots()
        assertEquals(1, snaps.size)
        val snap = snaps.first()
        // 安全红线：快照（vault_list 的数据源）绝不能含明文。
        val snapJson = VaultJson.encodeToString(VaultSnapshot.serializer(), snap)
        assertFalse(snapJson.contains(secret))
        assertFalse(snap.toString().contains(secret))
        // 但元数据齐全：label/note/用量/密文长度。
        assertEquals("github-token", snap.label)
        assertEquals("note-here", snap.note)
        assertEquals(VaultOrigin.HUMAN, snap.origin)
        assertEquals(0, snap.usageCount)
        assertEquals(secret.length, snap.secretLength)
    }

    @Test
    fun `label 冲突时更新既有条目并保留 id 与用量统计`() = runTest {
        val repo = newRepo()
        val first = repo.save(VaultRepository.newEntry("api-key", "旧备注", "old-secret-111111", VaultOrigin.HUMAN))
        repo.recordUsage(first.id)

        // 新 id 但同 label → 合并进既有条目（保留 id/createdAt/usageCount）。
        repo.save(VaultRepository.newEntry("api-key", "新备注", "new-secret-222222", VaultOrigin.AGENT))

        assertEquals(1, repo.entryCount())
        val merged = repo.getByLabel("api-key")!!
        assertEquals(first.id, merged.id)
        assertEquals("new-secret-222222", merged.secret)
        assertEquals("新备注", merged.note)
        assertEquals(VaultOrigin.AGENT, merged.origin)
        assertEquals(1, merged.usageCount) // 用量统计延续
        assertEquals("new-secret-222222", repo.resolveSecret("api-key"))
    }

    @Test
    fun `delete 按 id 与按标签删除`() {
        val repo = newRepo()
        val a = repo.save(VaultRepository.newEntry("a", "", "secret-aaaa-111", VaultOrigin.HUMAN))
        repo.save(VaultRepository.newEntry("b", "", "secret-bbbb-222", VaultOrigin.HUMAN))

        assertTrue(repo.delete(a.id))
        assertFalse(repo.delete(a.id)) // 重复删返回 false
        assertNull(repo.get(a.id))
        assertEquals(1, repo.entryCount())

        assertTrue(repo.deleteByLabel("b"))
        assertEquals(0, repo.entryCount())
        assertNull(repo.resolveSecret("b"))
    }

    @Test
    fun `recordUsage 累计用量并刷新 lastUsedAt`() = runTest {
        val repo = newRepo()
        val e = repo.save(VaultRepository.newEntry("t", "", "secret-tttt-333", VaultOrigin.HUMAN))
        assertEquals(0, e.usageCount)
        assertEquals(0L, e.lastUsedAt)

        repo.recordUsage(e.id)
        repo.recordUsage(e.id)
        val updated = repo.get(e.id)!!

        assertEquals(2, updated.usageCount)
        assertTrue("lastUsedAt 应已被刷新", updated.lastUsedAt > 0)
    }

    @Test
    fun `持久化往返 —— 新仓库实例从同一 store 读回全部条目`() = runTest {
        val store = InMemoryVaultStore()
        val repo1 = VaultRepository(store)
        val a = repo1.save(VaultRepository.newEntry("github-token", "n1", "ghp_roundtrip_111", VaultOrigin.HUMAN))
        repo1.save(VaultRepository.newEntry("openai-key", "n2", "sk-roundtrip_222", VaultOrigin.AGENT))
        repo1.recordUsage(a.id)

        // 新实例（模拟进程重启）：init 从 store.loadRaw() 重建。
        val repo2 = VaultRepository(store)
        assertEquals(2, repo2.entryCount())
        assertEquals("ghp_roundtrip_111", repo2.resolveSecret("github-token"))
        assertEquals("sk-roundtrip_222", repo2.resolveSecret("openai-key"))
        assertEquals(1, repo2.getByLabel("github-token")?.usageCount)
        assertEquals(VaultOrigin.AGENT, repo2.getByLabel("openai-key")?.origin)
    }

    @Test
    fun `坏 JSON 兜底为空库而不崩溃`() {
        val store = object : VaultStore {
            override fun loadRaw(): String = "{corrupted!!"
            override fun saveRaw(json: String) {}
        }
        val repo = VaultRepository(store)
        assertEquals(0, repo.entryCount())
    }

    @Test
    fun `脱敏器联动 —— 删除与覆写后旧密钥退出登记表`() {
        val store = InMemoryVaultStore()
        val repo = VaultRepository(store)
        val redactor = repo.secretRedactor

        val e = repo.save(VaultRepository.newEntry("k", "", "old-secret-value-000", VaultOrigin.HUMAN))
        assertEquals(1, redactor.registeredCount())
        assertTrue(redactor.redact("x old-secret-value-000 y").contains(SecretRedactor.MASK))

        // 覆写：旧密钥退出登记，新密钥进入。
        repo.save(e.copy(secret = "new-secret-value-999"))
        assertEquals(1, redactor.registeredCount())
        assertFalse(redactor.redact("x old-secret-value-000 y").contains(SecretRedactor.MASK))
        assertTrue(redactor.redact("x new-secret-value-999 y").contains(SecretRedactor.MASK))

        // 删除：登记表清空。
        repo.delete(e.id)
        assertEquals(0, redactor.registeredCount())
    }

    @Test
    fun `同 id 编辑保留创建时间与用量`() = runTest {
        val repo = newRepo()
        val e = repo.save(VaultRepository.newEntry("l", "", "secret-aaaa-111", VaultOrigin.HUMAN))
        repo.recordUsage(e.id)
        Thread.sleep(2)

        repo.save(e.copy(note = "edited", secret = "secret-bbbb-222"))
        val after = repo.get(e.id)!!
        assertEquals(e.createdAt, after.createdAt)
        assertEquals(1, after.usageCount)
        assertEquals("edited", after.note)
        assertNotEquals(e.secret, after.secret)
    }
}
