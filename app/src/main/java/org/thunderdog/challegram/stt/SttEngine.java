/*
 * Vibegram X: speech recognition engines behind one interface.
 * whisper.cpp runs the Whisper models; sherpa-onnx runs Parakeet.
 * All of them detect the language automatically.
 *
 * The progress and the text recognized so far are reported while a long message is still processed:
 * whisper.cpp streams its segments, Parakeet gets the audio in chunks of up to a minute cut at pauses.
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Nullable;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public abstract class SttEngine {
  public interface Listener {
    /** @param progress 0..1 of the whole message */
    void onProgress (float progress);
    /** Text recognized so far. */
    void onPartialText (String text);
  }

  protected volatile boolean cancelled;

  public String transcribe (float[] samples, @Nullable Listener listener) throws IOException {
    List<float[]> chunks = splitOnPauses(samples, maxChunkSeconds());
    final long total = Math.max(1, samples.length);
    long done = 0;
    StringBuilder b = new StringBuilder();
    for (float[] chunk : chunks) {
      if (cancelled) {
        throw new IOException("Cancelled");
      }
      final long chunkStart = done;
      final int chunkLength = chunk.length;
      String text = transcribeChunk(chunk, listener == null ? null : progress ->
        listener.onProgress((chunkStart + chunkLength * Math.max(0f, Math.min(1f, progress))) / (float) total)
      );
      text = cleanUp(text);
      if (!text.isEmpty()) {
        if (b.length() > 0) {
          b.append(' ');
        }
        b.append(text);
        if (listener != null) {
          listener.onPartialText(b.toString());
        }
      }
      done += chunk.length;
      if (listener != null) {
        listener.onProgress(done / (float) total);
      }
    }
    return removeRepeats(b.toString());
  }

  interface ChunkProgress {
    void onChunkProgress (float progress);
    default void onText (String segment) { }
    default void onLiveText (String text) { }
  }

  protected abstract int maxChunkSeconds ();

  protected abstract String transcribeChunk (float[] chunk, @Nullable ChunkProgress progress) throws IOException;

  public void cancel () {
    cancelled = true;
  }

  public void resetCancel () {
    cancelled = false;
  }

  public abstract void release ();

  private static int cachedThreads;

  /**
   * Number of performance cores: cores whose max frequency is above the slowest cluster.
   * Threads on efficiency cores slow whisper.cpp down because every thread waits for the slowest one.
   */
  static synchronized int threadCount () {
    if (cachedThreads != 0) {
      return cachedThreads;
    }
    int cores = Runtime.getRuntime().availableProcessors();
    int[] freqs = new int[cores];
    int min = Integer.MAX_VALUE, known = 0;
    for (int i = 0; i < cores; i++) {
      try (BufferedReader reader = new BufferedReader(new FileReader("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))) {
        freqs[i] = Integer.parseInt(reader.readLine().trim());
        min = Math.min(min, freqs[i]);
        known++;
      } catch (Throwable ignored) { }
    }
    int fast = 0;
    if (known == cores) {
      for (int freq : freqs) {
        if (freq > min) {
          fast++;
        }
      }
    }
    if (fast == 0) {
      // all cores are the same (or unknown): leave one for the UI
      fast = cores > 4 ? cores - 1 : cores;
    }
    cachedThreads = Math.max(2, Math.min(8, fast));
    android.util.Log.i("VgStt", cores + " cores, " + cachedThreads + " threads");
    return cachedThreads;
  }

  public static SttEngine create (SttModel model, File dir) throws IOException {
    switch (model.engine) {
      case SttModel.ENGINE_WHISPER_CPP:
        return new WhisperCpp(model, new File(dir, "model.bin"));
      case SttModel.ENGINE_SHERPA_NEMO_TDT:
        return new SherpaOffline(dir);
    }
    throw new IOException("Unknown engine");
  }

  // Post-processing

  /** Phrases Whisper invents on silence and music: it learned them from subtitles of videos. */
  private static final Pattern HALLUCINATION = Pattern.compile(
    "[^.!?…]*(" +
      "продолжение следует|редактор субтитров|корректор [а-яё]\\.|субтитр(ы|ов)? (сделал|создавал|делал|подготовил|предоставил)|" +
      "dimatorzok|amara\\.org|спасибо за просмотр|подписывайтесь на (наш )?канал|ставьте лайк|" +
      "thanks? (you )?for watching|please subscribe|subtitles by" +
    ")[^.!?…]*[.!?…]*",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
  );

  static String cleanUp (String text) {
    if (text == null) {
      return "";
    }
    String result = HALLUCINATION.matcher(text).replaceAll(" ");
    return result.replaceAll("\\s+", " ").trim();
  }

  /** Collapses a sentence repeated three or more times in a row (a decoder loop) into one. */
  static String removeRepeats (String text) {
    String[] sentences = text.split("(?<=[.!?…])\\s+");
    if (sentences.length < 3) {
      return text;
    }
    StringBuilder b = new StringBuilder();
    int i = 0;
    while (i < sentences.length) {
      int j = i + 1;
      while (j < sentences.length && sentences[j].equalsIgnoreCase(sentences[i])) {
        j++;
      }
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(sentences[i]);
      if (j - i == 2) {
        b.append(' ').append(sentences[i]);
      }
      i = j;
    }
    return b.toString();
  }

  // whisper.cpp

  /**
   * A short example of the expected output style: Whisper continues the style of the previous text,
   * so with this prompt it keeps punctuation and capital letters on long, casual messages.
   */
  private static final String WHISPER_PROMPT = "Привет! Да, всё хорошо, спасибо. Okay, let's do it.";

  static final class WhisperCpp extends SttEngine {
    private final boolean acft;
    private long ptr;

    WhisperCpp (SttModel model, File file) throws IOException {
      this.acft = model.acft;
      WhisperNative.load();
      ptr = WhisperNative.init(file.getAbsolutePath(), true);
      if (ptr == 0) {
        throw new IOException("whisper.cpp failed to load " + file.getName());
      }
    }

    @Override
    protected int maxChunkSeconds () {
      return 30;
    }

    /**
     * The whole message goes to whisper.cpp in one call: it moves its 30 s window along the audio itself
     * and keeps the previous text as context, which is faster than separate chunks (each chunk is padded to 30 s)
     * and keeps punctuation consistent. Segments are streamed back as soon as they are recognized.
     */
    @Override
    public String transcribe (float[] samples, @Nullable Listener listener) throws IOException {
      final StringBuilder committed = new StringBuilder();
      String text = transcribeChunk(samples, listener == null ? null : new ChunkProgress() {
        @Override
        public void onChunkProgress (float progress) {
          listener.onProgress(progress);
        }

        @Override
        public void onText (String segment) {
          String clean = cleanUp(segment);
          if (clean.isEmpty()) {
            return;
          }
          if (committed.length() > 0) {
            committed.append(' ');
          }
          committed.append(clean);
          listener.onPartialText(committed.toString());
        }

        @Override
        public void onLiveText (String live) {
          String clean = cleanUp(live);
          if (clean.isEmpty()) {
            return;
          }
          listener.onPartialText(committed.length() > 0 ? committed + " " + clean : clean);
        }
      });
      if (listener != null) {
        listener.onProgress(1f);
      }
      return removeRepeats(cleanUp(text));
    }

    @Override
    public void cancel () {
      super.cancel();
      if (ptr != 0) {
        WhisperNative.cancel(ptr);
      }
    }

    @Override
    public void resetCancel () {
      super.resetCancel();
      if (ptr != 0) {
        WhisperNative.resetCancel(ptr);
      }
    }

    @Override
    protected String transcribeChunk (float[] chunk, @Nullable ChunkProgress progress) throws IOException {
      float[] input = chunk;
      if (chunk.length < AudioDecoder.SAMPLE_RATE) {
        // whisper.cpp needs at least one second of audio
        input = new float[AudioDecoder.SAMPLE_RATE + AudioDecoder.SAMPLE_RATE / 10];
        System.arraycopy(chunk, 0, input, 0, chunk.length);
      }
      int audioCtx = 0;
      if (acft && input.length < 30 * AudioDecoder.SAMPLE_RATE) {
        // FUTO models are finetuned to run the encoder on the real audio length instead of 30 s (1500 frames).
        float seconds = input.length / (float) AudioDecoder.SAMPLE_RATE;
        audioCtx = Math.min(1500, (int) Math.ceil(seconds * 50f) + 64);
      }
      byte[] result = WhisperNative.transcribe(ptr, input, threadCount(), audioCtx, WHISPER_PROMPT,
        progress == null ? null : new WhisperNative.ProgressListener() {
          @Override
          public void onProgress (int value) {
            progress.onChunkProgress(value / 100f);
          }

          @Override
          public void onSegment (byte[] segment) {
            progress.onText(new String(segment, StandardCharsets.UTF_8));
          }

          @Override
          public void onLive (byte[] text) {
            progress.onLiveText(new String(text, StandardCharsets.UTF_8));
          }
        });
      if (result == null) {
        if (cancelled) {
          throw new IOException("Cancelled");
        }
        throw new IOException("whisper.cpp failed");
      }
      String decoded = new String(result, StandardCharsets.UTF_8);
      int newLine = decoded.indexOf('\n');
      String text = (newLine >= 0 ? decoded.substring(newLine + 1) : decoded).trim();
      if (text.equalsIgnoreCase(WHISPER_PROMPT)) {
        // the prompt echoed back on silence
        return "";
      }
      return text;
    }

    @Override
    public void release () {
      if (ptr != 0) {
        WhisperNative.free(ptr);
        ptr = 0;
      }
    }
  }

  // sherpa-onnx, offline: Parakeet TDT

  static final class SherpaOffline extends SttEngine {
    private final OfflineRecognizer recognizer;

    SherpaOffline (File dir) {
      OfflineModelConfig modelConfig = new OfflineModelConfig();
      modelConfig.setNumThreads(threadCount());
      modelConfig.setProvider("cpu");
      OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
      transducer.setEncoder(new File(dir, "encoder.int8.onnx").getAbsolutePath());
      transducer.setDecoder(new File(dir, "decoder.int8.onnx").getAbsolutePath());
      transducer.setJoiner(new File(dir, "joiner.int8.onnx").getAbsolutePath());
      modelConfig.setTransducer(transducer);
      modelConfig.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
      modelConfig.setModelType("nemo_transducer");
      OfflineRecognizerConfig config = new OfflineRecognizerConfig();
      config.setFeatConfig(new FeatureConfig(AudioDecoder.SAMPLE_RATE, 80, 0f));
      config.setModelConfig(modelConfig);
      config.setDecodingMethod("greedy_search");
      recognizer = new OfflineRecognizer(null, config);
    }

    /**
     * Chunks of up to 60 s cut at the longest pause. A chunk that starts in the middle of a sentence
     * often comes out with no punctuation at all, so cuts go where the speaker actually stopped, and chunks are
     * long enough to give the model context. Measured on a 2-minute message: about 7% slower than 25 s chunks
     * with punctuation everywhere; the whole message in one piece is punctuated too but ~50% slower
     * (attention cost grows with the square of the length).
     */
    @Override
    protected int maxChunkSeconds () {
      return 60;
    }

    @Override
    protected String transcribeChunk (float[] chunk, @Nullable ChunkProgress progress) {
      OfflineStream stream = recognizer.createStream();
      try {
        stream.acceptWaveform(chunk, AudioDecoder.SAMPLE_RATE);
        recognizer.decode(stream);
        return recognizer.getResult(stream).getText().trim();
      } finally {
        stream.release();
      }
    }

    @Override
    public void release () {
      recognizer.release();
    }
  }

  /**
   * Splits audio into chunks of at most {@code maxSeconds}, cutting in the middle of the longest pause
   * found in the last 60% of each chunk (pause = 20 ms frames quieter than 15% of the typical speech loudness).
   * Without a pause there, cuts at the quietest frame.
   */
  static List<float[]> splitOnPauses (float[] samples, int maxSeconds) {
    List<float[]> chunks = new ArrayList<>();
    final int frame = AudioDecoder.SAMPLE_RATE / 50;
    final int frames = samples.length / frame;
    final int maxFrames = maxSeconds * 50;
    if (frames <= maxFrames) {
      chunks.add(samples);
      return chunks;
    }
    float[] rms = new float[frames];
    for (int f = 0; f < frames; f++) {
      double sum = 0;
      for (int i = f * frame, end = i + frame; i < end; i++) {
        sum += samples[i] * samples[i];
      }
      rms[f] = (float) Math.sqrt(sum / frame);
    }
    float[] sorted = rms.clone();
    java.util.Arrays.sort(sorted);
    final float quiet = sorted[frames * 3 / 4] * .15f;

    int start = 0;
    while (frames - start > maxFrames) {
      int from = start + maxFrames * 2 / 5, to = start + maxFrames;
      int cut = -1, bestLength = 0;
      for (int f = from; f < to; ) {
        if (rms[f] < quiet) {
          int end = f;
          while (end < to && rms[end] < quiet) {
            end++;
          }
          if (end - f >= bestLength) { // the later one of equal pauses: longer chunks
            bestLength = end - f;
            cut = (f + end) / 2;
          }
          f = end;
        } else {
          f++;
        }
      }
      if (cut == -1) {
        cut = from;
        for (int f = from; f < to; f++) {
          if (rms[f] < rms[cut]) {
            cut = f;
          }
        }
      }
      chunks.add(java.util.Arrays.copyOfRange(samples, start * frame, cut * frame));
      start = cut;
    }
    chunks.add(java.util.Arrays.copyOfRange(samples, start * frame, samples.length));
    return chunks;
  }
}
