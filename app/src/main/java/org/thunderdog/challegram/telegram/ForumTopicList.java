/*
 * This file is a part of Vibegram-X
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.tool.UI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The ordered forum topics and TDLib cursor of one account/chat; used by the list and both tab layouts. UI thread only. */
public final class ForumTopicList implements ChatListener, MessageListener {
  private static final int PAGE_SIZE = 100;

  private final Tdlib tdlib;
  private final long chatId;
  private final boolean hideGeneral;
  private final Map<Integer, TdApi.ForumTopic> topics = new LinkedHashMap<>();
  private final Map<Integer, Integer> pinnedOrder = new HashMap<>();
  private final Set<Integer> deletedTopics = new HashSet<>();
  private final Set<Integer> pendingTopics = new HashSet<>();
  private final List<Runnable> listeners = new ArrayList<>();
  private List<TdApi.ForumTopic> snapshot = Collections.emptyList();

  private int offsetDate, offsetForumTopicId;
  private int pinnedOrderRevision;
  private long offsetMessageId;
  private boolean hasMore, loading, loadedContinuation, refreshPending;
  private volatile boolean destroyed;
  private volatile int clientGeneration;

  ForumTopicList (Tdlib tdlib, long chatId) {
    this.tdlib = tdlib;
    this.chatId = chatId;
    this.hideGeneral = tdlib.isBotForum(chatId);
  }

  public void addListener (Runnable listener) {
    if (destroyed || listeners.contains(listener)) {
      return;
    }
    if (listeners.isEmpty()) {
      tdlib.listeners().subscribeToChatUpdates(chatId, this);
      tdlib.listeners().subscribeToMessageUpdates(chatId, this);
      if (!loading) {
        refresh();
      }
    }
    listeners.add(listener);
  }

  public void removeListener (Runnable listener) {
    if (listeners.remove(listener) && listeners.isEmpty()) {
      unsubscribe();
    }
  }

  boolean isIdle () {
    return !hasListeners() && !loading;
  }

  boolean hasListeners () {
    return !listeners.isEmpty();
  }

  void resetAfterClientRestart () {
    int generation = ++clientGeneration;
    clearItems();
    offsetDate = offsetForumTopicId = 0;
    offsetMessageId = 0;
    hasMore = loading = loadedContinuation = refreshPending = false;
    publish();
    tdlib.awaitInitialization(() -> UI.post(() -> {
      if (!isStale(generation) && hasListeners() && tdlib.isAuthorized()) {
        refresh();
      }
    }));
  }

  private boolean isStale (int generation) {
    return destroyed || clientGeneration != generation;
  }

  void destroy () {
    if (!listeners.isEmpty()) {
      unsubscribe();
    }
    destroyed = true;
    listeners.clear();
    clearItems();
  }

  private void clearItems () {
    topics.clear();
    pinnedOrder.clear();
    deletedTopics.clear();
    pendingTopics.clear();
    snapshot = Collections.emptyList();
  }

