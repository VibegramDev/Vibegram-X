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
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.widget.Toast;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.ChatFilter;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibDelegate;
import org.thunderdog.challegram.telegram.TdlibNotificationUtils;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ChatsController;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.ui.camera.CameraController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.Permissions;
import org.thunderdog.challegram.widget.MaterialEditText;

import java.util.Collections;
import java.util.UUID;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;
import me.vkryl.core.lambda.RunnableBool;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.ChatId;
import tgx.td.Td;

/**
 * Entry points of mini apps and what they share: launch confirmations, theme parameters and
 * permission prompts.
 */
public final class WebApp {
  private WebApp () { }

  public static final String EXTRA_ACCOUNT_ID = "account_id", EXTRA_BOT_USER_ID = "bot_user_id";
  public static final String EXTRA_SHORTCUT_TOKEN = "shortcut_token";

  private static final String APPLICATION_NAME = "android";
  private static final String KEY_LAUNCH_CONFIRMED = "webapp_launch_confirmed_";
  private static final String KEY_SHORTCUT_TOKEN = "webapp_shortcut_token";
  private static final String SHORTCUT_PREFIX = "webapp_";

  private static final int LAUNCH_ALERT_NONE = 0, LAUNCH_ALERT_UNLESS_CONFIRMED = 1;
  private static final int LAUNCH_ALERT_ALWAYS = 2;

