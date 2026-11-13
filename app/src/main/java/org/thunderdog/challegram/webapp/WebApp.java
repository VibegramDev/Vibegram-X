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
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.PermissionRequest;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibDelegate;
import org.thunderdog.challegram.telegram.TdlibNotificationUtils;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ChatsController;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.ui.camera.CameraController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.Permissions;
import org.thunderdog.challegram.widget.MaterialEditText;

import me.vkryl.core.StringUtils;
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

  public static final String ACTION_OPEN_WEB_APP = BuildConfig.APPLICATION_ID + ".OPEN_WEB_APP";
  public static final String EXTRA_ACCOUNT_ID = "account_id", EXTRA_BOT_USER_ID = "bot_user_id";

  private static final String APPLICATION_NAME = "android";
  private static final String KEY_LAUNCH_CONFIRMED = "webapp_launch_confirmed_";
  private static final String SHORTCUT_PREFIX = "webapp_";

  // Entry points

  /** {@code inlineKeyboardButtonTypeWebApp} of a message. */
  public static void openInlineButton (
    ViewController<?> context,
    TdApi.Message message,
    String url
  ) {
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
    launch(context, args, true);
  }

  /** {@code keyboardButtonTypeWebApp} of a reply keyboard: the app may send data back once. */
  public static void openKeyboardButton (
    ViewController<?> context,
    long chatId,
    long botUserId,
    String buttonText,
    String url
  ) {
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_KEYBOARD_BUTTON, botUserId,
      chatId);
    args.url = url;
    args.buttonText = buttonText;
    args.mode = parseModeFromUrl(url, WebAppController.MODE_COMPACT);
    launch(context, args, true);
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
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_MENU_BUTTON, botUserId,
      ChatId.fromUserId(botUserId));
    args.url = button.url;
    args.needsContext = true;
    args.mode = parseModeFromUrl(button.url, WebAppController.MODE_COMPACT);
    launch(context, args, true);
  }

  /** The «switch to web app» button above inline results. */
  public static void openInlineSwitch (
    ViewController<?> context,
    long chatId,
    long botUserId,
    String url
  ) {
    WebAppController.Args args = new WebAppController.Args(
      WebAppController.TYPE_INLINE_SWITCH, botUserId,
      chatId);
    args.url = url;
    args.needsContext = true;
    args.mode = parseModeFromUrl(url, WebAppController.MODE_COMPACT);
    launch(context, args, true);
  }

  /**
   * The main mini app of a bot: the profile button, the «Open» button of the chat list and
   * links.
   */
  public static void openMainApp (
    TdlibDelegate context,
    long botUserId,
    long chatId,
    @Nullable String startParameter,
    @Nullable TdApi.WebAppOpenMode mode
  ) {
    ViewController<?> c = findController(context);
    if (c == null) {
      return;
    }
    WebAppController.Args args = new WebAppController.Args(WebAppController.TYPE_MAIN_APP,
      botUserId, chatId);
    args.startParameter = startParameter;
    args.mode = mode != null ? toMode(mode) : WebAppController.MODE_FULL_SIZE;
    launch(c, args, false);
  }

  /** A bot of the attachment menu, opened in the given chat. */
  public static void openAttachmentMenuBot (
    ViewController<?> context,
    long chatId,
    long botUserId,
    @Nullable String url,
    @Nullable TdApi.MessageTopic topicId,
    @Nullable TdApi.InputMessageReplyTo replyTo
  ) {
    Tdlib tdlib = context.tdlib();
    TdApi.AttachmentMenuBot bot = tdlib.attachmentMenuBot(botUserId);
    Runnable open = () -> {
      WebAppController.Args args = new WebAppController.Args(
        WebAppController.TYPE_ATTACHMENT_MENU, botUserId,
        chatId);
      args.url = url;
      args.topicId = topicId;
      args.replyTo = replyTo;
      launch(context, args, false);
    };
    if (bot == null || !bot.isAdded) {
      showDisclaimer(context, () -> tdlib.send(
        new TdApi.ToggleBotIsAddedToAttachmentMenu(botUserId, true, true),
        (ok, error) -> UI.post(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            open.run();
          }
        })));
    } else {
      open.run();
    }
  }

  /**
   * {@code internalLinkTypeWebApp}, {@code internalLinkTypeMainWebApp} or
   * {@code internalLinkTypeAttachmentMenuBot}.
   */
  public static void openLink (TdlibDelegate context, TdApi.InternalLinkType linkType) {
    final ViewController<?> c = findController(context);
    if (c == null) {
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
        return;
    }
    tdlib.send(new TdApi.SearchPublicChat(botUsername), (chat, error) -> UI.post(() -> {
      long botUserId = chat != null ? tdlib.chatUserId(chat.id) : 0;
      TdApi.User bot = botUserId != 0 ? tdlib.cache().user(botUserId) : null;
      if (error != null || bot == null ||
        bot.type.getConstructor() != TdApi.UserTypeBot.CONSTRUCTOR) {
        if (error != null) {
          UI.showError(error);
        }
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
            openMainApp(c, bot.id, currentChatId, link.startParameter, link.mode);
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
        launch(context, args, false);
      };
      TdApi.AttachmentMenuBot menuBot = tdlib.attachmentMenuBot(bot.id);
      boolean canBeAdded = ((TdApi.UserTypeBot) bot.type).canBeAddedToAttachmentMenu;
      if (canBeAdded && (menuBot == null || !menuBot.isAdded)) {
        showDisclaimer(context, () -> tdlib.send(
          new TdApi.ToggleBotIsAddedToAttachmentMenu(bot.id, true, true),
          (ok, toggleError) -> UI.post(() -> open.runWithBool(true))));
      } else if (found.requestWriteAccess ||
        (!found.skipConfirmation && !isLaunchConfirmed(tdlib, bot.id))) {
        showLaunchAlert(context, bot.id, found.requestWriteAccess, open);
      } else {
        open.runWithBool(false);
      }
    }));
  }

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
          pickChat(context, types, chat ->
            openAttachmentMenuBot(context, chat.id, botUserId, link.url, null, null));
          break;
        }
        case TdApi.TargetChatCurrent.CONSTRUCTOR:
        case TdApi.TargetChatInternalLink.CONSTRUCTOR:
        default: {
          if (currentChatId != 0) {
            openAttachmentMenuBot(context, currentChatId, botUserId, link.url, null, null);
          } else {
            tdlib.ui().openPrivateChat(context, botUserId, null);
          }
          break;
        }
      }
    }));
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

  private static void launch (
    ViewController<?> context,
    WebAppController.Args args,
    boolean needLaunchAlert
  ) {
    final Tdlib tdlib = context.tdlib();
    WebAppController open = WebAppController.findOpen(tdlib, args);
    if (open != null) {
      if (open.isMinimized()) {
        open.restoreFromTab();
      }
      return;
    }
    if (needLaunchAlert && !isLaunchConfirmed(tdlib, args.botUserId)) {
      showLaunchAlert(context, args.botUserId, false, allowWriteAccess -> show(context, args));
      return;
    }
    show(context, args);
  }

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
   * «Terms of Use» of mini apps for bots of the attachment and side menus, before they are
   * added.
   */
  private static void showDisclaimer (ViewController<?> context, Runnable onAccept) {
    CharSequence message = Lang.getMarkdownString(context, R.string.vg_BotWebAppDisclaimerSubtitle);
    context.showSettings(new SettingsWrapBuilder(R.id.btn_webAppDisclaimer)
      .setHeadline(Lang.getString(R.string.vg_TermsOfUse))
      .setHeaderItem(new ListItem(ListItem.TYPE_INFO, R.id.text_title, 0, message, false))
      .setRawItems(new ListItem[] {
        new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_webAppAgree, 0,
          Lang.getMarkdownString(context, R.string.vg_BotWebAppDisclaimerCheck), false)
      })
      .setSaveStr(R.string.Continue)
      .setIntDelegate((id, result) -> {
        if (result.get(R.id.btn_webAppAgree) != 0) {
          onAccept.run();
        } else {
          UI.showToast(R.string.vg_BotWebAppDisclaimerRequired, Toast.LENGTH_SHORT);
        }
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
      removeShortcut(botUserId);
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
  private static int parseModeFromUrl (String url, int defaultMode) {
    try {
      String mode = Uri.parse(url).getQueryParameter("mode");
      if ("fullscreen".equals(mode)) {
        return WebAppController.MODE_FULLSCREEN;
      }
      if ("compact".equals(mode)) {
        return WebAppController.MODE_COMPACT;
      }
    } catch (Throwable ignored) {
      // Not a hierarchical URI: no query parameters.
    }
    return defaultMode;
  }

  static String formatDuration (int seconds) {
    int days = seconds / 86400, hours = seconds % 86400 / 3600, minutes = seconds % 3600 / 60;
    StringBuilder b = new StringBuilder();
    if (days > 0) {
      b.append(Lang.plural(R.string.vg_BotEmojiStatusSetRequestForDay, days));
    }
    if (hours > 0) {
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(Lang.plural(R.string.vg_BotEmojiStatusSetRequestForHour, hours));
    }
    if (minutes > 0 || b.length() == 0) {
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(Lang.plural(R.string.vg_BotEmojiStatusSetRequestForMinute, Math.max(1, minutes)));
    }
    return b.toString();
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
    @Nullable TdApi.TargetChatTypes types,
    RunnableData<TdApi.Chat> onPicked
  ) {
    final Tdlib tdlib = context.tdlib();
    ChatsController c = new ChatsController(context.context(), tdlib);
    c.setArguments(new ChatsController.Arguments(new ChatsController.PickerDelegate() {
      @Override
      public boolean onChatPicked (TdApi.Chat chat, Runnable onDone) {
        if (!isChatAllowed(tdlib, chat.id, types)) {
          UI.showToast(R.string.vg_BotCantSharePreparedMessage, Toast.LENGTH_SHORT);
          return false;
        }
        onPicked.runWithData(chat);
        return true;
      }
    }));
    context.navigateTo(c);
  }

  // Requested chats and users

  static boolean openRequestedChatPicker (
    WebAppController context,
    long botUserId,
    TdApi.KeyboardButton button,
    TdApi.KeyboardButtonSource source,
    RunnableBool onResult
  ) {
    final Tdlib tdlib = context.tdlib();
    switch (button.type.getConstructor()) {
      case TdApi.KeyboardButtonTypeRequestUsers.CONSTRUCTOR: {
        TdApi.KeyboardButtonTypeRequestUsers request =
          (TdApi.KeyboardButtonTypeRequestUsers) button.type;
        context.minimize();
        pickChat(context, new TdApi.TargetChatTypes(true,
          request.restrictUserIsBot ? request.userIsBot : true,
          false, false), chat -> {
          long userId = tdlib.chatUserId(chat.id);
          tdlib.send(new TdApi.ShareUsersWithBot(source, request.id, new long[] {userId}, false),
            (ok, error) ->
            UI.post(() -> {
              if (error != null) {
                UI.showError(error);
              }
              onResult.runWithBool(error == null);
              context.restoreFromTab();
            }));
        });
        return true;
      }
      case TdApi.KeyboardButtonTypeRequestChat.CONSTRUCTOR: {
        TdApi.KeyboardButtonTypeRequestChat request =
          (TdApi.KeyboardButtonTypeRequestChat) button.type;
        context.minimize();
        pickChat(context,
          new TdApi.TargetChatTypes(false, false, !request.chatIsChannel, request.chatIsChannel),
          chat -> tdlib.send(new TdApi.ShareChatWithBot(source, request.id, chat.id, false),
            (ok, error) ->
            UI.post(() -> {
              if (error != null) {
                UI.showError(error);
              }
              onResult.runWithBool(error == null);
              context.restoreFromTab();
            })));
        return true;
      }
      default:
        return false;
    }
  }

  // QR codes

  private static @Nullable CameraController.QrCodeListener activeQrListener;

  static boolean openQrScanner (
    WebAppController context,
    @Nullable String text,
    RunnableData<String> onCode,
    Runnable onClosed
  ) {
    if (activeQrListener != null) {
      return false;
    }
    CameraController.QrCodeListener listener = new CameraController.QrCodeListener() {
      @Override
      public void onQrCodeScanned (String qrCode) {
        onCode.runWithData(qrCode);
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
        if (activeQrListener == this) {
          activeQrListener = null;
          context.setSheetHidden(false);
          onClosed.run();
        }
      }
    };
    activeQrListener = listener;
    // The camera lives under the popups of the activity, so the sheet steps aside while it is open.
    context.setSheetHidden(true);
    context.openInAppCamera(new ViewController.CameraOpenOptions()
      .noTrace(true)
      .allowSystem(false)
      .mode(CameraController.MODE_QR)
      .qrCodeListener(listener));
    return true;
  }

  static void closeQrScanner (WebAppController context) {
    if (activeQrListener != null) {
      activeQrListener = null;
      context.setSheetHidden(false);
      context.context().performBackPress(false);
    }
  }

  // Home screen

  private static String shortcutId (long botUserId) {
    return SHORTCUT_PREFIX + botUserId;
  }

  static String getHomeScreenStatus (Tdlib tdlib, long botUserId) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return "unsupported";
    }
    ShortcutManager manager = UI.getContext().getSystemService(ShortcutManager.class);
    if (manager == null || !manager.isRequestPinShortcutSupported()) {
      return "unsupported";
    }
    for (ShortcutInfo info : manager.getPinnedShortcuts()) {
      if (info.getId().equals(shortcutId(botUserId))) {
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
      intent.setAction(ACTION_OPEN_WEB_APP);
      intent.putExtra(EXTRA_ACCOUNT_ID, tdlib.id());
      intent.putExtra(EXTRA_BOT_USER_ID, botUserId);
      ShortcutInfoCompat.Builder b =
        new ShortcutInfoCompat.Builder(UI.getContext(), shortcutId(botUserId))
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
      } catch (Throwable t) {
        requested = false;
      }
      onResult.runWithBool(requested);
      });
    });
  }

  private static void removeShortcut (long botUserId) {
    try {
      ShortcutManagerCompat.disableShortcuts(UI.getContext(),
        java.util.Collections.singletonList(shortcutId(botUserId)), null);
    } catch (Throwable ignored) {
      // Launchers without pinned shortcuts.
    }
  }

  /** Opens the main app of the bot of a home screen shortcut, see {@link #addToHomeScreen}. */
  public static void openFromShortcut (
    @Nullable ViewController<?> context,
    int accountId,
    long botUserId
  ) {
    if (context != null && context.tdlib().id() == accountId && botUserId != 0) {
      openMainApp(context, botUserId, 0, null, null);
    }
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

  static void onPermissionRequest (
    ViewController<?> context,
    String botName,
    PermissionRequest request
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return;
    }
    final String[] resources = request.getResources();
    boolean audio = false, video = false, other = false;
    for (String resource : resources) {
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
        audio = true;
      } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
        video = true;
      } else {
        other = true;
      }
    }
    if (other || (!audio && !video)) {
      request.deny();
      return;
    }
    final String[] permissions;
    final int textRes;
    if (audio && video) {
      permissions = new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
      textRes = R.string.vg_BotWebViewRequestCameraMicPermission;
    } else if (video) {
      permissions = new String[] {Manifest.permission.CAMERA};
      textRes = R.string.vg_BotWebViewRequestCameraPermission;
    } else {
      permissions = new String[] {Manifest.permission.RECORD_AUDIO};
      textRes = R.string.vg_BotWebViewRequestMicrophonePermission;
    }
    askPermission(context, botName, textRes, permissions, granted -> {
      if (granted) {
        request.grant(resources);
      } else {
        request.deny();
      }
    });
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

  private static void askPermission (
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
