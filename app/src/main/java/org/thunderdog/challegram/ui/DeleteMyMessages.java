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

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.AuthorizationListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.navigation.AlertPopup;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Delete my messages» in group chats (Vibegram X, the behaviour of the exteragram plugin «Delete My Messages»).
 * <p>
 * The chat menu item counts the messages the user sent to a basic group or supergroup ({@code searchChatMessages} with
 * {@code senderId} = me), asks for a confirmation with that number (its button unlocks after 3 seconds), then deletes
 * them for all members in batches of 100, one batch a second, as the plugin does. Only messages that can be deleted for
 * everyone are deleted; the rest stay and are counted in the result (old messages of basic groups past the server
 * limit), except service messages such as «joined the group», which members can't delete at all.
 * <p>
 * The job keeps running after the chat is closed. Toasts report its start, every {@link #NOTIFY_EVERY} deleted
 * messages and the result with the period of the deleted messages; while it runs, the same menu item offers to stop
 * it. Messages sent after the confirmation are kept.
 */
public final class DeleteMyMessages {
  private DeleteMyMessages () { }

  private static final int BATCH_SIZE = 100;
  private static final int NOTIFY_EVERY = 1000;
  private static final long BATCH_DELAY_MS = 1000L;
  private static final int MAX_PASSES = 10;
  private static final int MAX_FAILED_BATCHES = 3;

  // UI thread only.
  private static final Map<String, Job> jobs = new HashMap<>();
  private static final Set<String> pendingCounts = new HashSet<>();

  private static String key (Tdlib tdlib, long chatId) {
    return tdlib.id() + "_" + chatId;
  }

  /** Group chats only: basic groups and supergroups, not channels, private or secret chats. */
  public static boolean isAvailable (@NonNull Tdlib tdlib, @Nullable TdApi.Chat chat) {
    return chat != null && tdlib.isMultiChat(chat);
  }

  public static boolean isRunning (@NonNull Tdlib tdlib, long chatId) {
    return jobs.containsKey(key(tdlib, chatId));
  }

  /** The chat menu item: confirmation of a new job, or of stopping the running one. */
  public static void onMenuItemPressed (@NonNull ViewController<?> context, long chatId) {
    final Tdlib tdlib = context.tdlib();
    final String key = key(tdlib, chatId);
    Job job = jobs.get(key);
    if (job != null) {
      confirmStop(context, job);
      return;
    }
    if (!pendingCounts.add(key)) {
      return;
    }
    // The newest of the user's messages and their number, for the confirmation.
    tdlib.send(new TdApi.SearchChatMessages(chatId, null, "", tdlib.mySender(), 0, 0, 1, null), (found, error) -> UI.post(() -> {
      pendingCounts.remove(key);
      if (error != null) {
        UI.showToast(Lang.getString(R.string.vg_DeleteMyMessagesError, TD.toErrorString(error)), Toast.LENGTH_LONG);
      } else {
        // TDLib may return fewer messages than asked: without the newest one, the last message of the chat is the bound.
        TdApi.Chat chat = tdlib.chat(chatId);
        long lastMessageId = found.messages.length > 0 ? found.messages[0].id : chat != null && chat.lastMessage != null ? chat.lastMessage.id : 0;
        if (lastMessageId == 0 || found.totalCount <= 0 && found.messages.length == 0) {
          UI.showToast(R.string.vg_DeleteMyMessagesNothing, Toast.LENGTH_SHORT);
        } else if (!context.isDestroyed()) {
          confirmStart(context, chatId, found.totalCount, lastMessageId);
        }
      }
    }));
  }

  /** Seconds the «Delete» button stays locked: deleting all own messages can't be undone. */
  private static final int CONFIRM_DELAY_SECONDS = 3;

  private static void confirmStart (ViewController<?> context, long chatId, int totalCount, long lastMessageId) {
    final Tdlib tdlib = context.tdlib();
    final String title = tdlib.chatTitle(chatId);
    CharSequence text = totalCount > 0 ?
      Lang.getStringBold(R.string.vg_DeleteMyMessagesConfirm, title, Lang.formatNumber(totalCount)) :
      Lang.getStringBold(R.string.vg_DeleteMyMessagesConfirmUnknown, title);
    new AlertPopup.Builder(context.context())
      .setController(context)
      .setTitle(Lang.getString(R.string.vg_DeleteMyMessagesTitle))
      .setMessage(text)
      .setNegativeButton(Lang.getString(R.string.Cancel), null)
      .setPositiveButton(Lang.getString(R.string.Delete), (dialog, which) -> {
        if (!isRunning(tdlib, chatId)) {
          new Job(tdlib, chatId, totalCount, lastMessageId).start();
        }
      })
      .setPositiveButtonDestructive()
      .setPositiveButtonDelay(CONFIRM_DELAY_SECONDS)
      .show();
  }

  private static void confirmStop (ViewController<?> context, Job job) {
    context.showOptions(Lang.getString(R.string.vg_DeleteMyMessagesStopConfirm, Lang.formatNumber(job.deletedCount)),
      new int[] {R.id.btn_vgDeleteMyMessagesStop, R.id.btn_cancel},
      new String[] {Lang.getString(R.string.vg_DeleteMyMessagesStopAction), Lang.getString(R.string.Cancel)},
      new int[] {ViewController.OptionColor.RED, ViewController.OptionColor.NORMAL},
      new int[] {R.drawable.baseline_stop_24, R.drawable.baseline_cancel_24},
      (itemView, id) -> {
        if (id == R.id.btn_vgDeleteMyMessagesStop) {
          job.cancel();
        }
        return true;
      });
  }

  /** Content written by the user, as opposed to service messages («joined the group», «created the topic»). */
  private static boolean isUserContent (TdApi.MessageContent content) {
    switch (content.getConstructor()) {
      case TdApi.MessageText.CONSTRUCTOR:
      case TdApi.MessageAnimation.CONSTRUCTOR:
      case TdApi.MessageAudio.CONSTRUCTOR:
      case TdApi.MessageDocument.CONSTRUCTOR:
      case TdApi.MessagePaidMedia.CONSTRUCTOR:
      case TdApi.MessagePhoto.CONSTRUCTOR:
      case TdApi.MessageSticker.CONSTRUCTOR:
      case TdApi.MessageVideo.CONSTRUCTOR:
      case TdApi.MessageVideoNote.CONSTRUCTOR:
      case TdApi.MessageVoiceNote.CONSTRUCTOR:
      case TdApi.MessageLocation.CONSTRUCTOR:
      case TdApi.MessageVenue.CONSTRUCTOR:
      case TdApi.MessageContact.CONSTRUCTOR:
      case TdApi.MessageAnimatedEmoji.CONSTRUCTOR:
      case TdApi.MessageDice.CONSTRUCTOR:
      case TdApi.MessageGame.CONSTRUCTOR:
      case TdApi.MessagePoll.CONSTRUCTOR:
      case TdApi.MessageStory.CONSTRUCTOR:
      case TdApi.MessageChecklist.CONSTRUCTOR:
      case TdApi.MessageInvoice.CONSTRUCTOR:
        return true;
    }
    return false;
  }

  private static String formatDate (int unixTime) {
    return DateFormat.getDateInstance(DateFormat.SHORT, Lang.locale()).format(new Date(unixTime * 1000L));
  }

  /** One chat: pages of search results from the newest message to the oldest one, deleted as they come. */
  private static final class Job implements AuthorizationListener {
    private final Tdlib tdlib;
    private final long chatId;
    private final String key;
    private final TdApi.MessageSender sender;
    private final int totalCount;
    private final long lastMessageId;

    // Messages already handled: deleted, kept or skipped. A message gets here only after its batch is done, so that a
    // search repeated after a flood wait finds the batch again.
    private final Set<Long> handledIds = new HashSet<>();
    private long fromMessageId;
    private int pass = 1;
    private boolean foundInPass;
    private int deletedCount, keptCount, notifiedCount, failedBatchCount;
    private int minDate, maxDate;
    private boolean isCancelled, isFinished;
    private @Nullable Runnable pendingStep;

    Job (Tdlib tdlib, long chatId, int totalCount, long lastMessageId) {
      this.tdlib = tdlib;
      this.chatId = chatId;
      this.key = key(tdlib, chatId);
      this.sender = tdlib.mySender();
      this.totalCount = totalCount;
      this.lastMessageId = lastMessageId;
    }

    void start () {
      jobs.put(key, this);
      tdlib.listeners().addAuthorizationChangeListener(this);
      UI.showToast(R.string.vg_DeleteMyMessagesStarted, Toast.LENGTH_SHORT);
      search();
    }

    @Override
    public void onAuthorizationStateChanged (TdApi.AuthorizationState authorizationState) {
      switch (authorizationState.getConstructor()) {
        case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
        case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
        case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
          UI.post(this::cancel);
          break;
      }
    }

    /** Finishes the job at once: a request in flight may never return, its late result is ignored. */
    void cancel () {
      if (isFinished) {
        return;
      }
      isCancelled = true;
      if (pendingStep != null) {
        UI.cancel(pendingStep);
        pendingStep = null;
      }
      finish(null);
    }

    private void schedule (Runnable step, long delayMs) {
      pendingStep = () -> {
        pendingStep = null;
        step.run();
      };
      UI.post(pendingStep, delayMs);
    }

    private void search () {
      if (isCancelled) {
        finish(null);
        return;
      }
      tdlib.send(new TdApi.SearchChatMessages(chatId, null, "", sender, fromMessageId, 0, BATCH_SIZE, null), (found, error) -> UI.post(() -> {
        if (isCancelled) {
          finish(null);
        } else if (error != null) {
          if (!retryLater(error, this::search)) {
            finish(error);
          }
        } else {
          onFound(found);
        }
      }));
    }

    private void onFound (TdApi.FoundChatMessages found) {
      final long nextFromMessageId = found.nextFromMessageId;
      final List<TdApi.Message> candidates = new ArrayList<>(found.messages.length);
      for (TdApi.Message message : found.messages) {
        if (handledIds.contains(message.id)) {
          continue;
        }
        if (message.id > lastMessageId) {
          // Sent after the confirmation.
          handledIds.add(message.id);
          continue;
        }
        candidates.add(message);
      }
      if (candidates.isEmpty()) {
        advance(nextFromMessageId);
        return;
      }
      foundInPass = true;
      tdlib.getMessageProperties(candidates, properties -> UI.post(() -> {
        if (isCancelled) {
          finish(null);
          return;
        }
        List<TdApi.Message> deletable = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
          TdApi.Message message = candidates.get(i);
          if (properties[i] != null && properties[i].canBeDeletedForAllUsers) {
            deletable.add(message);
          } else {
            handledIds.add(message.id);
            if (isUserContent(message.content)) {
              keptCount++;
            }
          }
        }
        if (deletable.isEmpty()) {
          advance(nextFromMessageId);
        } else {
          delete(deletable, nextFromMessageId);
        }
      }));
    }

    private void delete (List<TdApi.Message> messages, long nextFromMessageId) {
      long[] messageIds = new long[messages.size()];
      for (int i = 0; i < messageIds.length; i++) {
        messageIds[i] = messages.get(i).id;
      }
      tdlib.send(new TdApi.DeleteMessages(chatId, messageIds, true), (ok, error) -> UI.post(() -> {
        if (isFinished) {
          // Cancelled meanwhile: the result has already been reported.
          return;
        }
        if (error != null) {
          // TDLib drops the messages locally even when the server refuses, so after a flood wait the same page is
          // searched again: the messages come back from the server and get deleted then.
          if (!isCancelled && retryLater(error, this::search)) {
            return;
          }
          for (TdApi.Message message : messages) {
            handledIds.add(message.id);
          }
          keptCount += messages.size();
          if (isCancelled) {
            finish(null);
          } else if (++failedBatchCount >= MAX_FAILED_BATCHES) {
            finish(error);
          } else {
            advance(nextFromMessageId);
          }
          return;
        }
        failedBatchCount = 0;
        for (TdApi.Message message : messages) {
          handledIds.add(message.id);
          minDate = minDate == 0 ? message.date : Math.min(minDate, message.date);
          maxDate = Math.max(maxDate, message.date);
        }
        deletedCount += messages.size();
        if (deletedCount / NOTIFY_EVERY > notifiedCount / NOTIFY_EVERY) {
          notifiedCount = deletedCount;
          UI.showToast(progressText(), Toast.LENGTH_SHORT);
        }
        if (isCancelled) {
          finish(null);
        } else {
          schedule(() -> advance(nextFromMessageId), BATCH_DELAY_MS);
        }
      }));
    }

    private void advance (long nextFromMessageId) {
      if (isCancelled) {
        finish(null);
        return;
      }
      if (nextFromMessageId != 0 && (fromMessageId == 0 || nextFromMessageId < fromMessageId)) {
        fromMessageId = nextFromMessageId;
        if (pass > 1) {
          // Repeated passes mostly page through handled messages: paced, so that searches don't hit flood waits.
          schedule(this::search, BATCH_DELAY_MS);
        } else {
          search();
        }
      } else if (foundInPass && pass < MAX_PASSES) {
        // Search results can end before the oldest message (the plugin restarts from the oldest date for the same
        // reason): start over from the newest message while a pass still finds messages.
        pass++;
        foundInPass = false;
        fromMessageId = 0;
        schedule(this::search, BATCH_DELAY_MS);
      } else {
        finish(null);
      }
    }

    private boolean retryLater (TdApi.Error error, Runnable step) {
      int seconds = TD.getFloodErrorSeconds(error.code, error.message, -1);
      if (seconds < 0) {
        return false;
      }
      if (seconds >= 10) {
        UI.showToast(Lang.getString(R.string.vg_DeleteMyMessagesFloodWait, Lang.getDuration(seconds)), Toast.LENGTH_SHORT);
      }
      schedule(step, (seconds + 1) * 1000L);
      return true;
    }

    private String progressText () {
      if (totalCount >= deletedCount) {
        return Lang.getString(R.string.vg_DeleteMyMessagesProgress, Lang.formatNumber(deletedCount), Lang.formatNumber(totalCount));
      }
      return Lang.getString(R.string.vg_DeleteMyMessagesProgressShort, Lang.formatNumber(deletedCount));
    }

    private void finish (@Nullable TdApi.Error error) {
      if (isFinished) {
        return;
      }
      isFinished = true;
      jobs.remove(key);
      tdlib.listeners().removeAuthorizationChangeListener(this);
      final String text;
      if (error != null) {
        String errorText = Lang.getString(R.string.vg_DeleteMyMessagesError, TD.toErrorString(error));
        text = deletedCount > 0 ? errorText + "\n" + Lang.getString(R.string.vg_DeleteMyMessagesProgressShort, Lang.formatNumber(deletedCount)) : errorText;
      } else if (isCancelled) {
        text = Lang.getString(R.string.vg_DeleteMyMessagesStopped, Lang.formatNumber(deletedCount));
      } else if (deletedCount == 0) {
        text = keptCount > 0 ? Lang.getString(R.string.vg_DeleteMyMessagesNotDeleted, Lang.formatNumber(keptCount)) : Lang.getString(R.string.vg_DeleteMyMessagesNothing);
      } else if (keptCount > 0) {
        text = Lang.getString(R.string.vg_DeleteMyMessagesDonePartial, Lang.formatNumber(deletedCount), formatDate(minDate), formatDate(maxDate), Lang.formatNumber(keptCount));
      } else {
        text = Lang.getString(R.string.vg_DeleteMyMessagesDone, Lang.formatNumber(deletedCount), formatDate(minDate), formatDate(maxDate));
      }
      UI.showToast(text, Toast.LENGTH_LONG);
    }
  }
}
