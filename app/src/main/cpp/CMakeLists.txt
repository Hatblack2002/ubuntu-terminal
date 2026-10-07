cmake_minimum_required(VERSION 3.22.1)

project(ubuntu_terminal LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =====================================================================
# libterminal.so — native integration layer
# =====================================================================
#
# This library provides JNI bindings for:
#   1. Spawning child processes (proot + ubuntu bash) attached to a PTY
#   2. Reading stdout/stderr from the PTY master fd
#   3. Writing stdin to the PTY master fd
#   4. Resizing the PTY window (rows/cols)
#   5. Sending signals (SIGINT, SIGHUP, etc.)
#   6. Waiting for process exit
#
# Per the project spec (section 15):
#   libterminal.so is NOT Ubuntu. It is only the native glue required
#   to attach the Java/Kotlin UI to a real Linux process running Ubuntu.
# =====================================================================

add_library(terminal SHARED
    terminal_jni.cpp
    pty_compat.c
    process_utils.c
)

target_include_directories(terminal PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Android's bionic libc already provides openpty() since API 23
# via <pty.h>. We link against libutil which exposes forkpty() too.
target_link_libraries(terminal
    log
    android
)

# Strip symbols in release builds to keep .so small
target_compile_options(terminal PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
    $<$<CONFIG:Release>:-O2 -fvisibility=hidden>
)

target_link_options(terminal PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections,--strip-all>
)
