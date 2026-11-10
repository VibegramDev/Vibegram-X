/*
 * This file is a part of Vibegram-X
 *
 * Temporary diagnostics for the "taps do nothing until Back" state: BaseActivity.isNavigationBusy() stays true
 * (a popup that never finished showing, the recorder left "open", or a navigation/header animation that never ended).
 * Keeps the last UI events in memory and, once the app stays busy for STUCK_AFTER_MS, writes them together with
 * the busy state to logs/vg_stuck.log (Settings → long press on the version → App logs → Log files).
 */
package org.thunderdog.challegram.util;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class StuckWatch {
  private static final String TAG = "VGSTUCK";
  private static final String FILE_NAME = "vg_stuck.log";
  private static final int MAX_EVENTS = 60;
  private static final long MAX_FILE_SIZE = 512 * 1024;

  public static final long CHECK_INTERVAL_MS = 1000;
  public static final int STUCK_AFTER_TICKS = 3;

  private static final ArrayDeque<String> events = new ArrayDeque<>(MAX_EVENTS);
  private static final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

  private StuckWatch () { }

  private static String now () {
    return timeFormat.format(new Date());
  }

  /** Remembers a UI event (main thread). */
  public static void event (@NonNull String event) {
    if (events.size() == MAX_EVENTS) {
      events.removeFirst();
    }
    events.addLast(now() + " " + event);
  }

  /** Writes the busy state and the recent events to the log file and logcat. */
  public static void report (@NonNull String title, @NonNull String state, boolean withEvents) {
    StringBuilder b = new StringBuilder();
    b.append("=== ").append(now()).append(' ').append(title).append('\n');
    b.append(state).append('\n');
    if (withEvents) {
      b.append("--- last events:\n");
      for (String event : events) {
        b.append(event).append('\n');
      }
    }
    String text = b.toString();
    android.util.Log.e(TAG, text);
    write(text);
  }

  private static void write (String text) {
    try {
      File dir = Log.getLogDir();
      if (dir == null) {
        return;
      }
      File file = new File(dir, FILE_NAME);
      boolean append = !file.exists() || file.length() < MAX_FILE_SIZE;
      try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, append), StandardCharsets.UTF_8)) {
        writer.write(text);
        writer.write('\n');
      }
    } catch (Throwable t) {
      android.util.Log.e(TAG, "Cannot write " + FILE_NAME, t);
    }
  }
}
