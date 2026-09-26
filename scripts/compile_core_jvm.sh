#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_core_jvm.sh — local kotlinc type-check for core agent modules
#
# Verifies the pure-JVM Gradle modules this task touches:
#   :core:logging  :core:llm-adapter  :core:tool-registry  :core:agent-engine
# Single invocation = same-compilation semantics (internal visibility OK).
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/okhttp-4.12.0.jar:$LIBS/okhttp-sse-4.12.0.jar:$LIBS/okio-jvm-3.6.0.jar"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
OUT=$(mktemp -d)

echo "── compiling core main sources (logging → llm → tools → engine) ──"
"$KOTLINC" -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  core/logging/src/main/kotlin \
  core/llm-adapter/src/main/kotlin \
  core/tool-registry/src/main/kotlin \
  core/agent-engine/src/main/kotlin \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

n=$(find "$OUT/classes" -name '*.class' | wc -l)
echo "── main OK: $n classes ──"

if [ "${WITH_TESTS:-0}" = "1" ]; then
  echo "── compiling engine tests against main output ──"
  "$KOTLINC" -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
    core/agent-engine/src/test/kotlin \
    -cp "$CP:$OUT/classes" -d "$OUT/test-classes" 2>&1 | { grep -E "error:" || true; } | head -40
  echo "── tests OK: $(find "$OUT/test-classes" -name '*.class' | wc -l) classes ──"
fi

rm -rf "$OUT"
