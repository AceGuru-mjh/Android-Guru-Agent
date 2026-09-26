# 提示词模板库 — 10 内置模板 + 渲染流水线（P92）

> 状态：已实施 v1（Task 4-b）· 对比文档差距项 P92（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §4.5/§6.2）
> 前置：[prompt-variables.md](prompt-variables.md)（P91 变量引擎，渲染流水线的下半段）
> 业界对标：operit `prompt/config` 内置指令库（角色 + 硬约束 + 输出格式三段式）· rikkahub Pebble 模板 + 助手模板 · apex 自家 `ModePresets` 模式

## 一、动机

对比文档 §4.5 的结论：operit 的 FunctionalPrompts/SystemPromptConfig 提供开箱即用的
「角色 + 硬性约束 + 输出格式」三段式内置指令；rikkahub 有 Pebble 模板与助手模板；
apex 此前模板系统为零（对比文档点名 ModePresets 是可复刻骨架）。

落地选择：**不引入 Pebble 等模板引擎**（外部依赖 + 模板语言复杂度），而是复用 P91
的变量展开器做渲染内核——模板 = 存储的正文 + 声明的变量表 + 三层合并的值。
三件套位于 `core/agent-engine` 纯 JVM 包 `com.apex.agent.core.engine.templates`：

```
PromptTemplateRegistry（模板 CRUD / templates.json 持久化 / 导入导出 / 内置播种）
        ↓ get(id)
PromptTemplate（声明 variables + content 含 {{var}} 与 {{var|default}}）
        ↓ 变量值合并（三层优先级，注入 context.custom）
PromptVariableExpander（P91：展开/递归/转义/默认值/环安全）
        ↓
RenderResult（ok / text / missingRequired / template）
```

## 二、数据模型（PromptTemplateModels.kt，390 行）

### 2.1 模板模型

| 字段 | 类型/约定 | 说明 |
|------|-----------|------|
| id | `[a-z0-9_-]{1,64}`（`TEMPLATE_ID_PATTERN`，防路径穿越与超长） | 内置以 `builtin_` 前缀；用户模板 `newId()` = `tpl_${时间戳}_${1000..9999 随机}` |
| name / description | 非空白（save 校验 fail-fast） | 展示名/用途说明 |
| category | TemplateCategory 六枚举 | CODING / WRITING / ANALYSIS / TRANSLATION / PRODUCTIVITY / CUSTOM |
| content | 非空白 | 模板正文，可含 `{{var}}` 与 `{{var|default}}` |
| variables | List<TemplateVariable> | 声明变量（name/description/required/defaultValue）——渲染表单 + 必填校验 |
| tags / isBuiltIn / createdAt / updatedAt / usageCount | — | 检索标签 / 内置锁 / 时间戳（save 盖 updatedAt）/ 渲染命中计数 |

- `referencedVariables()`：扫描 content 实际引用的变量名（规范小写集合、转义不计）——
  引擎与测试用它校验「声明变量与正文引用一致」（词法与 P91 展开器同源）；
- `TemplateVariable.required = true` 渲染时缺失判失败；`defaultValue` 非空 =
  overrides 缺位时的取值；
- `TemplateLibrary`：持久化信封（templates + schemaVersion=1）；
- 全字段带默认值 + `ignoreUnknownKeys`——旧 JSON 缺字段无损加载、未来字段不炸。

### 2.2 十个内置模板清单（BuiltinPromptTemplates，中文正文）

| id | 名称 | 类别 | 声明变量（required 加 *） |
|----|------|------|---------------------------|
| `builtin_code_review` | 代码审查 | CODING | code* / language / focus |
| `builtin_commit_message` | 提交信息生成 | PRODUCTIVITY | changes* / style |
| `builtin_translate` | 专业翻译 | TRANSLATION | text* / target_language* / domain |
| `builtin_summarize` | 内容总结 | ANALYSIS | content* / perspective / length |
| `builtin_refactor_plan` | 重构方案 | CODING | code* / goal* / constraints |
| `builtin_bug_report` | BUG 报告 | CODING | symptom* / reproduce* / expected* / actual* / environment |
| `builtin_api_design` | API 设计 | CODING | requirement* / style |
| `builtin_test_cases` | 测试用例 | CODING | code* / framework |
| `builtin_tech_doc` | 技术文档 | WRITING | topic* / audience / code |
| `builtin_regex_explain` | 正则解释 | ANALYSIS | pattern* / sample |

覆盖 CODING×5 / PRODUCTIVITY / TRANSLATION / ANALYSIS×2 / WRITING 五类；每套
声明变量与正文引用严格一致（`referencedVariables()` 断言）。三段式哲学源自 operit：
每套正文 = 角色定位（「你是一位资深代码评审专家」）+ 硬性约束（编号要求清单）+
输出格式（结构化章节）。

