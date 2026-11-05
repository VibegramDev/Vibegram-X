# Third-party components and resources

* [TDLib](https://github.com/tdlib/td): used for interacting with Telegram API and caching data
* [LevelDB](https://github.com/google/leveldb): used for storing local data and settings

* [libtgvoip](https://github.com/grishka/libtgvoip) & java layer by the same author: used for VoIP

* [tlottie](https://github.com/dkaraush/tlottie): used for animated stickers and emoji, licensed under MIT
* [lz4](https://github.com/lz4/lz4): used for storing cached frames of animated stickers
* [WebP](https://developers.google.com/speed/webp): used for showing static stickers on Android versions below 4.4

* [ExoPlayer](https://github.com/google/ExoPlayer): used for audio and video playback on supported devices
* [libvpx](https://chromium.googlesource.com/webm/libvpx): vp9 ExoPlayer extension
* [flac](https://xiph.org/flac): flac ExoPlayer extension
* [ffmpeg](http://ffmpeg.org): used for GIF playback & ffmpeg ExoPlayer extension
* [libyuv](https://chromium.googlesource.com/libyuv/libyuv): used to convert pixel format while decoding GIFs

* [opus](https://gitlab.xiph.org/xiph/opus): used for voice messages
* [opusfile](https://gitlab.xiph.org/xiph/opusfile)
* [ogg](https://gitlab.xiph.org/xiph/ogg)

* [AndroidX](https://developer.android.com/jetpack/androidx): various basic Android stuff + CameraX
* [Play Core](https://developer.android.com/reference/com/google/android/play/core/release-notes): used for in-app update system
* [Google Play Services](https://developers.google.com/android/guides/releases): maps and location services
* [Firebase Messaging](https://firebase.google.com/support/release-notes/android): notifications
* [YouTube](https://developers.google.com/youtube/android/player/): YouTube player API

* [Transcoder](https://github.com/natario1/Transcoder): used to compress video files
* [ShortcutBadger](https://github.com/leolin310148/ShortcutBadger): used to show badge near app shortcut
* [ReLinker](https://github.com/KeepSafe/ReLinker): used to properly load native shared libraries
* [Konfetti](https://github.com/DanielMartinus/Konfetti): confetti animation
* [ZXing](https://github.com/zxing/zxing/): QR scanning on devices where Google Play Services are not available

* [Google's Material Icons](https://material.io/resources/icons): most of application icons
* [Material Design Icons](https://materialdesignicons.com): various icons by [Austin Andrews](https://twitter.com/Templarian), [Yasmina Lembachar](https://twitter.com/belledeboheme), [Haley Halcyon](https://twitter.com/itsspelledhaley), [Michael Richins](https://twitter.com/MrGrigri)

* [Klaxon](https://github.com/cbeust/klaxon): JSON parser used in build scripts

* [Diff-Match-Patch](http://code.google.com/p/google-diff-match-patch/): [source code](/app/src/main/java/org/thunderdog/challegram/core/DiffMatchPatch.java)
* [RTL View Pager](https://github.com/diego-gomez-olvera/RtlViewPager): [source code](/app/src/main/java/org/thunderdog/challegram/widget/rtl/RtlViewPager.java)
* [svg-android](https://github.com/japgolly/svg-android): [source code](/app/src/main/java/org/thunderdog/challegram/loader/svg)

## Speech recognition

Voice messages are transcribed on the device. The native libraries are prebuilt and committed to
`app/src/main/jniLibs/arm64-v8a/`; [README](/app/src/main/jniLibs/README.md) lists versions, sources and checksums.

* [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.5 with [ggml](https://github.com/ggml-org/ggml), licensed under
  [MIT](https://github.com/ggml-org/whisper.cpp/blob/v1.9.5/LICENSE): statically linked into `libvgwhisper.so`,
  `libvgwhisper_v82.so` and `libvgwhisper_v86.so`. The JNI bridge is [our code](/app/jni/vgwhisper), built by
  [scripts/stt/build_vgwhisper.sh](/scripts/stt/build_vgwhisper.sh).
* [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v2.0.1, licensed under
  [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE): `libsherpa-onnx-jni.so` from the official release
  and the Kotlin API ([source code](/app/src/main/kotlin/com/k2fsa/sherpa/onnx)).
* [ONNX Runtime](https://github.com/microsoft/onnxruntime) 1.28.2, licensed under
  [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE): `libonnxruntime.so`, shipped with the sherpa-onnx release.

## Telegram for Android (WEB proxy)

The WEB proxy transport (`app/src/main/java/org/thunderdog/challegram/proxy/`) and the format of `webproxy` links are based on
the WEB proxy implementation of [Telegram for Android](https://github.com/DrKLO/Telegram) (licensed under GNU GPL v. 2 or later).
