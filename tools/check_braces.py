"""检查 Kotlin 文件的括号配平（正确处理注释/字符串/字符字面量）。

项目自检里那段用正则粗暴剥离字符串，遇到复杂转义会误判。
这个脚本用状态机逐字符扫描，结果可靠。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def scan(text: str):
    """返回 (花括号差, 圆括号差, 方括号差)。用状态机处理各种字面量。"""
    i = 0
    n = len(text)
    depth_curly = depth_paren = depth_square = 0

    while i < n:
        c = text[i]

        # 行注释
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue

        # 块注释（Kotlin 支持嵌套）
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            nest = 1
            i += 2
            while i < n and nest > 0:
                if text[i] == '/' and i + 1 < n and text[i + 1] == '*':
                    nest += 1
                    i += 2
                elif text[i] == '*' and i + 1 < n and text[i + 1] == '/':
                    nest -= 1
                    i += 2
                else:
                    i += 1
            continue

        # 三引号原始字符串
        if text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                i += 1
            i += 3
            continue

        # 普通字符串
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue

        # 字符字面量（如 '{' 或 '\''）
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue

        if c == '{':
            depth_curly += 1
        elif c == '}':
            depth_curly -= 1
        elif c == '(':
            depth_paren += 1
        elif c == ')':
            depth_paren -= 1
        elif c == '[':
            depth_square += 1
        elif c == ']':
            depth_square -= 1
        i += 1

    return depth_curly, depth_paren, depth_square


def main() -> int:
    targets = sys.argv[1:] or [
        str(p) for p in ROOT.rglob("*.kt") if "build" not in p.parts
    ]
    bad = 0
    for path in targets:
        p = Path(path)
        if not p.is_absolute():
            p = ROOT / p
        if not p.exists():
            print(f"  文件不存在: {p}")
            bad += 1
            continue
        curly, paren, square = scan(p.read_text(encoding="utf-8"))
        if curly or paren or square:
            print(f"  [不配平] {p.relative_to(ROOT)}: {{}}={curly:+d} ()={paren:+d} []={square:+d}")
            bad += 1
    if bad:
        print(f"\n{bad} 个文件括号不配平")
        return 1
    print(f"  全部 {len(targets)} 个文件括号配平正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())
