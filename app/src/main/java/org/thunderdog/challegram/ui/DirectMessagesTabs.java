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
 */
package org.thunderdog.challegram.ui;

import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.util.text.Counter;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.collection.IntList;

/**
 * Channel direct messages chat administered by the user as tabs, as in the official app: «All» with the whole chat,
 * then one tab per sender (avatar, name, unread count) with that conversation. See {@link DirectMessagesTopicsController}
 * for the list view.
 */
public class DirectMessagesTabs implements ChatTopicTabs, ChatListener, ViewPagerTopView.OnItemClickListener {
  private final MessagesController owner;
  private final Tdlib tdlib;
  private final TdApi.Chat chat;
  private final long chatId;
  /** Conversation shown by {@link #owner}, 0 for «All». */
  private final long topicId;
  private final List<TdApi.DirectMessagesChatTopic> topics = new ArrayList<>();
  private final DirectMessagesTopicsController.TopicsLoader topicsLoader;
  private @Nullable ViewPagerHeaderViewCompact headerView;
  private boolean isSwitching, isDestroyed, topicsUpdatePending;

  public DirectMessagesTabs (MessagesController owner, TdApi.Chat chat, long topicId) {
    this.owner = owner;
    this.tdlib = owner.tdlib();
    this.chat = chat;
    this.chatId = chat.id;
    this.topicId = topicId;
    topics.addAll(tdlib.directMessagesChatTopics(chatId));
    tdlib.listeners().subscribeToChatUpdates(chatId, this);
    topicsLoader = new DirectMessagesTopicsController.TopicsLoader(tdlib, chatId);
    topicsLoader.loadMore();
  }

  public static MessagesController newController (ViewController<?> context, TdApi.Chat chat, long topicId) {
    MessagesController c = new MessagesController(context.context(), context.tdlib());
    c.setArguments(new MessagesController.Arguments(context.tdlib(), null, chat, null,
      topicId != 0 ? new TdApi.MessageTopicDirectMessages(topicId) : null, null).topicTabs(null));
    return c;
  }

  @Override
  public boolean isSideTabs () {
    return false;
  }

  @Override
  public void destroy () {
    if (!isDestroyed) {
      isDestroyed = true;
      tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
    }
  }

  private boolean isActive () {
    return !isDestroyed && !owner.isDestroyed();
  }

  @Override
  public View getHeaderView () {
    if (headerView == null) {
      headerView = ChatTopicTabs.newHeaderView(owner, Screen.dp(56f), this);
      updateTabs();
    }
    return headerView;
  }

  private int selectedPosition () {
    for (int i = 0; i < topics.size(); i++) {
      if (topics.get(i).id == topicId) {
        return 1 + i;
      }
    }
    return 0;
  }

  private void updateTabs () {
    if (headerView == null) {
      return;
    }
    List<ViewPagerTopView.Item> items = new ArrayList<>(1 + topics.size());
    items.add(new ViewPagerTopView.Item(Lang.getString(R.string.DirectMessagesAll)));
    for (TdApi.DirectMessagesChatTopic topic : topics) {
      Counter counter = null;
      if (topic.unreadCount > 0 || topic.isMarkedAsUnread) {
        counter = new Counter.Builder().textSize(12f).backgroundPadding(4f).build();
        counter.setCount(topic.unreadCount > 0 ? topic.unreadCount : Tdlib.CHAT_MARKED_AS_UNREAD, tdlib.chatNeedsMuteIcon(chatId), false);
      }
      items.add(new ViewPagerTopView.Item(tdlib.senderName(topic.senderId, true), tdlib, topic.senderId, counter));
    }
    headerView.getTopView().setItems(items);
    headerView.getTopView().setSelectionFactor(selectedPosition());
  }

  /** Topics come one update each (hundreds while loading): the tabs are rebuilt at most every 300 ms. */
  @Override
  public void onDirectMessagesChatTopicUpdated (long chatId, TdApi.DirectMessagesChatTopic topic) {
    owner.runOnUiThreadOptional(() -> {
      if (topicsUpdatePending) {
        return;
      }
      topicsUpdatePending = true;
      owner.runOnUiThreadOptional(() -> {
        topicsUpdatePending = false;
        if (!isActive()) {
          return;
        }
        List<TdApi.DirectMessagesChatTopic> newTopics = tdlib.directMessagesChatTopics(this.chatId);
        boolean tabsChanged = !sameTabs(topics, newTopics);
        topics.clear();
        topics.addAll(newTopics);
        if (tabsChanged) {
          // Most updates change only the last message of a conversation
          updateTabs();
        }
      }, null, 300);
    });
  }

  /** Same tabs: the same conversations in the same order with the same counters. */
  private static boolean sameTabs (List<TdApi.DirectMessagesChatTopic> a, List<TdApi.DirectMessagesChatTopic> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      TdApi.DirectMessagesChatTopic x = a.get(i), y = b.get(i);
      if (x.id != y.id || x.unreadCount != y.unreadCount || x.isMarkedAsUnread != y.isMarkedAsUnread) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void onPagerItemClick (int index) {
    long newTopicId = index == 0 || index - 1 >= topics.size() ? 0 : topics.get(index - 1).id;
    if (index >= topics.size() - 5) {
      topicsLoader.loadMore();
    }
    if (newTopicId == topicId || isSwitching || !isActive()) {
      return;
    }
    MessagesController c = newController(owner, chat, newTopicId);
    c.setOpenedAsTab();
    if (ChatTopicTabs.replace(owner, c)) {
      isSwitching = true;
      if (headerView != null) {
        headerView.getTopView().setFromTo(selectedPosition(), index);
        headerView.getTopView().setSelectionFactor(index);
      }
    }
  }

  // Menu

  @Override
  public void fillMenuItems (HeaderView header, LinearLayout menu, int iconColorId) {
    header.addMoreButton(menu, owner);
  }

  @Override
  public boolean onMenuItemPressed (int id) {
    if (id != R.id.menu_btn_more) {
      return false;
    }
    IntList ids = new IntList(2);
    IntList icons = new IntList(2);
    StringList strings = new StringList(2);
    ids.append(R.id.btn_search);
    icons.append(R.drawable.baseline_search_24);
    strings.append(R.string.Search);
    ids.append(R.id.btn_viewAsTopics);
    icons.append(R.drawable.baseline_format_list_bulleted_type_24);
    strings.append(R.string.ViewAsTopics);
    owner.showMore(ids.get(), strings.get(), icons.get(), 0);
    return true;
  }

  @Override
  public boolean onMoreItemPressed (int id) {
    if (id != R.id.btn_viewAsTopics) {
      return false;
    }
    tdlib.settings().setForumViewPreference(chatId, TdlibSettingsManager.FORUM_VIEW_TOPICS);
    DirectMessagesTopicsController c = new DirectMessagesTopicsController(owner.context(), tdlib);
    c.setArguments(new DirectMessagesTopicsController.Arguments(chat));
    ChatTopicTabs.replace(owner, c);
    return true;
  }
}
