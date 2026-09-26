package com.apex.agent.core.engine.templates

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 导入模式。 */
enum class ImportMode {
    /** 合并： incoming 覆盖同 id 的自定义模板；内置模板永不替换。 */
    MERGE,

    /** 替换： 清空后整库替换为 incoming（内置模板随之移除，可 reseedBuiltIns 还原）。 */
    REPLACE
}

/** 导入结果（防御式：解析失败 ok=false + error 留痕，不抛）。 */
data class TemplateImportResult(
    val ok: Boolean,
    val importedCount: Int,
    val skippedBuiltInCount: Int,
    val skippedInvalidCount: Int,
    val error: String? = null
)

/**
 * ═══ 提示词模板注册表（4-b）═══
 *
 * 学习 Operit 的 prompt/config 库（单源持久化 + 内置指令不可删）与
 * apex 的 FileTaskStore / LongTaskStore 纪律，落在 core/agent-engine
 * 纯 JVM 层：
 *
 * ```
 * <storageDir>/                DI 注入（App: filesDir/templates；测试: 临时目录）
 *   ├── templates.json         整库单文件（TemplateLibrary 信封）
 *   ├── templates.json.tmp     写入中 temp（崩溃残留，首载清理）
 *   └── templates.json.corrupt 损坏备份（加载失败时隔离，镜像 RikkaHub 损坏处理）
 * ```
 *
 * **原子写**：tmp 写入 → flush → fd.sync()（fsync）→ 同目录 rename；
 * rename 失败回退 copyTo + delete（FileTaskStore 同款）。
 *
 * **防御式读**：损坏 JSON → 备份到 templates.json.corrupt + 空库启动 +
 * logger 留痕，绝不抛（一条坏文件不该让模板功能瘫痪）。
 *
 * **并发模型**（对齐 LongTaskStore）：@Volatile 不可变快照缓存 + 写
 * Mutex 串行读改写 + 惰性双检加载。读侧（get/list/export）纯内存。
 *
 * **内置模板**：仅由 [reseedBuiltIns] 管理（缺则补，force=true 还原）；
 * 删除拒绝；导入永远不触碰内置 id（dropped + 计数留痕）。
 */
