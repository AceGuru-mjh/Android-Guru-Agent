#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# 提交信息规范检查（guard-rails · CI: "Commit Message Convention"）
#
# 纪律依据（AGENTS.md）：提交信息格式 `类型(范围): 中文摘要 —— 关键词`
# 例：feat(mcp-host): 逆向 MCP Host —— 手机作为 MCP Server / Token 鉴权
#
# 强制项（失败）：`类型(范围): 摘要` 或 `类型: 摘要` 前缀 —— 类型 ∈ 白名单。
# 建议项（警告不失败）：` —— 关键词` 尾段缺失时提示（历史提交兼容，不卡门）。
#
# ── 为什么范围解析在本脚本内 ────────────────────────────────────────────────
# 事故（2026-10-05，docs/ci/guard-rails-commit-range-incident.md）：范围解析
# 原先写死在 guard-rails.yml 的 `run: |` shell 块里，既无法单测、也无法本地
# 复现，且逻辑本身有洞（见 resolve_revs）。现全部下沉到此脚本 —— workflow 只
# 负责传事件参数，判定逻辑全部由 scripts/test_check_commit_messages.py 覆盖。
#
# ── 两种调用形态 ────────────────────────────────────────────────────────────
# 1) CI（推荐，可测）：
#      check_commit_messages.py --event pull_request --base <sha> [--head <sha>]
#      check_commit_messages.py --event push --ref <branch> [--before <sha>]
#                               [--target main] [--upstream origin/main]
# 2) 本地/回退（保留旧的显式范围形态，向后兼容）：
#      check_commit_messages.py <base_sha> <head_sha> [--first-parent]
#
# 退出码：0 = 通过，1 = 有违规提交，2 = 调用错误 / 无法解析（绝不静默放行）。
# ═══════════════════════════════════════════════════════════════════════════════
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):   # 提交信息含中文，Windows 控制台默认 GBK 会炸
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO_ROOT = Path(__file__).resolve().parent.parent

TYPES = {
    "feat", "fix", "refactor", "test", "docs", "chore", "ci", "build",
    "perf", "style", "revert", "release", "security",
}
HEADER = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[A-Za-z0-9#][A-Za-z0-9._/,-]*)\))?"
    r"(?P<bang>!)?:\s+(?P<subject>\S.*)$"
)
KEYWORDS_HINT = "——"

ZERO_SHA = "0" * 40


class ResolutionError(Exception):
    """范围无法确定 —— 必须让调用方看到「查不了」，而不是伪装成「通过」。"""


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise subprocess.CalledProcessError(
            result.returncode,
            result.args,
            output=result.stdout,
            stderr=result.stderr,
        )
    return result.stdout


def git_ok(*args: str) -> str | None:
    """成功返回 stdout（可能为空字符串），失败返回 None。

    注意：判定「是否存在」必须用 `is None`，不能用真值 —— `cat-file -e` 成功时
    stdout 为空串，空串是假值，`if not git_ok(...)` 会把「存在」读成「不存在」。
    """
    try:
        return git(*args).strip()
    except subprocess.CalledProcessError:
        return None


def commit_exists(rev: str) -> bool:
    return git_ok("cat-file", "-e", f"{rev}^{{commit}}") is not None


def is_merge_commit(rev: str) -> bool:
    return len(git_ok("rev-list", "--no-walk", "--parents", "-1", rev).split()) > 2


def parents_of(rev: str) -> list[str]:
    return git_ok("rev-list", "--no-walk", "--parents", "-1", rev).split()[1:]


def _rev_list(base: str, head: str, *, first_parent: bool) -> list[str]:
    """`git rev-list --no-merges [范围]`；range 无法解析时抛 ResolutionError。"""
    args = ["rev-list", "--no-merges", *(("--first-parent",) if first_parent else ()),
            f"{base}..{head}"]
    try:
        return git(*args).split()
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "").strip().splitlines()
        raise ResolutionError(
            f"提交范围 {base}..{head} 无法解析"
            + (f"（{detail[-1]}）" if detail else "")
        ) from exc


