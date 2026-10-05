# 事故报告：guard-rails.yml 静默瘫痪 + 提交规范门禁在 main 上的橡皮图章

> 日期：2026-10-05 · 状态：已修复（本 PR）
> 影响面：main 的 Guard Rails 四联检查整体停摆（自 12:27 UTC 起）；提交规范门禁在 main 上形同虚设

## 0. 一句话结论

PR #325 合并时 `guard-rails.yml` 一行注释丢了行首缩进 → YAML 截断 → GitHub **静默禁用整个
workflow**；而 #325 想修的「提交规范误伤」在修完之后，门禁在 main 上已经变成**检查 0 个提交
却报绿灯**的橡皮图章。两者叠加：守护栏既坏了，又坏得没人看得出来。

---

## 1. 第一段事故：workflow 文件静默失效（当前 live）

### 1.1 现象

| 运行 ID | 时间 (UTC) | ref | 表现 |
|---|---|---|---|
| `37308294288` | 12:15 | `fix/guard-rails-push-first-parent` | `This run likely failed because of a workflow file issue.` |
| `37309631600` | 12:27 | **main**（`5fe7cca9`） | 同上 |

两个 run **都没有任何 job**——不是 job 失败，是文件被整体拒绝。

### 1.2 取证

`git show 5fe7cca9:.github/workflows/guard-rails.yml` 用 PyYAML 解析：

```
yaml.parser.ParserError: while parsing a block mapping
  in ".github/workflows/guard-rails.yml", line 70, column 9
expected <block end>, but found '<scalar>'
  in ".github/workflows/guard-rails.yml", line 82, column 11
```

同一 checkout 下其余 6 个 workflow（apk / ci / pr-labeler / quality-gate / release / rootfs）
全部解析通过 —— 损坏范围精确到一行。

### 1.3 根因

PR #325 的合并冲突消解时，`run: |` 字面块内一行注释顶到行首：

```diff
           if [ -z "$BASE" ]; then BASE=$(git rev-parse HEAD~1); fi
-          # push 事件范围校正：分支合并 main 后，PUSH_BEFORE..HEAD 会把 main 的
+# push 事件范围校正（main 侧 ca61b1c3）：分支合并 main 后，PUSH_BEFORE..HEAD
           # 会把 main 的历史提交圈进校验范围（…
```

YAML 字面块在顶格行处结束，紧随其后的缩进行被当作新的映射层级，解析失败。

### 1.4 为什么没人发现

GitHub 对「workflow 文件无法解析」的处理是**静默禁用**：不报 YAML 错误、不跑 job，只在 run
摘要页写一句 workflow file issue。于是 i18n 字符串镜像、市场目录契约、**秘密扫描**、提交规范
——四联守护栏**同时停摆**，而 CI 面板上只看到 `Quality Gate` / `CI` 还在绿。

> 秘密扫描停摆这一条最值得记：公开仓库的凭证裸奔是 P0 事故，而当时唯一的防线已经离线约 1 小时。

---

## 2. 第二段事故：门禁修完之后，在 main 上是绿的空转

上一段（10+ 笔 push 运行误红）的成因是：main 上有一条直推的历史遗留不合规提交
`379c1bbd`（`Fix commit validation for missing base SHAs in CI…`，无 `类型(范围):` 前缀，
且位于 main 的**第一父链**上），而 push 侧用 `before..after` 范围，分支把 main merge 进来后
再 push 时该提交被圈进校验 → 整车误伤。

PR #325 用 `--first-parent` + merge-base 收敛范围，方向是对的。但它漏掉了 main 自身的 push 路径。

### 2.1 复现（实测，非推演）

拓扑：main（含历史遗留违规 `379c1bbd`）← GitHub merge commit ← 分支 `feat/m`
（内含一条真正违规的提交）。用 main 上的脚本 + PR #325 的调用方式：

```
cmd   : check_commit_messages.py <before> HEAD --first-parent     # push to main
exit  : 0                                        ← CI 绿
out   : ✓ 提交规范检查通过：范围内没有新增提交
```

**检查了 0 个提交**，而分支里那条 `BADBRANCH bare subject no prefix` 就这么落到了 main 上。

### 2.2 根因

两条规则叠加：

1. 脚本用 `--no-merges` 豁免 merge commit（`Merge pull request #N from …` 不是 `类型:` 前缀，
   检查它必然永久红）；
2. GitHub 把 PR 以 **merge commit** 落到 main。

于是 push 到 main 时 `before..HEAD` 区间里**只有**那个 merge commit，被规则 1 豁免 → 空集 →
打印「范围内没有新增提交」→ exit 0。

