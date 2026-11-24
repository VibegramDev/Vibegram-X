/*
 * Vibegram X: «English phrases through Whisper» — the Parakeet text with the English phrases of Whisper Small.
 *
 * The Russian Parakeet build hears a fast or accented English phrase as Russian syllables and writes it in Cyrillic
 * («Ар ю вининг, Сан»): on the owner's voice the Latin text scores far worse for the model itself, so no decoding
 * setting fixes it. Whisper Small writes such phrases in Latin, even inside a Russian sentence, but it makes about
 * twice as many errors in Russian words. So the result is the Parakeet text, and only the runs of words that Whisper
 * wrote in Latin replace the Parakeet words they were aligned with. Measured on a Mac: the 8 Russian test notes
 * come out unchanged (Whisper writes the brand names there in Cyrillic or Parakeet already has them in Latin),
 * the owner's three «are you winning son» notes come out in Latin.
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

final class SttEnglishMerge {
  private SttEnglishMerge () { }

  /**
   * Words further apart in time than this are never paired. The gap is between the word intervals, not their centers:
   * the two models stretch a word over a following pause differently (Parakeet gave one word 6.5 s, Whisper 2.5 s),
   * so centers of the same word were 3.6 s apart, while the intervals overlapped.
   */
  private static final long MAX_TIME_GAP_MS = 2000;
  private static final float GAP_COST = .7f, FAR_COST = 2f;

  /**
   * @param replaceLatin replace the words the main model already wrote in Latin too: GigaAM spells English it does
   *   not know in Latin letters by sound («Arivingson»), Parakeet's Latin words are real English words
   * @return the main words with the English runs of Whisper in place of the words aligned with them,
   *   or null when nothing changes (no Latin run, or the main model already wrote it the same way)
   */
  static @Nullable List<SttSubtitles.Word> merge (List<SttSubtitles.Word> main, List<SttSubtitles.Word> english, boolean replaceLatin) {
    if (main.isEmpty() || !hasLatinWord(english)) {
      return null;
    }
    int[][] pairs = align(main, english);
    List<SttSubtitles.Word> out = new ArrayList<>(main.size());
    boolean changed = false;
    int k = 0;
    while (k < pairs.length) {
      int mi = pairs[k][0], ei = pairs[k][1];
      if (ei == -1 || !isLatin(bare(english.get(ei).text))) {
        if (mi != -1) {
          out.add(main.get(mi));
        }
        k++;
        continue;
      }
      // A run: Whisper words in Latin (numbers between them stay in the run) and the Parakeet words paired with them
      int runStart = k;
      while (k < pairs.length) {
        int m = pairs[k][0], e = pairs[k][1];
        if (e != -1) {
          String b = bare(english.get(e).text);
          if (!(isLatin(b) || (!b.isEmpty() && !hasCyrillic(b)))) {
            break;
          }
        } else if (!latinFollows(pairs, english, k + 1)) {
          break;
        }
        k++;
      }
      // a run that ends with numbers or a word only the main model heard: give them back to the main text
      while (k > runStart + 1 && !endsRun(pairs[k - 1], main, english, replaceLatin)) {
        k--;
      }
      List<SttSubtitles.Word> oldWords = new ArrayList<>(), newWords = new ArrayList<>();
      for (int r = runStart; r < k; r++) {
        if (pairs[r][0] != -1) {
          oldWords.add(main.get(pairs[r][0]));
        }
        if (pairs[r][1] != -1) {
          newWords.add(english.get(pairs[r][1]));
        }
      }
      if (oldWords.isEmpty() || (replaceLatin ? sameWords(oldWords, newWords) : allLatin(oldWords))) {
        // no speech under it in the Parakeet text (Whisper invents words on noise), or already in Latin
        out.addAll(oldWords);
        continue;
      }
      out.addAll(dress(oldWords, newWords));
      changed = true;
    }
    return changed ? out : null;
  }

  /**
   * Parakeet knows where its sentence starts and ends: the run takes its leading punctuation and the case
   * of its first letter, and its trailing punctuation unless Whisper heard a question or an exclamation.
   */
  private static List<SttSubtitles.Word> dress (List<SttSubtitles.Word> oldWords, List<SttSubtitles.Word> newWords) {
    String oldFirst = oldWords.get(0).text, oldLast = oldWords.get(oldWords.size() - 1).text;
    String lead = oldFirst.substring(0, leadingPunctuation(oldFirst));
    String oldTail = oldLast.substring(oldLast.length() - trailingPunctuation(oldLast));
    List<SttSubtitles.Word> result = new ArrayList<>(newWords);
    int last = result.size() - 1;
    SttSubtitles.Word lastWord = result.get(last);
    String newTail = lastWord.text.substring(lastWord.text.length() - trailingPunctuation(lastWord.text));
    String tail = newTail.indexOf('?') != -1 || newTail.indexOf('!') != -1 ? newTail.replaceAll("[\"«»“”']", "") : oldTail;
    SttSubtitles.Word first = result.get(0);
    String firstText = bare(first.text);
    String oldBare = bare(oldFirst);
    if (!oldBare.isEmpty() && !firstText.isEmpty()) {
      int c = firstText.codePointAt(0);
      int cased = Character.isUpperCase(oldBare.codePointAt(0)) ? Character.toUpperCase(c) : Character.toLowerCase(c);
      firstText = new StringBuilder().appendCodePoint(cased).append(firstText.substring(Character.charCount(c))).toString();
    }
    if (last == 0) {
      result.set(0, new SttSubtitles.Word(first.startMs, first.endMs, lead + firstText + tail));
    } else {
      result.set(0, new SttSubtitles.Word(first.startMs, first.endMs, lead + firstText + trailingOf(first.text)));
      result.set(last, new SttSubtitles.Word(lastWord.startMs, lastWord.endMs, lastWord.text.substring(0, lastWord.text.length() - trailingPunctuation(lastWord.text)) + tail));
    }
    return result;
  }

  private static String trailingOf (String text) {
    String t = text.substring(text.length() - trailingPunctuation(text));
    return t.replaceAll("[\"«»“”]", "");
  }

  /** Needleman–Wunsch over words: similar spelling (Latin read as Cyrillic) pairs words, far apart in time never. */
  private static int[][] align (List<SttSubtitles.Word> a, List<SttSubtitles.Word> b) {
    final int n = a.size(), m = b.size();
    String[] ka = new String[n], kb = new String[m];
    for (int i = 0; i < n; i++) {
      ka[i] = key(a.get(i).text);
    }
    for (int j = 0; j < m; j++) {
      kb[j] = key(b.get(j).text);
    }
    float[] prev = new float[m + 1], cur = new float[m + 1];
    byte[][] back = new byte[n + 1][m + 1];
    for (int j = 1; j <= m; j++) {
      prev[j] = j * GAP_COST;
      back[0][j] = 2;
    }
    for (int i = 1; i <= n; i++) {
      cur[0] = i * GAP_COST;
      back[i][0] = 1;
      SttSubtitles.Word wa = a.get(i - 1);
      for (int j = 1; j <= m; j++) {
        SttSubtitles.Word wb = b.get(j - 1);
        long gap = Math.max(wa.startMs, wb.startMs) - Math.min(wa.endMs, wb.endMs);
        float pairCost = gap > MAX_TIME_GAP_MS ? FAR_COST : 1f - similarity(ka[i - 1], kb[j - 1]);
        float best = prev[j - 1] + pairCost;
        byte dir = 0;
        if (prev[j] + GAP_COST < best) {
          best = prev[j] + GAP_COST;
          dir = 1;
        }
        if (cur[j - 1] + GAP_COST < best) {
          best = cur[j - 1] + GAP_COST;
          dir = 2;
        }
        cur[j] = best;
        back[i][j] = dir;
      }
      float[] swap = prev;
      prev = cur;
      cur = swap;
    }
    ArrayList<int[]> pairs = new ArrayList<>(Math.max(n, m));
    int i = n, j = m;
    while (i > 0 || j > 0) {
      byte dir = back[i][j];
      if (dir == 0) {
        pairs.add(new int[] {--i, --j});
      } else if (dir == 1) {
        pairs.add(new int[] {--i, -1});
      } else {
        pairs.add(new int[] {-1, --j});
      }
    }
    int[][] result = new int[pairs.size()][];
    for (int p = 0; p < result.length; p++) {
      result[p] = pairs.get(pairs.size() - 1 - p);
    }
    return result;
  }

  private static boolean latinFollows (int[][] pairs, List<SttSubtitles.Word> english, int from) {
    for (int k = from; k < Math.min(pairs.length, from + 2); k++) {
      int e = pairs[k][1];
      if (e != -1 && isLatin(bare(english.get(e).text))) {
        return true;
      }
    }
    return false;
  }

  private static final String LATIN_AS_CYRILLIC = "абкдефгхижклмнопкрстуввксйз";

  /** Lower case, «ё» as «е», Latin letters read as Cyrillic ones: «are» → «аре» is close to «ар». */
  private static String key (String word) {
    String b = bare(word).toLowerCase();
    StringBuilder s = new StringBuilder(b.length());
    for (int i = 0; i < b.length(); i++) {
      char c = b.charAt(i);
      if (c >= 'a' && c <= 'z') {
        switch (c) {
          case 'j': s.append("дж"); break;
          case 'x': s.append("кс"); break;
          case 'w': s.append('в'); break;
          case 'y': s.append('й'); break;
          case 'z': s.append('з'); break;
          default: s.append(LATIN_AS_CYRILLIC.charAt(c - 'a'));
        }
      } else {
        s.append(c == 'ё' ? 'е' : c);
      }
    }
    return s.toString();
  }

  /** 1 − edit distance / longer length. */
  private static float similarity (String a, String b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0f;
    }
    int[] d = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      d[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      int p = d[0];
      d[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int t = d[j];
        d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), p + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        p = t;
      }
    }
    return 1f - d[b.length()] / (float) Math.max(a.length(), b.length());
  }

  private static boolean hasLatinWord (List<SttSubtitles.Word> words) {
    for (SttSubtitles.Word w : words) {
      if (isLatin(bare(w.text))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The last pair of a run: a Latin word of Whisper, or (when Latin words of the main model are replaced too)
   * a Latin piece of the main model with no Whisper word («Spoty Fi» for «Spotify»).
   */
  private static boolean endsRun (int[] pair, List<SttSubtitles.Word> main, List<SttSubtitles.Word> english, boolean replaceLatin) {
    if (pair[1] != -1) {
      return isLatin(bare(english.get(pair[1]).text));
    }
    return replaceLatin && pair[0] != -1 && isLatin(bare(main.get(pair[0]).text));
  }

  private static boolean sameWords (List<SttSubtitles.Word> a, List<SttSubtitles.Word> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!bare(a.get(i).text).equalsIgnoreCase(bare(b.get(i).text))) {
        return false;
      }
    }
    return true;
  }

  private static boolean allLatin (List<SttSubtitles.Word> words) {
    for (SttSubtitles.Word w : words) {
      String b = bare(w.text);
      if (!b.isEmpty() && !isLatin(b)) {
        return false;
      }
    }
    return true;
  }

  /** Has a Latin letter and no Cyrillic one. */
  static boolean isLatin (String s) {
    boolean latin = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        latin = true;
      } else if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
        return false;
      }
    }
    return latin;
  }

  private static boolean hasCyrillic (String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.UnicodeBlock.of(s.charAt(i)) == Character.UnicodeBlock.CYRILLIC) {
        return true;
      }
    }
    return false;
  }

  /** The word without punctuation around it. */
  static String bare (String s) {
    int start = leadingPunctuation(s), end = s.length() - trailingPunctuation(s);
    return start < end ? s.substring(start, end) : "";
  }

  private static int leadingPunctuation (String s) {
    int i = 0;
    while (i < s.length() && !Character.isLetterOrDigit(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int trailingPunctuation (String s) {
    int i = s.length();
    while (i > 0 && !Character.isLetterOrDigit(s.charAt(i - 1))) {
      i--;
    }
    return s.length() - i;
  }
}
