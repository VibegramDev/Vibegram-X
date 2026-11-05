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

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.collection.LongSparseArray;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.dialogs.ChatView;
import org.thunderdog.challegram.data.TGChat;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.collection.IntList;
import org.thunderdog.challegram.util.StringList;
import tgx.td.ChatPosition;
import tgx.td.Td;

/**
 * Channel direct messages chat administered by the user, as a list (as forum topics): the whole chat first, then one
 * row per sender, drawn as regular chat list rows (ChatView) with the sender's avatar and name, the last message,
 * the date and the unread count. See {@link DirectMessagesTabsController} for the tabs view.
 */
public class DirectMessagesTopicsController extends RecyclerViewController<DirectMessagesTopicsController.Arguments> implements ChatListener, View.OnClickListener, Menu, MoreDelegate {
  public static class Arguments {
    public final TdApi.Chat chat;

    public Arguments (TdApi.Chat chat) {
      this.chat = chat;
    }
  }

  /** Topic list loader: topics arrive as updateDirectMessagesChatTopic, TDLib returns 404 when all of them are loaded. */
  static class TopicsLoader {
    private final Tdlib tdlib;
    private final long chatId;
    private boolean isLoading, isEndReached;

    TopicsLoader (Tdlib tdlib, long chatId) {
      this.tdlib = tdlib;
      this.chatId = chatId;
    }

    void loadMore () {
      if (isLoading || isEndReached) {
        return;
      }
      isLoading = true;
      tdlib.send(new TdApi.LoadDirectMessagesChatTopics(chatId, 100), (ok, error) -> tdlib.ui().post(() -> {
        isLoading = false;
        if (ok == null) {
          isEndReached = true;
        }
      }));
    }
  }

  private TopicsLoader topicsLoader;

  private TdApi.Chat chat;
  private long chatId;

