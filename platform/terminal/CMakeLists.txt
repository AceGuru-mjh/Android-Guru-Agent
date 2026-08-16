cmake_minimum_required(VERSION 3.22.1)
project("apex_terminal" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 优化选项
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0")

add_library(apex_terminal SHARED
    src/main/cpp/pty_engine.cpp
    src/main/cpp/pty_session.cpp
    src/main/cpp/jni_bridge.cpp
)

target_include_directories(apex_terminal PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

target_link_libraries(apex_terminal
    android
    log
)