def resolve_revs(event: str, *, base: str | None, before: str | None,
                 ref: str | None, head: str, target: str,
                 upstream: str) -> tuple[list[str], str]:
    """把一次 CI 事件翻译成「要检查哪些提交」。

    返回 (revs, 范围说明)。抛 ResolutionError 表示无法判定 —— 调用方必须
    显式失败，绝不能把「查不了」降级成「通过」。
    """
    if event == "pull_request":
        # PR 语义：base..head 全量，即该 PR 的全部提交（merge-base 由 GitHub
        # 在 base.sha 上给出，无需我们再校正）。
        if not base:
            raise ResolutionError("pull_request 事件缺少 --base")
        return _rev_list(base, head, first_parent=False), f"PR {base}..{head}"

    # ── push 事件 ────────────────────────────────────────────────────────────
    if not before:
        before = ZERO_SHA

    if ref == target:
        # 推到目标分支（main）自身。
        #
        # 历史洞（2026-10-05 事故后实测）：GitHub 把 PR 以 merge commit 落到
        # main，而本脚本豁免 merge commit —— 于是 `before..HEAD --no-merges`
        # 为空集，打印「✓ 范围内没有新增提交」并 exit 0。main 上 237 条第一父
        # 提交几乎全是 merge commit，等于这条门禁在 main 上是绿的橡皮图章 ——
        # 而 main 恰恰是唯一无法靠 PR 事件兜底的地方。
        #
        # 正确语义：merge commit M(P1=目标分支原顶, P2=被合入的分支) 真正
        # 落地的新工作 = P1..P2。取该范围，既补上了 main 的盲区，又不会把
        # P1 的历史（main 既有提交，含历史遗留不合规项）卷进来。
        if is_merge_commit(head):
            ps = parents_of(head)
            if len(ps) >= 2:
                return _rev_list(ps[0], ps[1], first_parent=False), \
                    f"main merge commit 落地范围 {ps[0][:10]}..{ps[1][:10]}"
        # 非 merge（squash 合入 / 直推）：语义就是 before..HEAD。
        if set(before) == {"0"}:
            return [head], f"新分支首推，仅查 HEAD {head[:10]}"
        return _rev_list(before, head, first_parent=False), f"main push {before[:10]}..{head[:10]}"

    # 分支 push：用与上游的 merge-base 限定「分支独有提交」。
    #
    # 为什么不用 before..after：分支把 main merge 进来后再 push 时，
    # before..after 会横跨对侧（第二父）历史，把 main 上任一历史遗留的不合规
    # 提交圈进本次检查 —— 分支自身提交是否合规与此无关，却整车误伤。
    # merge-base 把它收敛成「本分支自分叉以来的提交」。
    if git_ok("rev-parse", "--verify", "--quiet", upstream):
        mb = git_ok("merge-base", head, upstream)
        if mb:
            return _rev_list(mb, head, first_parent=True), \
                f"分支 push merge-base 范围 {mb[:10]}..{head[:10]}"
    if set(before) == {"0"}:
        return [head], f"新分支首推（无 {upstream} 可参照），仅查 HEAD {head[:10]}"
    return _rev_list(before, head, first_parent=True), \
        f"分支 push {before[:10]}..{head[:10]}（无 {upstream} 可参照，未做 merge-base 收敛）"


def report(revs: list[str], scope: str) -> int:
    if not revs:
        print(f"✓ 提交规范检查通过：{scope} 内没有需要检查的提交（merge commit 豁免）")
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
        print(f"✗ 提交规范检查失败（{len(violations)} 项）—— 范围：{scope}\n")
        for v in violations:
            print(f"  - {v}")
        print("\n正确格式：`类型(范围): 中文摘要 —— 关键词 / 关键词`，"
              "类型如 feat/fix/refactor/test/docs/chore/ci/build/perf。")
        return 1

    print(f"✓ 提交规范检查通过：{scope} 内 {len(revs)} 个提交全部合规"
          + (f"（{len(warnings)} 条关键词建议）" if warnings else ""))
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        prog="check_commit_messages.py",
        description="提交信息规范检查：`类型(范围): 摘要 —— 关键词`",
    )
    ap.add_argument("legacy", nargs="*", metavar="SHA",
                    help="[旧形态] <base_sha> <head_sha>，与 --first-parent 同用")
    ap.add_argument("--first-parent", action="store_true",
                    help="[旧形态] 范围限定第一父链")
    ap.add_argument("--event", choices=["push", "pull_request"],
                    help="CI 事件类型；给定则走事件化范围解析")
    ap.add_argument("--base", help="pull_request 事件的 base.sha")
    ap.add_argument("--before", help="push 事件的 before.sha")
    ap.add_argument("--head", default="HEAD", help="待检提交，默认 HEAD")
    ap.add_argument("--ref", help="push 事件的目标分支名")
    ap.add_argument("--target", default="main",
                    help="push 事件的目标（受保护）分支名，默认 main")
    ap.add_argument("--upstream", default="origin/main",
                    help="分支 push 时用于 merge-base 收敛的上游引用")
    args = ap.parse_args(argv)

    if args.event is None:
        # ── 旧形态（向后兼容，本地 / 回退路径）───────────────────────────────
        if len(args.legacy) < 2:
            ap.error("需要 <base_sha> <head_sha>，或使用 --event push|pull_request")
        base, head = args.legacy[0], args.legacy[1]
        if args.legacy[2:]:
            ap.error(f"多余的位置参数：{args.legacy[2:]}")
        if not commit_exists(head):
            print(f"✗ 无法解析提交 {head}：不在当前 checkout 中，无法校验")
            return 2
        if set(base) == {"0"}:
            revs = [head]
            scope = f"新分支首推，仅查 HEAD {head[:10]}"
        elif not commit_exists(base):
            print(f"✗ 无法解析 base {base}：不在当前 checkout 中，无法校验 —— "
                  "请拉全历史后重试（不要把「查不了」当成「通过」）")
            return 2
        else:
            revs = _rev_list(base, head, first_parent=args.first_parent)
            scope = f"{base[:10]}..{head[:10]}" + ("（第一父链）" if args.first_parent else "")
        return report(revs, scope)

    # ── 事件化形态 ────────────────────────────────────────────────────────────
    if args.legacy:
        ap.error("--event 形态不接受位置参数")
    try:
        if not commit_exists(args.head):
            raise ResolutionError(f"无法解析 head {args.head}：不在当前 checkout 中")
        revs, scope = resolve_revs(
            args.event,
            base=args.base,
            before=args.before,
            ref=args.ref,
            head=args.head,
            target=args.target,
            upstream=args.upstream,
        )
    except ResolutionError as exc:
        print(f"✗ {exc}")
        return 2
    except subprocess.CalledProcessError as exc:
        print(f"✗ git 命令失败：{(exc.stderr or exc.stdout or '').strip()}")
        return 2
    return report(revs, scope)


if __name__ == "__main__":
    sys.exit(main())