## 三、持久化（PromptTemplateRegistry.kt，373 行）

```
<storageDir>/                DI 注入（App: filesDir/templates；测试: 临时目录）
  ├── templates.json         整库单文件（TemplateLibrary 信封，encodeDefaults）
  ├── templates.json.tmp     写入中 temp（崩溃残留，首载清理）
  └── templates.json.corrupt 损坏备份（加载失败时隔离）
```

- **原子写**（FileTaskStore 同款）：tmp 写入 → flush → `fd.sync()`（fsync）→ 同目录
  rename；rename 失败回退 copyTo + delete。写失败只留痕不抛（内存为准，下次写盘追平）；
- **防御式读**：损坏 JSON → 备份到 `templates.json.corrupt` + 空库启动 + logger 留痕，
  绝不抛（一条坏文件不该让模板功能瘫痪）；加载时逐条校验（非法条目跳过留痕）；
- **并发模型**（对齐 LongTaskStore）：@Volatile 不可变快照缓存（LinkedHashMap 保
  插入序）+ 写 Mutex 串行「读缓存 → 改 → 发布 → 写盘」四步原子 + 惰性双检加载；
- **CRUD**：save（upsert by id；id/name/content 校验 fail-fast；updatedAt 一律盖时钟戳，
  createdAt 新建缺省补戳）；delete（内置拒绝返回 false）；list(category)（插入序 =
  内置播种序 + 后续保存序）；get（非法 id 返回 null 不抛）。

### 导入导出（MERGE / REPLACE）

| 模式 | 语义 |
|------|------|
| MERGE | incoming 覆盖同 id 的**自定义**模板，其余保留 |
| REPLACE | 清空后整库替换为 incoming（内置模板随之移除——逃生通道：`reseedBuiltIns` 还原出厂） |

```
importLibrary(payload, mode)
   │ 坏 JSON ──────────────► TemplateImportResult(ok=false, error)   防御式不抛
   ▼
 逐条分流：
   ├── id ∈ BUILTIN_IDS ──► 丢弃 + skippedBuiltIn++（双模式一律，单一事实源）
   ├── 校验失败 ──────────► 跳过 + skippedInvalid++
   └── 其余 ─────────────► 落库（isBuiltIn=false 强制自定义 + 时间戳补齐）
                            + imported++
   REPLACE：先清空再分流；MERGE：直接覆盖同 id 自定义
   ▼
 真实变更（REPLACE 或 imported>0）→ persistLocked + onChanged
```

### 损坏隔离生命周期

```
templates.json 解析失败
   │ logger 留痕（异常类名 + 消息）
   ├── 已有 templates.json.corrupt → 先删
   ├── renameTo(templates.json.corrupt)（隔离自身失败只留痕）
   └── 空库启动（cache = {}）—— 一条坏文件不瘫痪模板功能
首次加载时：cleanupTempResidue() 清掉半写 .tmp（仅首载，避免与进行中写盘互踩）
```

- `exportLibrary()`：整库 pretty JSON（分享/备份，含内置模板）；
- 导入防御式（坏 JSON → `TemplateImportResult(ok=false, error)` 不抛）：已知内置 id 的
  条目在**两种模式下都丢弃**（内置模板仅由 reseedBuiltIns 单一事实源管理——杜绝导入
  伪造内置条目）；其余条目一律 `isBuiltIn=false` 落为自定义；非法条目（id 不合规/
  名称或正文空白）跳过 + skippedInvalidCount 计数留痕；
- `reseedBuiltIns(force)`：幂等播种（缺则补）；force=true 把已存在的内置条目整体
  还原为出厂定义（内容/变量/使用计数全重置，救「被改坏」）；
- `incrementUsage`：渲染计数独立通道——只动 usageCount，**不碰 updatedAt**（渲染不是
  内容修改）；`onChanged` 回调仅在真实变更后触发。

## 四、渲染引擎（PromptTemplateEngine.kt，107 行）

### 变量值合并优先级（高 → 低）

```
1. overrides            本次渲染的显式传参（表单输入 / /template:<id> 斜杠参数）
2. 模板 variables[].defaultValue（非空默认值）
3. 提示词变量注册表解析（内置 19 变量 + 自定义 + context.custom）
4. 缺失 —— required 变量进 RenderResult.missingRequired，ok=false
```

前两层经「enriched context」注入：`context.copy(custom = context.custom + declaredDefaults
+ overrides)`——**复用 P91 注册表的既有优先级链，不另起解析路径**（context.custom 天然
压过注册表自定义，这就是「设计取舍：custom 必须最高」的原因）。未声明的 `{{var}}`
同样能被第 3 层解析（内置变量直通，如正文直接写 `{{model_id}}`）。

