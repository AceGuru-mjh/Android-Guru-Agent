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
