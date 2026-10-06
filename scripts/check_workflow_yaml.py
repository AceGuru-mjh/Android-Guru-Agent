#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# Workflow YAML 自检（quality-gate · CI: "Workflow YAML Gate"）
#
# ── 事故背景（2026-10-05，docs/ci/guard-rails-commit-range-incident.md）────────
# `guard-rails.yml` 的 `run: |` 字面块里有一行注释丢了 2 个行首空格，YAML 在该
# 处截断 —— GitHub 对「workflow 文件自身无法解析」的处理是**静默禁用整个
# workflow**：不报 YAML 错误、不跑任何 job，只在 run 摘要页写一句 "likely failed
# because of a workflow file issue"。四联守护栏（i18n 镜像 / 市场目录 / 秘密扫描
# / 提交规范）整体停摆，且**没有任何门禁发现**。
#
# ── 为什么这道门禁必须住在一个独立文件里 ─────────────────────────────────────
# GitHub 拒绝的是「整个文件」。把自检 job 放进 guard-rails.yml 自己，在结构上就
# 不可能拦住 guard-rails.yml 损坏 —— 它会跟着一起消失。所以本脚本由
# **quality-gate.yml** 承载（独立文件，触发条件与 guard-rails.yml 一致）。
#
# ── 为什么用严格 YAML 解析，而不是「行首缩进」启发式 ─────────────────────────
# 试过按行首缩进猜测字面块是否截断，实测不可行：块**正常**结束时下一行同样是
# 顶格/浅缩进，缩进启发式无法区分二者，必然大面积误报。误报的门禁会被维护者
# 加白或关掉 —— 那比没有门禁更糟。因此这里只做严格 YAML 解析 + 结构校验：
# 事故那种破损**必然**解析失败，精确命中且零误报。
#
# 检查项：
#   1. .github/workflows/*.yml|*.yaml 全部可被 YAML 解析（失败带行号）
#   2. 每个文件有 name / on / 非空 jobs
#   3. 每个 job 有 runs-on / 非空 steps
#   4. 每个 step 的 run 解析为字符串、uses 解析为字符串（截断的块会变成映射）
#
# 依赖：PyYAML（GitHub ubuntu-latest 自带；缺失时自动安装，装不上则**硬失败**
#       —— 绝不静默降级成「看起来检查过了」）。
# 退出码：0 = 通过，1 = 有问题，2 = 依赖不可用（未实际检查）。
# ═══════════════════════════════════════════════════════════════════════════════
import glob
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"


def ensure_yaml():
    try:
        import yaml  # type: ignore
        return yaml, f"PyYAML {yaml.__version__}"
    except ImportError:
        pass
    print("… PyYAML 缺失，尝试安装")
    r = subprocess.run(
        [sys.executable, "-m", "pip", "install", "--quiet",
         "--disable-pip-version-check", "pyyaml"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    try:
        import yaml  # type: ignore
        return yaml, f"PyYAML {yaml.__version__}（本次自动安装）"
    except ImportError:
        print("✗ 无法获得 PyYAML，workflow 自检**未执行**")
        if r.stderr.strip():
            print(f"  pip: {r.stderr.strip()[-400:]}")
        return None, ""


def check_step(step, rel: str, where: str, problems: list[str]) -> None:
    if not isinstance(step, dict):
        problems.append(f"{rel}: {where} 不是映射（YAML 结构被截断的典型症状）")
        return
    if "run" in step and not isinstance(step["run"], str):
        problems.append(
            f"{rel}: {where} 的 `run` 解析为 {type(step['run']).__name__} 而非字符串"
            f" —— `run: |` 字面块很可能被截断")
    if "uses" in step and not isinstance(step["uses"], str):
        problems.append(f"{rel}: {where} 的 `uses` 不是字符串")
    if "run" not in step and "uses" not in step:
        problems.append(f"{rel}: {where} 既没有 `uses` 也没有 `run`")


def main() -> int:
    yaml, engine = ensure_yaml()
    if yaml is None:
        return 2

    files = sorted(
        Path(p) for p in (glob.glob(str(WORKFLOWS / "*.yml"))
                          + glob.glob(str(WORKFLOWS / "*.yaml")))
    )
    if not files:
        print(f"✗ {WORKFLOWS} 下没有找到 workflow 文件")
        return 1

    problems: list[str] = []
    for path in files:
        rel = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8")

        # ── 1. 严格解析（事故那次就是这里失败的）─────────────────────────────
        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError as exc:
            detail = str(exc).replace("\r", "")
            problems.append(f"{rel}: YAML 解析失败\n{detail}")
            continue

        if not isinstance(doc, dict):
            problems.append(f"{rel}: 顶层不是映射（得到 {type(doc).__name__}）")
            continue

        # ── 2. 顶层必备键 ──────────────────────────────────────────────────
        if not doc.get("name"):
            problems.append(f"{rel}: 缺少顶层 `name`")
        # YAML 1.1 会把裸 `on` 解析成布尔 True
        if doc.get("on", doc.get(True)) is None:
            problems.append(f"{rel}: 缺少顶层 `on`（workflow 不会在任何事件上触发）")

        jobs = doc.get("jobs")
        if not isinstance(jobs, dict) or not jobs:
            problems.append(f"{rel}: 缺少非空 `jobs`")
            continue

        # ── 3/4. job 与 step 结构 ──────────────────────────────────────────
        for job_id, job in jobs.items():
            where = f"job `{job_id}`"
            if not isinstance(job, dict):
                problems.append(f"{rel}: {where} 不是映射")
                continue
            if "runs-on" not in job:
                problems.append(f"{rel}: {where} 缺少 `runs-on`")
            steps = job.get("steps")
            if not isinstance(steps, list) or not steps:
                problems.append(f"{rel}: {where} 缺少非空 `steps`")
                continue
            for idx, step in enumerate(steps):
                check_step(step, rel, f"{where} step #{idx + 1}", problems)

    if problems:
        for p in problems:
            print(f"  ✗ {p}")
        print(f"\n✗ Workflow 自检失败（{len(problems)} 项）")
        print("  注意：GitHub 不报告 workflow YAML 语法错误，只**静默禁用**该")
        print("  workflow —— 所以这道门禁必须住在另一个 workflow 文件里，否则")
        print("  被检文件一坏，门禁跟着一起消失（本仓库 2026-10-05 就是这么停摆的）。")
        return 1

    print(f"✓ Workflow 自检通过：{len(files)} 个文件解析与结构均合法（{engine}）")
    for path in files:
        print(f"    OK  {path.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
