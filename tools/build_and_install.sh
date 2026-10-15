#!/usr/bin/env bash
# 一键打包并安装到手机。
#
# 用法：
#   bash tools/build_and_install.sh              # 编译 release + 同步交付物 + 装到手机
#   bash tools/build_and_install.sh --no-install # 只打包，不装
#   bash tools/build_and_install.sh --debug      # 装 debug 包（可读数据库，调试用）
#   bash tools/build_and_install.sh --clean      # 先 clean 再编译（慢，但排除缓存干扰）
#
# 为什么需要这个脚本：
# 打包这条链路上有 4 个「悄悄出错、但结果看起来正常」的坑，
# 每一步都有实际踩过的记录（见 docs/打包与安装.md）：
#   1. JAVA_HOME 必须是 Windows 路径 —— 给 .bat 用 /c/... 会报 invalid directory
#   2. 不设 MSYS_NO_PATHCONV=1 时，传给 adb 的 /sdcard/... 会被改写成 Windows 路径
#   3. 编译成功 ≠ 装上去的是新包 —— 忘同步交付物目录是最常见的事故
#   4. 装完不验证签名/哈希，无法确认手机上跑的到底是哪个包
#
# 所以这个脚本每一步都**断言**，任何一步对不上就直接失败退出，
# 而不是「看起来成功了」。

set -uo pipefail
cd "$(dirname "$0")/.."

# ─────────────── 环境（这台机器的固定路径）───────────────

# ⚠️ JAVA_HOME 用 Windows 形式：apksigner.bat 是批处理，认不出 /c/... 形式。
#    gradlew 是 bash 脚本，两种形式都能用，所以统一用 Windows 形式最省事。
export JAVA_HOME='C:\Users\24996\jdk17\jdk-17.0.20.1+1'
export ANDROID_HOME=/c/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
# ⚠️ 必须设：否则传给 adb.exe 的 /sdcard/x.png 会被 MSYS 改写成 D:\sdcard\x.png
export MSYS_NO_PATHCONV=1

ADB="$ANDROID_HOME/platform-tools/adb.exe"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner.bat"
PKG="com.moneyflow.app"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
DELIVERY="交付物/流水记账-v0.1.0.apk"

INSTALL=1
USE_DEBUG=0
DO_CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --no-install) INSTALL=0 ;;
    --debug)      USE_DEBUG=1 ;;
    --clean)      DO_CLEAN=1 ;;
    *) echo "未知参数：$arg"; exit 2 ;;
  esac
done

fail() { echo; echo "❌ $*"; exit 1; }
step() { echo; echo "════════ $* ════════"; }

# ─────────────── 0. 前置检查 ───────────────

step "0/5  前置检查"

[ -x "$JAVA_HOME/bin/javac" ] || \
  [ -x '/c/Users/24996/jdk17/jdk-17.0.20.1+1/bin/javac' ] \
  || fail "找不到 JDK。javac 必须存在（只有 JRE 编译不了）。
   期望路径：C:\\Users\\24996\\jdk17\\jdk-17.0.20.1+1"

"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/^/  JDK: /'

[ -d "$ANDROID_HOME/platforms/android-34" ] \
  || fail "Android SDK platform 34 缺失：$ANDROID_HOME/platforms/android-34"
[ -f "$APKSIGNER" ] || fail "找不到 apksigner：$APKSIGNER"
echo "  SDK: $ANDROID_HOME (platform-34 / build-tools 34.0.0)"

# ─────────────── 1. 编译 ───────────────

step "1/5  编译"

GRADLE_TASKS="assembleRelease"
[ "$USE_DEBUG" = "1" ] && GRADLE_TASKS="assembleDebug"
[ "$DO_CLEAN" = "1" ] && GRADLE_TASKS="clean $GRADLE_TASKS"

if ./gradlew --no-daemon --console=plain $GRADLE_TASKS > /tmp/mf_build.log 2>&1; then
  echo "  [OK] BUILD SUCCESSFUL（$GRADLE_TASKS）"
else
  echo "  编译失败，最后 30 行："
  tail -30 /tmp/mf_build.log
  fail "编译失败。完整日志：/tmp/mf_build.log"
