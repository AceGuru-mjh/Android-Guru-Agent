#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_jvm.sh — local kotlinc type-check for platform/terminal
#
# The sandbox has no Android SDK/Gradle; kotlinc (JVM target 17) gives full
# type-level verification for the two pure-JVM modules this task touches:
#   :terminal-emulator        (pure Kotlin, no Android imports)
#   :platform:terminal        (verified: zero android.*/androidx.* imports)
# App-layer files (TerminalModule.kt etc.) still need CI's Android compile —
# this script covers everything else, including test sources.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar"
TEST_CP="$CP:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
OUT=$(mktemp -d)

echo "── compiling main (terminal-emulator + platform/terminal) ──"
"$KOTLINC" -jvm-target 17 -nowarn \
  terminal-emulator/src/main/kotlin \
  platform/terminal/src/main/kotlin \
  -cp "$CP" -d "$OUT/main" 2>&1 | { grep -E "^.*error:|warning: unable" || true; } | head -60

echo "── compiling JVM tests ──"
"$KOTLINC" -jvm-target 17 -nowarn \
  platform/terminal/src/test/kotlin \
  -cp "$TEST_CP:$OUT/main" -d "$OUT/test" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/main -name '*.class' | wc -l) main classes, $(find $OUT/test -name '*.class' | wc -l) test classes ──"
rm -rf "$OUT"
