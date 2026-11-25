/*
 * Vibegram X: subtitles made from an on-device transcription.
 * Every engine reports the times of its tokens: sherpa-onnx (Parakeet, GigaAM) the start of each one,
 * whisper.cpp a rough start and end estimated from its timestamp tokens. Tokens are joined into words,
 * words into short phrases of one or two lines that are shown inside a playing round video.
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SttSubtitles {
  /** A phrase is cut after a sentence, before this many characters or at a pause in the speech. */
  private static final int MAX_CHARS = 38, MIN_CHARS_FOR_COMMA = 16;
  private static final long PAUSE_MS = 700;
  /** A phrase may be this much longer when that finishes the sentence: no lone «супер.» on the next screen. */
  private static final int TAIL_CHARS = 12;
  /** A phrase stays on screen a bit after its last word, unless the next one starts earlier. */
  private static final long LINGER_MS = 800;

  public static final class Cue {
    public final long startMs, endMs;
    public final String text;

    Cue (long startMs, long endMs, String text) {
      this.startMs = startMs;
      this.endMs = endMs;
      this.text = text;
    }
  }

  private final Cue[] cues;

  private SttSubtitles (Cue[] cues) {
    this.cues = cues;
  }

  public int size () {
    return cues.length;
  }

  public Cue get (int index) {
    return cues[index];
  }

  /** @return index of the phrase shown at the given position of the video, -1 when none */
  public int indexAt (long positionMs) {
    int low = 0, high = cues.length - 1, found = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (cues[mid].startMs <= positionMs) {
        found = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return found != -1 && positionMs < cues[found].endMs ? found : -1;
  }

  // Building

  static final class Word {
    final long startMs, endMs;
    final String text;

    Word (long startMs, long endMs, String text) {
      this.startMs = startMs;
      this.endMs = endMs;
      this.text = text;
    }
  }

  /**
   * Joins tokens into words: a token that starts with a space (or the SentencePiece «▁») starts a new word,
   * the others (word pieces, punctuation) continue the previous one.
   *
   * @param timestamps start of each token in seconds from the start of the chunk
   * @param durations duration of each token in seconds (TDT models only, may be empty)
   */
  static void addWords (List<Word> out, String[] tokens, float[] timestamps, float[] durations, long offsetMs) {
    if (tokens == null || timestamps == null || tokens.length == 0 || tokens.length != timestamps.length) {
      return;
    }
    final boolean hasDurations = durations != null && durations.length == tokens.length;
    StringBuilder word = null;
    long wordStart = 0, wordEnd = 0;
    for (int i = 0; i < tokens.length; i++) {
      String token = tokens[i];
      if (token == null || token.isEmpty()) {
        continue;
      }
      final boolean startsWord = token.charAt(0) == ' ' || token.charAt(0) == '\u2581';
      final String piece = token.replace('\u2581', ' ').trim();
      final long start = offsetMs + Math.round(timestamps[i] * 1000f);
      long end;
      if (hasDurations && durations[i] > 0f) {
        end = start + Math.round(durations[i] * 1000f);
      } else if (i + 1 < tokens.length) {
        end = Math.min(offsetMs + Math.round(timestamps[i + 1] * 1000f), start + 1000);
      } else {
        end = start + 400;
      }
      if (startsWord || word == null) {
        if (word != null && word.length() > 0) {
          out.add(new Word(wordStart, wordEnd, word.toString()));
        }
        word = new StringBuilder(piece);
        wordStart = start;
      } else {
        word.append(piece);
      }
      wordEnd = Math.max(wordEnd, end);
    }
    if (word != null && word.length() > 0) {
      out.add(new Word(wordStart, wordEnd, word.toString()));
    }
  }

  /** Size of a token record header from libvgwhisper: int32 t0, int32 t1, uint16 segment, uint16 length. */
  private static final int WHISPER_RECORD = 12;

  /**
   * Words from the token records libvgwhisper puts after the text (see recognizedText in vgwhisper.cpp).
   * Whisper's tokens are pieces of bytes, one may end in the middle of a UTF-8 character: the bytes of a word
   * are joined and decoded only at its end. A token that starts with a space, or a new segment, starts a new word.
   * Words inside the phrases matched by {@code drop} are skipped: the same phrases cleanUp removes from the text.
   *
   * @param from index of the first record in {@code data}
   * @param offsetMs start of the recognized audio in the message, token times are relative to it
   * @param limitMs end of the recognized audio in the message: no word ends later (short audio is padded)
   */
  static void addWhisperWords (List<Word> out, byte[] data, int from, long offsetMs, long limitMs, @Nullable Pattern drop) {
    if (data == null || from < 0 || from >= data.length) {
      return;
    }
    ByteBuffer buffer = ByteBuffer.wrap(data, from, data.length - from).order(ByteOrder.LITTLE_ENDIAN);
    List<Word> words = new ArrayList<>();
    ByteArrayOutputStream word = new ByteArrayOutputStream();
    int segment = -1;
    long wordStart = -1, wordEnd = 0;
    while (buffer.remaining() >= WHISPER_RECORD) {
      final long t0 = buffer.getInt(), t1 = buffer.getInt();
      final int index = buffer.getShort() & 0xffff, length = buffer.getShort() & 0xffff;
      if (length > buffer.remaining()) {
        break;
      }
      final int position = buffer.position();
      buffer.position(position + length);
      if (length == 0) {
        continue;
      }
      if (index != segment || data[position] == ' ') {
        addWord(words, word, wordStart, wordEnd);
        segment = index;
        wordStart = -1;
        wordEnd = 0;
      }
      final long start = Math.max(offsetMs, Math.min(limitMs, offsetMs + t0 * 10));
      final long end = Math.max(start, Math.min(limitMs, offsetMs + t1 * 10));
      if (wordStart < 0 && !isBlank(data, position, length)) {
        wordStart = start;
      }
      word.write(data, position, length);
      wordEnd = Math.max(wordEnd, end);
    }
    addWord(words, word, wordStart, wordEnd);
    if (words.isEmpty()) {
      return;
    }
    if (drop == null) {
      out.addAll(words);
      return;
    }
    // the words joined by single spaces, as in the cleaned-up text, and where each of them starts
    StringBuilder text = new StringBuilder();
    int[] starts = new int[words.size()];
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        text.append(' ');
      }
      starts[i] = text.length();
      text.append(words.get(i).text);
    }
    boolean[] dropped = new boolean[words.size()];
    Matcher matcher = drop.matcher(text);
    while (matcher.find()) {
      for (int i = 0; i < words.size(); i++) {
        final int wordFrom = starts[i], wordTo = wordFrom + words.get(i).text.length();
        if (wordFrom < matcher.end() && wordTo > matcher.start()) {
          dropped[i] = true;
        }
      }
    }
    for (int i = 0; i < words.size(); i++) {
      if (!dropped[i]) {
        out.add(words.get(i));
      }
    }
  }

  private static boolean isBlank (byte[] data, int from, int length) {
    for (int i = from; i < from + length; i++) {
      if (data[i] != ' ' && data[i] != '\n' && data[i] != '\t') {
        return false;
      }
    }
    return true;
  }

  private static void addWord (List<Word> out, ByteArrayOutputStream bytes, long start, long end) {
    if (bytes.size() == 0) {
      return;
    }
    String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    bytes.reset();
    if (!text.isEmpty() && start >= 0) {
      out.add(new Word(start, Math.max(start, end), text));
    }
  }

  static @Nullable SttSubtitles build (List<Word> words) {
    if (words.isEmpty()) {
      return null;
    }
    List<Cue> cues = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    long start = 0, end = 0;
    for (int i = 0; i < words.size(); i++) {
      Word word = words.get(i);
      if (text.length() > 0) {
        boolean cut = word.startMs - end >= PAUSE_MS ||
          (text.length() + 1 + word.text.length() > MAX_CHARS && text.length() + 1 + sentenceTail(words, i) > MAX_CHARS + TAIL_CHARS);
        if (cut) {
          addCue(cues, start, end, text);
          text.setLength(0);
        } else {
          text.append(' ');
        }
      }
      if (text.length() == 0) {
        start = word.startMs;
      }
      text.append(word.text);
      end = word.endMs;
      char last = word.text.charAt(word.text.length() - 1);
      if (".!?…".indexOf(last) != -1 || ((last == ',' || last == ';' || last == ':') && text.length() >= MIN_CHARS_FOR_COMMA)) {
        addCue(cues, start, end, text);
        text.setLength(0);
      }
    }
    if (text.length() > 0) {
      addCue(cues, start, end, text);
    }
    removeRepeats(cues);
    // each phrase stays until the next one, at most LINGER_MS after its last word
    Cue[] result = new Cue[cues.size()];
    for (int i = 0; i < result.length; i++) {
      Cue cue = cues.get(i);
      long until = cue.endMs + LINGER_MS;
      if (i + 1 < result.length) {
        until = Math.min(until, cues.get(i + 1).startMs);
      }
      result[i] = new Cue(cue.startMs, Math.max(cue.endMs, until), cue.text);
    }
    return result.length > 0 ? new SttSubtitles(result) : null;
  }

  /** Collapses a phrase repeated three or more times in a row (a Whisper decoder loop) into one, like SttEngine.removeRepeats. */
  private static void removeRepeats (List<Cue> cues) {
    int count = 0;
    for (int i = 0; i < cues.size(); ) {
      int j = i + 1;
      while (j < cues.size() && cues.get(j).text.equalsIgnoreCase(cues.get(i).text)) {
        j++;
      }
      final int keep = j - i >= 3 ? 1 : j - i;
      for (int k = i; k < i + keep; k++) {
        cues.set(count++, cues.get(k));
      }
      i = j;
    }
    cues.subList(count, cues.size()).clear();
  }

  /** Length of the words from {@code from} to the end of their sentence, or a big number when it is far. */
  private static int sentenceTail (List<Word> words, int from) {
    int length = 0;
    for (int i = from; i < words.size() && length <= MAX_CHARS + TAIL_CHARS; i++) {
      Word word = words.get(i);
      if (i > from) {
        if (word.startMs - words.get(i - 1).endMs >= PAUSE_MS) {
          return Integer.MAX_VALUE / 2;
        }
        length++;
      }
      length += word.text.length();
      char last = word.text.charAt(word.text.length() - 1);
      if (".!?…".indexOf(last) != -1) {
        return length;
      }
    }
    return Integer.MAX_VALUE / 2;
  }

  private static void addCue (List<Cue> cues, long start, long end, StringBuilder text) {
    String value = SttEngine.restoreUnknown(text.toString()).replaceAll("\\s+", " ").trim();
    if (!value.isEmpty()) {
      cues.add(new Cue(start, end, value));
    }
  }
}
