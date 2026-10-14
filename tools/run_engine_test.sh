#!/usr/bin/env bash
# 解析引擎 JVM 测试的运行脚本。
#
# 为什么需要它：项目里的 ParseEngineTest 是手写 runner（不依赖 JUnit），
# 所以 `gradlew test` 不会自动执行它，必须像这样直接调 JVM。
#
# 用法：bash tools/run_engine_test.sh

set -euo pipefail
cd "$(dirname "$0")/.."

# 优先用项目自带的 JDK，其次用 JAVA_HOME
if [ -x "/c/Users/24996/jdk17/jdk-17.0.20.1+1/bin/java" ]; then
  export JAVA_HOME="/c/Users/24996/jdk17/jdk-17.0.20.1+1"
elif [ -n "${JAVA_HOME:-}" ]; then
  : # 用现有的 JAVA_HOME
else
  echo "找不到 java。请设置 JAVA_HOME 或安装 JDK 17。" >&2
  exit 1
fi
JAVA="$JAVA_HOME/bin/java"

# 输出用 UTF-8，避免中文在 Windows 控制台乱码
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

ANDROID_HOME="${ANDROID_HOME:-/c/Android/Sdk}"
export ANDROID_HOME

echo "==> 编译 App 与测试代码"
./gradlew --no-daemon --console=plain compileDebugUnitTestKotlin -q

STDLIB=$(find ~/.gradle/caches -name "kotlin-stdlib-1.9.24.jar" 2>/dev/null | head -1)
if [ -z "$STDLIB" ]; then
  echo "找不到 kotlin-stdlib，请先跑一次 gradlew assembleDebug 让依赖下载完成。" >&2
  exit 1
fi

MAIN=$(cygpath -w "app/build/tmp/kotlin-classes/debug")
TEST=$(cygpath -w "app/build/tmp/kotlin-classes/debugUnitTest")
STDLIB_W=$(cygpath -w "$STDLIB")

echo "==> 运行解析引擎测试"
"$JAVA" -cp "$MAIN;$TEST;$STDLIB_W" com.moneyflow.app.ParseEngineTestKt