  private void unsubscribe () {
    tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId, this);
  }

  public List<TdApi.ForumTopic> items () {
    return snapshot;
  }

  public boolean canLoadMore () {
    return !destroyed && hasMore;
  }

  public void refresh () {
    if (destroyed) {
      return;
    }
    if (loading) {
      refreshPending = true;
    } else {
      loadPage(true);
    }
  }

  public void loadMore () {
    if (canLoadMore() && !loading) {
      loadPage(false);
    }
  }

  private void loadPage (boolean firstPage) {
    final int date = firstPage ? 0 : offsetDate;
    final long messageId = firstPage ? 0 : offsetMessageId;
    final int topicId = firstPage ? 0 : offsetForumTopicId;
    final int orderRevision = pinnedOrderRevision;
    final int generation = clientGeneration;
    loading = true;
    // Do not overwrite live changes (including changes to the name or pin) received while this request is pending.
    final Map<Integer, TdApi.ForumTopic> requestedWith = new HashMap<>(topics);
    tdlib.client().send(new TdApi.GetForumTopics(chatId, "", 0L, false, false, date, messageId, topicId, PAGE_SIZE), result -> {
      final TdApi.ForumTopics page = result instanceof TdApi.ForumTopics ? (TdApi.ForumTopics) result : null;
      if (page != null && !isStale(generation)) {
        tdlib.topics().putTopics(chatId, page.topics);
      }
      UI.post(() -> {
        if (isStale(generation)) {
          return;
        }
        loading = false;
        if (page != null) {
          if (firstPage && orderRevision == pinnedOrderRevision) {
            pinnedOrder.clear();
            for (TdApi.ForumTopic topic : page.topics) {
              if (topic.isPinned && !deletedTopics.contains(topic.info.forumTopicId)) {
                pinnedOrder.put(topic.info.forumTopicId, pinnedOrder.size());
              }
            }
          }
          for (TdApi.ForumTopic topic : page.topics) {
            int id = topic.info.forumTopicId;
            if (topics.get(id) == requestedWith.get(id)) {
              put(topic);
            }
          }
          // A pin refresh fetches the head, not a replacement for previously loaded pages.
          if (!firstPage || !loadedContinuation) {
            offsetDate = page.nextOffsetDate;
            offsetMessageId = page.nextOffsetMessageId;
            offsetForumTopicId = page.nextOffsetForumTopicId;
            hasMore = page.topics.length > 0 && (offsetDate != 0 || offsetMessageId != 0 || offsetForumTopicId != 0) &&
              (offsetDate != date || offsetMessageId != messageId || offsetForumTopicId != topicId);
            loadedContinuation |= !firstPage;
          }
        }
        if (refreshPending) {
          refreshPending = false;
          refresh();
        } else if (page != null && topics.isEmpty() && !listeners.isEmpty()) {
          // A bot's first page can consist only of its hidden General topic. Never retry errors here.
          loadMore();
        }
        publish();
      });
    });
  }

  private void put (TdApi.ForumTopic topic) {
    int id = topic.info.forumTopicId;
    if (!deletedTopics.contains(id) && !(hideGeneral && topic.info.isGeneral)) {
      topics.put(id, topic);
    }
  }

  public void remove (int id) {
    if (!destroyed) {
      deletedTopics.add(id);
      pinnedOrder.remove(id);
      if (topics.remove(id) != null) {
        publish();
      }
    }
  }

  public boolean isRemoved (int id) {
    return deletedTopics.contains(id);
  }

  public void setPinnedOrder (int[] ids) {
    if (destroyed) {
      return;
    }
    pinnedOrderRevision++;
    pinnedOrder.clear();
    for (int i = 0; i < ids.length; i++) {
      pinnedOrder.put(ids[i], i);
    }
    publish();
  }

  private int pinnedIndex (TdApi.ForumTopic topic) {
    Integer index = pinnedOrder.get(topic.info.forumTopicId);
    return index != null ? index : Integer.MAX_VALUE;
  }

  private static long order (TdApi.ForumTopic topic) {
    return Math.max(topic.order, Math.max(TdlibForumTopicManager.messageOrder(topic.lastMessage), TdlibForumTopicManager.draftOrder(topic.draftMessage)));
  }

  private void publish () {
    List<TdApi.ForumTopic> ordered = new ArrayList<>(topics.values());
    Collections.sort(ordered, (a, b) -> {
      if (a.isPinned != b.isPinned) {
        return a.isPinned ? -1 : 1;
      }
      if (a.isPinned) {
        int comparison = Integer.compare(pinnedIndex(a), pinnedIndex(b));
        if (comparison != 0) {
          return comparison;
        }
      }
      return Long.compare(order(b), order(a));
    });
    snapshot = Collections.unmodifiableList(ordered);
    for (Runnable listener : new ArrayList<>(listeners)) {
      if (listeners.contains(listener)) {
        listener.run();
      }
    }
  }

  public void requestTopic (int id) {
    if (destroyed || deletedTopics.contains(id) || !pendingTopics.add(id)) {
      return;
    }
    TdApi.ForumTopic before = topics.get(id);
    int generation = clientGeneration;
    tdlib.client().send(new TdApi.GetForumTopic(chatId, id), result -> {
      TdApi.ForumTopic fresh = result instanceof TdApi.ForumTopic ? (TdApi.ForumTopic) result : null;
      if (fresh != null && !isStale(generation)) {
        tdlib.topics().putTopic(chatId, fresh);
      }
      UI.post(() -> {
        if (isStale(generation)) {
          return;
        }
        pendingTopics.remove(id);
        if (fresh != null && topics.get(id) == before && !deletedTopics.contains(id)) {
          put(fresh);
          publish();
        }
      });
    });
  }

  @Override
  public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
    if (info.chatId != chatId) {
      return;
    }
    UI.post(() -> {
      TdApi.ForumTopic previous = topics.get(info.forumTopicId);
      if (previous != null) {
        TdApi.ForumTopic topic = TdlibForumTopicManager.copyOf(previous);
        topic.info = info;
        put(topic);
        publish();
      }
    });
  }

  @Override
  public void onForumTopicUpdated (long chatId, long messageThreadId, boolean isPinned, long lastReadInboxMessageId, long lastReadOutboxMessageId,
                                   int unreadMentionCount, int unreadReactionCount, TdApi.ChatNotificationSettings settings, @Nullable TdApi.DraftMessage draft) {
    if (chatId != this.chatId) {
      return;
    }
    int id = (int) messageThreadId;
    UI.post(() -> {
      if (destroyed) {
        return;
      }
      TdApi.ForumTopic previous = topics.get(id);
      if (previous == null) {
        if (isPinned) {
          refresh();
        }
        return;
      }
      TdApi.ForumTopic topic = TdlibForumTopicManager.copyOf(previous);
      topic.isPinned = isPinned;
      topic.lastReadInboxMessageId = lastReadInboxMessageId;
      topic.lastReadOutboxMessageId = lastReadOutboxMessageId;
      topic.unreadMentionCount = unreadMentionCount;
      topic.unreadReactionCount = unreadReactionCount;
      if (settings != null) {
        topic.notificationSettings = settings;
      }
      topic.draftMessage = draft;
      if (topic.lastMessage != null && lastReadInboxMessageId >= topic.lastMessage.id) {
        topic.unreadCount = 0;
      }
      put(topic);
      if (previous.isPinned != isPinned) {
        if (!isPinned) {
          pinnedOrder.remove(id);
        }
        refresh();
      }
      publish();
      if (topic.unreadCount > 0 && previous.lastReadInboxMessageId != lastReadInboxMessageId) {
        requestTopic(id);
      }
    });
  }

  @Override
  public void onNewMessage (TdApi.Message message) {
    onTopicMessage(message, 0);
  }

  @Override
  public void onMessageSendSucceeded (TdApi.Message message, long oldMessageId) {
    onTopicMessage(message, oldMessageId);
  }

  private void onTopicMessage (TdApi.Message message, long oldMessageId) {
    if (message.chatId != chatId || !(message.topicId instanceof TdApi.MessageTopicForum)) {
      return;
    }
    int id = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
    UI.post(() -> {
      TdApi.ForumTopic previous = topics.get(id);
      if (previous == null) {
        requestTopic(id);
        return;
      }
      boolean wasPending = oldMessageId != 0 && previous.lastMessage != null && previous.lastMessage.id == oldMessageId;
      if (wasPending || previous.lastMessage == null || previous.lastMessage.id < message.id) {
        TdApi.ForumTopic topic = TdlibForumTopicManager.copyOf(previous);
        topic.lastMessage = message;
        if (!wasPending && !message.isOutgoing && message.id > previous.lastReadInboxMessageId) {
          topic.unreadCount++;
        }
        put(topic);
        publish();
      }
    });
  }

  @Override
  public void onMessageContentChanged (long chatId, long messageId, TdApi.MessageContent content) {
    if (chatId != this.chatId) {
      return;
    }
    UI.post(() -> {
      for (TdApi.ForumTopic topic : snapshot) {
        if (topic.lastMessage != null && topic.lastMessage.id == messageId) {
          // Re-fetch instead of mutating the Message object shared with TDLib and other views.
          requestTopic(topic.info.forumTopicId);
          break;
        }
      }
    });
  }

  @Override
  public void onMessagesDeleted (long chatId, long[] messageIds) {
    if (chatId != this.chatId) {
      return;
    }
    UI.post(() -> {
      for (TdApi.ForumTopic topic : snapshot) {
        if (topic.lastMessage == null) {
          continue;
        }
        for (long deletedId : messageIds) {
          if (topic.lastMessage.id == deletedId) {
            requestTopic(topic.info.forumTopicId);
            break;
          }
        }
      }
    });
  }
}
