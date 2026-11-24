/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.mediaview;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;
import me.vkryl.leveldb.LevelDB;

/**
 * Vibegram X: the place a video of a message was left at in the media viewer, so opening it again goes on from there
 * (like the official app). Kept on disk by the file's remote unique id, the oldest are dropped above {@link #MAX_COUNT}.
 */
public final class VideoPositions {
  private VideoPositions () { }

  private static final String KEY_PREFIX = "vg_video_pos_";
  /** Shorter videos always start from the beginning. */
  private static final long MIN_DURATION_MS = 10_000;
  /** Closer to the start or to the end than this means "start from the beginning" next time. */
  private static final long EDGE_MS = 2_000;
  private static final int MAX_COUNT = 300;

  private static boolean trimmed;

  /** Key of the video, or {@code null} if its place is not remembered (GIF, secret, round, gallery or short video). */
  public static @Nullable String keyOf (@Nullable MediaItem item) {
    if (item == null || item.getType() != MediaItem.TYPE_VIDEO || item.isSecret() || item.isRoundVideo() || item.needTrim() || item.getSourceGalleryFile() != null) {
      return null;
    }
    if (item.getVideoDuration(false, TimeUnit.MILLISECONDS) < MIN_DURATION_MS) {
      return null;
    }
    TdApi.File file = item.getTargetFile();
    if (file == null || file.remote == null || StringUtils.isEmpty(file.remote.uniqueId)) {
      return null;
    }
    return KEY_PREFIX + file.remote.uniqueId;
  }

  /** The remembered place in ms, or 0. */
  public static long get (@Nullable String key) {
    if (key == null) {
      return 0;
    }
    long[] value = Settings.instance().getLongArray(key);
    return value != null && value.length == 2 ? value[0] : 0;
  }

  public static void save (@Nullable String key, long positionMs, long durationMs) {
    if (key == null || durationMs <= 0) {
      return;
    }
    if (positionMs < EDGE_MS || positionMs > durationMs - EDGE_MS) {
      Settings.instance().remove(key);
      return;
    }
    Settings.instance().putLongArray(key, new long[] {positionMs, System.currentTimeMillis()});
    if (!trimmed) {
      trimmed = true;
      trim();
    }
  }

  /** Drops the oldest places above {@link #MAX_COUNT}, once per launch. */
  private static void trim () {
    LevelDB pmc = Settings.instance().pmc();
    List<long[]> times = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (LevelDB.Entry entry : pmc.find(KEY_PREFIX)) {
      long[] value = entry.asLongArray();
      keys.add(entry.key());
      times.add(new long[] {value != null && value.length == 2 ? value[1] : 0, keys.size() - 1});
    }
    if (keys.size() <= MAX_COUNT) {
      return;
    }
    Collections.sort(times, (a, b) -> Long.compare(a[0], b[0]));
    for (int i = 0; i < keys.size() - MAX_COUNT; i++) {
      pmc.remove(keys.get((int) times.get(i)[1]));
    }
  }
}
