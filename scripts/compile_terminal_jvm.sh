#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_jvm.sh — local kotlinc type-check for platform/terminal
#
# The sandbox has no Android SDK/Gradle; kotlinc (JVM target 17) gives full
# type-level verification for the two pure-JVM modules this task touches:
#   :terminal-emulator        (pure Kotlin, no Android imports)
#   :platform:terminal        (verified: zero android.*/androidx.* imports)
# Main + test sources compile in ONE invocation — same-compilation semantics
# give tests `internal` visibility exactly like Gradle's test source set.
# App-layer files (TerminalModule.kt etc.) still need CI's Android compile.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
OUT=$(mktemp -d)

echo "── compiling main + test (single compilation, internal-visible) ──"
"$KOTLINC" -jvm-target 17 -nowarn \
  terminal-emulator/src/main/kotlin \
  terminal-emulator/src/test/kotlin \
  platform/terminal/src/main/kotlin \
  platform/terminal/src/test/kotlin \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/classes -name '*.class' | wc -l) classes ──"
rm -rf "$OUT"
