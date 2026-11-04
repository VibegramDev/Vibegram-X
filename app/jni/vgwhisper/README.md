# vgwhisper

JNI wrapper around [whisper.cpp](https://github.com/ggml-org/whisper.cpp) for on-device voice transcription
(`org.thunderdog.challegram.stt.WhisperNative`). Not part of the Gradle build: the prebuilt libraries are
committed to `app/src/main/jniLibs/arm64-v8a/`. Rebuild them after changing anything here:

```
bash app/jni/vgwhisper/build.sh
```

- `vgwhisper.c` — the JNI functions.
- `CMakeLists.txt` — builds whisper.cpp statically into one `.so`, CPU only.
- `build.sh` — three variants: `libvgwhisper` (armv8-a), `_v82` (dotprod, fp16), `_v86` (+ i8mm).
- `whisper.cpp-1.9.5/` — whisper.cpp v1.9.5 (MIT, see its LICENSE), trimmed to what the CPU build needs
  (no examples, tests, bindings, models, GPU backends), with one change:
  `whisper-encode-once.patch`.

## whisper-encode-once.patch

With automatic language detection, stock `whisper_full_with_state` runs the encoder over the first 30 s twice:
once to detect the language, then again for the transcription. The encoder takes ~90% of the time, so every
Whisper model was about 2x slower than needed. The patch reuses the encoder output of the language detection
for the first window. The text is the same as with the language set explicitly.
