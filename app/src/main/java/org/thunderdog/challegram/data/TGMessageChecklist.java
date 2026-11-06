/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Vibegram X: checklists (MessageChecklist), drawn after the official Android app.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.CreateChecklistController;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.widget.CheckBoxView;
import org.thunderdog.challegram.widget.SimplestCheckBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.util.ClickHelper;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.DateUtils;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.lambda.Destroyable;
import tgx.td.ChatId;
import tgx.td.Td;
import tgx.td.data.MessageWithProperties;

/**
 * A checklist in a message bubble. Layout and behavior follow the official app (ChatMessageCell, todo):
 * a bold title, "Checklist"/"Group Checklist" under it, one row per task and "N of M completed" below.
 * When the user may mark tasks, every task has a round check box; a done task shows the avatar
 * of whoever completed it next to the box and their name under the task text.
 * Otherwise a done task is crossed out with a check mark on the left, an open one has a dot.
 */
public class TGMessageChecklist extends TGMessage implements ClickHelper.Delegate, ComplexReceiver.KeyFilter {
  private static final int ANIMATOR_TASK = 0;

  private static final float CHECK_CENTER_X = 13f;
  private static final float TEXT_OFFSET_MARKING = 48f;
  private static final float TEXT_OFFSET_READ_ONLY = 35f;
  private static final float MIN_TASK_HEIGHT = 46f;
  private static final float STATUS_HEIGHT = 18f;
  private static final float CHECK_RADIUS = 9f;
  private static final float AVATAR_RADIUS = 10f;
  private static final float AVATAR_OFFSET_X = 13.75f;
  private static final float AVATAR_CUT_RADIUS = 11f;
  private static final float AUTHOR_SHIFT = 7.66f;
  private static final float FOOTER_GAP = 8f;
  private static final float FOOTER_HEIGHT = 16f;

  private static final class PendingState {
    final boolean isDone;
    final int requestId;

    PendingState (boolean isDone, int requestId) {
      this.isDone = isDone;
      this.requestId = requestId;
    }
  }

  private static final class TaskEntry implements Destroyable {
    final int taskId;
    TdApi.ChecklistTask task;
    boolean isDone;
    int completionDate;
    @Nullable TdApi.MessageSender completedBy;
    @Nullable String authorName;
    @Nullable Text authorText;
    @Nullable TdApi.FormattedText textSource;
    boolean isStrikethrough;
    @Nullable TextWrapper text;
    @Nullable BoolAnimator isChecked;
    @Nullable SimplestCheckBox checkBox;

    TaskEntry (int taskId) {
      this.taskId = taskId;
    }

    float getCheckFactor () {
      return isChecked != null ? isChecked.getFloatValue() : isDone ? 1f : 0f;
    }

    @Override
    public void performDestroy () {
      if (checkBox != null) {
        checkBox.destroy();
        checkBox = null;
      }
      if (text != null) {
        text.performDestroy();
      }
    }
  }

  private TdApi.Checklist checklist;
  private final ClickHelper clickHelper;
  private final Map<Integer, PendingState> pendingStates = new HashMap<>();
  private int lastRequestId;

  private @Nullable TdApi.FormattedText titleSource;
  private @Nullable TextWrapper titleText;
  private @Nullable Text statusText;
  private @StringRes int statusRes;
  private TaskEntry[] entries = new TaskEntry[0];
  private String footerStr;
  private int footerStrWidth;
  private int preparedWidth;
  private boolean textWrappersChanged;

  public TGMessageChecklist (MessagesManager manager, TdApi.Message msg, TdApi.MessageChecklist content) {
    super(manager, msg);
    this.checklist = content.list;
    this.clickHelper = new ClickHelper(this);
  }

  // Public API

  public TdApi.Checklist getChecklist () {
    return checklist;
  }

  public static TdApi.InputChecklist toInputChecklist (TdApi.Checklist checklist, int excludeTaskId) {
    List<TdApi.InputChecklistTask> tasks = new ArrayList<>(checklist.tasks.length);
    for (TdApi.ChecklistTask task : checklist.tasks) {
      if (task.id != excludeTaskId) {
        tasks.add(new TdApi.InputChecklistTask(task.id, task.text));
      }
    }
    return new TdApi.InputChecklist(checklist.title, tasks.toArray(new TdApi.InputChecklistTask[0]), checklist.othersCanAddTasks, checklist.othersCanMarkTasksAsDone);
  }

