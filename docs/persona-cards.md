# 角色卡 / 人设系统 — SillyTavern 导入与世界书（P95）

> 状态：已实施 v1（Task 4-e）· 对比文档差距项 P95（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §4.3/§6.2）
> 前置：apex `AgentRole` 仅手建（6 字段轻量人设，无角色卡导入、无世界书）
> 业界对标：rikkahub `AssistantImporter`（PNG tEXt "[chara:" 提取 + spec 路由 TAVERN_PARSERS + "You are roleplaying as X." 组合）· operit `CharacterCard`/TavernCharacterCard 互通体系

## 一、动机

对比文档 §4.3 的结论：rikkahub 有 SillyTavern PNG/JSON 导入 + 世界书注入；
operit 有 CharacterCard 全资源绑定 + Tavern 双向互通；apex 的 AgentRole 只有
手建 6 字段——**数百万张流通的 SillyTavern 卡一张都进不来**。

落地于纯 JVM 模块 `core/agent-engine` 新包 `com.apex.agent.core.engine.persona`
（4 个 main 文件 1192 行 + 3 个测试文件 1338 行）。**核心纪律：core 零三方依赖**
——rikkahub 用 metadata-extractor 读 PNG tEXt，我们手写 chunk 扫描器。

```
SillyTavern 卡（JSON 文本 / PNG tEXt/zTXt chunk）
        │ CharacterCardParser.parseJson      spec 路由（V1 平面 / V2-V3 嵌套归一）
        │ PngCharacterCardReader              PNG 手写解析（chara/ccv3 → Base64 → JSON）
        ▼
CharacterCardV2（归一内部表示 + Lorebook 宽容序列化）
        │ CharacterCardParser.toPersona      RikkaHub 式组合规则
        ▼
PersonaCard（id/roleDefinition/rolePrompt/firstMessage/lorebook/tags/source）
        │ PersonaRegistry（{id}.json 持久化 / 导入导出 / applyLorebook 触发）
        ▼ app 层拍平
AgentConfig 人设字段（agentName/roleDefinition/rolePrompt——引擎纯字符串消费零改动）
```

## 二、SillyTavern 卡模型（CharacterCardModels.kt，405 行）

### 2.1 V1/V2/V3 归一（CharacterCardV2）