  /**
   * Mini apps need the WebView of Android 5.0: older ones expose every public method of the
   * JavaScript interface to the page and have no evaluateJavascript.
   */
  @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.LOLLIPOP)
  public static boolean isSupported () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
  }

  @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.LOLLIPOP)
  private static boolean checkSupported () {
    if (isSupported()) {
      return true;
    }
    UI.showApiLevelWarning(Build.VERSION_CODES.LOLLIPOP);
    return false;
  }

  /** Called when the account starts logging out, while its user id is still known. */
  public static void onLoggingOut (Tdlib tdlib, long userId) {
    if (!isSupported()) {
      return;
    }
    WebAppController.closeAll(tdlib.id());
    WebAppStorage.onLoggedOut(userId);
    WebAppBiometry.onLoggedOut(userId);
    WebAppLocation.onLoggedOut(userId);
  }

  // Entry points

  /** {@code inlineKeyboardButtonTypeWebApp} of a message. */
  public static void openInlineButton (
    ViewController<?> context,
    TdApi.Message message,
    String url
  ) {
    if (!checkSupported()) {
      return;
    }
    long botUserId = message.viaBotUserId != 0 ? message.viaBotUserId :
      Td.getSenderUserId(message);
    if (botUserId == 0) {
      botUserId = context.tdlib().chatUserId(message.chatId);
    }
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_INLINE_BUTTON, botUserId,
      message.chatId);
    args.url = url;
    args.topicId = message.topicId;
    args.replyTo = new TdApi.InputMessageReplyToMessage(message.id, null, 0, null);
    args.mode = parseModeFromUrl(url, WebAppController.MODE_COMPACT);
    launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
  }

  /** {@code keyboardButtonTypeWebApp} of a reply keyboard: the app may send data back once. */
  public static void openKeyboardButton (
    ViewController<?> context,
    long chatId,
    long botUserId,
    String buttonText,
    String url
  ) {
    if (!checkSupported()) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_KEYBOARD_BUTTON, botUserId,
      chatId);
    args.url = url;
    args.buttonText = buttonText;
    args.mode = parseModeFromUrl(url, WebAppController.MODE_COMPACT);
    launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
  }

  /** {@code botMenuButton} next to the composer of a chat with a bot. */
  public static void openMenuButton (
    ViewController<?> context,
    long botUserId,
    TdApi.BotMenuButton button
  ) {
    if (context.tdlib().isKnownHost(Uri.parse(button.url).getHost(), false)) {
      context.tdlib().ui().openUrl(context, button.url, null);
      return;
    }
    if (!checkSupported()) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_MENU_BUTTON, botUserId,
      ChatId.fromUserId(botUserId));
    args.url = button.url;
    args.needsContext = true;
    args.mode = parseModeFromUrl(button.url, WebAppController.MODE_COMPACT);
    launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
  }

  /** The «switch to web app» button above inline results. */
  public static void openInlineSwitch (
    ViewController<?> context,
    long chatId,
    long botUserId,
    String url
  ) {
    if (!checkSupported()) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_INLINE_SWITCH, botUserId,
      chatId);
    args.url = url;
    args.needsContext = true;
    args.mode = parseModeFromUrl(url, WebAppController.MODE_COMPACT);
    launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
  }

  /** The main mini app of a bot from the «Open App» button of its profile. */
  public static void openMainApp (ViewController<?> context, long botUserId) {
    if (!checkSupported()) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(WebAppController.TYPE_MAIN_APP,
      botUserId, 0);
    args.mode = WebAppController.MODE_FULL_SIZE;
    launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
  }

  /**
   * {@code internalLinkTypeWebApp}, {@code internalLinkTypeMainWebApp} or
   * {@code internalLinkTypeAttachmentMenuBot}; {@code after} gets whether the link was handled.
   */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  public static void openLink (
    TdlibDelegate context,
    TdApi.InternalLinkType linkType,
    @Nullable RunnableBool after
  ) {
    final ViewController<?> c = findController(context);
    if (c == null) {
      if (after != null) {
        after.runWithBool(false);
      }
      return;
    }
    final Tdlib tdlib = context.tdlib();
    final String botUsername;
    switch (linkType.getConstructor()) {
      case TdApi.InternalLinkTypeWebApp.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeWebApp) linkType).botUsername;
        break;
      case TdApi.InternalLinkTypeMainWebApp.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeMainWebApp) linkType).botUsername;
        break;
      case TdApi.InternalLinkTypeAttachmentMenuBot.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeAttachmentMenuBot) linkType).botUsername;
        break;
      default:
        throw new IllegalArgumentException(linkType.toString());
    }
    tdlib.send(new TdApi.SearchPublicChat(botUsername), (chat, error) -> UI.post(() -> {
      long botUserId = chat != null ? tdlib.chatUserId(chat.id) : 0;
      TdApi.User bot = botUserId != 0 ? tdlib.cache().user(botUserId) : null;
      boolean isBot = bot != null && bot.type.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR;
      if (after != null) {
        after.runWithBool(isBot);
      } else if (error != null) {
        UI.showError(error);
      }
      if (!isBot) {
        return;
      }
      long currentChatId = getCurrentChatId(c);
      switch (linkType.getConstructor()) {
        case TdApi.InternalLinkTypeWebApp.CONSTRUCTOR:
          openAppLink(c, bot, currentChatId, (TdApi.InternalLinkTypeWebApp) linkType);
          break;
        case TdApi.InternalLinkTypeMainWebApp.CONSTRUCTOR: {
          TdApi.InternalLinkTypeMainWebApp link = (TdApi.InternalLinkTypeMainWebApp) linkType;
          if (((TdApi.UserTypeBot) bot.type).hasMainWebApp) {
            openMainAppLink(c, bot, currentChatId, link);
          } else {
            tdlib.ui().openPrivateChat(c, bot.id, null);
          }
          break;
        }
        case TdApi.InternalLinkTypeAttachmentMenuBot.CONSTRUCTOR: {
          TdApi.InternalLinkTypeAttachmentMenuBot link =
            (TdApi.InternalLinkTypeAttachmentMenuBot) linkType;
          openAttachmentMenuBotLink(c, bot.id, currentChatId, link);
          break;
        }
      }
    }));
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openAppLink (
    ViewController<?> context,
    TdApi.User bot,
    long chatId,
    TdApi.InternalLinkTypeWebApp link
  ) {
    final Tdlib tdlib = context.tdlib();
    tdlib.send(new TdApi.SearchWebApp(bot.id, link.webAppShortName),
      (found, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      RunnableBool open = allowWriteAccess -> {
        WebAppController.Args args = new WebAppController.Args(WebAppController.TYPE_APP_LINK,
          bot.id, chatId);
        args.webAppShortName = link.webAppShortName;
        args.startParameter = link.startParameter;
        args.allowWriteAccess = allowWriteAccess;
        args.mode = link.mode != null ? toMode(link.mode) : WebAppController.MODE_FULL_SIZE;
        launch(context, args, LAUNCH_ALERT_NONE);
      };
      TdApi.AttachmentMenuBot menuBot = tdlib.attachmentMenuBot(bot.id);
      boolean canBeAdded = ((TdApi.UserTypeBot) bot.type).canBeAddedToAttachmentMenu;
      if (canBeAdded && (menuBot == null || !menuBot.isAdded)) {
        addToAttachmentMenu(context, bot.id, found.requestWriteAccess, open);
      } else if (found.requestWriteAccess ||
        (!found.skipConfirmation && !isLaunchConfirmed(tdlib, bot.id))) {
        showLaunchAlert(context, bot.id, found.requestWriteAccess, open);
      } else {
        open.runWithBool(false);
      }
    }));
  }

  /**
   * TDLib asks to add bots that can be in the attachment menu before their main app is opened by
   * a link.
   */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openMainAppLink (
    ViewController<?> context,
    TdApi.User bot,
    long chatId,
    TdApi.InternalLinkTypeMainWebApp link
  ) {
    final Tdlib tdlib = context.tdlib();
    WebAppController.Args args = new WebAppController.Args(WebAppController.TYPE_MAIN_APP,
      bot.id, chatId);
    args.startParameter = link.startParameter;
    args.mode = link.mode != null ? toMode(link.mode) : WebAppController.MODE_FULL_SIZE;
    if (!((TdApi.UserTypeBot) bot.type).canBeAddedToAttachmentMenu) {
      launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
      return;
    }
    tdlib.send(new TdApi.GetAttachmentMenuBot(bot.id), (menuBot, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
      } else if (menuBot.isAdded) {
        launch(context, args, LAUNCH_ALERT_UNLESS_CONFIRMED);
      } else {
        addToAttachmentMenu(context, bot.id, menuBot.requestWriteAccess,
          allowWriteAccess -> launch(context, args, LAUNCH_ALERT_NONE));
      }
    }));
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openAttachmentMenuBotLink (
    ViewController<?> context,
    long botUserId,
    long currentChatId,
    TdApi.InternalLinkTypeAttachmentMenuBot link
  ) {
    final Tdlib tdlib = context.tdlib();
    tdlib.send(new TdApi.GetAttachmentMenuBot(botUserId), (bot, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      switch (link.targetChat.getConstructor()) {
        case TdApi.TargetChatChosen.CONSTRUCTOR: {
          TdApi.TargetChatTypes types = ((TdApi.TargetChatChosen) link.targetChat).types;
          pickChat(context, chat -> isChatAllowed(tdlib, chat.id, types) &&
            getAttachmentMenuBotError(tdlib, bot, chat.id) == 0,
            chat -> openAttachmentMenuBot(context, bot, chat.id, link.url));
          break;
        }
        case TdApi.TargetChatInternalLink.CONSTRUCTOR: {
          TdApi.InternalLinkType chatLink = ((TdApi.TargetChatInternalLink) link.targetChat).link;
          tdlib.ui().openInternalLinkType(context, null, chatLink, null, isOpened -> {
            if (isOpened) {
              UI.post(() -> openAttachmentMenuBotInCurrentChat(context, bot, link.url));
            }
          });
          break;
        }
        case TdApi.TargetChatCurrent.CONSTRUCTOR: {
          if (currentChatId != 0) {
            openAttachmentMenuBotInChat(context, bot, currentChatId, link.url);
          } else {
            tdlib.ui().openPrivateChat(context, botUserId, null);
          }
          break;
        }
        default:
          throw new UnsupportedOperationException(link.targetChat.toString());
      }
    }));
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openAttachmentMenuBotInCurrentChat (
    ViewController<?> context,
    TdApi.AttachmentMenuBot bot,
    @Nullable String url
  ) {
    ViewController<?> current = context.context().navigation().getCurrentStackItem();
    if (current instanceof MessagesController && !current.isDestroyed() &&
      current.tdlib() == context.tdlib()) {
      openAttachmentMenuBotInChat(current, bot, ((MessagesController) current).getChatId(), url);
    }
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openAttachmentMenuBotInChat (
    ViewController<?> context,
    TdApi.AttachmentMenuBot bot,
    long chatId,
    @Nullable String url
  ) {
    int errorRes = getAttachmentMenuBotError(context.tdlib(), bot, chatId);
    if (errorRes != 0) {
      UI.showToast(errorRes, Toast.LENGTH_SHORT);
      return;
    }
    openAttachmentMenuBot(context, bot, chatId, url);
  }

  private static @StringRes int getAttachmentMenuBotError (
    Tdlib tdlib,
    TdApi.AttachmentMenuBot bot,
    long chatId
  ) {
    TdApi.Chat chat = tdlib.chat(chatId);
    if (chat == null) {
      return R.string.vg_BotCantOpenAttachMenuUser;
    }
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR: {
        if (((TdApi.ChatTypePrivate) chat.type).userId == bot.botUserId) {
          return bot.supportsSelfChat ? 0 : R.string.vg_BotCantOpenAttachMenuSameBot;
        }
        if (tdlib.isBotChat(chat)) {
          return bot.supportsBotChats ? 0 : R.string.vg_BotCantOpenAttachMenuBot;
        }
        return bot.supportsUserChats ? 0 : R.string.vg_BotCantOpenAttachMenuUser;
      }
      case TdApi.ChatTypeBasicGroup.CONSTRUCTOR:
        return bot.supportsGroupChats ? 0 : R.string.vg_BotCantOpenAttachMenuGroup;
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR:
        if (((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
          return bot.supportsChannelChats ? 0 : R.string.vg_BotCantOpenAttachMenuChannel;
        }
        return bot.supportsGroupChats ? 0 : R.string.vg_BotCantOpenAttachMenuGroup;
      case TdApi.ChatTypeSecret.CONSTRUCTOR:
        // Mini apps send nothing to secret chats.
        return R.string.vg_BotCantOpenAttachMenuUser;
      default:
        Td.assertChatType_e562ec7d();
        throw Td.unsupported(chat.type);
    }
  }

  /** A bot of the attachment menu, opened in the given chat. */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void openAttachmentMenuBot (
    ViewController<?> context,
    TdApi.AttachmentMenuBot bot,
    long chatId,
    @Nullable String url
  ) {
    RunnableBool open = allowWriteAccess -> {
      WebAppController.Args args = new WebAppController.Args(
        WebAppController.TYPE_ATTACHMENT_MENU, bot.botUserId,
        chatId);
      args.url = url;
      launch(context, args, LAUNCH_ALERT_NONE);
    };
    if (bot.isAdded) {
      open.runWithBool(false);
    } else {
      addToAttachmentMenu(context, bot.botUserId, bot.requestWriteAccess, open);
    }
  }

  private static @Nullable ViewController<?> findController (TdlibDelegate context) {
    if (context instanceof ViewController<?>) {
      return (ViewController<?>) context;
    }
    return context.context().navigation().getCurrentStackItem();
  }

  private static long getCurrentChatId (ViewController<?> context) {
    if (context instanceof MessagesController) {
      return ((MessagesController) context).getChatId();
    }
    ViewController<?> current = context.context().navigation().getCurrentStackItem();
    return current instanceof MessagesController ? ((MessagesController) current).getChatId() : 0;
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void launch (
    ViewController<?> context,
    WebAppController.Args args,
    int launchAlert
  ) {
    final Tdlib tdlib = context.tdlib();
    WebAppController open = WebAppController.findOpen(tdlib, args);
    if (open != null) {
      if (open.isMinimized()) {
        open.restoreFromTab();
      }
      return;
    }
    boolean needLaunchAlert = launchAlert == LAUNCH_ALERT_ALWAYS ||
      (launchAlert == LAUNCH_ALERT_UNLESS_CONFIRMED && !isLaunchConfirmed(tdlib, args.botUserId));
    if (needLaunchAlert) {
      showLaunchAlert(context, args.botUserId, false, allowWriteAccess -> show(context, args));
      return;
    }
    show(context, args);
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static void show (ViewController<?> context, WebAppController.Args args) {
    WebAppController c = new WebAppController(context.context(), context.tdlib());
    c.setArguments(args);
    c.show();
  }

  // Launch confirmations

  private static String launchConfirmedKey (Tdlib tdlib, long botUserId) {
    return KEY_LAUNCH_CONFIRMED + tdlib.myUserId() + "_" + botUserId;
  }

  /** TDLib asks to show the alert about data sent to the bot once for each bot. */
  private static boolean isLaunchConfirmed (Tdlib tdlib, long botUserId) {
    return Settings.instance().pmc().getBoolean(launchConfirmedKey(tdlib, botUserId), false);
  }

  private static void showLaunchAlert (
    ViewController<?> context,
    long botUserId,
    boolean requestWriteAccess,
    RunnableBool onStart
  ) {
    final Tdlib tdlib = context.tdlib();
    final String botName = tdlib.cache().userName(botUserId);
    CharSequence message = Lang.getMarkdownString(context, R.string.vg_BotWebViewStartPermission2);
    ListItem header = new ListItem(ListItem.TYPE_INFO, R.id.text_title, 0, message, false);
    ListItem[] items;
    if (requestWriteAccess) {
      items = new ListItem[] {
        new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_webAppAllowWrite, 0,
          Lang.getStringBold(R.string.vg_OpenUrlOption2, botName), true)
      };
    } else {
      items = new ListItem[] {
        new ListItem(ListItem.TYPE_SETTING, R.id.btn_webAppMoreAboutBot, 0,
          Lang.getString(R.string.vg_MoreAboutThisBot), false)
      };
    }
    context.showSettings(new SettingsWrapBuilder(R.id.btn_webAppStart)
      .setHeadline(botName)
      .setHeaderItem(header)
      .setRawItems(items)
      .setSaveStr(R.string.vg_BotWebViewStart)
      .setOnSettingItemClick((itemView, settingsId, item, doneButton, settingsAdapter, window) -> {
        if (item.getId() == R.id.btn_webAppMoreAboutBot) {
          window.hideWindow(true);
          tdlib.ui().openPrivateProfile(context, botUserId, null);
        }
      })
      .setIntDelegate((id, result) -> {
        Settings.instance().pmc().putBoolean(launchConfirmedKey(tdlib, botUserId), true);
        onStart.runWithBool(requestWriteAccess && result.get(R.id.btn_webAppAllowWrite) != 0);
      }));
  }

  /**
   * «Terms of Use» of mini apps for bots of the attachment and side menus, then adds the bot
   * there. Write access is given only by its own checkbox.
   */
  private static void addToAttachmentMenu (
    ViewController<?> context,
    long botUserId,
    boolean requestWriteAccess,
    RunnableBool onAdded
  ) {
    final Tdlib tdlib = context.tdlib();
    CharSequence message = Lang.getMarkdownString(context, R.string.vg_BotWebAppDisclaimerSubtitle);
    ListItem agreeItem = new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_webAppAgree, 0,
      Lang.getMarkdownString(context, R.string.vg_BotWebAppDisclaimerCheck), false);
    ListItem[] items;
    if (requestWriteAccess) {
      items = new ListItem[] {
        agreeItem,
        new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_webAppAllowWrite, 0,
          Lang.getStringBold(R.string.vg_OpenUrlOption2, tdlib.cache().userName(botUserId)), true)
      };
    } else {
      items = new ListItem[] {agreeItem};
    }
    context.showSettings(new SettingsWrapBuilder(R.id.btn_webAppDisclaimer)
      .setHeadline(Lang.getString(R.string.vg_TermsOfUse))
      .setHeaderItem(new ListItem(ListItem.TYPE_INFO, R.id.text_title, 0, message, false))
      .setRawItems(items)
      .setSaveStr(R.string.Continue)
      .setIntDelegate((id, result) -> {
        if (result.get(R.id.btn_webAppAgree) == 0) {
          UI.showToast(R.string.vg_BotWebAppDisclaimerRequired, Toast.LENGTH_SHORT);
          return;
        }
        boolean allowWriteAccess = requestWriteAccess && result.get(R.id.btn_webAppAllowWrite) != 0;
        tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(botUserId, true, allowWriteAccess),
          (ok, error) -> UI.post(() -> {
            if (error != null) {
              UI.showError(error);
              return;
            }
            // The accepted terms cover the data sent to the bot on launch.
            Settings.instance().pmc().putBoolean(launchConfirmedKey(tdlib, botUserId), true);
            onAdded.runWithBool(allowWriteAccess);
          }));
      }));
  }

  static void confirmRemoveFromMenu (ViewController<?> context, long botUserId, Runnable after) {
    final Tdlib tdlib = context.tdlib();
    AlertPopup.Builder b = new AlertPopup.Builder(context.context());
    b.setTitle(Lang.getString(R.string.vg_BotRemoveFromMenuTitle));
    b.setMessage(Lang.getStringBold(R.string.vg_BotRemoveFromMenu,
      tdlib.cache().userName(botUserId)));
    b.setPositiveButton(Lang.getString(R.string.OK), (dialog, which) -> {
      tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(botUserId, false, false),
        tdlib.typedOkHandler());
      removeShortcut(tdlib, botUserId);
      after.run();
    });
    b.setPositiveButtonDestructive();
    b.setNegativeButton(Lang.getString(R.string.Cancel), null);
    context.showAlert(b);
  }

  static boolean isAttachmentMenuBot (Tdlib tdlib, long botUserId) {
    TdApi.AttachmentMenuBot bot = tdlib.attachmentMenuBot(botUserId);
    return bot != null && bot.isAdded && (bot.showInAttachmentMenu || bot.showInSideMenu);
  }

  // Parameters

  static TdApi.ThemeParameters buildThemeParameters () {
    return new TdApi.ThemeParameters(
      rgb(Theme.getColor(ColorId.filling)),
      rgb(Theme.getColor(ColorId.background)),
      rgb(Theme.getColor(ColorId.filling)),
      rgb(Theme.getColor(ColorId.background)),
      rgb(Theme.getColor(ColorId.filling)),
      rgb(Theme.getColor(ColorId.separator)),
      rgb(Theme.getColor(ColorId.text)),
      rgb(Theme.getColor(ColorId.textNeutral)),
      rgb(Theme.getColor(ColorId.textNeutral)),
      rgb(Theme.getColor(ColorId.textLight)),
      rgb(Theme.getColor(ColorId.textNegative)),
      rgb(Theme.getColor(ColorId.textLight)),
      rgb(Theme.getColor(ColorId.textLink)),
      rgb(M3.primary()),
      rgb(M3.onPrimary())
    );
  }

  private static int rgb (int color) {
    return color & 0xffffff;
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  static TdApi.WebAppOpenParameters buildOpenParameters (int mode) {
    TdApi.WebAppOpenMode openMode;
    switch (mode) {
      case WebAppController.MODE_FULLSCREEN:
        openMode = new TdApi.WebAppOpenModeFullScreen();
        break;
      case WebAppController.MODE_FULL_SIZE:
        openMode = new TdApi.WebAppOpenModeFullSize();
        break;
      case WebAppController.MODE_COMPACT:
      default:
        openMode = new TdApi.WebAppOpenModeCompact();
        break;
    }
    return new TdApi.WebAppOpenParameters(buildThemeParameters(), APPLICATION_NAME, openMode);
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  static int toMode (TdApi.WebAppOpenMode mode) {
    switch (mode.getConstructor()) {
      case TdApi.WebAppOpenModeFullScreen.CONSTRUCTOR:
        return WebAppController.MODE_FULLSCREEN;
      case TdApi.WebAppOpenModeFullSize.CONSTRUCTOR:
        return WebAppController.MODE_FULL_SIZE;
      case TdApi.WebAppOpenModeCompact.CONSTRUCTOR:
      default:
        return WebAppController.MODE_COMPACT;
    }
  }

  /**
   * Button URLs may ask for a mode with the {@code mode} query parameter, like {@code t.me}
   * links.
   */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static int parseModeFromUrl (String url, int defaultMode) {
    Uri uri = Uri.parse(url);
    String mode = uri.isHierarchical() ? uri.getQueryParameter("mode") : null;
    if ("fullscreen".equals(mode)) {
      return WebAppController.MODE_FULLSCREEN;
    }
    if ("compact".equals(mode)) {
      return WebAppController.MODE_COMPACT;
    }
    return defaultMode;
  }

  static String getInlineQueryResultId (TdApi.InlineQueryResult result) {
    switch (result.getConstructor()) {
      case TdApi.InlineQueryResultArticle.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultArticle) result).id;
      case TdApi.InlineQueryResultContact.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultContact) result).id;
      case TdApi.InlineQueryResultLocation.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultLocation) result).id;
      case TdApi.InlineQueryResultVenue.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultVenue) result).id;
      case TdApi.InlineQueryResultGame.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultGame) result).id;
      case TdApi.InlineQueryResultAnimation.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultAnimation) result).id;
      case TdApi.InlineQueryResultAudio.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultAudio) result).id;
      case TdApi.InlineQueryResultDocument.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultDocument) result).id;
      case TdApi.InlineQueryResultPhoto.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultPhoto) result).id;
      case TdApi.InlineQueryResultSticker.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultSticker) result).id;
      case TdApi.InlineQueryResultVideo.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultVideo) result).id;
      case TdApi.InlineQueryResultVoiceNote.CONSTRUCTOR:
        return ((TdApi.InlineQueryResultVoiceNote) result).id;
      default:
        Td.assertInlineQueryResult_6a8a1449();
        throw Td.unsupported(result);
    }
  }

  static boolean isChatAllowed (Tdlib tdlib, long chatId, @Nullable TdApi.TargetChatTypes types) {
    if (types == null) {
      return true;
    }
    TdApi.Chat chat = tdlib.chat(chatId);
    if (chat == null) {
      return false;
    }
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR:
      case TdApi.ChatTypeSecret.CONSTRUCTOR:
        return tdlib.isBotChat(chat) ? types.allowBotChats : types.allowUserChats;
      case TdApi.ChatTypeBasicGroup.CONSTRUCTOR:
        return types.allowGroupChats;
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR:
        return tdlib.isChannel(chatId) ? types.allowChannelChats : types.allowGroupChats;
      default:
        return false;
    }
  }

  private static void pickChat (
    ViewController<?> context,
    ChatFilter filter,
    RunnableData<TdApi.Chat> onPicked
  ) {
    ChatsController c = new ChatsController(context.context(), context.tdlib());
    c.setArguments(new ChatsController.Arguments(filter, (chat, onDone) -> {
      onPicked.runWithData(chat);
      return true;
    }));
    context.navigateTo(c);
  }

  // Requested chats and users

  /**
   * {@code web_app_request_chat}: the picked chat is shared with the bot and the page gets the
   * result; the picked chat is not opened.
   */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  static boolean openRequestedChatPicker (
    WebAppController context,
    long botUserId,
    TdApi.KeyboardButton button,
    TdApi.KeyboardButtonSource source,
    RunnableBool onResult
  ) {
    final Tdlib tdlib = context.tdlib();
    final ChatFilter filter;
    final RunnableData<TdApi.Chat> share;
    final RunnableBool reply = isShared -> {
      onResult.runWithBool(isShared);
      if (!context.isDestroyed()) {
        context.restoreFromTab();
      }
    };
    final Tdlib.ResultHandler<TdApi.Ok> handler = (ok, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
      }
      reply.runWithBool(error == null);
    });
    switch (button.type.getConstructor()) {
      case TdApi.KeyboardButtonTypeRequestUsers.CONSTRUCTOR: {
        TdApi.KeyboardButtonTypeRequestUsers request =
          (TdApi.KeyboardButtonTypeRequestUsers) button.type;
        filter = chat -> isRequestedUser(tdlib, chat, botUserId, request);
        share = chat -> tdlib.send(new TdApi.ShareUsersWithBot(source, request.id,
          new long[] {tdlib.chatUserId(chat.id)}, false), handler);
        break;
      }
      case TdApi.KeyboardButtonTypeRequestChat.CONSTRUCTOR: {
        TdApi.KeyboardButtonTypeRequestChat request =
          (TdApi.KeyboardButtonTypeRequestChat) button.type;
        filter = chat -> isRequestedChat(tdlib, chat, request);
        share = chat -> tdlib.send(new TdApi.ShareChatWithBot(source, request.id, chat.id, false),
          handler);
        break;
      }
      default:
        return false;
    }
    final boolean[] isPicked = new boolean[1];
    ChatsController c = new ChatsController(context.context(), tdlib);
    c.setArguments(new ChatsController.Arguments(filter, (chat, onDone) -> {
      if (!isPicked[0]) {
        isPicked[0] = true;
        share.runWithData(chat);
        c.navigateBack();
      }
      return false;
    }));
    c.addDestroyListener(() -> {
      if (!isPicked[0]) {
        isPicked[0] = true;
        reply.runWithBool(false);
      }
    });
    context.minimize();
    context.navigateTo(c);
    return true;
  }

  private static boolean isRequestedUser (
    Tdlib tdlib,
    TdApi.Chat chat,
    long botUserId,
    TdApi.KeyboardButtonTypeRequestUsers request
  ) {
    if (chat.type.getConstructor() != TdApi.ChatTypePrivate.CONSTRUCTOR ||
      tdlib.isSelfChat(chat) || tdlib.chatUserId(chat.id) == botUserId) {
      return false;
    }
    TdApi.User user = tdlib.chatUser(chat);
    if (user == null || user.type.getConstructor() == TdApi.UserTypeDeleted.CONSTRUCTOR) {
      return false;
    }
    boolean isBot = user.type.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR;
    return (!request.restrictUserIsBot || isBot == request.userIsBot) &&
      (!request.restrictUserIsPremium || user.isPremium == request.userIsPremium);
  }

  /** The rights of the bot and its membership are checked by TDLib when the chat is shared. */
  private static boolean isRequestedChat (
    Tdlib tdlib,
    TdApi.Chat chat,
    TdApi.KeyboardButtonTypeRequestChat request
  ) {
    boolean isChannel = tdlib.isChannelChat(chat);
    if (isChannel != request.chatIsChannel || (!isChannel && !tdlib.isMultiChat(chat))) {
      return false;
    }
    if (request.restrictChatIsForum && tdlib.isForum(chat.id) != request.chatIsForum) {
      return false;
    }
    if (request.restrictChatHasUsername &&
      StringUtils.isEmpty(tdlib.chatUsername(chat)) == request.chatHasUsername) {
      return false;
    }
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
    int statusType = status != null ? status.getConstructor() : 0;
    if (request.chatIsCreated) {
      return statusType == TdApi.ChatMemberStatusCreator.CONSTRUCTOR;
    }
    return request.userAdministratorRights == null ||
      statusType == TdApi.ChatMemberStatusCreator.CONSTRUCTOR ||
      statusType == TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR;
  }

  // QR codes

  /** The camera is shared by all mini apps: one scanner at a time. */
  private static @Nullable QrScanner activeQrScanner;

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  private static final class QrScanner implements CameraController.QrCodeListener, Destroyable {
    private final WebAppController owner;
    private final @Nullable String text;
    private final RunnableData<String> onCode;
    private final Runnable onClosed;

    QrScanner (WebAppController owner, @Nullable String text, RunnableData<String> onCode,
               Runnable onClosed) {
      this.owner = owner;
      this.text = text;
      this.onCode = onCode;
      this.onClosed = onClosed;
    }

    @Override
    public void onQrCodeScanned (String qrCode) {
      if (activeQrScanner == this) {
        onCode.runWithData(qrCode);
      }
    }

    @Override
    public boolean acceptsAnyQrCode () {
      return true;
    }

    @Override
    public @Nullable String getQrSubtitle () {
      return StringUtils.isEmpty(text) ? null : text;
    }

    @Override
    public void onQrScannerClosed () {
      if (release(true)) {
        owner.setSheetHidden(false);
        onClosed.run();
      }
    }

    /** The controller went away with the camera open. */
    @Override
    public void performDestroy () {
      if (release(false)) {
        owner.context().closeCameraByBackPress();
      }
    }

    private boolean release (boolean removeDestroyListener) {
      if (activeQrScanner != this) {
        return false;
      }
      activeQrScanner = null;
      if (removeDestroyListener) {
        owner.removeDestroyListener(this);
      }
      return true;
    }
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  static boolean openQrScanner (
    WebAppController context,
    @Nullable String text,
    RunnableData<String> onCode,
    Runnable onClosed
  ) {
    if (activeQrScanner != null) {
      return false;
    }
    QrScanner scanner = new QrScanner(context, text, onCode, onClosed);
    activeQrScanner = scanner;
    context.addDestroyListener(scanner);
    // The camera lives under the popups of the activity, so the sheet steps aside while it is open.
    context.setSheetHidden(true);
    context.openInAppCamera(new ViewController.CameraOpenOptions()
      .noTrace(true)
      .allowSystem(false)
      .mode(CameraController.MODE_QR)
      .qrCodeListener(scanner));
    return true;
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  static void closeQrScanner (WebAppController context) {
    QrScanner scanner = activeQrScanner;
    if (scanner != null && scanner.owner == context && scanner.release(true)) {
      context.setSheetHidden(false);
      context.context().closeCameraByBackPress();
    }
  }

  // Home screen

  private static String shortcutId (Tdlib tdlib, long botUserId) {
    return SHORTCUT_PREFIX + tdlib.id() + "_" + botUserId;
  }

  /** Random per installation: shortcut intents without it may come from other apps. */
  private static String getShortcutToken () {
    String token = Settings.instance().pmc().getString(KEY_SHORTCUT_TOKEN, null);
    if (token == null) {
      token = UUID.randomUUID().toString();
      Settings.instance().pmc().putString(KEY_SHORTCUT_TOKEN, token);
    }
    return token;
  }

  public static boolean isShortcutTokenValid (@Nullable String token) {
    return token != null &&
      token.equals(Settings.instance().pmc().getString(KEY_SHORTCUT_TOKEN, null));
  }

  static String getHomeScreenStatus (Tdlib tdlib, long botUserId) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(UI.getContext())) {
      return "unsupported";
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      // Shortcuts pinned through the launcher broadcast can't be queried.
      return "unknown";
    }
    String id = shortcutId(tdlib, botUserId);
    for (ShortcutInfoCompat info : ShortcutManagerCompat.getShortcuts(UI.getContext(),
      ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
      if (info.getId().equals(id)) {
        return "added";
      }
    }
    return "missed";
  }

  static void addToHomeScreen (ViewController<?> context, long botUserId, RunnableBool onResult) {
    final Tdlib tdlib = context.tdlib();
    if ("added".equals(getHomeScreenStatus(tdlib, botUserId))) {
      onResult.runWithBool(true);
      return;
    }
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(UI.getContext())) {
      onResult.runWithBool(false);
      return;
    }
    final long botChatId = ChatId.fromUserId(botUserId);
    final TdApi.Chat botChat = tdlib.chat(botChatId);
    Background.instance().post(() -> {
      Bitmap bitmap = botChat != null ?
        TdlibNotificationUtils.buildLargeIcon(tdlib, botChat, true) : null;
      UI.post(() -> {
        Intent intent = new Intent(UI.getContext(), MainActivity.class);
        intent.setAction(Intents.ACTION_OPEN_WEB_APP);
        intent.putExtra(EXTRA_ACCOUNT_ID, tdlib.id());
        intent.putExtra(EXTRA_BOT_USER_ID, botUserId);
        intent.putExtra(EXTRA_SHORTCUT_TOKEN, getShortcutToken());
        ShortcutInfoCompat.Builder b =
          new ShortcutInfoCompat.Builder(UI.getContext(), shortcutId(tdlib, botUserId))
          .setShortLabel(tdlib.cache().userName(botUserId))
          .setIntent(intent);
        if (U.isValidBitmap(bitmap)) {
          b.setIcon(IconCompat.createWithBitmap(bitmap));
        } else {
          b.setIcon(IconCompat.createWithResource(UI.getContext(), R.mipmap.app_launcher));
        }
        boolean requested;
        try {
          requested = ShortcutManagerCompat.requestPinShortcut(UI.getContext(), b.build(), null);
        } catch (IllegalStateException e) {
          // The app is not in the foreground anymore.
          requested = false;
        }
        onResult.runWithBool(requested);
      });
    });
  }

  private static void removeShortcut (Tdlib tdlib, long botUserId) {
    ShortcutManagerCompat.disableShortcuts(UI.getContext(),
      Collections.singletonList(shortcutId(tdlib, botUserId)), null);
  }

  /**
   * Opens the main app of the bot of a home screen shortcut, see {@link #addToHomeScreen}.
   * Intents without the token of the app's shortcuts always get the launch confirmation.
   */
  public static void openFromShortcut (ViewController<?> context, long botUserId,
                                       boolean isTrusted) {
    if (!checkSupported()) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(WebAppController.TYPE_MAIN_APP,
      botUserId, 0);
    args.mode = WebAppController.MODE_FULL_SIZE;
    launch(context, args, isTrusted ? LAUNCH_ALERT_UNLESS_CONFIRMED : LAUNCH_ALERT_ALWAYS);
  }

  // WebView prompts

  static void showPrompt (
    ViewController<?> context,
    String title,
    String message,
    @Nullable String defaultValue,
    JsPromptResult result
  ) {
    final MaterialEditText input = new MaterialEditText(context.context(), context.tdlib());
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    if (defaultValue != null) {
      input.setText(defaultValue);
    }
    final boolean[] confirmed = new boolean[1];
    AlertPopup.Builder b = new AlertPopup.Builder(context.context());
    b.setTitle(title);
    b.setMessage(message);
    b.setView(input);
    b.setPositiveButton(Lang.getString(R.string.OK), (dialog, which) -> confirmed[0] = true);
    b.setNegativeButton(Lang.getString(R.string.Cancel), null);
    b.setOnDismissListener(dialog -> {
      if (confirmed[0]) {
        result.confirm(input.getText().toString());
      } else {
        result.cancel();
      }
    });
    if (context.showAlert(b) == null) {
      result.cancel();
    }
  }

  /** {@code request} is an {@code android.webkit.PermissionRequest}, which API 16 can't load. */
  static void onPermissionRequest (ViewController<?> context, String botName, Object request) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      WebAppPermissions.onPermissionRequest(context, botName,
        (android.webkit.PermissionRequest) request);
    }
  }

  static void onGeolocationPermissionRequest (
    ViewController<?> context,
    String botName,
    String origin,
    GeolocationPermissions.Callback callback
  ) {
    askPermission(context, botName, R.string.vg_BotWebViewRequestGeolocationPermission,
      new String[] {
      Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
    }, granted -> callback.invoke(origin, granted, false));
  }

  static void askPermission (
    ViewController<?> context,
    String botName,
    int textRes,
    String[] permissions,
    RunnableBool onResult
  ) {
    final boolean[] allowed = new boolean[1];
    AlertPopup.Builder b = new AlertPopup.Builder(context.context());
    b.setTitle(botName);
    b.setMessage(Lang.getStringBold(textRes, botName));
    b.setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> allowed[0] = true);
    b.setNegativeButton(Lang.getString(R.string.Cancel), null);
    b.setOnDismissListener(dialog -> {
      if (!allowed[0]) {
        onResult.runWithBool(false);
        return;
      }
      BaseActivity activity = context.context();
      if (activity.permissions().checkPermissions(permissions) == Permissions.GrantResult.ALL) {
        onResult.runWithBool(true);
        return;
      }
      activity.requestCustomPermissions(permissions, (code, requested, grantResults, grantCount) ->
        onResult.runWithBool(grantCount == requested.length));
    });
    if (context.showAlert(b) == null) {
      onResult.runWithBool(false);
    }
  }
}
