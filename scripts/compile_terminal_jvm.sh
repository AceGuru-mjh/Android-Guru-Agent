#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_jvm.sh — local kotlinc type-check for the terminal stack
#
# The sandbox has no Android SDK/Gradle; kotlinc (JVM target 17) gives full
# type-level verification for the pure-JVM modules this task touches:
#   :terminal-emulator   (pure Kotlin, zero Android imports)
#   :terminal-native     (one android.util.Log import — stubbed locally)
#   :platform:terminal   (screen/ pulls vtnative → android.util.Log, stubbed)
#
# android.util.Log is stubbed from a temp dir (never committed); everything
# else compiles against real sources. Main + test in ONE invocation gives
# tests `internal` visibility exactly like Gradle's test source set.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar"
OUT=$(mktemp -d)
STUBS=$(mktemp -d)
mkdir -p "$STUBS/android/util"
cat > "$STUBS/android/util/Log.kt" <<'STUB'
// Local JVM-compile stub (never committed) — CI uses the real android.util.Log.
package android.util
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = false
}
STUB

echo "── compiling terminal-emulator + terminal-native + platform/terminal ──"
# 全栈一次编译内存压力大（UnicodeWidthTables 大区间表 + 800+ 源文件）：-J-Xmx4g
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  "$STUBS" \
  terminal-emulator/src/main/kotlin \
  terminal-emulator/src/test/kotlin \
  terminal-native/src/main/kotlin \
  terminal-native/src/test/kotlin \
  platform/terminal/src/main/kotlin \
  platform/terminal/src/test/kotlin \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/classes -name '*.class' | wc -l) classes ──"
rm -rf "$OUT" "$STUBS"
