package com.apex.agent.core.engine.persona

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ═══ 人设注册表（4-e）═══
 *
 * 文件式 PersonaCard 持久化（镜像 task 包 FileTaskStore 的模式），叠加
 * Operit CharacterCardManager 的角色卡入口与 RikkaHub AssistantImporter
 * 的双通道导入（JSON / PNG）：
 *
 * ```
 * <dir>/                      DI 注入（app: filesDir/personas；测试: 临时目录）
 *   ├── serena.json           一人设一文件（PersonaCard JSON）
 *   ├── serena.json.tmp       写入中 temp（崩溃残留，保存前不扫描）
 *   └── serena.json.corrupt   解析失败隔离区（不删除，留证据）
 * ```
 *
 * **原子写**：tmp 写入 → flush → fd.sync()（fsync）→ 同目录 renameTo，
 * rename 失败回退 copyTo + delete（镜像 FileTaskStore 的兜底链）。
 *
 * **id 纪律**：`[a-z0-9_-]{1,64}`（小写数字下划线连字符，防路径穿越）。
 * save/load 走 require fail-fast（调用方 bug 立刻暴露）；导入入口
 * （importTavernJson / importTavernPng）先校验再折叠进 Result（外部输入
 * 不许抛）。
 *
 * **防御式加载**：corrupt 文件 → 隔离成 `.corrupt` + null + logger 留痕，
 * 绝不让单个坏文件拖垮 list()；隔离动作自身失败只留痕。
 *
 * **世界书应用**：[applyLorebook] 是 RikkaHub RegexInjection/lorebook
 * 触发概念的简化版——关键词包含匹配（大小写不敏感可配）+ scanDepth
 * 消息窗口 + insertionOrder 升序。
 *
 * **挂点**：app 层「设置 → Agent 角色」读取 [list]，选中项拍平进
 * AgentConfig 人设 6 字段（agentName=name / roleDefinition / rolePrompt），
 * firstMessage 作为会话开场白插入；导入入口供「分享文件 / 选择 PNG 卡」
 * 菜单与（建议的）斜杠命令 persona import 使用。
 */
