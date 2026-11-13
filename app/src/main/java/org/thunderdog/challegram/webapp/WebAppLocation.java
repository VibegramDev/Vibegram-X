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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;

import java.util.List;

import me.vkryl.leveldb.LevelDB;

/**
 * Location access of a mini app ({@code web_app_check_location}, {@code web_app_request_location}),
 * per (account user, bot). The bot's grant lives in the TGX pmc under
 * {@code webapp_location_<accountUserId>_<botId>_granted} / {@code _requested}. All methods are
 * called on the UI thread.
 */
public final class WebAppLocation {
  public interface Callback {
    void onResult (JSONObject payload);
  }

  // The official client waits for a fix without a limit; a mini app gets "unavailable" after this
  // time instead.
  private static final long LOCATION_TIMEOUT_MS = 30_000L;
  private static final String KEY_OS_PERMISSION_ASKED = "webapp_location_os_asked";

  private final String keyPrefix;

  public WebAppLocation (Tdlib tdlib, long botUserId) {
    this.keyPrefix = "webapp_location_" + tdlib.myUserId(true) + "_" + botUserId;
  }

  // State

  private static LevelDB pmc () {
    return org.thunderdog.challegram.unsorted.Settings.instance().pmc();
  }

  private static boolean hasGps () {
    PackageManager packageManager = AppContext.get().getPackageManager();
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
  }

  private static boolean hasPermission (String permission) {
    return ContextCompat.checkSelfPermission(AppContext.get(), permission) ==
      PackageManager.PERMISSION_GRANTED;
  }

  private static boolean hasOsPermission () {
    return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
      hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
  }

  public boolean isRequested () {
    return pmc().getBoolean(keyPrefix + "_requested", false);
  }

  /** False when the app has lost the OS permission, whatever the bot was granted before. */
  public boolean isGranted () {
    boolean granted = pmc().getBoolean(keyPrefix + "_granted", false);
    if (granted && !hasOsPermission()) {
      // As the official client: a grant without the OS permission is reset, so the bot asks again.
      pmc().remove(keyPrefix + "_granted");
      pmc().remove(keyPrefix + "_requested");
      return false;
    }
    return granted;
  }

  public void setGranted (boolean granted) {
    pmc().putBoolean(keyPrefix + "_requested", true);
    if (granted) {
      pmc().putBoolean(keyPrefix + "_granted", true);
    } else {
      pmc().remove(keyPrefix + "_granted");
    }
  }

