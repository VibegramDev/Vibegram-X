/*
 * Vibegram X: JNI bridge to whisper.cpp (libvgwhisper / libvgwhisper_v82 / libvgwhisper_v86).
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;


import java.io.BufferedReader;
import java.io.FileReader;

public final class WhisperNative {
  private static boolean loaded;

  private WhisperNative () { }

  @Keep
  public interface ProgressListener {
    /** @param progress 0..100 within the current call to {@link #transcribe} */
    @Keep
    void onProgress (int progress);
    /** A finished segment of text (UTF-8), sent while the rest of the audio is still processed. */
    @Keep
    void onSegment (byte[] text);
    /** Text of the segment being decoded right now (UTF-8), updated several times a second. */
    @Keep
    void onLive (byte[] text);
  }

  /** Picks the fastest build the CPU supports: ARMv8.6 int8 matrix multiply, ARMv8.2 dot product + fp16, or plain ARMv8. */
  public static synchronized void load () {
    if (!loaded) {
      loadFastest("vgwhisper");
      loaded = true;
    }
  }

  /** Loads {@code <base>_v86}, {@code <base>_v82} or {@code <base>}: the fastest one this CPU can run. */
  static void loadFastest (String base) {
    String features = "";
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("Features")) {
          features = line + " ";
          break;
        }
      }
    } catch (Throwable ignored) { }
    boolean v82 = features.contains(" asimddp ") && features.contains(" fphp ") && features.contains(" asimdhp ");
    boolean v86 = v82 && features.contains(" i8mm ");
    String[] candidates = v86 ? new String[] {base + "_v86", base + "_v82", base} : v82 ? new String[] {base + "_v82", base} : new String[] {base};
    UnsatisfiedLinkError lastError = null;
    for (String name : candidates) {
      try {
        System.loadLibrary(name);
        android.util.Log.i("VgStt", "loaded " + name);
        return;
      } catch (UnsatisfiedLinkError e) {
        lastError = e;
      }
    }
    throw lastError;
  }

  public static native long init (String modelPath, boolean flashAttn);
  public static native void free (long ptr);
  public static native void cancel (long ptr);
  public static native void resetCancel (long ptr);
  /** @return UTF-8 "language\ntext" or null when failed or cancelled */
  public static native byte[] transcribe (long ptr, float[] samples, int threads, int beamSize, @Nullable String prompt, @Nullable ProgressListener listener);
}
