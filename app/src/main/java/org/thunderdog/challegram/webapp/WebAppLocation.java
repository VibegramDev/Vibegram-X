/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BotLocation from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.helper.LocationHelper;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.leveldb.LevelDB;

/**
 * Location access of a mini app ({@code web_app_check_location}, {@code web_app_request_location}),
 * per (account user, bot). The bot's grant lives in the TGX pmc under
 * {@code webapp_location_<accountUserId>_<botId>_granted} / {@code _requested}. All methods are
 * called on the UI thread.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class WebAppLocation {
  public interface Callback {
    void onResult (JSONObject payload);
  }

  private static final long LOCATION_TIMEOUT_MS = 30_000L;
  private static final String KEY_PREFIX = "webapp_location_";
  private static final String KEY_OS_PERMISSION_ASKED = "webapp_location_os_asked";
  private static final String KEY_GRANTED = "_granted", KEY_REQUESTED = "_requested";

  private final String keyPrefix;
  private boolean isPending, isReleased;
  private @Nullable LocationHelper locationHelper;

  public WebAppLocation (Tdlib tdlib, long botUserId) {
    this.keyPrefix = keyPrefix(tdlib, botUserId);
  }

  // State

  private static String keyPrefix (Tdlib tdlib, long botUserId) {
    return KEY_PREFIX + tdlib.myUserId(true) + "_" + botUserId;
  }

  private static LevelDB pmc () {
    return Settings.instance().pmc();
  }

  private static boolean isAvailable () {
    return AppContext.get().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION);
  }

  private static boolean hasPermission (String permission) {
    return ContextCompat.checkSelfPermission(AppContext.get(), permission) ==
      PackageManager.PERMISSION_GRANTED;
  }

  // LocationHelper requests both permissions until both are granted, so an approximate-only grant
  // does not count.
  private static boolean hasOsPermission () {
    return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
      hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
  }

  /** False when the app has lost the OS permission, whatever the bot was granted before. */
  private static boolean isGranted (String keyPrefix) {
    boolean granted = pmc().getBoolean(keyPrefix + KEY_GRANTED, false);
    if (granted && !hasOsPermission()) {
      // The bot asks again once the OS permission is back.
      pmc().remove(keyPrefix + KEY_GRANTED);
      pmc().remove(keyPrefix + KEY_REQUESTED);
      return false;
    }
    return granted;
  }

  public static boolean isGranted (Tdlib tdlib, long botUserId) {
    return isGranted(keyPrefix(tdlib, botUserId));
  }

  /** Forgets the decision, so the bot can ask again. */
  public static void revoke (Tdlib tdlib, long botUserId) {
    String keyPrefix = keyPrefix(tdlib, botUserId);
    pmc().remove(keyPrefix + KEY_GRANTED);
    pmc().remove(keyPrefix + KEY_REQUESTED);
  }

  public static void onLoggedOut (long userId) {
    pmc().removeByPrefix(KEY_PREFIX + userId + "_");
  }

  private boolean isRequested () {
    return pmc().getBoolean(keyPrefix + KEY_REQUESTED, false);
  }

  private void setGranted (boolean granted) {
    pmc().putBoolean(keyPrefix + KEY_REQUESTED, true);
    if (granted) {
      pmc().putBoolean(keyPrefix + KEY_GRANTED, true);
    } else {
      pmc().remove(keyPrefix + KEY_GRANTED);
    }
  }

  /** {@code location_checked} payload. */
  public JSONObject check () {
    JSONObject payload = new JSONObject();
    try {
      boolean available = isAvailable();
      payload.put("available", available);
      if (available) {
        boolean granted = isGranted(keyPrefix);
        boolean requested = isRequested();
        payload.put("access_requested", requested);
        if (requested) {
          payload.put("access_granted", granted);
        }
      }
    } catch (JSONException e) {
      Log.e("Cannot build location state", e);
    }
    return payload;
  }

  // Request

  /** {@code web_app_request_location}: replies a {@code location_requested} payload. */
  public void request (ViewController<?> context, String botName, Callback callback) {
    if (isPending || !isAvailable() || context.isDestroyed()) {
      callback.onResult(unavailable());
      return;
    }
    boolean granted = isGranted(keyPrefix);
    if (isRequested()) {
      if (granted) {
        requestLocation(context.context(), callback);
      } else {
        callback.onResult(unavailable());
      }
      return;
    }
    BaseActivity activity = context.context();
    boolean isPermanentlyDenied = !hasOsPermission() &&
      pmc().getBoolean(KEY_OS_PERMISSION_ASKED, false) &&
      !activity.permissions().shouldShowAccessLocationRationale();
    int positiveText = isPermanentlyDenied ? R.string.Settings : R.string.Allow;
    isPending = true;
    AlertPopup popup = context.showAlert(new AlertPopup.Builder(activity)
      .setMessage(Lang.getStringBold(R.string.vg_BotLocationRequest, botName))
      .setPositiveButton(Lang.getString(positiveText), (dialog, which) -> {
        if (isPermanentlyDenied) {
          // Not a decision on the bot: it may ask again once the permission is enabled.
          Intents.openPermissionSettings();
          finish(callback, null);
        } else {
          if (!hasOsPermission()) {
            pmc().putBoolean(KEY_OS_PERMISSION_ASKED, true);
          }
          requestLocation(activity, callback);
        }
      })
      .setNegativeButton(
        Lang.getString(R.string.vg_BotLocationPermissionDecline),
        (dialog, which) -> deny(callback)
      )
      .setOnCancelListener(dialog -> deny(callback))
    );
    if (popup == null) {
      finish(callback, null);
    }
  }

  /** The bot alert has already been answered, so the OS permission is requested without the TGX one. */
  private void requestLocation (BaseActivity activity, Callback callback) {
    if (isReleased) {
      finish(callback, null);
      return;
    }
    isPending = true;
    LocationHelper helper = LocationHelper.requestLocation(activity, LOCATION_TIMEOUT_MS, true, false, true,
      (errorCode, location) -> {
        locationHelper = null;
        setGranted(hasOsPermission());
        boolean hasFix = errorCode == LocationHelper.ERROR_CODE_NONE ||
          errorCode == LocationHelper.ERROR_CODE_TIMEOUT;
        finish(callback, hasFix ? location : null);
      }
    );
    if (isPending) {
      locationHelper = helper;
    }
  }

  private void deny (Callback callback) {
    setGranted(false);
    finish(callback, null);
  }

  private void finish (Callback callback, @Nullable Location location) {
    isPending = false;
    callback.onResult(location != null ? toPayload(location) : unavailable());
  }

  /** Stops the pending request; its callback is not called anymore. */
  public void release () {
    isReleased = true;
    if (locationHelper != null) {
      locationHelper.cancel();
      locationHelper.destroy();
      locationHelper = null;
    }
  }

  // Payloads

  private static JSONObject unavailable () {
    JSONObject payload = new JSONObject();
    try {
      payload.put("available", false);
    } catch (JSONException e) {
      Log.e("Cannot build location payload", e);
    }
    return payload;
  }

  private static JSONObject toPayload (Location location) {
    JSONObject payload = new JSONObject();
    try {
      payload.put("available", true);
      payload.put("latitude", location.getLatitude());
      payload.put("longitude", location.getLongitude());
      payload.put("altitude", location.getAltitude());
      payload.put("course", finiteOrZero(location.getBearing()));
      payload.put("speed", finiteOrZero(location.getSpeed()));
      payload.put("horizontal_accuracy", location.hasAccuracy() ?
        (Object) finiteOrZero(location.getAccuracy()) : JSONObject.NULL);
      Object verticalAccuracy = JSONObject.NULL, courseAccuracy = JSONObject.NULL;
      Object speedAccuracy = JSONObject.NULL;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (location.hasVerticalAccuracy()) {
          verticalAccuracy = finiteOrZero(location.getVerticalAccuracyMeters());
        }
        if (location.hasBearingAccuracy()) {
          courseAccuracy = finiteOrZero(location.getBearingAccuracyDegrees());
        }
        if (location.hasSpeedAccuracy()) {
          speedAccuracy = finiteOrZero(location.getSpeedAccuracyMetersPerSecond());
        }
      }
      payload.put("vertical_accuracy", verticalAccuracy);
      payload.put("course_accuracy", courseAccuracy);
      payload.put("speed_accuracy", speedAccuracy);
    } catch (JSONException e) {
      Log.e("Cannot build location payload", e);
      return unavailable();
    }
    return payload;
  }

  /** JSONObject rejects NaN and infinities. */
  private static double finiteOrZero (double value) {
    return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
  }
}
