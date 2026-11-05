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
package org.thunderdog.challegram.ui;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.widget.M3Sheets;

import java.util.ArrayList;

import me.vkryl.core.StringUtils;

/**
 * «Log in with Telegram», as in the official app (OAuthSheet): the request of a website or an app to log in with
 * this account. Shows who asks (bot avatar, domain or verified app name), from where (device, browser, location, IP),
 * the optional «Allow messages» permission, and Decline / Log In. The phone number is shared only after a separate
 * question, and the emoji or code shown on the login screen is matched when the request requires it.
 *
 * Requests arrive as a notification (updateNewOauthRequest, «Login Request»); a tap opens this sheet. OAuth links
 * opened from outside of the app (tg://oauth, t.me/oauth) open it directly. Login buttons of bots and login links use
 * the same sheet without the device part.
 */
public final class OAuthSheet {
  private OAuthSheet () { }

  private static final String CHANNEL_ID = "vg_login_requests";
  private static final int NOTIFICATION_ID_BASE = Integer.MAX_VALUE - 1000;

  // Notification

  public static void showNotification (Tdlib tdlib, TdApi.UpdateNewOauthRequest update) {
    if (StringUtils.isEmpty(update.url)) {
      return;
    }
    Context context = AppContext.get();
    NotificationManagerCompat manager = NotificationManagerCompat.from(context);
    if (!manager.areNotificationsEnabled()) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager m = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (m != null) {
        android.app.NotificationChannel channel = new android.app.NotificationChannel(CHANNEL_ID, Lang.getString(R.string.vg_BotAuthNotificationChannel), NotificationManager.IMPORTANCE_HIGH);
        try {
          m.createNotificationChannel(channel);
        } catch (Throwable ignored) { }
      }
    }
    Intent intent = new Intent(context, MainActivity.class);
    Intents.secureIntent(intent, true);
    intent.setAction(Intents.ACTION_OPEN_OAUTH + "." + tdlib.id() + "." + Math.random());
    intent.putExtra("account_id", tdlib.id());
    intent.putExtra("oauth_url", update.url);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    final int notificationId = NOTIFICATION_ID_BASE - tdlib.id();
    PendingIntent contentIntent = PendingIntent.getActivity(context, notificationId, intent, flags);
    String domain = StringUtils.isEmpty(update.domain) ? "—" : update.domain;
    String location = StringUtils.isEmpty(update.location) ? "—" : update.location;
    String text = Lang.getString(R.string.vg_BotAuthNotification, domain, location);
    NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.app_notification)
      .setContentTitle(Lang.getString(R.string.vg_BotAuthNotificationTitle))
      .setContentText(text)
      .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setAutoCancel(true)
      .setShowWhen(true)
      .setWhen(System.currentTimeMillis())
      .setTimeoutAfter(60_000L) // the link is valid for 60 seconds
      .setContentIntent(contentIntent);
    if (tdlib.context().isMultiUser()) {
      b.setSubText(tdlib.accountName());
    }
    try {
      manager.notify(notificationId, b.build());
    } catch (SecurityException ignored) {
      // POST_NOTIFICATIONS not granted
    }
  }

  public static void cancelNotification (int accountId) {
    NotificationManagerCompat.from(AppContext.get()).cancel(NOTIFICATION_ID_BASE - accountId);
  }

  // Request

  private static final class Request {
    final Tdlib tdlib;
    final String url;
    final boolean isOauth;
    final boolean fromBotButton;
    final boolean external;
    String domain = "";
    boolean fromApp;
    String verifiedAppName = "";
    long botUserId;
    boolean requestWriteAccess, requestPhoneNumberAccess;
    String browser = "", platform = "", ipAddress = "", location = "";
    boolean matchCodeFirst;
    String[] matchCodes = new String[0];

    @Nullable String selectedMatchCode;
    boolean allowWriteAccess = true;
    boolean allowPhoneNumberAccess;
    /** The main sheet is being replaced by the match code sheet: hiding it is not a decline. */
    boolean handedOff;

    Request (Tdlib tdlib, String url, boolean isOauth, boolean fromBotButton, boolean external) {
      this.tdlib = tdlib;
      this.url = url;
      this.isOauth = isOauth;
      this.fromBotButton = fromBotButton;
      this.external = external;
    }

    String displayName () {
      if (fromApp) {
        return StringUtils.isEmpty(verifiedAppName) ? Lang.getString(R.string.vg_UnverifiedApp) : verifiedAppName;
      }
      return domain;
    }

    boolean hasDeviceInfo () {
      return !StringUtils.isEmpty(platform) || !StringUtils.isEmpty(browser) || !StringUtils.isEmpty(location) || !StringUtils.isEmpty(ipAddress);
    }
  }

  public interface LoginUrlCallback {
    void onLogin (boolean allowWriteAccess);
  }

  // Entry points

  /** OAuth request (notification tap or a tg://oauth link from outside of the app). */
  public static void openOauthLink (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull String url, boolean external) {
    cancelNotification(tdlib.id());
    tdlib.send(new TdApi.GetOauthLinkInfo(url, ""), (info, error) -> UI.post(() -> {
      if (context.isDestroyed()) {
        return;
      }
      if (error != null) {
        showFailure(null, error);
        return;
      }
      if (info.userId != 0 && info.userId != tdlib.myUserId()) {
        // The link was made for another account of this app: process it with that account.
        int accountId = TdlibManager.instance().accountIdForUserId(info.userId, 0);
        if (accountId != -1 && accountId != tdlib.id()) {
          TdlibAccount account = TdlibManager.instance().account(accountId);
          if (!account.isUnauthorized()) {
            Tdlib other = account.tdlib();
            other.awaitInitialization(() -> UI.post(() -> openOauthLink(context, other, url, external)));
            return;
          }
        }
      }
      Request request = new Request(tdlib, url, true, false, external);
      request.domain = info.domain;
      request.fromApp = info.fromApp;
      request.verifiedAppName = info.verifiedAppName;
      request.botUserId = info.botUserId;
      request.requestWriteAccess = info.requestWriteAccess;
      request.requestPhoneNumberAccess = info.requestPhoneNumberAccess;
      request.browser = info.browser;
      request.platform = info.platform;
      request.ipAddress = info.ipAddress;
      request.location = info.location;
      request.matchCodeFirst = info.matchCodeFirst;
      request.matchCodes = info.matchCodes != null ? info.matchCodes : new String[0];
      start(context, request);
    }));
  }

  /** Login button of a bot message or a login link: «Log in to domain» with the optional message permission. */
  public static void showLoginUrl (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.LoginUrlInfoRequestConfirmation confirm, boolean fromBotButton, @NonNull LoginUrlCallback callback) {
    Request request = new Request(tdlib, confirm.url, false, fromBotButton, false);
    request.domain = confirm.domain;
    request.botUserId = confirm.botUserId;
    request.requestWriteAccess = confirm.requestWriteAccess;
    showMainSheet(context, request, callback);
  }

  private static void start (ViewController<?> context, Request request) {
    if (request.matchCodeFirst && request.matchCodes.length > 0) {
      showMatchCodeSheet(context, request, false, code -> {
        request.selectedMatchCode = code;
        request.tdlib.send(new TdApi.CheckOauthRequestMatchCode(request.url, code), (ok, error) -> UI.post(() -> {
          if (error != null) {
            showFailure(request.displayName(), null);
          } else if (!context.isDestroyed()) {
            showMainSheet(context, request, null);
          }
        }));
      }, () -> decline(request));
    } else {
      showMainSheet(context, request, null);
    }
  }

  // Main sheet

  private static void showMainSheet (ViewController<?> context, Request request, @Nullable LoginUrlCallback loginUrlCallback) {
    final Tdlib tdlib = request.tdlib;
    final boolean[] processing = new boolean[1];
    final boolean[] askingPhone = new boolean[1];
    final boolean[] processed = new boolean[1];
    final PopupLayout[] sheet = new PopupLayout[1];
    final SettingsAdapter[] adapterRef = new SettingsAdapter[1];
    final Runnable updateButtons = () -> {
      adapterRef[0].updateValuedSettingById(R.id.btn_vgOAuthLogin);
      adapterRef[0].updateValuedSettingById(R.id.btn_vgOAuthDecline);
    };
    SettingsAdapter adapter = new SettingsAdapter(context, v -> {
      final int id = v.getId();
      if (id == R.id.btn_vgOAuthAllowMessages) {
        request.allowWriteAccess = adapterRef[0].toggleView(v);
        return;
      }
      if (processing[0] || askingPhone[0] || request.handedOff || sheet[0] == null) {
        return;
      }
      PopupLayout popupLayout = sheet[0];
      if (id == R.id.btn_vgOAuthDecline) {
        processed[0] = true;
        popupLayout.hideWindow(true);
        if (request.isOauth) {
          decline(request);
        }
      } else if (id == R.id.btn_vgOAuthLogin) {
        if (!request.isOauth) {
          processed[0] = true;
          popupLayout.hideWindow(true);
          if (loginUrlCallback != null) {
            loginUrlCallback.onLogin(request.requestWriteAccess && request.allowWriteAccess);
          }
          return;
        }
        Runnable accept = () -> beforeAccept(context, request, () -> {
          processing[0] = true;
          processed[0] = true;
          updateButtons.run();
          accept(context, request, popupLayout);
        });
        if (request.requestPhoneNumberAccess) {
          askingPhone[0] = true;
          askPhoneNumber(context, request, accept, () -> askingPhone[0] = false);
        } else {
          accept.run();
        }
      }
    }, context) {
      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        view.setAvatar(tdlib, new TdApi.MessageSenderUser(request.botUserId), 80f);
        int subtitleRes = request.fromApp ? R.string.vg_BotAuthAppSubtitle : request.fromBotButton ? R.string.vg_BotAuthBotSubtitle : R.string.vg_BotAuthSiteSubtitle;
        TdApi.FormattedText subtitle = TD.toFormattedText(Lang.getString(subtitleRes), false);
        TD.parseMarkdownWithEntities(subtitle);
        view.setTexts(tdlib, Lang.getString(R.string.vg_BotAuthTitle, request.displayName()).replace("**", ""), subtitle);
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgOAuthAllowMessages) {
          view.getToggler().setRadioEnabled(request.allowWriteAccess, isUpdate);
        } else {
          view.setData(item.getStringValue());
        }
      }

      @Override
      protected void setM3Button (ListItem item, M3ButtonView view, boolean isUpdate) {
        super.setM3Button(item, view, isUpdate);
        view.setEnabled(!processing[0]);
      }
    };
    adapterRef[0] = adapter;
    adapter.setSegmented();

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER));
    if (request.hasDeviceInfo()) {
      boolean hasDevice = !StringUtils.isEmpty(request.platform) || !StringUtils.isEmpty(request.browser);
      if (hasDevice) {
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, 0, R.drawable.baseline_devices_other_24, StringUtils.isEmpty(request.platform) ? "—" : request.platform, false)
          .setStringValue(StringUtils.isEmpty(request.browser) ? "—" : request.browser));
      }
      if (!StringUtils.isEmpty(request.location) || !StringUtils.isEmpty(request.ipAddress)) {
        if (hasDevice) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        }
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, 0, R.drawable.baseline_language_24, StringUtils.isEmpty(request.location) ? "—" : request.location, false)
          .setStringValue(StringUtils.isEmpty(request.ipAddress) ? "—" : Lang.getString(R.string.vg_BotAuthBasedOnIP, request.ipAddress)));
      }
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_BotAuthInfo));
    }
    if (request.requestWriteAccess) {
      request.allowWriteAccess = true;
      items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_vgOAuthAllowMessages, 0, R.string.vg_BotAuthAllowMessages, R.id.btn_vgOAuthAllowMessages, true));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getString(R.string.vg_BotAuthAllowMessagesInfo, tdlib.cache().userName(request.botUserId)), false));
    }
    items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgOAuthLogin, 0, R.string.vg_BotAuthLogin));
    items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgOAuthDecline, 0, R.string.vg_Decline).setIntValue(M3ButtonView.STYLE_TEXT));
    adapter.setItems(items, false);
    sheet[0] = M3Sheets.showList(context, adapter);
    if (sheet[0] != null && request.isOauth) {
      // Closing the sheet without an answer declines the request, as in the official app.
      sheet[0].setDismissListener(new PopupLayout.DismissListener() {
        @Override
        public void onPopupDismissPrepare (PopupLayout popup) {
          if (!processed[0] && !request.handedOff) {
            processed[0] = true;
            decline(request);
          }
        }

        @Override
        public void onPopupDismiss (PopupLayout popup) { }
      });
    }
  }

  private static void askPhoneNumber (ViewController<?> context, Request request, Runnable after, Runnable onClosed) {
    TdApi.User self = request.tdlib.myUser();
    String phone = self != null ? Strings.formatPhone(self.phoneNumber) : "";
    String name = request.fromBotButton && !request.fromApp ? request.tdlib.cache().userName(request.botUserId) : request.displayName();
    new AlertPopup.Builder(context.context())
      .setController(context)
      .setTitle(Lang.getString(R.string.vg_BotAuthPhoneNumber))
      .setMessage(Strings.buildMarkdown(context, Lang.getString(R.string.vg_BotAuthPhoneNumberText, name, phone)))
      .setNegativeButton(Lang.getString(R.string.vg_BotAuthPhoneNumberDeny), (dialog, which) -> {
        request.allowPhoneNumberAccess = false;
        after.run();
      })
      .setPositiveButton(Lang.getString(R.string.vg_BotAuthPhoneNumberAccept), (dialog, which) -> {
        request.allowPhoneNumberAccess = true;
        after.run();
      })
      .setOnDismissListener(dialog -> onClosed.run())
      .show();
  }

  private static void beforeAccept (ViewController<?> context, Request request, Runnable accept) {
    if (request.matchCodes.length > 0 && StringUtils.isEmpty(request.selectedMatchCode)) {
      // The code sheet replaces the main sheet (sheets don't stack); Cancel brings the main sheet back.
      request.handedOff = true;
      showMatchCodeSheet(context, request, true, code -> {
        request.selectedMatchCode = code;
        accept.run();
      }, () -> {
        request.handedOff = false;
        if (!context.isDestroyed()) {
          showMainSheet(context, request, null);
        } else {
          decline(request);
        }
      });
    } else {
      accept.run();
    }
  }

  private static void accept (ViewController<?> context, Request request, PopupLayout sheet) {
    final String displayName = request.displayName();
    TdApi.AcceptOauthRequest function = new TdApi.AcceptOauthRequest(request.url, request.selectedMatchCode != null ? request.selectedMatchCode : "", request.requestWriteAccess && request.allowWriteAccess, request.allowPhoneNumberAccess, null);
    request.tdlib.send(function, (httpUrl, error) -> UI.post(() -> {
      sheet.hideWindow(true);
      if (error != null) {
        showFailure(displayName, error);
        return;
      }
      if (StringUtils.isEmpty(httpUrl.url)) {
        boolean withoutPhone = request.requestPhoneNumberAccess && !request.allowPhoneNumberAccess;
        String text = Lang.getString(withoutPhone ? R.string.vg_BotAuthLoggedInSuccessWithoutPhoneNumber : R.string.vg_BotAuthLoggedInSuccess, displayName).replace("**", "");
        UI.showToast(Lang.getString(R.string.vg_BotAuthLoggedInSuccessTitle) + "\n" + text, Toast.LENGTH_LONG);
        if (request.external) {
          BaseActivity activity = context.context();
          UI.post(() -> {
            if (!activity.isFinishing()) {
              // Back to the app that asked for the login.
              activity.moveTaskToBack(true);
            }
          }, 800);
        }
      } else {
        Uri uri = Uri.parse(httpUrl.url);
        if (!Intents.openUriInBrowser(uri)) {
          Intents.openLink(httpUrl.url);
        }
      }
    }));
  }

  private static void decline (Request request) {
    if (request.isOauth && !StringUtils.isEmpty(request.url)) {
      request.tdlib.send(new TdApi.DeclineOauthRequest(request.url), request.tdlib.typedOkHandler());
    }
  }

  private static void showFailure (@Nullable String displayName, @Nullable TdApi.Error error) {
    if (error != null && !"URL_EXPIRED".equals(error.message) && !StringUtils.isEmpty(error.message) && !error.message.startsWith("Invalid OAuth")) {
      UI.showError(error);
      return;
    }
    String text = StringUtils.isEmpty(displayName) ? Lang.getString(R.string.vg_BotAuthLoggedInFailNoDomain) : Lang.getString(R.string.vg_BotAuthLoggedInFail, displayName).replace("**", "");
    UI.showToast(Lang.getString(R.string.vg_BotAuthLoggedInFailTitle) + "\n" + text, Toast.LENGTH_LONG);
  }

  // Match codes

  private interface CodeCallback {
    void onCode (String code);
  }

  private static boolean isEmojiCodes (String[] codes) {
    for (String code : codes) {
      for (int i = 0; i < code.length(); ) {
        int codePoint = code.codePointAt(i);
        if (Character.isLetterOrDigit(codePoint)) {
          return false;
        }
        i += Character.charCount(codePoint);
      }
    }
    return true;
  }

  private static void showMatchCodeSheet (ViewController<?> context, Request request, boolean isCancel, CodeCallback onSelected, @Nullable Runnable onCancel) {
    final boolean[] chosen = new boolean[1];
    // Telegram X options sheet: the codes, then Cancel (back to the request) or Decline.
    boolean emoji = isEmojiCodes(request.matchCodes);
    int count = request.matchCodes.length;
    int[] ids = new int[count + 1];
    String[] titles = new String[count + 1];
    int[] colors = new int[count + 1];
    for (int i = 0; i < count; i++) {
      ids[i] = i + 1;
      titles[i] = request.matchCodes[i];
      colors[i] = ViewController.OptionColor.NORMAL;
    }
    ids[count] = R.id.btn_cancel;
    titles[count] = Lang.getString(isCancel ? R.string.Cancel : R.string.vg_Decline);
    colors[count] = isCancel ? ViewController.OptionColor.NORMAL : ViewController.OptionColor.RED;
    CharSequence info = TextUtils.concat(
      Lang.getString(emoji ? R.string.vg_BotAuthSelectEmoji : R.string.vg_BotAuthSelectCode), "\n",
      Strings.buildMarkdown(context, Lang.getString(R.string.vg_BotAuthLoginRequestFrom, request.displayName())));
    PopupLayout popup = context.showOptions(info, ids, titles, colors, null, (view, id) -> {
      if (id >= 1 && id <= count && !chosen[0]) {
        chosen[0] = true;
        onSelected.onCode(request.matchCodes[id - 1]);
      }
      return true;
    });
    if (popup != null && onCancel != null) {
      popup.setDismissListener(new PopupLayout.DismissListener() {
        @Override
        public void onPopupDismissPrepare (PopupLayout p) {
          if (!chosen[0]) {
            chosen[0] = true;
            onCancel.run();
          }
        }

        @Override
        public void onPopupDismiss (PopupLayout p) { }
      });
    }
  }
}
