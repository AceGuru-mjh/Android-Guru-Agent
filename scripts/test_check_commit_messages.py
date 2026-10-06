#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# check_commit_messages.py 的范围解析回归测试
#
# 为什么要它：2026-10-05 事故（docs/ci/guard-rails-commit-range-incident.md）的
# 范围解析逻辑当时写死在 guard-rails.yml 的 shell 块里，既无法在本地复现，也
# 没有测试覆盖 —— 一个已合入 main 的 fix 把门禁变成了「0 提交被检查 + 绿灯」。
# 本文件把七种真实 CI 拓扑固化成用例，任何范围语义回退都会在这里变红。
#
# 用法：python3 scripts/test_check_commit_messages.py
#      （零依赖，仅标准库；CI 的 workflow-yaml 门禁会顺带跑它）
# ═══════════════════════════════════════════════════════════════════════════════
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_commit_messages.py"

BAD_LEGACY = "Fix commit validation for missing base SHAs in CI and make guard-rails resilient"
BAD_TAGS = ("BADLEGACY", "BADBRANCH", "BADSQUASH", "BADNEWBRANCH", "BADLINEAR", "BADDIRECT")


class Sandbox:
    """一次性 git 仓库，构造 CI 里真实出现的提交拓扑。"""

    def __init__(self) -> None:
        self.path = Path(tempfile.mkdtemp(prefix="commit-msg-"))

    def __enter__(self) -> "Sandbox":
        self.g("init", "-q", "-b", "main")
        self.g("config", "user.email", "ci@example.com")
        self.g("config", "user.name", "CI")
        # 与 CI 同构：被检脚本位于被检仓库内（脚本用 __file__ 推导仓库根）。
        (self.path / "scripts").mkdir(exist_ok=True)
        shutil.copyfile(SCRIPT, self.path / "scripts" / SCRIPT.name)
        return self

    @property
    def script(self) -> Path:
        return self.path / "scripts" / SCRIPT.name

    def __exit__(self, *exc) -> None:
        shutil.rmtree(self.path, onerror=lambda f, p, e: None)

    def g(self, *args: str, check: bool = True) -> str:
        r = subprocess.run(
            ["git", "-C", str(self.path), *args],
            capture_output=True, text=True, check=check,
            encoding="utf-8", errors="replace",
        )
        if check and r.returncode != 0:
            raise subprocess.CalledProcessError(r.returncode, args, r.stdout, r.stderr)
        return r.stdout.strip()

    def commit(self, msg: str, fname: str | None = None) -> str:
        if fname:
            (self.path / fname).write_text(msg + "\n", encoding="utf-8")
            self.g("add", fname)
        self.g("commit", "-q", "--allow-empty", "-m", msg)
        return self.g("rev-parse", "HEAD")

    def check(self, *args: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update({"HOME": str(self.path),
                    "PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1"})
        env.pop("GIT_DIR", None)
        env.pop("GIT_WORK_TREE", None)
        return subprocess.run(
            [sys.executable, str(self.script), *args],
            cwd=str(self.path), capture_output=True, text=True,
            encoding="utf-8", errors="replace", env=env,
        )


# ── 期望矩阵 ──────────────────────────────────────────────────────────────────
# (用例名, 期望)  期望 ∈ {"caught", "clean", "vacuous"}
CASES = []


def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


@case("PR 事件：base..head 全量，分支自身违规被抓")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    base = sb.g("rev-parse", "HEAD")
    sb.g("checkout", "-q", "-b", "feat/pr")
    sb.commit("feat(pr): good work", "a.txt")
    sb.commit("BADBRANCH no prefix", "a.txt")
    r = sb.check("--event", "pull_request", "--base", base)
    assert r.returncode == 1, r.stdout
    assert "BADBRANCH" in r.stdout, r.stdout
    return "caught"


@case("分支 merge main 后 push：只看分支独有提交，不误伤 main 历史")
def _(sb: Sandbox):
    bad_legacy = sb.commit(BAD_LEGACY, "a.txt")     # main 上的历史遗留违规提交
    sb.commit("docs(main): baseline", "a.txt")
    sb.commit("ci(main): more main work", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    sb.g("checkout", "-q", "-b", "feat/m", sb.g("rev-parse", "HEAD~1"))
    sb.commit("feat(m): new work", "b.txt")
    before = sb.g("rev-parse", "HEAD")
    sb.commit("BADBRANCH own bad commit", "b.txt")
    sb.g("merge", "-q", "--no-ff", "main", "-m", "merge: sync main into feat/m")
    r = sb.check("--event", "push", "--ref", "feat/m", "--before", before)
    assert r.returncode == 1, r.stdout
    assert "BADBRANCH" in r.stdout, r.stdout
    assert bad_legacy[:8] not in r.stdout.split("✗")[-1], \
        f"main 的历史遗留提交 {bad_legacy[:8]} 不该被卷进分支检查:\n{r.stdout}"
    return "caught"


@case("main push：GitHub merge commit 落地（P1..P2）—— 历史橡皮图章洞已补")
def _(sb: Sandbox):
    bad_legacy = sb.commit(BAD_LEGACY, "a.txt")
    sb.commit("docs(main): baseline", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    sb.g("checkout", "-q", "-b", "feat/m", sb.g("rev-parse", "HEAD~1"))
    sb.commit("feat(m): work to land", "b.txt")
    sb.commit("BADBRANCH bad inside PR", "b.txt")
    sb.g("checkout", "-q", "main")
    before = sb.g("rev-parse", "HEAD")
    sb.g("merge", "-q", "--no-ff", "feat/m", "-m", "Merge pull request #7 from feat/m")
    r = sb.check("--event", "push", "--ref", "main", "--before", before)
    assert r.returncode == 1, f"main 上的 merge commit 落地必须被检查，实际:\n{r.stdout}"
    assert "BADBRANCH" in r.stdout, r.stdout
    assert bad_legacy[:8] not in r.stdout, \
        f"main 历史 {bad_legacy[:8]} 不该被卷进:\n{r.stdout}"
    return "caught"


@case("main push：GitHub squash commit 落地（before..HEAD）")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    sb.commit("ci(main): more", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    sb.g("checkout", "-q", "-b", "feat/s")
    sb.commit("feat(s): to be squashed", "b.txt")
    sb.g("checkout", "-q", "main")
    before = sb.g("rev-parse", "HEAD")
    sb.g("merge", "-q", "--squash", "feat/s", check=False)
    sb.g("commit", "-q", "-m", "BADSQUASH squash with no prefix")
    r = sb.check("--event", "push", "--ref", "main", "--before", before)
    assert r.returncode == 1, r.stdout
    assert "BADSQUASH" in r.stdout, r.stdout
    return "caught"


@case("新分支首推（before 全 0）：只查 HEAD，但违规仍被抓")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    sb.g("checkout", "-q", "-b", "feat/n", "HEAD~0")
    sb.commit("feat(n): first push", "c.txt")
    sb.commit("BADNEWBRANCH bad on new branch", "c.txt")
    r = sb.check("--event", "push", "--ref", "feat/n",
                 "--before", "0" * 40)
    assert r.returncode == 1, r.stdout
    assert "BADNEWBRANCH" in r.stdout, r.stdout
    return "caught"


@case("分支线性 push（无 merge）：before..HEAD 正常受检")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    before = sb.g("rev-parse", "HEAD")
    sb.g("checkout", "-q", "-b", "feat/l", "HEAD")
    sb.commit("feat(l): linear one", "d.txt")
    sb.commit("BADLINEAR linear bad", "d.txt")
    r = sb.check("--event", "push", "--ref", "feat/l", "--before", before)
    assert r.returncode == 1, r.stdout
    assert "BADLINEAR" in r.stdout, r.stdout
    return "caught"


@case("直推 main（非 merge）：before..HEAD 正常受检")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    before = sb.g("rev-parse", "HEAD")
    sb.commit("BADDIRECT bad pushed straight to main", "a.txt")
    r = sb.check("--event", "push", "--ref", "main", "--before", before)
    assert r.returncode == 1, r.stdout
    assert "BADDIRECT" in r.stdout, r.stdout
    return "caught"


@case("全部合规 → exit 0")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    sb.g("update-ref", "refs/remotes/origin/main", "main")
    sb.g("checkout", "-q", "-b", "feat/ok")
    sb.commit("feat(ok): 全部合规 —— 关键词 / 关键词", "a.txt")
    r = sb.check("--event", "push", "--ref", "feat/ok", "--before", "HEAD~1")
    assert r.returncode == 0, r.stdout
    return "clean"


@case("旧形态 <base> <head> 仍可用（向后兼容）")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    before = sb.g("rev-parse", "HEAD")
    sb.commit("BADLEGACY legacy call bad commit", "a.txt")
    r = sb.check(before, "HEAD")
    assert r.returncode == 1, r.stdout
    assert "BADLEGACY" in r.stdout, r.stdout
    return "caught"


@case("无法判定时报错退出 2（绝不把「查不了」当「通过」）")
def _(sb: Sandbox):
    sb.commit("docs(main): baseline", "a.txt")
    r = sb.check("--event", "pull_request", "--base", "deadbeef" * 5)
    assert r.returncode == 2, r.stdout
    assert "✗" in r.stdout, r.stdout
    return "clean"


def run() -> int:
    if hasattr(sys.stdout, "reconfigure"):   # 用例含中文，Windows 默认 GBK 会炸
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print("范围解析回归测试 —— check_commit_messages.py\n")
    failed = 0
    for name, fn in CASES:
        with Sandbox() as sb:
            try:
                outcome = fn(sb)
                print(f"  PASS  [{outcome:<8}] {name}")
            except AssertionError as exc:
                failed += 1
                print(f"  FAIL  {name}\n        {exc}")
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"  ERROR {name}\n        {type(exc).__name__}: {exc}")
    print()
    print(f"{len(CASES) - failed}/{len(CASES)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run())
