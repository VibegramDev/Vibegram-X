/*
 * Vibegram X: «Enhanced transcription». After the selected recognizer, a small language model
 * (Qwen3.5-4B, 4-bit GGUF, llama.cpp) reads the finished text and fixes words that were misheard, judging by
 * the context. It gets only the text, not the audio, so it cannot recover a word the recognizer lost completely.
 *
 * Bench (8 Russian voice notes, 462 words, Parakeet Ultra int8, this code path on a Mac): word errors 8.7% → 9.3%.
 * It writes names in their own spelling («Гермес» → Hermes, «Клод» → Claude, «ринсол» → RinSol), but also changes
 * a few right words («ночью» → «ночи»): a small model judges by meaning, not by sound. Kept as an option to try.
 *
 * Only small replacements are taken from the answer ({@link #keepSmallEdits}): the model also drops words it does
 * not understand and repeats words in a loop (once «пик» on a two-minute note until the token limit).
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SttCorrector {
  interface Listener {
    /** @param progress 0..1, estimated from the number of generated tokens */
    void onProgress (float progress);
  }

  /**
   * For Cyrillic text: names and English terms written in Russian letters are the most common recognition error,
   * and only this wording makes the model write them in Latin. Tested on our notes word for word.
   */
  private static final String SYSTEM_RU =
    "Ты исправляешь автоматическую расшифровку русского голосового сообщения. В тексте бывают ошибки распознавания: " +
    "неверно услышанные слова, особенно английские названия и термины, записанные русскими буквами, и неверные окончания. " +
    "Исправь только явные ошибки распознавания, опираясь на смысл. Не перефразируй, не меняй стиль, не убирай мат и разговорные слова, " +
    "не добавляй ничего от себя. Если не уверен — оставь как есть. Выведи только исправленный текст.";

  /** For other languages. */
  private static final String SYSTEM =
    "You fix speech-recognition errors in an automatic transcript of a voice message. " +
    "The transcript may contain misheard words: real words that sound similar to what was said, " +
    "broken names of products, brands, people, and foreign (often English) terms written wrongly or phonetically. " +
    "Fix only such recognition errors and obvious punctuation mistakes. " +
    "Do not rephrase, do not change word order, do not shorten, do not add anything, do not translate - keep the original language. " +
    "Keep slang and profanity exactly as spoken, never censor. If unsure, leave the words unchanged. " +
    "Output only the corrected transcript, nothing else.";

  /**
   * Texts are corrected in parts split at sentence ends, about a minute and a half of speech each:
   * enough context for the meaning, and a part the model breaks (a loop, a reply instead of a fix) costs little.
   */
  private static final int MAX_PART_CHARS = 1200;
  /** Russian text takes about 3.5 characters per token of this model; used for the token limit and the progress. */
  private static final float CHARS_PER_TOKEN = 3.5f;

  private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
  private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+\\s+");

  private long ptr;

  SttCorrector (File modelFile) throws IOException {
    LlmNative.load();
    ptr = LlmNative.init(modelFile.getAbsolutePath());
    if (ptr == 0) {
      throw new IOException("llama.cpp failed to load " + modelFile.getName());
    }
  }

  /** Any thread. */
  synchronized void cancel () {
    if (ptr != 0) {
      LlmNative.cancel(ptr);
    }
  }

  synchronized void resetCancel () {
    if (ptr != 0) {
      LlmNative.resetCancel(ptr);
    }
  }

  synchronized void release () {
    if (ptr != 0) {
      LlmNative.free(ptr);
      ptr = 0;
    }
  }

  /** @return the corrected text; a part the model answered strangely keeps the original text */
  String correct (String text, @Nullable Listener listener) throws IOException {
    long handle = ptr;
    if (handle == 0) {
      throw new IOException("Released");
    }
    List<String> parts = split(text);
    final float totalTokens = Math.max(1f, text.length() / CHARS_PER_TOKEN);
    StringBuilder b = new StringBuilder();
    int doneChars = 0;
    for (String part : parts) {
      final int partStart = doneChars;
      String prompt =
        "<|im_start|>system\n" + (isMostlyCyrillic(part) ? SYSTEM_RU : SYSTEM) + "<|im_end|>\n" +
        "<|im_start|>user\n" + part + "<|im_end|>\n" +
        "<|im_start|>assistant\n<think>\n\n</think>\n\n";
      // room for a third more text than the original: a longer answer is rejected anyway
      int maxTokens = (int) (part.length() / CHARS_PER_TOKEN * 1.4f) + 32;
      byte[] result = LlmNative.generate(handle, prompt.getBytes(StandardCharsets.UTF_8), maxTokens, SttEngine.threadCount(),
        listener == null ? null : generated ->
          listener.onProgress(Math.min(1f, (partStart / CHARS_PER_TOKEN + generated) / totalTokens))
      );
      if (result == null) {
        throw new IOException("Correction failed or cancelled");
      }
      String corrected = clean(new String(result, StandardCharsets.UTF_8));
      if (!isPlausible(part, corrected)) {
        Log.w(Log.TAG_VOICE, "STT: correction rejected, keeping the original part (%d → %d chars)", part.length(), corrected.length());
        corrected = part;
      } else {
        corrected = keepSmallEdits(part, corrected);
      }
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(corrected);
      doneChars += part.length();
    }
    return b.toString();
  }

  private static String clean (String answer) {
    return THINK.matcher(answer).replaceAll("").replaceAll("\\s+", " ").trim();
  }

  /**
   * The model must return the same text with a few words fixed. An empty answer, a much shorter or longer one
   * (a summary, a reply to the message, a translation, a word repeated until the limit) means it did something else.
   */
  private static boolean isPlausible (String original, String corrected) {
    if (corrected.isEmpty()) {
      return false;
    }
    float ratio = corrected.length() / (float) original.length();
    return ratio > .75f && ratio < 1.3f;
  }

  private static boolean isMostlyCyrillic (String text) {
    int cyrillic = 0, letters = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetter(c)) {
        letters++;
        if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
          cyrillic++;
        }
      }
    }
    return letters > 0 && cyrillic * 2 > letters;
  }

  /** At most this many words replaced by at most this many: a fixed word or a name of two words. */
  private static final int MAX_EDIT_WORDS = 2;

  /**
   * The recognizer's text with only the small replacements of the answer: words aligned by their letters
   * (case, «ё» and punctuation ignored), a run of 1–2 different words replaced by 1–2 words is taken from the answer.
   * Words the model dropped or added are not taken, nor its punctuation of unchanged words.
   */
  static String keepSmallEdits (String original, String corrected) {
    String[] a = original.split(" "), b = corrected.split(" ");
    String[] ka = new String[a.length], kb = new String[b.length];
    for (int i = 0; i < a.length; i++) {
      ka[i] = wordKey(a[i]);
    }
    for (int j = 0; j < b.length; j++) {
      kb[j] = wordKey(b[j]);
    }
    // longest common subsequence of the word keys
    int[][] lcs = new int[a.length + 1][b.length + 1];
    for (int i = a.length - 1; i >= 0; i--) {
      for (int j = b.length - 1; j >= 0; j--) {
        lcs[i][j] = ka[i].equals(kb[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    StringBuilder out = new StringBuilder();
    int i = 0, j = 0;
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && ka[i].equals(kb[j])) {
        append(out, a[i]);
        i++;
        j++;
        continue;
      }
      // a run of differences up to the next common word
      int gi = i, gj = j;
      while (gi < a.length || gj < b.length) {
        if (gi < a.length && gj < b.length && ka[gi].equals(kb[gj]) && lcs[gi][gj] == lcs[i][j]) {
          break;
        }
        if (gj < b.length && (gi == a.length || lcs[gi][gj + 1] >= lcs[gi + 1][gj])) {
          gj++;
        } else {
          gi++;
        }
      }
      int removed = gi - i, added = gj - j;
      boolean take = removed > 0 && added > 0 && removed <= MAX_EDIT_WORDS && added <= MAX_EDIT_WORDS;
      if (take) {
        for (int x = j; x < gj; x++) {
          append(out, b[x]);
        }
      } else {
        for (int x = i; x < gi; x++) {
          append(out, a[x]);
        }
      }
      i = gi;
      j = gj;
    }
    return out.toString();
  }

  private static void append (StringBuilder out, String word) {
    if (word.isEmpty()) {
      return;
    }
    if (out.length() > 0) {
      out.append(' ');
    }
    out.append(word);
  }

  private static String wordKey (String word) {
    StringBuilder b = new StringBuilder(word.length());
    for (int i = 0; i < word.length(); i++) {
      char c = Character.toLowerCase(word.charAt(i));
      if (c == 'ё') {
        c = 'е';
      }
      if (Character.isLetterOrDigit(c)) {
        b.append(c);
      }
    }
    return b.toString();
  }

  static List<String> split (String text) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (text.length() - start > MAX_PART_CHARS) {
      int limit = start + MAX_PART_CHARS;
      int cut = -1;
      Matcher m = SENTENCE_END.matcher(text);
      m.region(start + MAX_PART_CHARS / 2, limit);
      while (m.find()) {
        cut = m.end();
      }
      if (cut == -1) {
        cut = text.lastIndexOf(' ', limit);
        if (cut <= start) {
          cut = limit;
        }
      }
      parts.add(text.substring(start, cut).trim());
      start = cut;
    }
    String tail = text.substring(start).trim();
    if (!tail.isEmpty()) {
      parts.add(tail);
    }
    return parts;
  }
}
