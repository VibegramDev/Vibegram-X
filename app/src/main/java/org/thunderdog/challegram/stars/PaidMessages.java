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
package org.thunderdog.challegram.stars;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.ui.ListItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import me.vkryl.core.lambda.RunnableBool;
import me.vkryl.core.lambda.RunnableLong;

/**
 * Paid messages, as in the official app: users (and groups) may charge Telegram Stars for every message sent to them.
 *
 * Before such messages are sent, {@link #confirm} asks to pay for them — the «Confirm Payment» dialog of the
 * official app, then the second confirmation of Vibegram X ({@link StarsUi#confirmAgain}) — and puts the paid Stars
 * into the send options. Messages that still fail because the price has changed are resent with payment by
 * {@link #resend}.
 */
public final class PaidMessages {
  private PaidMessages () { }

  // Price

  /**
   * Stars per message the current user must pay to send messages to the chat, as far as it's known locally;
   * 0 if the messages are free.
   */
  public static long knownOutgoingPrice (@NonNull Tdlib tdlib, long chatId) {
    TdApi.Chat chat = tdlib.chat(chatId);
    if (chat == null) {
      return 0;
    }
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR: {
        long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
        if (tdlib.isSelfUserId(userId)) {
          return 0;
        }
        TdApi.UserFullInfo userFull = tdlib.cache().userFull(userId, false);
        if (userFull != null) {
          return userFull.outgoingPaidMessageStarCount;
        }
        return 0;
      }
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR: {
        TdApi.ChatTypeSupergroup type = (TdApi.ChatTypeSupergroup) chat.type;
        if (type.isChannel) {
          return 0;
        }
        TdApi.SupergroupFullInfo supergroupFull = tdlib.cache().supergroupFull(type.supergroupId, false);
        if (supergroupFull != null) {
          return supergroupFull.outgoingPaidMessageStarCount;
        }
        TdApi.Supergroup supergroup = tdlib.cache().supergroup(type.supergroupId);
        if (supergroup != null && !TD.isAdmin(supergroup.status)) {
          return supergroup.paidMessageStarCount;
        }
        return 0;
      }
    }
    return 0;
  }

  /**
   * Price of a message to the chat. When the full information about the user isn't loaded but the user charges
   * for messages in general, asks TDLib whether the current user must pay (contacts and chats started by the user
   * are free).
   */
  public static void resolveOutgoingPrice (@NonNull Tdlib tdlib, long chatId, @NonNull RunnableLong callback) {
    long knownPrice = knownOutgoingPrice(tdlib, chatId);
    if (knownPrice > 0) {
      callback.runWithLong(knownPrice);
      return;
    }
    long userId = tdlib.chatUserId(chatId);
    if (userId != 0 && !tdlib.isSelfUserId(userId) && tdlib.cache().userFull(userId, false) == null) {
      TdApi.User user = tdlib.cache().user(userId);
      if (user != null && user.paidMessageStarCount > 0) {
        tdlib.send(new TdApi.CanSendMessageToUser(userId, false), (result, error) -> {
          long price = 0;
          if (result != null && result.getConstructor() == TdApi.CanSendMessageToUserResultUserHasPaidMessages.CONSTRUCTOR) {
            price = ((TdApi.CanSendMessageToUserResultUserHasPaidMessages) result).outgoingPaidMessageStarCount;
          }
          final long finalPrice = price;
          UI.post(() -> callback.runWithLong(finalPrice));
        });
        return;
      }
    }
    callback.runWithLong(0);
  }

  /** Text of the message input in a chat that charges for messages: «Message for ⭐10». */
  public static @Nullable String inputPlaceholder (@NonNull Tdlib tdlib, long chatId) {
    long price = knownOutgoingPrice(tdlib, chatId);
    return price > 0 ? Lang.getString(R.string.vg_StarsTypeMessageFor, Stars.format(price)) : null;
  }

  // Send functions

  public static long chatIdOf (@NonNull TdApi.Function<?> function) {
    switch (function.getConstructor()) {
      case TdApi.SendMessage.CONSTRUCTOR:
        return ((TdApi.SendMessage) function).chatId;
      case TdApi.SendMessageAlbum.CONSTRUCTOR:
        return ((TdApi.SendMessageAlbum) function).chatId;
      case TdApi.ForwardMessages.CONSTRUCTOR:
        return ((TdApi.ForwardMessages) function).chatId;
      case TdApi.SendInlineQueryResultMessage.CONSTRUCTOR:
        return ((TdApi.SendInlineQueryResultMessage) function).chatId;
    }
    return 0;
  }

  public static int messageCountOf (@NonNull TdApi.Function<?> function) {
    switch (function.getConstructor()) {
      case TdApi.SendMessage.CONSTRUCTOR:
      case TdApi.SendInlineQueryResultMessage.CONSTRUCTOR:
        return 1;
      case TdApi.SendMessageAlbum.CONSTRUCTOR:
        return ((TdApi.SendMessageAlbum) function).inputMessageContents.length;
      case TdApi.ForwardMessages.CONSTRUCTOR:
        return ((TdApi.ForwardMessages) function).messageIds.length;
    }
    return 0;
  }

  private static @Nullable TdApi.MessageSendOptions optionsOf (@NonNull TdApi.Function<?> function) {
    switch (function.getConstructor()) {
      case TdApi.SendMessage.CONSTRUCTOR:
        return ((TdApi.SendMessage) function).options;
      case TdApi.SendMessageAlbum.CONSTRUCTOR:
        return ((TdApi.SendMessageAlbum) function).options;
      case TdApi.ForwardMessages.CONSTRUCTOR:
        return ((TdApi.ForwardMessages) function).options;
      case TdApi.SendInlineQueryResultMessage.CONSTRUCTOR:
        return ((TdApi.SendInlineQueryResultMessage) function).options;
    }
    return null;
  }

  /** Puts the paid Stars into the function's own copy of its send options (functions of one send share them). */
  private static void applyPaidStarCount (@NonNull TdApi.Function<?> function, long starCount) {
    TdApi.MessageSendOptions o = optionsOf(function);
    TdApi.MessageSendOptions copy = o != null ?
      new TdApi.MessageSendOptions(o.suggestedPostInfo, o.disableNotification, o.fromBackground, o.protectContent, o.allowPaidBroadcast, starCount, o.updateOrderOfInstalledStickerSets, o.schedulingState, o.effectId, o.sendingId, o.onlyPreview) :
      new TdApi.MessageSendOptions(null, false, false, false, false, starCount, false, null, 0, 0, false);
    switch (function.getConstructor()) {
      case TdApi.SendMessage.CONSTRUCTOR:
        ((TdApi.SendMessage) function).options = copy;
        break;
      case TdApi.SendMessageAlbum.CONSTRUCTOR:
        ((TdApi.SendMessageAlbum) function).options = copy;
        break;
      case TdApi.ForwardMessages.CONSTRUCTOR:
        ((TdApi.ForwardMessages) function).options = copy;
        break;
      case TdApi.SendInlineQueryResultMessage.CONSTRUCTOR:
        ((TdApi.SendInlineQueryResultMessage) function).options = copy;
        break;
    }
  }

  // Confirmation

  private static final class ChatPayment {
    final long chatId;
    long price;
    int messageCount;
    final List<TdApi.Function<?>> functions = new ArrayList<>();

    ChatPayment (long chatId) {
      this.chatId = chatId;
    }

    long total () {
      return price * messageCount;
    }
  }

  /**
   * Gate before sending: when some of the functions send messages to chats that charge for them, asks the user to
   * pay and puts the Stars into the send options. {@code after} receives true when the functions may be sent
   * (immediately, when nothing has to be paid), false when the user declined.
   */
  public static void confirm (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull List<? extends TdApi.Function<?>> functions, @NonNull RunnableBool after) {
    if (!UI.inUiThread()) {
      UI.post(() -> confirm(context, tdlib, functions, after));
      return;
    }
    // Bots don't charge for messages
    if (tdlib.sendInNewBotForumTopic(functions, () -> after.runWithBool(true))) {
      return;
    }
    LinkedHashMap<Long, ChatPayment> chats = new LinkedHashMap<>();
    for (TdApi.Function<?> function : functions) {
      long chatId = chatIdOf(function);
      int count = messageCountOf(function);
      if (chatId == 0 || count == 0) {
        continue;
      }
      ChatPayment payment = chats.get(chatId);
      if (payment == null) {
        payment = new ChatPayment(chatId);
        chats.put(chatId, payment);
      }
      payment.messageCount += count;
      payment.functions.add(function);
    }
    if (chats.isEmpty()) {
      after.runWithBool(true);
      return;
    }
    // Fast path: nothing is paid as far as it's known, and nothing has to be asked.
    boolean needResolve = false;
    for (ChatPayment payment : chats.values()) {
      payment.price = knownOutgoingPrice(tdlib, payment.chatId);
      if (payment.price == 0) {
        long userId = tdlib.chatUserId(payment.chatId);
        TdApi.User user = userId != 0 ? tdlib.cache().user(userId) : null;
        if (user != null && user.paidMessageStarCount > 0 && tdlib.cache().userFull(userId, false) == null) {
          needResolve = true;
        }
      }
    }
    if (!needResolve) {
      confirmImpl(context, tdlib, chats, after);
      return;
    }
    AtomicInteger remaining = new AtomicInteger(chats.size());
    for (ChatPayment payment : chats.values()) {
      resolveOutgoingPrice(tdlib, payment.chatId, price -> {
        payment.price = price;
        if (remaining.decrementAndGet() == 0) {
          confirmImpl(context, tdlib, chats, after);
        }
      });
    }
  }

  private static void confirmImpl (ViewController<?> context, Tdlib tdlib, Map<Long, ChatPayment> chats, RunnableBool after) {
    List<ChatPayment> paid = new ArrayList<>();
    long total = 0;
    int messageCount = 0;
    for (ChatPayment payment : chats.values()) {
      if (payment.price > 0) {
        paid.add(payment);
        total += payment.total();
        messageCount += payment.messageCount;
      }
    }
    if (paid.isEmpty()) {
      after.runWithBool(true);
      return;
    }
    if (context.isDestroyed()) {
      after.runWithBool(false);
      return;
    }

    final long totalStars = total;
    final int totalMessages = messageCount;
    CharSequence message;
    CharSequence recipient;
    if (paid.size() == 1) {
      ChatPayment payment = paid.get(0);
      String name = tdlib.chatTitle(payment.chatId);
      recipient = name;
      CharSequence line1 = Lang.plural(R.string.vg_StarsPaidMessageCharges, payment.price, name);
      CharSequence line2 = payment.messageCount == 1 ?
        Lang.plural(R.string.vg_StarsPaidMessagePayOne, payment.total()) :
        payForMessages(payment.total(), payment.messageCount);
      message = line1 + "\n\n" + line2;
    } else {
      recipient = Lang.plural(R.string.vg_StarsPaidMessageChats, paid.size());
      CharSequence line1 = Lang.plural(R.string.vg_StarsPaidMessageMultiChats, paid.size());
      CharSequence line2 = payForMessages(totalStars, totalMessages);
      message = line1 + "\n\n" + line2;
    }
    final CharSequence finalRecipient = recipient;
    StarsUi.confirm(context,
      Lang.getString(R.string.vg_StarsPaidMessageConfirmTitle),
      message,
      Lang.plural(R.string.vg_StarsPaidMessagePayButton, totalMessages),
      0, false,
      confirmed -> {
        if (!confirmed) {
          after.runWithBool(false);
          return;
        }
        StarsUi.confirmAgain(context, Lang.getString(R.string.vg_StarsConfirmAgainPay, Stars.format(totalStars), finalRecipient), Lang.getString(R.string.vg_StarsPay), confirmedAgain -> {
          if (!confirmedAgain) {
            after.runWithBool(false);
            return;
          }
          if (!StarsUi.checkBalance(context, tdlib, totalStars, finalRecipient.toString(), true)) {
            after.runWithBool(false);
            return;
          }
          for (ChatPayment payment : paid) {
            for (TdApi.Function<?> function : payment.functions) {
              applyPaidStarCount(function, payment.price * messageCountOf(function));
            }
          }
          after.runWithBool(true);
        });
      }
    );
  }

  /** Whether messages to the chat may have to be paid for (known price or a user who charges in general). */
  public static boolean mayBePaid (@NonNull Tdlib tdlib, long chatId) {
    if (knownOutgoingPrice(tdlib, chatId) > 0) {
      return true;
    }
    long userId = tdlib.chatUserId(chatId);
    if (userId != 0 && !tdlib.isSelfUserId(userId) && tdlib.cache().userFull(userId, false) == null) {
      TdApi.User user = tdlib.cache().user(userId);
      return user != null && user.paidMessageStarCount > 0;
    }
    return false;
  }

  /**
   * Sends a message function from any place of the app (stickers, voice messages, polls, inline results…): when the
   * chat charges for messages, the payment is confirmed on the current screen first. Without a screen (a reply from a
   * notification) the message is sent as is, and TDLib marks it failed until it's resent with payment. When the user
   * declines, the handler receives the error of an aborted request, which isn't shown to the user.
   */
  public static void sendWithConfirmation (@NonNull Tdlib tdlib, @NonNull TdApi.Function<?> function, @NonNull org.drinkless.tdlib.Client.ResultHandler handler) {
    if (tdlib.sendInNewBotForumTopic(Collections.singletonList(function),
      () -> tdlib.client().send(function, handler))) {
      return;
    }
    long chatId = chatIdOf(function);
    TdApi.MessageSendOptions options = optionsOf(function);
    if (chatId == 0 || (options != null && options.paidMessageStarCount > 0) || !mayBePaid(tdlib, chatId)) {
      tdlib.client().send(function, handler);
      return;
    }
    UI.post(() -> {
      org.thunderdog.challegram.BaseActivity activity = UI.getUiContext();
      ViewController<?> context = activity != null && UI.isResumed() ? activity.navigation().getCurrentStackItem() : null;
      if (context == null || context.isDestroyed() || context.tdlib() != tdlib) {
        tdlib.client().send(function, handler);
        return;
      }
      confirm(context, tdlib, java.util.Collections.singletonList(function), confirmed -> {
        if (confirmed) {
          tdlib.client().send(function, handler);
        } else {
          handler.onResult(new TdApi.Error(500, "Request aborted"));
        }
      });
    });
  }

  /** «Would you like to pay **N** Stars to send M messages?» (two plurals, as in the official app). */
  private static String payForMessages (long starCount, int messageCount) {
    return Lang.plural(R.string.vg_StarsPaidMessagePayMany, starCount) + " " + Lang.plural(R.string.vg_StarsPaidMessageCount, messageCount);
  }

  /** Sends the functions after {@link #confirm}; nothing is sent when the user declines. */
  public static void send (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull List<? extends TdApi.Function<?>> functions) {
    confirm(context, tdlib, functions, confirmed -> {
      if (confirmed) {
        for (TdApi.Function<?> function : functions) {
          tdlib.client().send(function, tdlib.messageHandler());
        }
      }
    });
  }

  // Failed messages

  /**
   * Resends the messages. Failed messages that require payment (the price changed or wasn't known when they were
   * sent) are paid for after the same confirmation as new messages.
   */
  public static void resend (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, long chatId, @NonNull long[] messageIds) {
    tdlib.send(new TdApi.GetMessages(chatId, messageIds), (result, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      long required = 0;
      int paidCount = 0;
      long samePrice = -1;
      for (TdApi.Message message : result.messages) {
        if (message != null && message.sendingState != null && message.sendingState.getConstructor() == TdApi.MessageSendingStateFailed.CONSTRUCTOR) {
          long price = ((TdApi.MessageSendingStateFailed) message.sendingState).requiredPaidMessageStarCount;
          if (price > 0) {
            required += price;
            paidCount++;
            samePrice = samePrice == -1 || samePrice == price ? price : 0;
          }
        }
      }
      if (required == 0) {
        tdlib.resendMessages(chatId, messageIds);
        return;
      }
      if (context.isDestroyed()) {
        return;
      }
      final long total = required;
      final int count = paidCount;
      String name = tdlib.chatTitle(chatId);
      CharSequence payLine = count == 1 ?
        Lang.plural(R.string.vg_StarsPaidMessagePayOne, total) :
        payForMessages(total, count);
      // Messages failed at different prices: only the total is named.
      CharSequence text = samePrice > 0 ?
        Lang.plural(R.string.vg_StarsPaidMessageCharges, samePrice, name) + "\n\n" + payLine :
        payLine;
      StarsUi.confirm(context,
        Lang.getString(R.string.vg_StarsPaidMessageConfirmTitle),
        text,
        Lang.plural(R.string.vg_StarsPaidMessagePayButton, count),
        0, false,
        confirmed -> {
          if (!confirmed) {
            return;
          }
          StarsUi.confirmAgain(context, Lang.getString(R.string.vg_StarsConfirmAgainPay, Stars.format(total), name), Lang.getString(R.string.vg_StarsPay), confirmedAgain -> {
            if (confirmedAgain && StarsUi.checkBalance(context, tdlib, total, name, true)) {
              tdlib.client().send(new TdApi.ResendMessages(chatId, messageIds, null, total), tdlib.messageHandler());
            }
          });
        }
      );
    }));
  }

  // Remove fee

  /**
   * «Remove Fee» for a user who pays the current user for messages: lets the user write for free from now on, and
   * optionally refunds the Stars already paid.
   */
  public static void showRemoveFee (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, long userId) {
    tdlib.send(new TdApi.GetPaidMessageRevenue(userId), (revenue, error) -> UI.post(() -> {
      if (context.isDestroyed()) {
        return;
      }
      long paidStars = revenue != null ? revenue.starCount : 0;
      String name = tdlib.cache().userName(userId);
      RunnableBool onConfirmed = refund -> {
        Runnable remove = () -> tdlib.send(new TdApi.AllowUnpaidMessagesFromUser(userId, refund), (ok, error1) -> UI.post(() -> {
          if (error1 != null) {
            UI.showError(error1);
          } else {
            UI.showToast(Lang.getString(R.string.vg_StarsRemoveFeeDone, name), android.widget.Toast.LENGTH_SHORT);
          }
        }));
        CharSequence again = refund ?
          Lang.getString(R.string.vg_StarsConfirmAgainRefund, Stars.format(paidStars), name) :
          Lang.getString(R.string.vg_StarsConfirmAgainRemoveFee, name);
        StarsUi.confirmAgain(context, again, Lang.getString(refund ? R.string.vg_StarsRefundAction : R.string.vg_StarsRemoveFeeAction), confirmedAgain -> {
          if (confirmedAgain) {
            remove.run();
          }
        });
      };
      if (paidStars > 0) {
        // Telegram X dialog with an option (as «Pin message» with «Notify all members»).
        context.showSettings(new SettingsWrapBuilder(R.id.btn_vgStarsRemoveFee)
          .addHeaderItem(new ListItem(ListItem.TYPE_INFO, 0, 0, StarsUi.markdownSpans(context, Lang.getString(R.string.vg_StarsRemoveFeeMessage, name)), false))
          .setRawItems(new ListItem[] {
            new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_vgStarsRefund, 0, Lang.plural(R.string.vg_StarsRemoveFeeRefund, paidStars), false)
          })
          .setSaveStr(Lang.getString(R.string.vg_StarsRemoveFeeAction))
          .setIntDelegate((id, result) -> onConfirmed.runWithBool(result.get(R.id.btn_vgStarsRefund) != 0)));
      } else {
        StarsUi.confirm(context,
          Lang.getString(R.string.vg_StarsRemoveFeeTitle),
          Lang.getString(R.string.vg_StarsRemoveFeeMessage, name),
          Lang.getString(R.string.vg_StarsRemoveFeeAction),
          0, false,
          confirmed -> {
            if (confirmed) {
              onConfirmed.runWithBool(false);
            }
          });
      }
    }));
  }
}
