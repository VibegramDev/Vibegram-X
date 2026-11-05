#!/usr/bin/env bash
# Builds app/src/main/jniLibs/arm64-v8a/libvgwhisper{,_v82,_v86}.so: app/jni/vgwhisper on top of whisper.cpp.
# WhisperNative.loadFastest picks the library by the CPU features in /proc/cpuinfo.
#
# Usage: scripts/stt/build_vgwhisper.sh [--host]
#   --host  build libvgwhisper for this computer instead (desktop JDK test), into $WORK/host
# Environment: ANDROID_NDK_HOME (default: $ANDROID_HOME/ndk/<version.ndk_primary>), VGWHISPER_WORK (build directory),
#   VGWHISPER_OUT (where to put the Android libraries, default app/src/main/jniLibs/arm64-v8a).
set -euo pipefail

WHISPER_VERSION=1.9.5
WHISPER_SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
# The lowest API of the primary NDK (ndkVersionToMinSdk): the library must load on every flavor that ships it
ANDROID_API=21

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/app/jni/vgwhisper"
OUT="${VGWHISPER_OUT:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
WORK="${VGWHISPER_WORK:-${TMPDIR:-/tmp}/vgwhisper-build}"
mkdir -p "$WORK" "$OUT"

sha256 () {
  if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d ' ' -f 1; else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}

ARCHIVE="$WORK/whisper.cpp-$WHISPER_VERSION.tar.gz"
if [ ! -f "$ARCHIVE" ] || [ "$(sha256 "$ARCHIVE")" != "$WHISPER_SHA256" ]; then
  curl -fL --retry 3 -o "$ARCHIVE" "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$WHISPER_VERSION.tar.gz"
fi
ACTUAL_SHA256="$(sha256 "$ARCHIVE")"
if [ "$ACTUAL_SHA256" != "$WHISPER_SHA256" ]; then
  echo "whisper.cpp $WHISPER_VERSION: sha256 $ACTUAL_SHA256, expected $WHISPER_SHA256" >&2
  exit 1
fi
WHISPER_DIR="$WORK/whisper.cpp-$WHISPER_VERSION"
rm -rf "$WHISPER_DIR"
tar -xzf "$ARCHIVE" -C "$WORK"

if [ "${1:-}" = "--host" ]; then
  cmake -S "$SOURCE" -B "$WORK/host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DWHISPER_DIR="$WHISPER_DIR"
  cmake --build "$WORK/host" --target vgwhisper
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
    -DWHISPER_DIR="$WHISPER_DIR" \
    -DVGWHISPER_NAME="$name" \
    -DGGML_CPU_ARM_ARCH="$arch"
  cmake --build "$dir" --target vgwhisper
  "$STRIP" --strip-all -o "$OUT/lib$name.so" "$dir/lib$name.so"
  if strings "$OUT/lib$name.so" | grep -q -e "$HOME" -e "$WORK"; then
    echo "lib$name.so contains build paths" >&2
    exit 1
  fi
  echo "$OUT/lib$name.so"
}

build vgwhisper armv8-a
build vgwhisper_v82 armv8.2-a+dotprod+fp16
build vgwhisper_v86 armv8.6-a+dotprod+fp16+i8mm
