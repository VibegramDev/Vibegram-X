/*
 * Vibegram X: JNI bridge to llama.cpp (libvgllama / libvgllama_v82 / libvgllama_v86, built by
 * scripts/stt/build_vgllama.sh), used by «Enhanced transcription» to correct the recognized text.
 */
package org.thunderdog.challegram.stt;

import android.os.Build;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

public final class LlmNative {
  private static boolean loaded;

  private LlmNative () { }

  @Keep
  public interface TokenListener {
    /** @param generated number of tokens generated so far */
    @Keep
    void onToken (int generated);
  }

  /** The library is built for API 23 (llama.cpp needs posix_madvise) and arm64 only, like the recognizers. */
  public static boolean isSupported () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && SttManager.isSupported();
  }

  public static synchronized void load () {
    if (!loaded) {
      WhisperNative.loadFastest("vgllama");
      loaded = true;
    }
  }

  /** @return handle of the loaded model, 0 on failure */
  public static native long init (String modelPath);
  public static native void free (long ptr);
  public static native void cancel (long ptr);
  public static native void resetCancel (long ptr);
  /**
   * Greedy generation (always the most probable token, so the same input gives the same output).
   * @param prompt UTF-8 prompt with the chat template applied
   * @return UTF-8 generated text, or null when failed or cancelled
   */
  public static native @Nullable byte[] generate (long ptr, byte[] prompt, int maxTokens, int threads, @Nullable TokenListener listener);
}
