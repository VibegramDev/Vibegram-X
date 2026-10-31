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
 * Vibegram X: checklist creation, editing and adding tasks (official app: ChatAttachAlertPollLayout and
 * PollCreateActivity in the "todo" mode).
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.CustomItemAnimator;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Keyboard;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.CharacterStyleFilter;
import org.thunderdog.challegram.util.HapticMenuHelper;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.FillingDecoration;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.text.CodePointCountFilter;
import me.vkryl.android.text.RestrictFilter;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.Td;

public class CreateChecklistController extends RecyclerViewController<CreateChecklistController.Args> implements View.OnClickListener, SettingsAdapter.TextChangeListener {
  public static final int MODE_CREATE = 0;
  public static final int MODE_EDIT = 1;
  public static final int MODE_ADD_TASKS = 2;

  public static class Args {
    public final int mode;
    public final long chatId;
    public final @Nullable TdApi.MessageTopic messageTopicId;
    public final @Nullable TdApi.InputSuggestedPostInfo inputSuggestedPostInfo;
    public final @Nullable Callback callback;
    public final long messageId;
    public final @Nullable TdApi.Checklist checklist;
    public final int focusTaskId;

    private Args (int mode, long chatId, @Nullable TdApi.MessageTopic messageTopicId, @Nullable TdApi.InputSuggestedPostInfo inputSuggestedPostInfo, @Nullable Callback callback, long messageId, @Nullable TdApi.Checklist checklist, int focusTaskId) {
      this.mode = mode;
      this.chatId = chatId;
      this.messageTopicId = messageTopicId;
      this.inputSuggestedPostInfo = inputSuggestedPostInfo;
      this.callback = callback;
      this.messageId = messageId;
      this.checklist = checklist;
      this.focusTaskId = focusTaskId;
    }

    public static Args newChecklist (long chatId, @Nullable TdApi.MessageTopic messageTopicId, @Nullable TdApi.InputSuggestedPostInfo inputSuggestedPostInfo, @NonNull Callback callback) {
      return new Args(MODE_CREATE, chatId, messageTopicId, inputSuggestedPostInfo, callback, 0, null, 0);
    }

    public static Args existingChecklist (long chatId, long messageId, @NonNull TdApi.Checklist checklist, int mode, int focusTaskId) {
      if (mode != MODE_EDIT && mode != MODE_ADD_TASKS)
        throw new IllegalArgumentException(Integer.toString(mode));
      return new Args(mode, chatId, null, null, null, messageId, checklist, focusTaskId);
    }
  }

  public interface Callback {
    boolean onSendChecklist (CreateChecklistController context, long chatId, @Nullable TdApi.MessageTopic topicId, TdApi.InputMessageChecklist checklist, TdApi.MessageSendOptions sendOptions, RunnableData<TdApi.Message> after);
    boolean areScheduledOnly (CreateChecklistController context);
    TdApi.ChatList provideChatList (CreateChecklistController context);
  }

  /** Data of a task row: an existing task keeps its identifier, tasks of the list are locked when only adding. */
  private static class TaskData {
    final int taskId;
    final @Nullable TdApi.FormattedText originalText;
    final boolean isLocked;

    TaskData (int taskId, @Nullable TdApi.FormattedText originalText, boolean isLocked) {
      this.taskId = taskId;
      this.originalText = originalText;
      this.isLocked = isLocked;
    }
  }

  public CreateChecklistController (@NonNull Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_createChecklist;
  }

  private int mode () {
    return getArgumentsStrict().mode;
  }

  @Override
  public CharSequence getName () {
    switch (mode()) {
      case MODE_EDIT:
        return Lang.getString(R.string.EditChecklist);
      case MODE_ADD_TASKS:
        return Lang.getString(R.string.ChecklistNewTasks);
      case MODE_CREATE:
      default:
        return Lang.getString(R.string.NewChecklist);
    }
  }

  @Override
  public boolean passNameToHeader () {
    return true;
  }

  @Override
  public int getRootColorId () {
    return ColorId.background;
  }

  private SettingsAdapter adapter;
  private FillingDecoration decoration;
  private ListItem titleItem, addItem, tasksHint, allowMarkingItem, allowAddingItem;
  private final List<ListItem> tasks = new ArrayList<>();
  private int lockedTaskCount;
  private int maxTaskId;
  private boolean allowMarking = true, allowAdding = true;
  private @Nullable ListItem pendingFocusItem;

