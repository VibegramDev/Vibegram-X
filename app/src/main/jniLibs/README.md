# Prebuilt native libraries

Speech recognition libraries, arm64-v8a only. They are not built by Gradle.

| File | Version | Source | sha256 |
|---|---|---|---|
| `libsherpa-onnx-jni.so` | sherpa-onnx 2.0.1 (git `af2d8d81`, Oct 26 2026) | `jniLibs/arm64-v8a/` of the official release archive [sherpa-onnx-v2.0.1-android.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2), unchanged | `5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575` |
| `libonnxruntime.so` | ONNX Runtime 1.28.2 | same archive, unchanged | `33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1` |
| `libvgwhisper.so`, `libvgwhisper_v82.so`, `libvgwhisper_v86.so` | whisper.cpp 1.9.5 | built from [app/jni/vgwhisper](/app/jni/vgwhisper) by [scripts/stt/build_vgwhisper.sh](/scripts/stt/build_vgwhisper.sh) | depends on the NDK |

The archive's sha256 is `39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330`.
The library versions are reported by `VersionInfo` (`getVersionStr2`, `getGitSha12`, `getGitDate2`) and by the strings in the files.

`libsherpa-onnx-jni.so` reads the fields of the Kotlin config classes by name, so
`app/src/main/kotlin/com/k2fsa/sherpa/onnx/*.kt` must come from the same sherpa-onnx version
(`sherpa-onnx/kotlin-api/` of tag v2.0.1). Update the libraries and the Kotlin files together.

The three `libvgwhisper` variants are for different CPU generations: `armv8-a`, `armv8.2-a+dotprod+fp16`
and `armv8.6-a+dotprod+fp16+i8mm`. `WhisperNative.loadFastest` picks one by the features in `/proc/cpuinfo`.