fi

# 警告数（项目约定：0 warning）
WARN=$(grep -c '^w: ' /tmp/mf_build.log 2>/dev/null | head -1)
WARN=${WARN:-0}
if [ "$WARN" != "0" ]; then
  echo "  ⚠️ 有 $WARN 条编译警告（项目约定是 0）"
  grep '^w: ' /tmp/mf_build.log | head -5 | sed 's/^/     /'
else
  echo "  [OK] 0 warning"
fi

# ─────────────── 2. 校验产物 ───────────────

step "2/5  校验 APK"

SRC_APK="$RELEASE_APK"
[ "$USE_DEBUG" = "1" ] && SRC_APK="$DEBUG_APK"

[ -f "$SRC_APK" ] || fail "编译声称成功，但产物不存在：$SRC_APK"

# ⚠️ 产物必须比所有源码新，用来发现「Gradle 用了缓存，装上去的是旧代码」。
#
# 但**不能直接拿 mtime 硬失败**：`touch` 一个源文件（内容没变）会让 Gradle
# 判定 UP-TO-DATE 而不重写 APK，这时 mtime 检查会永远失败，把正常流程卡死。
# 所以这里用「Gradle 自己报告的 UP-TO-DATE」作为主判据，
# mtime 只作提示。
if grep -q 'Task :app:packageRelease UP-TO-DATE\|Task :app:packageRelease FROM-CACHE' /tmp/mf_build.log; then
  echo "  ℹ️ 本次未重新打包（Gradle 判定 UP-TO-DATE / FROM-CACHE）"
  echo "     如果确信改过代码，加 --clean 重跑以排除缓存干扰"
fi

NEWEST_SRC=$(find app/src -type f \( -name '*.kt' -o -name '*.xml' \) -newer "$SRC_APK" 2>/dev/null | head -5)
if [ -n "$NEWEST_SRC" ]; then
  echo "  ⚠️ 以下源码的修改时间比 APK 新（可能只是 touch 过，内容未必变）："
  echo "$NEWEST_SRC" | sed 's/^/     /'
  echo "     若刚改过这些文件的**内容**，加 --clean 重跑。"
else
  echo "  [OK] 产物比全部源码新"
fi

# 签名校验：必须能验过，否则装不上（或被系统拒装）
SIGNER=$("$APKSIGNER" verify --print-certs "$SRC_APK" 2>&1 | grep -m1 'SHA-256 digest')
if [ -z "$SIGNER" ]; then
  fail "签名校验失败：$SRC_APK
   用这条命令看详情：\"$APKSIGNER\" verify --print-certs \"$SRC_APK\""
fi
echo "  [OK] 签名有效"
echo "       $SIGNER"

SIZE=$(stat -c %s "$SRC_APK" 2>/dev/null || echo 0)
echo "  大小: $((SIZE / 1024 / 1024)) MB ($SIZE 字节)"

# ─────────────── 3. 同步交付物 ───────────────

step "3/5  同步交付物目录"

if [ "$USE_DEBUG" = "0" ]; then
  cp "$SRC_APK" "$DELIVERY" || fail "同步交付物失败：$DELIVERY"

  # ⚠️ 断言两份文件确实一致。只 cp 不校验的话，
  #    曾经出现过「交付物是上一版」而没人发现的情况。
  H1=$(sha256sum "$SRC_APK" | cut -d' ' -f1)
  H2=$(sha256sum "$DELIVERY" | cut -d' ' -f1)
  [ "$H1" = "$H2" ] || fail "交付物与编译产物不一致！
     $SRC_APK -> $H1
     $DELIVERY -> $H2"
  echo "  [OK] $DELIVERY"
  echo "       sha256: ${H1:0:16}…"
else
  echo "  (debug 包不覆盖交付物)"
fi

# ─────────────── 4. 装到手机 ───────────────

if [ "$INSTALL" = "0" ]; then
  step "4/5  跳过安装（--no-install）"
  echo
  echo "完成。APK：$SRC_APK"
  exit 0
fi

step "4/5  安装到手机"

