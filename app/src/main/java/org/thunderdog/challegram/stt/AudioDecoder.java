/*
 * Vibegram X: decodes a voice note (OGG/Opus) or a video note (MP4/AAC) into 16 kHz mono float PCM.
 */
package org.thunderdog.challegram.stt;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

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

  public static float[] decode (String path, CancellationSignal cancel) throws IOException {
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
      extractor.selectTrack(track);
      String mime = format.getString(MediaFormat.KEY_MIME);
      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(format, null, null, 0);
      codec.start();

      int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
      boolean floatPcm = false;

      FloatArray mono = new FloatArray(sampleRate * 8);
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
                mono.add(sum / channels);
              }
            } else {
              ShortBuffer shorts = data.asShortBuffer();
              int count = shorts.remaining() / channels;
              for (int i = 0; i < count; i++) {
                int sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                  sum += shorts.get(i * channels + ch);
                }
                mono.add(sum / (32768f * channels));
              }
            }
          }
          codec.releaseOutputBuffer(outIndex, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true;
          }
        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat outFormat = codec.getOutputFormat();
          sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
          floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
        }
      }
      return resample(mono.toArray(), sampleRate, SAMPLE_RATE);
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
   * distinct sets of weights. Computing sin/cos per tap took ~4 s for a 2-minute message.
   */
  static float[] resample (float[] input, int fromRate, int toRate) {
    if (fromRate == toRate || input.length == 0) {
      return input;
    }
    final int g = gcd(fromRate, toRate);
    final int L = toRate / g, M = fromRate / g;
    final double ratio = (double) toRate / fromRate;
    final double cutoff = Math.min(1.0, ratio) * 0.95;
    final int taps = (int) Math.ceil(16 * (1.0 / Math.min(1.0, ratio)));
    final int width = 2 * taps;
    if ((long) L * width > 4_000_000L) {
      throw new IllegalArgumentException("Unsupported sample rate " + fromRate);
    }
    // weights[phase][k] for input index floor(center) - taps + 1 + k
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
    final int outLength = (int) ((long) input.length * L / M);
    final float[] output = new float[outLength];
    for (int i = 0; i < outLength; i++) {
      long pos = (long) i * M;
      int base = (int) (pos / L);
      float[] w = weights[(int) (pos % L)];
      int start = base - taps + 1;
      float sum = 0;
      if (start >= 0 && start + width <= input.length) {
        for (int k = 0; k < width; k++) {
          sum += input[start + k] * w[k];
        }
      } else {
        float weightSum = 0;
        for (int k = 0; k < width; k++) {
          int j = start + k;
          if (j >= 0 && j < input.length) {
            sum += input[j] * w[k];
            weightSum += w[k];
          }
        }
        sum = weightSum != 0 ? sum / weightSum : 0;
      }
      output[i] = sum;
    }
    return output;
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

    void add (float value) {
      if (size == data.length) {
        float[] grown = new float[data.length * 2];
        System.arraycopy(data, 0, grown, 0, size);
        data = grown;
      }
      data[size++] = value;
    }

    float[] toArray () {
      float[] result = new float[size];
      System.arraycopy(data, 0, result, 0, size);
      return result;
    }
  }
}
