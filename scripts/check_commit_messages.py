#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# 提交信息规范检查（guard-rails · CI: "Commit Message Convention"）
#
# 纪律依据（AGENTS.md）：提交信息格式 `类型(范围): 中文摘要 —— 关键词 / 关键词`
# 例：feat(mcp-host): 逆向 MCP Host —— 手机作为 MCP Server / Token 鉴权
#
# 检查范围：BASE_SHA..HEAD_SHA 的非 merge 提交（merge commit 豁免）。
# 强制项（失败）：`类型(范围): 摘要` 或 `类型: 摘要` 前缀 —— 类型 ∈ 白名单。
# 建议项（警告不失败）：` —— 关键词` 尾段缺失时提示（历史提交兼容，不卡门）。
#
# CI 用法：BASE_SHA / HEAD_SHA 由 workflow 注入（PR = base..head；
# push = before..after，before 为全 0 时只查 HEAD 单提交）。
# 本地用法：python3 scripts/check_commit_messages.py <base> <head>
# 只用标准库；退出码 0 = 通过，1 = 有违规。
# ═══════════════════════════════════════════════════════════════════════════════
import re
import subprocess
import sys
from pathlib import Path

TYPES = {
    "feat", "fix", "refactor", "test", "docs", "chore", "ci", "build",
    "perf", "style", "revert", "release", "security",
}
HEADER = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[A-Za-z0-9#][A-Za-z0-9._/,-]*)\))?"
    r"(?P<bang>!)?:\s+(?P<subject>\S.*)$"
)
KEYWORDS_HINT = "——"


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(Path(__file__).resolve().parent.parent), *args],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise subprocess.CalledProcessError(
            result.returncode,
            result.args,
            output=result.stdout,
            stderr=result.stderr,
        )
    return result.stdout


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: check_commit_messages.py <base_sha> <head_sha> [--first-parent]")
        return 1
    base, head = sys.argv[1], sys.argv[2]
    # push 事件限定第一父链：merge commit 拉入的对侧（第二父）提交属于其
    # 来源分支的既有历史 —— 已由各自 PR 的 base..head 检查覆盖，不属于本次
    # push 的新工作。否则 main 上任一历史不合规直推提交会让此后所有
    # 「merge main 后再 push」的分支永久误伤（push before..after 横跨对侧链）。
    first_parent = "--first-parent" in sys.argv[3:]

    if set(base) == {"0"}:  # 新分支首推：只查 HEAD 单提交
        revs = [head]
    else:
        try:
            # Some CI runs check out only the PR head SHA, so the base commit may not
            # be present locally. In that case, fall back to checking only the HEAD
            # commit instead of crashing with exit status 128.
            git("cat-file", "-e", f"{base}^{{commit}}")
            revs = git(
                "rev-list", "--no-merges",
                *("--first-parent",) if first_parent else (),
                f"{base}..{head}",
            ).split()
        except subprocess.CalledProcessError:
            print(f"⚠ 提交范围 {base}..{head} 无法解析：base SHA 不在当前 checkout 中，回退为仅检查 HEAD 提交")
            revs = [head]

    if not revs:
        print("✓ 提交规范检查通过：范围内没有新增提交")
        return 0

    violations: list[str] = []
    warnings: list[str] = []

    for sha in revs:
        subject = git("log", "-1", "--format=%s", sha).strip()
        m = HEADER.match(subject)
        if not m:
            violations.append(f"{sha[:10]}：`{subject[:60]}` 不符合 `类型(范围): 摘要` 格式")
            continue
        if m.group("type") not in TYPES:
            violations.append(
                f"{sha[:10]}：类型 `{m.group('type')}` 不在白名单 {sorted(TYPES)}"
            )
            continue
        if KEYWORDS_HINT not in subject:
            warnings.append(f"{sha[:10]}：`{subject[:50]}` 缺 `—— 关键词` 尾段（建议补，不卡门）")

    for w in warnings:
        print(f"  ⚠ {w}")
    if violations:
        print(f"✗ 提交规范检查失败（{len(violations)} 项）：\n")
        for v in violations:
            print(f"  - {v}")
        print("\n正确格式：`类型(范围): 中文摘要 —— 关键词 / 关键词`，"
              "类型如 feat/fix/refactor/test/docs/chore/ci/build/perf。")
        return 1

    print(f"✓ 提交规范检查通过：{len(revs)} 个提交全部合规"
          + (f"（{len(warnings)} 条关键词建议）" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