  public static String formatCompletionDate (int date) {
    String time = Lang.time(date, TimeUnit.SECONDS);
    if (DateUtils.isToday(date, TimeUnit.SECONDS)) {
      return Lang.getString(R.string.ChecklistCompletedToday, time);
    }
    if (DateUtils.isYesterday(date, TimeUnit.SECONDS)) {
      return Lang.getString(R.string.ChecklistCompletedYesterday, time);
    }
    return Lang.getString(R.string.ChecklistCompletedDate, Lang.dateFullShort(date, TimeUnit.SECONDS), time);
  }

  // State

  private boolean isMarkingLayout () {
    if (isEventLog()) {
      return false;
    }
    if (checklist.canMarkTasksAsDone) {
      return true;
    }
    // Same rule as the official app: the own list (or a shared one) keeps check boxes
    // while it is being sent or waits for its scheduled date.
    return msg.forwardInfo == null && (isNotSent() || isScheduled()) && (msg.isOutgoing || checklist.othersCanMarkTasksAsDone);
  }

  private int getTextOffset () {
    return Screen.dp(isMarkingLayout() ? TEXT_OFFSET_MARKING : TEXT_OFFSET_READ_ONLY);
  }

  private TdApi.MessageSender selfSender () {
    TdApi.Chat chat = tdlib.chat(msg.chatId);
    if (chat != null && chat.messageSenderId != null) {
      return chat.messageSenderId;
    }
    return tdlib.mySender();
  }

  private static boolean isDone (TdApi.ChecklistTask task) {
    return task.completionDate != 0 || task.completedBy != null;
  }

  private void applyChecklist (TdApi.Checklist checklist, boolean animated) {
    boolean layoutChanged = this.checklist.tasks.length != checklist.tasks.length || !Td.equalsTo(this.checklist.title, checklist.title) || this.checklist.canMarkTasksAsDone != checklist.canMarkTasksAsDone || this.checklist.othersCanMarkTasksAsDone != checklist.othersCanMarkTasksAsDone;
    if (!layoutChanged) {
      for (int i = 0; i < checklist.tasks.length; i++) {
        if (this.checklist.tasks[i].id != checklist.tasks[i].id || !Td.equalsTo(this.checklist.tasks[i].text, checklist.tasks[i].text)) {
          layoutChanged = true;
          break;
        }
      }
    }
    this.checklist = checklist;
    if (titleText == null) {
      // Content isn't built yet
      return;
    }
    setTexts(animated && !layoutChanged && needAnimateChanges());
    invalidateContentReceiver();
    if (layoutChanged) {
      rebuildAndUpdateContent();
    } else {
      invalidate();
    }
    requestChangedTextMedia();
  }

  private void requestChangedTextMedia () {
    if (textWrappersChanged) {
      textWrappersChanged = false;
      invalidateTextMediaReceiver();
    }
  }

