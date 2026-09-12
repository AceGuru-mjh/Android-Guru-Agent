#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# kotlin_balance.py — Kotlin 括号/花括号平衡门禁（词法感知版）
# ═══════════════════════════════════════════════════════════════════════════
# 用法: python3 scripts/kotlin_balance.py <file.kt> [file2.kt ...]
# 裸 grep 计数把字符串内括号("missing ')')")与注释枚举(1) 2))计入统计造成
# 假阳性。本脚本实现 Kotlin 词法状态机,剥离以下 token 后再统计:
#   行注释 / 块注释 / raw 三引号串(含 4+ 引号连跑边界) / 转义串 /
#   字符字面量 / 字符串模板 ${...}(含嵌套:串内模板/模板内串/模板内 lambda)
# 模板内 lambda 花括号按真实代码计数(深度跟踪);反引号标识符(测试方法名
# 可含撇号/引号)整段跳过。真正的语法性失衡仍然必报。
# 用法(仓库根): 见 .github/workflows/ci.yml "Check bracket balance (lexer-aware)"

import sys
import pathlib


def strip_kotlin(src):
    out = []
    # 栈元素: (外层模式, 外层模板花括号深度)
    stack = []
    mode = 'code'              # code | str | raw | tmpl
    depth = 0                  # 当前 tmpl 模式的花括号嵌套深度
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if mode in ('code', 'tmpl'):
            if c == '/' and nxt == '/':                      # 行注释
                j = src.find('\n', i)
                i = n if j == -1 else j
            elif c == '/' and nxt == '*':                    # 块注释
                j = src.find('*/', i + 2)
                i = n if j == -1 else j + 2
            elif c == '"':
                j = i
                while j < n and src[j] == '"':
                    j += 1
                run = j - i
                stack.append((mode, depth))
                if run >= 3:                                 # raw 串开(3 引号为界,多余为内容)
                    mode = 'raw'
                    i += 3
                else:                                        # 普通串开
                    mode = 'str'
                    i += 1
            elif c == '`':                                   # 反引号标识符(测试方法名可含撇号/引号)
                j = src.find('`', i + 1)
                i = n if j == -1 else j + 1
            elif c == "'":                                   # 字符字面量
                j = i + 1
                while j < n and src[j] != "'":
                    if src[j] == '\\':
                        j += 1
                    j += 1
                i = j + 1
            elif mode == 'tmpl' and c == '{':                # 模板内代码块/lambda 开
                depth += 1
                out.append(c)
                i += 1
            elif mode == 'tmpl' and c == '}':
                if depth > 0:                                # lambda/块收尾 —— 真代码
                    depth -= 1
                    out.append(c)
                else:                                        # 模板本身结束 -> 恢复外层
                    mode, depth = stack.pop() if stack else ('code', 0)
                i += 1
            else:
                if c in '(){}':
                    out.append(c)
                i += 1
        elif mode == 'str':
            if c == '\\':
                i += 2
            elif c == '"':                                   # 串结束 -> 恢复外层
                mode, depth = stack.pop() if stack else ('code', 0)
                i += 1
            elif c == '$' and nxt == '{':                    # 串内模板开
                stack.append(('str', 0))
                mode = 'tmpl'
                depth = 0
                i += 2
            elif c == '$':                                   # $ident 简单引用
                i += 1
                while i < n and (src[i].isalnum() or src[i] == '_'):
                    i += 1
            else:
                i += 1
        elif mode == 'raw':
            if c == '"':
                j = i
                while j < n and src[j] == '"':
                    j += 1
                run = j - i
                if run >= 3:                                 # 引号连跑 >=3 = 关界(含前置内容引号)
                    i = j
                    mode, depth = stack.pop() if stack else ('code', 0)
                else:                                        # 内容引号
                    i = j
            elif c == '$' and nxt == '{':                    # raw 内模板开
                stack.append(('raw', 0))
                mode = 'tmpl'
                depth = 0
                i += 2
            elif c == '$':
                i += 1
                while i < n and (src[i].isalnum() or src[i] == '_'):
                    i += 1
            else:
                i += 1
    return ''.join(out)

def main():
    if len(sys.argv) < 2:
        print("usage: kotlin_balance.py <file.kt> [...]"); sys.exit(2)
    fail = False
    for f in sys.argv[1:]:
        try:
            stripped = strip_kotlin(pathlib.Path(f).read_text(encoding='utf-8'))
        except Exception as e:
            print(f"FAIL {f}: read error {e}")
            fail = True
            continue
        po, pc = stripped.count('('), stripped.count(')')
        bo, bc = stripped.count('{'), stripped.count('}')
        if po != pc or bo != bc:
            print(f"X {f}: {po} ( vs {pc} ) | {bo} {{ vs {bc} }}")
            fail = True
    if fail:
        sys.exit(1)
    print(f"OK {len(sys.argv)-1} kt files balanced (lexer-stripped)")

if __name__ == '__main__':
    main()
