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

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.util.Permissions;

import java.util.HashSet;
import java.util.Set;

import me.vkryl.core.StringUtils;

/**
 * {@code web_app_request_file_download}: a confirmation, then the system DownloadManager into
 * public Downloads.
 *
 * <p>State is in memory only and touched on the UI thread: the bots whose prompt is open, per
 * account user.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class WebAppDownloads {
  public static final String STATUS_DOWNLOADING = "downloading", STATUS_CANCELLED = "cancelled";

  /**
   * Called on the UI thread. Not called for a request that arrives while the prompt of the same bot
   * is still open: the documentation asks to ignore such duplicates.
   */
  public interface Callback {
    void onResult (String status);
  }

  // UTF-16 units: even 3-byte characters stay within the 255-byte name limit of file systems.
  private static final int MAX_FILE_NAME_LENGTH = 80;
  private static final int MAX_EXTENSION_LENGTH = 16;

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
    Uri uri = parseHttpsUrl(url);
    String safeFileName = toSafeFileName(fileName);
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
          showPrompt(context, botKey, botName, uri, safeFileName, callback);
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
    String fileName,
    Callback callback
  ) {
    CharSequence text = Lang.getStringBold(R.string.vg_BotDownloadFileText, botName);
    AlertPopup popup = context.showAlert(new AlertPopup.Builder(context.context())
      .setTitle(Lang.getString(R.string.vg_BotDownloadFileTitle))
      .setMessage(TextUtils.concat(text, "\n\n", fileName))
      .setPositiveButton(Lang.getString(R.string.vg_BotDownloadFileDownload), (dialog, which) ->
        startDownload(context, botKey, uri, fileName, callback)
      )
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) ->
        finish(botKey, callback, STATUS_CANCELLED)
      )
      .setOnCancelListener(dialog -> finish(botKey, callback, STATUS_CANCELLED))
    );
    if (popup == null) {
      finish(botKey, callback, STATUS_CANCELLED);
    }
  }

  private static void startDownload (
    ViewController<?> context,
    String botKey,
    Uri uri,
    String fileName,
    Callback callback
  ) {
    // Since Android 10 DownloadManager writes to public Downloads without the storage permission.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !context.isDestroyed()) {
      boolean isRequesting = context.context().permissions().requestWriteExternalStorage(
        Permissions.WriteType.DOWNLOADS,
        granted -> {
          if (granted) {
            enqueue(botKey, uri, fileName, callback);
          } else {
            finish(botKey, callback, STATUS_CANCELLED);
          }
        }
      );
      if (isRequesting) {
        return;
      }
    }
    enqueue(botKey, uri, fileName, callback);
  }

  private static void enqueue (String botKey, Uri uri, String fileName, Callback callback) {
    DownloadManager manager =
      (DownloadManager) AppContext.get().getSystemService(Context.DOWNLOAD_SERVICE);
    if (manager == null) {
      finish(botKey, callback, STATUS_CANCELLED);
      return;
    }
    try {
      DownloadManager.Request request = new DownloadManager.Request(uri);
      request.setTitle(fileName);
      request.setNotificationVisibility(
        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
      );
      // Throws IllegalStateException when external storage is unavailable.
      request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
      manager.enqueue(request);
    } catch (IllegalStateException | SecurityException e) {
      Log.e("Cannot start a mini app download", e);
      finish(botKey, callback, STATUS_CANCELLED);
      return;
    }
    finish(botKey, callback, STATUS_DOWNLOADING);
  }

  private static @Nullable Uri parseHttpsUrl (@Nullable String url) {
    if (StringUtils.isEmpty(url)) {
      return null;
    }
    Uri uri = Uri.parse(url);
    boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
    return isHttps && !TextUtils.isEmpty(uri.getHost()) ? uri : null;
  }

  /**
   * The name goes into a path under Downloads and into the confirmation: besides separators and
   * leading dots ({@link U#getSecureFileName}) it loses control and format characters, so that
   * bidi overrides can't disguise the extension, and gets a length that file systems accept.
   */
  private static @Nullable String toSafeFileName (@Nullable String fileName) {
    if (fileName == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder(fileName.length());
    for (int i = 0; i < fileName.length(); i++) {
      char c = fileName.charAt(i);
      if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) {
        builder.append(c);
      }
    }
    String name = U.getSecureFileName(builder.toString().trim());
    if (StringUtils.isEmpty(name)) {
      return null;
    }
    if (name.length() > MAX_FILE_NAME_LENGTH) {
      int dot = name.lastIndexOf('.');
      String extension =
        dot > 0 && name.length() - dot <= MAX_EXTENSION_LENGTH ? name.substring(dot) : "";
      int end = MAX_FILE_NAME_LENGTH - extension.length();
      if (Character.isHighSurrogate(name.charAt(end - 1))) {
        end--;
      }
      name = name.substring(0, end) + extension;
    }
    return name;
  }
}
