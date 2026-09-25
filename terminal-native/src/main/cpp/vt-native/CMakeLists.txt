cmake_minimum_required(VERSION 3.16)
project(apex-vt-native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Options:
#   APEX_VT_BUILD_TESTS — host test suite + benchmark (default ON when this is the top project)
#   APEX_VT_BUILD_FUZZ  — deterministic fuzz harness (default follows TESTS)
#   APEX_VT_BUILD_JNI   — Android JNI shared library "vt_native"
#                         (auto-ON when building for Android)
if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
  set(APEX_VT_DEFAULT_TESTS ON)
else()
  set(APEX_VT_DEFAULT_TESTS OFF)
endif()

option(APEX_VT_BUILD_TESTS "Build the host test suite" ${APEX_VT_DEFAULT_TESTS})
option(APEX_VT_BUILD_FUZZ "Build the deterministic fuzz harness" ${APEX_VT_DEFAULT_TESTS})
option(APEX_VT_BUILD_JNI "Build the Android JNI bridge (libvt_native.so)"
       ${ANDROID})

# ── Core static library (host + Android) ──────────────────────────────────
add_library(apex-vt-core STATIC
  src/vt_style.cpp
  src/vt_width.cpp
  src/vt_screen.cpp
  src/vt_engine.cpp
)
target_include_directories(apex-vt-core PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src>
  $<INSTALL_INTERFACE:include>
)

if(MSVC)
  target_compile_options(apex-vt-core PRIVATE /W4)
else()
  target_compile_options(apex-vt-core PRIVATE -Wall -Wextra -Wpedantic -Wshadow)
endif()

# ── Android JNI shared library ────────────────────────────────────────────
if(APEX_VT_BUILD_JNI)
  add_library(vt_native SHARED src/jni/vt_jni.cpp)
  target_link_libraries(vt_native PRIVATE apex-vt-core)
  if(ANDROID)
    target_link_libraries(vt_native PRIVATE log)
    # Android 15 16KB page alignment (harmless on older devices).
    target_link_options(vt_native PRIVATE "-Wl,-z,max-page-size=16384")
  endif()
endif()

# ── Host tests ────────────────────────────────────────────────────────────
if(APEX_VT_BUILD_TESTS)
  add_executable(vt_tests
    tests/test_main.cpp
    tests/test_parser.cpp
    tests/test_screen.cpp
    tests/test_engine.cpp
    tests/test_perf.cpp
  )
  target_link_libraries(vt_tests PRIVATE apex-vt-core)
  enable_testing()
  add_test(NAME vt_tests COMMAND vt_tests)
endif()

if(APEX_VT_BUILD_FUZZ)
  add_executable(vt_fuzz tests/fuzz_feed.cpp)
  target_link_libraries(vt_fuzz PRIVATE apex-vt-core)
endif()