# ⚠️ adb 的输出是 CRLF 行尾！直接 grep 'device$' 永远匹配不到
#    （行尾是 \r，$ 锚点对不上），会让脚本误报「没有设备」。
#    所以凡是要解析 adb 输出的地方，必须先 tr -d '\r'。
DEVICES=$("$ADB" devices | tr -d '\r' | grep -cw 'device')
if [ "$DEVICES" = "0" ]; then
  echo "  没有检测到设备。检查："
  echo "   1. USB 线是**能传数据**的（只能充电的线认不到）"
  echo "   2. 手机已开「开发者选项 → USB 调试」"
  echo "   3. 手机弹「允许 USB 调试吗」时点了允许"
  echo
  echo "  当前设备列表："
  "$ADB" devices -l | tr -d '\r' | sed 's/^/     /'
  fail "无可用设备。已打包完成，可加 --no-install 跳过安装。"
fi

DEV_LINE=$("$ADB" devices -l | tr -d '\r' | grep -w 'device' | head -1)
echo "  设备：$(echo "$DEV_LINE" | awk '{print $1}')"

# 手机型号与系统版本，便于把问题和机型对上
MODEL=$("$ADB" shell getprop ro.product.marketname 2>/dev/null | tr -d '\r')
[ -z "$MODEL" ] && MODEL=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
REL=$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "  $MODEL / Android $REL"

# --debug 与 release 同签名（都用 debug keystore），可互相覆盖且数据保留
if "$ADB" install -r "$SRC_APK" 2>&1 | tee /tmp/mf_install.log | grep -q 'Success'; then
  echo "  [OK] 安装成功"
else
  echo "  安装输出："
  cat /tmp/mf_install.log | sed 's/^/     /'
  echo
  echo "  常见原因："
  echo "   - INSTALL_FAILED_UPDATE_INCOMPATIBLE：签名不同，先 adb uninstall $PKG"
  echo "   - INSTALL_FAILED_VERSION_DOWNGRADE：手机上的版本更高，加 -d 或先卸载"
  fail "安装失败"
fi

# ─────────────── 5. 验证手机上跑的确实是这个包 ───────────────

step "5/5  验证安装结果"

LOCAL_HASH=$(sha256sum "$SRC_APK" | cut -d' ' -f1)

# 把手机上正在运行的 APK 拉回来比哈希。
# ⚠️ 这是唯一能证明「手机跑的就是刚编译那个包」的方法。
#    pm path 给出的是真实安装路径；拉回来比哈希，比看时间戳可靠得多。
REMOTE=$( "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -1 )
if [ -n "$REMOTE" ]; then
  TMP_DL='C:\Users\24996\AppData\Local\Temp\mf_installed.apk'
  if "$ADB" pull "$REMOTE" "$TMP_DL" >/dev/null 2>&1; then
    DL_HASH=$(sha256sum '/c/Users/24996/AppData/Local/Temp/mf_installed.apk' | cut -d' ' -f1)
    if [ "$LOCAL_HASH" = "$DL_HASH" ]; then
      echo "  [OK] 手机上的包与刚编译的包逐字节一致"
      echo "       sha256: ${LOCAL_HASH:0:16}…"
    else
      fail "手机上的包和刚编译的不一致！
   本地: $LOCAL_HASH
   手机: $DL_HASH
   可能是安装被系统拦截或覆盖失败。"
    fi
    rm -f '/c/Users/24996/AppData/Local/Temp/mf_installed.apk'
  else
    echo "  ⚠️ 无法拉取手机上已装的 APK（权限或路径问题），跳过哈希比对"
  fi
else
  echo "  ⚠️ pm path 没返回路径，跳过哈希比对"
fi

echo
echo "════════ 完成 ════════"
echo "  APK:   $SRC_APK"
echo "  交付物: $DELIVERY"
echo "  手机上: $MODEL / Android $REL"
echo
echo "⚠️ 装完后请在手机上完成这三步（否则收不到通知）："
echo "   1. 打开 App → ⚙️ 设置 → 「通知使用权」去开启"
echo "   2. 设置 → 「后台运行」去关闭电池优化"
echo "   3. 小米/红米额外开「自启动」并设省电策略「无限制」"
