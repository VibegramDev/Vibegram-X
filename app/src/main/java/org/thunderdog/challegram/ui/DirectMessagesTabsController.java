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
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import org.thunderdog.challegram.widget.ViewPager;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerController;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Counter;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.collection.IntList;
import org.thunderdog.challegram.util.StringList;

/**
 * Channel direct messages chat administered by the user, as tabs (as in the official app): «All» with the whole chat,
 * then one tab per sender (avatar, name, unread count) with that conversation. See {@link DirectMessagesTopicsController}
 * for the list view.
 */
public class DirectMessagesTabsController extends ViewPagerController<DirectMessagesTabsController.Arguments> implements ChatListener, Menu, MoreDelegate {
  public static class Arguments {
    public final TdApi.Chat chat;

    public Arguments (TdApi.Chat chat) {
      this.chat = chat;
    }
  }

  private static final long ALL_ITEM_ID = 0;

  private TdApi.Chat chat;
  private long chatId;
  private final List<TdApi.DirectMessagesChatTopic> topics = new ArrayList<>();

  public DirectMessagesTabsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    this.chat = args.chat;
    this.chatId = args.chat.id;
    // Before the pager gets its adapter: changing the count later without notifying it crashes ViewPager.
    topics.clear();
    topics.addAll(tdlib.directMessagesChatTopics(chatId));
  }

  @Override
  public int getId () {
    return R.id.controller_directMessagesTabs;
  }

  @Override
  public long getChatId () {
    return chatId;
  }

  @Override
  public CharSequence getName () {
    return chat != null ? tdlib.chatTitle(chat) : "";
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  @Override
  protected int getTitleStyle () {
    return TITLE_STYLE_COMPACT;
  }

  @Override
  protected int getMenuButtonsWidth () {
    return Screen.dp(56f);
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    tdlib.listeners().subscribeToChatUpdates(chatId, this);
    topicsLoader = new DirectMessagesTopicsController.TopicsLoader(tdlib, chatId);
    topicsLoader.loadMore();
  }

  private DirectMessagesTopicsController.TopicsLoader topicsLoader;

  @Override
  public void onPageScrolled (int position, int actualPosition, float positionOffset, int positionOffsetPixels) {
    super.onPageScrolled(position, actualPosition, positionOffset, positionOffsetPixels);
    // Load more topics when the last loaded tabs are near
    if (topicsLoader != null && actualPosition >= getPagerItemCount() - 5) {
      topicsLoader.loadMore();
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
  }

  // Topics

  private boolean topicsUpdatePending;

  /** Topics come one update each (hundreds while loading): the tabs are rebuilt at most every 300 ms. */
  @Override
  public void onDirectMessagesChatTopicUpdated (long chatId, TdApi.DirectMessagesChatTopic topic) {
    runOnUiThreadOptional(() -> {
      if (topicsUpdatePending) {
        return;
      }
      topicsUpdatePending = true;
      runOnUiThreadOptional(() -> {
        topicsUpdatePending = false;
        if (isDestroyed()) {
          return;
        }
        List<TdApi.DirectMessagesChatTopic> newTopics = tdlib.directMessagesChatTopics(this.chatId);
        boolean tabsChanged = !sameTabs(topics, newTopics);
        topics.clear();
        topics.addAll(newTopics);
        if (tabsChanged) {
          // Most updates change only the last message of a conversation: the tabs are rebuilt only when they change
          notifyPagerItemPositionsChanged();
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
  protected int getPagerItemCount () {
    return 1 + topics.size();
  }

  @Override
  protected long getPagerItemId (int position) {
    return position == 0 ? ALL_ITEM_ID : topics.get(position - 1).id;
  }

  @Override
  protected @Nullable List<ViewPagerTopView.Item> getPagerSectionItems () {
    List<ViewPagerTopView.Item> items = new ArrayList<>(1 + topics.size());
    items.add(new ViewPagerTopView.Item(Lang.getString(R.string.DirectMessagesAll)));
    for (TdApi.DirectMessagesChatTopic topic : topics) {
      Counter counter = null;
      long unreadCount = topic.unreadCount;
      if (unreadCount > 0 || topic.isMarkedAsUnread) {
        counter = new Counter.Builder().textSize(12f).backgroundPadding(4f).build();
        counter.setCount(unreadCount > 0 ? unreadCount : Tdlib.CHAT_MARKED_AS_UNREAD, tdlib.chatNeedsMuteIcon(chatId), false);
      }
      items.add(new ViewPagerTopView.Item(tdlib.senderName(topic.senderId, true), tdlib, topic.senderId, counter));
    }
    return items;
  }

  @Override
  protected CharSequence[] getPagerSections () {
    return null;
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, int position) {
    MessagesController controller = new MessagesController(context, tdlib);
    TdApi.MessageTopic topicId = position == 0 ? null : new TdApi.MessageTopicDirectMessages(topics.get(position - 1).id);
    controller.setArguments(new MessagesController.Arguments(tdlib, null, chat, null, topicId, null));
    return controller;
  }

  @Override
  public View getViewForApplyingOffsets () {
    ViewController<?> c = getCachedControllerForPosition(getCurrentPagerItemPosition());
    return c != null ? c.getViewForApplyingOffsets() : null;
  }

  // Menu

  @Override
  public int getMenuId () {
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
      IntList ids = new IntList(2);
      IntList icons = new IntList(2);
      StringList strings = new StringList(2);
      ids.append(R.id.btn_viewAsTopics);
      icons.append(R.drawable.baseline_format_list_bulleted_type_24);
      strings.append(R.string.ViewAsTopics);
      showMore(ids.get(), strings.get(), icons.get(), 0);
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (id == R.id.btn_viewAsTopics) {
      tdlib.settings().setForumViewPreference(chatId, TdlibSettingsManager.FORUM_VIEW_TOPICS);
      DirectMessagesTopicsController c = new DirectMessagesTopicsController(context, tdlib);
      c.setArguments(new DirectMessagesTopicsController.Arguments(chat));
      c.addOneShotFocusListener(() -> c.destroyStackItemAt(c.stackSize() - 2));
      navigateTo(c);
    }
  }
}