V2（spec=chara_card_v2）与 V3（chara_card_v3）的内容字段嵌套在 `data` 对象下，
V1 是平面结构——两者 JSON 键名一致（snake_case），归一到同一模型 `CharacterCardV2`
（路由见 §三）。字段：spec/specVersion/name/description/personality/scenario/
first_mes/mes_example/system_prompt/post_history_instructions/alternate_greetings/
tags/creator/character_version/extensions/**character_book**。

### 2.2 卡字段 → PersonaCard 映射表（PersonaMapping.DEFAULT，组合契约文档化）

| 卡字段（data.*） | PersonaCard 目标 | 组合规则 |
|------------------|------------------|----------|
| `name` | roleDefinition | 身份行 `"You are roleplaying as {name}."` |
| `description` | roleDefinition | `"## Description"` 分节，原文照录（空段落整体省略） |
| `personality` | roleDefinition | `"## Personality"` 分节（同上） |
| `scenario` | roleDefinition | `"## Scenario"` 分节（同上） |
| `system_prompt` | rolePrompt | rolePrompt 首段 |
| `post_history_instructions` | rolePrompt | 追加在 system_prompt 之后，空行分隔 |
| `first_mes` | firstMessage | 开场白；为空回退首个非空 alternate_greetings |
| `character_book` | lorebook | 世界书原样携带（entries 形状宽容归一） |
| `tags` | tags | 标签原样携带 |

规则表与 `toPersona` 实现由测试锚定（`PersonaMapping DEFAULT documents every
composed card field`）——改组合规则必须同步改表。`PersonaSource` 四来源
（MANUAL/TAVERN_JSON/TAVERN_PNG/IMPORTED）标记导入路径；`mes_example` 当前只
保留字段不注入提示词。

### 2.3 宽容序列化器（防御式解析的载体）

ST 生态的字段形状千奇百怪，两个字段级 `@Serializable(with=)` 序列化器吸收
（避免类级自定义序列化器的自引用递归）：

- `TolerantStringMapSerializer`（extensions）：值有布尔/数字/嵌套对象/数组——
  基本类型取字面内容（false → "false"）、JsonNull → ""、对象/数组 → 其 JSON
  文本（保留可回读形状）；
- `TolerantLorebookSerializer`（character_book）：七条归一规则——entries 对象
  映射（SillyTavern 导出形态，键 "0"/"1"/…）→ 数组、非对象条目丢弃、旧字段
  `key` → `keys`（数组或逗号分隔字符串均接受）、`enabled`/`case_sensitive`
  接受字符串形状（"true"/"1"）、`insertion_order` 接受字符串数字、`position`
  接受数字或字符串（未知值折叠默认 BEFORE_CHAR）、content/extension 拍平字符串。

## 三、解析路由与组合（CharacterCardParser.kt，223 行）

### 3.1 parseJson 路由（RikkaHub TAVERN_PARSERS 策略表的 when 化）

| 输入特征 | 路由 |
|----------|------|
| spec ∈ {chara_card_v2, chara_card_v3}，或无 spec 但根带 data 对象 | 嵌套：内容字段取 data，spec 头取根优先（缺失回退 data） |
| spec ∈ {chara_card_v1, "1.0"}，或无 spec 也无 data | V1 平面：整卡字段在根对象 |
| V2 声明嵌套但 data 缺失/非对象 | **平面回退**（防御：吸收部分导出工具的畸形输出，spec 保真透传） |
| spec 未知且无 data 对象 | UnknownSpec 错误 |
| JSON 解析失败 / 根非对象 / 字段形状损坏 | NotJson 错误 |
| 任何路由解析出的 name 为空 | MissingName（卡无名即无意义，硬错误） |

错误是 **sealed CardParseError**（NotJson/MissingName/UnknownSpec + describe() 人读句），
经 CardParseException 桥折叠进 Result——绝不向上抛。decode 异常按容器有无 name
折叠 MissingName/NotJson（无名卡优先报缺名）。Lenient Json（ignoreUnknownKeys +
coerceInputValues + isLenient）。

### 3.2 toPersona 组合（RikkaHub AssistantImporter 组合逻辑）

- roleDefinition：身份行 + 非空三分节（`## Description` / `## Personality` /
  `## Scenario`）空行相接；全空时只剩身份行（不产生空标题）；
- rolePrompt：system_prompt 与 post_history_instructions 空行相接（皆空 = 空串
  ——AgentConfig 空字段即行为零变化）；
- firstMessage：first_mes 优先，空白回退首个非空 alternate greeting；
- createdAt/updatedAt 取 clock()；source 默认 IMPORTED，由导入入口覆盖为
  TAVERN_JSON / TAVERN_PNG。

## 四、PNG 卡手写解析（PngCharacterCardReader.kt，227 行）

### 为什么不用 metadata-extractor（rikkahub 的选择）

core 层保持零三方依赖（rikkahub 有 lib 而我们手写）。PNG 结构足够简单：
`文件 = 8 字节签名 + N 个 chunk；chunk = 4 字节大端长度 + 4 字节类型 + 数据 +
4 字节 CRC`。tEXt = `"keyword\0text"`（Latin-1）；zTXt = `"keyword\0" + 1 字节
压缩方法(0=zlib) + zlib 数据`。

### 为什么 CRC 不校验（刻意，KDoc 文档化）

校验 CRC 会拒掉一批「数据完整但尾部被截断」的卡——IM 转存重编码、下载中断、
老工具写错 CRC 都常见。签名校验 + chunk 结构边界守卫（长度不许越过缓冲）+
Base64/JSON 形状门控已经足够可靠——坏 CRC 只意味着数据**可能**是坏的，
而后续 CharacterCardParser 还有三层防御兜底。

### 扫描与解码语义

- 守卫：长度 > 2^31-1 停、长度越过缓冲停（容错截断传输，已读 chunk 照常产出）、
  IEND 终止（IEND 后的卡不可见）、IDAT 等数据 chunk 自然跳过、CRC 区截断停止；
- 卡关键词 `chara` / `ccv3`（大小写不敏感，覆盖 Chara 变体）；候选解码三层：
  **裸 JSON 直取 → MIME Base64（容忍换行混入）→ UTF-8 → 以 `{` 开头的 JSON
  形状门控 → `"[chara: <base64>]"` 正则包装回退**（RikkaHub 兼容——其导入器用
  正则从文本里剥包装）；返回首个可解出的卡 JSON，全败 → null（绝不抛）；
- zTXt 解压：java.util.zip.Inflater，非 0 压缩方法跳过，截断流带已解压部分返回
  （容错），**8 MiB 解压上限防炸弹**；
- iTXt 不实现（ST/Chub 生态只用 tEXt 与 zTXt，文档化决策）；
- `extractAllTextChunks` 通用辅助：全量 tEXt/zTXt 的 keyword→text 映射
  （Title/Software 等 PNG 元数据巡检；同关键词保首个）。

## 五、世界书 / Lorebook 关键词触发

| 字段 | 语义 |
|------|------|
| `keys` | 触发关键词列表（旧字段 "key" 与逗号分隔字符串形状也被吸收） |
| `content` | 触发后注入的正文 |
| `enabled` | false = 条目停用（activeEntries 过滤，永不触发） |
| `insertionOrder`（insertion_order） | 多条目同时触发时的注入顺序（升序；Lorebook.activeEntries 稳定排序） |
| `caseSensitive` | 默认 false 大小写不敏感；true 精确匹配 |
| `position` | LorebookPosition 四位置（ST 子集）：BEFORE_CHAR(0)/AFTER_CHAR(1)/BEFORE_AN(2)/AFTER_AN(3)——未知值折叠默认 |

`PersonaRegistry.applyLorebook(persona, recentMessages, scanDepth=4)`：只扫最近
scanDepth 条消息（0/负数 = 空窗口不触发）；条目任一 key 以**包含**语义命中窗口
内任一消息即触发；**空 key 永不触发**（防 contains("") 恒真退化）；返回触发的
content 列表按 insertionOrder 升序。这是 rikkahub RegexInjection 触发的简化版
（useRegex 正则触发留待后续）。

## 六、PersonaRegistry 持久化（PersonaRegistry.kt，337 行）

```
<dir>/                      DI 注入（app: filesDir/personas；测试: 临时目录）
  ├── {id}.json             一人设一文件（PersonaCard JSON）
  ├── {id}.json.tmp         写入中 temp（崩溃残留，delete 一并清理）
  └── {id}.json.corrupt     损坏隔离区（不删除留证据；二次隔离附加时间戳防覆盖）
```

- **原子写**（FileTaskStore 同款）：tmp → flush → `fd.sync()` → renameTo，
  rename 失败回退 copyTo + delete；IO 异常清理 temp 后抛 PersonaIOException；
  Mutex 串行同 id 写路径；Dispatchers.IO；
- **id 纪律**：`[a-z0-9_-]{1,64}`（小写防路径穿越）——save/load/delete 走
  require fail-fast（调用方 bug）；导入入口先校验折叠进 Result（外部输入不许抛）；
- **save**（upsert 整文件替换）：updatedAt 一律盖 clock()，createdAt > 0 保留
  （0 补钟），返回盖章快照；**load/list**：损坏文件就地隔离 + null + logger
  留痕（单个坏文件不拖垮 list），list 按 updatedAt 降序；**delete** 幂等
  （文件与 temp 残留一并清；隔离区是证据不清）；
- **导入**：importTavernJson / importTavernPng（id 校验 → 提取/解析 → toPersona →
  覆盖 source → 落盘折叠 Result）；PNG 无卡数据折叠 PersonaImportException；
- **export**：pretty JSON——人设导出不回写 SillyTavern 卡格式（apex 人设字段是
  投影方向不同的超集，逆向映射无意义）。

## 七、AgentConfig 六人设字段映射表

| AgentConfig 字段 | PersonaCard 来源 | 说明 |
|------------------|------------------|------|
| `agentName` | name | "You are X" 身份行来源 |
| `roleDefinition` | roleDefinition | 这个角色是谁（身份行 + 三分节） |
| `rolePrompt` | rolePrompt | 原样拼入 Agent Role 段（system_prompt + post_history_instructions） |
| `userTitle` | —— 卡内无对应 | 留给用户在人设编辑页补（AgentRole.userTitle 同款） |
| `roleStyle` | —— 卡内无对应 | 同上（style 键） |
| `roleLanguage` | —— 卡内无对应 | 同上（replyLanguage 键） |

firstMessage 不进 AgentConfig——由 app 层作为会话首条 assistant 消息插入
（对应 rikkahub presetMessages 语义）；lorebook 经 applyLorebook 触发后注入
`additionalSystemContext`。引擎保持纯字符串消费、不感知 PersonaCard 模型
（与 AgentRole 相同的模块边界防腐）。

## 八、测试矩阵（84 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| CharacterCardParserTest | 28 | V2 全字段/扩展拍平（布尔→"false"、嵌套对象→JSON 文本）/世界书数组与对象映射两形态/旧字段 key + 字符串形状布尔/坏条目丢弃不崩卡/V1 平面默认值/显式 v1 spec/V3 嵌套/无 spec 带 data 路由/V2 无 data 平面回退/缺名 MissingName/垃圾数组数字字符串全 NotJson/未知 spec UnknownSpec 且带 data 仍解析/toPersona 身份行+三分节顺序/rolePrompt 空行相接/first_mes 优先/备选开场白回退/空分节省略/世界书标签携带+假钟/activeEntries 过滤排序/PersonaMapping.DEFAULT 契约锚定/枚举 order 值 |
| PngCharacterCardReaderTest | 26 | 手写 PNG 构造器（签名+chunk+CRC 占位零——读取器不校验故无需真算）：tEXt chara 提取/zTXt Inflater 解压/非 0 压缩方法跳过/ccv3/Chara 大小写变体/Base64 段内换行容忍/签名破坏 null/数据截断 null 不崩/头截断 null/病态长度停/无文本 chunk null/空与极短输入 null/非卡关键词忽略/坏 Base64 跳过后 chunk 接管/Base64 非 JSON 跳过/裸 JSON 直取/包装回退/IEND 后不可见/首个可解码胜出/多 IDAT 干扰不受影响/extractAllTextChunks 映射与去重 |
| PersonaRegistryTest | 30 | roundtrip 全字段抽样+盖章等值/updatedAt 盖章与 createdAt 保留(0 补钟)/upsert 原子替换/无 temp 残留/缺员 null/非法 id 快失败/损坏隔离（好卡不受影响+二次隔离时间戳防覆盖）/delete 幂等+清残留/list 降序/空库/JSON 导入 happy+垃圾折叠+缺名折叠+非法 id 折叠不写盘/PNG 导入 happy+无卡折叠+非 PNG 折叠/export pretty 回读等值/applyLorebook：关键词触发/大小写不敏感/敏感精确/scanDepth 窗口内外/停用跳过/insertionOrder 排序/多 key 任一触发/空 key+空消息+0 深度+空书全空 |

验证：`:core:agent-engine:test` BUILD SUCCESSFUL——模块 711 tests / 0 failed /
1 skipped（新增 84 个全绿，既有 627 个零回归；skipped 为 RoleRoutingGoldenTest
预存跳过）。迭代记录：首轮 3 失失败全为笔误（隔离后缀双 .json、夹具自带
personality 却断言分节省略、assertThrows 内调 suspend）修正后全绿。门禁：
kotlin_balance.py 7 文件 OK；check_file_size.sh 704 main + 208 test 预算内；
无反射派发/无 printStackTrace。

## 九、app 层接入指南

| 环节 | 落点 |
|------|------|
| AgentRole 兼容 | PersonaCard 激活时 app 层组装 AgentConfig（agentName=name、roleDefinition/rolePrompt 直灌；userTitle/roleStyle/roleLanguage 三字段卡内无对应，编辑页补）走 AgentModule 启动快照 / AgentChatViewModel.patchConfig 热切换 |
| 开场白 | firstMessage 由 app 在会话首条插入 assistant 消息 |
| 世界书 | 发送前 `registry.applyLorebook(persona, recentMessages, scanDepth)` 结果注入 AgentConfig.additionalSystemContext 的 "## World Context" 段 |
| 斜杠命令（建议） | `/persona import <file>`（JSON/PNG 双通道）+ `/persona list/switch`，冲突 id 提示覆盖 |
| DI | `PersonaRegistry(File(context.filesDir, "personas"))` 单例；PNG 原图另存会话背景（RikkaHub 语义）由 app 层做，core 不管 UI 资产 |

## 十、后续路线

- lorebook 的 useRegex 正则触发与 InjectionPosition 全模型（rikkahub 的
  BEFORE/AFTER_SYSTEM_PROMPT、AT_DEPTH+injectDepth）；
- operit 式角色级资源绑定（allowedToolIds/记忆空间——对比文档 §4.3 中期建议）；
- PNG 卡**导出**（当前只读：手写 chunk 解析器可逆写 tEXt，未实现）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/agent-engine/.../engine/persona/CharacterCardModels.kt` | 405 | CharacterCardV2/LorebookPosition/LorebookEntry/Lorebook/PersonaSource/PersonaCard/PersonaMapping + 两个宽容序列化器 |
| `core/agent-engine/.../engine/persona/CharacterCardParser.kt` | 223 | parseJson 路由 + toPersona 组合 + CardParseError/CardParseException |
| `core/agent-engine/.../engine/persona/PngCharacterCardReader.kt` | 227 | PNG chunk 扫描 + tEXt/zTXt 解析 + 三层卡值解码 |
| `core/agent-engine/.../engine/persona/PersonaRegistry.kt` | 337 | 文件式持久化 + 导入导出 + applyLorebook + PersonaIOException/PersonaImportException |
| `core/agent-engine/.../engine/persona/CharacterCardParserTest.kt` | 455 | 28 用例 |
| `core/agent-engine/.../engine/persona/PngCharacterCardReaderTest.kt` | 332 | 26 用例 |
| `core/agent-engine/.../engine/persona/PersonaRegistryTest.kt` | 551 | 30 用例 |
