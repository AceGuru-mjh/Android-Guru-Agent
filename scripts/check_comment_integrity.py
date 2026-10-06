#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════════
# check_comment_integrity.py — Kotlin 注释完整性门禁（词法感知）
# ═══════════════════════════════════════════════════════════════════════════════
# 背景（2026-10-06，main 直推 401ac034 事故）：
#   MoreCapabilitiesTools.kt 的 KDoc 里写了 MIME 通配符 "*/*" —— 字节序列 `*/`
#   提前终止了块注释，注释后半段变成顶层垃圾 token，`core:tool-registry` 编译
#   失败（"Expecting a top level declaration"），main 与 PR #330 双红。
#   修复第一版又踩了另一半坑：把示例写成 "image/*" —— `/*` 在注释体内会**开
#   嵌套层**（Kotlin 块注释可嵌套），外层注释直到 EOF 都没闭合。
#
#   本类 bug 的特征：括号/花括号完全平衡（kotlin_balance.py 检不出）、提交信
#   息合规（guard-rails 检不出）、只有编译能抓 —— 而直推 main 的编译是落地后
#   才跑的。本门禁把「注释既不提前终止也不悬垂嵌套」前移到 <1s 的结构检查。
#
# 两条规则（对 core/ + app/ 全部 .kt，含 src/main 与 src/test）：
#   R1 premature-close —— 注释体行（`^\s*\*` 的注释续行）上出现把嵌套深度收
#      到 0 的 `*/` 且行尾还有非空白内容 → 注释被 `*/` 截断。行内注释
#      `code /* x */ code` 的收合不在注释体行上，不误报；平衡嵌套
#      （`（`/* 未实现 */`）`）的 `*/` 收的是内层深度，不误报；合法收合行
#      ` */` 行尾无内容，不误报。（全仓库无 ` */ 尾部代码` 形态，规则安全。）
#   R2 unclosed —— 块注释打开后到文件尾没收平（含悬垂嵌套层），按打开行报
#      告。K1 词法器对未闭合注释报 "Unclosed comment"，这里给出更早、带行号
#      的诊断。
#
# 词法状态机（与 kotlin_balance.py 同源思路，模板栈 + 花括号深度版）：
#   code / str / raw / tmpl 四态；块注释状态优先于一切（注释内容不参与
#   引号/模板判定，反之字符串/模板里的 `/*` `*/` 也不开注释）；`${...` 开
#   模板（栈存外层 mode+花括号深度），模板内 `{}` 计深度（lambda/嵌套块），
#   模板内可再开普通/raw 串；普通串不跨行（对齐 K1 行尾恢复）。
#
# 用法: python3 scripts/check_comment_integrity.py [file.kt ...]   # 缺省扫全仓库
# 退出码: 0 通过 / 1 发现问题（带文件:行号与修复指引）

import sys
import pathlib

GLOB_PATTERNS = ("core/**/*.kt", "app/**/*.kt")


