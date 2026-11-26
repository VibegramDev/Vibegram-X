package org.thunderdog.challegram.telegram

import android.os.SystemClock
import androidx.annotation.UiThread
import me.vkryl.core.reference.ReferenceMap
import org.drinkless.tdlib.TdApi.*
import org.thunderdog.challegram.data.TD
import org.thunderdog.challegram.tool.UI

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
  private val topicLists = LinkedHashMap<Long, ForumTopicList>(16, 0.75f, true)
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
    putValue(key, ForumTopicState.copyOf(topic))
  }

  @TdlibThread
  fun putTopics(chatId: Long, topics: Array<ForumTopic>) {
    for (topic in topics) {
      putTopic(chatId, topic)
    }
  }

  @UiThread
  fun list(chatId: Long): ForumTopicList {
    topicLists[chatId]?.let { return it }
    if (topicLists.size >= 8) {
      val iterator = topicLists.values.iterator()
      while (iterator.hasNext() && topicLists.size >= 8) {
        val list = iterator.next()
        if (list.isIdle) {
          list.destroy()
          iterator.remove()
        }
      }
    }
    return ForumTopicList(tdlib, chatId).also { topicLists[chatId] = it }
  }

  @TdlibThread
  fun clearTopicLists() {
    synchronized(listLock) { muteEndTimes.clear() }
    UI.post {
      val iterator = topicLists.values.iterator()
      while (iterator.hasNext()) {
        val list = iterator.next()
        if (list.hasListeners()) {
          list.resetAfterClientRestart()
        } else {
          list.destroy()
          iterator.remove()
        }
      }
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
    val isUpdated = replaceExisting(key) { previous, _ ->
      previous?.let { ForumTopicState.withUpdate(it, update) }
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
    val isUpdated = replaceExisting(key) { previous, _ ->
      previous?.let { ForumTopicState.withInfo(it, update.info) }
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
    replaceExisting(Key(message.chatId, topicId.forumTopicId)) { previous, _ ->
      previous?.let { ForumTopicState.withMessage(it, message, 0).takeUnless { next -> next === previous } }
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

}
