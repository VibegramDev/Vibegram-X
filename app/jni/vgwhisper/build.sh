#!/bin/bash
# Builds libvgwhisper*.so (whisper.cpp JNI for voice transcription) and copies them into app/src/main/jniLibs/arm64-v8a.
# Three variants of the same code; WhisperNative.loadFastest picks the fastest one the CPU supports.
set -e
cd "$(dirname "$0")"
NDK=$(ls -d ~/Library/Android/sdk/ndk/30* | tail -1)
CM=~/Library/Android/sdk/cmake/3.22.1/bin/cmake
OUT=../../src/main/jniLibs/arm64-v8a
build() { # name arch
  rm -rf build/$1
  $CM -S . -B build/$1 -G Ninja -DCMAKE_MAKE_PROGRAM=$HOME/Library/Android/sdk/cmake/3.22.1/bin/ninja \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 \
    -DCMAKE_BUILD_TYPE=Release -DVG_LIB_NAME=$1 -DGGML_CPU_ARM_ARCH=$2 >/dev/null
  $CM --build build/$1 -j 10 >/dev/null
  $NDK/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded build/$1/lib$1.so
  cp build/$1/lib$1.so $OUT/
  ls -la $OUT/lib$1.so
}
build vgwhisper armv8-a
build vgwhisper_v82 armv8.2-a+dotprod+fp16
build vgwhisper_v86 armv8.2-a+dotprod+fp16+i8mm
