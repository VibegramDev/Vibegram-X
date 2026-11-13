/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BotWebViewContainer from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.stars.StarsPaymentSheet;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ShareController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;
import tgx.td.ChatId;
import tgx.td.Td;

/**
 * The {@code TelegramWebviewProxy} of a mini app: receives {@code web_app_*} events of the page and
 * sends events back with {@code window.Telegram.WebView.receiveEvent}. Event names and payloads:
 * core.telegram.org/api/web-events.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
final class WebAppBridge implements WebAppSensors.Listener {
  static final String INTERFACE_NAME = "TelegramWebviewProxy";
  private static final String LISTENER_NAME = "TelegramWebviewProxyChannel";
  /**
   * Defines {@code TelegramWebviewProxy.postEvent} on top of the message listener, so the page
   * sees the same API as with {@code addJavascriptInterface}.
   */
  private static final String PROXY_SCRIPT =
    "(function(){var c=window." + LISTENER_NAME + ";if(!c)return;" +
    "window." + INTERFACE_NAME + "={postEvent:function(t,d){" +
    "c.postMessage(JSON.stringify([String(t),d===undefined||d===null?null:String(d)]));}};})();";

  private static final int MAX_MESSAGE_LENGTH = 1024 * 1024, MAX_EVENT_NAME_LENGTH = 128;
  private static final long USER_GESTURE_TIMEOUT_MS = 10_000L;
  private static final long DIALOG_REOPEN_WINDOW_MS = 150L, DIALOG_COOLDOWN_MS = 3_000L;
  private static final int DIALOG_MAX_RAPID_OPENS = 3;
  private static final long HAPTIC_MIN_INTERVAL_MS = 50L;
  private static final int MAX_POPUP_MESSAGE_LENGTH = 256, MAX_POPUP_TEXT_LENGTH = 64;
  private static final long MAX_EMOJI_STATUS_DURATION = 366L * 24 * 60 * 60;

  private final WebAppController controller;
  private final Tdlib tdlib;
  private final long botUserId;

  private @Nullable String trustedOrigin;
  private boolean isAttached, usesMessageListener;

  /**
   * Incremented on each navigation of the page. Events are stamped with it when they arrive, so
   * events and replies of a previous page are dropped.
   */
  private volatile int documentGeneration;
  private long lastUserGestureMs, lastHapticMs;

  private boolean isDialogShown, isQrScannerShown, isInvoiceOpen, isDataSent,
    isBiometryRequestPending, isLocationRequestPending;
  private long lastDialogClosedMs, dialogCooldownUntilMs;
  private int rapidDialogCount;

  private @Nullable WebAppStorage deviceStorage, secureStorage;
  private @Nullable WebAppBiometry biometry;
  private @Nullable WebAppLocation location;
  private @Nullable WebAppSensors sensors;

  WebAppBridge (WebAppController controller) {
    this.controller = controller;
    this.tdlib = controller.tdlib();
    this.botUserId = controller.getBotUserId();
  }

  // Lifecycle

  /**
   * Installs the bridge before the launch URL is loaded. With the message listener, events of
   * other origins (when {@code trustedOrigin} is set) and of child frames never reach the app.
   */
  // addDocumentStartJavaScript is @RestrictTo only in androidx.webkit 1.4.0 of the legacy flavor,
  // the newer androidx.webkit of the other flavors has it as a public API.
  @SuppressLint("RestrictedApi")
  void attachTo (WebView webView, @Nullable String trustedOrigin) {
    if (isAttached) {
      return;
    }
    isAttached = true;
    this.trustedOrigin = trustedOrigin;
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
      WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      Set<String> rules = Collections.singleton(trustedOrigin != null ? trustedOrigin : "*");
      try {
        WebViewCompat.addWebMessageListener(webView, LISTENER_NAME, rules, this::onWebMessage);
        WebViewCompat.addDocumentStartJavaScript(webView, PROXY_SCRIPT, rules);
        usesMessageListener = true;
        return;
      } catch (IllegalArgumentException e) {
        Log.e("Mini app origin %s is not a valid origin rule", e, trustedOrigin);
      }
    }
    webView.addJavascriptInterface(this, INTERFACE_NAME);
  }

  // The document start script stays: its reference type differs between androidx.webkit versions
  // of the flavors, and without the listener it defines nothing.
  void detachFrom (WebView webView) {
    if (!isAttached) {
      return;
    }
    isAttached = false;
    if (usesMessageListener) {
      WebViewCompat.removeWebMessageListener(webView, LISTENER_NAME);
      usesMessageListener = false;
    } else {
      webView.removeJavascriptInterface(INTERFACE_NAME);
    }
  }

  void onPageStarted () {
    documentGeneration++;
    lastUserGestureMs = 0;
    if (sensors != null) {
      sensors.release();
      sensors = null;
    }
    if (location != null) {
      location.release();
      location = null;
    }
  }

  void onUserInteraction () {
    lastUserGestureMs = SystemClock.uptimeMillis();
  }

  void onVisibilityChanged (boolean isVisible) {
    if (sensors != null) {
      if (isVisible) {
        sensors.resume();
      } else {
        sensors.pause();
      }
    }
    JSONObject payload = new JSONObject();
    put(payload, "is_visible", isVisible);
    send("visibility_changed", payload);
  }

  void release () {
    documentGeneration++;
    if (sensors != null) {
      sensors.release();
      sensors = null;
    }
  }

  boolean consumeUserGesture () {
    if (SystemClock.uptimeMillis() - lastUserGestureMs > USER_GESTURE_TIMEOUT_MS) {
      return false;
    }
    lastUserGestureMs = 0;
    return true;
  }

  // Outgoing events

  @UiThread
  void send (String eventName, @Nullable Object payload) {
    if (controller.isDestroyed()) {
      return;
    }
    StringBuilder b = new StringBuilder(eventName.length() + 64);
    b.append("window.Telegram.WebView.receiveEvent('").append(eventName).append("', ");
    b.append(payload != null ? payload.toString() : "null").append(");");
    controller.evaluateJs(b.toString());
  }

  /**
   * Sends a reply to a request made by the page of the given generation, unless the page has
   * changed since.
   */
  @UiThread
  private void reply (int generation, String eventName, @Nullable Object payload) {
    if (generation == documentGeneration) {
      send(eventName, payload);
    }
  }

  @Override
  public void onSensorChanged (int type, String eventName, String json) {
    send(eventName, json);
  }

  // Incoming events

  // Without the message listener only the page calls it, so R8 would remove it without @Keep.
  @Keep
  @JavascriptInterface
  public void postEvent (final String eventType, final String eventData) {
    onEvent(documentGeneration, eventType, eventData);
  }

  @UiThread
  private void onWebMessage (
    @NonNull WebView view,
    @NonNull WebMessageCompat message,
    @NonNull Uri sourceOrigin,
    boolean isMainFrame,
    @NonNull JavaScriptReplyProxy replyProxy
  ) {
    if (!isMainFrame || (trustedOrigin != null && !trustedOrigin.equals(sourceOrigin.toString()))) {
      return;
    }
    final int generation = documentGeneration;
    String data = message.getData();
    if (data == null) {
      return;
    }
    try {
      JSONArray event = new JSONArray(data);
      onEvent(generation, event.getString(0), event.isNull(1) ? null : event.getString(1));
    } catch (JSONException e) {
      Log.w("Invalid mini app message", e);
    }
  }

  private void onEvent (int generation, String eventType, @Nullable String eventData) {
    if (StringUtils.isEmpty(eventType) || eventType.length() > MAX_EVENT_NAME_LENGTH ||
      (eventData != null && eventData.length() > MAX_MESSAGE_LENGTH)) {
      return;
    }
    UI.post(() -> {
      if (controller.isDestroyed() || generation != documentGeneration ||
        !controller.isTrustedDocument()) {
        return;
      }
      if (showsUi(eventType) && !isInForeground()) {
        rejectEvent(generation, eventType, eventData);
        return;
      }
      try {
        handleEvent(generation, eventType, eventData);
      } catch (JSONException | IllegalArgumentException e) {
        Log.w("Invalid mini app event %s", e, eventType);
        rejectEvent(generation, eventType, eventData);
      }
    });
  }

  private boolean isInForeground () {
    return !controller.isMinimized() &&
      controller.context().getActivityState() == UI.State.RESUMED;
  }

  /** Events that show something over the app, open screens or vibrate. */
  private static boolean showsUi (String eventType) {
    switch (eventType) {
      case "web_app_open_link":
      case "web_app_open_tg_link":
      case "web_app_open_invoice":
      case "web_app_open_popup":
      case "web_app_open_scan_qr_popup":
      case "web_app_read_text_from_clipboard":
      case "web_app_trigger_haptic_feedback":
      case "web_app_switch_inline_query":
      case "web_app_request_write_access":
      case "web_app_request_phone":
      case "web_app_biometry_request_access":
      case "web_app_biometry_request_auth":
      case "web_app_biometry_update_token":
      case "web_app_biometry_open_settings":
      case "web_app_share_to_story":
      case "web_app_add_to_home_screen":
      case "web_app_set_emoji_status":
      case "web_app_request_emoji_status_access":
      case "web_app_request_location":
      case "web_app_open_location_settings":
      case "web_app_request_file_download":
      case "web_app_send_prepared_message":
      case "web_app_request_chat":
      case "web_app_secure_storage_restore_key":
        return true;
      default:
        return false;
    }
  }

  /**
   * Answers a request that won't be handled, so the page doesn't wait forever: the SDK keeps
   * popups, invoices and the QR scanner marked as open until their closing event.
   */
  private void rejectEvent (int generation, String eventType, @Nullable String eventData) {
    JSONObject data;
    try {
      data = parse(eventData);
    } catch (JSONException e) {
      data = new JSONObject();
    }
    final String requestId = data.optString("req_id", "");
    switch (eventType) {
      case "web_app_open_popup":
        reply(generation, "popup_closed", new JSONObject());
        break;
      case "web_app_open_scan_qr_popup":
        reply(generation, "scan_qr_popup_closed", null);
        break;
      case "web_app_open_invoice":
        replyInvoice(generation, data.optString("slug", ""), "cancelled");
        break;
      case "web_app_read_text_from_clipboard":
        replyRequestId(generation, "clipboard_text_received", requestId);
        break;
      case "web_app_invoke_custom_method": {
        JSONObject payload = new JSONObject();
        put(payload, "req_id", requestId);
        put(payload, "error", "UNKNOWN_ERROR");
        reply(generation, "custom_method_invoked", payload);
        break;
      }
      case "web_app_request_write_access":
        replyStatus(generation, "write_access_requested", "cancelled");
        break;
      case "web_app_request_phone":
        replyStatus(generation, "phone_requested", "cancelled");
        break;
      case "web_app_biometry_request_access":
        reply(generation, "biometry_info_received", biometry().getInfo());
        break;
      case "web_app_biometry_request_auth":
        replyStatus(generation, "biometry_auth_requested", "failed");
        break;
      case "web_app_biometry_update_token":
        replyStatus(generation, "biometry_token_updated", "failed");
        break;
      case "web_app_add_to_home_screen":
        replyError(generation, "home_screen_failed", "UNSUPPORTED");
        break;
      case "web_app_request_fullscreen":
        replyError(generation, "fullscreen_failed", "UNSUPPORTED");
        break;
      case "web_app_set_emoji_status":
        replyError(generation, "emoji_status_failed", "USER_DECLINED");
        break;
      case "web_app_request_emoji_status_access":
        replyStatus(generation, "emoji_status_access_requested", "cancelled");
        break;
      case "web_app_request_location":
        replyLocationUnavailable(generation);
        break;
      case "web_app_request_file_download":
        replyStatus(generation, "file_download_requested", "cancelled");
        break;
      case "web_app_send_prepared_message":
        replyError(generation, "prepared_message_failed", "USER_DECLINED");
        break;
      case "web_app_request_chat":
        replyRequestId(generation, "requested_chat_failed", requestId);
        break;
      case "web_app_start_accelerometer":
        replyError(generation, "accelerometer_failed", "UNSUPPORTED");
        break;
      case "web_app_start_gyroscope":
        replyError(generation, "gyroscope_failed", "UNSUPPORTED");
        break;
      case "web_app_start_device_orientation":
        replyError(generation, "device_orientation_failed", "UNSUPPORTED");
        break;
      case "web_app_device_storage_save_key":
      case "web_app_device_storage_get_key":
      case "web_app_device_storage_clear":
      case "web_app_secure_storage_save_key":
      case "web_app_secure_storage_get_key":
      case "web_app_secure_storage_clear":
      case "web_app_secure_storage_restore_key": {
        JSONObject payload = new JSONObject();
        put(payload, "req_id", requestId);
        put(payload, "error", WebAppStorage.ERROR_UNKNOWN);
        reply(generation, eventType.startsWith("web_app_secure_storage_") ?
          "secure_storage_failed" : "device_storage_failed", payload);
        break;
      }
    }
  }

  private static JSONObject parse (@Nullable String data) throws JSONException {
    return StringUtils.isEmpty(data) ? new JSONObject() : new JSONObject(data);
  }

  @UiThread
  private void handleEvent (int generation, String eventType, @Nullable String eventData)
    throws JSONException {
    switch (eventType) {
      case "web_app_ready":
        controller.onPageReady();
        break;
      case "web_app_expand":
        controller.expand();
        break;
      case "web_app_close":
        controller.closeNow();
        break;
      case "web_app_setup_main_button":
      case "web_app_setup_secondary_button":
        setupButton(eventType.equals("web_app_setup_main_button"), parse(eventData));
        break;
      case "web_app_setup_back_button":
        controller.setBackButtonVisible(parse(eventData).optBoolean("is_visible", false));
        break;
      case "web_app_setup_settings_button":
        controller.setSettingsButtonVisible(parse(eventData).optBoolean("is_visible", false));
        break;
      case "web_app_set_header_color": {
        JSONObject data = parse(eventData);
        String color = data.optString("color", null);
        if (!StringUtils.isEmpty(color)) {
          int value = parseColor(color);
          if (value != 0) {
            controller.setHeaderColor(value, null);
          }
        } else {
          String key = data.optString("color_key", null);
          if ("bg_color".equals(key) || "secondary_bg_color".equals(key)) {
            controller.setHeaderColor(0, key);
          }
        }
        break;
      }
      case "web_app_set_background_color": {
        int value = parseColor(parse(eventData).optString("color", "#ffffff"));
        if (value != 0) {
          controller.setAppBackgroundColor(value | 0xff000000);
        }
        break;
      }
      case "web_app_set_bottom_bar_color": {
        String color = parse(eventData).optString("color", null);
        if (StringUtils.isEmpty(color)) {
          controller.setBottomBarColor(0);
        } else {
          int value = parseColor(color);
          if (value != 0) {
            controller.setBottomBarColor(value | 0xff000000);
          }
        }
        break;
      }
      case "web_app_request_theme":
        sendTheme();
        break;
      case "web_app_request_viewport":
        controller.sendViewport(true);
        break;
      case "web_app_request_safe_area":
        controller.sendSafeArea();
        break;
      case "web_app_request_content_safe_area":
        controller.sendContentSafeArea();
        break;
      case "web_app_setup_closing_behavior":
        controller.setNeedClosingConfirmation(
          parse(eventData).optBoolean("need_confirmation", false)
        );
        break;
      case "web_app_setup_swipe_behavior": {
        JSONObject data = parse(eventData);
        if (data.has("allow_vertical_swipe")) {
          controller.setAllowSwipes(data.optBoolean("allow_vertical_swipe", true));
        }
        break;
      }
      case "web_app_open_link":
        openLink(parse(eventData));
        break;
      case "web_app_open_tg_link":
        openTelegramLink(parse(eventData));
        break;
      case "web_app_open_invoice":
        openInvoice(generation, parse(eventData).optString("slug", ""));
        break;
      case "web_app_open_popup":
        openPopup(generation, parse(eventData));
        break;
      case "web_app_open_scan_qr_popup":
        openQrScanner(generation, parse(eventData).optString("text", null));
        break;
      case "web_app_close_scan_qr_popup":
        controller.closeQrScanner();
        break;
      case "web_app_read_text_from_clipboard":
        readClipboard(generation, parse(eventData).getString("req_id"));
        break;
      case "web_app_trigger_haptic_feedback": {
        JSONObject data = parse(eventData);
        long now = SystemClock.uptimeMillis();
        if (now - lastHapticMs >= HAPTIC_MIN_INTERVAL_MS) {
          lastHapticMs = now;
          WebAppHaptics.perform(controller.getValue(), data.optString("type", ""),
            data.optString("impact_style", null), data.optString("notification_type", null));
        }
        break;
      }
      case "web_app_data_send":
        sendData(parse(eventData).optString("data", ""));
        break;
      case "web_app_switch_inline_query":
        switchInlineQuery(parse(eventData));
        break;
      case "web_app_request_write_access":
        requestWriteAccess(generation);
        break;
      case "web_app_request_phone":
        requestPhone(generation);
        break;
      case "web_app_invoke_custom_method":
        invokeCustomMethod(generation, parse(eventData));
        break;
      case "web_app_biometry_get_info":
        send("biometry_info_received", biometry().getInfo());
        break;
      case "web_app_biometry_request_access": {
        String reason = optReason(eventData);
        if (isBiometryRequestPending) {
          rejectEvent(generation, eventType, eventData);
          break;
        }
        isBiometryRequestPending = true;
        biometry().requestAccess(controller, controller.getBotName(), reason, () -> {
          isBiometryRequestPending = false;
          reply(generation, "biometry_info_received", biometry().getInfo());
        });
        break;
      }
      case "web_app_biometry_request_auth": {
        String reason = optReason(eventData);
        if (isBiometryRequestPending) {
          rejectEvent(generation, eventType, eventData);
          break;
        }
        isBiometryRequestPending = true;
        biometry().requestAuth(controller, controller.getBotName(), reason, (status, token) -> {
          isBiometryRequestPending = false;
          JSONObject payload = new JSONObject();
          put(payload, "status", status);
          if (token != null) {
            put(payload, "token", token);
          }
          reply(generation, "biometry_auth_requested", payload);
        });
        break;
      }
      case "web_app_biometry_update_token": {
        JSONObject data = parse(eventData);
        String token = data.getString("token");
        if (isBiometryRequestPending) {
          rejectEvent(generation, eventType, eventData);
          break;
        }
        isBiometryRequestPending = true;
        biometry().updateToken(controller, controller.getBotName(), token,
          data.optString("reason", null),
          (status, ignored) -> {
            isBiometryRequestPending = false;
            JSONObject payload = new JSONObject();
            put(payload, "status", status);
            reply(generation, "biometry_token_updated", payload);
          });
        break;
      }
      case "web_app_biometry_open_settings":
        if (consumeUserGesture()) {
          controller.openBotProfile();
        }
        break;
      case "web_app_share_to_story":
        if (consumeUserGesture()) {
          UI.showToast(R.string.vg_WebAppNotSupportedStory, android.widget.Toast.LENGTH_SHORT);
        }
        break;
      case "web_app_request_fullscreen": {
        boolean blur = parse(eventData).optBoolean("blur", true);
        String error = controller.requestFullscreen();
        if (error != null) {
          JSONObject payload = new JSONObject();
          put(payload, "error", error);
          send("fullscreen_failed", payload);
        } else {
          JSONObject payload = new JSONObject();
          put(payload, "is_fullscreen", true);
          put(payload, "blur_enabled", blur);
          send("fullscreen_changed", payload);
        }
        break;
      }
      case "web_app_exit_fullscreen": {
        controller.exitFullscreen();
        JSONObject payload = new JSONObject();
        put(payload, "is_fullscreen", false);
        send("fullscreen_changed", payload);
        break;
      }
      case "web_app_toggle_orientation_lock":
        controller.setOrientationLocked(parse(eventData).optBoolean("locked", false));
        break;
      case "web_app_start_accelerometer":
        startSensor(WebAppSensors.TYPE_ACCELEROMETER, "accelerometer", parse(eventData));
        break;
      case "web_app_stop_accelerometer":
        stopSensor(WebAppSensors.TYPE_ACCELEROMETER, "accelerometer");
        break;
      case "web_app_start_gyroscope":
        startSensor(WebAppSensors.TYPE_GYROSCOPE, "gyroscope", parse(eventData));
        break;
      case "web_app_stop_gyroscope":
        stopSensor(WebAppSensors.TYPE_GYROSCOPE, "gyroscope");
        break;
      case "web_app_start_device_orientation":
        startSensor(WebAppSensors.TYPE_DEVICE_ORIENTATION, "device_orientation", parse(eventData));
        break;
      case "web_app_stop_device_orientation":
        stopSensor(WebAppSensors.TYPE_DEVICE_ORIENTATION, "device_orientation");
        break;
      case "web_app_add_to_home_screen":
        if (consumeUserGesture()) {
          controller.addToHomeScreen(added -> {
            if (added) {
              reply(generation, "home_screen_added", null);
            } else {
              replyError(generation, "home_screen_failed", "UNSUPPORTED");
            }
          });
        } else {
          rejectEvent(generation, eventType, eventData);
        }
        break;
      case "web_app_check_home_screen": {
        JSONObject payload = new JSONObject();
        put(payload, "status", controller.getHomeScreenStatus());
        send("home_screen_checked", payload);
        break;
      }
      case "web_app_set_emoji_status":
        if (consumeUserGesture()) {
          setEmojiStatus(generation, parse(eventData));
        } else {
          rejectEvent(generation, eventType, eventData);
        }
        break;
      case "web_app_request_emoji_status_access":
        if (consumeUserGesture()) {
          requestEmojiStatusAccess(generation);
        } else {
          rejectEvent(generation, eventType, eventData);
        }
        break;
      case "web_app_check_location":
        send("location_checked", location().check());
        break;
      case "web_app_request_location":
        if (isLocationRequestPending) {
          replyLocationUnavailable(generation);
          break;
        }
        isLocationRequestPending = true;
        location().request(controller, controller.getBotName(), payload -> {
          isLocationRequestPending = false;
          reply(generation, "location_requested", payload);
          reply(generation, "location_checked", location().check());
        });
        break;
      case "web_app_open_location_settings":
        if (consumeUserGesture()) {
          controller.openBotProfile();
        }
        break;
      case "web_app_request_file_download":
        requestFileDownload(generation, eventData);
        break;
      case "web_app_send_prepared_message":
        if (consumeUserGesture()) {
          sendPreparedMessage(generation, parse(eventData).optString("id", ""));
        } else {
          rejectEvent(generation, eventType, eventData);
        }
        break;
      case "web_app_request_chat":
        requestChat(generation, parse(eventData).optString("req_id", ""));
        break;
      case "web_app_device_storage_save_key":
      case "web_app_device_storage_get_key":
      case "web_app_device_storage_clear":
        handleStorage(generation, false, eventType, parse(eventData));
        break;
      case "web_app_secure_storage_save_key":
      case "web_app_secure_storage_get_key":
      case "web_app_secure_storage_clear":
      case "web_app_secure_storage_restore_key":
        handleStorage(generation, true, eventType, parse(eventData));
        break;
      case "web_app_hide_keyboard":
        controller.hideSoftwareKeyboard();
        break;
      default:
        Log.v("Unknown mini app event: %s", eventType);
        break;
    }
  }

  private static @Nullable String optReason (@Nullable String eventData) throws JSONException {
    return parse(eventData).optString("reason", null);
  }

  private static int parseColor (@Nullable String color) {
    if (StringUtils.isEmpty(color)) {
      return 0;
    }
    try {
      return Color.parseColor(color);
    } catch (IllegalArgumentException ignored) {
      return 0;
    }
  }

  private static void put (JSONObject object, String key, @Nullable Object value) {
    try {
      object.put(key, value != null ? value : JSONObject.NULL);
    } catch (JSONException ignored) {
      // Keys are constants and values are primitives or strings, so put never throws here.
    }
  }

  // Theme

  void sendTheme () {
    TdApi.ThemeParameters theme = WebApp.buildThemeParameters();
    JSONObject params = new JSONObject();
    put(params, "bg_color", hex(theme.backgroundColor));
    put(params, "secondary_bg_color", hex(theme.secondaryBackgroundColor));
    put(params, "header_bg_color", hex(theme.headerBackgroundColor));
    put(params, "bottom_bar_bg_color", hex(theme.bottomBarBackgroundColor));
    put(params, "section_bg_color", hex(theme.sectionBackgroundColor));
    put(params, "section_separator_color", hex(theme.sectionSeparatorColor));
    put(params, "text_color", hex(theme.textColor));
    put(params, "accent_text_color", hex(theme.accentTextColor));
    put(params, "section_header_text_color", hex(theme.sectionHeaderTextColor));
    put(params, "subtitle_text_color", hex(theme.subtitleTextColor));
    put(params, "destructive_text_color", hex(theme.destructiveTextColor));
    put(params, "hint_color", hex(theme.hintColor));
    put(params, "link_color", hex(theme.linkColor));
    put(params, "button_color", hex(theme.buttonColor));
    put(params, "button_text_color", hex(theme.buttonTextColor));
    JSONObject payload = new JSONObject();
    put(payload, "theme_params", params);
    send("theme_changed", payload);
  }

  private static String hex (int color) {
    return String.format(Locale.US, "#%06X", color & 0xffffff);
  }

  // Buttons

  private void setupButton (boolean isMain, JSONObject data) {
    long emojiId = 0;
    String emojiIdString = data.optString("icon_custom_emoji_id", null);
    if (!StringUtils.isEmpty(emojiIdString)) {
      emojiId = StringUtils.parseLong(emojiIdString);
    }
    controller.setupButton(
      isMain,
      data.optBoolean("is_visible", false),
      data.optBoolean("is_active", true),
      data.has("text") ? data.optString("text", "") : null,
      parseColor(data.optString("color", null)),
      parseColor(data.optString("text_color", null)),
      data.optBoolean("is_progress_visible", false),
      data.optBoolean("has_shine_effect", false),
      emojiId,
      isMain ? null : data.optString("position", null)
    );
  }

  void onButtonPressed (boolean isMain) {
    onUserInteraction();
    send(isMain ? "main_button_pressed" : "secondary_button_pressed", null);
  }

  void onBackButtonPressed () {
    send("back_button_pressed", null);
  }

  void onSettingsButtonPressed () {
    onUserInteraction();
    send("settings_button_pressed", null);
  }

  // Links

  private void openLink (JSONObject data) {
    String url = data.optString("url", "");
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
    if (!(scheme.equals("http") || scheme.equals("https")) || !consumeUserGesture()) {
      return;
    }
    controller.openExternalLink(url, data.optBoolean("try_instant_view", false));
  }

  private void openTelegramLink (JSONObject data) {
    String path = data.optString("path_full", "");
    if (StringUtils.isEmpty(path) || !consumeUserGesture()) {
      return;
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    controller.openTelegramLink(tdlib.tMeUrl() + path);
  }

  // Payments

  private void openInvoice (int generation, String slug) {
    if (StringUtils.isEmpty(slug)) {
      return;
    }
    if (isInvoiceOpen) {
      replyInvoice(generation, slug, "cancelled");
      return;
    }
    isInvoiceOpen = true;
    StarsPaymentSheet.open(controller, tdlib, new TdApi.InputInvoiceName(slug), null, null,
      result -> {
        isInvoiceOpen = false;
        replyInvoice(generation, slug, invoiceStatus(result));
      }
    );
  }

  private static String invoiceStatus (int result) {
    switch (result) {
      case StarsPaymentSheet.RESULT_PAID:
        return "paid";
      case StarsPaymentSheet.RESULT_PENDING:
        return "pending";
      case StarsPaymentSheet.RESULT_FAILED:
        return "failed";
      default:
        return "cancelled";
    }
  }

  private void replyInvoice (int generation, String slug, String status) {
    JSONObject payload = new JSONObject();
    put(payload, "slug", slug);
    put(payload, "status", status);
    reply(generation, "invoice_closed", payload);
  }

  // Dialogs

  /**
   * Whether the page may show one more dialog now. A page reopening dialogs as soon as they close
   * gets a cooldown, so it can't lock the user in a loop of dialogs.
   */
  private boolean canShowDialog () {
    final long now = SystemClock.uptimeMillis();
    if (isDialogShown || now < dialogCooldownUntilMs || !isInForeground()) {
      return false;
    }
    if (now - lastDialogClosedMs < DIALOG_REOPEN_WINDOW_MS) {
      if (++rapidDialogCount >= DIALOG_MAX_RAPID_OPENS) {
        rapidDialogCount = 0;
        dialogCooldownUntilMs = now + DIALOG_COOLDOWN_MS;
        return false;
      }
    } else {
      rapidDialogCount = 0;
    }
    return true;
  }

  /** Shows a dialog of the page, {@code onClosed} runs when it's closed. */
  private boolean showDialog (AlertPopup.Builder b, Runnable onClosed) {
    if (!canShowDialog()) {
      return false;
    }
    b.setOnDismissListener(dialog -> {
      isDialogShown = false;
      lastDialogClosedMs = SystemClock.uptimeMillis();
      onClosed.run();
    });
    isDialogShown = controller.showAlert(b) != null;
    return isDialogShown;
  }

  private void confirm (
    CharSequence title,
    CharSequence message,
    String okText,
    String cancelText,
    RunnableBool onResult
  ) {
    final boolean[] confirmed = new boolean[1];
    AlertPopup.Builder b = new AlertPopup.Builder(controller.context())
      .setTitle(title)
      .setMessage(message)
      .setPositiveButton(okText, (dialog, which) -> confirmed[0] = true)
      .setNegativeButton(cancelText, null);
    if (!showDialog(b, () -> onResult.runWithBool(confirmed[0]))) {
      onResult.runWithBool(false);
    }
  }

  private void openPopup (int generation, JSONObject data) throws JSONException {
    String title = data.optString("title", "");
    String message = data.getString("message");
    JSONArray buttons = data.getJSONArray("buttons");
    if (title.length() > MAX_POPUP_TEXT_LENGTH || message.isEmpty() ||
      message.length() > MAX_POPUP_MESSAGE_LENGTH || buttons.length() == 0 ||
      buttons.length() > 3) {
      throw new IllegalArgumentException("Invalid popup");
    }
    String[] ids = new String[buttons.length()];
    String[] texts = new String[buttons.length()];
    int destructiveIndex = -1;
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.getJSONObject(i);
      ids[i] = button.optString("id", "");
      String type = button.optString("type", "default");
      switch (type) {
        case "ok":
          texts[i] = Lang.getString(R.string.OK);
          break;
        case "close":
          texts[i] = Lang.getString(R.string.vg_Close);
          break;
        case "cancel":
          texts[i] = Lang.getString(R.string.Cancel);
          break;
        case "destructive":
        case "default":
          texts[i] = button.getString("text");
          if (texts[i].isEmpty()) {
            throw new IllegalArgumentException("Empty popup button");
          }
          if (type.equals("destructive") && destructiveIndex == -1) {
            destructiveIndex = i;
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown popup button type");
      }
      if (ids[i].length() > MAX_POPUP_TEXT_LENGTH || texts[i].length() > MAX_POPUP_TEXT_LENGTH) {
        throw new IllegalArgumentException("Popup button is too long");
      }
    }
    // Only the positive button of AlertPopup can be destructive.
    if (destructiveIndex > 0) {
      String id = ids[0], text = texts[0];
      ids[0] = ids[destructiveIndex];
      texts[0] = texts[destructiveIndex];
      ids[destructiveIndex] = id;
      texts[destructiveIndex] = text;
    }
    final String[] chosenId = new String[1];
    AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
    b.setTitle(StringUtils.isEmpty(title) ? controller.getBotName() : title);
    b.setMessage(message);
    b.setPositiveButton(texts[0], (dialog, which) -> chosenId[0] = ids[0]);
    if (destructiveIndex != -1) {
      b.setPositiveButtonDestructive();
    }
    if (ids.length > 1) {
      b.setNegativeButton(texts[1], (dialog, which) -> chosenId[0] = ids[1]);
    }
    if (ids.length > 2) {
      b.setNeutralButton(texts[2], (dialog, which) -> chosenId[0] = ids[2]);
    }
    boolean isShown = showDialog(b, () -> {
      JSONObject payload = new JSONObject();
      if (chosenId[0] != null) {
        onUserInteraction();
        put(payload, "button_id", chosenId[0]);
      }
      reply(generation, "popup_closed", payload);
    });
    if (!isShown) {
      reply(generation, "popup_closed", new JSONObject());
    }
  }

  // QR codes

  private void openQrScanner (int generation, @Nullable String text) {
    if (isQrScannerShown) {
      return;
    }
    isQrScannerShown = controller.openQrScanner(text, qrCode -> {
      onUserInteraction();
      JSONObject payload = new JSONObject();
      put(payload, "data", qrCode);
      reply(generation, "qr_text_received", payload);
    }, byApp -> {
      isQrScannerShown = false;
      if (!byApp) {
        reply(generation, "scan_qr_popup_closed", null);
      }
    });
  }

  // Clipboard

  private void readClipboard (int generation, String requestId) {
    JSONObject payload = new JSONObject();
    put(payload, "req_id", requestId);
    if (consumeUserGesture() && controller.isClipboardAllowed()) {
      CharSequence text = U.getPasteText(controller.context());
      put(payload, "data", text != null ? text.toString() : "");
    }
    reply(generation, "clipboard_text_received", payload);
  }

  // Data, inline queries

  private void sendData (String data) {
    if (isDataSent || controller.getLaunchType() != WebAppController.TYPE_KEYBOARD_BUTTON) {
      return;
    }
    isDataSent = true;
    tdlib.send(new TdApi.SendWebAppData(botUserId, controller.getButtonText(), data),
      (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
    controller.closeNow();
  }

  private void switchInlineQuery (JSONObject data) throws JSONException {
    String query = data.getString("query");
    JSONArray chatTypes = data.optJSONArray("chat_types");
    controller.switchInlineQuery(query, chatTypes != null ? parseChatTypes(chatTypes) : null);
  }

  /** {@code null} when the query is for the current chat. */
  private static @Nullable TdApi.TargetChatTypes parseChatTypes (JSONArray chatTypes) {
    TdApi.TargetChatTypes types = new TdApi.TargetChatTypes();
    for (int i = 0; i < chatTypes.length(); i++) {
      switch (chatTypes.optString(i, "")) {
        case "users":
          types.allowUserChats = true;
          break;
        case "bots":
          types.allowBotChats = true;
          break;
        case "groups":
          types.allowGroupChats = true;
          break;
        case "channels":
          types.allowChannelChats = true;
          break;
      }
    }
    boolean isAny = types.allowUserChats || types.allowBotChats || types.allowGroupChats ||
      types.allowChannelChats;
    return isAny ? types : null;
  }

  // Permissions

  private void requestWriteAccess (int generation) {
    tdlib.send(new TdApi.CanBotSendMessages(botUserId), (ok, error) -> UI.post(() -> {
      if (error == null) {
        replyStatus(generation, "write_access_requested", "allowed");
        return;
      }
      confirm(
        Lang.getString(R.string.vg_BotWebViewRequestWriteTitle),
        Lang.getString(R.string.vg_BotWebViewRequestWriteMessage),
        Lang.getString(R.string.vg_BotWebViewRequestAllow),
        Lang.getString(R.string.vg_BotWebViewRequestDontAllow),
        allowed -> {
          if (!allowed) {
            replyStatus(generation, "write_access_requested", "cancelled");
            return;
          }
          tdlib.send(new TdApi.AllowBotToSendMessages(botUserId),
            (result, allowError) -> UI.post(() -> {
            if (allowError != null) {
              UI.showError(allowError);
            }
            replyStatus(generation, "write_access_requested",
              allowError == null ? "allowed" : "cancelled");
          }));
        }
      );
    }));
  }

  private void requestPhone (int generation) {
    TdApi.User me = tdlib.myUser();
    if (me == null) {
      replyStatus(generation, "phone_requested", "cancelled");
      return;
    }
    final long chatId = ChatId.fromUserId(botUserId);
    final boolean isBlocked = tdlib.chatFullyBlocked(chatId);
    CharSequence message = Lang.getStringBold(R.string.vg_AreYouSureShareMyContactInfoWebapp,
      controller.getBotName());
    if (isBlocked) {
      message = TextUtils.concat(message, "\n\n",
        Lang.getString(R.string.vg_AreYouSureShareMyContactInfoBotUnblock));
    }
    confirm(Lang.getString(R.string.vg_ShareYouPhoneNumberTitle), message,
      Lang.getString(R.string.ShareContact), Lang.getString(R.string.Cancel), share -> {
      if (!share) {
        replyStatus(generation, "phone_requested", "cancelled");
        return;
      }
      if (isBlocked) {
        tdlib.send(
          new TdApi.SetMessageSenderBlockList(new TdApi.MessageSenderUser(botUserId), null),
          tdlib.typedOkHandler());
      }
      TdApi.Contact contact = new TdApi.Contact(me.phoneNumber, me.firstName, me.lastName, null,
        me.id);
      tdlib.sendMessage(chatId, null, null, Td.newSendOptions(),
        new TdApi.InputMessageContact(contact), sent -> UI.post(() ->
          replyStatus(generation, "phone_requested", sent != null ? "sent" : "cancelled")
        ));
    });
  }

  private void replyStatus (int generation, String eventName, String status) {
    JSONObject payload = new JSONObject();
    put(payload, "status", status);
    reply(generation, eventName, payload);
  }

  // Custom methods (cloud storage)

  private void invokeCustomMethod (int generation, JSONObject data) throws JSONException {
    final String requestId = data.getString("req_id");
    final String method = data.getString("method");
    final Object params = data.opt("params");
    final String paramsJson = params == null || params == JSONObject.NULL ? "{}" : params.toString();
    tdlib.send(new TdApi.SendWebAppCustomRequest(botUserId, method, paramsJson),
      (result, error) ->
      UI.post(() -> {
        JSONObject payload = new JSONObject();
        put(payload, "req_id", requestId);
        if (error != null) {
          put(payload, "error", error.message);
        } else {
          try {
            put(payload, "result", new JSONTokener(result.result).nextValue());
          } catch (JSONException e) {
            put(payload, "error", "UNKNOWN_ERROR");
          }
        }
        reply(generation, "custom_method_invoked", payload);
      })
    );
  }

  // Sensors

  private void startSensor (int type, String name, JSONObject data) {
    if (sensors == null) {
      sensors = new WebAppSensors(this);
    }
    if (sensors.start(type, data.optInt("refresh_rate", 1000),
      data.optBoolean("need_absolute", false))) {
      send(name + "_started", null);
    } else {
      JSONObject payload = new JSONObject();
      put(payload, "error", "UNSUPPORTED");
      send(name + "_failed", payload);
    }
  }

  private void stopSensor (int type, String name) {
    if (sensors != null) {
      sensors.stop(type);
    }
    send(name + "_stopped", null);
  }

  // Emoji status

  private void setEmojiStatus (int generation, JSONObject data) {
    long customEmojiId = StringUtils.parseLong(data.optString("custom_emoji_id", "0"));
    final int duration = (int) Math.min(MAX_EMOJI_STATUS_DURATION,
      Math.max(0, data.optLong("duration", 0)));
    if (customEmojiId == 0) {
      replyError(generation, "emoji_status_failed", "SUGGESTED_EMOJI_INVALID");
      return;
    }
    String botName = controller.getBotName();
    CharSequence message = duration > 0 ?
      Lang.getStringBold(R.string.vg_BotEmojiStatusSetRequestFor, botName,
        Lang.getDurationFull(duration)) :
      Lang.getStringBold(R.string.vg_BotEmojiStatusSetRequest, botName);
    confirm(botName, message, Lang.getString(R.string.vg_BotEmojiStatusConfirm),
      Lang.getString(R.string.Cancel), confirmed -> {
      if (!confirmed) {
        replyError(generation, "emoji_status_failed", "USER_DECLINED");
        return;
      }
      if (!tdlib.hasPremium()) {
        replyError(generation, "emoji_status_failed", "USER_DECLINED");
        controller.showPremiumRequired();
        return;
      }
      int expirationDate = duration > 0 ? (int) (System.currentTimeMillis() / 1000L) + duration : 0;
      TdApi.EmojiStatus status = new TdApi.EmojiStatus(
        new TdApi.EmojiStatusTypeCustomEmoji(customEmojiId), expirationDate);
      tdlib.send(new TdApi.SetEmojiStatus(status), (ok, error) -> UI.post(() -> {
        if (error != null) {
          replyError(generation, "emoji_status_failed", "SERVER_ERROR");
        } else {
          reply(generation, "emoji_status_set", null);
          controller.showBulletin(Lang.getString(R.string.vg_BotEmojiStatusUpdated));
        }
      }));
    });
  }

  private void requestEmojiStatusAccess (int generation) {
    tdlib.cache().userFull(botUserId, userFull -> UI.post(() -> {
      if (userFull == null || userFull.botInfo == null) {
        replyStatus(generation, "emoji_status_access_requested", "cancelled");
        return;
      }
      if (userFull.botInfo.canManageEmojiStatus) {
        replyStatus(generation, "emoji_status_access_requested", "allowed");
        return;
      }
      String botName = controller.getBotName();
      confirm(botName,
        Lang.getStringBold(R.string.vg_BotEmojiStatusPermissionRequest, botName, botName),
        Lang.getString(R.string.vg_BotEmojiStatusPermissionAllow),
        Lang.getString(R.string.vg_BotEmojiStatusPermissionDecline), allowed -> {
        if (!allowed) {
          replyStatus(generation, "emoji_status_access_requested", "cancelled");
          return;
        }
        if (!tdlib.hasPremium()) {
          replyStatus(generation, "emoji_status_access_requested", "cancelled");
          controller.showPremiumRequired();
          return;
        }
        tdlib.send(new TdApi.ToggleBotCanManageEmojiStatus(botUserId, true),
          (ok, error) -> UI.post(() -> {
          replyStatus(generation, "emoji_status_access_requested",
            error == null ? "allowed" : "cancelled");
          if (error == null) {
            controller.showBulletin(Lang.getStringBold(
              R.string.vg_BotEmojiStatusPermissionRequestGranted, botName));
          }
        }));
      });
    }));
  }

  private void replyError (int generation, String eventName, String error) {
    JSONObject payload = new JSONObject();
    put(payload, "error", error);
    reply(generation, eventName, payload);
  }

  private void replyLocationUnavailable (int generation) {
    JSONObject payload = new JSONObject();
    put(payload, "available", false);
    reply(generation, "location_requested", payload);
  }

  // Downloads

  private void requestFileDownload (int generation, @Nullable String eventData) {
    String url, fileName;
    try {
      JSONObject data = parse(eventData);
      url = data.getString("url");
      fileName = data.getString("file_name");
    } catch (JSONException e) {
      replyStatus(generation, "file_download_requested", "cancelled");
      return;
    }
    if (!consumeUserGesture()) {
      replyStatus(generation, "file_download_requested", "cancelled");
      return;
    }
    WebAppDownloads.request(controller, tdlib, botUserId, controller.getBotName(), url, fileName,
      status ->
      replyStatus(generation, "file_download_requested", status)
    );
  }

  // Prepared messages

  private void sendPreparedMessage (int generation, String preparedMessageId) {
    if (StringUtils.isEmpty(preparedMessageId)) {
      replyError(generation, "prepared_message_failed", "MESSAGE_EXPIRED");
      return;
    }
    tdlib.send(new TdApi.GetPreparedInlineMessage(botUserId, preparedMessageId),
      (prepared, error) ->
      UI.post(() -> {
        if (error != null || controller.isDestroyed()) {
          replyError(generation, "prepared_message_failed", "MESSAGE_EXPIRED");
          return;
        }
        sharePreparedMessage(generation, prepared);
      })
    );
  }

  private void sharePreparedMessage (int generation, TdApi.PreparedInlineMessage prepared) {
    final boolean[] isSent = new boolean[1];
    final TdApi.TargetChatTypes types = prepared.chatTypes;
    ShareController c = new ShareController(controller.context(), tdlib);
    c.setArguments(new ShareController.Args(new ShareController.ShareProviderDelegate() {
      @Override
      public void generateFunctionsForChat (
        long chatId,
        TdApi.Chat chat,
        TdApi.MessageSendOptions sendOptions,
        ArrayList<TdApi.Function<?>> functions
      ) {
        functions.add(new TdApi.SendInlineQueryResultMessage(chatId, null, null, sendOptions,
          prepared.inlineQueryId,
          prepared.result != null ? WebApp.getInlineQueryResultId(prepared.result) : "",
          false));
      }

      @Override
      public CharSequence generateErrorMessageForChat (long chatId) {
        return WebApp.isChatAllowed(tdlib, chatId, types) ? null :
          Lang.getString(R.string.vg_BotCantSharePreparedMessage);
      }
    }).setAfter(() -> {
      // ShareController reports when the messages are handed to TDLib, not their delivery.
      isSent[0] = true;
      UI.post(() -> reply(generation, "prepared_message_sent", null));
    }));
    c.addDestroyListener(() -> {
      if (!isSent[0]) {
        replyError(generation, "prepared_message_failed", "USER_DECLINED");
      }
    });
    c.show();
  }

  // Requested chats

  private void requestChat (int generation, String requestId) {
    tdlib.send(new TdApi.GetPreparedKeyboardButton(botUserId, requestId),
      (button, error) -> UI.post(() -> {
      if (error != null || controller.isDestroyed()) {
        if (error != null) {
          UI.showError(error);
        }
        replyRequestId(generation, "requested_chat_failed", requestId);
        return;
      }
      TdApi.KeyboardButtonSource source =
        new TdApi.KeyboardButtonSourceWebApp(botUserId, requestId);
      boolean started = controller.openRequestedChatPicker(button, source, sent ->
        replyRequestId(generation, sent ? "requested_chat_sent" : "requested_chat_failed",
          requestId)
      );
      if (!started) {
        replyRequestId(generation, "requested_chat_failed", requestId);
      }
    }));
  }

  private void replyRequestId (int generation, String eventName, String requestId) {
    JSONObject payload = new JSONObject();
    put(payload, "req_id", requestId);
    reply(generation, eventName, payload);
  }

  // Storage

  private void handleStorage (int generation, boolean secure, String eventType, JSONObject data) {
    final String requestId = data.optString("req_id", null);
    if (StringUtils.isEmpty(requestId)) {
      return;
    }
    final String prefix = secure ? "secure_storage_" : "device_storage_";
    final WebAppStorage storage = storage(secure);
    final String key = data.optString("key", null);
    final WebAppStorage.Callback callback = (value, error, canRestore) -> {
      JSONObject payload = new JSONObject();
      put(payload, "req_id", requestId);
      if (error != null) {
        put(payload, "error", error);
        reply(generation, prefix + "failed", payload);
        return;
      }
      String event;
      if (eventType.endsWith("_save_key")) {
        event = "key_saved";
      } else if (eventType.endsWith("_get_key")) {
        event = "key_received";
        put(payload, "value", value);
        if (secure && value == null) {
          put(payload, "can_restore", canRestore);
        }
      } else if (eventType.endsWith("_restore_key")) {
        event = "key_restored";
        put(payload, "value", value);
      } else {
        event = "cleared";
      }
      reply(generation, prefix + event, payload);
    };
    if (eventType.endsWith("_clear")) {
      storage.clear(callback);
      return;
    }
    if (StringUtils.isEmpty(key)) {
      callback.onResult(null, WebAppStorage.ERROR_KEY_INVALID, false);
      return;
    }
    if (eventType.endsWith("_save_key")) {
      Object value = data.opt("value");
      storage.save(key, value == null || value == JSONObject.NULL ? null : value.toString(),
        callback);
    } else if (eventType.endsWith("_get_key")) {
      storage.get(key, callback);
    } else {
      storage.restore(controller, controller.getBotName(), key, callback);
    }
  }

  private WebAppStorage storage (boolean secure) {
    if (secure) {
      if (secureStorage == null) {
        secureStorage = new WebAppStorage(tdlib, botUserId, true);
      }
      return secureStorage;
    }
    if (deviceStorage == null) {
      deviceStorage = new WebAppStorage(tdlib, botUserId, false);
    }
    return deviceStorage;
  }

  private WebAppBiometry biometry () {
    if (biometry == null) {
      biometry = new WebAppBiometry(tdlib, botUserId);
    }
    return biometry;
  }

  private WebAppLocation location () {
    if (location == null) {
      location = new WebAppLocation(tdlib, botUserId);
    }
    return location;
  }
}
