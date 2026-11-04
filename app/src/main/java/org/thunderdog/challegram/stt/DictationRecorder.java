/*
 * This file is a part of Vibegram X
 */
package org.thunderdog.challegram.stt;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;

import java.util.ArrayList;
import java.util.List;

/**
 * Vibegram: microphone capture for dictation. Unlike {@link org.thunderdog.challegram.helper.Recorder} nothing is
 * encoded or written to disk: 16 kHz mono PCM, the input format of the transcription models, is kept in memory
 * and handed to {@link SttManager#transcribeSamples} when the recording is finished.
 */
public final class DictationRecorder {
  public interface Listener {
    /** UI thread. Same scale as the voice recorder: RMS of the louder samples. */
    void onAmplitude (float amplitude);
    /** UI thread. The microphone could not be opened or stopped delivering audio. */
    void onFail ();
  }

  /** The models take 16 kHz; devices without it record at 48 kHz and are downsampled by 3. */
  private static final int TARGET_RATE = AudioDecoder.SAMPLE_RATE;
  private static final int FALLBACK_RATE = 48000;
  /** Recognition of very long audio takes too long to wait for in the composer. */
  public static final long MAX_DURATION_MS = 10 * 60 * 1000;

  private static DictationRecorder instance;

  public static DictationRecorder instance () {
    if (instance == null) {
      instance = new DictationRecorder();
    }
    return instance;
  }

  private final Object lock = new Object();
  private @Nullable Thread thread;
  private volatile boolean running;
  /** Bumped on every start: a stopped capture thread never touches the next session's state. */
  private int session;
  private final List<short[]> chunks = new ArrayList<>();
  private int sampleCount;
  private int sampleRate;

  private DictationRecorder () { }

  public boolean isRecording () {
    synchronized (lock) {
      return thread != null;
    }
  }

  public void start (Listener listener) {
    synchronized (lock) {
      if (thread != null) {
        return;
      }
      final int session = ++this.session;
      chunks.clear();
      sampleCount = 0;
      running = true;
      thread = new Thread(() -> capture(session, listener), "VgDictation");
      thread.start();
    }
  }

  /** Stops the capture and returns the recorded 16 kHz samples in [-1, 1] (null when cancelled or nothing was recorded). */
  public @Nullable float[] stop (boolean cancel) {
    Thread thread;
    synchronized (lock) {
      thread = this.thread;
      this.thread = null;
      running = false;
    }
    if (thread != null) {
      try {
        thread.join(1000);
      } catch (InterruptedException ignored) { }
    }
    synchronized (lock) {
      float[] result = cancel || sampleCount == 0 ? null : collect();
      chunks.clear();
      sampleCount = 0;
      return result;
    }
  }

  private float[] collect () {
    int factor = sampleRate == FALLBACK_RATE ? 3 : 1;
    float[] out = new float[sampleCount / factor];
    int outIndex = 0, phase = 0;
    float acc = 0;
    for (short[] chunk : chunks) {
      for (short s : chunk) {
        acc += s;
        if (++phase == factor) {
          if (outIndex < out.length) {
            out[outIndex++] = acc / factor / 32768f;
          }
          acc = 0;
          phase = 0;
        }
      }
    }
    return out;
  }

  @SuppressLint("MissingPermission") // RecordAudioVideoController requests RECORD_AUDIO before recording
  private void capture (int session, Listener listener) {
    AudioRecord record = null;
    try {
      record = open(TARGET_RATE);
      if (record == null) {
        record = open(FALLBACK_RATE);
      }
      if (record == null) {
        throw new IllegalStateException("AudioRecord is not available");
      }
      record.startRecording();
      if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        throw new IllegalStateException("AudioRecord did not start");
      }
      short[] buffer = new short[record.getSampleRate() / 20]; // 50 ms
      final int maxSamples = (int) (MAX_DURATION_MS / 1000 * record.getSampleRate());
      while (running) {
        int read = record.read(buffer, 0, buffer.length);
        if (read < 0) {
          throw new IllegalStateException("AudioRecord.read: " + read);
        }
        if (read == 0) {
          continue;
        }
        double sum = 0;
        int counted = 0;
        for (int i = 0; i < read; i++) {
          short s = buffer[i];
          if (s > 2500) {
            sum += s * s;
          }
          counted++;
        }
        final float amplitude = (float) Math.sqrt(sum / Math.max(1, counted));
        synchronized (lock) {
          if (this.session != session || !running) {
            break;
          }
          if (sampleCount < maxSamples) {
            short[] chunk = new short[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            chunks.add(chunk);
            sampleCount += read;
          }
        }
        UI.post(() -> {
          if (this.session == session && running) {
            listener.onAmplitude(amplitude);
          }
        });
      }
    } catch (Throwable t) {
      Log.e("Dictation: recording failed", t);
      boolean notify;
      synchronized (lock) {
        notify = this.session == session && running;
      }
      if (notify) {
        UI.post(listener::onFail);
      }
    } finally {
      if (record != null) {
        try {
          record.stop();
        } catch (Throwable ignored) { }
        record.release();
      }
    }
  }

  @SuppressLint("MissingPermission")
  private @Nullable AudioRecord open (int rate) {
    int minSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minSize <= 0) {
      return null;
    }
    try {
      // VOICE_RECOGNITION: no AGC or voice-call processing, which hurts the recognition models.
      AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minSize * 4, rate / 5 * 2));
      if (record.getState() != AudioRecord.STATE_INITIALIZED) {
        record.release();
        return null;
      }
      synchronized (lock) {
        sampleRate = rate;
      }
      return record;
    } catch (Throwable t) {
      Log.w("Dictation: AudioRecord(%d) failed", t, rate);
      return null;
    }
  }
}