  private int maxTaskCount () {
    return Math.max(1, tdlib.options().checklistTaskCountMax);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    final Args args = getArgumentsStrict();

    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        final int itemId = item.getId();
        if (itemId == R.id.title) {
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.addLengthCounter(false);
          editText.setMaxLength(tdlib.options().checklistTitleLengthMax);
          editText.setAlwaysActive(true);
          editText.getEditText().setLineDisabled(true);
        } else if (itemId == R.id.optionAdd) {
          editText.setEmptyHint(R.string.ChecklistAddTask);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.getEditText().setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
          editText.setNeedNextButton(v -> true);
        } else if (itemId == R.id.option) {
          TaskData data = (TaskData) item.getData();
          boolean isLocked = data != null && data.isLocked;
          editText.setEmptyHint(R.string.ChecklistTask);
          editText.setMaxLength(tdlib.options().checklistTaskTextLengthMax);
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
          Views.setSingleLine(editText.getEditText(), false);
          editText.setAlwaysActive(true);
          editText.getEditText().setLineDisabled(true);
          editText.setInputEnabled(!isLocked);
          editText.getEditText().setBackspaceListener((v, text, selectionStart, selectionEnd) -> {
            ListItem listItem = (ListItem) ((ViewGroup) v.getParent().getParent()).getTag();
            if (listItem != null && !isLockedTask(listItem) && editableTaskCount() > 1 && (text.length() == 0 || text.toString().trim().isEmpty())) {
              removeTask(listItem);
              return true;
            }
            return false;
          });
          editText.setNeedNextButton((v) -> {
            ListItem listItem = (ListItem) ((ViewGroup) v.getParent()).getTag();
            if (listItem != null && listItem.getId() == R.id.option) {
              int i = adapter.indexOfView(listItem);
              if (i != -1 && i + 2 < adapter.getItems().size()) {
                int id = adapter.getItems().get(i + 2).getId();
                return id == R.id.option || id == R.id.optionAdd;
              }
            }
            return false;
          });
          editText.getEditText().setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
        }
      }

