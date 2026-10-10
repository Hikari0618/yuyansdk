# 语燕输入法 Rime 核心构建（librime + librime-lua + librime-octagram）
#
# 构建配方基于 Trime（同文输入法）的 app/src/main/jni/CMakeLists.txt
# (SPDX-FileCopyrightText: 2015 - 2024 Rime community, GPL-3.0-or-later)
# 修改：目标库改名为 yuyanime，JNI 桥改为匹配 YuyanIme 的 Kotlin 原生接口。

cmake_minimum_required(VERSION 3.18.0)

project(yuyanime VERSION 1.0.0)

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

# Android 15+ 的 16 KB 页设备要求所有 LOAD 段 16 KB 对齐（NDK r27 默认仍是 4 KB，
# 真机安装时会提示「以下库未进行 16 KB 对齐」）。只影响 Android 构建，host 构建不受影响。
if(ANDROID)
  # NDK r27 及更低版本要同时给这两个标志才是完整的 16KB 对齐：
  #   max-page-size     → LOAD 段 p_align 变成 0x4000（readelf 看着就合规了）
  #   common-page-size  → 数据段/RELRO 段尾部也按 16KB 对齐
  # 只给前者时 RELRO 段不达标：(VirtAddr + MemSiz) % 0x4000 = 0x2000 ≠ 0，
  # 官方文档明确这会「在 16KB 设备上运行时段错误崩溃」（NDK r28+ 默认两者都开，无需设置）。
  add_link_options("LINKER:-z,max-page-size=16384")
  add_link_options("LINKER:-z,common-page-size=16384")
endif()

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(OpenCC)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(src)
