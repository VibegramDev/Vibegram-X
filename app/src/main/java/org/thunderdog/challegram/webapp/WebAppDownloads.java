/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.webapp;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.util.Permissions;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import me.vkryl.core.lambda.RunnableLong;

/**
 * {@code web_app_request_file_download}: a confirmation, then the system DownloadManager into
 * public Downloads.
 *
 * <p>State is in memory only and touched on the UI thread: URLs already handed to DownloadManager
 * per (account user, bot), and the bots whose prompt is open.
 */
public final class WebAppDownloads {
  public static final String STATUS_DOWNLOADING = "downloading", STATUS_CANCELLED = "cancelled";

  /**
   * Called on the UI thread. Not called for a request that arrives while the prompt of the same bot
   * is still open: the documentation asks to ignore such duplicates.
   */
  public interface Callback {
    void onResult (String status);
  }

  private static final int PROBE_TIMEOUT_MS = 5000;

  private static final Map<String, Set<String>> startedUrls = new HashMap<>();
  private static final Set<String> pendingBots = new HashSet<>();

  private WebAppDownloads () { }

  public static void request (
    ViewController<?> context,
    Tdlib tdlib,
    long botUserId,
    String botName,
    String url,
    String fileName,
    Callback callback
  ) {
    String botKey = tdlib.myUserId(true) + "_" + botUserId;
    if (pendingBots.contains(botKey)) {
      return;
    }
    Set<String> urls = startedUrls.get(botKey);
    if (urls != null && urls.contains(url)) {
      callback.onResult(STATUS_DOWNLOADING);
      return;
    }
    Uri uri = parseHttpUrl(url);
    String safeFileName = sanitizeFileName(fileName);
    if (uri == null || safeFileName == null) {
      callback.onResult(STATUS_CANCELLED);
      return;
    }
    pendingBots.add(botKey);
    tdlib.send(new TdApi.CheckWebAppFileDownload(botUserId, fileName, url), (result, error) ->
      UI.post(() -> {
        if (error != null || context.isDestroyed()) {
          finish(botKey, callback, STATUS_CANCELLED);
        } else {
          showPrompt(context, botKey, botName, uri, url, safeFileName, callback);
        }
      })
    );
  }

  private static void finish (String botKey, Callback callback, String status) {
    pendingBots.remove(botKey);
    callback.onResult(status);
  }

  private static void showPrompt (
    ViewController<?> context,
    String botKey,
    String botName,
    Uri uri,
    String url,
    String fileName,
    Callback callback
  ) {
    CharSequence text = Lang.getStringBold(R.string.vg_BotDownloadFileText, botName);
    AlertPopup popup = context.showAlert(new AlertPopup.Builder(context.context())
      .setTitle(Lang.getString(R.string.vg_BotDownloadFileTitle))
      .setMessage(buildMessage(text, fileName, -1))
      .setPositiveButton(Lang.getString(R.string.vg_BotDownloadFileDownload), (dialog, which) ->
        startDownload(context, botKey, uri, url, fileName, callback)
      )
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) ->
        finish(botKey, callback, STATUS_CANCELLED)
      )
      .setOnCancelListener(dialog -> finish(botKey, callback, STATUS_CANCELLED))
    );
    if (popup == null) {
      finish(botKey, callback, STATUS_CANCELLED);
      return;
    }
    probeSize(url, size -> {
      TextView messageView = popup.getMessageView();
      if (size > 0 && messageView != null && popup.isShowing()) {
        messageView.setText(buildMessage(text, fileName, size));
      }
    });
  }

  private static CharSequence buildMessage (CharSequence text, String fileName, long size) {
    if (size > 0) {
      return TextUtils.concat(text, "\n\n", fileName, "\n", Strings.buildSize(size));
    } else {
      return TextUtils.concat(text, "\n\n", fileName);
    }
  }

  private static void startDownload (
    ViewController<?> context,
    String botKey,
    Uri uri,
    String url,
    String fileName,
    Callback callback
  ) {
    // Since Android 10 DownloadManager writes to public Downloads without the storage permission.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !context.isDestroyed()) {
      boolean isRequesting = context.context().permissions().requestWriteExternalStorage(
        Permissions.WriteType.DOWNLOADS,
        granted -> {
          if (granted) {
            enqueue(botKey, uri, url, fileName, callback);
          } else {
            finish(botKey, callback, STATUS_CANCELLED);
          }
        }
      );
      if (isRequesting) {
        return;
      }
    }
    enqueue(botKey, uri, url, fileName, callback);
  }

  private static void enqueue (
    String botKey,
    Uri uri,
    String url,
    String fileName,
    Callback callback
  ) {
    try {
      DownloadManager manager =
        (DownloadManager) AppContext.get().getSystemService(Context.DOWNLOAD_SERVICE);
      if (manager == null) {
        finish(botKey, callback, STATUS_CANCELLED);
        return;
      }
      DownloadManager.Request request = new DownloadManager.Request(uri);
      request.setTitle(fileName);
      request.setNotificationVisibility(
        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
      );
      request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
      manager.enqueue(request);
    } catch (Throwable t) {
      Log.e("Cannot start a mini app download", t);
      finish(botKey, callback, STATUS_CANCELLED);
      return;
    }
    Set<String> urls = startedUrls.get(botKey);
    if (urls == null) {
      urls = new HashSet<>();
      startedUrls.put(botKey, urls);
    }
    urls.add(url);
    finish(botKey, callback, STATUS_DOWNLOADING);
  }

  /**
   * HEAD request for Content-Length; {@code after} runs on the UI thread, only when the size is
   * known.
   */
  private static void probeSize (String url, RunnableLong after) {
    new Thread(() -> {
      long size = -1;
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("HEAD");
        connection.setConnectTimeout(PROBE_TIMEOUT_MS);
        connection.setReadTimeout(PROBE_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        int responseCode = connection.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            size = connection.getContentLengthLong();
          } else {
            size = parseLength(connection.getHeaderField("Content-Length"));
          }
        }
      } catch (Throwable t) {
        Log.w("Cannot get the size of a mini app download", t);
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
      if (size > 0) {
        long knownSize = size;
        UI.post(() -> after.runWithLong(knownSize));
      }
    }, "WebAppDownloadProbe").start();
  }

  private static long parseLength (@Nullable String value) {
    if (value == null) {
      return -1;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static @Nullable Uri parseHttpUrl (@Nullable String url) {
    if (url == null || url.isEmpty()) {
      return null;
    }
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme();
    boolean isHttp = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    return isHttp && !TextUtils.isEmpty(uri.getHost()) ? uri : null;
  }

  /** The name goes into a path under Downloads: separators would let it leave the folder. */
  private static @Nullable String sanitizeFileName (@Nullable String fileName) {
    if (fileName == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder(fileName.length());
    for (int i = 0; i < fileName.length(); i++) {
      char c = fileName.charAt(i);
      builder.append(c == '/' || c == '\\' || c < ' ' ? '_' : c);
    }
    String result = builder.toString().trim();
    if (result.isEmpty() || result.equals(".") || result.equals("..")) {
      return null;
    }
    return result;
  }
}
