#!/usr/bin/env python3
"""check_comment_integrity.py 的回归用例。

两个真实事故样本（2026-10-06 main 直推 401ac034 连环修复的两版）+ 一组合法
形态（嵌套收平 / 行内注释 / 字符串内的 `*/*` 与 `/*`）—— 确保门禁只咬真 bug。
"""

import sys
import pathlib
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from check_comment_integrity import scan_file  # noqa: E402

CASES = [
    # (name, source, expected_rules)
    ("原始事故：KDoc 内 MIME 通配 \"*/*\" 提前终止", '''/**
 * - mime_type (optional, default "*/*"): 文件类型过滤
 */
class X
''', {"premature-close"}),
    ("修复 v1 事故：KDoc 内 \"image/*\" 悬垂嵌套", '''/**
 * - mime_type (optional, 默认全部类型): MIME 过滤（可传 "image/*" 等缩窄）
 */
class X
''', {"unclosed"}),
    ("合法：KDoc 内嵌套注释收平（`/* 未实现 */`）", '''/**
 * 枚举值（`/* 暂未实现 */`）之后仍有正文。
 */
interface Y
''', set()),
    ("合法：行内注释后跟代码", '''val x = 1 /* note */ + 2
''', set()),
    ("合法：raw 串内 \"*/*\" 与 image/*", '''val d = """
        - mime_type (default "*/*"): e.g. "image/*" or "application/pdf"
    """.trimIndent()
''', set()),
    ("合法：普通串含 /* 与 */", '''val s = "pattern /* not comment */ and */ fine"
''', set()),
    ("合法：干净 KDoc", '''/**
 * 参数：
 * - a (optional, default 1): 说明
 */
fun f()
''', set()),
    ("合法：模板/三引号串内的注释符序列", '''val s = "x" /* c */ ${"""a*/b"""} 
''', set()),
]


def main() -> int:
    passed = 0
    for name, src, expected in CASES:
        with tempfile.NamedTemporaryFile("w", suffix=".kt", delete=False,
                                         encoding="utf-8") as f:
            f.write(src)
            path = f.name
        rules = {rule for _ln, rule, _d in scan_file(path)}
        ok = rules == expected
        print(f"{'PASS' if ok else 'FAIL'}  [{'clean' if not expected else 'caught'}] {name}"
              + ("" if ok else f" —— 期望 {expected or '无问题'}，实际 {rules or '无问题'}"))
        passed += ok
        pathlib.Path(path).unlink()
    print(f"\n{passed}/{len(CASES)} 通过")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())