  public DirectMessagesTopicsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    this.chat = args.chat;
    this.chatId = args.chat.id;
  }

  @Override
  public int getId () {
    return R.id.controller_directMessagesTopics;
  }

  @Override
  public long getChatId () {
    return chatId;
  }

  @Override
  public CharSequence getName () {
    return chat != null ? tdlib.chatTitle(chat) : "";
  }

  // Rows

  private static class Row {
    final @Nullable TdApi.DirectMessagesChatTopic topic; // null for the whole chat
    final TGChat chat;

    Row (@Nullable TdApi.DirectMessagesChatTopic topic, TGChat chat) {
      this.topic = topic;
      this.chat = chat;
    }
  }

  private final List<Row> rows = new ArrayList<>();
  private RowsAdapter adapter;
  /** Rows of topics by topic id, reused while their topic doesn't change. */
  private final LongSparseArray<Row> topicRows = new LongSparseArray<>();
  /** Topics changed since the last update of the list. */
  private final java.util.Set<Long> changedTopicIds = new java.util.HashSet<>();
  private boolean needRebuildAllRows;
  /** Users for whom CreatePrivateChat was already sent. */
  private final java.util.Set<Long> requestedPrivateChatUserIds = new java.util.HashSet<>();

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new RowsAdapter();
    LinearLayoutManager manager = new LinearLayoutManager(context, RecyclerView.VERTICAL, false);
    recyclerView.setLayoutManager(manager);
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        if (manager.findLastVisibleItemPosition() >= rows.size() - 10) {
          topicsLoader.loadMore();
        }
      }
    });
    needRebuildAllRows = true;
    updateRows();
    tdlib.listeners().subscribeToChatUpdates(chatId, this);
    topicsLoader = new TopicsLoader(tdlib, chatId);
    topicsLoader.loadMore();
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
  }

  /** The sender's own chat gives the row its avatar, name and status; the topic gives the messages and counters. */
  private @Nullable TdApi.Chat findSenderChat (TdApi.MessageSender sender) {
    switch (sender.getConstructor()) {
      case TdApi.MessageSenderUser.CONSTRUCTOR: {
        long userId = ((TdApi.MessageSenderUser) sender).userId;
        TdApi.Chat senderChat = tdlib.chat(userId);
        if (senderChat == null && requestedPrivateChatUserIds.add(userId)) {
          // Local only: creates the chat object for the row, nothing is sent to the user.
          // Rows without a sender chat are added on the next list update.
          tdlib.send(new TdApi.CreatePrivateChat(userId, true), (result, error) -> {
            if (result != null) {
              onDirectMessagesChatTopicUpdated(chatId, null);
            }
          });
        }
        return senderChat;
      }
      case TdApi.MessageSenderChat.CONSTRUCTOR:
        return tdlib.chat(((TdApi.MessageSenderChat) sender).chatId);
    }
    return null;
  }

  private TGChat newTopicRow (TdApi.Chat senderChat, TdApi.DirectMessagesChatTopic topic) {
    TdApi.Chat displayChat = Td.copyOf(senderChat);
    displayChat.lastMessage = topic.lastMessage;
    displayChat.draftMessage = topic.draftMessage;
    displayChat.unreadCount = (int) topic.unreadCount;
    displayChat.unreadMentionCount = 0;
    displayChat.unreadReactionCount = (int) topic.unreadReactionCount;
    displayChat.isMarkedAsUnread = topic.isMarkedAsUnread;
    displayChat.lastReadInboxMessageId = topic.lastReadInboxMessageId;
    displayChat.lastReadOutboxMessageId = topic.lastReadOutboxMessageId;
    displayChat.positions = new TdApi.ChatPosition[0];
    displayChat.notificationSettings = chat.notificationSettings;
    displayChat.actionBar = null;
    return new TGChat(this, ChatPosition.CHAT_LIST_MAIN, displayChat, true);
  }

  private static long rowId (Row row) {
    return row.topic != null ? row.topic.id : -1;
  }

  /** Updates the list: only rows of changed topics are rebuilt, the adapter gets the exact changes. */
  private void updateRows () {
    final boolean rebuildAll = needRebuildAllRows;
    needRebuildAllRows = false;
    List<Row> oldRows = new ArrayList<>(rows);
    java.util.Set<Long> changedRowIds = new java.util.HashSet<>(changedTopicIds);
    changedTopicIds.clear();

    List<Row> newRows = new ArrayList<>();
    // The whole chat row: its counters change with any topic, rebuilt each time (a single row)
    TdApi.Chat currentChat = tdlib.chat(chatId);
    if (currentChat != null) {
      chat = currentChat;
    }
    TdApi.Chat allChat = Td.copyOf(chat);
    allChat.positions = new TdApi.ChatPosition[0];
    newRows.add(new Row(null, new TGChat(this, ChatPosition.CHAT_LIST_MAIN, allChat, true)));
    LongSparseArray<Row> newTopicRows = new LongSparseArray<>();
    for (TdApi.DirectMessagesChatTopic topic : tdlib.directMessagesChatTopics(chatId)) {
      Row row = topicRows.get(topic.id);
      if (row == null || rebuildAll || changedRowIds.contains(topic.id)) {
        TdApi.Chat senderChat = findSenderChat(topic.senderId);
        row = senderChat != null ? new Row(topic, newTopicRow(senderChat, topic)) : null;
        changedRowIds.add(topic.id);
      }
      if (row != null) {
        newRows.add(row);
        newTopicRows.put(topic.id, row);
      }
    }
    topicRows.clear();
    for (int i = 0; i < newTopicRows.size(); i++) {
      topicRows.put(newTopicRows.keyAt(i), newTopicRows.valueAt(i));
    }
    rows.clear();
    rows.addAll(newRows);
    if (adapter == null) {
      return;
    }
    androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
      @Override
      public int getOldListSize () {
        return oldRows.size();
      }

      @Override
      public int getNewListSize () {
        return newRows.size();
      }

      @Override
      public boolean areItemsTheSame (int oldItemPosition, int newItemPosition) {
        return rowId(oldRows.get(oldItemPosition)) == rowId(newRows.get(newItemPosition));
      }

      @Override
      public boolean areContentsTheSame (int oldItemPosition, int newItemPosition) {
        return oldRows.get(oldItemPosition) == newRows.get(newItemPosition);
      }
    }).dispatchUpdatesTo(adapter);
  }

  private boolean rowsUpdatePending;

  /** Topics come one update each (hundreds while loading): the list is updated at most every 300 ms. */
  @Override
  public void onDirectMessagesChatTopicUpdated (long chatId, TdApi.DirectMessagesChatTopic topic) {
    runOnUiThreadOptional(() -> {
      if (topic != null) {
        changedTopicIds.add(topic.id);
      }
      if (rowsUpdatePending) {
        return;
      }
      rowsUpdatePending = true;
      runOnUiThreadOptional(() -> {
        rowsUpdatePending = false;
        if (!isDestroyed()) {
          updateRows();
        }
      }, null, 300);
    });
  }

  @Override
  public void onClick (View v) {
    Object tag = v.getTag();
    if (!(tag instanceof Row)) {
      return;
    }
    Row row = (Row) tag;
    MessagesController c = new MessagesController(context, tdlib);
    TdApi.MessageTopic topicId = row.topic != null ? new TdApi.MessageTopicDirectMessages(row.topic.id) : null;
    c.setArguments(new MessagesController.Arguments(tdlib, null, chat, null, topicId, null));
    navigateTo(c);
  }

  private class RowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      ChatView view = new ChatView(context, tdlib);
      view.setOnClickListener(DirectMessagesTopicsController.this);
      addThemeInvalidateListener(view);
      return new RecyclerView.ViewHolder(view) { };
    }

    @Override
    public void onBindViewHolder (@NonNull RecyclerView.ViewHolder holder, int position) {
      Row row = rows.get(position);
      ChatView view = (ChatView) holder.itemView;
      view.setTag(row);
      view.setChat(row.chat);
      // The row stands for a topic, not for the sender's own chat: no chat preview on hold.
      view.setPreviewChatId(null, 0, null);
    }

    @Override
    public int getItemCount () {
      return rows.size();
    }

    @Override
    public void onViewAttachedToWindow (@NonNull RecyclerView.ViewHolder holder) {
      ((ChatView) holder.itemView).attach();
    }

    @Override
    public void onViewDetachedFromWindow (@NonNull RecyclerView.ViewHolder holder) {
      ((ChatView) holder.itemView).detach();
    }
  }

  // Menu

  @Override
  protected int getMenuId () {
    return R.id.menu_more;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_more) {
      header.addMoreButton(menu, this);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_more) {
      IntList ids = new IntList(1);
      IntList icons = new IntList(1);
      StringList strings = new StringList(1);
      ids.append(R.id.btn_viewAsTabs);
      icons.append(R.drawable.baseline_swap_horiz_24);
      strings.append(R.string.ViewAsTabs);
      showMore(ids.get(), strings.get(), icons.get(), 0);
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (id == R.id.btn_viewAsTabs) {
      tdlib.settings().setForumViewPreference(chatId, TdlibSettingsManager.FORUM_VIEW_TABS);
      DirectMessagesTabsController c = new DirectMessagesTabsController(context, tdlib);
      c.setArguments(new DirectMessagesTabsController.Arguments(chat));
      c.addOneShotFocusListener(() -> c.destroyStackItemAt(c.stackSize() - 2));
      navigateTo(c);
    }
  }
}
