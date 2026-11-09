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

import android.content.Context;
import android.graphics.Color;
import android.text.TextPaint;
import android.util.LongSparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextEntityMessage;
import org.thunderdog.challegram.widget.TopicSideTabsView;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import tgx.td.ChatId;

/**
 * Topics of a forum (or of a chat with a bot that has topics) as tabs above or beside the chat, as in the
 * official app: «All» with the whole chat, then one tab per topic. Each tab is a regular {@link MessagesController}
 * on the navigation stack, opened with {@link TdApi.MessageTopicForum} like any topic; picking another tab replaces it.
 * The loaded topics move to the next screen with the tabs.
 */
public class ForumTopicTabs implements ChatTopicTabs, TdlibCache.SupergroupDataChangeListener, ChatListener, MessageListener, ViewPagerTopView.OnItemClickListener {
  private static final String TOPIC_ICON_PLACEHOLDER = "\u2B50";
  private static final float SIDE_TAB_TEXT_SIZE = 10f;
  /** Custom emoji of a topic in a side tab is as large as the coloured letter icons. */
  private static final float SIDE_TAB_EMOJI_TEXT_SIZE = 24f;

  private final MessagesController owner;
  private final Tdlib tdlib;
  private final TdApi.Chat chat;
  private final long chatId;
  /** Topic shown by {@link #owner}, 0 for «All». */
  private final int forumTopicId;
  private final boolean isBotForum;
  /** Tabs on the side instead of the header ({@link TdlibSettingsManager#FORUM_VIEW_SIDE_TABS}). */
  private final boolean isSideTabs;

  private final List<TdApi.ForumTopic> topics = new ArrayList<>();
  private boolean isLoading, hasMore;
  private int nextOffsetDate, nextOffsetForumTopicId;
  private long nextOffsetMessageId;
  private boolean knownIsForum = true;
  /** Topic to open once it's added: the one the user has just created. */
  private int pendingSelectTopicId;
  private boolean isSwitching, isDestroyed;

  private @Nullable ViewPagerHeaderViewCompact headerView;
  private @Nullable TopicSideTabsView sideTabs;
  /** Tabs by topic: a tab is built again only when its topic changes. */
  private final LongSparseArray<TopicSideTabsView.Tab> sideTabsCache = new LongSparseArray<>();

  public ForumTopicTabs (MessagesController owner, TdApi.Chat chat, int forumTopicId, @Nullable ChatTopicTabs previousTabs) {
    ForumTopicTabs previous = previousTabs instanceof ForumTopicTabs ? (ForumTopicTabs) previousTabs : null;
    this.owner = owner;
    this.tdlib = owner.tdlib();
    this.chat = chat;
    this.chatId = chat.id;
    this.forumTopicId = forumTopicId;
    this.isBotForum = tdlib.isBotForum(chatId);
    this.isSideTabs = tdlib.settings().getForumViewPreference(chatId) == TdlibSettingsManager.FORUM_VIEW_SIDE_TABS;
    TdApi.Supergroup supergroup = tdlib.chatToSupergroup(chatId);
    if (supergroup != null) {
      knownIsForum = supergroup.isForum;
    }
    if (ChatId.isSupergroup(chatId)) {
      tdlib.cache().subscribeToSupergroupUpdates(ChatId.toSupergroupId(chatId), this);
    }
    tdlib.listeners().subscribeToChatUpdates(chatId, this);
    tdlib.listeners().subscribeToMessageUpdates(chatId, this);
    if (previous != null && previous.chatId == chatId) {
      topics.addAll(previous.topics);
      hasMore = previous.hasMore;
      nextOffsetDate = previous.nextOffsetDate;
      nextOffsetMessageId = previous.nextOffsetMessageId;
      nextOffsetForumTopicId = previous.nextOffsetForumTopicId;
    } else {
      loadTopics();
    }
  }

  /** New screen of the chat with the tabs, showing the topic (null for «All»). */
  public static MessagesController newController (ViewController<?> context, TdApi.Chat chat, @Nullable TdApi.ForumTopic topic, int forumTopicId, @Nullable ChatTopicTabs previous) {
    MessagesController c = new MessagesController(context.context(), context.tdlib());
    MessagesController.Arguments args = new MessagesController.Arguments(context.tdlib(), null, chat, null,
      forumTopicId != 0 ? new TdApi.MessageTopicForum(forumTopicId) : null, null);
    if (topic != null) {
      args.setForumTopic(topic);
    }
    c.setArguments(args.topicTabs(previous));
    return c;
  }

