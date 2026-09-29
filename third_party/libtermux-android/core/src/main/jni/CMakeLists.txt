# Modified for mobby (2026-09-29): compile the process-group pipe executor.
# LibTermux-Android
# Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# Author: cybernahid-dev (Systems Developer)
# Project: https://github.com/AeonCoreX-Lab/libtermux-android

cmake_minimum_required(VERSION 3.22.1)
project("libtermux_jni" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Compiler flags
add_compile_options(
    -Wall -Wextra -Wno-unused-parameter
    -O2
    -fstack-protector-strong
    -fvisibility=hidden
)

# Shared library
add_library(
    libtermux_jni
    SHARED
    pipe_process.cpp
    termux_jni.cpp
    pty_helper.cpp
    process_helper.cpp
)

# System libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    libtermux_jni
    ${log-lib}
    ${android-lib}
)

target_include_directories(libtermux_jni PRIVATE .)
