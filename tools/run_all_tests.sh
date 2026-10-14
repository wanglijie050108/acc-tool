#!/usr/bin/env bash
# 一键跑全部测试。
#
# 用法：
#   bash tools/run_all_tests.sh          # 只跑 JVM 单元测试（快，不需要模拟器）
#   bash tools/run_all_tests.sh --device # 连模拟器/真机，额外跑数据库仪器测试
#
# 三层测试的分工：
#   1. JVM 单元测试    → 解析规则、指纹逻辑、CSV 解析（快，秒级）
#   2. 仪器测试        → Room 数据库真实读写、去重逻辑、CSV 导入（需要设备）
#   3. 结构自检        → Manifest/资源/package 一致性（纯静态）
#   4. 规则对照测试    → Python 版，与 Kotlin 实现互相印证

set -uo pipefail
cd "$(dirname "$0")/.."

if [ -x "/c/Users/24996/jdk17/jdk-17.0.20.1+1/bin/java" ]; then
  export JAVA_HOME="/c/Users/24996/jdk17/jdk-17.0.20.1+1"
fi
export ANDROID_HOME="${ANDROID_HOME:-/c/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
export MSYS_NO_PATHCONV=1

PY="/c/Users/24996/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
FAILED=0

echo "════════════════════════════════════════"
echo " 1/5  结构自检（静态）"
echo "════════════════════════════════════════"
if "$PY" tools/project_check.py >/tmp/pc.log 2>&1; then
  echo "  [OK] 通过"
else
  echo "  [FAIL] 失败："
  tail -20 /tmp/pc.log
  FAILED=1
fi

echo
echo "════════════════════════════════════════"
echo " 2/5  JVM 单元测试"
echo "════════════════════════════════════════"
if ./gradlew --no-daemon --console=plain testDebugUnitTest -q >/tmp/ut.log 2>&1; then
  TOTAL=$(grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null \
          | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')
  FAILS=$(grep -ho 'failures="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null \
          | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')
  echo "  [OK] $TOTAL 个用例通过，$FAILS 个失败"
else
  echo "  [FAIL] 单元测试失败："
  grep -E "^e:|FAILED|expected" /tmp/ut.log | head -20
  FAILED=1
fi

echo
echo "════════════════════════════════════════"
echo " 3/5  解析引擎独立 runner"
echo "════════════════════════════════════════"
if bash tools/run_engine_test.sh 2>&1 | grep -q "OK"; then
  echo "  [OK] 通过"
else
  echo "  [FAIL] 失败"
  FAILED=1
fi

echo
echo "════════════════════════════════════════"
echo " 4/5  规则对照测试（Python）"
echo "════════════════════════════════════════"
if "$PY" tools/rule_check.py >/tmp/rc.log 2>&1; then
  echo "  [OK] 通过"
else
  echo "  [FAIL] 失败："
  tail -10 /tmp/rc.log
  FAILED=1
fi

echo
echo "════════════════════════════════════════"
echo " 5/5  真实银行短信覆盖率（Python）"
echo "════════════════════════════════════════"
if PYTHONIOENCODING=utf-8 "$PY" tools/real_sms_check.py >/tmp/rs.log 2>&1; then
  grep -E "识别成功" /tmp/rs.log | sed 's/^/  [OK] /'
else
  echo "  [FAIL] 有真实样本未被识别："
  tail -15 /tmp/rs.log
  FAILED=1
fi

# 可选的仪器测试
if [ "${1:-}" = "--device" ]; then
  echo
  echo "════════════════════════════════════════"
  echo " +    仪器测试（需要已连接的设备）"
  echo "════════════════════════════════════════"
  if [ -z "$("$ANDROID_HOME/platform-tools/adb.exe" devices 2>/dev/null | grep -w device | grep -v List)" ]; then
    echo "  [SKIP] 没有可用设备。先启动模拟器再重试。"
  elif ./gradlew --no-daemon --console=plain connectedDebugAndroidTest -q >/tmp/it.log 2>&1; then
    TOTAL=$(grep -ho 'tests="[0-9]*"' app/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null \
            | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')
    echo "  [OK] $TOTAL 个仪器测试通过"
  else
    echo "  [FAIL] 仪器测试失败："
    grep -E "FAILED|junit|AssertionError" /tmp/it.log | head -20
    FAILED=1
  fi
fi

echo
echo "════════════════════════════════════════"
if [ "$FAILED" = "0" ]; then
  echo " 全部测试通过"
else
  echo " 有测试失败，见上面的输出"
fi
echo "════════════════════════════════════════"
exit $FAILED
