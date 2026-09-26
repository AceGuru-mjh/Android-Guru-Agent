# 胶囊流式输出系统 — Coding 工作流时间轴

> Coding 模式与 Agent 聊天模式的本质差异：**Coding 工作流不是聊天**。
> 主输出是 **Diff + 终端日志 + 结构化错误** 三件套，Markdown 正文只是
> 结论载体。本系统把 Coding 页的消息流升级为「胶囊时间轴」——每一次
> 工具调用（读/写/改/搜/命令/git/lint/测试）都是一颗带状态的胶囊。

## 一、架构总览（VM 双通道分流）

```
AgentEvent 流（引擎零改动）
   │
   ├─① 副作用通道（既有 reduce，保持不动）
   │    长任务追踪 · 深水区升级观察器 · 会话落盘 · AUTO 错误史归档
   │
   └─② 渲染通道（本系统新增）
        streamSession.onEvent(event)     纯归约 + 置脏（25ms 攒批）
            │
        render ticker（25ms ≤40Hz）
            │
        uiState.stream: CodeStreamSnapshot 不可变快照
            │
        CodeStreamTimeline 渲染（时间轴 + 终端面板 + 详情弹层）
```

渲染与副作用彻底分流：渲染风暴（流式 token、工具输出洪峰）不再冲击
副作用链路；既有功能（长任务/检查点/档位自治）零改动。

## 二、核心组件（core/code-engine/.../code/stream/）

| 文件 | 职责 |
|------|------|
| `CodeStreamModels.kt` | ToolKind 十族 / ToolCallStatus 六态 / StreamToolCall 胶囊视图态 / StreamEntry sealed 时间轴条目 / CodeStreamSnapshot 快照 |
| `CodeStreamSession.kt` | 事件归约状态机：工具全生命周期 / 流式追加 / 验证闭环聚合 / callId 幂等 / 25ms tick |
| `UnifiedDiffParser.kt` | 双格式 diff 归一（git hunk + CodeEditTool mini），行号语义 + 截断/统计 + 容错 |
| `TerminalPulseBuffer.kt` | 四条件脉冲（4KB / 8 行 / 120ms / 显式 flush）+ 16KB 环形尾窗 |
| `CodeStreamCheckpoint.kt` | 扁平持久化：lastEventId + committedFiles + pendingToolCalls + entries（存 diff 原文） |

## 三、UI 组件（app/.../ui/screen/code/stream/）

| 文件 | 职责 |
|------|------|
| `CodeCapsule.kt` | 胶囊本体（族着色图标 + target + 状态徽标 + hunk 进度 + 摘要）+ 「+N 更多」分组 |
| `CodeTerminalPanel.kt` | 终端面板（恒深底 + ANSI 清洗 + 独立锚定 + 可折叠） |
| `CodeDiffView.kt` | hunk 级 Diff（双侧行号 + 绿红底 + 骨架态 + 截断提示） |
| `CodeToolDetailSheet.kt` | 详情弹层分族路由 + 滚动保持（键控 call.id） |
| `CodeStreamScroll.kt` | 双锚定（时间轴与终端各自独立的智能滚动） |
| `CodeStreamCards.kt` | 卡片族：思考链 / 验证轮次（红绿态）/ 结构化错误 / 停止 / 文件 chips / 流式气泡 |
| `CodeStreamTimeline.kt` | 时间轴主列表（同族连续聚合 + 稳定 key + 回底 FAB） |

## 四、关键设计决策

### 4.1 幂等（中断重试安全）

- **callId 即幂等键**：ToolCallStart 重放跳过（已存在即返回）、
  ToolOutputChunk 幂等追加、ToolCallComplete 兜底补建（Start 丢失的
  恢复场景）——整条事件流重放安全；
- **检查点恢复**：未到终态的胶囊恢复为 `PARTIAL`（中断态），由用户
  决定单工具重试（定向指令 + 幂等键语义）或整轮重跑；