- `render(templateId, overrides, context)`：模板不存在 → ok=false + template=null；
  缺必填 → ok=false + missingRequired 清单（text 为尽力展开的部分结果，缺位占位符按
  展开器策略保留）；渲染命中即 `incrementUsage`；
- `renderRaw(content, overrides, context)`：裸文本渲染（输入框「预览展开」/临时拼装），
  overrides 直接注入展开上下文，无 usageCount 副作用。

## 五、测试矩阵（43 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| PromptTemplateRegistryTest | 24 | roundtrip 字段抽样、原子写 tmp/rename 路径、损坏 JSON → .corrupt 隔离 + 空库启动、temp 残留首载清理、save 校验（非法 id/空名/空正文抛）、updatedAt 盖戳与 createdAt 保留、delete 内置拒绝/自定义成功、reseedBuiltIns 幂等 + force 还原（使用计数归零）、import MERGE 覆盖/REPLACE 清空/内置 id 双模式丢弃/非法跳过计数/坏 JSON ok=false、exportLibrary pretty 结构、incrementUsage 不碰 updatedAt、onChanged 仅真实变更触发 |
| PromptTemplateEngineTest | 19 | 三层合并优先级逐层断言（overrides 压默认值压注册表）、未声明变量经内置解析直通、missingRequired 清单（按声明顺序）、部分渲染结果、模板不存在 ok=false、renderRaw 无计数副作用、渲染命中 usageCount 自增、默认值注入后正文 `{{var\|default}}` 与声明 defaultValue 并存语义 |

验证：`:core:agent-engine:test` BUILD SUCCESSFUL——模块 546 tests / 0 failed / 1 skipped
（与 P91 同批 97 个新增中的 43 个；既有零回归）。迭代记录：首轮 2 个编译错
（assertThrows lambda 非协程体 → 手写 assertIllegalArgument suspend 帮助函数），
次轮 2 个断言笔误（sampleTemplate 携带 createdAt/usageCount 与期望冲突 → 显式
copy 归零），修正后全绿。

## 六、与 `/template:<id>` 斜杠命令的未来接线（对比文档 §6.2 P92）

core 层已就绪、app 层待接线（对比文档 P92 路线）：

| 环节 | 设计 |
|------|------|
| 斜杠命令 | SlashCommand 加 Template 子类型（`/template:<id> [k=v ...]`）+ Router 分支 |
| 参数解析 | `k=v` 透传成 overrides（k 规范化小写）；无参数 → 缺必填时提示补全 |
| 渲染调用 | `engine.render(id, overrides, context)` → ok 时作为消息正文发送 |
| 快捷卡片 | 聊天页模板快捷入口消费 `registry.list(category)`（六类分组） |
| 持久化迁移 | AgentSettings 加 promptTemplates/templates 字段改走注册表存储 dir |

## 七、设计取舍与常见问题

**Q: 为什么导入对内置 id 一律丢弃而不是覆盖？**
内置模板仅由 `reseedBuiltIns` 单一事实源管理。若允许导入覆盖内置条目，用户间传递
的「魔改内置模板」会悄悄改变所有人的基线——防伪造保护（isBuiltIn 规范化：只有已知
内置 id 可持有删除保护，普通 id 强制 false）与导入丢弃是同一红线的两端。REPLACE 后
想找回内置？`reseedBuiltIns()` 是逃生通道。

**Q: 单文件 templates.json 会不会成为瓶颈？**
模板量级在百条以内（10 内置 + 用户自建），整文件重写 + fsync 的成本可忽略；
换 SQLite/分文件只会在损坏隔离与原子写上引入更多状态机——KISS 优先。

**Q: usageCount 与 updatedAt 为什么分通道？**
排序/统计口径（「最常用模板」）与内容修改时间（diff/同步）是两本账——混在一起会让
每次渲染都把模板顶到「最近修改」列表首位。

## 八、后续路线

- app 层接线（§六）与设置页模板管理 UI（CRUD + 导入导出 + reseed 入口）；
- 分类检索（tags 全文匹配）与 usageCount 排序视图；
- 模板分享：导出单模板子集（当前 exportLibrary 是整库）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/agent-engine/.../engine/templates/PromptTemplateModels.kt` | 390 | TemplateCategory/TemplateVariable/PromptTemplate/TemplateLibrary/BuiltinPromptTemplates（10 套） |
| `core/agent-engine/.../engine/templates/PromptTemplateRegistry.kt` | 373 | 持久化 + CRUD + 导入导出 + reseed + incrementUsage |
| `core/agent-engine/.../engine/templates/PromptTemplateEngine.kt` | 107 | 三层合并渲染 + RenderResult + renderRaw |
| `core/agent-engine/.../engine/templates/PromptTemplateRegistryTest.kt` | 484 | 24 用例 |
| `core/agent-engine/.../engine/templates/PromptTemplateEngineTest.kt` | 313 | 19 用例 |
