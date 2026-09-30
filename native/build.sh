#!/bin/bash
# 语燕输入法 Rime 核心构建脚本
#
# 用法:
#   ./build.sh host      # 构建 Linux 验证工具 yuyan_test
#   ./build.sh android   # 构建 libs/arm64-v8a/libyuyanime.so（需要 ANDROID_NDK_HOME）
#
# 依赖源码固定版本（与 Trime 同文输入法所用版本一致，已验证兼容）:
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-host}"
BUILD_DIR="build-${MODE}"

# ---- 固定版本 ----
LIBRIME_SHA=33e78140250125871856cdc5b42ddc6a5fcd3cd4      # v1.17.0
LIBRIME_LUA_SHA=68f9c364a2d25a04c7d4794981d7c796b05ab627
LIBRIME_LUA_DEPS_SHA=9c53b362229766a97b83683b9541c46679118a90
LIBRIME_OCTAGRAM_SHA=bd12863f45fbbd5c7db06d5ec8be8987b10253bf
OPENCC_SHA=907bfcbbd3aae86ff04bc8eaca67c8af03108ddf
SNAPPY_SHA=32ded457c0b1fe78ceb8397632c416568d6714a0

clone_pin() {
  local url="$1" sha="$2" dir="$3"
  if [ -d "$dir" ] && [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then
    echo "== $dir 已存在，跳过克隆"
    return
  fi
  echo "== 克隆 $url @ ${sha:0:8}"
  git clone --depth 1 --no-single-branch "$url" "$dir" 2>/dev/null || git clone "$url" "$dir"
  git -C "$dir" fetch --depth 1 origin "$sha"
  git -C "$dir" checkout -q "$sha"
}

ensure_sources() {
  clone_pin https://github.com/rime/librime.git "$LIBRIME_SHA" librime
  # librime 的 vendored 依赖（glog/leveldb/marisa-trie/opencc/yaml-cpp）
  if [ -f "librime/.git" ] || [ -d "librime/.git" ]; then
    if [ ! -d "librime/deps/yaml-cpp" ] || [ -z "$(ls -A librime/deps/yaml-cpp 2>/dev/null)" ]; then
      echo "== 初始化 librime 子模块"
      git -C librime submodule update --init --recursive --depth 1
    fi
  fi
  clone_pin https://github.com/hchunhui/librime-lua.git "$LIBRIME_LUA_SHA" librime-lua
  clone_pin https://github.com/hchunhui/librime-lua.git "$LIBRIME_LUA_DEPS_SHA" librime-lua-deps
  clone_pin https://github.com/lotem/librime-octagram.git "$LIBRIME_OCTAGRAM_SHA" librime-octagram
  clone_pin https://github.com/BYVoid/OpenCC.git "$OPENCC_SHA" OpenCC
  clone_pin https://github.com/google/snappy.git "$SNAPPY_SHA" snappy

  # Android 下 lua 的 fseek 兼容性补丁（来自 Trime）
  if [ ! -f librime-lua-deps/.yuyan_patched ]; then
    echo "== 应用 lua.patch"
    git -C librime-lua-deps apply "$(pwd)/patches/lua.patch"
    touch librime-lua-deps/.yuyan_patched
  fi
}

configure_and_build() {
  local extra_args=("$@")
  cmake -S . -B "$BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -GNinja \
    "${extra_args[@]}"
  cmake --build "$BUILD_DIR" -j "${JOBS:-8}"
}

ensure_sources

case "$MODE" in
  host)
    configure_and_build
    echo "== 完成: ${BUILD_DIR}/src/yuyan_test"
    ;;
  android)
    : "${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME 指向 NDK 目录}"
    configure_and_build \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-23 \
      -DANDROID_STL=c++_static
    mkdir -p ../libs/arm64-v8a
    cp "$BUILD_DIR/src/libyuyanime.so" ../libs/arm64-v8a/libyuyanime.so
    echo "== 完成: ../libs/arm64-v8a/libyuyanime.so"
    ;;
  *)
    echo "unknown mode: $MODE (host|android)" >&2
    exit 2
    ;;
esac
