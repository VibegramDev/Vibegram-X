package org.thunderdog.challegram.telegram

import android.os.SystemClock
import me.vkryl.core.reference.ReferenceMap
import org.drinkless.tdlib.TdApi.*
import org.thunderdog.challegram.data.TD

class TdlibForumTopicManager(
  tdlib: Tdlib
) : TdlibDataManager<TdlibForumTopicManager.Key, ForumTopic, TdlibForumTopicManager.Entry>(tdlib) {
  data class Key(
    @JvmField val chatId: Long,
    @JvmField val forumTopicId: Int
  )
  class Entry : AbstractEntry<Key, ForumTopic> {
    @JvmField val forumTopicId: Int

    constructor(key: Key, value: ForumTopic?, error: Error?) : super(key, value, error) {
      this.forumTopicId = key.forumTopicId
    }
  }

  interface Watcher : TdlibDataManager.Watcher<Key, ForumTopic, Entry> {
    fun onForumTopicLoaded (context: TdlibForumTopicManager, entry: Entry)

    override fun onEntryLoaded(context: TdlibDataManager<Key, ForumTopic, Entry>, entry: Entry) {
      onForumTopicLoaded(context as TdlibForumTopicManager, entry)
    }
  }

  override fun newEntry(key: Key, value: ForumTopic?, error: Error?): Entry =
    Entry(key, value, error)

  override fun requestData(contextId: Int, keysToRequest: Collection<Key>) {
    for (key in keysToRequest) {
      tdlib.send(GetForumTopic(key.chatId, key.forumTopicId)) { forumTopic, error ->
        if (!isCancelled(contextId)) {
          if (error != null) {
            processError(contextId, key, error)
          } else {
            forumTopic?.let { setMuteEndTime(key, it.notificationSettings) }
            processData(contextId, key, forumTopic)
          }
        }
      }
    }
  }

  private val listLock = Any()
  private val topicLists = HashMap<Long, IntArray>()
  private val muteEndTimes = HashMap<Key, Long>()

  private fun setMuteEndTime(key: Key, settings: ChatNotificationSettings?) {
    synchronized(listLock) {
      if (settings == null || settings.useDefaultMuteFor || settings.muteFor <= 0) {
        muteEndTimes.remove(key)
      } else {
        muteEndTimes[key] = if (TD.isMutedForever(settings.muteFor)) {
          Long.MAX_VALUE
        } else {
          SystemClock.elapsedRealtime() + settings.muteFor * 1000L
        }
      }
    }
  }

  /**
   * Seconds left until the topic's own settings unmute it, 0 if they don't mute it.
   * TDLib sends no update when a temporary mute of a topic ends.
   */
  fun ownMuteFor(chatId: Long, forumTopicId: Int): Int {
    val endTime = synchronized(listLock) {
      muteEndTimes[Key(chatId, forumTopicId)]
    } ?: return 0
    if (endTime == Long.MAX_VALUE) {
      return Int.MAX_VALUE
    }
    val timeLeft = endTime - SystemClock.elapsedRealtime()
    return if (timeLeft > 0) ((timeLeft + 999L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
  }

  fun hasOwnMute(chatId: Long, forumTopicId: Int): Boolean =
    ownMuteFor(chatId, forumTopicId) > 0

  /**
   * Whether a notification about a message in the topic must be dropped, or null while the topic is unknown.
   * As for muted chats, mentions and replies are dropped only when the topic disables them.
   */
  fun isNotificationMuted(key: Key, isMention: Boolean): Boolean? {
    val entry = find(key) ?: return null
    val settings = entry.value?.notificationSettings ?: return false
    return if (isMention) {
      !settings.useDefaultDisableMentionNotifications && settings.disableMentionNotifications
    } else {
      hasOwnMute(key.chatId, key.forumTopicId)
    }
  }

  @TdlibThread
  fun putTopic(chatId: Long, topic: ForumTopic) {
    val key = Key(chatId, topic.info.forumTopicId)
    setMuteEndTime(key, topic.notificationSettings)
    putValue(key, copyOf(topic))
  }

  @TdlibThread
  fun putTopics(chatId: Long, topics: Array<ForumTopic>) {
    for (topic in topics) {
      putTopic(chatId, topic)
    }
  }

  /** Remembers the order of the topic list last shown for the chat. Known topics are kept: updates keep them fresh. */
  fun setTopicList(chatId: Long, topics: List<ForumTopic>) {
    val ids = IntArray(topics.size)
    for ((index, topic) in topics.withIndex()) {
      ids[index] = topic.info.forumTopicId
      val key = Key(chatId, topic.info.forumTopicId)
      if (find(key)?.value == null) {
        setMuteEndTime(key, topic.notificationSettings)
        putValue(key, copyOf(topic))
      }
    }
    synchronized(listLock) {
      topicLists[chatId] = ids
    }
  }

  /** Copies of the topics last shown for the chat, to show them right away while the list is loaded. */
  fun getTopicList(chatId: Long): List<ForumTopic>? {
    val ids = synchronized(listLock) {
      topicLists[chatId]
    } ?: return null
    val topics = ArrayList<ForumTopic>(ids.size)
    for (forumTopicId in ids) {
      find(Key(chatId, forumTopicId))?.value?.let {
        topics.add(copyOf(it))
      }
    }
    return topics
  }

  fun clearTopicLists() {
    synchronized(listLock) {
      topicLists.clear()
      muteEndTimes.clear()
    }
  }

  interface Observer {
    fun onTopicFound(key: Key, topic: ForumTopic, inPlace: Boolean)
    fun onTopicUpdated(key: Key, update: UpdateForumTopic)
    fun onTopicInfoUpdated(key: Key, topicInfo: ForumTopicInfo)
  }

  @TdlibThread
  fun updateForumTopic(update: UpdateForumTopic) {
    val key = Key(
      update.chatId,
      update.forumTopicId
    )
    setMuteEndTime(key, update.notificationSettings)
    val isUpdated = replaceExisting(key) { prevValue, _ ->
      if (prevValue != null) {
        val prevLastMessage = prevValue.lastMessage
        val isRead = prevLastMessage != null && update.lastReadInboxMessageId >= prevLastMessage.id
        ForumTopic(
          prevValue.info,
          prevValue.lastMessage,
          maxOf(prevValue.order, draftOrder(update.draftMessage)),
          update.isPinned,
          if (isRead) 0 else prevValue.unreadCount,
          update.lastReadInboxMessageId,
          update.lastReadOutboxMessageId,
          update.unreadMentionCount,
          update.unreadReactionCount,
          update.unreadPollVoteCount,
          update.notificationSettings,
          update.draftMessage
        )
      } else {
        null
      }
    }
    if (isUpdated) {
      observers.iterate(key) { observer ->
        observer.onTopicUpdated(key, update)
      }
    }
  }

  @TdlibThread
  fun updateForumTopicInfo(update: UpdateForumTopicInfo) {
    val key = Key(
      update.info.chatId,
      update.info.forumTopicId
    )
    val isUpdated = replaceExisting(key) { prevValue, _ ->
      if (prevValue != null) {
        ForumTopic(
          update.info,
          prevValue.lastMessage,
          prevValue.order,
          prevValue.isPinned,
          prevValue.unreadCount,
          prevValue.lastReadInboxMessageId,
          prevValue.lastReadOutboxMessageId,
          prevValue.unreadMentionCount,
          prevValue.unreadReactionCount,
          prevValue.unreadPollVoteCount,
          prevValue.notificationSettings,
          prevValue.draftMessage
        )
      } else {
        null
      }
    }
    if (isUpdated) {
      observers.iterate(key) { observer ->
        observer.onTopicInfoUpdated(key, update.info)
      }
    }
  }

  @TdlibThread
  fun updateNewMessage(message: Message) {
    val topicId = message.topicId
    if (topicId !is MessageTopicForum) {
      return
    }
    replaceExisting(Key(message.chatId, topicId.forumTopicId)) { prevValue, _ ->
      if (prevValue != null && (prevValue.lastMessage?.let { it.id < message.id } ?: true)) {
        val isUnread = !message.isOutgoing && message.id > prevValue.lastReadInboxMessageId
        ForumTopic(
          prevValue.info,
          message,
          maxOf(prevValue.order, messageOrder(message)),
          prevValue.isPinned,
          prevValue.unreadCount + (if (isUnread) 1 else 0),
          prevValue.lastReadInboxMessageId,
          prevValue.lastReadOutboxMessageId,
          prevValue.unreadMentionCount,
          prevValue.unreadReactionCount,
          prevValue.unreadPollVoteCount,
          prevValue.notificationSettings,
          prevValue.draftMessage
        )
      } else {
        null
      }
    }
  }

  private val observers = ReferenceMap<Key, Observer>(true)

  fun findAndObserve(key: Key, observer: Observer): Entry? {
    observers.add(key, observer)
    return findOrRequest(key) { entry, inPlace ->
      if (!entry.isNotFound) {
        observer.onTopicFound(key, entry.value!!, inPlace)
      }
    }
  }

  fun stopObserving(key: Key, observer: Observer) {
    observers.remove(key, observer)
  }

  companion object {
    /** ForumTopic.order of TDLib: date of the last message or draft in the high bits, server message id in the low ones. */
    @JvmStatic
    fun messageOrder(message: Message?): Long =
      if (message != null) (message.date.toLong() shl 32) + (message.id shr 20) else 0L

    @JvmStatic
    fun draftOrder(draftMessage: DraftMessage?): Long =
      if (draftMessage != null) draftMessage.date.toLong() shl 32 else 0L

    @JvmStatic
    fun copyOf(topic: ForumTopic): ForumTopic = ForumTopic(
      topic.info,
      topic.lastMessage,
      topic.order,
      topic.isPinned,
      topic.unreadCount,
      topic.lastReadInboxMessageId,
      topic.lastReadOutboxMessageId,
      topic.unreadMentionCount,
      topic.unreadReactionCount,
      topic.unreadPollVoteCount,
      topic.notificationSettings,
      topic.draftMessage
    )
  }
}