def scan_file(path: str) -> list[tuple[int, str, str]]:
    """返回 [(line, rule, detail)]。"""
    try:
        src = open(path, encoding="utf-8").read()
    except (FileNotFoundError, UnicodeDecodeError) as e:
        return [(0, "unreadable", str(e))]

    issues: list[tuple[int, str, str]] = []
    lines = src.splitlines()

    def is_body_line(ln: int) -> bool:
        return 0 < ln <= len(lines) and lines[ln - 1].lstrip().startswith("*")

    def rest_of_line(pos: int) -> str:
        j = src.find("\n", pos)
        return src[pos: j if j != -1 else len(src)]

    i, n = 0, len(src)
    line = 1
    in_line_comment = False
    mode = "code"          # code | str | raw | tmpl
    stack: list[tuple[str, int]] = []   # (外层 mode, 外层花括号深度)
    depth = 0              # 当前 tmpl 内的 { } 深度
    block_stack: list[int] = []         # 块注释嵌套：各层打开行号

    def pop_stack() -> tuple[str, int]:
        return stack.pop() if stack else ("code", 0)

    while i < n:
        c = src[i]

        if c == "\n":
            line += 1
            in_line_comment = False
            if mode == "str":  # 普通串不跨行 —— 行尾恢复（对齐 K1）
                mode, depth = pop_stack()
            i += 1
            continue
        if in_line_comment:
            i += 1
            continue

        # ① 块注释内容 —— 优先于一切模式（嵌套 + 收合检测）
        if block_stack:
            if src.startswith("*/", i):
                block_stack.pop()
                if not block_stack:  # 收平 —— R1：注释体行上截断 + 行尾残留
                    rest = rest_of_line(i + 2)
                    if rest.strip() and is_body_line(line):
                        issues.append((
                            line, "premature-close",
                            f"注释在 `*/` 处被截断，行尾残留 `{rest.strip()[:40]}` —— "
                            "注释体内不能出现 `*/` 序列（MIME 通配/glob 改文字描述）"))
                i += 2
                continue
            if src.startswith("/*", i):  # 嵌套开层（Kotlin 块注释可嵌套）
                block_stack.append(line)
                i += 2
                continue
            i += 1
            continue

        # ② raw 三引号串
        if mode == "raw":
            if src.startswith('"""', i):
                mode, depth = pop_stack()
                i += 3
                continue
            if c == "$" and i + 1 < n and src[i + 1] == "{":  # raw 内模板
                stack.append((mode, depth))
                mode = "tmpl"
                depth = 0
                i += 2
                continue
            i += 1
            continue

        # ③ 普通串（可含 ${} 模板）
        if mode == "str":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                mode, depth = pop_stack()
                i += 1
                continue
            if c == "$" and i + 1 < n and src[i + 1] == "{":
                stack.append((mode, depth))
                mode = "tmpl"
                depth = 0
                i += 2
                continue
            i += 1
            continue

        # ④ 模板内部 = 真代码（花括号计深度；可再开串/注释/嵌套模板）
        if mode == "tmpl":
            if c == "{":
                depth += 1
                i += 1
                continue
            if c == "}":
                if depth > 0:
                    depth -= 1   # lambda/块收尾 —— 仍在模板内
                else:
                    mode, depth = pop_stack()  # 模板本体收尾
                i += 1
                continue

        # ⑤ code / tmpl 公共：注释、行注释、串开
        if src.startswith("//", i):
            in_line_comment = True
            i += 2
            continue
        if src.startswith("/*", i):
            block_stack.append(line)
            i += 2
            continue
        if src.startswith('"""', i):
            stack.append((mode, depth))
            mode = "raw"
            i += 3
            continue
        if c == '"':
            stack.append((mode, depth))
            mode = "str"
            i += 1
            continue
        i += 1

    if block_stack:  # R2: EOF 仍未收平
        for opened_at in block_stack:
            issues.append((
                opened_at, "unclosed",
                "块注释打开后直到文件尾未闭合（若注释体内写过 `/*` —— 如 \"image/*\" —— "
                "会开嵌套层，需额外 `*/` 收平；Kotlin 块注释可嵌套）"))
    return issues


def iter_targets(args: list[str]) -> list[str]:
    if args:
        return args
    out = []
    for pat in GLOB_PATTERNS:
        out.extend(str(p) for p in pathlib.Path(".").glob(pat) if "/build/" not in str(p))
    return sorted(out)


def main() -> int:
    targets = iter_targets(sys.argv[1:])
    if not targets:
        print("check_comment_integrity: 未找到目标（core/ app/ 下无 .kt？）")
        return 0
    total = 0
    for path in targets:
        for ln, rule, detail in scan_file(path):
            total += 1
            print(f"✗ {path}:{ln} [{rule}] {detail}")
    if total:
        print(f"\n✗ 注释完整性检查失败：{total} 处（Kotlin 块注释不提前终止、不悬垂嵌套）")
        print("  修复指引：注释体内避免写出 `*/` 或 `/*` 序列 —— MIME 通配 \"*/*\"、"
              "glob \"image/*\" 等改用文字描述；确需引用时确保嵌套收平。")
        return 1
    print(f"✓ 注释完整性检查通过：{len(targets)} 个文件，无提前终止 / 悬垂嵌套")
    return 0


if __name__ == "__main__":
    sys.exit(main())