class PersonaRegistry(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {}
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 串行化同 id 的写路径（并发导入同一 id 时不交错 tmp）。 */
    private val writeMutex = Mutex()

    init {
        dir.mkdirs()
    }

    // ═══ 写 ═══

    /**
     * 保存（upsert）：一人设一文件，整文件替换。
     *
     * 时间戳纪律：updatedAt 一律重盖为 clock()（真实改动时间）；
     * createdAt 尊重入参（>0 保留——新建时 toPersona 已落过钟），
     * 0 视为未初始化补 clock()。
     *
     * 返回落盘后的最终快照（含盖章后的时间戳）。id 非法抛
     * [IllegalArgumentException]（fail-fast，镜像 FileTaskStore）；IO
     * 失败抛 [PersonaIOException]（temp 已清理不留垃圾）。
     */
    suspend fun save(persona: PersonaCard): PersonaCard = withContext(Dispatchers.IO) {
        val id = validIdOrThrow(persona.id)
        val stamped = persona.copy(
            createdAt = if (persona.createdAt > 0) persona.createdAt else clock(),
            updatedAt = clock()
        )
        val target = fileFor(id)
        val tmp = File(dir, "$id$TMP_SUFFIX")
        writeMutex.withLock {
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(json.encodeToString(PersonaCard.serializer(), stamped).toByteArray())
                    out.flush()
                    // fsync：rename 前数据落盘（进程死亡 → rename 要么发生要么没发生）
                    out.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨设备/目标被锁）→ 复制 + 删除兜底
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: IOException) {
                tmp.delete()
                throw PersonaIOException("save($id) failed: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                // kotlinx SerializationException 是 IllegalArgumentException 子类
                tmp.delete()
                throw PersonaIOException("save($id) serialize failed: ${e.message}", e)
            }
        }
        logger("persona saved: $id (updatedAt=${stamped.updatedAt})")
        stamped
    }

    // ═══ 读 ═══

    /** 按 id 加载；不存在 → null；损坏 → 隔离 + null + 留痕（不抛）。 */
    fun load(id: String): PersonaCard? {
        val file = fileFor(validIdOrThrow(id))
        if (!file.exists()) return null
        return decodeOrQuarantine(file)
    }

    /**
     * 全量列表，updatedAt 降序（最近更新在前，UI 列表序）。
     * 损坏文件就地隔离（quarantine）后跳过——单个坏文件不影响其余。
     */
    fun list(): List<PersonaCard> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        val personas = mutableListOf<PersonaCard>()
        for (file in files.sortedBy { it.name }) {
            decodeOrQuarantine(file)?.let(personas::add)
        }
        return personas.sortedByDescending { it.updatedAt }
    }

    // ═══ 删 ═══

    /**
     * 删除（幂等）：文件与 temp 残留一并清掉；返回「是否真的删掉了东西」。
     * id 非法抛 [IllegalArgumentException]。隔离区（.corrupt）是证据，不清。
     */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val safeId = validIdOrThrow(id)
        val target = fileFor(safeId)
        val tmp = File(dir, "$safeId$TMP_SUFFIX")
        val existed = target.exists() || tmp.exists()
        val targetGone = !target.exists() || target.delete()
        val tmpGone = !tmp.exists() || tmp.delete()
        logger("persona deleted: $safeId (existed=$existed)")
        existed && targetGone && tmpGone
    }

    // ═══ 导入（外部输入，一切失败折叠进 Result）═══

    /**
     * 导入 SillyTavern JSON 卡：parseJson → toPersona → 标记
     * [PersonaSource.TAVERN_JSON] → save。
     *
     * 失败折叠：id 非法 / 解析错误（CardParseException 携带类型化
     * CardParseError）/ 落盘 IO 失败——全部 Result.failure，不抛。
     */
    suspend fun importTavernJson(json: String, id: String): Result<PersonaCard> =
        withContext(Dispatchers.IO) {
            if (!id.matches(ID_PATTERN)) {
                return@withContext Result.failure(
                    IllegalArgumentException("illegal persona id: $id (allowed: [a-z0-9_-]{1,64})")
                )
            }
            val parsed = CharacterCardParser.parseJson(json)
            if (parsed.isFailure) {
                val cause = parsed.exceptionOrNull()
                logger("tavern json import rejected: $id (${cause?.message})")
                return@withContext Result.failure(
                    cause ?: PersonaImportException("card parse failed without cause")
                )
            }
            val persona = CharacterCardParser
                .toPersona(parsed.getOrThrow(), id, clock)
                .copy(source = PersonaSource.TAVERN_JSON)
            saveAndFold(persona, "tavern json")
        }

    /**
     * 导入 SillyTavern PNG 卡：extractCardJson → parseJson → toPersona →
     * 标记 [PersonaSource.TAVERN_PNG] → save。
     *
     * 失败折叠：id 非法 / PNG 无卡数据（无签名 / 无 chara 或 ccv3 chunk /
     * Base64 损坏）→ [PersonaImportException]；解析与落盘失败同 JSON 路径。
     */
    suspend fun importTavernPng(pngBytes: ByteArray, id: String): Result<PersonaCard> =
        withContext(Dispatchers.IO) {
            if (!id.matches(ID_PATTERN)) {
                return@withContext Result.failure(
                    IllegalArgumentException("illegal persona id: $id (allowed: [a-z0-9_-]{1,64})")
                )
            }
            val cardJson = PngCharacterCardReader.extractCardJson(pngBytes)
            if (cardJson == null) {
                logger("tavern png import rejected: no card chunk in $id")
                return@withContext Result.failure(
                    PersonaImportException("no SillyTavern card data found in PNG (keywords: chara/ccv3)")
                )
            }
            val parsed = CharacterCardParser.parseJson(cardJson)
            if (parsed.isFailure) {
                val cause = parsed.exceptionOrNull()
                logger("tavern png import rejected: $id (${cause?.message})")
                return@withContext Result.failure(
                    cause ?: PersonaImportException("card parse failed without cause")
                )
            }
            val persona = CharacterCardParser
                .toPersona(parsed.getOrThrow(), id, clock)
                .copy(source = PersonaSource.TAVERN_PNG)
            saveAndFold(persona, "tavern png")
        }

    // ═══ 导出 ═══

    /**
     * 导出人设为 pretty JSON（分享 / 备份 / 重建用）。人设导出不回写成
     * SillyTavern 卡格式（apex 人设字段是超集方向不同的投影，逆向映射
     * 无意义），导出的是 PersonaCard 自身格式。
     */
    fun export(persona: PersonaCard): String {
        return prettyJson.encodeToString(PersonaCard.serializer(), persona)
    }

    // ═══ 世界书应用 ═══

    /**
     * 应用世界书：返回被触发的条目 content 列表（按 insertionOrder 升序）。
     *
     * 触发规则（RikkaHub lorebook 触发的简化实现）：
     * - 只扫最近 [scanDepth] 条消息（默认 4，0/负数 = 窗口为空 → 不触发）；
     * - 条目任一 key 以「包含」语义命中窗口内任一消息即触发；
     * - 匹配默认大小写不敏感，条目 [LorebookEntry.caseSensitive] 打开精确；
     * - 只看 enabled 条目（[Lorebook.activeEntries]）；
     * - 空 key 永不触发（防 " 包含空串恒真 " 的退化）。
     *
     * app 层把返回的 content 注入 additionalSystemContext（位置先于深加工，
     * 保持 Agent Role 段落语义）。
     */
    fun applyLorebook(
        persona: PersonaCard,
        recentMessages: List<String>,
        scanDepth: Int = 4
    ): List<String> {
        if (recentMessages.isEmpty() || scanDepth <= 0) return emptyList()
        val window = recentMessages.takeLast(scanDepth)
        if (window.isEmpty()) return emptyList()
        return persona.lorebook.activeEntries()
            .filter { entry -> entry.keys.any { key -> key.isNotBlank() && window.any { message -> containsKey(message, key, entry.caseSensitive) } } }
            .map { it.content }
    }

    private fun containsKey(message: String, key: String, caseSensitive: Boolean): Boolean {
        return if (caseSensitive) {
            message.contains(key)
        } else {
            message.lowercase().contains(key.lowercase())
        }
    }

    // ═══ 内部 ═══

    /** 导入路径的落盘折叠：save 抛出的一切异常折叠进 Result。 */
    private suspend fun saveAndFold(persona: PersonaCard, channel: String): Result<PersonaCard> {
        return try {
            Result.success(save(persona))
        } catch (e: Exception) {
            logger("$channel import save failed: ${persona.id} (${e.message})")
            Result.failure(e)
        }
    }

    /** id 校验（fail-fast：save/load/delete 直接抛，导入入口先折叠）。 */
    private fun validIdOrThrow(id: String): String {
        require(id.matches(ID_PATTERN)) { "illegal persona id: $id (allowed: [a-z0-9_-]{1,64})" }
        return id
    }

    /** 防路径穿越：id 受白名单约束后拼文件名。 */
    private fun fileFor(id: String): File = File(dir, "$id$SUFFIX")

    /**
     * 解析单个文件；失败 → 隔离成同名 .corrupt（已存在则附加时间戳防
     * 覆盖）并返回 null。隔离动作自身失败只留痕（不影响其余文件加载）。
     */
    private fun decodeOrQuarantine(file: File): PersonaCard? {
        return try {
            json.decodeFromString(PersonaCard.serializer(), file.readText())
        } catch (e: Exception) {
            logger(
                "persona file corrupt, quarantining: ${file.name} " +
                    "(${e::class.simpleName}: ${e.message})"
            )
            runCatching {
                var quarantine = File(dir, file.name + CORRUPT_SUFFIX)
                if (quarantine.exists()) {
                    quarantine = File(dir, "${file.name}$CORRUPT_SUFFIX.${clock()}")
                }
                file.renameTo(quarantine)
            }.onFailure {
                logger("quarantine move failed: ${file.name}")
            }
            null
        }
    }

    companion object {
        /** 人设 id 白名单：小写字母/数字/下划线/连字符，1-64 位。 */
        val ID_PATTERN = Regex("[a-z0-9_-]{1,64}")

        private const val SUFFIX = ".json"
        private const val TMP_SUFFIX = ".json.tmp"
        private const val CORRUPT_SUFFIX = ".corrupt"
    }
}

/**
 * 持久化 IO 异常（FileTaskStore.TaskStoreIOException 的镜像——包装
 * IOException / 序列化异常，带上下文消息；temp 已在抛出前清理）。
 */
class PersonaIOException(message: String, cause: Exception) : Exception(message, cause)

/**
 * 导入管道的可折叠失败（PNG 无卡数据 / 无 cause 的解析失败）——
 * 只作为 Result.failure 载体，绝不向上抛。
 */
class PersonaImportException(message: String) : Exception(message)
