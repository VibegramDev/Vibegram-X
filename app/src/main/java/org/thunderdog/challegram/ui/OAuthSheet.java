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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import org.thunderdog.challegram.navigation.DialogLayout;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.widget.AvatarView;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.NoScrollTextView;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.VgSheets;

import me.vkryl.core.ColorUtils;
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

  /** «Log in to domain» with the domain in the primary colour, as the link-coloured domain of the official app. */
  private static CharSequence buildTitle (Request request) {
    String name = request.displayName();
    SpannableStringBuilder b = new SpannableStringBuilder(Lang.getString(R.string.vg_BotAuthTitle, name).replace("**", ""));
    int index = b.toString().lastIndexOf(name);
    if (index != -1 && !name.isEmpty()) {
      b.setSpan(new ForegroundColorSpan(M3.primary()), index, index + name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return b;
  }

  private static void showMainSheet (ViewController<?> context, Request request, @Nullable LoginUrlCallback loginUrlCallback) {
    final Tdlib tdlib = request.tdlib;
    final boolean[] processing = new boolean[1];
    final boolean[] processed = new boolean[1];
    final TextView[] buttons = new TextView[2];
    PopupLayout sheet = VgSheets.showSheet(context, popupLayout -> {
      Context ctx = context.context();
      LinearLayout content = VgSheets.newContent(ctx);

      AvatarView avatarView = new AvatarView(ctx);
      avatarView.setUser(tdlib, request.botUserId, false);
      content.addView(avatarView, VgSheets.centered(Screen.dp(80f), 12f, 16f));

      TextView titleView = VgSheets.newTitle(ctx, buildTitle(request));
      content.addView(titleView, VgSheets.wide(0, 8f));

      int subtitleRes = request.fromApp ? R.string.vg_BotAuthAppSubtitle : request.fromBotButton ? R.string.vg_BotAuthBotSubtitle : R.string.vg_BotAuthSiteSubtitle;
      TextView subtitleView = VgSheets.newBody(ctx, VgSheets.markdown(Lang.getString(subtitleRes)), true);
      content.addView(subtitleView, VgSheets.wide(0, 20f));

      if (request.hasDeviceInfo()) {
        LinearLayout card = VgSheets.newCard(ctx);
        if (!StringUtils.isEmpty(request.platform) || !StringUtils.isEmpty(request.browser)) {
          card.addView(VgSheets.newCardRow(ctx, R.drawable.baseline_devices_other_24,
            StringUtils.isEmpty(request.platform) ? "—" : request.platform,
            StringUtils.isEmpty(request.browser) ? "—" : request.browser));
        }
        if (!StringUtils.isEmpty(request.location) || !StringUtils.isEmpty(request.ipAddress)) {
          card.addView(VgSheets.newCardRow(ctx, R.drawable.baseline_language_24,
            StringUtils.isEmpty(request.location) ? "—" : request.location,
            StringUtils.isEmpty(request.ipAddress) ? "—" : Lang.getString(R.string.vg_BotAuthBasedOnIP, request.ipAddress)));
        }
        content.addView(card, VgSheets.wide(0, 6f));
        TextView info = VgSheets.newCaption(ctx, Lang.getString(R.string.vg_BotAuthInfo), false);
        info.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
        content.addView(info, VgSheets.wide(0, 20f));
      }

      if (request.requestWriteAccess) {
        request.allowWriteAccess = true;
        FrameLayout switchCard = VgSheets.newSwitchCard(ctx, Lang.getString(R.string.vg_BotAuthAllowMessages), true, isEnabled -> request.allowWriteAccess = isEnabled);
        content.addView(switchCard, VgSheets.wide(0, 6f));
        TextView info = VgSheets.newCaption(ctx, Lang.getString(R.string.vg_BotAuthAllowMessagesInfo, tdlib.cache().userName(request.botUserId)), false);
        info.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
        content.addView(info, VgSheets.wide(0, 20f));
      }

      TextView decline = VgSheets.newDestructiveButton(ctx, Lang.getString(R.string.vg_Decline), v -> {
        if (processing[0]) {
          return;
        }
        processed[0] = true;
        popupLayout.hideWindow(true);
        if (request.isOauth) {
          decline(request);
        }
      });
      TextView login = VgSheets.newFilledButton(ctx, Lang.getString(R.string.vg_BotAuthLogin), v -> {
        if (processing[0]) {
          return;
        }
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
          VgSheets.setButtonEnabled(buttons[0], false);
          VgSheets.setButtonEnabled(buttons[1], false);
          accept(context, request, popupLayout, () -> {
            processing[0] = false;
            VgSheets.setButtonEnabled(buttons[0], true);
            VgSheets.setButtonEnabled(buttons[1], true);
          });
        });
        if (request.requestPhoneNumberAccess) {
          askPhoneNumber(context, request, accept);
        } else {
          accept.run();
        }
      });
      buttons[0] = decline;
      buttons[1] = login;
      content.addView(VgSheets.newButtonRow(ctx, decline, login), VgSheets.wide(4f, 0));
      return content;
    });
    if (sheet != null && request.isOauth) {
      // Closing the sheet without an answer declines the request, as in the official app.
      sheet.setDismissListener(new PopupLayout.DismissListener() {
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

  private static void askPhoneNumber (ViewController<?> context, Request request, Runnable after) {
    TdApi.User self = request.tdlib.myUser();
    String phone = self != null ? Strings.formatPhone(self.phoneNumber) : "";
    String name = request.fromBotButton && !request.fromApp ? request.tdlib.cache().userName(request.botUserId) : request.displayName();
    DialogLayout dialog = new DialogLayout(context.context(), context, null);
    dialog.setHeadline(Lang.getString(R.string.vg_BotAuthPhoneNumber));
    dialog.addMessageView(VgSheets.markdown(Lang.getString(R.string.vg_BotAuthPhoneNumberText, name, phone)));
    final PopupLayout[] popupLayout = new PopupLayout[1];
    dialog.addButton(R.id.btn_cancel, Lang.getString(R.string.vg_BotAuthPhoneNumberDeny), v -> {
      popupLayout[0].hideWindow(true);
      request.allowPhoneNumberAccess = false;
      after.run();
    });
    dialog.addButton(R.id.btn_done, Lang.getString(R.string.vg_BotAuthPhoneNumberAccept), v -> {
      popupLayout[0].hideWindow(true);
      request.allowPhoneNumberAccess = true;
      after.run();
    });
    popupLayout[0] = VgSheets.showDialog(context, dialog);
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

  private static void accept (ViewController<?> context, Request request, PopupLayout sheet, Runnable onFailed) {
    final String displayName = request.displayName();
    TdApi.AcceptOauthRequest function = new TdApi.AcceptOauthRequest(request.url, request.selectedMatchCode != null ? request.selectedMatchCode : "", request.requestWriteAccess && request.allowWriteAccess, request.allowPhoneNumberAccess);
    request.tdlib.send(function, (httpUrl, error) -> UI.post(() -> {
      sheet.hideWindow(true);
      if (error != null) {
        onFailed.run();
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
    PopupLayout popup = VgSheets.showSheet(context, popupLayout -> {
      Context ctx = context.context();
      LinearLayout content = VgSheets.newContent(ctx);
      boolean emoji = isEmojiCodes(request.matchCodes);
      TextView titleView = VgSheets.newTitle(ctx, Lang.getString(emoji ? R.string.vg_BotAuthSelectEmoji : R.string.vg_BotAuthSelectCode));
      titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f);
      content.addView(titleView, VgSheets.wide(12f, 24f));

      LinearLayout row = new LinearLayout(ctx);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER);
      for (int i = 0; i < request.matchCodes.length; i++) {
        final String code = request.matchCodes[i];
        TextView button = new NoScrollTextView(ctx);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Fonts.getRobotoMedium());
        button.setTextColor(M3.onSurface());
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, emoji ? 34f : 22f);
        button.setSingleLine(true);
        button.setText(code);
        android.graphics.drawable.GradientDrawable oval = new android.graphics.drawable.GradientDrawable();
        oval.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        oval.setColor(M3.surfaceContainerHigh());
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mask.setColor(0xffffffff);
        button.setBackground(new M3StateLayerDrawable(oval, mask, ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
        button.setOnClickListener(v -> {
          if (!chosen[0]) {
            chosen[0] = true;
            popupLayout.hideWindow(true);
            onSelected.onCode(code);
          }
        });
        Views.setClickable(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Screen.dp(72f), Screen.dp(72f));
        if (i > 0) {
          params.leftMargin = Screen.dp(20f);
        }
        row.addView(button, params);
      }
      content.addView(row, VgSheets.wide(0, 24f));

      SpannableStringBuilder footer = new SpannableStringBuilder(VgSheets.markdown(Lang.getString(R.string.vg_BotAuthLoginRequestFrom, request.displayName())));
      TextView footerView = VgSheets.newCaption(ctx, footer, true);
      content.addView(footerView, VgSheets.wide(0, 16f));

      TextView cancel = isCancel ?
        VgSheets.newTonalButton(ctx, Lang.getString(R.string.Cancel), v -> popupLayout.hideWindow(true)) :
        VgSheets.newDestructiveButton(ctx, Lang.getString(R.string.vg_Decline), v -> popupLayout.hideWindow(true));
      content.addView(cancel, VgSheets.buttonParams(0));
      return content;
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
