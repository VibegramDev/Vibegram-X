package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

/** Shared TDLib topic reducer. Never mutate a DTO published to another screen or thread. */
public final class ForumTopicState {
  private ForumTopicState () { }

  public static TdApi.ForumTopic copyOf (TdApi.ForumTopic topic) {
    return new TdApi.ForumTopic(topic.info, topic.lastMessage, topic.order, topic.isPinned, topic.unreadCount,
      topic.lastReadInboxMessageId, topic.lastReadOutboxMessageId, topic.unreadMentionCount,
      topic.unreadReactionCount, topic.unreadPollVoteCount, topic.notificationSettings, topic.draftMessage);
  }

  public static TdApi.ForumTopic withInfo (TdApi.ForumTopic previous, TdApi.ForumTopicInfo info) {
    TdApi.ForumTopic topic = copyOf(previous);
    topic.info = info;
    return topic;
  }

  public static TdApi.ForumTopic withUpdate (TdApi.ForumTopic previous, TdApi.UpdateForumTopic update) {
    TdApi.ForumTopic topic = copyOf(previous);
    long priorDraftOrder = draftOrder(previous.draftMessage);
    long nextDraftOrder = draftOrder(update.draftMessage);
    // The server order can include the old draft. Keep the server's message order when it is
    // distinguishable; otherwise use the last known message (or zero until a fresh topic arrives).
    if (priorDraftOrder != 0 && priorDraftOrder == previous.order && priorDraftOrder != nextDraftOrder) {
      topic.order = messageOrder(previous.lastMessage);
    }
    topic.order = Math.max(topic.order, nextDraftOrder);
    topic.isPinned = update.isPinned;
    topic.lastReadInboxMessageId = update.lastReadInboxMessageId;
    topic.lastReadOutboxMessageId = update.lastReadOutboxMessageId;
    topic.unreadMentionCount = update.unreadMentionCount;
    topic.unreadReactionCount = update.unreadReactionCount;
    topic.unreadPollVoteCount = update.unreadPollVoteCount;
    topic.notificationSettings = update.notificationSettings;
    topic.draftMessage = update.draftMessage;
    if (topic.lastMessage != null && topic.lastReadInboxMessageId >= topic.lastMessage.id) {
      topic.unreadCount = 0;
    }
    return topic;
  }

  static TdApi.ForumTopic withMessage (TdApi.ForumTopic previous, TdApi.Message message, long oldMessageId) {
    boolean wasPending = oldMessageId != 0 && previous.lastMessage != null && previous.lastMessage.id == oldMessageId;
    if (!wasPending && previous.lastMessage != null && previous.lastMessage.id >= message.id) {
      return previous;
    }
    TdApi.ForumTopic topic = copyOf(previous);
    if (wasPending && topic.order == messageOrder(previous.lastMessage)) {
      topic.order = draftOrder(topic.draftMessage);
    }
    topic.order = Math.max(topic.order, messageOrder(message));
    topic.lastMessage = message;
    if (!wasPending && !message.isOutgoing && message.id > topic.lastReadInboxMessageId) {
      topic.unreadCount++;
    }
    return topic;
  }

  /** Merge a full reply with the UI state changed since the request was sent. The accompanying
   * UpdateForumTopic has neither the preview nor the exact unread count. */
  static TdApi.ForumTopic mergeReply (@Nullable TdApi.ForumTopic before, @Nullable TdApi.ForumTopic current, TdApi.ForumTopic fresh) {
    if (current == null || (current == before && fresh.lastReadInboxMessageId >= current.lastReadInboxMessageId &&
        fresh.lastReadOutboxMessageId >= current.lastReadOutboxMessageId)) {
      return fresh;
    }
    TdApi.ForumTopic merged = copyOf(fresh);
    // These fields also arrive in topic updates. Preserve each live change independently.
    if (before == null || current.draftMessage != before.draftMessage) merged.draftMessage = current.draftMessage;
    if (before == null || current.info != before.info) merged.info = current.info;
    if (before == null || current.isPinned != before.isPinned) merged.isPinned = current.isPinned;
    if (before == null || current.unreadMentionCount != before.unreadMentionCount) merged.unreadMentionCount = current.unreadMentionCount;
    if (before == null || current.unreadReactionCount != before.unreadReactionCount) merged.unreadReactionCount = current.unreadReactionCount;
    if (before == null || current.unreadPollVoteCount != before.unreadPollVoteCount) merged.unreadPollVoteCount = current.unreadPollVoteCount;
    if (before == null || current.notificationSettings != before.notificationSettings) merged.notificationSettings = current.notificationSettings;
    merged.lastReadInboxMessageId = Math.max(current.lastReadInboxMessageId, fresh.lastReadInboxMessageId);
    merged.lastReadOutboxMessageId = Math.max(current.lastReadOutboxMessageId, fresh.lastReadOutboxMessageId);

    // A delete can legitimately move the preview backwards. Don't confuse a metadata update
    // with a preview change, but don't regress to an older preview AND read marker.
    boolean preserveMessage = lastMessageId(current) != lastMessageId(fresh) &&
      (before == null || current.lastMessage != before.lastMessage ||
        (fresh.lastReadInboxMessageId < current.lastReadInboxMessageId && lastMessageId(fresh) < lastMessageId(current)));
    TdApi.ForumTopic preview = preserveMessage ? current : fresh;
    merged.lastMessage = preview.lastMessage;
    long previewDraftOrder = draftOrder(preview.draftMessage);
    long mergedDraftOrder = draftOrder(merged.draftMessage);
    long order = previewDraftOrder != 0 && preview.order == previewDraftOrder && previewDraftOrder != mergedDraftOrder
      ? messageOrder(merged.lastMessage) : preview.order;
    merged.order = Math.max(order, mergedDraftOrder);
    if (merged.lastMessage != null && merged.lastReadInboxMessageId >= merged.lastMessage.id) {
      merged.unreadCount = 0;
    } else if (!matchesUnreadCount(merged, fresh)) {
      merged.unreadCount = current.unreadCount;
    }
    return merged;
  }

  static boolean matchesUnreadCount (TdApi.ForumTopic current, TdApi.ForumTopic fresh) {
    return current.lastReadInboxMessageId == fresh.lastReadInboxMessageId && lastMessageId(current) == lastMessageId(fresh);
  }

  /** An in-flight count can survive changes to metadata, but not to the read marker or latest message. */
  public static boolean canApplyUnreadCount (TdApi.ForumTopic before, TdApi.ForumTopic current, TdApi.ForumTopic fresh) {
    return current.lastReadInboxMessageId == before.lastReadInboxMessageId &&
      current.unreadCount == before.unreadCount &&
      lastMessageId(current) == lastMessageId(before) && matchesUnreadCount(current, fresh);
  }

  private static long lastMessageId (TdApi.ForumTopic topic) {
    return topic.lastMessage != null ? topic.lastMessage.id : 0;
  }

  private static long messageOrder (@Nullable TdApi.Message message) {
    return message != null ? ((long) message.date << 32) + (message.id >> 20) : 0;
  }

  private static long draftOrder (@Nullable TdApi.DraftMessage draft) {
    return draft != null ? (long) draft.date << 32 : 0;
  }
}