  private void setTexts (boolean animated) {
    // Title
    TdApi.FormattedText title = checklist.title != null ? checklist.title : Td.emptyFormattedText();
    if (titleText == null || !Td.equalsTo(titleSource, title)) {
      titleSource = title;
      if (titleText != null) {
        titleText.performDestroy();
      }
      titleText = new TextWrapper(tdlib, title, getBiggerTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> invalidateTextMediaReceiver(text, specificMedia))
        .addTextFlags(Text.FLAG_ALL_BOLD)
        .setViewProvider(currentViews);
      textWrappersChanged = true;
      if (preparedWidth > 0) {
        titleText.prepare(preparedWidth);
      }
    }

    // "Checklist" or "Group Checklist"
    @StringRes int statusRes = checklist.othersCanMarkTasksAsDone && !tdlib.isSelfChat(msg.chatId) ? R.string.GroupChecklist : R.string.Checklist;
    if (this.statusRes != statusRes || statusText == null) {
      this.statusRes = statusRes;
      this.statusText = new Text.Builder(Lang.getString(statusRes), Math.max(preparedWidth, Screen.dp(100f)), Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
    }

    // Tasks
    final boolean markingLayout = isMarkingLayout();
    final int taskTextWidth = preparedWidth - getTextOffset();
    TaskEntry[] oldEntries = this.entries;
    TaskEntry[] newEntries = new TaskEntry[checklist.tasks.length];
    for (int i = 0; i < checklist.tasks.length; i++) {
      TdApi.ChecklistTask task = checklist.tasks[i];
      TaskEntry entry = null;
      for (TaskEntry oldEntry : oldEntries) {
        if (oldEntry.taskId == task.id) {
          entry = oldEntry;
          break;
        }
      }
      if (entry == null) {
        entry = new TaskEntry(task.id);
      }
      entry.task = task;

      boolean serverDone = isDone(task);
      PendingState pending = pendingStates.get(task.id);
      boolean isDone = pending != null ? pending.isDone : serverDone;
      TdApi.MessageSender completedBy;
      if (!isDone) {
        completedBy = null;
      } else if (serverDone && task.completedBy != null) {
        completedBy = task.completedBy;
      } else if (pending != null) {
        completedBy = selfSender();
      } else {
        completedBy = null;
      }
      entry.isDone = isDone;
      entry.completionDate = isDone ? (serverDone ? task.completionDate : (int) tdlib.currentTime(TimeUnit.SECONDS)) : 0;
      if (completedBy != null) {
        // Unchecked tasks keep the previous author, so the avatar fades out with the check box
        entry.completedBy = completedBy;
      }
      if (entry.isChecked == null) {
        entry.isChecked = new BoolAnimator(ANIMATOR_TASK, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165L, isDone);
      } else {
        entry.isChecked.setValue(isDone, animated);
      }
      String authorName = entry.completedBy != null ? tdlib.senderName(entry.completedBy, false) : null;
      if (authorName == null) {
        entry.authorName = null;
        entry.authorText = null;
      } else if (!authorName.equals(entry.authorName) || entry.authorText == null) {
        entry.authorName = authorName;
        entry.authorText = taskTextWidth > 0 ? buildAuthorText(authorName, taskTextWidth) : null;
      }

      boolean isStrikethrough = isDone && !markingLayout;
      TdApi.FormattedText text = task.text != null ? task.text : Td.emptyFormattedText();
      if (entry.text == null || entry.isStrikethrough != isStrikethrough || !Td.equalsTo(entry.textSource, text)) {
        entry.textSource = text;
        entry.isStrikethrough = isStrikethrough;
        if (entry.text != null) {
          entry.text.performDestroy();
        }
        entry.text = new TextWrapper(tdlib, isStrikethrough ? withStrikethrough(text) : text, getTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, t, specificMedia) -> invalidateTextMediaReceiver(t, specificMedia))
          .setViewProvider(currentViews);
        textWrappersChanged = true;
        if (taskTextWidth > 0) {
          entry.text.prepare(taskTextWidth);
        }
      }
      newEntries[i] = entry;
    }
    for (TaskEntry oldEntry : oldEntries) {
      boolean found = false;
      for (TaskEntry newEntry : newEntries) {
        if (newEntry == oldEntry) {
          found = true;
          break;
        }
      }
      if (!found) {
        oldEntry.performDestroy();
      }
    }
    this.entries = newEntries;

    setFooter();
  }

