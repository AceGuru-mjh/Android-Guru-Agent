#!/usr/bin/env bash
# T81 native host-side verification（Linux 主机真实 forkpty 行为验证 + TSan 可选）
# 用法：bash platform/terminal/src/hostTest/run.sh [--tsan]
# 说明：不进 APK（Gradle 源集不含 hostTest）；Android 真机行为由
#       src/androidTest 的 NativePtyJniInstrumentationTest 覆盖（需 connectedDebugAndroidTest）。
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
CPP="$DIR/../main/cpp"
SAN="${1:-}"
FLAGS=(-std=c++17 -Wall -Wextra -O2 -I"$DIR" -I"$CPP" -pthread -lutil)
if [ "$SAN" = "--tsan" ]; then FLAGS+=(-O1 -g -fsanitize=thread); fi
g++ "${FLAGS[@]}" "$CPP/pty_session.cpp" "$CPP/pty_engine.cpp" \
    "$DIR/native_host_test.cpp" -o /tmp/apex_pty_host_test
/tmp/apex_pty_host_test
