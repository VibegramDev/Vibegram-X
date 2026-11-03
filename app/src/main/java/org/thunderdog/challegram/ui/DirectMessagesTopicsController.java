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

  /** Loads the topic list: the topics arrive as updateDirectMessagesChatTopic until TDLib returns 404. */
  static void loadTopics (Tdlib tdlib, long chatId) {
    tdlib.send(new TdApi.LoadDirectMessagesChatTopics(chatId, 100), (ok, error) -> {
      if (ok != null) {
        loadTopics(tdlib, chatId);
      }
    });
  }

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

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new RowsAdapter();
    recyclerView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
    recyclerView.setAdapter(adapter);
    buildRows();
    tdlib.listeners().subscribeToChatUpdates(chatId, this);
    loadTopics(tdlib, chatId);
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
        if (senderChat == null) {
          // Local only: creates the chat object for the row, nothing is sent to the user.
          tdlib.send(new TdApi.CreatePrivateChat(userId, true), (result, error) -> {
            if (result != null) {
              runOnUiThreadOptional(this::buildRows);
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

  private void buildRows () {
    rows.clear();
    TdApi.Chat currentChat = tdlib.chat(chatId);
    if (currentChat != null) {
      chat = currentChat;
    }
    TdApi.Chat allChat = Td.copyOf(chat);
    allChat.positions = new TdApi.ChatPosition[0];
    rows.add(new Row(null, new TGChat(this, ChatPosition.CHAT_LIST_MAIN, allChat, true)));
    for (TdApi.DirectMessagesChatTopic topic : tdlib.directMessagesChatTopics(chatId)) {
      TdApi.Chat senderChat = findSenderChat(topic.senderId);
      if (senderChat != null) {
        rows.add(new Row(topic, newTopicRow(senderChat, topic)));
      }
    }
    if (adapter != null) {
      adapter.notifyDataSetChanged();
    }
  }

  @Override
  public void onDirectMessagesChatTopicUpdated (long chatId, TdApi.DirectMessagesChatTopic topic) {
    runOnUiThreadOptional(this::buildRows);
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
