#!/usr/bin/env bash
# Builds app/src/main/jniLibs/arm64-v8a/libvgllama{,_v82,_v86}.so: app/jni/vgllama on top of llama.cpp.
# LlmNative.load picks the library by the CPU features in /proc/cpuinfo (the same way as for whisper).
#
# Usage: scripts/stt/build_vgllama.sh [--host]
#   --host  build vgllama_test for this computer instead (prints the model's answer to a prompt file), into $WORK/host
# Environment: ANDROID_NDK_HOME (default: $ANDROID_HOME/ndk/<version.ndk_primary>), VGLLAMA_WORK (build directory),
#   VGLLAMA_OUT (where to put the Android libraries, default app/src/main/jniLibs/arm64-v8a).
set -euo pipefail

LLAMA_VERSION=b11960
LLAMA_SHA256=2b37ffa871595f90b94164f6c510ad9a34bd7f94da21557cc9878587e163af37
# llama.cpp needs posix_madvise (API 23); LlmNative does not load the library on older Android
ANDROID_API=23

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/app/jni/vgllama"
OUT="${VGLLAMA_OUT:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
WORK="${VGLLAMA_WORK:-${TMPDIR:-/tmp}/vgllama-build}"
mkdir -p "$WORK" "$OUT"

sha256 () {
  if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d ' ' -f 1; else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}

ARCHIVE="$WORK/llama.cpp-$LLAMA_VERSION.tar.gz"
if [ ! -f "$ARCHIVE" ] || [ "$(sha256 "$ARCHIVE")" != "$LLAMA_SHA256" ]; then
  curl -fL --retry 3 -o "$ARCHIVE" "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$LLAMA_VERSION.tar.gz"
fi
ACTUAL_SHA256="$(sha256 "$ARCHIVE")"
if [ "$ACTUAL_SHA256" != "$LLAMA_SHA256" ]; then
  echo "llama.cpp $LLAMA_VERSION: sha256 $ACTUAL_SHA256, expected $LLAMA_SHA256" >&2
  exit 1
fi
LLAMA_DIR="$WORK/llama.cpp-$LLAMA_VERSION"
rm -rf "$LLAMA_DIR"
tar -xzf "$ARCHIVE" -C "$WORK"

if [ "${1:-}" = "--host" ]; then
  cmake -S "$SOURCE" -B "$WORK/host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$LLAMA_DIR"
  cmake --build "$WORK/host" --target vgllama_test
  echo "$WORK/host/vgllama_test"
  exit 0
fi

NDK_VERSION="$(sed -n 's/^version.ndk_primary=//p' "$ROOT/version.properties")"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/ndk/$NDK_VERSION}"
if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "NDK $NDK_VERSION not found at $NDK (set ANDROID_NDK_HOME)" >&2
  exit 1
fi
STRIP="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)"

build () {
  local name="$1" arch="$2"
  local dir="$WORK/android-$name"
  rm -rf "$dir"
  cmake -S "$SOURCE" -B "$dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_DIR="$LLAMA_DIR" \
    -DVGLLAMA_NAME="$name" \
    -DGGML_CPU_ARM_ARCH="$arch"
  cmake --build "$dir" --target vgllama
  "$STRIP" --strip-all -o "$OUT/lib$name.so" "$dir/lib$name.so"
  if strings "$OUT/lib$name.so" | grep -q -e "$HOME" -e "$WORK"; then
    echo "lib$name.so contains build paths" >&2
    exit 1
  fi
  rm -rf "$dir"
  echo "$OUT/lib$name.so"
}

build vgllama armv8-a
build vgllama_v82 armv8.2-a+dotprod+fp16
build vgllama_v86 armv8.6-a+dotprod+fp16+i8mm
