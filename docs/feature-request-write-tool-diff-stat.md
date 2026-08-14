# 产品需求：write / replace 工具原生返回 diff 统计

- **类型**：CodeBuddy 客户端（IDE）工具能力增强
- **提出方**：Agent 使用者反馈
- **优先级**：P2（体验增强，非阻塞）
- **关联**：与 `read_file` 的 `offset/limit/行号` 能力配套，补齐"写"一侧的可观测性

---

## 1. 背景与问题

当前 Agent 在编辑代码时主要使用两个工具：

- `write_to_file`：整文件覆盖写入
- `replace_in_file`：精确字符串替换

**问题**：两个工具执行成功后，**仅返回成功/失败状态**，不回显任何变更度量。用户（以及 Agent 自身）无法从工具返回直接得知：

- 本次操作**新增了多少行**
- **删除了多少行**
- **净变更行数**是多少
- 修改落在文件的**哪一段（行区间）**

这导致：

1. **用户不可见**：在流式输出或工具调用面板里，用户看不到"到底改了多少"，只能事后去 diff 视图核对。
2. **Agent 需额外回读**：Agent 为了向用户报告变更规模，必须再发一次 `read_file` 回读改动区来人工计算 diff，浪费一轮工具调用 + context 占用。
3. **与 read 能力不对等**：`read_file` 已支持 `offset/limit` + 行号定位，读侧可观测性完整；写侧缺失，体验割裂。

---

## 2. 需求描述

### 2.1 `replace_in_file` 返回体增强

在执行成功后，返回结构应额外包含：

```json
{
  "status": "success",
  "filePath": "app/src/main/kotlin/.../AgentChatScreen.kt",
  "diffStat": {
    "addedLines": 4,
    "deletedLines": 0,
    "netChange": 4
  },
  "changedRange": {
    "startLine": 71,
    "endLine": 74
  },
  "matchCount": 1
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `addedLines` | 新增行数（`new_str` 行数 − 被替换的 `old_str` 行数，取非负增量视角） |
| `deletedLines` | 删除行数 |
| `netChange` | `addedLines − deletedLines` 净变更 |
| `changedRange.startLine` | 变更起始行（基于替换点定位） |
| `changedRange.endLine` | 变更结束行 |
| `matchCount` | 实际命中替换次数（与 `replace_all` 对应，便于校验"是否唯一致换"） |

### 2.2 `write_to_file` 返回体增强

整文件写入时，若文件已存在，应提供**与旧版本的行数对比**：

```json
{
  "status": "success",
  "filePath": "...",
  "diffStat": {
    "addedLines": 120,
    "deletedLines": 95,
    "netChange": 25
  },
  "oldLineCount": 95,
  "newLineCount": 120,
  "isNewFile": false
}
```

新文件场景下 `oldLineCount = 0`、`isNewFile = true`。

---

## 3. 验收标准

- [ ] `replace_in_file` 成功返回包含 `diffStat`（added/deleted/net）与 `changedRange`
- [ ] `write_to_file` 成功返回包含 `diffStat` 与新旧行数对比
- [ ] 行数计算以 `\n` 换行计，与 `read_file` 行号体系一致（1-based）
- [ ] `replace_all=true` 时 `matchCount` 反映真实命中数，且 `diffStat` 为累计值
- [ ] 失败场景（old_str 不唯一 / 文件不存在）返回结构不变，仅 `status=failed` + 原因，不影响既有错误流

---

## 4. 设计约束

- **向后兼容**：新增字段为可选扩展，旧消费方（仅读 `status`）不受影响。
- **零额外 IO**：diff 计算应在写入时顺带完成（已有新旧内容 bytes），不引入额外文件读取。
- **与 UI 协同**：工具调用卡片（AgentChatScreen 的 ToolCall/RunningToolCall 区域）可消费 `diffStat` 直接渲染"+/− N 行"徽标，无需 Agent 回读。

---

## 5. 收益

1. 用户实时看到"改了多少行"，降低认知负担。
2. Agent 不再为报告变更规模而额外 `read_file` 回读，省一轮工具调用 + context。
3. 与 `read_file` 的行号/分段能力形成对称的读写可观测性。

---

## 6. 参考实现示意（伪代码）

```kotlin
// replace_in_file 执行后
val oldLines = oldStr.lines().size
val newLines = newStr.lines().size
val added = maxOf(0, newLines - oldLines)
val deleted = maxOf(0, oldLines - newLines)

return ReplaceResult(
    status = SUCCESS,
    filePath = filePath,
    diffStat = DiffStat(added, deleted, added - deleted),
    changedRange = locateRange(oldStr, fileText),
    matchCount = matchCount
)
```
