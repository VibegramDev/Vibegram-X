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

import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

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
import java.util.Locale;

import me.vkryl.core.StringUtils;
import tgx.td.ChatId;

/**
 * The {@code TelegramWebviewProxy} of a mini app: receives {@code web_app_*} events of the page and
 * sends events back with {@code window.Telegram.WebView.receiveEvent}. Event names and payloads
 * follow the official clients (core.telegram.org/api/web-events).
 */
final class WebAppBridge implements WebAppSensors.Listener {
  static final String INTERFACE_NAME = "TelegramWebviewProxy";

  private static final int MAX_MESSAGE_LENGTH = 1024 * 1024, MAX_EVENT_NAME_LENGTH = 128;
  private static final long USER_GESTURE_TIMEOUT_MS = 10_000L;
  private static final long POPUP_REOPEN_WINDOW_MS = 150L, POPUP_COOLDOWN_MS = 3_000L;
  private static final int POPUP_MAX_RAPID_OPENS = 3;

  private final WebAppController controller;
  private final Tdlib tdlib;
  private final long botUserId;

  /**
   * Incremented on each navigation of the page, so replies to requests of a previous page are
   * dropped.
   */
  private int documentGeneration;
  private long lastUserGestureMs;

  private boolean isPopupShown, isPermissionDialogShown, isQrScannerShown, isInvoiceOpen,
    isDataSent;
  private long lastPopupClosedMs, popupCooldownUntilMs;
  private int rapidPopupCount;

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