  @Override
  public boolean isSideTabs () {
    return isSideTabs;
  }

  public boolean isBotForum () {
    return isBotForum;
  }

  @Override
  public void destroy () {
    if (isDestroyed) {
      return;
    }
    isDestroyed = true;
    if (ChatId.isSupergroup(chatId)) {
      tdlib.cache().unsubscribeFromSupergroupUpdates(ChatId.toSupergroupId(chatId), this);
    }
    tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId, this);
  }

  private boolean isActive () {
    return !isDestroyed && !owner.isDestroyed();
  }

  // Views

  @Override
  public View getHeaderView () {
    if (headerView == null) {
      headerView = ChatTopicTabs.newHeaderView(owner, getMenuButtonsWidth(), this);
      headerView.getTopView().setItems(buildTopItems());
      headerView.getTopView().setSelectionFactor(selectedPosition());
    }
    return headerView;
  }

  /** The chat with the column of tabs beside it. */
  @Override
  public View wrapContent (View content) {
    Context context = owner.context();
    sideTabs = new TopicSideTabsView(context, this::openTab);
    sideTabs.setTabs(buildSideTabs());
    sideTabs.setSelectedPosition(selectedPosition());

    ImageView topButton = new ImageView(context);
    topButton.setScaleType(ImageView.ScaleType.CENTER);
    topButton.setImageResource(R.drawable.baseline_tab_24);
    topButton.setColorFilter(Theme.getColor(ColorId.icon));
    owner.addThemeFilterListener(topButton, ColorId.icon);
    RippleSupport.setTransparentSelector(topButton);
    topButton.setOnClickListener(v -> switchTabsLayout(TdlibSettingsManager.FORUM_VIEW_TABS));

    FrameLayoutFix column = TopicSideTabsView.wrap(context, sideTabs, topButton);
    int width = Screen.dp(TopicSideTabsView.WIDTH);
    column.setLayoutParams(FrameLayoutFix.newParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT));
    ViewSupport.setThemedBackground(column, ColorId.filling, owner);

    FrameLayoutFix wrap = new FrameLayoutFix(context);
    wrap.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    if (Lang.rtl()) {
      params.rightMargin = width;
    } else {
      params.leftMargin = width;
    }
    content.setLayoutParams(params);
    wrap.addView(content);
    wrap.addView(column);
    return wrap;
  }

  private int selectedPosition () {
    if (forumTopicId == 0) {
      return 0;
    }
    int index = indexOfTopic(forumTopicId);
    return index != -1 ? 1 + index : 0;
  }

  private void onTabsChanged () {
    if (headerView != null) {
      headerView.getTopView().setItems(buildTopItems());
      headerView.getTopView().setSelectionFactor(selectedPosition());
    }
    if (sideTabs != null) {
      sideTabs.setTabs(buildSideTabs());
      sideTabs.setSelectedPosition(selectedPosition());
    }
  }

  private boolean tabsUpdatePending;

  /** Counters change with every message: the tabs are rebuilt at most every 300 ms. */
  private void scheduleTabsUpdate () {
    if (tabsUpdatePending) {
      return;
    }
    tabsUpdatePending = true;
    owner.runOnUiThreadOptional(() -> {
      tabsUpdatePending = false;
      if (isActive()) {
        onTabsChanged();
      }
    }, null, 300);
  }

  private List<ViewPagerTopView.Item> buildTopItems () {
    List<ViewPagerTopView.Item> items = new ArrayList<>(1 + topics.size());
    if (isBotForum) {
      // As in the official app, the whole chat of a bot is the tab where new topics start
      items.add(new ViewPagerTopView.Item(R.drawable.baseline_add_comment_24));
    } else {
      items.add(new ViewPagerTopView.Item(Lang.getString(R.string.DirectMessagesAll)));
    }
    for (TdApi.ForumTopic topic : topics) {
      items.add(newTopicItem(topic));
    }
    return items;
  }

  /** Tab of a topic: its custom emoji icon, name and unread counter, as in the official app. */
  private ViewPagerTopView.Item newTopicItem (TdApi.ForumTopic topic) {
    Counter counter = null;
    if (topic.unreadCount > 0) {
      counter = new Counter.Builder().textSize(12f).backgroundPadding(4f).build();
      counter.setCount(topic.unreadCount, tdlib.forumTopicNeedsMuteIcon(chatId, topic), false);
    }
    long customEmojiId = topic.info.icon != null ? topic.info.icon.customEmojiId : 0;
    if (customEmojiId == 0) {
      return new ViewPagerTopView.Item(topic.info.name, counter);
    }
    String label = TOPIC_ICON_PLACEHOLDER + " " + topic.info.name;
    TdApi.FormattedText text = new TdApi.FormattedText(label, new TdApi.TextEntity[] {
      new TdApi.TextEntity(0, TOPIC_ICON_PLACEHOLDER.length(), new TdApi.TextEntityTypeCustomEmoji(customEmojiId))
    });
    boolean animate = !Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI);
    return new ViewPagerTopView.Item(label, counter).setCustomEmojiText(tdlib, text, animate);
  }

  private List<TopicSideTabsView.Tab> buildSideTabs () {
    List<TopicSideTabsView.Tab> tabs = new ArrayList<>(1 + topics.size());
    if (isBotForum) {
      tabs.add(new TopicSideTabsView.Tab(0, null, null, null, 0, 0, R.drawable.baseline_add_comment_24, null, ""));
    } else {
      String all = Lang.getString(R.string.DirectMessagesAll);
      tabs.add(new TopicSideTabsView.Tab(0, newSideTabName(all), null, null, 0, 0, R.drawable.baseline_forum_24, null, all));
    }
    boolean noAnimations = Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI);
    TextPaint letterPaint = Paints.getMediumTextPaint(15f, Color.WHITE, false);
    for (TdApi.ForumTopic topic : topics) {
      TdApi.ForumTopicInfo info = topic.info;
      long customEmojiId = info.icon != null ? info.icon.customEmojiId : 0;
      int color = ForumTopicView.getTopicColor(info.icon != null ? info.icon.color : 0);
      boolean isMuted = tdlib.forumTopicNeedsMuteIcon(chatId, topic);
      String contentKey = info.name + '|' + customEmojiId + '|' + color + '|' + topic.unreadCount + '|' + isMuted;
      TopicSideTabsView.Tab cachedTab = sideTabsCache.get(info.forumTopicId);
      if (cachedTab != null && cachedTab.contentKey.equals(contentKey)) {
        tabs.add(cachedTab);
        continue;
      }
      Text emoji = null;
      String letter = null;
      float letterWidth = 0;
      if (customEmojiId != 0) {
        TdApi.TextEntity[] rawEntities = {
          new TdApi.TextEntity(0, TOPIC_ICON_PLACEHOLDER.length(), new TdApi.TextEntityTypeCustomEmoji(customEmojiId))
        };
        TextEntity[] entities = TextEntity.valueOf(tdlib, TOPIC_ICON_PLACEHOLDER, rawEntities, null);
        if (entities != null && noAnimations) {
          for (TextEntity entity : entities) {
            if (entity instanceof TextEntityMessage) {
              ((TextEntityMessage) entity).setForceDisableAnimations(true);
            }
          }
        }
        emoji = new Text.Builder(TOPIC_ICON_PLACEHOLDER, Screen.dp(100f), Paints.robotoStyleProvider(SIDE_TAB_EMOJI_TEXT_SIZE), TextColorSets.Regular.NORMAL)
          .entities(entities, (text, specificMedia) -> {
            if (sideTabs != null) {
              sideTabs.invalidate();
              for (int i = 0; i < sideTabs.getChildCount(); i++) {
                sideTabs.getChildAt(i).invalidate();
              }
            }
          })
          .singleLine()
          .noClickable()
          .build();
      } else {
        letter = ForumTopicView.getTopicLetter(info);
        letterWidth = letter != null ? U.measureText(letter, letterPaint) : 0;
      }
      Counter counter = null;
      if (topic.unreadCount > 0) {
        counter = new Counter.Builder().textSize(11f).backgroundPadding(3f).build();
        counter.setCount(topic.unreadCount, isMuted, false);
      }
      TopicSideTabsView.Tab tab = new TopicSideTabsView.Tab(info.forumTopicId, newSideTabName(info.name), emoji, letter, letterWidth, color, 0, counter, contentKey);
      sideTabsCache.put(info.forumTopicId, tab);
      tabs.add(tab);
    }
    return tabs;
  }

  private static Text newSideTabName (String name) {
    return new Text.Builder(name, TopicSideTabsView.getTextMaxWidth(), Paints.robotoStyleProvider(SIDE_TAB_TEXT_SIZE), TextColorSets.Regular.LIGHT)
      .allBold()
      .maxLineCount(3)
      .noClickable()
      .textFlags(Text.FLAG_ALIGN_CENTER)
      .build();
  }

  // Switching tabs

  @Override
  public void onPagerItemClick (int index) {
    openTab(index);
  }

  private void openTab (int position) {
    if (position == 0) {
      openTopic(null, 0);
    } else if (position - 1 < topics.size()) {
      TdApi.ForumTopic topic = topics.get(position - 1);
      openTopic(topic, topic.info.forumTopicId);
    }
    if (hasMore && !isLoading && position >= topics.size() - 3) {
      loadMoreTopics();
    }
  }

  private void openTopic (@Nullable TdApi.ForumTopic topic, int forumTopicId) {
    if (forumTopicId == this.forumTopicId || isSwitching || !isActive()) {
      return;
    }
    MessagesController c = newController(owner, chat, topic, forumTopicId, this);
    c.setOpenedAsTab();
    if (replaceOwner(c)) {
      isSwitching = true;
      int position = forumTopicId == 0 ? 0 : 1 + indexOfTopic(forumTopicId);
      if (headerView != null) {
        headerView.getTopView().setFromTo(selectedPosition(), position);
        headerView.getTopView().setSelectionFactor(position);
      }
      if (sideTabs != null) {
        sideTabs.setSelectedPosition(position);
      }
    }
  }

  private boolean replaceOwner (ViewController<?> c) {
    return ChatTopicTabs.replace(owner, c);
  }

  private boolean expectsCreatedTopic;

  /** A message sent to «All» of a chat with a bot is starting a new topic, which opens once created. */
  public void expectCreatedTopic (boolean expect) {
    this.expectsCreatedTopic = expect;
  }

  /** Opens the tab of the topic, once the topic is loaded if it isn't yet. */
  public void selectTopic (int forumTopicId) {
    pendingSelectTopicId = forumTopicId;
    selectPendingTopic();
    if (pendingSelectTopicId != 0) {
      loadTopics();
    }
  }

  private void selectPendingTopic () {
    if (pendingSelectTopicId == 0) {
      return;
    }
    int index = indexOfTopic(pendingSelectTopicId);
    if (index != -1) {
      pendingSelectTopicId = 0;
      TdApi.ForumTopic topic = topics.get(index);
      openTopic(topic, topic.info.forumTopicId);
    }
  }

  private int indexOfTopic (int forumTopicId) {
    for (int i = 0; i < topics.size(); i++) {
      if (topics.get(i).info.forumTopicId == forumTopicId) {
        return i;
      }
    }
    return -1;
  }

  @Nullable
  private TdApi.ForumTopic getCurrentTopic () {
    int index = forumTopicId != 0 ? indexOfTopic(forumTopicId) : -1;
    return index != -1 ? topics.get(index) : null;
  }

  /** Reopens the chat with the tabs on top, on the side or as the list. */
  private void switchTabsLayout (int viewPreference) {
    tdlib.settings().setForumViewPreference(chatId, viewPreference);
    ViewController<?> c;
    if (viewPreference == TdlibSettingsManager.FORUM_VIEW_TOPICS) {
      ForumTopicsController listController = new ForumTopicsController(owner.context(), tdlib);
      listController.setArguments(new ForumTopicsController.Arguments(chat));
      c = listController;
    } else {
      MessagesController messages = newController(owner, chat, getCurrentTopic(), forumTopicId, this);
      messages.setOpenedAsTab();
      c = messages;
    }
    replaceOwner(c);
  }

  // Topics

  private boolean isShownTopic (TdApi.ForumTopic topic) {
    // Chats with bots don't show the General topic, as in the official app
    return !(isBotForum && topic.info.isGeneral);
  }

  private void addShownTopics (TdApi.ForumTopic[] newTopics) {
    for (TdApi.ForumTopic topic : newTopics) {
      if (isShownTopic(topic)) {
        topics.add(topic);
      }
    }
  }

  private void loadTopics () {
    if (isLoading) {
      return;
    }
    isLoading = true;
    if (topics.isEmpty()) {
      List<TdApi.ForumTopic> cachedTopics = tdlib.getCachedForumTopics(chatId);
      if (cachedTopics != null && !cachedTopics.isEmpty()) {
        addShownTopics(cachedTopics.toArray(new TdApi.ForumTopic[0]));
        onTabsChanged();
      }
    }
    tdlib.send(new TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100), (forumTopics, error) -> owner.runOnUiThreadOptional(() -> {
      isLoading = false;
      if (forumTopics == null || !isActive()) {
        return;
      }
      topics.clear();
      addShownTopics(forumTopics.topics);
      setNextOffset(forumTopics);
      tdlib.updateForumTopicsCache(chatId, topics);
      onTabsChanged();
      selectPendingTopic();
    }));
  }

  private void loadMoreTopics () {
    if (isLoading || !hasMore) {
      return;
    }
    isLoading = true;
    tdlib.send(new TdApi.GetForumTopics(chatId, "", nextOffsetDate, nextOffsetMessageId, nextOffsetForumTopicId, 50), (forumTopics, error) -> owner.runOnUiThreadOptional(() -> {
      isLoading = false;
      if (forumTopics == null || !isActive()) {
        return;
      }
      for (TdApi.ForumTopic topic : forumTopics.topics) {
        if (isShownTopic(topic) && indexOfTopic(topic.info.forumTopicId) == -1) {
          topics.add(topic);
        }
      }
      setNextOffset(forumTopics);
      onTabsChanged();
    }));
  }

  private void setNextOffset (TdApi.ForumTopics forumTopics) {
    nextOffsetDate = forumTopics.nextOffsetDate;
    nextOffsetMessageId = forumTopics.nextOffsetMessageId;
    nextOffsetForumTopicId = forumTopics.nextOffsetForumTopicId;
    hasMore = forumTopics.topics.length > 0 && (nextOffsetDate != 0 || nextOffsetMessageId != 0 || nextOffsetForumTopicId != 0);
  }

  /** Read state of a topic changed: updateForumTopic has no unread count, so it's requested. */
  @Override
  public void onForumTopicUpdated (long chatId, long messageThreadId, boolean isPinned, long lastReadInboxMessageId, long lastReadOutboxMessageId, int unreadMentionCount, int unreadReactionCount, TdApi.ChatNotificationSettings notificationSettings, @Nullable TdApi.DraftMessage draftMessage) {
    if (chatId != this.chatId) {
      return;
    }
    final int forumTopicId = (int) messageThreadId;
    owner.runOnUiThreadOptional(() -> {
      int index = indexOfTopic(forumTopicId);
      if (!isActive() || index == -1) {
        return;
      }
      TdApi.ForumTopic topic = topics.get(index);
      boolean readChanged = topic.lastReadInboxMessageId != lastReadInboxMessageId;
      topic.lastReadInboxMessageId = lastReadInboxMessageId;
      if (topic.isPinned != isPinned) {
        // Pinned topics go first in the order of the server
        loadTopics();
      }
      if (notificationSettings != null) {
        // The counter turns grey in muted topics
        topic.notificationSettings = notificationSettings;
        scheduleTabsUpdate();
      }
      if (!readChanged || topic.unreadCount == 0) {
        return;
      }
      if (topic.lastMessage != null && lastReadInboxMessageId >= topic.lastMessage.id) {
        topic.unreadCount = 0;
        scheduleTabsUpdate();
        return;
      }
      tdlib.send(new TdApi.GetForumTopic(chatId, forumTopicId), (result, error) -> owner.runOnUiThreadOptional(() -> {
        int freshIndex = indexOfTopic(forumTopicId);
        if (result != null && isActive() && freshIndex != -1 && topics.get(freshIndex).unreadCount != result.unreadCount) {
          topics.get(freshIndex).unreadCount = result.unreadCount;
          scheduleTabsUpdate();
        }
      }));
    });
  }

  /** Topics created after the tabs were loaded, and counters of the loaded ones. */
  @Override
  public void onNewMessage (TdApi.Message message) {
    if (message.topicId == null || message.topicId.getConstructor() != TdApi.MessageTopicForum.CONSTRUCTOR) {
      return;
    }
    int forumTopicId = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
    boolean isCreated = message.isOutgoing && message.content.getConstructor() == TdApi.MessageForumTopicCreated.CONSTRUCTOR;
    owner.runOnUiThreadOptional(() -> {
      if (!isActive()) {
        return;
      }
      int index = indexOfTopic(forumTopicId);
      if (index != -1) {
        TdApi.ForumTopic topic = topics.get(index);
        topic.lastMessage = message;
        if (!message.isOutgoing && message.id > topic.lastReadInboxMessageId) {
          topic.unreadCount++;
          scheduleTabsUpdate();
        }
        return;
      }
      if (isCreated && expectsCreatedTopic) {
        // As in the official app, the topic started by a message sent here opens; not the ones created on other devices
        expectsCreatedTopic = false;
        pendingSelectTopicId = forumTopicId;
      }
      tdlib.send(new TdApi.GetForumTopic(chatId, forumTopicId), (topic, error) -> owner.runOnUiThreadOptional(() -> {
        if (topic == null || !isActive() || indexOfTopic(forumTopicId) != -1 || !isShownTopic(topic)) {
          return;
        }
        // The topic has the newest message: it goes right after the pinned ones
        int insertIndex = 0;
        while (insertIndex < topics.size() && topics.get(insertIndex).isPinned) {
          insertIndex++;
        }
        topics.add(insertIndex, topic);
        tdlib.updateForumTopicsCache(chatId, topics);
        onTabsChanged();
        selectPendingTopic();
      }));
    });
  }

  /** Tab titles follow renamed topics. */
  @Override
  public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
    if (info.chatId != chatId) {
      return;
    }
    owner.runOnUiThreadOptional(() -> {
      int index = isActive() ? indexOfTopic(info.forumTopicId) : -1;
      if (index != -1) {
        TdApi.ForumTopic topic = topics.get(index);
        boolean changed = !StringUtils.equalsOrBothEmpty(topic.info.name, info.name) ||
          (topic.info.icon != null ? topic.info.icon.customEmojiId : 0) != (info.icon != null ? info.icon.customEmojiId : 0);
        topic.info = info;
        if (changed) {
          onTabsChanged();
        }
      }
    });
  }

  @Override
  public void onSupergroupUpdated (TdApi.Supergroup supergroup) {
    owner.runOnUiThreadOptional(() -> {
      if (ChatId.toSupergroupId(chatId) != supergroup.id || !isActive()) {
        return;
      }
      // The chat opens without the tabs only when forum mode is turned off. The layout set by the admins (hasForumTabs) is ignored: the user chooses it here.
      boolean wasForum = knownIsForum;
      knownIsForum = supergroup.isForum;
      if (wasForum && !supergroup.isForum) {
        openWithoutTabs();
      }
    });
  }

  @Override
  public void onSupergroupFullUpdated (long supergroupId, TdApi.SupergroupFullInfo newSupergroupFull) { }

  private void openWithoutTabs () {
    MessagesController c = new MessagesController(owner.context(), tdlib);
    c.setArguments(new MessagesController.Arguments(tdlib, null, chat, null, null, null));
    replaceOwner(c);
  }

  // Menu

  public int getMenuButtonsWidth () {
    // The more button and the button that creates topics in chats with bots
    return Screen.dp(56f) + (canCreateBotTopics() ? Screen.dp(49f) : 0);
  }

  @Override
  public void fillMenuItems (HeaderView header, LinearLayout menu, int iconColorId) {
    if (canCreateBotTopics()) {
      header.addButton(menu, R.id.btn_createTopic, R.drawable.baseline_create_24, iconColorId, owner, Screen.dp(49f));
    }
    header.addMoreButton(menu, owner);
  }

  @Override
  public boolean onMenuItemPressed (int id) {
    if (id == R.id.btn_createTopic) {
      showCreateTopicDialog();
      return true;
    }
    if (id != R.id.menu_btn_more) {
      return false;
    }
    TdApi.ForumTopic topic = getCurrentTopic();
    IntList ids = new IntList(10);
    IntList icons = new IntList(10);
    StringList strings = new StringList(10);

    ids.append(R.id.btn_search);
    icons.append(R.drawable.baseline_search_24);
    strings.append(R.string.Search);

    boolean canManage = canManageTopics();
    // Topics of chats with bots can't be closed, the rest is up to the user
    boolean canEdit = canManage || canCreateBotTopics();

    if (topic != null) {
      boolean isMuted = tdlib.forumTopicNeedsMuteIcon(chatId, topic);
      ids.append(R.id.btn_notifications);
      icons.append(isMuted ? R.drawable.baseline_notifications_off_24 : R.drawable.baseline_notifications_24);
      strings.append(isMuted ? R.string.Unmute : R.string.Mute);

      if (canManage) {
        ids.append(topic.info.isClosed ? R.id.btn_reopenTopic : R.id.btn_closeTopic);
        icons.append(R.drawable.baseline_lock_24);
        strings.append(topic.info.isClosed ? R.string.ReopenTopic : R.string.CloseTopic);
      }
      if (canEdit) {
        ids.append(topic.isPinned ? R.id.btn_unpinTopic : R.id.btn_pinTopic);
        icons.append(topic.isPinned ? R.drawable.deproko_baseline_pin_undo_24 : R.drawable.deproko_baseline_pin_24);
        strings.append(topic.isPinned ? R.string.UnpinTopic : R.string.PinTopic);

        ids.append(R.id.btn_editTopic);
        icons.append(R.drawable.baseline_edit_24);
        strings.append(R.string.EditTopic);
      }
      if (canCreateBotTopics()) {
        ids.append(R.id.btn_deleteTopic);
        icons.append(R.drawable.baseline_delete_24);
        strings.append(R.string.DeleteTopic);
      }
    }

    if (canCreateTopics()) {
      ids.append(R.id.btn_createTopic);
      icons.append(R.drawable.baseline_add_24);
      strings.append(R.string.NewTopic);
    }

    if (canManage) {
      ids.append(R.id.btn_chatSettings);
      icons.append(R.drawable.baseline_info_24);
      strings.append(R.string.TabInfo);
    }

    // Tabs on the side or on top, as the button in the tabs of the official app
    ids.append(R.id.btn_sideTabs);
    icons.append(isSideTabs ? R.drawable.baseline_tab_24 : R.drawable.baseline_view_sidebar_24);
    strings.append(isSideTabs ? R.string.vg_TopicTabsOnTop : R.string.vg_TopicTabsOnSide);

    ids.append(R.id.btn_viewAsTopics);
    icons.append(R.drawable.baseline_format_list_bulleted_type_24);
    strings.append(R.string.ViewAsTopics);

    // Chats with bots have the «All» tab instead
    if (!isBotForum) {
      ids.append(R.id.btn_viewAsChat);
      icons.append(R.drawable.baseline_chat_bubble_24);
      strings.append(R.string.ViewAsChat);
    }

    owner.showMore(ids.get(), strings.get(), icons.get(), 0);
    return true;
  }

  @Override
  public boolean onMoreItemPressed (int id) {
    TdApi.ForumTopic topic = getCurrentTopic();
    if (id == R.id.btn_createTopic) {
      showCreateTopicDialog();
    } else if (id == R.id.btn_chatSettings) {
      ProfileController profileController = new ProfileController(owner.context(), tdlib);
      profileController.setArguments(new ProfileController.Args(chat, null, false));
      owner.navigateTo(profileController);
    } else if (id == R.id.btn_viewAsTopics) {
      switchTabsLayout(TdlibSettingsManager.FORUM_VIEW_TOPICS);
    } else if (id == R.id.btn_sideTabs) {
      switchTabsLayout(isSideTabs ? TdlibSettingsManager.FORUM_VIEW_TABS : TdlibSettingsManager.FORUM_VIEW_SIDE_TABS);
    } else if (id == R.id.btn_viewAsChat) {
      tdlib.send(new TdApi.ToggleChatViewAsTopics(chatId, false), (ok, error) -> owner.runOnUiThreadOptional(() -> {
        if (error != null) {
          UI.showError(error);
        } else if (isActive()) {
          openWithoutTabs();
        }
      }));
    } else if (topic != null && id == R.id.btn_notifications) {
      tdlib.ui().showForumTopicMuteOptions(owner, topic, null);
    } else if (topic != null && (id == R.id.btn_closeTopic || id == R.id.btn_reopenTopic)) {
      tdlib.send(new TdApi.ToggleForumTopicIsClosed(chatId, topic.info.forumTopicId, id == R.id.btn_closeTopic), tdlib.typedOkHandler());
    } else if (topic != null && (id == R.id.btn_pinTopic || id == R.id.btn_unpinTopic)) {
      tdlib.send(new TdApi.ToggleForumTopicIsPinned(chatId, topic.info.forumTopicId, id == R.id.btn_pinTopic), tdlib.typedOkHandler());
    } else if (topic != null && id == R.id.btn_editTopic) {
      editTopic(topic);
    } else if (topic != null && id == R.id.btn_deleteTopic) {
      deleteTopic(topic);
    } else {
      return false;
    }
    return true;
  }

  private void deleteTopic (TdApi.ForumTopic topic) {
    owner.showConfirm(Lang.getStringBold(R.string.DeleteTopicConfirm, topic.info.name), Lang.getString(R.string.Delete), R.drawable.baseline_delete_24, ViewController.OptionColor.RED, () ->
      tdlib.send(new TdApi.DeleteForumTopic(chatId, topic.info.forumTopicId), (ok, error) -> owner.runOnUiThreadOptional(() -> {
        if (error != null) {
          UI.showError(error);
          return;
        }
        int index = indexOfTopic(topic.info.forumTopicId);
        if (isActive() && index != -1) {
          topics.remove(index);
          tdlib.updateForumTopicsCache(chatId, topics);
          if (topic.info.forumTopicId == forumTopicId) {
            openTopic(null, 0);
          } else {
            onTabsChanged();
          }
        }
      }))
    );
  }

  private void editTopic (TdApi.ForumTopic topic) {
    owner.openInputAlert(Lang.getString(R.string.EditTopic), Lang.getString(R.string.TopicNameHint), R.string.Done, R.string.Cancel, topic.info.name, (inputView, result) -> {
      String name = result.trim();
      if (name.isEmpty() || name.length() > 128) {
        inputView.setInErrorState(true);
        return false;
      }
      // The name follows updateForumTopicInfo
      tdlib.send(new TdApi.EditForumTopic(chatId, topic.info.forumTopicId, name, false, 0), tdlib.typedOkHandler());
      return true;
    }, true);
  }

  private void showCreateTopicDialog () {
    owner.openInputAlert(Lang.getString(R.string.NewTopic), Lang.getString(R.string.TopicNameHint), R.string.Done, R.string.Cancel, null, (inputView, result) -> {
      String name = result.trim();
      if (name.isEmpty() || name.length() > 128) {
        inputView.setInErrorState(true);
        return false;
      }
      createTopic(name);
      return true;
    }, true);
  }

  /** Colors allowed for topic icons by the server. */
  private static final int[] TOPIC_COLORS = {0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F};

  private void createTopic (String name) {
    int color = TOPIC_COLORS[(int) (Math.random() * TOPIC_COLORS.length)];
    tdlib.send(new TdApi.CreateForumTopic(chatId, name, false, new TdApi.ForumTopicIcon(color, 0)), (info, error) -> owner.runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
      } else if (isActive()) {
        // As in the official app, the new topic opens
        selectTopic(info.forumTopicId);
      }
    }));
  }

  // Permissions

  private boolean canCreateBotTopics () {
    return isBotForum && tdlib.canManageBotForumTopics(chatId);
  }

  private boolean canCreateTopics () {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
      case TdApi.ChatMemberStatusMember.CONSTRUCTOR:
      case TdApi.ChatMemberStatusRestricted.CONSTRUCTOR:
        return chat.permissions != null && chat.permissions.canCreateTopics;
      default:
        return false;
    }
  }

  private boolean canManageTopics () {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
      default:
        return false;
    }
  }
}
