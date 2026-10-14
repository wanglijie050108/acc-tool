"""项目结构自检：不需要 Android SDK / JDK 就能发现大部分低级错误。

检查项：
1. AndroidManifest 里引用的每个类，对应的 .kt 文件是否存在
2. Manifest 引用的资源（@mipmap/@drawable/@xml/@style/@string）是否存在
3. 每个 .kt 的 package 声明是否与目录路径一致
4. Kotlin 文件里的括号/引号是否配平（粗查）
5. 跨文件引用的类是否都已定义
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main"
JAVA = SRC / "java"
MANIFEST = SRC / "AndroidManifest.xml"

errors: list[str] = []
warns: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warns.append(msg)


# ── 1. Manifest 里的类 ──────────────────────────────────────────
manifest_text = MANIFEST.read_text(encoding="utf-8")
pkg_match = re.search(r'package="([^"]+)"', manifest_text)
# namespace 在 build.gradle.kts 里
gradle_text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
ns_match = re.search(r'namespace\s*=\s*"([^"]+)"', gradle_text)
if not ns_match:
    err("build.gradle.kts 里找不到 namespace")
    namespace = "com.moneyflow.app"
else:
    namespace = ns_match.group(1)

declared_classes = re.findall(r'android:name="\.([A-Za-z0-9_.]+)"', manifest_text)
print(f"Manifest 声明的组件类：{len(declared_classes)} 个")
for cls in declared_classes:
    rel = Path(*cls.split(".")).with_suffix(".kt")
    path = JAVA / Path(*namespace.split(".")) / rel
    if path.exists():
        print(f"  [OK]   {cls}")
    else:
        err(f"Manifest 引用了 {cls}，但文件不存在：{path}")

# ── 2. Manifest 引用的资源 ──────────────────────────────────────
print("\n检查资源引用：")
res_refs = set(re.findall(r'@(mipmap|drawable|xml|style|string|values)/([A-Za-z0-9_.]+)', manifest_text))
# themes.xml 里也有引用
for extra in ["values/themes.xml"]:
    p = SRC / "res" / extra
    if p.exists():
        res_refs |= set(re.findall(r'@(mipmap|drawable|xml|style|string|values)/([A-Za-z0-9_.]+)', p.read_text(encoding="utf-8")))

for kind, name in sorted(res_refs):
    # 资源可能在任意 -qualifier 目录下
    found = False
    if kind in ("style", "string"):
        target_dir = SRC / "res" / "values"
        for f in target_dir.glob("*.xml"):
            if f'name="{name}"' in f.read_text(encoding="utf-8"):
                found = True
                break
    else:
        for d in (SRC / "res").glob(f"{kind}*"):
            if (d / f"{name}.xml").exists() or (d / f"{name}.png").exists():
                found = True
                break
    status = "[OK]  " if found else "[MISS]"
    print(f"  {status} @{kind}/{name}")
    if not found:
        err(f"资源 @{kind}/{name} 不存在")

# ── 3. package 声明与路径一致 ──────────────────────────────────
print("\n检查 package 声明：")
kt_files = sorted(JAVA.rglob("*.kt"))
package_errors = 0
for f in kt_files:
    text = f.read_text(encoding="utf-8")
    m = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
    if not m:
        err(f"{f.relative_to(ROOT)} 缺少 package 声明")
        continue
    declared = m.group(1)
    expected = str(f.parent.relative_to(JAVA)).replace(os.sep, ".")
    if declared != expected:
        err(f"{f.relative_to(ROOT)}: package 是 {declared}，但路径要求 {expected}")
        package_errors += 1
print(f"  {len(kt_files)} 个 .kt 文件，package 不匹配 {package_errors} 个")

# ── 4. 括号配平（用状态机，见 check_braces.py） ────────────────
# 早期版本用正则粗暴剥离字符串/注释，遇到复杂转义（如 CSV 解析里的引号处理）
# 会误报。现在统一走 check_braces.py 的状态机实现。
print("\n检查括号配平：")
import subprocess
_brace = subprocess.run(
    [sys.executable, str(ROOT / "tools" / "check_braces.py")],
    capture_output=True, text=True, encoding="utf-8",
)
if _brace.returncode != 0:
    for line in (_brace.stdout or "").splitlines():
        if "不配平" in line:
            err(line.strip())
    print(f"  [FAIL] 见上")
else:
    print(f"  [OK] {len(kt_files)} 个文件全部配平")

# ── 5. 跨文件类引用 ────────────────────────────────────────────
print("\n检查关键类引用：")
defined: set[str] = set()
for f in kt_files:
    text = f.read_text(encoding="utf-8")
    defined |= set(re.findall(r"\b(?:class|object|interface|enum class)\s+([A-Z][A-Za-z0-9_]*)", text))
    # 顶层 Composable 函数也是被 import 的目标（如 MoneyFlowTheme）
    defined |= set(re.findall(r"^fun\s+([A-Z][A-Za-z0-9_]*)\s*\(", text, re.MULTILINE))
    # enum 的成员常量类
    defined |= set(re.findall(r"\benum class\s+([A-Z][A-Za-z0-9_]*)", text))

# 收集本项目内的 import（com.moneyflow.app.*）
internal_imports: set[str] = set()
for f in kt_files:
    text = f.read_text(encoding="utf-8")
    for imp in re.findall(r"^import\s+(com\.moneyflow\.app\.[A-Za-z0-9_.]+)", text, re.MULTILINE):
        simple = imp.split(".")[-1]
        # 只关心看起来像类名的（首字母大写）
        if simple and simple[0].isupper():
            internal_imports.add(f"{simple}|{f.relative_to(ROOT)}")

missing_imports = 0
for entry in sorted(internal_imports):
    simple, src = entry.split("|")
    if simple not in defined:
        err(f"{src} 导入了 {simple}，但没有找到该类的定义")
        missing_imports += 1
print(f"  本项内部导入 {len(internal_imports)} 个，缺失定义 {missing_imports} 个")

# ── 6. 依赖检查：用到的 import 前缀 vs build.gradle 声明 ─────────
print("\n检查第三方依赖是否声明：")
all_imports: set[str] = set()
for f in kt_files:
    text = f.read_text(encoding="utf-8")
    all_imports |= set(re.findall(r"^import\s+([a-z][A-Za-z0-9_.]+)", text, re.MULTILINE))

# 已知的 group→依赖映射
groups = {
    "androidx.activity": "activity-compose",
    "androidx.compose": "compose-bom",
    "androidx.core": "core-ktx",
    "androidx.lifecycle": "lifecycle",
    "androidx.room": "room",
    "kotlinx.coroutines": "kotlinx-coroutines",
}
for g, dep in sorted(groups.items()):
    used = any(i.startswith(g) for i in all_imports)
    declared = dep in gradle_text or g.split(".")[-1] in gradle_text
    if used and not declared:
        err(f"用到了 {g}.*，但 build.gradle.kts 里似乎没声明 {dep}")
    elif used:
        print(f"  [OK]   {g}.* → {dep}")

# ── 汇总 ───────────────────────────────────────────────────────
print("\n" + "=" * 60)
if warns:
    print(f"警告 {len(warns)} 条：")
    for w in warns:
        print(f"  ! {w}")
if errors:
    print(f"\n[FAIL] 发现 {len(errors)} 个问题：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
else:
    print("[OK] 结构自检全部通过")