main 的第一父链共 237 条提交（总提交 796 条），几乎全是 merge commit。**也就是说这条门禁在
main 上从来没有真正工作过** —— 而 main 恰恰是唯一无法靠 `pull_request` 事件兜底的地方
（`379c1bbd` 就是直推进去的，没有任何 PR 事件覆盖它）。

### 2.3 与 PR #325 的关系

#325 让**分支** push 不再误伤（实测有效，本 PR 回归用例 C1 覆盖）。但它给 push 事件统一挂了
`--first-parent`，同时没有为 main 的 merge-commit 落地路径补范围 —— 于是把「误报」换成了
main 上的「漏报」。**误伤变漏报不是修复。**

---

## 3. 修复

| # | 修复 | 落点 |
|---|------|------|
| 1 | **缩进回补** —— main 的 Guard Rails 恢复运行 | `guard-rails.yml` |
| 2 | **范围解析下沉到脚本** + 补 main merge-commit 落地路径（`P1..P2`） | `scripts/check_commit_messages.py` |
| 3 | **Workflow YAML 门禁放进独立文件**（`quality-gate.yml`） | `scripts/check_workflow_yaml.py` + `quality-gate.yml` |
| 4 | **回归用例**固化 10 种 CI 拓扑 | `scripts/test_check_commit_messages.py` |

### 3.1 为什么 YAML 门禁必须换一个文件住

GitHub 拒绝的是**整个文件**。把自检 job 放进 `guard-rails.yml` 自己，在结构上就**不可能**
拦住 `guard-rails.yml` 损坏 —— 它会跟着一起消失。事故报告里若写「自检 job 会阻断 YAML 损坏」，
那是**结构上不成立**的承诺。`quality-gate.yml` 是独立文件、触发条件与 `guard-rails.yml`
一致，隔离这才有意义。

### 3.2 为什么用严格解析，而不是「行首缩进」启发式

先尝试过按行首缩进猜测字面块是否截断，**实测不可行**：字面块**正常**结束时下一行同样是
顶格/浅缩进，缩进启发式无法区分二者，对 7 个 workflow 产生 96 条误报。误报的门禁会被
维护者加白或关掉 —— 那比没有门禁更糟。因此只保留严格 YAML 解析 + 结构校验：事故那种破损
**必然**解析失败，精确命中且零误报（7 条负向用例覆盖）。

### 3.3 main merge commit 的范围语义

merge commit `M(P1=目标分支原顶, P2=被合入的分支)` 真正落地的新工作是 `P1..P2`：

- 补上 main 的盲区（分支自身提交被检查）；
- 不把 `P1` 的历史（main 既有提交，含 `379c1bbd` 这类历史遗留项）卷进来。

`squash` 合入与直推走 `before..HEAD`；分支 push 走 `merge-base(HEAD, origin/main)..HEAD` + 第一父链。
三者都写成了可单测的用例。

### 3.4 不采用 SHA 豁免名单

另一种思路是把 `379c1bbd` 硬编码进豁免表。本 PR 不用：范围语义修对之后，它在所有正常路径
里**本来就不可见**（它是每个 main 顶端的祖先），不需要靠名单遮。豁免名单是掩盖而非修复，
还会随历史推移变成一串无人敢动的 SHA。

---

## 4. 验证

| 项 | 结果 |
|---|---|
| 10 种 CI 拓扑回归用例 | ✅ 10/10（PR / 分支 merge main / main merge / main squash / 新分支首推 / 线性 push / 直推 main / 全合规 / 旧形态兼容 / 无法判定 exit 2） |
| 新旧对照（同拓扑） | ✅ 旧脚本 `exit 0` +「0 个新增提交」；新脚本 `exit 1` 并指名违规提交 |
| YAML 门禁 · 修复后树 | ✅ 7 文件通过 |
| YAML 门禁 · 对 origin/main（损坏） | ✅ `exit 1`，精确报出 line 70 / line 82 |
| YAML 门禁负向用例 | ✅ 7/7（缺 `on` / 缺 `runs-on` / 缩进丢失 / 空 steps / 空 jobs / `run` 变映射 / 健康对照） |

复现命令：

```bash
python3 scripts/check_workflow_yaml.py          # 本地自检
python3 scripts/test_check_commit_messages.py   # 范围解析回归
```

---

## 5. 纪律

- **改 workflow 文件后必须本地跑 `check_workflow_yaml.py`**，别等 CI 告诉你「workflow file issue」。
- **任何守护某个文件的门禁，不能住在那个文件里。** 这是本次事故唯一的结构性教训。
- **门禁不能误报到没法用。** 宁可少查一项，也别让维护者养成忽略红灯的习惯。
- 合并冲突消解 `run:` / `with:` / `env:` 等 YAML 字面块时，**逐行核对行首缩进** —— 丢空格是
  这类块最常见的手滑，且失败形态是「静默」而非「报错」。
