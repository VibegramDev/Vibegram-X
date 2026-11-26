package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

public interface ForumTopicInfoListener {
  default void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) { }
  /** updateForumTopic carries no unreadCount: it has to be requested with GetForumTopic when needed. */
  default void onForumTopicUpdated (TdApi.UpdateForumTopic update) { }
}
