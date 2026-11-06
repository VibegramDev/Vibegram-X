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
 * File created for forum topics tabs support
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
import org.thunderdog.challegram.component.chat.ChatHeaderView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerController;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.telegram.TdlibUi;
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
import org.thunderdog.challegram.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;

import tgx.td.ChatId;

/**
 * Forum topics as tabs, after the «All» tab with the whole chat, as in the official app. Chats
 * with bots that have topics open here too: a message sent to their «All» starts a new topic
 * ({@link Tdlib#sendInNewBotForumTopic}).
 */
public class ForumTopicTabsController
  extends ViewPagerController<ForumTopicTabsController.Arguments>
  implements TdlibCache.SupergroupDataChangeListener, ChatListener, MessageListener, Menu,
  MoreDelegate {

  public static class Arguments {
    public final TdApi.Chat chat;

    public Arguments (TdApi.Chat chat) {
      this.chat = chat;
    }
  }

  private TdApi.Chat chat;
  private long chatId;
  private final List<TdApi.ForumTopic> topics = new ArrayList<>();
  private boolean isLoading;
  private boolean hasMore;
  private int nextOffsetDate, nextOffsetForumTopicId;
  private long nextOffsetMessageId;
  private boolean isSubscribedToUpdates;
  private boolean knownIsForum = true;

  private static final long ALL_ITEM_ID = 0;

  private boolean isBotForum;
  /** Tabs on the side instead of the header ({@link TdlibSettingsManager#FORUM_VIEW_SIDE_TABS}). */
  private boolean isSideTabs;
  private @Nullable TopicSideTabsView sideTabs;
  private @Nullable ChatHeaderView sideTabsHeader;
  /** Topic to open once it's added: the one the user has just created. */
  private int pendingSelectTopicId;

  public ForumTopicTabsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    this.chat = args.chat;
    this.chatId = args.chat.id;
    this.isBotForum = tdlib.isBotForum(chatId);
    this.isSideTabs = tdlib.settings().getForumViewPreference(chatId) ==
      TdlibSettingsManager.FORUM_VIEW_SIDE_TABS;
  }

  /** Opens the tab of the topic once the topics are loaded: the one open before a layout switch. */
  public void setInitialTopic (int forumTopicId) {
    this.pendingSelectTopicId = forumTopicId;
  }

  private void onTabsChanged () {
    notifyPagerItemPositionsChanged();
    if (sideTabs != null) {
      sideTabs.setTabs(buildSideTabs());
    }
  }

  @Override
  public View getCustomHeaderCell () {
    return isSideTabs ? sideTabsHeader : super.getCustomHeaderCell();
  }

  private void createSideTabs (Context context, FrameLayoutFix contentView, ViewPager pager) {
    sideTabsHeader = new ChatHeaderView(context, tdlib, this);
    sideTabsHeader.setCallback(() -> {
      ProfileController controller = new ProfileController(context, tdlib);
      controller.setShareCustomHeaderView(true);
      controller.setArguments(new ProfileController.Args(chat, null, false));
      navigateTo(controller);
    });
    sideTabsHeader.setInnerMargins(Screen.dp(56f), getMenuButtonsWidth());
    sideTabsHeader.setChat(tdlib, chat, null, null);
    if (isBotForum) {
      // Bots type in topics, and the tabs show the topics
      sideTabsHeader.removeChatStatus();
    }

    sideTabs = new TopicSideTabsView(context, position -> setCurrentPagerPosition(position, true));
    sideTabs.setTabs(buildSideTabs());

    ImageView topButton = new ImageView(context);
    topButton.setScaleType(ImageView.ScaleType.CENTER);
    topButton.setImageResource(R.drawable.baseline_tab_24);
    topButton.setColorFilter(Theme.getColor(ColorId.icon));
    addThemeFilterListener(topButton, ColorId.icon);
    RippleSupport.setTransparentSelector(topButton);
    topButton.setOnClickListener(v -> switchTabsLayout(TdlibSettingsManager.FORUM_VIEW_TABS));

    FrameLayoutFix column = TopicSideTabsView.wrap(context, sideTabs, topButton);
    int width = Screen.dp(TopicSideTabsView.WIDTH);
    column.setLayoutParams(FrameLayoutFix.newParams(width,
      ViewGroup.LayoutParams.MATCH_PARENT, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT));
    ViewSupport.setThemedBackground(column, ColorId.filling, this);
    contentView.addView(column);

    FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) pager.getLayoutParams();
    if (Lang.rtl()) {
      params.rightMargin = width;
    } else {
      params.leftMargin = width;
    }
    pager.setLayoutParams(params);
  }

  /** Reopens the topics with the tabs on top, on the side or as the list. */
  private void switchTabsLayout (int viewPreference) {
    tdlib.settings().setForumViewPreference(chatId, viewPreference);
    TdApi.ForumTopic topic = getCurrentTopic();
    ViewController<?> c;
    if (viewPreference == TdlibSettingsManager.FORUM_VIEW_TOPICS) {
      ForumTopicsController listController = new ForumTopicsController(context, tdlib);
      listController.setArguments(new ForumTopicsController.Arguments(chat));
      c = listController;
    } else {
      ForumTopicTabsController tabsController = new ForumTopicTabsController(context, tdlib);
      tabsController.setArguments(new Arguments(chat));
      if (topic != null) {
        tabsController.setInitialTopic(topic.info.forumTopicId);
      }
      c = tabsController;
    }
    c.addOneShotFocusListener(() -> c.destroyStackItemAt(c.stackSize() - 2));
    navigateTo(c);
  }

  private static final float SIDE_TAB_TEXT_SIZE = 10f;
  /** Custom emoji of a topic in a side tab is as large as the coloured letter icons. */
  private static final float SIDE_TAB_EMOJI_TEXT_SIZE = 24f;

  /** Tabs by topic: a tab is built again only when its topic changes. */
  private final LongSparseArray<TopicSideTabsView.Tab> sideTabsCache = new LongSparseArray<>();

  private List<TopicSideTabsView.Tab> buildSideTabs () {
    List<TopicSideTabsView.Tab> tabs = new ArrayList<>(1 + topics.size());
    if (isBotForum) {
      tabs.add(new TopicSideTabsView.Tab(ALL_ITEM_ID, null, null, null, 0, 0,
        R.drawable.baseline_add_comment_24, null, ""));
    } else {
      String all = Lang.getString(R.string.DirectMessagesAll);
      tabs.add(new TopicSideTabsView.Tab(ALL_ITEM_ID, newSideTabName(all), null, null, 0, 0,
        R.drawable.baseline_forum_24, null, all));
    }
    boolean noAnimations =
      Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI);
    TextPaint letterPaint = Paints.getMediumTextPaint(15f, Color.WHITE, false);
    for (TdApi.ForumTopic topic : topics) {
      TdApi.ForumTopicInfo info = topic.info;
      long customEmojiId = info.icon != null ? info.icon.customEmojiId : 0;
      int color = ForumTopicView.getTopicColor(info.icon != null ? info.icon.color : 0);
      boolean isMuted = tdlib.forumTopicNeedsMuteIcon(chatId, topic);
      String contentKey = info.name + '|' + customEmojiId + '|' + color + '|' +
        topic.unreadCount + '|' + isMuted;
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
          new TdApi.TextEntity(0, TOPIC_ICON_PLACEHOLDER.length(),
            new TdApi.TextEntityTypeCustomEmoji(customEmojiId))
        };
        TextEntity[] entities =
          TextEntity.valueOf(tdlib, TOPIC_ICON_PLACEHOLDER, rawEntities, null);
        if (entities != null && noAnimations) {
          for (TextEntity entity : entities) {
            if (entity instanceof TextEntityMessage) {
              ((TextEntityMessage) entity).setForceDisableAnimations(true);
            }
          }
        }
        emoji = new Text.Builder(TOPIC_ICON_PLACEHOLDER, Screen.dp(100f),
          Paints.robotoStyleProvider(SIDE_TAB_EMOJI_TEXT_SIZE), TextColorSets.Regular.NORMAL)
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
      TopicSideTabsView.Tab tab = new TopicSideTabsView.Tab(info.forumTopicId,
        newSideTabName(info.name), emoji, letter, letterWidth, color, 0, counter, contentKey);
      sideTabsCache.put(info.forumTopicId, tab);
      tabs.add(tab);
    }
    return tabs;
  }

  private static Text newSideTabName (String name) {
    return new Text.Builder(name, TopicSideTabsView.getTextMaxWidth(),
      Paints.robotoStyleProvider(SIDE_TAB_TEXT_SIZE), TextColorSets.Regular.LIGHT)
      .allBold()
      .maxLineCount(3)
      .noClickable()
      .textFlags(Text.FLAG_ALIGN_CENTER)
      .build();
  }

  /** Number of tabs before the topics: «All», the whole chat, as in the official app. */
  private static int topicsOffset () {
    return 1;
  }

  /** Chats with bots don't show the General topic, as in the official app. */
  private boolean isShownTopic (TdApi.ForumTopic topic) {
    return !(isBotForum && topic.info.isGeneral);
  }

  @Override
  public int getId () {
    return R.id.controller_forumTopicTabs;
  }

  @Override
  public long getChatId () {
    return chatId;
  }

  @Override
  public CharSequence getName () {
    return chat != null ? chat.title : "";
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
    // Width for the more button (3 dots) and the button that creates topics in chats with bots
    return Screen.dp(56f) + (canCreateBotTopics() ? Screen.dp(49f) : 0);
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    if (isSideTabs) {
      createSideTabs(context, contentView, pager);
    }
    loadTopics();
  }

  @Override
  public void onPageSelected (int position, int actualPosition) {
    if (sideTabs != null) {
      sideTabs.setSelectedPosition(position);
    }
  }

  private void loadTopics () {
    if (isLoading) return;
    isLoading = true;

    if (!isSubscribedToUpdates) {
      TdApi.Supergroup supergroup = tdlib.chatToSupergroup(chatId);
      if (supergroup != null) {
        knownIsForum = supergroup.isForum;
      }
      if (ChatId.isSupergroup(chatId)) {
        tdlib.cache().subscribeToSupergroupUpdates(ChatId.toSupergroupId(chatId), this);
      }
      tdlib.listeners().subscribeToChatUpdates(chatId, this);
      tdlib.listeners().subscribeToMessageUpdates(chatId, this);
      isSubscribedToUpdates = true;
    }

    // First, try to show cached topics immediately for instant display
    java.util.List<TdApi.ForumTopic> cachedTopics = tdlib.getCachedForumTopics(chatId);
    if (cachedTopics != null && !cachedTopics.isEmpty()) {
      topics.clear();
      addShownTopics(cachedTopics);
      // Rebuild the pager with cached topics immediately
      onTabsChanged();
    }

    // Then refresh from network in background
    tdlib.client().send(new TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100), result -> {
      if (result.getConstructor() == TdApi.ForumTopics.CONSTRUCTOR) {
        TdApi.ForumTopics forumTopics = (TdApi.ForumTopics) result;
        tdlib.ui().post(() -> {
          if (isDestroyed()) return;
          topics.clear();
          addShownTopics(java.util.Arrays.asList(forumTopics.topics));
          setNextOffset(forumTopics);
          // Update cache
          tdlib.updateForumTopicsCache(chatId, topics);
          isLoading = false;

          // Rebuild the pager with topics
          if (!topics.isEmpty()) {
            onTabsChanged();
          }
          selectPendingTopic();
        });
      } else {
        tdlib.ui().post(() -> isLoading = false);
      }
    });
  }

  private void addShownTopics (List<TdApi.ForumTopic> newTopics) {
    for (TdApi.ForumTopic topic : newTopics) {
      if (isShownTopic(topic)) {
        topics.add(topic);
      }
    }
  }

  @Override
  protected int getPagerItemCount () {
    return topicsOffset() + topics.size();
  }

  @Override
  protected long getPagerItemId (int position) {
    if (position == 0) {
      return ALL_ITEM_ID;
    }
    int index = position - topicsOffset();
    if (index < topics.size()) {
      return topics.get(index).info.forumTopicId;
    }
    return position;
  }

  @Override
  protected @Nullable List<ViewPagerTopView.Item> getPagerSectionItems () {
    if (isSideTabs) {
      return null;
    }
    List<ViewPagerTopView.Item> items = new ArrayList<>();
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

  private static final String TOPIC_ICON_PLACEHOLDER = "\u2B50";

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
      new TdApi.TextEntity(0, TOPIC_ICON_PLACEHOLDER.length(),
        new TdApi.TextEntityTypeCustomEmoji(customEmojiId))
    });
    boolean animate = !Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI);
    return new ViewPagerTopView.Item(label, counter).setCustomEmojiText(tdlib, text, animate);
  }

  private boolean tabsUpdatePending;

  /** Counters change with every message: the tabs are rebuilt at most every 300 ms. */
  private void scheduleTabsUpdate () {
    if (tabsUpdatePending) {
      return;
    }
    tabsUpdatePending = true;
    runOnUiThreadOptional(() -> {
      tabsUpdatePending = false;
      onTabsChanged();
    }, null, 300);
  }

  /** Read state of a topic changed: updateForumTopic has no unread count, so it's requested. */
  @Override
  public void onForumTopicUpdated (long chatId, long messageThreadId, boolean isPinned,
    long lastReadInboxMessageId, long lastReadOutboxMessageId, int unreadMentionCount,
    int unreadReactionCount, TdApi.ChatNotificationSettings notificationSettings,
    @Nullable TdApi.DraftMessage draftMessage) {
    if (chatId != this.chatId) {
      return;
    }
    final int forumTopicId = (int) messageThreadId;
    tdlib.ui().post(() -> {
      int index = indexOfTopic(forumTopicId);
      if (isDestroyed() || index == -1) {
        return;
      }
      TdApi.ForumTopic topic = topics.get(index);
      boolean readChanged = topic.lastReadInboxMessageId != lastReadInboxMessageId;
      topic.lastReadInboxMessageId = lastReadInboxMessageId;
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
      tdlib.client().send(new TdApi.GetForumTopic(chatId, forumTopicId), result -> {
        if (result.getConstructor() != TdApi.ForumTopic.CONSTRUCTOR) {
          return;
        }
        int unreadCount = ((TdApi.ForumTopic) result).unreadCount;
        tdlib.ui().post(() -> {
          int freshIndex = indexOfTopic(forumTopicId);
          if (!isDestroyed() && freshIndex != -1 &&
            topics.get(freshIndex).unreadCount != unreadCount) {
            topics.get(freshIndex).unreadCount = unreadCount;
            scheduleTabsUpdate();
          }
        });
      });
    });
  }

  @Override
  protected String[] getPagerSections () {
    // Using getPagerSectionItems instead
    return null;
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, int position) {
    if (position == 0) {
      MessagesController controller = new MessagesController(context, tdlib);
      controller.setArguments(
        new MessagesController.Arguments(tdlib, null, chat, null, null, null)
      );
      return controller;
    }

    TdApi.ForumTopic topic = topics.get(position - topicsOffset());
    MessagesController controller = new MessagesController(context, tdlib);

    MessagesController.Arguments args = new MessagesController.Arguments(
      tdlib,
      null, // chatList
      chat,
      null, // threadInfo
      new TdApi.MessageTopicForum(topic.info.forumTopicId),
      null // filter
    );
    args.setForumTopic(topic);
    controller.setArguments(args);

    return controller;
  }

  @Override
  public View getViewForApplyingOffsets () {
    ViewController<?> c = getCachedControllerForPosition(getCurrentPagerItemPosition());
    return c != null ? c.getViewForApplyingOffsets() : null;
  }

  @Override
  public void onPageScrolled (int position, int actualPosition, float positionOffset, int positionOffsetPixels) {
    // Load more topics when scrolling near the end
    if (hasMore && !isLoading && actualPosition - topicsOffset() >= topics.size() - 3) {
      loadMoreTopics();
    }
  }

  private void loadMoreTopics () {
    if (isLoading || !hasMore) return;
    isLoading = true;

    tdlib.client().send(new TdApi.GetForumTopics(chatId, "", nextOffsetDate, nextOffsetMessageId, nextOffsetForumTopicId, 50), result -> {
      if (result.getConstructor() == TdApi.ForumTopics.CONSTRUCTOR) {
        TdApi.ForumTopics forumTopics = (TdApi.ForumTopics) result;
        tdlib.ui().post(() -> {
          if (isDestroyed()) return;
          for (TdApi.ForumTopic topic : forumTopics.topics) {
            if (isShownTopic(topic) && indexOfTopic(topic.info.forumTopicId) == -1) {
              topics.add(topic);
            }
          }
          setNextOffset(forumTopics);
          isLoading = false;

          // Update the pager
          onTabsChanged();
        });
      } else {
        tdlib.ui().post(() -> isLoading = false);
      }
    });
  }

  private void setNextOffset (TdApi.ForumTopics forumTopics) {
    nextOffsetDate = forumTopics.nextOffsetDate;
    nextOffsetMessageId = forumTopics.nextOffsetMessageId;
    nextOffsetForumTopicId = forumTopics.nextOffsetForumTopicId;
    hasMore = forumTopics.topics.length > 0 && (nextOffsetDate != 0 || nextOffsetMessageId != 0 || nextOffsetForumTopicId != 0);
  }

  // MessageListener: topics created after the tabs were loaded

  @Override
  public void onNewMessage (TdApi.Message message) {
    if (message.topicId == null ||
      message.topicId.getConstructor() != TdApi.MessageTopicForum.CONSTRUCTOR) {
      return;
    }
    int forumTopicId = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
    boolean isCreatedByUser = message.isOutgoing &&
      message.content.getConstructor() == TdApi.MessageForumTopicCreated.CONSTRUCTOR;
    tdlib.ui().post(() -> {
      if (isDestroyed()) {
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
      if (isCreatedByUser) {
        // As in the official app, the topic the user has just created opens
        pendingSelectTopicId = forumTopicId;
      }
      tdlib.client().send(new TdApi.GetForumTopic(chatId, forumTopicId), result -> {
        if (result.getConstructor() != TdApi.ForumTopic.CONSTRUCTOR) {
          return;
        }
        TdApi.ForumTopic topic = (TdApi.ForumTopic) result;
        tdlib.ui().post(() -> {
          if (isDestroyed() || indexOfTopic(forumTopicId) != -1 || !isShownTopic(topic)) {
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
        });
      });
    });
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
      setCurrentPagerPosition(topicsOffset() + index, true);
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

  // ChatListener (ForumTopicInfoListener): tab titles follow renamed topics

  @Override
  public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
    if (info.chatId != chatId) return;
    tdlib.ui().post(() -> {
      if (isDestroyed()) return;
      int index = indexOfTopic(info.forumTopicId);
      if (index != -1) {
        TdApi.ForumTopic topic = topics.get(index);
        boolean nameChanged = !StringUtils.equalsOrBothEmpty(topic.info.name, info.name);
        topic.info = info;
        if (nameChanged) {
          onTabsChanged();
        }
      }
    });
  }

  // TdlibCache.SupergroupDataChangeListener implementation
  @Override
  public void onSupergroupUpdated (TdApi.Supergroup supergroup) {
    tdlib.ui().post(() -> {
      if (ChatId.toSupergroupId(chatId) == supergroup.id && !isDestroyed()) {
        // Reopen the chat only when forum mode is turned off, not on any supergroup update.
        // The layout set by the admins (hasForumTabs) is ignored: the user chooses it here.
        final boolean wasForum = knownIsForum;
        knownIsForum = supergroup.isForum;
        if (wasForum && !supergroup.isForum && chat != null) {
          // Forum mode was disabled - navigate to regular chat view
          navigateBack();
          tdlib.ui().post(() -> {
            if (!isDestroyed()) {
              tdlib.ui().openChat(this, chat.id, new TdlibUi.ChatOpenParameters().keepStack());
            }
          });
        }
      }
    });
  }

  @Override
  public void onSupergroupFullUpdated (long supergroupId, TdApi.SupergroupFullInfo newSupergroupFull) {
    // Not used
  }

  // Menu implementation
  @Override
  public int getMenuId () {
    return R.id.menu_search;
  }

  @Override
  protected int getSearchMenuId () {
    return R.id.menu_clear;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_search) {
      if (canCreateBotTopics()) {
        header.addButton(menu, R.id.btn_createTopic, R.drawable.baseline_create_24,
          getHeaderIconColorId(), this, Screen.dp(49f));
      }
      header.addMoreButton(menu, this);
    } else if (id == R.id.menu_clear) {
      header.addClearButton(menu, this);
    }
  }

  @Nullable
  private TdApi.ForumTopic getCurrentTopic () {
    int index = getCurrentPagerItemPosition() - topicsOffset();
    if (index >= 0 && index < topics.size()) {
      return topics.get(index);
    }
    return null;
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_clear) {
      clearSearchInput();
    } else if (id == R.id.btn_createTopic) {
      showCreateTopicDialog();
    } else if (id == R.id.menu_btn_more) {
      TdApi.ForumTopic topic = getCurrentTopic();

      IntList ids = new IntList(8);
      IntList icons = new IntList(8);
      StringList strings = new StringList(8);

      // Search option
      ids.append(R.id.menu_btn_search);
      icons.append(R.drawable.baseline_search_24);
      strings.append(R.string.Search);

      boolean canManage = canManageTopics();
      // Topics of chats with bots can't be closed, the rest is up to the user
      boolean canEdit = canManage || canCreateBotTopics();

      // Topic-specific options (only if we have a valid topic)
      if (topic != null) {
        // Notifications (always available for members)
        boolean isMuted = topic.notificationSettings != null && topic.notificationSettings.muteFor > 0;
        ids.append(R.id.btn_notifications);
        icons.append(isMuted ? R.drawable.baseline_notifications_off_24 : R.drawable.baseline_notifications_24);
        strings.append(isMuted ? R.string.Unmute : R.string.Mute);

        // Admin-only actions
        if (canManage) {
          // Close/Reopen
          if (topic.info.isClosed) {
            ids.append(R.id.btn_reopenTopic);
            icons.append(R.drawable.baseline_lock_24);
            strings.append(R.string.ReopenTopic);
          } else {
            ids.append(R.id.btn_closeTopic);
            icons.append(R.drawable.baseline_lock_24);
            strings.append(R.string.CloseTopic);
          }
        }

        if (canEdit) {
          // Pin/Unpin
          if (topic.isPinned) {
            ids.append(R.id.btn_unpinTopic);
            icons.append(R.drawable.deproko_baseline_pin_undo_24);
            strings.append(R.string.UnpinTopic);
          } else {
            ids.append(R.id.btn_pinTopic);
            icons.append(R.drawable.deproko_baseline_pin_24);
            strings.append(R.string.PinTopic);
          }

          // Edit
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

      // Create topic option (only if user has permission)
      if (canCreateTopics()) {
        ids.append(R.id.btn_createTopic);
        icons.append(R.drawable.baseline_add_24);
        strings.append(R.string.NewTopic);
      }

      // Group info option (admin only)
      if (canManage) {
        ids.append(R.id.btn_chatSettings);
        icons.append(R.drawable.baseline_info_24);
        strings.append(R.string.TabInfo);
      }

      // Tabs on the side or on top, as the button in the tabs of the official app
      ids.append(R.id.btn_sideTabs);
      icons.append(isSideTabs ? R.drawable.baseline_tab_24 : R.drawable.baseline_view_sidebar_24);
      strings.append(isSideTabs ? R.string.vg_TopicTabsOnTop : R.string.vg_TopicTabsOnSide);

      // View as topics list option (switch to ForumTopicsController)
      ids.append(R.id.btn_viewAsTopics);
      icons.append(R.drawable.baseline_format_list_bulleted_type_24);
      strings.append(R.string.ViewAsTopics);

      // View as chat option (chats with bots have the «All» tab instead)
      if (!isBotForum) {
        ids.append(R.id.btn_viewAsChat);
        icons.append(R.drawable.baseline_chat_bubble_24);
        strings.append(R.string.ViewAsChat);
      }

      showMore(ids.get(), strings.get(), icons.get(), 0);
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    TdApi.ForumTopic topic = getCurrentTopic();

    if (id == R.id.menu_btn_search) {
      // Switch to ForumTopicsController which has full search functionality
      ForumTopicsController listController = new ForumTopicsController(context, tdlib);
      listController.setArguments(new ForumTopicsController.Arguments(chat));
      navigateTo(listController);
    } else if (id == R.id.btn_createTopic) {
      showCreateTopicDialog();
    } else if (id == R.id.btn_chatSettings) {
      // Open chat profile/settings
      ProfileController profileController = new ProfileController(context, tdlib);
      profileController.setArguments(new ProfileController.Args(chat, null, false));
      navigateTo(profileController);
    } else if (id == R.id.btn_viewAsTopics) {
      switchTabsLayout(TdlibSettingsManager.FORUM_VIEW_TOPICS);
    } else if (id == R.id.btn_sideTabs) {
      switchTabsLayout(isSideTabs ?
        TdlibSettingsManager.FORUM_VIEW_TABS : TdlibSettingsManager.FORUM_VIEW_SIDE_TABS);
    } else if (id == R.id.btn_viewAsChat) {
      // Set viewAsTopics to false and open as unified chat
      tdlib.client().send(new TdApi.ToggleChatViewAsTopics(chatId, false), result -> {
        if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
          tdlib.ui().post(() -> {
            // Create the messages controller and replace current controller
            MessagesController c = new MessagesController(context, tdlib);
            c.setArguments(new MessagesController.Arguments(tdlib, null, chat, null, null, null));

            // Navigate to messages controller and remove this controller from stack after navigation
            c.addOneShotFocusListener(() -> {
              // Destroy the ForumTopicTabsController from the stack (it's now at position stackSize - 2)
              c.destroyStackItemAt(c.stackSize() - 2);
            });
            navigateTo(c);
          });
        }
      });
    } else if (topic != null) {
      if (id == R.id.btn_notifications) {
        showTopicMuteOptions(topic);
      } else if (id == R.id.btn_closeTopic) {
        toggleTopicClosed(topic, true);
      } else if (id == R.id.btn_reopenTopic) {
        toggleTopicClosed(topic, false);
      } else if (id == R.id.btn_pinTopic) {
        toggleTopicPinned(topic, true);
      } else if (id == R.id.btn_unpinTopic) {
        toggleTopicPinned(topic, false);
      } else if (id == R.id.btn_editTopic) {
        editTopic(topic);
      } else if (id == R.id.btn_deleteTopic) {
        deleteTopic(topic);
      }
    }
  }

  private void deleteTopic (TdApi.ForumTopic topic) {
    showConfirm(
      Lang.getStringBold(R.string.DeleteTopicConfirm, topic.info.name),
      Lang.getString(R.string.Delete),
      R.drawable.baseline_delete_24,
      OptionColor.RED,
      () -> {
        TdApi.DeleteForumTopic function =
          new TdApi.DeleteForumTopic(topic.info.chatId, topic.info.forumTopicId);
        tdlib.client().send(function, result -> tdlib.ui().post(() -> {
          if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            UI.showError(result);
            return;
          }
          int index = indexOfTopic(topic.info.forumTopicId);
          if (!isDestroyed() && index != -1) {
            topics.remove(index);
            tdlib.updateForumTopicsCache(chatId, topics);
            onTabsChanged();
          }
        }));
      }
    );
  }

  private void toggleTopicPinned (TdApi.ForumTopic topic, boolean pinned) {
    tdlib.client().send(new TdApi.ToggleForumTopicIsPinned(topic.info.chatId, topic.info.forumTopicId, pinned), result -> {
      if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
        // Update local state
        topic.isPinned = pinned;
      } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        UI.post(() -> UI.showError(result));
      }
    });
  }

  private void toggleTopicClosed (TdApi.ForumTopic topic, boolean closed) {
    tdlib.client().send(new TdApi.ToggleForumTopicIsClosed(topic.info.chatId, topic.info.forumTopicId, closed), result -> {
      if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
        // Update local state
        topic.info.isClosed = closed;
      } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        UI.post(() -> UI.showError(result));
      }
    });
  }

  private void editTopic (TdApi.ForumTopic topic) {
    openInputAlert(
      Lang.getString(R.string.EditTopic),
      Lang.getString(R.string.TopicNameHint),
      R.string.Done,
      R.string.Cancel,
      topic.info.name,
      (inputView, result) -> {
        String newName = result.trim();
        if (newName.isEmpty()) {
          inputView.setInErrorState(true);
          return false;
        }
        if (newName.length() > 128) {
          inputView.setInErrorState(true);
          return false;
        }
        // Edit the topic with new name, keep existing icon
        tdlib.client().send(new TdApi.EditForumTopic(
          topic.info.chatId,
          topic.info.forumTopicId,
          newName,
          false, // editIconCustomEmoji
          0 // iconCustomEmojiId (not changing)
        ), result1 -> {
          UI.post(() -> {
            if (result1.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
              // Update local topic name and refresh tabs
              topic.info.name = newName;
              onTabsChanged();
            } else if (result1.getConstructor() == TdApi.Error.CONSTRUCTOR) {
              UI.showError(result1);
            }
          });
        });
        return true;
      },
      true
    );
  }

  private void showTopicMuteOptions (TdApi.ForumTopic topic) {
    boolean isMuted = topic.notificationSettings != null && topic.notificationSettings.muteFor > 0;

    IntList ids = new IntList(5);
    IntList icons = new IntList(5);
    ArrayList<String> strings = new ArrayList<>();

    if (isMuted) {
      // Currently muted - show unmute option
      ids.append(R.id.btn_menu_enable);
      icons.append(R.drawable.baseline_notifications_24);
      strings.add(Lang.getString(R.string.EnableNotifications));
    } else {
      // Currently unmuted - show mute options
      ids.append(R.id.btn_menu_1hour);
      icons.append(R.drawable.baseline_notifications_paused_24);
      strings.add(Lang.plural(R.string.MuteForXHours, 1));

      ids.append(R.id.btn_menu_8hours);
      icons.append(R.drawable.baseline_notifications_paused_24);
      strings.add(Lang.plural(R.string.MuteForXHours, 8));

      ids.append(R.id.btn_menu_2days);
      icons.append(R.drawable.baseline_notifications_paused_24);
      strings.add(Lang.plural(R.string.MuteForXDays, 2));

      ids.append(R.id.btn_menu_disable);
      icons.append(R.drawable.baseline_notifications_off_24);
      strings.add(Lang.getString(R.string.MuteForever));
    }

    showOptions(topic.info.name, ids.get(), strings.toArray(new String[0]), null, icons.get(), (itemView, itemId) -> {
      int muteFor = 0;
      if (itemId == R.id.btn_menu_enable) {
        muteFor = 0; // Unmute
      } else if (itemId == R.id.btn_menu_1hour) {
        muteFor = (int) java.util.concurrent.TimeUnit.HOURS.toSeconds(1);
      } else if (itemId == R.id.btn_menu_8hours) {
        muteFor = (int) java.util.concurrent.TimeUnit.HOURS.toSeconds(8);
      } else if (itemId == R.id.btn_menu_2days) {
        muteFor = (int) java.util.concurrent.TimeUnit.DAYS.toSeconds(2);
      } else if (itemId == R.id.btn_menu_disable) {
        muteFor = Integer.MAX_VALUE; // Mute forever
      }

      setForumTopicMuteFor(topic, muteFor);
      return true;
    });
  }

  private void setForumTopicMuteFor (TdApi.ForumTopic topic, int muteFor) {
    TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings();
    settings.useDefaultMuteFor = (muteFor == 0);
    settings.muteFor = muteFor;
    settings.useDefaultSound = true;
    settings.useDefaultShowPreview = true;
    settings.useDefaultMuteStories = true;
    settings.useDefaultStorySound = true;
    settings.useDefaultDisablePinnedMessageNotifications = true;
    settings.useDefaultDisableMentionNotifications = true;

    tdlib.client().send(new TdApi.SetForumTopicNotificationSettings(
      topic.info.chatId,
      topic.info.forumTopicId,
      settings
    ), result -> {
      UI.post(() -> {
        if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
          // Update local state
          if (topic.notificationSettings == null) {
            topic.notificationSettings = new TdApi.ChatNotificationSettings();
          }
          topic.notificationSettings.muteFor = muteFor;
          topic.notificationSettings.useDefaultMuteFor = (muteFor == 0);
        } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.showError(result);
        }
      });
    });
  }

  private void showCreateTopicDialog () {
    openInputAlert(
      Lang.getString(R.string.NewTopic),
      Lang.getString(R.string.TopicNameHint),
      R.string.Done,
      R.string.Cancel,
      null,
      (inputView, result) -> {
        String name = result.trim();
        if (name.isEmpty()) {
          inputView.setInErrorState(true);
          return false;
        }
        if (name.length() > 128) {
          inputView.setInErrorState(true);
          return false;
        }
        createTopic(name);
        return true;
      },
      true
    );
  }

  private void createTopic (String name) {
    // Standard topic colors from Telegram
    int[] topicColors = {
      0x6FB9F0, // Blue
      0xFFD67E, // Yellow
      0xCB86DB, // Purple
      0x8EEE98, // Green
      0xFF93B2, // Pink
      0xFB6F5F  // Red
    };
    // Pick a random color
    int color = topicColors[(int) (Math.random() * topicColors.length)];

    TdApi.ForumTopicIcon icon = new TdApi.ForumTopicIcon(color, 0);

    tdlib.client().send(new TdApi.CreateForumTopic(chatId, name, false, icon), result -> {
      UI.post(() -> {
        if (result.getConstructor() == TdApi.ForumTopicInfo.CONSTRUCTOR) {
          // Reload topics to show the new one
          loadTopics();
        } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.showError(result);
        }
      });
    });
  }

  @Override
  public void destroy () {
    super.destroy();
    if (isSubscribedToUpdates) {
      if (ChatId.isSupergroup(chatId)) {
        tdlib.cache().unsubscribeFromSupergroupUpdates(ChatId.toSupergroupId(chatId), this);
      }
      tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
      tdlib.listeners().unsubscribeFromMessageUpdates(chatId, this);
      isSubscribedToUpdates = false;
    }
  }

  // Permission checks for topic actions

  private boolean canCreateBotTopics () {
    return isBotForum && tdlib.canManageBotForumTopics(chatId);
  }

  private boolean canCreateTopics () {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) return false;

    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
      case TdApi.ChatMemberStatusMember.CONSTRUCTOR:
      case TdApi.ChatMemberStatusRestricted.CONSTRUCTOR:
        // Check chat-level permissions
        return chat != null && chat.permissions != null && chat.permissions.canCreateTopics;
      default:
        return false;
    }
  }

  private boolean canManageTopics () {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) return false;

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
