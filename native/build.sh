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
    # OpenCC 的数据（*.txt → *.ocd2）要用【宿主机】的 opencc_dict 生成
    # （ocd2 是数据格式、与目标架构无关；OpenCC 的 CMake 本来就预期 PATH 里有这个 host 工具）。
    # 不加这段会报：/bin/sh: 1: opencc_dict: not found → ninja 停在数据生成这一步，
    # libyuyanime.so 不会重新链接，表现为「改了 native 代码但 .so 时间戳没变、新符号没进包」。
    # 该工具由 ./build.sh host 产出（build-host/OpenCC/src/tools/opencc_dict）。
    HOST_OPENCC_TOOLS="$(pwd)/build-host/OpenCC/src/tools"
    if [ -x "$HOST_OPENCC_TOOLS/opencc_dict" ]; then
      export PATH="$HOST_OPENCC_TOOLS:$PATH"
    else
      echo "⚠ 没找到宿主机 opencc_dict（$HOST_OPENCC_TOOLS）—— 请先跑 ./build.sh host" >&2
    fi
    configure_and_build \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-23 \
      -DANDROID_STL=c++_static
    mkdir -p ../libs/arm64-v8a
    cp "$BUILD_DIR/src/libyuyanime.so" ../libs/arm64-v8a/libyuyanime.so
    # 必须 strip：CMake 产物带 debug_info（约 74MB），而 AGP 打包 jniLibs 时【不会】strip
    # （实测：上一版 APK 里的 .so 与 libs/ 里的字节数完全一致 → 打包前就是 strip 过的 5.6MB）。
    # 不 strip 会让 APK 直接涨约 70MB。llvm-strip 保留 .dynsym：
    # JNI 符号、16KB 页对齐（p_align=0x4000）与 RELRO 都不受影响（strip 后已实测校验）。
    STRIP="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    if [ -x "$STRIP" ]; then
      "$STRIP" --strip-unneeded ../libs/arm64-v8a/libyuyanime.so
      echo "== 已 strip: $(stat -c%s ../libs/arm64-v8a/libyuyanime.so) bytes"
    else
      echo "⚠ 未找到 llvm-strip（$STRIP）—— .so 未 strip，APK 会大 ~70MB" >&2
    fi
    echo "== 完成: ../libs/arm64-v8a/libyuyanime.so"
    ;;
  *)
    echo "unknown mode: $MODE (host|android)" >&2
    exit 2
    ;;
esac