      @Override
      public void onFocusChanged (MaterialEditTextGroup v, boolean isFocused) {
        super.onFocusChanged(v, isFocused);
        if (isFocused && ((ViewGroup) v.getParent()).getId() == R.id.optionAdd) {
          addTask();
        }
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        int itemId = item.getId();
        if (itemId == R.id.btn_checklistAllowMarking) {
          view.getToggler().setRadioEnabled(allowMarking, isUpdate);
        } else if (itemId == R.id.btn_checklistAllowAdding) {
          view.getToggler().setRadioEnabled(allowAdding, isUpdate);
        }
      }
    };
    adapter.setTextChangeListener(this);

    decoration = new FillingDecoration(recyclerView, this) {
      @Override
      public void getItemOffsets (@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int i = parent.getChildAdapterPosition(view);
        outRect.top = 0;
        outRect.bottom = i != -1 && i == adapter.getItemCount() - 1 ? Screen.dp(56f) : 0;
      }
    };

    List<ListItem> items = new ArrayList<>();

    final TdApi.Checklist checklist = args.checklist;
    if (checklist != null) {
      allowMarking = checklist.othersCanMarkTasksAsDone;
      allowAdding = checklist.othersCanAddTasks;
      for (TdApi.ChecklistTask task : checklist.tasks) {
        maxTaskId = Math.max(maxTaskId, task.id);
      }
    }

    if (args.mode != MODE_ADD_TASKS) {
      titleItem = new ListItem(ListItem.TYPE_EDITTEXT, R.id.title, 0, R.string.ChecklistTitle);
      if (checklist != null && !Td.isEmpty(checklist.title)) {
        titleItem.setStringValue(TD.toCharSequence(checklist.title));
      }
      items.add(titleItem);
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      decoration.addId(R.id.title);
      items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChecklistTasks));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));

    final int tasksStart = items.size();
    if (checklist != null) {
      boolean isLocked = args.mode == MODE_ADD_TASKS;
      for (TdApi.ChecklistTask task : checklist.tasks) {
        if (items.size() > tasksStart) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        }
        ListItem taskItem = newTaskItem(new TaskData(task.id, task.text, isLocked));
        taskItem.setStringValue(TD.toCharSequence(task.text));
        items.add(taskItem);
        if (args.focusTaskId != 0 && task.id == args.focusTaskId) {
          pendingFocusItem = taskItem;
        }
      }
      if (isLocked) {
        lockedTaskCount = checklist.tasks.length;
      }
    }
    if (args.mode != MODE_EDIT && tasks.size() < maxTaskCount()) {
      // A new list starts with one empty task, adding tasks starts with an empty one after the list
      if (items.size() > tasksStart) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      }
      ListItem newTask = newTaskItem(null);
      items.add(newTask);
      if (args.mode == MODE_ADD_TASKS) {
        pendingFocusItem = newTask;
      }
    }
    addItem = new ListItem(ListItem.TYPE_EDITTEXT_POLL_OPTION_ADD, R.id.optionAdd).setInputFilters(new InputFilter[] {new CodePointCountFilter(0)});
    if (tasks.size() < maxTaskCount()) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(addItem);
    }
    decoration.addRange(tasksStart, items.size());
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(tasksHint = new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, buildLimitText(), false));

    if (args.mode != MODE_ADD_TASKS) {
      items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.Settings));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(allowMarkingItem = new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_checklistAllowMarking, 0, R.string.ChecklistAllowMarking));
      allowAddingItem = new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_checklistAllowAdding, 0, R.string.ChecklistAllowAdding);
      if (allowMarking) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(allowAddingItem);
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }

    items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));

    adapter.setItems(items, false);
    if (args.mode == MODE_CREATE || (args.mode == MODE_EDIT && pendingFocusItem == null)) {
      adapter.setLockFocusOn(this, false);
    }

    recyclerView.setAdapter(adapter);
    recyclerView.setItemAnimator(new CustomItemAnimator(AnimatorUtils.DECELERATE_INTERPOLATOR, 180L));
    recyclerView.addItemDecoration(decoration);
  }

  private ListItem newTaskItem (@Nullable TaskData data) {
    ListItem item = new ListItem(ListItem.TYPE_EDITTEXT_POLL_OPTION, R.id.option).setInputFilters(new InputFilter[] {
      new CodePointCountFilter(Math.max(1, tdlib.options().checklistTaskTextLengthMax)),
      new CharacterStyleFilter(),
      new RestrictFilter(new char[] {'\n'})
    }).setOnEditorActionListener(new EditBaseController.SimpleEditorActionListener(EditorInfo.IME_ACTION_NEXT, v -> addTask()));
    item.setData(data);
    tasks.add(item);
    return item;
  }

  private static boolean isLockedTask (ListItem item) {
    TaskData data = (TaskData) item.getData();
    return data != null && data.isLocked;
  }

  private int editableTaskCount () {
    return tasks.size() - lockedTaskCount;
  }

  private String buildLimitText () {
    final int maxTaskCount = maxTaskCount();
    return tasks.size() >= maxTaskCount ? Lang.getString(R.string.ChecklistTasksMax) : Lang.plural(R.string.ChecklistTasksLimit, maxTaskCount - tasks.size());
  }

  private void updateLimit () {
    if (tasksHint.setStringIfChanged(buildLimitText())) {
      adapter.updateValuedSetting(tasksHint);
    }
  }

  private HapticMenuHelper currentMenu;
  private boolean doneButtonPrepared;

  @Override
  public void onFocus () {
    super.onFocus();
    if (!doneButtonPrepared) {
      doneButtonPrepared = true;
      boolean isCreate = mode() == MODE_CREATE;
      getDoneButton().setIcon(isCreate ? R.drawable.deproko_baseline_send_24 : R.drawable.baseline_check_24, isCreate ? Screen.dp(1.5f) : 0);
      checkDone();
    }
    if (mode() == MODE_CREATE && currentMenu == null) {
      currentMenu = tdlib.ui().createSimpleHapticMenu(this, getArgumentsStrict().chatId, this::canDone, null, null, null, this::send, null)
        .attachToView(getDoneButton());
    }
    if (pendingFocusItem != null) {
      final ListItem item = pendingFocusItem;
      pendingFocusItem = null;
      int i = adapter.indexOfView(item);
      if (i != -1) {
        ((androidx.recyclerview.widget.LinearLayoutManager) getRecyclerView().getLayoutManager()).scrollToPositionWithOffset(i, Screen.dp(56f));
      }
      tdlib.ui().postDelayed(() -> {
        if (!isDestroyed()) {
          requestFocus(item);
        }
      }, 120);
    }
  }

  @Override
  public void onClick (View v) {
    final int itemId = v.getId();
    if (itemId == R.id.optionAdd) {
      addTask();
    } else if (itemId == R.id.btn_checklistAllowMarking) {
      allowMarking = adapter.toggleView(v);
      int markingIndex = adapter.indexOfView(allowMarkingItem);
      int addingIndex = adapter.indexOfView(allowAddingItem);
      if (allowMarking && addingIndex == -1 && markingIndex != -1) {
        adapter.addItems(markingIndex + 1, new ListItem(ListItem.TYPE_SEPARATOR_FULL), allowAddingItem);
      } else if (!allowMarking && addingIndex != -1) {
        adapter.removeRange(addingIndex - 1, 2);
      }
    } else if (itemId == R.id.btn_checklistAllowAdding) {
      allowAdding = adapter.toggleView(v);
    }
  }

  private boolean isAdding;

  private boolean addTask () {
    final int maxTaskCount = maxTaskCount();
    if (tasks.size() >= maxTaskCount || isAdding)
      return false;
    int i = adapter.indexOfViewById(R.id.optionAdd);
    if (i == -1)
      return false;
    isAdding = true;
    int targetIndex;
    if (tasks.size() + 1 == maxTaskCount) {
      adapter.setItem(targetIndex = i, newTaskItem(null));
    } else {
      adapter.getItems().add(i - 1, newTaskItem(null));
      adapter.getItems().add(i - 1, new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      decoration.lastRange()[1] += 2;
      targetIndex = i;
      adapter.notifyItemRangeInserted(i, 2);
    }
    updateLimit();
    checkDone();
    tdlib.ui().postDelayed(() -> {
      isAdding = false;
      View view = getRecyclerView().getLayoutManager().findViewByPosition(targetIndex);
      if (view instanceof ViewGroup) {
        Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
      }
    }, 180);
    return true;
  }

  private void removeTask (ListItem task) {
    final int i = tasks.indexOf(task);
    if (i == -1)
      return;
    tasks.remove(i);
    boolean hideKeyboard = true;
    if (!tasks.isEmpty()) {
      ListItem nextFocus = tasks.get(i > 0 ? i - 1 : 0);
      if (!isLockedTask(nextFocus)) {
        int nextIndex = adapter.indexOfView(nextFocus);
        View view = getRecyclerView().getLayoutManager().findViewByPosition(nextIndex);
        if (view instanceof ViewGroup) {
          Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
          hideKeyboard = false;
        }
      }
    }
    int adapterPosition = adapter.indexOfView(task);
    if (adapterPosition == -1)
      throw new AssertionError();
    if (tasks.size() == maxTaskCount() - 1 && adapter.indexOfView(addItem) == -1) {
      // The "Add a Task" row comes back
      if (i == tasks.size()) {
        adapter.setItem(adapterPosition, addItem);
      } else {
        adapter.removeRange(adapterPosition, 2);
        int lastAdapterPosition = adapter.indexOfView(tasks.get(tasks.size() - 1));
        adapter.getItems().add(lastAdapterPosition + 1, addItem);
        adapter.getItems().add(lastAdapterPosition + 1, new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        adapter.notifyItemRangeInserted(lastAdapterPosition + 1, 2);
      }
    } else {
      decoration.lastRange()[1] -= 2;
      if (i == 0) {
        // The first row has no separator above it: remove the one below
        adapter.removeRange(adapterPosition, 2);
      } else {
        adapter.removeRange(adapterPosition - 1, 2);
      }
    }
    if (hideKeyboard) {
      hideSoftwareKeyboard();
    }
    updateLimit();
    checkDone();
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup v) {
    checkDone();
  }

  private void requestFocus (ListItem item) {
    int i = adapter.indexOfView(item);
    View view = getRecyclerView().getLayoutManager().findViewByPosition(i);
    if (view instanceof ViewGroup) {
      Keyboard.show(((MaterialEditTextGroup) ((ViewGroup) view).getChildAt(0)).getEditText());
    }
  }

  // Values

  private @Nullable TdApi.FormattedText getTitle () {
    if (titleItem == null)
      return null;
    CharSequence cs = StringUtils.trim(titleItem.getCharSequenceValue());
    if (StringUtils.isEmpty(cs))
      return null;
    TdApi.Checklist checklist = getArgumentsStrict().checklist;
    if (checklist != null && checklist.title != null && cs.toString().equals(checklist.title.text)) {
      // Unchanged: keep the original entities
      return checklist.title;
    }
    return TD.toFormattedText(cs, false);
  }

  private static @Nullable TdApi.FormattedText getTaskText (ListItem item) {
    CharSequence cs = StringUtils.trim(item.getCharSequenceValue());
    if (StringUtils.isEmpty(cs))
      return null;
    TaskData data = (TaskData) item.getData();
    if (data != null && data.originalText != null && cs.toString().equals(data.originalText.text)) {
      return data.originalText;
    }
    TdApi.FormattedText text = TD.toFormattedText(cs, false);
    return Td.isEmpty(text) ? null : text;
  }

  private boolean canDone () {
    final Args args = getArgumentsStrict();
    if (args.mode != MODE_ADD_TASKS) {
      TdApi.FormattedText title = getTitle();
      if (title == null || Td.codePointCount(title) > tdlib.options().checklistTitleLengthMax)
        return false;
    }
    final int maxLength = tdlib.options().checklistTaskTextLengthMax;
    int count = 0;
    for (ListItem item : tasks) {
      if (isLockedTask(item))
        continue;
      TdApi.FormattedText text = getTaskText(item);
      if (text == null)
        continue;
      if (Td.codePointCount(text) > maxLength)
        return false;
      count++;
    }
    return count > 0;
  }

  private boolean hasChanges () {
    final Args args = getArgumentsStrict();
    final TdApi.Checklist checklist = args.checklist;
    if (args.mode == MODE_CREATE) {
      if (getTitle() != null)
        return true;
      for (ListItem item : tasks) {
        if (getTaskText(item) != null)
          return true;
      }
      return false;
    }
    if (args.mode == MODE_ADD_TASKS) {
      for (ListItem item : tasks) {
        if (!isLockedTask(item) && getTaskText(item) != null)
          return true;
      }
      return false;
    }
    // MODE_EDIT
    if (checklist == null)
      return false;
    TdApi.FormattedText title = getTitle();
    if (title == null || !Td.equalsTo(title, checklist.title))
      return true;
    if (allowMarking != checklist.othersCanMarkTasksAsDone || (allowMarking && allowAdding != checklist.othersCanAddTasks))
      return true;
    List<TdApi.InputChecklistTask> newTasks = buildTasks(false);
    if (newTasks.size() != checklist.tasks.length)
      return true;
    for (int i = 0; i < newTasks.size(); i++) {
      TdApi.InputChecklistTask newTask = newTasks.get(i);
      TdApi.ChecklistTask oldTask = checklist.tasks[i];
      if (newTask.id != oldTask.id || !Td.equalsTo(newTask.text, oldTask.text))
        return true;
    }
    return false;
  }

  private List<TdApi.InputChecklistTask> buildTasks (boolean onlyNew) {
    List<TdApi.InputChecklistTask> result = new ArrayList<>(tasks.size());
    int nextTaskId = maxTaskId;
    for (ListItem item : tasks) {
      TaskData data = (TaskData) item.getData();
      if (onlyNew && data != null)
        continue;
      TdApi.FormattedText text = getTaskText(item);
      if (text == null)
        continue;
      int taskId = data != null ? data.taskId : ++nextTaskId;
      result.add(new TdApi.InputChecklistTask(taskId, text));
    }
    return result;
  }

  private void checkDone () {
    boolean canDone = canDone();
    if (!canDone && currentMenu != null) {
      currentMenu.hideMenu();
    }
    setDoneVisible(canDone, true);
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (hasChanges()) {
      if (commit) {
        showOptions(Lang.getString(R.string.ChecklistDiscardPrompt), new int[] {R.id.btn_done, R.id.btn_cancel}, new String[] {Lang.getString(R.string.ChecklistDiscard), Lang.getString(R.string.Cancel)}, new int[] {OptionColor.RED, OptionColor.NORMAL}, new int[] {R.drawable.baseline_delete_forever_24, R.drawable.baseline_cancel_24}, (itemView, id) -> {
          if (id == R.id.btn_done) {
            navigateBack();
          }
          return true;
        });
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    return !hasChanges() && !getDoneButton().isInProgress();
  }

  // Done

  @Override
  protected void onDoneClick () {
    if (mode() == MODE_CREATE) {
      send(Td.newSendOptions(), false);
    } else {
      saveChanges();
    }
  }

  private boolean validate () {
    final Args args = getArgumentsStrict();
    if (args.mode != MODE_ADD_TASKS) {
      TdApi.FormattedText title = getTitle();
      if (title == null || Td.codePointCount(title) > tdlib.options().checklistTitleLengthMax) {
        requestFocus(titleItem);
        return false;
      }
    }
    final int maxLength = tdlib.options().checklistTaskTextLengthMax;
    for (ListItem item : tasks) {
      if (isLockedTask(item))
        continue;
      TdApi.FormattedText text = getTaskText(item);
      if (text != null && Td.codePointCount(text) > maxLength) {
        requestFocus(item);
        return false;
      }
    }
    return canDone();
  }

  private void showError (TdApi.Error error) {
    context().tooltipManager().builder(getDoneButton()).controller(this).show(tdlib, TD.toErrorString(error)).hideDelayed();
  }

  private void send (TdApi.MessageSendOptions sendOptions, boolean disableMarkdown) {
    if (getDoneButton().isInProgress() || !validate())
      return;
    final Args args = getArgumentsStrict();
    final long chatId = args.chatId;
    final TdApi.MessageTopic messageTopicId = args.messageTopicId;
    if (args.callback == null)
      throw new IllegalStateException();

    if (sendOptions.schedulingState == null && args.callback.areScheduledOnly(this)) {
      tdlib.ui().showScheduleOptions(this, chatId, false, (modifiedSendOptions, disableMarkdown1) -> send(modifiedSendOptions, disableMarkdown), sendOptions, null);
      return;
    }

    final CharSequence slowModeRestrictionText = tdlib.getSlowModeRestrictionText(chatId, sendOptions.schedulingState);
    if (slowModeRestrictionText != null) {
      context().tooltipManager().builder(getDoneButton()).controller(this).show(tdlib, slowModeRestrictionText).hideDelayed();
      return;
    }

    TdApi.InputChecklist checklist = new TdApi.InputChecklist(
      getTitle(),
      buildTasks(false).toArray(new TdApi.InputChecklistTask[0]),
      allowMarking && allowAdding,
      allowMarking
    );
    TdApi.InputMessageChecklist content = new TdApi.InputMessageChecklist(checklist);

    getDoneButton().setInProgress(true);
    hideSoftwareKeyboard();

    RunnableData<TdApi.Message> after = message -> runOnUiThreadOptional(() -> {
      getDoneButton().setInProgress(false);
      if (message != null) {
        if (sendOptions.schedulingState != null && !args.callback.areScheduledOnly(this)) {
          NavigationStack stack = navigationStack();
          if (stack != null) {
            MessagesController c = new MessagesController(context, tdlib);
            c.setArguments(new MessagesController.Arguments(args.callback.provideChatList(this), tdlib.chatStrict(args.chatId), /* messageThread */ null, messageTopicId, null, MessagesManager.HIGHLIGHT_MODE_NONE, null).setScheduled(true));
            stack.insertBack(c);
          }
        }
        navigateBack();
      }
    });
    final TdApi.MessageSendOptions finalSendOptions = Td.newSendOptions(sendOptions, args.inputSuggestedPostInfo, tdlib.chatDefaultDisableNotifications(chatId));
    if (!args.callback.onSendChecklist(this, chatId, messageTopicId, content, finalSendOptions, after)) {
      tdlib.sendMessage(chatId, messageTopicId, null, finalSendOptions, content, after);
    }
  }

  private void saveChanges () {
    if (getDoneButton().isInProgress() || !validate())
      return;
    final Args args = getArgumentsStrict();
    if (args.checklist == null)
      throw new IllegalStateException();
    if (args.mode == MODE_EDIT && !hasChanges()) {
      navigateBack();
      return;
    }
    getDoneButton().setInProgress(true);
    hideSoftwareKeyboard();
    if (args.mode == MODE_ADD_TASKS) {
      TdApi.InputChecklistTask[] newTasks = buildTasks(true).toArray(new TdApi.InputChecklistTask[0]);
      tdlib.send(new TdApi.AddChecklistTasks(args.chatId, args.messageId, newTasks), (ok, error) -> runOnUiThreadOptional(() -> {
        getDoneButton().setInProgress(false);
        if (error != null) {
          showError(error);
        } else {
          navigateBack();
        }
      }));
    } else {
      TdApi.InputChecklist checklist = new TdApi.InputChecklist(
        getTitle(),
        buildTasks(false).toArray(new TdApi.InputChecklistTask[0]),
        allowMarking && allowAdding,
        allowMarking
      );
      tdlib.send(new TdApi.EditMessageChecklist(args.chatId, args.messageId, null, checklist), (message, error) -> runOnUiThreadOptional(() -> {
        getDoneButton().setInProgress(false);
        if (error != null) {
          showError(error);
        } else {
          navigateBack();
        }
      }));
    }
  }
}