- **持久化存 diff 原文**而非 hunk 结构——解析器改进后旧档案自动受益。

### 4.2 脉冲式输出（禁止打字机）

- 终端输出按四条件**整块 flush**（体量/行数/时间窗/显式）——一屏内容
  一次到位，杜绝逐字符重组；
- Diff 按 hunk 级拼装，流式半截 hunk 照常渲染（骨架态兜底）；
- 渲染 25ms ≤40Hz 攒批：tick 无变更返回 null，StateFlow 零更新。

### 4.3 防刷屏 / 防死循环（验证闭环）

- edit→lint/test→再 edit 的闭环会自行延长胶囊链：
  - **验证轮次卡**聚合「一轮改动 + 随后的验证」为可折叠轮次（红绿态
    自动判定），轮内明细默认收起；
  - **同族连续超 3 颗**收进「+N 更多」分组行；
- 错误即 `ErrorEntry` 结构化卡（可恢复标记 + 提示 + 关联胶囊 id），
  禁止 AlertDialog 打断工作流。

### 4.4 双锚定

时间轴与终端面板**各自独立**持有一份 `StreamScrollAnchor`：
- `isAtBottom`（150px 派生）+ 阅读模式（索引递减方向监测）+ 流式
  即时跟随 / 收尾动画；
- 用户在终端里翻历史时，时间轴照常跟随对话推进，互不抢占。

## 五、验收 Checklist

- [x] 工具调用全程胶囊化（读/写/改/搜/命令/git/lint/测试/计划/MCP 十族）
- [x] 胶囊状态机 waiting→running→success/failed/applied/partial
- [x] 编辑类成功显示 hunk 进度（3/7）与 `+N −M` 摘要
- [x] 命令类显示 exit code 与耗时（3.4s）
- [x] BASH 点击 → 终端面板 / 详情全文
- [x] EDIT/WRITE 点击 → hunk 级 Diff（双侧行号）
- [x] GREP 点击 → 命中列表（文件头行高亮）
- [x] 同族连续 >3 收进「+N 更多」，展开态本地保持
- [x] 验证闭环折叠为轮次卡（红绿态）
- [x] 思考链独立卡片（可折叠全文）
- [x] 错误结构化卡片（可恢复标记 + 提示），无 AlertDialog
- [x] 停止卡 + 完成摘要 + 受影响文件 chips
- [x] 终端脉冲整块 flush（四条件），无打字机抖动
- [x] 时间轴 + 终端双锚定，阅读模式互不干扰
- [x] 25ms ≤40Hz 渲染攒批（tick 无变更零更新）
- [x] 中断恢复：检查点落盘 → PARTIAL 态 + lastEventId + committedFiles
- [x] 旧版会话快照兼容（messages → 时间轴降级映射）
- [x] 抽屉导航：Coding 入口在 Agent 正下方（修复缺失项）

## 六、边界用例（测试锁定）

1. **半截 hunk**：流式期间 diff 原文不完整 → 已到行照常渲染，无崩溃；
2. **万行输出**：环形尾窗 16KB 常数内存，面板与详情不撑爆；
3. **事件风暴**：100 个 ToolOutputChunk 不 tick → 终态仍只有一颗胶囊；
4. **重放幂等**：Start/Complete 各重放一次 → 时间轴无重复条目；
5. **轮次嵌套**：edit→test(红)→edit→test(绿) → 两轮次卡，各自红绿态；
6. **坏档隔离**：检查点 JSON 损坏 → .corrupt 隔离 + 空时间轴恢复；
7. **旧档升级**：v1.x 会话快照（无 stream 字段）→ messages 降级映射；
8. **快速切档**：ULTRACODE→STANDARD→APEXCODE 反复切换 → 旋钮基数
   快照保证无指数污染；
9. **未完成恢复**：中断时 running 的 BASH → PARTIAL + pendingToolCalls；
10. **空输入**：空 diff / 空输出 / 空命令 → 骨架与占位文案，无异常。