  private Text buildAuthorText (String name, int maxWidth) {
    return new Text.Builder(name, maxWidth, Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
  }

  private static TdApi.FormattedText withStrikethrough (TdApi.FormattedText text) {
    if (Td.isEmpty(text)) {
      return text;
    }
    int entityCount = text.entities != null ? text.entities.length : 0;
    TdApi.TextEntity[] entities = new TdApi.TextEntity[entityCount + 1];
    entities[0] = new TdApi.TextEntity(0, text.text.length(), new TdApi.TextEntityTypeStrikethrough());
    if (entityCount > 0) {
      System.arraycopy(text.entities, 0, entities, 1, entityCount);
    }
    return new TdApi.FormattedText(text.text, entities);
  }

  private int getCompletedCount () {
    int count = 0;
    for (TaskEntry entry : entries) {
      if (entry.isDone) {
        count++;
      }
    }
    return count;
  }

  private void setFooter () {
    String total = String.valueOf(entries.length);
    String completed = String.valueOf(getCompletedCount());
    String footer;
    // Official: an incoming list in a private chat that others can't mark names its author
    if (!msg.isOutgoing && ChatId.isUserChat(msg.chatId) && !checklist.othersCanMarkTasksAsDone) {
      footer = Lang.getString(R.string.ChecklistCompletedBy, total, completed, tdlib.senderName(msg.senderId, false));
    } else {
      footer = Lang.getString(R.string.ChecklistCompleted, total, completed);
    }
    if (!footer.equals(footerStr)) {
      footerStr = footer;
      footerStrWidth = (int) U.measureText(footer, Paints.getRegularTextPaint(12f));
    }
  }

  // Layout

  @Override
  protected void buildContent (int maxWidth) {
    preparedWidth = maxWidth;
    if (titleText == null) {
      setTexts(false);
      // MessageView requests the text media of a freshly built message itself
      textWrappersChanged = false;
    }
    if (titleText != null) {
      titleText.prepare(maxWidth);
    }
    if (statusText == null || statusText.getMaxWidth() != maxWidth) {
      statusText = new Text.Builder(Lang.getString(statusRes), maxWidth, Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
    }
    int taskTextWidth = maxWidth - getTextOffset();
    for (TaskEntry entry : entries) {
      if (entry.text != null) {
        entry.text.prepare(taskTextWidth);
      }
      if (entry.authorName != null && (entry.authorText == null || entry.authorText.getMaxWidth() != taskTextWidth)) {
        entry.authorText = buildAuthorText(entry.authorName, taskTextWidth);
      }
    }
  }

  private int getTitleHeight () {
    return titleText != null && !Td.isEmpty(titleSource) ? titleText.getHeight() + Screen.dp(5f) : 0;
  }

  private static int getTaskTextTopOffset (TaskEntry entry) {
    int lineHeight = entry.text != null ? entry.text.getLineHeight() : 0;
    return Math.max(Screen.dp(8f), Screen.dp(MIN_TASK_HEIGHT) / 2 - lineHeight / 2);
  }

  private static int getTaskHeight (TaskEntry entry) {
    if (entry.text == null) {
      return Screen.dp(MIN_TASK_HEIGHT) + Screen.separatorSize();
    }
    return Math.max(Screen.dp(MIN_TASK_HEIGHT), getTaskTextTopOffset(entry) + entry.text.getHeight() + Screen.dp(12f)) + Screen.separatorSize();
  }

  private int getTasksTop () {
    return getTitleHeight() + Screen.dp(STATUS_HEIGHT);
  }

  @Override
  protected int getContentHeight () {
    int height = getTasksTop();
    for (TaskEntry entry : entries) {
      height += getTaskHeight(entry);
    }
    height += Screen.dp(FOOTER_GAP) + Screen.dp(FOOTER_HEIGHT);
    if (useBubbles()) {
      height += Screen.dp(4f);
    }
    return height;
  }

  @Override
  protected int getBottomLineContentWidth () {
    // The counter is centered: its right edge is what the time must not overlap
    int width = getContentWidth();
    return footerStrWidth >= width ? width : (width + footerStrWidth) / 2;
  }

  // Media

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    if (titleText == null) {
      textMediaReceiver.clear();
      return;
    }
    int idOffset = Integer.MAX_VALUE / (entries.length + 1);
    titleText.requestMedia(textMediaReceiver, 0, idOffset);
    long key = 0;
    for (TaskEntry entry : entries) {
      key += idOffset;
      if (entry.text != null) {
        entry.text.requestMedia(textMediaReceiver, key, idOffset);
      } else {
        textMediaReceiver.clearReceiversRange(key, key + idOffset);
      }
    }
  }

  @Override
  public boolean needComplexReceiver () {
    return true;
  }

  @Override
  public void requestMediaContent (ComplexReceiver complexReceiver, boolean invalidate, int invalidateArg) {
    if (isMarkingLayout()) {
      for (TaskEntry entry : entries) {
        if (entry.completedBy != null) {
          AvatarReceiver receiver = complexReceiver.getAvatarReceiver(Td.getSenderId(entry.completedBy));
          receiver.requestMessageSender(tdlib, entry.completedBy, AvatarReceiver.Options.NONE);
        }
      }
    }
    complexReceiver.clearReceivers(this);
  }

  @Override
  public boolean filterKey (int receiverType, Receiver receiver, long key) {
    if (isMarkingLayout()) {
      for (TaskEntry entry : entries) {
        if (entry.completedBy != null && Td.getSenderId(entry.completedBy) == key) {
          return true;
        }
      }
    }
    return false;
  }

  // Drawing

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    final int decentColor = getDecentColor();
    final int lineColor = getVerticalLineColor();
    final int lineContentColor = getVerticalLineContentColor();
    final boolean markingLayout = isMarkingLayout();
    final int textOffset = getTextOffset();
    // Official: the press selector spans from 8dp before the content to its right edge,
    // separators go from 2dp before the task text to the same right edge.
    final int leftX = startX - (useBubbles() ? Screen.dp(8f) : 0);
    final int rightX = startX + maxWidth;

    if (titleText != null && !Td.isEmpty(titleSource)) {
      titleText.draw(c, startX, startX + maxWidth, 0, startY, null, 1f, view.getTextMediaReceiver());
      startY += getTitleHeight();
    }
    if (statusText != null) {
      statusText.draw(c, startX, startY);
    }
    startY += Screen.dp(STATUS_HEIGHT);

    for (int i = 0; i < entries.length; i++) {
      TaskEntry entry = entries[i];
      int taskHeight = getTaskHeight(entry);

      if (highlightIndex == i) {
        c.drawRect(leftX, startY, rightX, startY + taskHeight, Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }

      int separatorY = startY + taskHeight - Screen.separatorSize();
      c.drawLine(startX + textOffset - Screen.dp(2f), separatorY, rightX, separatorY, Paints.getProgressPaint(getSeparatorColor(), Screen.separatorSize()));

      float checkFactor = entry.getCheckFactor();
      int textTop = startY + getTaskTextTopOffset(entry);
      int lineHeight = entry.text != null ? entry.text.getLineHeight() : Screen.dp(20f);
      int cx = startX + Screen.dp(CHECK_CENTER_X);
      int cy = textTop + lineHeight / 2;

      float authorFactor = markingLayout && entry.completedBy != null && entry.authorText != null ? checkFactor : 0f;
      int textY = textTop - Math.round(Screen.dp(AUTHOR_SHIFT) * authorFactor);
      if (entry.text != null) {
        entry.text.draw(c, startX + textOffset, startX + maxWidth, 0, textY, null, 1f, view.getTextMediaReceiver());
      }
      if (authorFactor > 0f) {
        int authorY = textY + (entry.text != null ? entry.text.getHeight() : lineHeight) + Screen.dp(1.5f);
        entry.authorText.draw(c, startX + textOffset, authorY, null, authorFactor);
      }

      if (markingLayout) {
        if (checkFactor < 1f) {
          c.drawCircle(cx, cy, Screen.dp(CHECK_RADIUS), Paints.getProgressPaint(ColorUtils.alphaColor(1f - checkFactor, decentColor), Screen.dp(1f)));
        }
        if (checkFactor > 0f && entry.completedBy != null) {
          drawAvatar(c, receiver, entry.completedBy, cx + Screen.dp(AVATAR_OFFSET_X), cy, checkFactor);
          int cutColor = getContentReplaceColor();
          if (highlightIndex == i) {
            cutColor = ColorUtils.compositeColor(cutColor, Theme.getColor(getPressColorId()));
          }
          c.drawCircle(cx, cy, Screen.dp(AVATAR_CUT_RADIUS) * (.5f + .5f * checkFactor), Paints.fillingPaint(cutColor));
        }
        if (checkFactor > 0f) {
          if (entry.checkBox == null) {
            entry.checkBox = SimplestCheckBox.newInstance(checkFactor, null, lineColor, lineContentColor, SimplestCheckBox.MODE_NORMAL, 0f);
          }
          SimplestCheckBox.draw(c, cx, cy, checkFactor, null, entry.checkBox, lineColor, lineContentColor, SimplestCheckBox.MODE_NORMAL, 0f);
        }
      } else if (entry.isDone) {
        // Material Symbols Rounded "check" at 18dp (1.5dp stroke).
        CheckBoxView.drawCheckMark(c, cx, cy, CheckBoxView.MARK_CHECK, .75f, 1f, 0f, lineColor);
      } else {
        c.drawCircle(cx, cy, Screen.dp(2.5f), Paints.fillingPaint(lineColor));
      }

      startY += taskHeight;
    }

    startY += Screen.dp(FOOTER_GAP);
    if (footerStr != null) {
      c.drawText(footerStr, startX + maxWidth / 2f - footerStrWidth / 2f, startY + Screen.dp(12f), Paints.getRegularTextPaint(12f, decentColor));
    }
  }

  private void drawAvatar (Canvas c, ComplexReceiver complexReceiver, TdApi.MessageSender sender, float cx, float cy, float alpha) {
    AvatarReceiver receiver = complexReceiver.getAvatarReceiver(Td.getSenderId(sender));
    int radius = Screen.dp(AVATAR_RADIUS);
    receiver.setBounds((int) (cx - radius), (int) (cy - radius), (int) (cx + radius), (int) (cy + radius));
    boolean needScale = alpha != 1f;
    int restoreToCount;
    if (needScale) {
      float scale = .5f + .5f * alpha;
      restoreToCount = Views.save(c);
      c.scale(scale, scale, cx, cy);
      receiver.setPaintAlpha(receiver.getPaintAlpha() * alpha);
    } else {
      restoreToCount = -1;
    }
    if (receiver.needPlaceholder()) {
      receiver.drawPlaceholder(c);
    }
    receiver.draw(c);
    if (needScale) {
      receiver.restorePaintAlpha();
      Views.restore(c, restoreToCount);
    }
  }

  // Animation

  @Override
  protected void onChildFactorChanged (int id, float factor, float fraction) {
    if (id == ANIMATOR_TASK) {
      invalidate();
    }
  }

  // Updates

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() == TdApi.MessageChecklist.CONSTRUCTOR) {
      applyChecklist(((TdApi.MessageChecklist) newContent).list, true);
      return true;
    }
    return false;
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    applyChecklist(((TdApi.MessageChecklist) newContent).list, true);
    return true;
  }

  @Override
  protected boolean onLocaleChange () {
    statusText = null;
    statusRes = 0;
    footerStr = null;
    if (titleText != null) {
      setTexts(false);
    }
    return true;
  }

  @Override
  protected void onMessageContainerDestroyed () {
    if (titleText != null) {
      titleText.performDestroy();
    }
    for (TaskEntry entry : entries) {
      entry.performDestroy();
    }
  }

  // Touch

  private static final int HIGHLIGHT_NONE = -1;
  private int clickTaskIndex = HIGHLIGHT_NONE;
  private int highlightIndex = HIGHLIGHT_NONE;

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    boolean res = super.onTouchEvent(view, e);
    return (!isEventLog() && clickHelper.onTouchEvent(view, e)) || res;
  }

  private int findTaskIndex (float originalX, float originalY) {
    float x = originalX - getContentX();
    float y = originalY - getContentY();
    if (x < 0 || y < 0 || x > getContentMaxWidth()) {
      return HIGHLIGHT_NONE;
    }
    int top = getTasksTop();
    for (int i = 0; i < entries.length; i++) {
      int height = getTaskHeight(entries[i]);
      if (y >= top && y < top + height) {
        return i;
      }
      top += height;
    }
    return HIGHLIGHT_NONE;
  }

  private int getTaskTop (int index) {
    int top = getTasksTop();
    for (int i = 0; i < index && i < entries.length; i++) {
      top += getTaskHeight(entries[i]);
    }
    return top;
  }

  @Override
  public boolean needClickAt (View view, float x, float y) {
    if (isEventLog() || isNotSent()) {
      return false;
    }
    clickTaskIndex = findTaskIndex(x, y);
    return clickTaskIndex != HIGHLIGHT_NONE;
  }

  @Override
  public void onClickTouchDown (View view, float x, float y) {
    setHighlightIndex(clickTaskIndex);
  }

  @Override
  public void onClickTouchUp (View view, float x, float y) {
    setHighlightIndex(HIGHLIGHT_NONE);
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    int index = clickTaskIndex;
    clickTaskIndex = HIGHLIGHT_NONE;
    if (index >= 0 && index < entries.length) {
      toggleTask(view, entries[index]);
    }
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    clickHelper.cancel(view, x, y);
    setHighlightIndex(HIGHLIGHT_NONE);
    boolean res = super.performLongPress(view, x, y);
    if (!isEventLog() && !isNotSent() && allowInteraction() && !messagesController().inSelectMode()) {
      int index = findTaskIndex(x, y);
      if (index != HIGHLIGHT_NONE) {
        showTaskOptions(entries[index]);
        return true;
      }
    }
    return res;
  }

  private void setHighlightIndex (int index) {
    if (this.highlightIndex != index) {
      this.highlightIndex = index;
      invalidate();
    }
  }

  private TooltipOverlayView.LocationProvider makeTaskLocationProvider (int taskId) {
    return (targetView, outRect) -> {
      int index = indexOfTask(taskId);
      if (index == -1) {
        outRect.set(0, 0, getContentWidth(), getContentHeight());
        return;
      }
      TaskEntry entry = entries[index];
      int top = getTaskTop(index) + getTaskTextTopOffset(entry);
      int lineHeight = entry.text != null ? entry.text.getLineHeight() : Screen.dp(20f);
      int cx = Screen.dp(CHECK_CENTER_X);
      int radius = Screen.dp(CHECK_RADIUS);
      outRect.set(cx - radius, top + lineHeight / 2 - radius, cx + radius, top + lineHeight / 2 + radius);
    };
  }

  private int indexOfTask (int taskId) {
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].taskId == taskId) {
        return i;
      }
    }
    return -1;
  }

  private void showTaskHint (View view, int taskId, CharSequence text, boolean isPremium) {
    TooltipOverlayView.TooltipBuilder builder = buildContentHint(view, makeTaskLocationProvider(taskId), true);
    if (isPremium) {
      builder.icon(R.drawable.baseline_warning_24);
    }
    builder.show(tdlib, text).hideDelayed();
  }

  /**
   * Same checks and order as the official app (ChatActivity.didPressToDoButton).
   */
  private boolean checkCanMark (View view, int taskId) {
    if (isScheduled()) {
      showTaskHint(view, taskId, Lang.getString(R.string.ChecklistScheduled), false);
      return false;
    }
    if (msg.forwardInfo != null) {
      showTaskHint(view, taskId, Lang.getString(R.string.ChecklistMarkForwarded), false);
      return false;
    }
    if (!checklist.canMarkTasksAsDone) {
      showTaskHint(view, taskId, Lang.getMarkdownString(controller(), R.string.ChecklistMarkRestricted, tdlib.senderName(msg.senderId, false)), false);
      return false;
    }
    if (!tdlib.hasPremium()) {
      showTaskHint(view, taskId, Lang.getMarkdownString(controller(), R.string.ChecklistMarkPremium), true);
      return false;
    }
    return true;
  }

  private void toggleTask (View view, TaskEntry entry) {
    if (isEventLog() || isNotSent() || !checkCanMark(view, entry.taskId)) {
      return;
    }
    UI.hapticVibrate(view, false);
    setTaskDone(view, entry.taskId, !entry.isDone);
  }

  private void setTaskDone (@Nullable View view, final int taskId, final boolean isDone) {
    final int requestId = ++lastRequestId;
    pendingStates.put(taskId, new PendingState(isDone, requestId));
    setTexts(needAnimateChanges());
    invalidateContentReceiver();
    invalidate();
    requestChangedTextMedia();

    final long chatId = msg.chatId, messageId = msg.id;
    int[] taskIds = new int[] {taskId};
    tdlib.send(new TdApi.MarkChecklistTasksAsDone(chatId, messageId, isDone ? taskIds : new int[0], isDone ? new int[0] : taskIds), (ok, error) -> runOnUiThreadOptional(() -> {
      PendingState pending = pendingStates.get(taskId);
      if (pending != null && pending.requestId == requestId) {
        // TDLib has applied the server updates by now: show the actual state
        pendingStates.remove(taskId);
        setTexts(needAnimateChanges());
        invalidateContentReceiver();
        invalidate();
        requestChangedTextMedia();
      }
      if (error != null) {
        View currentView = findCurrentView();
        if (currentView != null) {
          showTaskHint(currentView, taskId, TD.toErrorString(error), false);
        } else {
          UI.showError(error);
        }
      }
    }));
  }

  // Task menu (official: TodoItemMenu)

  private void showTaskOptions (TaskEntry entry) {
    final MessagesController c = messagesController();
    final int taskId = entry.taskId;
    final TdApi.ChecklistTask task = entry.task;
    final TdApi.MessageProperties properties = lastMessageProperties();

    IntList ids = new IntList(6);
    List<String> strings = new ArrayList<>(6);
    IntList icons = new IntList(6);
    IntList colors = new IntList(6);
    CharSequence info = null;

    if (checklist.canMarkTasksAsDone || isScheduled()) {
      if (entry.isDone) {
        if (entry.completionDate != 0) {
          info = formatCompletionDate(entry.completionDate);
        }
        ids.append(R.id.btn_checklistTaskCheck);
        strings.add(Lang.getString(R.string.ChecklistUncheck));
        icons.append(R.drawable.baseline_cancel_24);
        colors.append(ViewController.OptionColor.NORMAL);
      } else {
        ids.append(R.id.btn_checklistTaskCheck);
        strings.add(Lang.getString(R.string.ChecklistCheck));
        icons.append(R.drawable.baseline_check_circle_24);
        colors.append(ViewController.OptionColor.NORMAL);
      }
    }
    if (canReplyTo() && c.canWriteMessages()) {
      ids.append(R.id.btn_checklistTaskReply);
      strings.add(Lang.getString(R.string.ChecklistReplyToTask));
      icons.append(R.drawable.baseline_reply_24);
      colors.append(ViewController.OptionColor.NORMAL);
    }
    if (tdlib.canCopyPostLink(msg)) {
      ids.append(R.id.btn_checklistTaskCopyLink);
      strings.add(Lang.getString(R.string.CopyLink));
      icons.append(R.drawable.baseline_link_24);
      colors.append(ViewController.OptionColor.NORMAL);
    }
    if (canBeSaved()) {
      ids.append(R.id.btn_checklistTaskCopy);
      strings.add(Lang.getString(R.string.Copy));
      icons.append(R.drawable.baseline_content_copy_24);
      colors.append(ViewController.OptionColor.NORMAL);
    }
    if (properties.canBeEdited && !isScheduled()) {
      ids.append(R.id.btn_checklistTaskEdit);
      strings.add(Lang.getString(R.string.ChecklistEditTask));
      icons.append(R.drawable.baseline_edit_24);
      colors.append(ViewController.OptionColor.NORMAL);
      if (checklist.tasks.length > 1) {
        ids.append(R.id.btn_checklistTaskDelete);
        strings.add(Lang.getString(R.string.ChecklistDeleteTask));
        icons.append(R.drawable.baseline_delete_24);
        colors.append(ViewController.OptionColor.RED);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    if (info == null) {
      info = TD.toCharSequence(task.text);
    }
    c.showOptions(info, ids.get(), strings.toArray(new String[0]), colors.get(), icons.get(), (itemView, id) -> {
      if (id == R.id.btn_checklistTaskCheck) {
        View view = findCurrentView();
        if (view != null && checkCanMark(view, taskId)) {
          setTaskDone(view, taskId, !isTaskDone(taskId));
        }
      } else if (id == R.id.btn_checklistTaskReply) {
        c.showReply(new MessageWithProperties(msg, lastMessageProperties()), null, taskId, null, true, true);
      } else if (id == R.id.btn_checklistTaskCopyLink) {
        tdlib.send(new TdApi.GetMessageLink(msg.chatId, msg.id, 0, taskId, "", false, false), (link, error) -> {
          if (link != null) {
            tdlib.ui().post(() -> UI.copyText(link.link, link.isPublic ? R.string.CopiedLink : R.string.CopiedLinkPrivate));
          } else {
            UI.showError(error);
          }
        });
      } else if (id == R.id.btn_checklistTaskCopy) {
        UI.copyText(TD.toCopyText(task.text), R.string.CopiedText);
      } else if (id == R.id.btn_checklistTaskEdit) {
        c.openChecklistEditor(this, CreateChecklistController.MODE_EDIT, taskId);
      } else if (id == R.id.btn_checklistTaskDelete) {
        tdlib.send(new TdApi.EditMessageChecklist(msg.chatId, msg.id, null, toInputChecklist(checklist, taskId)), (message, error) -> {
          if (error != null) {
            UI.showError(error);
          }
        });
      }
      return true;
    });
  }

  private boolean isTaskDone (int taskId) {
    int index = indexOfTask(taskId);
    return index != -1 && entries[index].isDone;
  }
}
