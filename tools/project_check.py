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

# ── 7. 权限弹窗接线自检（防「功能被静默改回跳设置页」）──────────
#
# 背景（真实事故）：App 曾自己弹系统权限框，用户明确说满意，
# 但那次改动没提交、也没测试守着，一次更新后就被覆盖没了。
# 下面这几条把它钉住 —— 光有单元测试不够，因为单元测试测的是
# PermissionRequest 的内部逻辑，**没人保证调用方真的接了线**：
# 把 MainActivity 里的调用删掉，单元测试照样全绿。
print("\n检查权限申请接线：")


def _read(rel: str) -> str:
    p = JAVA / Path(*namespace.split(".")) / rel
    return p.read_text(encoding="utf-8") if p.exists() else ""


_permission_file = JAVA / Path(*namespace.split(".")) / "ui" / "PermissionRequest.kt"

if not _permission_file.exists():
    err("ui/PermissionRequest.kt 不存在 —— 权限申请的唯一入口丢了，"
        "功能会退回「只能跳系统设置页」")
else:
    print("  [OK]   PermissionRequest.kt 存在（权限申请的唯一入口）")

    _main = _read("MainActivity.kt")
    _settings = _read("ui/SettingsScreen.kt")

    # ⚠️ 这里刻意**不看注释**。
    # 第一版自检用「文件里出现过 requestRuntimePermissions 就算通过」，
    # 实测漏判：把 onCreate 里的调用删掉后，注释和设置页那处调用
    # 仍然让检查显示 [OK] —— 正是「检查通过但功能已经没了」这个最坏情况。
    # 所以先剥掉注释，再在**代码**里找调用点。
    def _strip_comments(src: str) -> str:
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.DOTALL)   # 块注释
        src = re.sub(r"//[^\n]*", "", src)                      # 行注释
        return src

    _main_code = _strip_comments(_main)

    # 7.1 onCreate 里必须真的在启动时申请权限
    _oncreate = re.search(r"override\s+fun\s+onCreate\b.*?(?=\n    override|\n    private|\Z)",
                          _main_code, re.DOTALL)
    # 启动路径的申请：必须由 needsRuntimePrompt 守卫，且**不是**设置页那段 when 分支。
    # 用「守卫 + 申请」在同一表达式里来判定，避免把设置页里的调用误算成启动申请。
    _start_prompt = re.search(
        r"needsRuntimePrompt[^)]*\)\s*\)?\s*\{?\s*\n?\s*(?:PermissionRequest\.)?requestRuntimePermissions",
        _main_code,
    )
    if _oncreate is None:
        err("MainActivity.kt 里找不到 onCreate —— 无法确认启动时是否申请权限")
    elif _start_prompt is None:
        err("MainActivity.onCreate 里没有「先判断 needsRuntimePrompt、再 requestRuntimePermissions」的启动申请 —— "
            "启动时不会弹权限框（这正是被弄丢过的那次改动）")
    else:
        print("  [OK]   启动时（onCreate）会申请运行时权限")

    # 7.2 已有的守卫不得被挪出 onCreate
    if _oncreate is None:
        pass
    elif _start_prompt is not None and _start_prompt.start() > _oncreate.end():
        err("needsRuntimePrompt 守卫出现在 onCreate 之外 —— 启动路径没有守卫")
    else:
        print("  [OK]   已授权时不会重复骚扰（守卫与申请同条件）")

    # 7.3 设置页按钮必须走统一回调
    if "onRequestPermission" not in _settings:
        err("SettingsScreen.kt 没有 onRequestPermission 回调 —— "
            "设置页按钮没接上弹窗逻辑，会退回「只会跳系统设置页」")
    elif "PermissionRequest" not in _settings:
        err("SettingsScreen.kt 没有引用 PermissionRequest —— 权限状态查询可能又各写了一份")
    else:
        # 三个按钮都必须挂上回调，少一个就是那一项退化成没反应
        _wired = len(re.findall(r"onRequestPermission\(PermissionRequest\.Kind\.", _settings))
        if _wired < 3:
            err(f"SettingsScreen.kt 只有 {_wired}/3 个权限按钮接上了 onRequestPermission —— "
                f"没接的那一项点下去不会弹窗")
        else:
            print(f"  [OK]   设置页 {_wired} 个权限按钮都走统一入口")

    # 7.4 权限查询/跳转不得在别处重复实现（本项目已被「写两份」坑过两次）
    _dup_markers = ("openAppSettings", "openBatterySettings", "openNotificationAccessSettings")
    _dups = []
    for _other in kt_files:
        if _other == _permission_file:
            continue
        _t = _strip_comments(_other.read_text(encoding="utf-8"))
        for _m in _dup_markers:
            if re.search(rf"\bfun\s+{_m}\s*\(", _t):
                _dups.append(f"{_other.relative_to(ROOT)} 重复定义了 {_m}()")
    if _dups:
        for _d in _dups:
            err(_d + " —— 权限跳转只能有一份实现（在 PermissionRequest.kt）")
    else:
        print("  [OK]   权限查询/跳转没有重复实现")

    # 7.5 不得申请网络权限（README 的隐私承诺）
    _manifest = MANIFEST.read_text(encoding="utf-8")
    if re.search(r"android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)", _manifest):
        err("AndroidManifest.xml 申请了网络权限 —— "
            "README 明确承诺「代码里没有申请网络权限，不可能上传」")
    else:
        print("  [OK]   未申请网络权限（隐私承诺未被破坏）")

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
