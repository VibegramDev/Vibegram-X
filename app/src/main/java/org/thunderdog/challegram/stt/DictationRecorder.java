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
 * Nothing here blocks the UI thread: {@link #stop} returns at once and the samples arrive in a callback.
 */
public final class DictationRecorder {
  public interface Listener {
    /** UI thread. Same scale as the voice recorder: RMS of the louder samples. */
    void onAmplitude (float amplitude);
    /** UI thread. The microphone could not be opened or stopped delivering audio. */
    void onFail ();
    /** UI thread. {@link #MAX_DURATION_MS} was recorded: the recording should be finished. */
    void onMaxDurationReached ();
  }

  public interface ResultCallback {
    /** UI thread. @param samples 16 kHz samples in [-1, 1], null when cancelled or nothing was recorded */
    void onRecorded (@Nullable float[] samples);
  }

  /** The models take 16 kHz; devices without it record at 48 kHz and are downsampled by 3. */
  private static final int TARGET_RATE = AudioDecoder.SAMPLE_RATE;
  private static final int FALLBACK_RATE = 48000;
  /** Recognition of very long audio takes too long to wait for in the composer. */
  public static final long MAX_DURATION_MS = 10 * 60 * 1000;

  private static volatile DictationRecorder instance;

  public static DictationRecorder instance () {
    if (instance == null) {
      synchronized (DictationRecorder.class) {
        if (instance == null) {
          instance = new DictationRecorder();
        }
      }
    }
    return instance;
  }

  /**
   * One recording. Owned by its capture thread: a new recording can start while the previous thread is still
   * closing the microphone, so nothing is shared between them.
   */
  private static final class Session {
    final Listener listener;
    final List<short[]> chunks = new ArrayList<>();
    int sampleCount;
    int sampleRate;
    /** Guarded by the recorder's lock. */
    boolean running = true;
    /** The capture thread has ended and closed the microphone. */
    boolean finished;
    boolean cancelled;
    @Nullable ResultCallback callback;

    Session (Listener listener) {
      this.listener = listener;
    }
  }

  private final Object lock = new Object();
  private @Nullable Session current;

  private DictationRecorder () { }

  public boolean isRecording () {
    synchronized (lock) {
      return current != null;
    }
  }

  public void start (Listener listener) {
    synchronized (lock) {
      if (current != null) {
        return;
      }
      final Session session = new Session(listener);
      current = session;
      new Thread(() -> capture(session), "VgDictation").start();
    }
  }

  /**
   * Stops the capture without waiting for the microphone thread. The samples are delivered to {@code callback}
   * on the UI thread once the thread has finished (null when cancelled, nothing was recorded or the microphone failed).
   */
  public void stop (boolean cancel, @Nullable ResultCallback callback) {
    synchronized (lock) {
      Session session = current;
      current = null;
      if (session == null) {
        if (callback != null) {
          UI.post(() -> callback.onRecorded(null));
        }
        return;
      }
      session.running = false;
      session.cancelled = cancel;
      if (session.finished) {
        // the capture thread already ended on a failure
        if (callback != null) {
          UI.post(() -> callback.onRecorded(null));
        }
        return;
      }
      session.callback = callback;
    }
  }

  private static float[] collect (Session session) {
    int factor = session.sampleRate == FALLBACK_RATE ? 3 : 1;
    float[] out = new float[session.sampleCount / factor];
    int outIndex = 0, phase = 0;
    float acc = 0;
    for (short[] chunk : session.chunks) {
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
  private void capture (final Session session) {
    final Listener listener = session.listener;
    AudioRecord record = null;
    boolean failed = false;
    try {
      record = open(TARGET_RATE);
      if (record == null) {
        record = open(FALLBACK_RATE);
      }
      if (record == null) {
        throw new IllegalStateException("AudioRecord is not available");
      }
      session.sampleRate = record.getSampleRate();
      record.startRecording();
      if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        throw new IllegalStateException("AudioRecord did not start");
      }
      short[] buffer = new short[record.getSampleRate() / 20]; // 50 ms
      final int maxSamples = (int) (MAX_DURATION_MS / 1000 * record.getSampleRate());
      boolean maxReported = false;
      while (true) {
        synchronized (lock) {
          if (!session.running) {
            break;
          }
        }
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
        boolean reachedMax = false;
        synchronized (lock) {
          if (!session.running) {
            break;
          }
          if (session.sampleCount < maxSamples) {
            int take = Math.min(read, maxSamples - session.sampleCount);
            short[] chunk = new short[take];
            System.arraycopy(buffer, 0, chunk, 0, take);
            session.chunks.add(chunk);
            session.sampleCount += take;
          } else if (!maxReported) {
            maxReported = reachedMax = true;
          }
        }
        if (reachedMax) {
          UI.post(() -> {
            if (isActive(session)) {
              listener.onMaxDurationReached();
            }
          });
        } else {
          UI.post(() -> {
            if (isActive(session)) {
              listener.onAmplitude(amplitude);
            }
          });
        }
      }
    } catch (Throwable t) {
      Log.e("Dictation: recording failed", t);
      failed = true;
      if (isActive(session)) {
        UI.post(() -> {
          if (isActive(session)) {
            listener.onFail();
          }
        });
      }
    } finally {
      if (record != null) {
        try {
          record.stop();
        } catch (Throwable ignored) { }
        record.release();
      }
    }
    // The microphone is closed: hand the samples over if the recording was finished (not cancelled).
    final ResultCallback callback;
    final float[] result;
    synchronized (lock) {
      session.finished = true;
      if (session.running) {
        // the capture failed while still recording: onFail() was posted, stop() delivers no samples
        session.chunks.clear();
        session.sampleCount = 0;
        return;
      }
      callback = session.callback;
      result = callback == null || session.cancelled || failed || session.sampleCount == 0 ? null : collect(session);
      session.chunks.clear();
      session.sampleCount = 0;
    }
    if (callback != null) {
      UI.post(() -> callback.onRecorded(result));
    }
  }

  private boolean isActive (Session session) {
    synchronized (lock) {
      return current == session && session.running;
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
      return record;
    } catch (Throwable t) {
      Log.w("Dictation: AudioRecord(%d) failed", t, rate);
      return null;
    }
  }
}
