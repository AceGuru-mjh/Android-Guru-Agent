#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# mcp_catalog 市场目录完整性检查（guard-rails · CI: "Market Catalog Integrity"）
#
# 纪律依据：assets/mcp_catalog/*.json 是离线精选目录，
# 运行时由 McpServerCatalog.parseCategoryFile 解析 + validateEntries 校验。
# 坏数据（字段缺失/枚举越界/id 冲突）会直接破坏市场的浏览与安装流 ——
# 本脚本把运行时校验前移到 CI，坏目录永远到不了 APK。
#
# Hub v2（2026-10）：随包精选目录已整体迁往官方 MCP 仓库
# （AceGuru-mjh/apex-mcp-hub），APK 不再打包 —— 目录缺失/为空是合法的
# 退役态（通过）；一旦未来重新随包分发或作为热更 fixture 回归，
# 下列完整性与 v1 相同的条目校验立即重新生效。
#
# 检查项（与 McpServerCatalog.validateEntries 的九条规则对齐）：
#   1. JSON 可解析 + 顶层结构（categories → entries 数组）合法
#   2. id：格式（^[a-z0-9][a-z0-9-]{1,48}$）+ 跨文件全局唯一
#   3. 必填字段：name 非空 / descriptionZh 非空（中文目录是硬要求）
#   4. transport ∈ {stdio, http, sse}；stdio 必有 command、远端必有 url
#   5. runtime ∈ {node, python, java, docker, remote}；python 必须 sandboxOnly
#   6. tier ∈ {agent, coding, all}；risk ∈ {low, medium, high}
#   7. envSchema 键不重复
#
# 只用标准库；退出码 0 = 通过，1 = 有坏条目。
# ═════════════════════════════════════════════════════════════════════════
import json
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/assets/mcp_catalog"
ID_REGEX = re.compile(r"^[a-z0-9][a-z0-9-]{1,48}$")
TRANSPORTS = {"STDIO", "HTTP", "SSE"}  # JSON 存 McpTransport 枚举名（大写）
RUNTIMES = {"node", "python", "java", "docker", "remote"}
TIERS = {"agent", "coding", "all"}
RISKS = {"low", "medium", "high"}

def check_entry(entry: dict, file: str, idx: int) -> list[str]:
    where = f"{file}[{idx}]"
    issues: list[str] = []
    def bad(msg: str):
        issues.append(f"{where}：{msg}")

    eid = entry.get("id", "")
    if not isinstance(eid, str) or not ID_REGEX.match(eid):
        bad(f"id 非法：{eid!r}（小写字母/数字/连字符，2-49 位）")
    if not str(entry.get("name", "")).strip():
        bad("name 为空")
    if not str(entry.get("descriptionZh", "")).strip():
        bad("descriptionZh 为空（中文目录是硬要求）")

    transport = entry.get("transport")
    if transport not in TRANSPORTS:
        bad(f"transport 非法：{transport!r}（目录不收录 BUILTIN）")
    else:
        if transport == "STDIO" and not str(entry.get("command") or "").strip():
            bad("STDIO 缺 command")
        if transport != "STDIO" and not str(entry.get("url") or "").strip():
            bad("远端缺 url")

    runtime = entry.get("runtime")
    if runtime not in RUNTIMES:
        bad(f"runtime 非法：{runtime!r}")
    if runtime == "python" and not entry.get("sandboxOnly"):
        bad("python 运行时必须 sandboxOnly=true")

    if entry.get("tier") not in TIERS:
        bad(f"tier 非法：{entry.get('tier')!r}")
    if entry.get("risk") not in RISKS:
        bad(f"risk 非法：{entry.get('risk')!r}")

    env = entry.get("envSchema") or []
    keys = [e.get("key") for e in env if isinstance(e, dict)]
    if len(keys) != len(set(keys)):
        bad("envSchema 键重复")
    return issues

def main() -> int:
    if not CATALOG.is_dir():
        # Hub v2：随包目录已退役（迁官方 MCP 仓库），缺失即合法
        print("✓ mcp_catalog 已退役（Hub v2 迁官方 MCP 仓库）：目录缺失，跳过")
        return 0

    problems: list[str] = []
    seen_ids: dict[str, str] = {}
    files = sorted(CATALOG.glob("*.json"))
    if not files:
        # Hub v2：空目录同为合法退役态
        print("✓ mcp_catalog 已退役（Hub v2 迁官方 MCP 仓库）：零分类文件，跳过")
        return 0

    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            problems.append(f"{path.name}：JSON 解析失败 —— {e}")
            continue

        entries = data.get("entries") if isinstance(data, dict) else None
        if not isinstance(entries, list):
            problems.append(f"{path.name}：顶层结构应为 {{\"entries\": [...]}}")
            continue
        if not entries:
            problems.append(f"{path.name}：entries 为空（空分类文件应删除而非保留）")
            continue

        for i, entry in enumerate(entries):
            if not isinstance(entry, dict):
                problems.append(f"{path.name}[{i}]：条目不是对象")
                continue
            problems.extend(check_entry(entry, path.name, i))
            eid = entry.get("id")
            if isinstance(eid, str) and eid:
                if eid in seen_ids:
                    problems.append(
                        f"{path.name}：id `{eid}` 与 {seen_ids[eid]} 跨文件重复"
                    )
                else:
                    seen_ids[eid] = path.name

    if problems:
        print(f"✗ mcp_catalog 完整性检查失败（{len(problems)} 项）：\n")
        for p in problems:
            print(f"  - {p}")
        return 1

    total = sum(
        len(json.loads(p.read_text(encoding="utf-8")).get("entries", []))
        for p in files
    )
    print(f"✓ mcp_catalog 完整性通过：{len(files)} 个分类 / {total} 条目，id 全局唯一，契约全合规")
    return 0

if __name__ == "__main__":
    sys.exit(main())
