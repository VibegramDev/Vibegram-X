package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

public interface ForumTopicInfoListener {
  default void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) { }
  /** updateForumTopic carries no unreadCount: it has to be requested with GetForumTopic when needed. */
  default void onForumTopicUpdated (long chatId, long messageThreadId, boolean isPinned, long lastReadInboxMessageId, long lastReadOutboxMessageId, int unreadMentionCount, int unreadReactionCount, TdApi.ChatNotificationSettings notificationSettings, @Nullable TdApi.DraftMessage draftMessage) { }
}
