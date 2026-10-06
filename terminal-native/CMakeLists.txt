cmake_minimum_required(VERSION 3.22.1)
project("apex_vt_native" CXX)

# ═══════════════════════════════════════════════════════════════════════════
# :terminal-native — Android module entry for the vendored apex-vt-native
# engine. add_subdirectory builds:
#   * apex-vt-core (static)  — the C++17 VT/ANSI core
#   * vt_native    (shared)  — libvt_native.so (JNI bridge), packaged by AGP
# ═══════════════════════════════════════════════════════════════════════════

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored library (upstream: AceGuru-mjh/apex-vt-native — see VENDOR.md).
# Tests/fuzz stay OFF here; the upstream CI runs them on every push.
add_subdirectory(src/main/cpp/vt-native)

# ═══════════════════════════════════════════════════════════════════════════
# T95: host-maintained render fast path (vendored tree untouched).
#
# fastpath/ collapses the engine's per-cell FlatSnapshot into style-runs in
# C++ (one flat int array per JNI hand-off) and is compiled INTO the vendored
# vt_native target via target_sources — the vendored files themselves stay
# byte-identical to the upstream commit (VENDOR.md rule 1), and only the
# upstream PUBLIC API (apex/vt/vt_engine.h) is consumed. Rule-2 territory:
# host glue maintained in THIS repo, same as the Kotlin wrapper.
# ═══════════════════════════════════════════════════════════════════════════
target_sources(vt_native PRIVATE
  src/main/cpp/fastpath/vt_runs.cpp
  src/main/cpp/fastpath/vt_jni_fastpath.cpp)