  void onPageStarted () {
    documentGeneration++;
    lastUserGestureMs = 0;
    if (sensors != null) {
      sensors.release();
      sensors = null;
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

  private boolean consumeUserGesture () {
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

  // Only the page calls it, so R8 would remove it without @Keep.
  @Keep
  @JavascriptInterface
  public void postEvent (final String eventType, final String eventData) {
    if (StringUtils.isEmpty(eventType) || eventType.length() > MAX_EVENT_NAME_LENGTH ||
      (eventData != null && eventData.length() > MAX_MESSAGE_LENGTH)) {
      return;
    }
    UI.post(() -> {
      if (!controller.isDestroyed() && controller.isTrustedDocument()) {
        try {
          handleEvent(eventType, eventData);
        } catch (Throwable t) {
          Log.e("Cannot handle mini app event %s", t, eventType);
        }
      }
    });
  }

  private static JSONObject parse (@Nullable String data) throws JSONException {
    return StringUtils.isEmpty(data) ? new JSONObject() : new JSONObject(data);
  }

  @UiThread
  private void handleEvent (String eventType, @Nullable String eventData) throws JSONException {
    final int generation = documentGeneration;
    switch (eventType) {
      case "web_app_ready":
        controller.onPageReady();
        break;
      case "web_app_expand":
        controller.expand();
        break;
      case "web_app_close":
        controller.closeByApp(parse(eventData).optBoolean("return_back", false));
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
        controller.setBottomBarColor(
          StringUtils.isEmpty(color) ? 0 : parseColor(color) | 0xff000000
        );
        break;
      }
      case "web_app_request_theme":
        sendTheme();
        break;
      case "web_app_request_viewport":
        controller.sendViewport(true);
        break;
      case "web_app_request_safe_area":
        controller.sendSafeArea(true);
        break;
      case "web_app_request_content_safe_area":
        controller.sendContentSafeArea(true);
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
      case "web_app_allow_scroll": {
        JSONArray data = StringUtils.isEmpty(eventData) ?
          new JSONArray() : new JSONArray(eventData);
        controller.setPageScrollAllowed(data.optBoolean(0, true), data.optBoolean(1, true));
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
        WebAppHaptics.perform(data.optString("type", ""), data.optString("impact_style", null),
          data.optString("notification_type", null));
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
      case "web_app_biometry_request_access":
        biometry().requestAccess(controller, controller.getBotName(), optReason(eventData), () ->
          reply(generation, "biometry_info_received", biometry().getInfo())
        );
        break;
      case "web_app_biometry_request_auth":
        biometry().requestAuth(controller, controller.getBotName(), optReason(eventData),
          (status, token) -> {
          JSONObject payload = new JSONObject();
          put(payload, "status", status);
          if (token != null) {
            put(payload, "token", token);
          }
          reply(generation, "biometry_auth_requested", payload);
        });
        break;
      case "web_app_biometry_update_token": {
        JSONObject data = parse(eventData);
        String token = data.getString("token");
        biometry().updateToken(controller, controller.getBotName(), token,
          data.optString("reason", null),
          (status, ignored) -> {
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
              JSONObject payload = new JSONObject();
              put(payload, "error", "UNSUPPORTED");
              reply(generation, "home_screen_failed", payload);
            }
          });
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
        }
        break;
      case "web_app_request_emoji_status_access":
        if (consumeUserGesture()) {
          requestEmojiStatusAccess(generation);
        }
        break;
      case "web_app_check_location":
        send("location_checked", location().check());
        break;
      case "web_app_request_location":
        location().request(controller, controller.getBotName(), payload -> {
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
    try {
      JSONObject payload = new JSONObject();
      payload.put("theme_params", new JSONObject(controller.buildThemeJson()));
      send("theme_changed", payload);
    } catch (JSONException e) {
      send("theme_changed", new JSONObject());
    }
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
    final boolean[] paid = new boolean[1];
    StarsPaymentSheet.open(controller, tdlib, new TdApi.InputInvoiceName(slug),
      () -> paid[0] = true,
      () -> {
        isInvoiceOpen = false;
        replyInvoice(generation, slug, paid[0] ? "paid" : "cancelled");
      }
    );
  }

  private void replyInvoice (int generation, String slug, String status) {
    JSONObject payload = new JSONObject();
    put(payload, "slug", slug);
    put(payload, "status", status);
    reply(generation, "invoice_closed", payload);
  }

  // Popups

  private void openPopup (int generation, JSONObject data) throws JSONException {
    final long now = SystemClock.uptimeMillis();
    if (isPopupShown || now < popupCooldownUntilMs) {
      return;
    }
    String message = data.getString("message");
    JSONArray buttons = data.getJSONArray("buttons");
    if (buttons.length() == 0 || buttons.length() > 3 || message.length() > 256) {
      return;
    }
    String[] ids = new String[buttons.length()];
    String[] texts = new String[buttons.length()];
    boolean[] destructive = new boolean[buttons.length()];
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.getJSONObject(i);
      ids[i] = button.getString("id");
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
          destructive[i] = true;
          texts[i] = button.getString("text");
          break;
        default:
          texts[i] = button.getString("text");
          break;
      }
    }
    if (now - lastPopupClosedMs < POPUP_REOPEN_WINDOW_MS) {
      if (++rapidPopupCount >= POPUP_MAX_RAPID_OPENS) {
        rapidPopupCount = 0;
        popupCooldownUntilMs = now + POPUP_COOLDOWN_MS;
        return;
      }
    } else {
      rapidPopupCount = 0;
    }
    final String[] chosenId = new String[1];
    String title = data.optString("title", "");
    AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
    b.setTitle(StringUtils.isEmpty(title) ? controller.getBotName() : title);
    b.setMessage(message);
    b.setPositiveButton(texts[0], (dialog, which) -> chosenId[0] = ids[0]);
    if (destructive[0]) {
      b.setPositiveButtonDestructive();
    }
    if (ids.length > 1) {
      b.setNegativeButton(texts[1], (dialog, which) -> chosenId[0] = ids[1]);
    }
    if (ids.length > 2) {
      b.setNeutralButton(texts[2], (dialog, which) -> chosenId[0] = ids[2]);
    }
    b.setOnDismissListener(dialog -> {
      isPopupShown = false;
      lastPopupClosedMs = SystemClock.uptimeMillis();
      JSONObject payload = new JSONObject();
      if (chosenId[0] != null) {
        onUserInteraction();
        put(payload, "button_id", chosenId[0]);
      }
      reply(generation, "popup_closed", payload);
    });
    isPopupShown = controller.showAlert(b) != null;
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
    controller.closeByApp(false);
  }

  private void switchInlineQuery (JSONObject data) throws JSONException {
    String query = data.getString("query");
    JSONArray chatTypes = data.getJSONArray("chat_types");
    controller.switchInlineQuery(query, chatTypes.length() == 0);
  }

  // Permissions

  private void requestWriteAccess (int generation) {
    if (isPermissionDialogShown) {
      replyStatus(generation, "write_access_requested", "cancelled");
      return;
    }
    isPermissionDialogShown = true;
    tdlib.send(new TdApi.CanBotSendMessages(botUserId), (ok, error) -> UI.post(() -> {
      if (error == null) {
        isPermissionDialogShown = false;
        replyStatus(generation, "write_access_requested", "allowed");
        return;
      }
      final boolean[] allowed = new boolean[1];
      AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
      b.setTitle(Lang.getString(R.string.vg_BotWebViewRequestWriteTitle));
      b.setMessage(Lang.getString(R.string.vg_BotWebViewRequestWriteMessage));
      b.setPositiveButton(Lang.getString(R.string.vg_BotWebViewRequestAllow),
        (dialog, which) -> allowed[0] = true);
      b.setNegativeButton(Lang.getString(R.string.vg_BotWebViewRequestDontAllow), null);
      b.setOnDismissListener(dialog -> {
        if (!allowed[0]) {
          isPermissionDialogShown = false;
          replyStatus(generation, "write_access_requested", "cancelled");
          return;
        }
        tdlib.send(new TdApi.AllowBotToSendMessages(botUserId),
          (result, allowError) -> UI.post(() -> {
          isPermissionDialogShown = false;
          if (allowError != null) {
            UI.showError(allowError);
          }
          replyStatus(generation, "write_access_requested",
            allowError == null ? "allowed" : "cancelled");
        }));
      });
      if (controller.showAlert(b) == null) {
        isPermissionDialogShown = false;
        replyStatus(generation, "write_access_requested", "cancelled");
      }
    }));
  }

  private void requestPhone (int generation) {
    TdApi.User me = tdlib.myUser();
    if (isPermissionDialogShown || me == null) {
      replyStatus(generation, "phone_requested", "cancelled");
      return;
    }
    isPermissionDialogShown = true;
    final boolean isBlocked = tdlib.chatFullyBlocked(ChatId.fromUserId(botUserId));
    CharSequence message = Lang.getStringBold(R.string.vg_AreYouSureShareMyContactInfoWebapp,
      controller.getBotName());
    if (isBlocked) {
      message = TextUtils.concat(message, "\n\n",
        Lang.getString(R.string.vg_AreYouSureShareMyContactInfoBotUnblock));
    }
    final boolean[] share = new boolean[1];
    AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
    b.setTitle(Lang.getString(R.string.vg_ShareYouPhoneNumberTitle));
    b.setMessage(message);
    b.setPositiveButton(Lang.getString(R.string.ShareContact), (dialog, which) -> share[0] = true);
    b.setNegativeButton(Lang.getString(R.string.Cancel), null);
    b.setOnDismissListener(dialog -> {
      isPermissionDialogShown = false;
      if (!share[0]) {
        replyStatus(generation, "phone_requested", "cancelled");
        return;
      }
      if (isBlocked) {
        tdlib.send(
          new TdApi.SetMessageSenderBlockList(new TdApi.MessageSenderUser(botUserId), null),
          tdlib.typedOkHandler());
      }
      TdApi.Contact contact = new TdApi.Contact(me.phoneNumber, me.firstName, me.lastName, "",
        me.id);
      TdApi.SendMessage function = new TdApi.SendMessage(
        ChatId.fromUserId(botUserId), null, null, null,
        null, new TdApi.InputMessageContact(contact));
      tdlib.send(function, (sent, error) -> UI.post(() ->
        replyStatus(generation, "phone_requested", error == null ? "sent" : "cancelled")
      ));
    });
    if (controller.showAlert(b) == null) {
      isPermissionDialogShown = false;
      replyStatus(generation, "phone_requested", "cancelled");
    }
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
    final Object params = data.get("params");
    tdlib.send(new TdApi.SendWebAppCustomRequest(botUserId, method, params.toString()),
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
    int refreshRate = Math.max(20, Math.min(1000, data.optInt("refresh_rate", 1000)));
    if (sensors.start(type, refreshRate, data.optBoolean("need_absolute", false))) {
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
    int duration = Math.max(0, data.optInt("duration", 0));
    if (customEmojiId == 0) {
      replyError(generation, "emoji_status_failed", "SUGGESTED_EMOJI_INVALID");
      return;
    }
    final boolean[] confirmed = new boolean[1];
    AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
    b.setTitle(controller.getBotName());
    if (duration > 0) {
      b.setMessage(Lang.getStringBold(R.string.vg_BotEmojiStatusSetRequestFor,
        controller.getBotName(),
        WebApp.formatDuration(duration)));
    } else {
      b.setMessage(Lang.getStringBold(R.string.vg_BotEmojiStatusSetRequest,
        controller.getBotName()));
    }
    b.setPositiveButton(Lang.getString(R.string.vg_BotEmojiStatusConfirm),
      (dialog, which) -> confirmed[0] = true);
    b.setNegativeButton(Lang.getString(R.string.Cancel), null);
    b.setOnDismissListener(dialog -> {
      if (!confirmed[0]) {
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
    if (controller.showAlert(b) == null) {
      replyError(generation, "emoji_status_failed", "USER_DECLINED");
    }
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
      final boolean[] allowed = new boolean[1];
      AlertPopup.Builder b = new AlertPopup.Builder(controller.context());
      b.setTitle(controller.getBotName());
      b.setMessage(Lang.getStringBold(R.string.vg_BotEmojiStatusPermissionRequest,
        controller.getBotName(),
        controller.getBotName()));
      b.setPositiveButton(Lang.getString(R.string.vg_BotEmojiStatusPermissionAllow),
        (dialog, which) -> allowed[0] = true);
      b.setNegativeButton(Lang.getString(R.string.vg_BotEmojiStatusPermissionDecline), null);
      b.setOnDismissListener(dialog -> {
        if (!allowed[0]) {
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
              R.string.vg_BotEmojiStatusPermissionRequestGranted,
              controller.getBotName()));
          }
        }));
      });
      if (controller.showAlert(b) == null) {
        replyStatus(generation, "emoji_status_access_requested", "cancelled");
      }
    }));
  }

  private void replyError (int generation, String eventName, String error) {
    JSONObject payload = new JSONObject();
    put(payload, "error", error);
    reply(generation, eventName, payload);
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
        if (!isSent[0]) {
          isSent[0] = true;
          UI.post(() -> reply(generation, "prepared_message_sent", null));
        }
      }

      @Override
      public CharSequence generateErrorMessageForChat (long chatId) {
        return WebApp.isChatAllowed(tdlib, chatId, types) ? null :
          Lang.getString(R.string.vg_BotCantSharePreparedMessage);
      }
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