class PromptTemplateRegistry(
    private val storageDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
    val onChanged: (() -> Unit)? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true   // 前向兼容：未来字段不炸
        isLenient = true
        encodeDefaults = true      // 默认值显式落盘（diff 友好）
        prettyPrint = false
    }

    /** 写路径串行锁：读缓存 → 改 → 发布 → 写盘 四步原子。 */
    private val mutex = Mutex()

    /** 内存缓存：不可变快照，写时复制整体替换（LinkedHashMap 保插入序）。 */
    @Volatile
    private var cache: Map<String, PromptTemplate> = LinkedHashMap()

    /** 惰性加载双检锁 + 完成旗标。 */
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    init {
        // 构造即建目录（幂等，失败不抛：只读场景照样可用）
        runCatching { storageDir.mkdirs() }
            .onFailure { logger("PromptTemplateRegistry: mkdirs failed: ${it.message}") }
    }

    // ═══ 读 ═══

    /** 按 id 查询（非法 id 返回 null——外部查询语义，不抛）。 */
    fun get(id: String): PromptTemplate? {
        if (!TEMPLATE_ID_PATTERN.matches(id)) return null
        ensureLoaded()
        return cache[id]
    }

    /** 列出模板（可选分类过滤；插入序 = 内置播种序 + 后续保存序）。 */
    fun list(category: TemplateCategory? = null): List<PromptTemplate> {
        ensureLoaded()
        return if (category == null) cache.values.toList() else cache.values.filter { it.category == category }
    }

    /** 导出整库为 pretty JSON（分享/备份；含内置模板）。 */
    fun exportLibrary(): String {
        ensureLoaded()
        val pretty = Json { encodeDefaults = true; prettyPrint = true }
        return pretty.encodeToString(TemplateLibrary.serializer(), TemplateLibrary(cache.values.toList()))
    }

    // ═══ 写 ═══

    /**
     * 保存（upsert by id）。校验：id 非空且匹配 [TEMPLATE_ID_PATTERN]，
     * name/content 非空白——违规抛 [IllegalArgumentException]（调用方
     * bug，fail-fast）。updatedAt 一律盖当前时钟戳；新建时 createdAt
     * 缺省（0）则补时钟戳，显式携带则保留（导入场景）。
     *
     * isBuiltIn 规范化：只有已知内置 id 可持有删除保护（已存在内置
     * 条目保持 true，防止借 save 摘掉保护；普通 id 强制 false）。磁盘
     * 写失败只留痕不抛（内存已更新，下次写盘追平）。
     */
    suspend fun save(template: PromptTemplate) {
        validateForSave(template)
        var changed = false
        mutex.withLock {
            ensureLoaded()
            val existing = cache[template.id]
            val now = clock()
            val toStore = template.copy(
                isBuiltIn = existing?.isBuiltIn == true ||
                    BuiltinPromptTemplates.isKnownBuiltInId(template.id),
                createdAt = when {
                    existing != null -> existing.createdAt
                    template.createdAt > 0 -> template.createdAt
                    else -> now
                },
                updatedAt = now
            )
            cache = LinkedHashMap(cache).apply { put(toStore.id, toStore) }
            changed = true
            persistLocked()
        }
        if (changed) onChanged?.invoke()
    }

    /**
     * 删除模板：内置模板拒绝删除（返回 false，可 reseedBuiltIns 还原）；
     * 不存在返回 false；成功返回 true。
     */
    suspend fun delete(id: String): Boolean {
        var removed = false
        mutex.withLock {
            ensureLoaded()
            val existing = cache[id] ?: return false
            if (existing.isBuiltIn) {
                logger("PromptTemplateRegistry: refuse to delete built-in template '$id' (use reseedBuiltIns to restore)")
                return false
            }
            cache = LinkedHashMap(cache).apply { remove(id) }
            removed = true
            persistLocked()
        }
        if (removed) onChanged?.invoke()
        return removed
    }

    /**
     * 播种内置模板（幂等）：缺则补；force=true 时把已存在的内置条目
     * 整体还原为出厂定义（内容/变量/使用计数全重置，救「被改坏」）。
     * 返回写入条数（补种或还原的数量）。
     */
    suspend fun reseedBuiltIns(force: Boolean = false): Int {
        var seeded = 0
        mutex.withLock {
            ensureLoaded()
            val next = LinkedHashMap(cache)
            for (builtin in BuiltinPromptTemplates.ALL) {
                val existing = next[builtin.id]
                when {
                    existing == null -> {
                        next[builtin.id] = builtin
                        seeded++
                    }
                    force && existing != builtin -> {
                        next[builtin.id] = builtin
                        seeded++
                    }
                    else -> Unit
                }
            }
            if (seeded > 0) {
                cache = next
                persistLocked()
            }
        }
        if (seeded > 0) onChanged?.invoke()
        return seeded
    }

    /**
     * 导入模板库（防御式：坏 JSON → ok=false + error 留痕，不抛）。
     *
     * - 已知内置 id 的 incoming 条目在两种模式下都被丢弃（内置模板仅由
     *   reseedBuiltIns 管理——单一事实源，杜绝导入伪造内置条目）；
     * - 其余条目一律落为自定义（isBuiltIn=false）；
     * - 非法条目（id 不合规/名称或正文空白）跳过 + 计数留痕；
     * - MERGE 覆盖同 id 自定义模板；REPLACE 清空后整库替换。
     */
    suspend fun importLibrary(payload: String, mode: ImportMode): TemplateImportResult {
        val library = try {
            json.decodeFromString(TemplateLibrary.serializer(), payload)
        } catch (e: Exception) {
            logger("PromptTemplateRegistry: import parse failed (${e::class.simpleName}: ${e.message})")
            return TemplateImportResult(ok = false, 0, 0, 0, error = e.message)
        }
        var imported = 0
        var skippedBuiltIn = 0
        var skippedInvalid = 0
        mutex.withLock {
            ensureLoaded()
            val now = clock()
            val next = if (mode == ImportMode.REPLACE) LinkedHashMap() else LinkedHashMap(cache)
            for (incoming in library.templates) {
                when {
                    BuiltinPromptTemplates.isKnownBuiltInId(incoming.id) -> {
                        skippedBuiltIn++
                        logger("PromptTemplateRegistry: import skipped built-in id '${incoming.id}'")
                    }
                    !isValidTemplate(incoming) -> {
                        skippedInvalid++
                        logger("PromptTemplateRegistry: import skipped invalid template '${incoming.id}'")
                    }
                    else -> {
                        next[incoming.id] = incoming.copy(
                            isBuiltIn = false,
                            createdAt = if (incoming.createdAt > 0) incoming.createdAt else now,
                            updatedAt = if (incoming.updatedAt > 0) incoming.updatedAt else now
                        )
                        imported++
                    }
                }
            }
            cache = next
            if (mode == ImportMode.REPLACE || imported > 0) {
                persistLocked()
            }
        }
        if (mode == ImportMode.REPLACE || imported > 0) {
            onChanged?.invoke()
        }
        return TemplateImportResult(ok = true, imported, skippedBuiltIn, skippedInvalid)
    }

    /**
     * 渲染计数自增（引擎 render 调用）：只动 usageCount，不碰
     * updatedAt（渲染不是内容修改）。模板不存在则无操作。
     */
    suspend fun incrementUsage(templateId: String) {
        mutex.withLock {
            ensureLoaded()
            val existing = cache[templateId] ?: return
            cache = LinkedHashMap(cache).apply {
                put(templateId, existing.copy(usageCount = existing.usageCount + 1))
            }
            persistLocked()
        }
    }

    // ═══ 校验 ═══

    private fun validateForSave(template: PromptTemplate) {
        require(TEMPLATE_ID_PATTERN.matches(template.id)) {
            "illegal template id '${template.id}' (expected [a-z0-9_-]{1,64})"
        }
        require(template.name.isNotBlank()) { "template name must not be blank (id='${template.id}')" }
        require(template.content.isNotBlank()) { "template content must not be blank (id='${template.id}')" }
    }

    /** 导入条目校验（非法跳过而非抛——外部输入的宽容语义）。 */
    private fun isValidTemplate(template: PromptTemplate): Boolean =
        TEMPLATE_ID_PATTERN.matches(template.id) &&
            template.name.isNotBlank() &&
            template.content.isNotBlank()

    // ═══ 持久化 ═══

    /** 首次访问同步扫描加载（双检 + 监视器锁；损坏备份后空库启动）。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val target = File(storageDir, FILE_NAME)
            if (target.exists()) {
                try {
                    val library = json.decodeFromString(TemplateLibrary.serializer(), target.readText())
                    val normalized = LinkedHashMap<String, PromptTemplate>()
                    for (t in library.templates) {
                        if (!isValidTemplate(t)) {
                            logger("PromptTemplateRegistry: skip invalid template in store '${t.id}'")
                            continue
                        }
                        // isBuiltIn 规范化：只有已知内置 id 可持有删除保护
                        normalized[t.id] = t.copy(isBuiltIn = BuiltinPromptTemplates.isKnownBuiltInId(t.id))
                    }
                    cache = normalized
                } catch (e: Exception) {
                    logger(
                        "PromptTemplateRegistry: templates.json corrupt, backing up and starting empty " +
                            "(${e::class.simpleName}: ${e.message})"
                    )
                    runCatching {
                        val backup = File(storageDir, CORRUPT_FILE_NAME)
                        if (backup.exists()) backup.delete()
                        target.renameTo(backup)
                    }.onFailure {
                        logger("PromptTemplateRegistry: corrupt backup move failed: ${it.message}")
                    }
                    cache = LinkedHashMap()
                }
            } else {
                cache = LinkedHashMap()
            }
            cleanupTempResidue()
            loaded = true
        }
    }

    /**
     * 原子写盘（持锁调用）：tmp → fsync → rename；rename 失败回退
     * copyTo + delete。失败只留痕不抛（内存为准，下次写盘追平）。
     */
    private fun persistLocked() {
        val target = File(storageDir, FILE_NAME)
        val tmp = File(storageDir, TMP_FILE_NAME)
        try {
            val payload = json.encodeToString(
                TemplateLibrary.serializer(),
                TemplateLibrary(templates = cache.values.toList())
            )
            runCatching { storageDir.mkdirs() }
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            logger("PromptTemplateRegistry: persist failed (${e.message}); in-memory cache stays authoritative")
        } catch (e: IllegalArgumentException) {
            // kotlinx SerializationException 是 IllegalArgumentException 子类
            tmp.delete()
            logger("PromptTemplateRegistry: persist serialize failed (${e.message})")
        }
    }

    /** 清理半写 temp 残留（仅在首载执行，避免与进行中的写盘互踩）。 */
    private fun cleanupTempResidue() {
        val tmp = File(storageDir, TMP_FILE_NAME)
        if (tmp.exists()) {
            val ok = tmp.delete()
            logger("PromptTemplateRegistry: cleaned half-written temp file (deleted=$ok)")
        }
    }

    private companion object {
        const val FILE_NAME = "templates.json"
        const val TMP_FILE_NAME = "templates.json.tmp"
        const val CORRUPT_FILE_NAME = "templates.json.corrupt"
    }
}
