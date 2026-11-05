/*
 * Vibegram X: decodes a voice note (OGG/Opus) or a video note (MP4/AAC) into 16 kHz mono float PCM.
 */
package org.thunderdog.challegram.stt;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class AudioDecoder {
  public static final int SAMPLE_RATE = 16000;

  private AudioDecoder () { }

  public interface CancellationSignal {
    boolean isCancelled ();
  }

  /** Too long for recognition: the samples would not fit in memory on low-end phones. */
  public static final class TooLongException extends IOException {
    public TooLongException (long seconds) {
      super("Audio is too long: " + seconds + "s");
    }
  }

  /** Receives mono samples at the source rate. */
  private interface MonoSink {
    /** Called once before the first sample. */
    void onFormat (int sampleRate, long durationUs) throws IOException;
    void add (float sample) throws IOException;
  }

  /**
   * Decodes the first audio track into 16 kHz mono. Samples are resampled as soon as the codec outputs them
   * (no full-rate copy of the track), the result array is allocated once from the track duration.
   *
   * @param maxSeconds longer audio throws {@link TooLongException} instead of filling the memory; 0 for no limit
   */
  public static float[] decode (String path, int maxSeconds, @Nullable CancellationSignal cancel) throws IOException {
    final long maxSamples = maxSeconds > 0 ? (long) maxSeconds * SAMPLE_RATE : Long.MAX_VALUE;
    final FloatArray result = new FloatArray(0);
    final StreamResampler[] resampler = new StreamResampler[1];
    decode(path, maxSeconds, cancel, new MonoSink() {
      @Override
      public void onFormat (int sampleRate, long durationUs) {
        long expected = durationUs > 0 ? durationUs * SAMPLE_RATE / 1_000_000L + SAMPLE_RATE / 2 : SAMPLE_RATE * 8L;
        long cap = maxSeconds > 0 ? (long) (maxSeconds + 1) * SAMPLE_RATE : Integer.MAX_VALUE - 16;
        result.ensureCapacity((int) Math.min(expected, cap));
        resampler[0] = new StreamResampler(sampleRate, SAMPLE_RATE, result);
      }

      @Override
      public void add (float sample) throws IOException {
        resampler[0].push(sample);
        if (result.size() > maxSamples) {
          throw new TooLongException(result.size() / SAMPLE_RATE);
        }
      }
    });
    if (resampler[0] == null) {
      return new float[0];
    }
    resampler[0].finish();
    return result.toArray();
  }

  /**
   * Peak amplitudes (0..32767) of {@code count} equal parts of the audio, at the source rate: enough for a waveform,
   * no resampling and no copy of the samples.
   */
  public static short[] decodePeaks (String path, int count, int maxSeconds, @Nullable CancellationSignal cancel) throws IOException {
    // abs peaks of 5 ms blocks, reduced to count parts at the end
    final FloatArray blocks = new FloatArray(0);
    final int[] blockSize = new int[1];
    final int[] inBlock = new int[1];
    final float[] blockPeak = new float[1];
    decode(path, maxSeconds, cancel, new MonoSink() {
      @Override
      public void onFormat (int sampleRate, long durationUs) {
        blockSize[0] = Math.max(1, sampleRate / 200);
        blocks.ensureCapacity(durationUs > 0 ? (int) Math.min(1_000_000, durationUs / 5000 + 16) : 1024);
      }

      @Override
      public void add (float sample) {
        float abs = Math.abs(sample);
        if (abs > blockPeak[0]) {
          blockPeak[0] = abs;
        }
        if (++inBlock[0] == blockSize[0]) {
          blocks.add(blockPeak[0]);
          blockPeak[0] = 0f;
          inBlock[0] = 0;
        }
      }
    });
    if (inBlock[0] > 0) {
      blocks.add(blockPeak[0]);
    }
    final int length = blocks.size();
    if (length == 0) {
      return null;
    }
    final float[] data = blocks.data();
    short[] peaks = new short[count];
    for (int i = 0; i < count; i++) {
      int from = (int) ((long) length * i / count);
      int to = Math.max(from + 1, (int) ((long) length * (i + 1) / count));
      float peak = 0f;
      for (int j = from; j < to && j < length; j++) {
        peak = Math.max(peak, data[j]);
      }
      peaks[i] = (short) Math.min(32767, (int) (peak * 32767f));
    }
    return peaks;
  }

  private static void decode (String path, int maxSeconds, @Nullable CancellationSignal cancel, MonoSink sink) throws IOException {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try {
      extractor.setDataSource(path);
      int track = -1;
      MediaFormat format = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat f = extractor.getTrackFormat(i);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          track = i;
          format = f;
          break;
        }
      }
      if (track == -1) {
        throw new IOException("No audio track");
      }
      final long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
      if (maxSeconds > 0 && durationUs > (maxSeconds + 1) * 1_000_000L) {
        throw new TooLongException(durationUs / 1_000_000L);
      }
      extractor.selectTrack(track);
      String mime = format.getString(MediaFormat.KEY_MIME);
      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(format, null, null, 0);
      codec.start();

      int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
      boolean floatPcm = false;
      boolean started = false;

      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false, outputDone = false;
      while (!outputDone) {
        if (cancel != null && cancel.isCancelled()) {
          throw new IOException("Cancelled");
        }
        if (!inputDone) {
          int inIndex = codec.dequeueInputBuffer(10000);
          if (inIndex >= 0) {
            ByteBuffer in = codec.getInputBuffer(inIndex);
            int size = in != null ? extractor.readSampleData(in, 0) : -1;
            if (size < 0) {
              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int outIndex = codec.dequeueOutputBuffer(info, 10000);
        if (outIndex >= 0) {
          ByteBuffer out = codec.getOutputBuffer(outIndex);
          if (out != null && info.size > 0) {
            if (!started) {
              // the output format is known by now (INFO_OUTPUT_FORMAT_CHANGED comes before the first buffer)
              started = true;
              sink.onFormat(sampleRate, durationUs);
            }
            out.position(info.offset);
            out.limit(info.offset + info.size);
            ByteBuffer data = out.slice().order(ByteOrder.nativeOrder());
            if (floatPcm) {
              int count = data.remaining() / 4 / channels;
              for (int i = 0; i < count; i++) {
                float sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                  sum += data.getFloat((i * channels + ch) * 4);
                }
                sink.add(sum / channels);
              }
            } else {
              ShortBuffer shorts = data.asShortBuffer();
              int count = shorts.remaining() / channels;
              for (int i = 0; i < count; i++) {
                int sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                  sum += shorts.get(i * channels + ch);
                }
                sink.add(sum / (32768f * channels));
              }
            }
          }
          codec.releaseOutputBuffer(outIndex, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true;
          }
        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat outFormat = codec.getOutputFormat();
          if (!started) {
            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
          } else {
            channels = outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
          }
          floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
        }
      }
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (Throwable ignored) { }
        codec.release();
      }
      extractor.release();
    }
  }

  /**
   * Windowed-sinc resampler (low-pass at the lower Nyquist frequency) with precomputed polyphase weights:
   * output sample i sits at input position i * M / L (L/M = toRate/fromRate reduced), so there are only L
   * distinct sets of weights. Fed one sample at a time, it keeps only the last few input samples,
   * so the source-rate track (48 kHz for Opus) is never stored.
   */
  static final class StreamResampler {
    private final FloatArray output;
    private final boolean passThrough;
    private final int L, M, taps, width;
    private final float[][] weights;
    private float[] history; // input samples from absolute index historyStart
    private long historyStart;
    private int historyLength;
    private long inputCount;
    private long outputIndex;

    StreamResampler (int fromRate, int toRate, FloatArray output) {
      this.output = output;
      this.passThrough = fromRate == toRate;
      if (passThrough) {
        L = M = taps = width = 1;
        weights = null;
        return;
      }
      final int g = gcd(fromRate, toRate);
      L = toRate / g;
      M = fromRate / g;
      final double ratio = (double) toRate / fromRate;
      taps = (int) Math.ceil(16 * (1.0 / Math.min(1.0, ratio)));
      width = 2 * taps;
      weights = buildWeights(L, taps, Math.min(1.0, ratio) * 0.95);
      history = new float[Math.max(4096, width * 4)];
    }

    void push (float sample) {
      if (passThrough) {
        output.add(sample);
        return;
      }
      if (historyLength == history.length) {
        compact();
      }
      history[historyLength++] = sample;
      inputCount++;
      // emit every output whose window is complete
      while (true) {
        long pos = outputIndex * M;
        long base = pos / L;
        if (base + taps >= inputCount) {
          break;
        }
        output.add(compute(base, (int) (pos % L)));
        outputIndex++;
      }
    }

    void finish () {
      if (passThrough) {
        return;
      }
      final long outLength = inputCount * L / M;
      while (outputIndex < outLength) {
        long pos = outputIndex * M;
        output.add(compute(pos / L, (int) (pos % L)));
        outputIndex++;
      }
    }

    private float compute (long base, int phase) {
      final float[] w = weights[phase];
      final long start = base - taps + 1;
      float sum = 0;
      if (start >= 0 && start + width <= inputCount) {
        int offset = (int) (start - historyStart);
        for (int k = 0; k < width; k++) {
          sum += history[offset + k] * w[k];
        }
        return sum;
      }
      float weightSum = 0;
      for (int k = 0; k < width; k++) {
        long j = start + k;
        if (j >= 0 && j < inputCount) {
          sum += history[(int) (j - historyStart)] * w[k];
          weightSum += w[k];
        }
      }
      return weightSum != 0 ? sum / weightSum : 0;
    }

    /** Drops the input samples no output needs any more. */
    private void compact () {
      long needed = Math.max(0, (outputIndex * M) / L - taps + 1);
      int drop = (int) Math.min(historyLength, Math.max(0, needed - historyStart));
      if (drop > 0) {
        System.arraycopy(history, drop, history, 0, historyLength - drop);
        historyLength -= drop;
        historyStart += drop;
      }
      if (historyLength == history.length) {
        history = java.util.Arrays.copyOf(history, history.length * 2);
      }
    }
  }

  private static float[][] buildWeights (int L, int taps, double cutoff) {
    final int width = 2 * taps;
    if ((long) L * width > 4_000_000L) {
      throw new IllegalArgumentException("Unsupported sample rate ratio " + L);
    }
    // weights[phase][k] for input index floor(center) - taps + 1 + k; computing sin/cos per tap is far too slow
    final float[][] weights = new float[L][width];
    for (int phase = 0; phase < L; phase++) {
      double frac = phase / (double) L; // center - floor(center)
      double weightSum = 0;
      for (int k = 0; k < width; k++) {
        double d = (k - taps + 1) - frac; // j - center
        double t = d * cutoff;
        double sinc = t == 0 ? 1.0 : Math.sin(Math.PI * t) / (Math.PI * t);
        double w = d / (taps + 1);
        double window = 0.5 * (1 + Math.cos(Math.PI * Math.max(-1, Math.min(1, w))));
        weights[phase][k] = (float) (sinc * window);
        weightSum += sinc * window;
      }
      for (int k = 0; k < width; k++) {
        weights[phase][k] /= (float) weightSum;
      }
    }
    return weights;
  }

  private static int gcd (int a, int b) {
    while (b != 0) {
      int t = a % b;
      a = b;
      b = t;
    }
    return a;
  }

  static final class FloatArray {
    private float[] data;
    private int size;

    FloatArray (int capacity) {
      data = new float[Math.max(16, capacity)];
    }

    void ensureCapacity (int capacity) {
      if (capacity > data.length) {
        data = java.util.Arrays.copyOf(data, capacity);
      }
    }

    void add (float value) {
      if (size == data.length) {
        // grow by half: a wrong duration estimate should not double the peak memory
        data = java.util.Arrays.copyOf(data, data.length + Math.max(16, data.length / 2));
      }
      data[size++] = value;
    }

    int size () {
      return size;
    }

    float[] data () {
      return data;
    }

    float[] toArray () {
      return size == data.length ? data : java.util.Arrays.copyOf(data, size);
    }
  }
}
