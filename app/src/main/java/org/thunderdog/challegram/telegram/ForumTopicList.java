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
  private final Set<Integer> refetchTopics = new HashSet<>();
  private final List<Runnable> listeners = new ArrayList<>();
  private List<TdApi.ForumTopic> snapshot = Collections.emptyList();

  private int offsetDate, offsetForumTopicId;
  private int pinnedOrderRevision;
  private long offsetMessageId;
  private boolean hasMore, loading, loadedContinuation, refreshPending, reopenPending;
  private boolean loadFailed, failedFirstPage;
  private @Nullable Map<Integer, TdApi.ForumTopic> unseenOnReopen;
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
    boolean firstListener = listeners.isEmpty();
    listeners.add(listener);
    if (firstListener) {
      tdlib.listeners().subscribeToChatUpdates(chatId, this);
      tdlib.listeners().subscribeToMessageUpdates(chatId, this);
      if (loading) {
        reopenPending = true;
      } else {
        reopen();
      }
    }
  }

  public void removeListener (Runnable listener) {
    if (listeners.remove(listener) && listeners.isEmpty()) {
      reopenPending = false;
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
    hasMore = loading = loadedContinuation = refreshPending = reopenPending = loadFailed = false;
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
    refetchTopics.clear();
    unseenOnReopen = null;
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

  public boolean isLoading () {
    return loading;
  }

  public boolean hasLoadError () {
    return loadFailed;
  }

  public void retry () {
    if (!destroyed && loadFailed && !loading) {
      loadPage(failedFirstPage);
    }
  }

  private void reopen () {
    unseenOnReopen = topics.isEmpty() ? null : new HashMap<>(topics);
    loadedContinuation = false;
    loadPage(true);
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
    if (canLoadMore() && !loading && !loadFailed) {
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
    loadFailed = false;
    // Keep the state at request time for merging fields changed by live updates.
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
            if (unseenOnReopen != null) {
              unseenOnReopen.remove(id);
            }
            put(ForumTopicState.mergeReply(requestedWith.get(id), topics.get(id), topic));
          }
          // A pin refresh fetches the head, not a replacement for previously loaded pages.
          if (!firstPage || !loadedContinuation) {
            offsetDate = page.nextOffsetDate;
            offsetMessageId = page.nextOffsetMessageId;
            offsetForumTopicId = page.nextOffsetForumTopicId;
            hasMore = page.topics.length > 0 && (offsetDate != 0 || offsetMessageId != 0 || offsetForumTopicId != 0) &&
              (offsetDate != date || offsetMessageId != messageId || offsetForumTopicId != topicId);
            loadedContinuation |= !firstPage;
            if (!hasMore && unseenOnReopen != null) {
              // A repeated cursor stops pagination, but does not prove absence.
              if (page.topics.length == 0 || (offsetDate == 0 && offsetMessageId == 0 && offsetForumTopicId == 0)) {
                for (Map.Entry<Integer, TdApi.ForumTopic> unseen : unseenOnReopen.entrySet()) {
                  int id = unseen.getKey();
                  if (topics.get(id) == unseen.getValue()) {
                    topics.remove(id);
                    pinnedOrder.remove(id);
                  }
                }
              }
              unseenOnReopen = null;
            }
          }
        } else {
          loadFailed = true;
          failedFirstPage = firstPage;
        }
        if (reopenPending && hasListeners()) {
          reopenPending = refreshPending = false;
          reopen();
        } else if (refreshPending) {
          refreshPending = false;
          refresh();
        } else if (page != null && hasListeners() && (topics.isEmpty() ||
                   (firstPage && !loadedContinuation && unseenOnReopen != null && !unseenOnReopen.isEmpty()))) {
          // A bot's first page may only contain hidden General. Also check one continuation
          // after reopening so ordinary single-page forums can reconcile without scrolling.
          loadMore();
        }
        publish();
      });
    });
    publish();
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
      return Long.compare(b.order, a.order);
    });
    snapshot = Collections.unmodifiableList(ordered);
    for (Runnable listener : new ArrayList<>(listeners)) {
      if (listeners.contains(listener)) {
        listener.run();
      }
    }
  }

  public void requestTopic (int id) {
    requestTopic(id, true);
  }

  private void requestTopic (int id, boolean retryOnConflict) {
    if (destroyed || deletedTopics.contains(id)) {
      return;
    }
    if (!pendingTopics.add(id)) {
      refetchTopics.add(id);
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
        boolean retry = false;
        if (fresh != null && !deletedTopics.contains(id)) {
          TdApi.ForumTopic current = topics.get(id);
          if (current != null || before == null) {
            TdApi.ForumTopic merged = ForumTopicState.mergeReply(before, current, fresh);
            retry = retryOnConflict && merged.unreadCount > 0 && !ForumTopicState.matchesUnreadCount(merged, fresh);
            put(merged);
            publish();
          }
        }
        if (refetchTopics.remove(id)) {
          requestTopic(id);
        } else if (retry && hasListeners()) {
          requestTopic(id, false);
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
        put(ForumTopicState.withInfo(previous, info));
        publish();
      }
    });
  }

  @Override
  public void onForumTopicUpdated (TdApi.UpdateForumTopic update) {
    if (update.chatId != chatId) {
      return;
    }
    UI.post(() -> {
      if (destroyed) {
        return;
      }
      int id = update.forumTopicId;
      TdApi.ForumTopic previous = topics.get(id);
      if (previous == null) {
        if (update.isPinned && !deletedTopics.contains(id)) {
          refresh();
        }
        return;
      }
      TdApi.ForumTopic topic = ForumTopicState.withUpdate(previous, update);
      put(topic);
      if (previous.isPinned != topic.isPinned) {
        if (!topic.isPinned) {
          pinnedOrder.remove(id);
        }
        refresh();
      }
      publish();
      if ((topic.unreadCount > 0 && previous.lastReadInboxMessageId != topic.lastReadInboxMessageId) ||
          (previous.lastMessage == null && previous.order > topic.order)) {
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
      TdApi.ForumTopic topic = ForumTopicState.withMessage(previous, message, oldMessageId);
      if (topic != previous) {
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
