# 事故报告：Commit Message Convention 误伤与 guard-rails.yml 瘫痪

> 日期：2026-10-05 · 状态：修复中（修复分支 `fix/guard-rails-yaml-recovery`）
> 影响面：push 侧 CI 误红（11+ 笔历史运行）+ main 的 Guard Rails 四联检查瘫痪

## 1. 时间线与证据

### 1.1 上半场：误伤（检查范围错误）

| 运行 ID | 时间 (UTC) | 分支 | 失败 job | 实际原因 |
|---|---|---|---|---|
| `37262432150` | 04:11 | `fix/comprehensive-reliability-sweep` | Commit Message Convention | main 历史提交被圈入 |
| `37268919124` | 05:41 | `fix/coding-toolname-400-resilience` | 同上 | 同上 |
| `37268977717` | 05:42 | `feat/agent-reply-cloudy-glass` | 同上 | 同上 |
| `37268982282` | 05:42 | `feat/expert-brand-icons-drawer-templates` | 同上 | 同上 |
| `37284509508` | 08:34 | **main** | 同上 | 同上 |
| `37286167686` | 08:50 | `feat/agent-reply-cloudy-glass` | 同上 | 同上 |
| `37286172536` | 08:50 | `feat/expert-brand-icons-drawer-templates` | 同上 | 同上 |
| `37286175496` | 08:50 | `fix/coding-toolname-400-resilience` | 同上 | 同上 |
| `37286178709` | 08:50 | `fix/issue-sweep-2-about-polish` | 同上 | 同上 |
| `37291332108` | 09:38 | `fix/terminal-engine-lifecycle-snapshot-parity` | 同上 | 同上 |

**根因链**：
1. main 上存在一条英文提交 `379c1bbd`（"Fix commit validation for missing base SHAs in CI…"）
   —— 由维护者直推，未经 push 侧检查（或当时检查尚未拦截该类）。
2. 旧版 push 侧检查用 `PUSH_BEFORE..HEAD` 作为范围。当分支 merge 了 main（或
   before..after 横跨对侧历史）时，`379c1bbd` 被圈进校验范围 → 判红。
3. 这把**分支自己的工作**与**main 的历史遗留**混在同一次检查里 —— 正确性错误，
   不是那些分支提交不规范。

**取证日志**（run `37291332108`）：
```
range: 3f9ebaad..09d0ea5c
✗ 提交规范检查失败（1 项）：
  - 379c1bbda3：`Fix commit validation for missing base SHAs in CI and make g` 不符合 `类型(范围): 摘要` 格式
```
—— 唯一条违规是 main 的历史提交，分支自身提交全部合规。

### 1.2 下半场：瘫痪（workflow 文件损坏）

| 运行 ID | 时间 | 分支 | 现象 |
|---|---|---|---|
| `37308294288` | 12:15 | `fix/guard-rails-push-first-parent` (PR #325) | run 报 "workflow file issue" |
| `37309631600` | 12:27 | **main**（`5fe7cca9` 合并后） | 同上 —— **main 的 Guard Rails 从此瘫痪** |

**根因**：PR #325 的合并冲突消解时，`guard-rails.yml` 的 `run: |` 字面块内
一行注释丢失 2 空格缩进（顶到行首）：
```yaml
          if [ -z "$BASE" ]; then BASE=$(git rev-parse HEAD~1); fi
# push 事件范围校正（main 侧 ca61b1c3）：...     ← 缺行首缩进，超出 run: | 块
```
YAML 解析器认为 `run:` 块在此截断，随后以顶层标量出现 → 整个 workflow
无法解析。GitHub 的处理是**该 workflow 静默失效**：不报 YAML 错误、不运行
任何 job，只在 run 摘要页写一句 "likely failed because of a workflow file
issue"。四联检查（i18n / 市场目录 / 秘密扫描 / 提交规范）全部停摆。

## 2. 修复（本分支）

| # | 修复 | 落点 |
|---|------|------|
| 1 | 缩进回补（唯一实际差异） | `.github/workflows/guard-rails.yml` |
| 2 | **Workflow YAML Self-Check** 新 job：PyYAML 严格解析 `.github/workflows/*.yml`，解析失败带行号阻断 | 同上（第五个 job） |
| 3 | **豁免名单**：`379c1bbd` 标记为历史事实，直跑脚本/异常范围下不再整车误伤（仅此一条，不豁免新提交） | `scripts/check_commit_messages.py` |
| 4 | push 侧 `--first-parent` + merge-base（来自 PR #325，本次随缩进修复一起重新落地） | workflow + 脚本（上一轮已实现） |

## 3. 防护矩阵（修复后）

| 场景 | 旧行为 | 新行为 |
|------|--------|--------|
| 分支 merge main 后 push | `before..after` 圈入对侧历史 → 可能红 | merge-base 校正 + `--first-parent` → 只看分支独有提交 |
| main 自身 push | `before..after` 全查 | 语义保持（main 的提交都该合规） |
| 脚本被直跑（本地/回退命令） | `379c1bbd` 再次判红 | 豁免名单放行 + 打印豁免行 |
| workflow YAML 损坏 | **静默瘫痪**，无人发现 | `workflow-yaml-selfcheck` job 阻断（任何 PR/push 都跑） |

## 4. 豁免名单的纪律

- 只允许**已合并的历史事实**进入；禁止把"来不及修的提交"塞进来；
- 每项必须附理由指向本报告；
- 新增项需要在 PR 里显式说明（review 时留意 diff）。
- 该名单是**兜底**：正常路径（merge-base + first-parent）根本不看 main 历史，
  豁免只防直跑/回退路径的复现。**不豁免任何新提交**（新提交一律按规范检查）。

## 5. 未受影响 / 已澄清

- 无未合并 PR 因本事故被卡（当前 open PR 的 Commit Message Convention 均非失败态）；
- 受损均为 **push 侧运行记录**与 **main workflow 可用性**，无代码丢失；
- `379c1bbd` 的历史无法改写（main 已推送），也**不需要**改写 —— 防护矩阵已
  使其在正常路径不可见。
