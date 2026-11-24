/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vibegram X: chats pinned on this device only, over the server limit of pinned chats (5 without Premium).
 *
 * The server keeps its own pinned chats; the ones that do not fit are kept here, per account and chat list, and are
 * not synced. {@link Tdlib} turns the position of such a chat into a pinned one when it comes from TDLib, so the
 * chat list, its sorting and every "is pinned" check see an ordinary pinned chat. The order of a local pin lies above
 * every ordinary chat (whose order is built from the date of the last message) and below every server pin
 * (TDLib gives them orders from 2147000000 << 32), so local pins go right after the server ones.
 *
 * Each pin has a rank: a new pin gets the highest one (top of the local group), reordering rewrites the ranks.
 */
public final class LocalPinnedChats {
  private static final String KEY_PREFIX = "vg_local_pins_";
  private static final long ORDER_BASE = 2146000000L << 32;

  private final int accountId;
  /** list key → [chatId, rank, chatId, rank, …] */
  private final Map<String, long[]> cache = new HashMap<>();

  LocalPinnedChats (int accountId) {
    this.accountId = accountId;
  }

  public static @Nullable String listKey (@Nullable TdApi.ChatList chatList) {
    if (chatList == null) {
      return null;
    }
    switch (chatList.getConstructor()) {
      case TdApi.ChatListMain.CONSTRUCTOR:
        return "main";
      case TdApi.ChatListArchive.CONSTRUCTOR:
        return "archive";
      case TdApi.ChatListFolder.CONSTRUCTOR:
        return "folder" + ((TdApi.ChatListFolder) chatList).chatFolderId;
    }
    return null;
  }

  private @NonNull long[] get (String listKey) {
    long[] pins = cache.get(listKey);
    if (pins == null) {
      pins = Settings.instance().pmc().getLongArray(KEY_PREFIX + accountId + "_" + listKey);
      if (pins == null || pins.length % 2 != 0) {
        pins = new long[0];
      }
      cache.put(listKey, pins);
    }
    return pins;
  }

  private void put (String listKey, long[] pins) {
    cache.put(listKey, pins);
    String key = KEY_PREFIX + accountId + "_" + listKey;
    if (pins.length == 0) {
      Settings.instance().pmc().remove(key);
    } else {
      Settings.instance().pmc().putLongArray(key, pins);
    }
  }

  /** @return rank of the pin, or 0 when the chat is not pinned locally */
  private static long rankOf (long[] pins, long chatId) {
    for (int i = 0; i < pins.length; i += 2) {
      if (pins[i] == chatId) {
        return pins[i + 1];
      }
    }
    return 0;
  }

  public synchronized boolean isPinned (@Nullable TdApi.ChatList chatList, long chatId) {
    String listKey = listKey(chatList);
    return listKey != null && rankOf(get(listKey), chatId) != 0;
  }

  public synchronized boolean hasAny (@Nullable TdApi.ChatList chatList) {
    String listKey = listKey(chatList);
    return listKey != null && get(listKey).length > 0;
  }

  /** Pins the chat on top of the local pins. */
  public synchronized void pin (@NonNull TdApi.ChatList chatList, long chatId) {
    String listKey = listKey(chatList);
    if (listKey == null) {
      return;
    }
    long[] pins = get(listKey);
    if (rankOf(pins, chatId) != 0) {
      return;
    }
    long maxRank = 0;
    for (int i = 1; i < pins.length; i += 2) {
      maxRank = Math.max(maxRank, pins[i]);
    }
    long[] newPins = new long[pins.length + 2];
    System.arraycopy(pins, 0, newPins, 0, pins.length);
    newPins[pins.length] = chatId;
    newPins[pins.length + 1] = maxRank + 1;
    put(listKey, newPins);
  }

  public synchronized boolean unpin (@Nullable TdApi.ChatList chatList, long chatId) {
    String listKey = listKey(chatList);
    if (listKey == null) {
      return false;
    }
    long[] pins = get(listKey);
    for (int i = 0; i < pins.length; i += 2) {
      if (pins[i] == chatId) {
        long[] newPins = new long[pins.length - 2];
        System.arraycopy(pins, 0, newPins, 0, i);
        System.arraycopy(pins, i + 2, newPins, i, pins.length - i - 2);
        put(listKey, newPins);
        return true;
      }
    }
    return false;
  }

  /** Sets the order of the local pins of the list: the first chat goes on top. Chats that are not pinned locally are skipped. */
  public synchronized void reorder (@NonNull TdApi.ChatList chatList, @NonNull List<Long> chatIds) {
    String listKey = listKey(chatList);
    if (listKey == null) {
      return;
    }
    long[] pins = get(listKey);
    List<Long> ordered = new ArrayList<>(chatIds.size());
    for (Long chatId : chatIds) {
      if (rankOf(pins, chatId) != 0 && !ordered.contains(chatId)) {
        ordered.add(chatId);
      }
    }
    // Pins of chats that are not in the given list (not loaded) keep their place below the given ones
    List<long[]> rest = new ArrayList<>();
    for (int i = 0; i < pins.length; i += 2) {
      if (!ordered.contains(pins[i])) {
        rest.add(new long[] {pins[i], pins[i + 1]});
      }
    }
    rest.sort((a, b) -> Long.compare(b[1], a[1]));
    long[] newPins = new long[pins.length];
    int count = ordered.size() + rest.size();
    int index = 0;
    for (Long chatId : ordered) {
      newPins[index * 2] = chatId;
      newPins[index * 2 + 1] = count - index;
      index++;
    }
    for (long[] pin : rest) {
      newPins[index * 2] = pin[0];
      newPins[index * 2 + 1] = count - index;
      index++;
    }
    put(listKey, newPins);
  }

  public synchronized long[] chatIds (@Nullable TdApi.ChatList chatList) {
    String listKey = listKey(chatList);
    if (listKey == null) {
      return new long[0];
    }
    long[] pins = get(listKey);
    long[] chatIds = new long[pins.length / 2];
    for (int i = 0; i < chatIds.length; i++) {
      chatIds[i] = pins[i * 2];
    }
    return chatIds;
  }

  /**
   * @return the position to keep for the chat: a pinned one when the chat is pinned here, the given one otherwise.
   * A chat pinned on the server drops its local pin.
   */
  synchronized TdApi.ChatPosition apply (long chatId, TdApi.ChatPosition position) {
    if (position == null || position.order == 0 || position.source != null) {
      return position;
    }
    String listKey = listKey(position.list);
    if (listKey == null) {
      return position;
    }
    long[] pins = get(listKey);
    long rank = rankOf(pins, chatId);
    if (rank == 0) {
      return position;
    }
    if (position.isPinned) {
      unpin(position.list, chatId);
      return position;
    }
    return new TdApi.ChatPosition(position.list, ORDER_BASE + rank, true, null);
  }
}
