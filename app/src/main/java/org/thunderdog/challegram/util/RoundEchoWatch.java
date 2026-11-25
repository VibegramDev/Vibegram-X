/*
 * This file is a part of Vibegram-X
 *
 * Temporary diagnostics for the round video "echo" (the same sound twice, the second a bit later, gone after a force
 * stop). Every ExoPlayer the app creates is registered here with who created it; every RoundVideoController (one per
 * activity) reports its creation, destruction and round starts. Two seconds after a round starts, and whenever two
 * players play at the same time, the state of all players and controllers plus the recent events is written to
 * logs/vg_round_audio.log (Settings → long press on the version → App logs → Log files).
 */
package org.thunderdog.challegram.util;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.player.RoundVideoController;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class RoundEchoWatch {
  private static final String TAG = "VGECHO";
  private static final String FILE_NAME = "vg_round_audio.log";
  private static final int MAX_EVENTS = 80;
  private static final long MAX_FILE_SIZE = 512 * 1024;
  private static final long SNAPSHOT_DELAY_MS = 2000;
  private static final long ECHO_REPORT_INTERVAL_MS = 5000;

  private static final ArrayDeque<String> events = new ArrayDeque<>(MAX_EVENTS);
  private static final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
  private static final WeakHashMap<ExoPlayer, String> players = new WeakHashMap<>();
  private static final WeakHashMap<RoundVideoController, Boolean> controllers = new WeakHashMap<>();
  private static long lastEchoReport;

  private RoundEchoWatch () { }

  private static String now () {
    return timeFormat.format(new Date());
  }

  private static String id (@Nullable Object o) {
    return o != null ? Integer.toHexString(System.identityHashCode(o)) : "null";
  }

  private static synchronized void event (@NonNull String event) {
    if (events.size() == MAX_EVENTS) {
      events.removeFirst();
    }
    events.addLast(now() + " " + event);
  }

  // Players

  /** Called for every ExoPlayer the app creates (U.newExoPlayer). */
  public static void onPlayerCreated (@NonNull ExoPlayer player, @Nullable Context context) {
    StringBuilder origin = new StringBuilder();
    origin.append(context != null ? context.getClass().getSimpleName() + "@" + id(context) : "no context");
    StackTraceElement[] stack = new Throwable().getStackTrace();
    for (int i = 2; i < Math.min(stack.length, 7); i++) {
      origin.append(" < ").append(stack[i].getClassName()).append('.').append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
    }
    String label = origin.toString();
    synchronized (RoundEchoWatch.class) {
      players.put(player, label);
    }
    event("player " + id(player) + " created: " + label);
    player.addListener(new Player.Listener() {
      @Override
      public void onIsPlayingChanged (boolean isPlaying) {
        event("player " + id(player) + (isPlaying ? " plays" : " stops"));
        if (isPlaying) {
          checkEcho();
        }
      }
    });
  }

  private static void checkEcho () {
    int playing = 0;
    for (ExoPlayer player : snapshotPlayers().keySet()) {
      if (isPlaying(player)) {
        playing++;
      }
    }
    if (playing > 1) {
      long time = SystemClock.uptimeMillis();
      if (time - lastEchoReport >= ECHO_REPORT_INTERVAL_MS) {
        lastEchoReport = time;
        report("ECHO: " + playing + " players play at once");
      }
    }
  }

  private static boolean isPlaying (ExoPlayer player) {
    try {
      return player.getApplicationLooper() == Looper.myLooper() && !player.isReleased() && player.isPlaying();
    } catch (Throwable t) {
      return false;
    }
  }

  private static synchronized Map<ExoPlayer, String> snapshotPlayers () {
    return new java.util.HashMap<>(players);
  }

  // Round video controllers (one per activity)

  public static void onControllerCreated (@NonNull RoundVideoController controller, @NonNull Context activity) {
    synchronized (RoundEchoWatch.class) {
      controllers.put(controller, Boolean.TRUE);
    }
    event("round controller " + id(controller) + " created for " + activity.getClass().getSimpleName() + "@" + id(activity));
  }

  public static void onControllerDestroyed (@NonNull RoundVideoController controller) {
    event("round controller " + id(controller) + " destroyed with its activity");
  }

  public static void onRoundStarted (@NonNull RoundVideoController controller, @NonNull TdApi.Message message) {
    event("round controller " + id(controller) + " starts message " + message.chatId + "/" + message.id);
    UI.post(() -> report("round started"), SNAPSHOT_DELAY_MS);
  }

  // Report

  private static void report (@NonNull String title) {
    StringBuilder b = new StringBuilder();
    b.append("=== ").append(now()).append(' ').append(title).append('\n');
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      b.append("process uptime: ").append(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).append(" ms\n");
    }

    List<RoundVideoController> controllerList;
    synchronized (RoundEchoWatch.class) {
      controllerList = new ArrayList<>(controllers.keySet());
    }
    b.append("--- round controllers: ").append(controllerList.size()).append('\n');
    for (RoundVideoController controller : controllerList) {
      b.append(id(controller)).append(": ").append(controller.describeForDiagnostics()).append('\n');
    }

    Map<ExoPlayer, String> playerMap = snapshotPlayers();
    b.append("--- players: ").append(playerMap.size()).append('\n');
    for (Map.Entry<ExoPlayer, String> entry : playerMap.entrySet()) {
      b.append(id(entry.getKey())).append(": ").append(describePlayer(entry.getKey())).append("; ").append(entry.getValue()).append('\n');
    }

    b.append("--- last events:\n");
    synchronized (RoundEchoWatch.class) {
      for (String event : events) {
        b.append(event).append('\n');
      }
    }
    String text = b.toString();
    android.util.Log.i(TAG, text);
    write(text);
  }

  private static String describePlayer (ExoPlayer player) {
    try {
      if (player.getApplicationLooper() != Looper.myLooper()) {
        return "other thread";
      }
      if (player.isReleased()) {
        return "released";
      }
      return "playing=" + player.isPlaying() +
        " state=" + player.getPlaybackState() +
        " playWhenReady=" + player.getPlayWhenReady() +
        " position=" + player.getCurrentPosition() +
        " volume=" + player.getVolume() +
        " audioSession=" + player.getAudioSessionId() +
        " media=" + (player.getCurrentMediaItem() != null ? player.getCurrentMediaItem().mediaId : "none");
    } catch (Throwable t) {
      return t.toString();
    }
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