  /** {@code location_checked} payload. */
  public JSONObject check () {
    JSONObject payload = new JSONObject();
    try {
      boolean available = hasGps();
      payload.put("available", available);
      if (available) {
        boolean granted = isGranted();
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
    if (!hasGps()) {
      callback.onResult(unavailable());
      return;
    }
    boolean granted = isGranted();
    if (hasOsPermission() && isRequested()) {
      if (granted) {
        requestLocation(context, callback);
      } else {
        callback.onResult(unavailable());
      }
      return;
    }
    if (context.isDestroyed()) {
      callback.onResult(unavailable());
      return;
    }
    BaseActivity activity = context.context();
    boolean isPermanentlyDenied = !hasOsPermission() &&
      pmc().getBoolean(KEY_OS_PERMISSION_ASKED, false) &&
      !activity.permissions().shouldShowAccessLocationRationale();
    int positiveText = isPermanentlyDenied ?
      R.string.vg_BotLocationPermissionSettings :
      R.string.vg_BotLocationPermissionAllow;
    AlertPopup popup = context.showAlert(new AlertPopup.Builder(activity)
      .setMessage(Lang.getStringBold(R.string.vg_BotLocationPermissionRequest, botName, botName))
      .setPositiveButton(Lang.getString(positiveText), (dialog, which) -> {
        if (isPermanentlyDenied) {
          setGranted(false);
          Intents.openPermissionSettings();
          callback.onResult(unavailable());
        } else {
          requestOsPermission(context, callback);
        }
      })
      .setNegativeButton(
        Lang.getString(R.string.vg_BotLocationPermissionDecline),
        (dialog, which) -> {
          setGranted(false);
          callback.onResult(unavailable());
        }
      )
      .setOnCancelListener(dialog -> {
        setGranted(false);
        callback.onResult(unavailable());
      })
    );
    if (popup == null) {
      callback.onResult(unavailable());
    }
  }

  private void requestOsPermission (ViewController<?> context, Callback callback) {
    if (hasOsPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      onOsPermissionResult(context, callback);
      return;
    }
    pmc().putBoolean(KEY_OS_PERMISSION_ASKED, true);
    context.context().requestLocationPermission(
      false,
      true,
      (code, permissions, grantResults, grantCount) -> onOsPermissionResult(context, callback)
    );
  }

  private void onOsPermissionResult (ViewController<?> context, Callback callback) {
    // Unlike the official client, a bot is not granted when the OS permission was denied.
    boolean granted = hasOsPermission();
    setGranted(granted);
    if (granted) {
      requestLocation(context, callback);
    } else {
      callback.onResult(unavailable());
    }
  }

  private void requestLocation (ViewController<?> context, Callback callback) {
    LocationManager manager =
      (LocationManager) AppContext.get().getSystemService(Context.LOCATION_SERVICE);
    if (manager == null || !isGranted()) {
      callback.onResult(unavailable());
      return;
    }
    try {
      List<String> providers = manager.getProviders(true);
      for (int i = providers.size() - 1; i >= 0; i--) {
        Location location = manager.getLastKnownLocation(providers.get(i));
        if (location != null) {
          callback.onResult(toPayload(location));
          return;
        }
      }
    } catch (SecurityException e) {
      Log.w("Cannot read last known location", e);
      callback.onResult(unavailable());
      return;
    }
    if (!isProviderEnabled(manager, LocationManager.GPS_PROVIDER)) {
      showGpsDisabledAlert(context, callback);
      return;
    }
    String provider = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ?
      LocationManager.GPS_PROVIDER :
      LocationManager.NETWORK_PROVIDER;
    if (!isProviderEnabled(manager, provider)) {
      callback.onResult(unavailable());
      return;
    }
    new SingleUpdate(manager, callback).start(provider);
  }

  private static boolean isProviderEnabled (LocationManager manager, String provider) {
    try {
      return manager.isProviderEnabled(provider);
    } catch (Throwable t) {
      return false;
    }
  }

  private static void showGpsDisabledAlert (ViewController<?> context, Callback callback) {
    if (context.isDestroyed()) {
      callback.onResult(unavailable());
      return;
    }
    AlertPopup popup = context.showAlert(new AlertPopup.Builder(context.context())
      .setMessage(Lang.getString(R.string.vg_GpsDisabledAlertText))
      .setPositiveButton(Lang.getString(R.string.vg_Enable), (dialog, which) ->
        Intents.openIntent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
      )
      .setNegativeButton(Lang.getString(R.string.Cancel), null)
      .setOnDismissListener(dialog -> callback.onResult(unavailable()))
    );
    if (popup == null) {
      callback.onResult(unavailable());
    }
  }

  /** One location fix from {@code requestLocationUpdates}, or "unavailable" after the timeout. */
  private static final class SingleUpdate implements LocationListener, Runnable {
    private final LocationManager manager;
    private final Callback callback;
    private boolean isFinished;

    SingleUpdate (LocationManager manager, Callback callback) {
      this.manager = manager;
      this.callback = callback;
    }

    void start (String provider) {
      try {
        manager.requestLocationUpdates(provider, 1L, 0f, this, Looper.getMainLooper());
      } catch (SecurityException | IllegalArgumentException e) {
        Log.w("Cannot request location updates", e);
        finish(null);
        return;
      }
      UI.post(this, LOCATION_TIMEOUT_MS);
    }

    private void finish (@Nullable Location location) {
      if (isFinished) {
        return;
      }
      isFinished = true;
      UI.cancel(this);
      try {
        manager.removeUpdates(this);
      } catch (SecurityException e) {
        Log.w("Cannot stop location updates", e);
      }
      callback.onResult(location != null ? toPayload(location) : unavailable());
    }

    @Override
    public void run () {
      finish(null);
    }

    @Override
    public void onLocationChanged (@NonNull Location location) {
      finish(location);
    }

    // Abstract before Android 11: without them older platforms throw AbstractMethodError.

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged (String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled (@NonNull String provider) { }

    @Override
    public void onProviderDisabled (@NonNull String provider) {
      finish(null);
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
