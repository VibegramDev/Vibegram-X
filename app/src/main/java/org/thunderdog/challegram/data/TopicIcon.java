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
package org.thunderdog.challegram.data;

import android.graphics.Canvas;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifFile;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibEmojiManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Letters;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Icon of a forum topic, drawn in place of an avatar: the custom emoji of the topic, or the first letter of its name
 * on the topic color ({@link #placeholder}), which also stands in while the emoji loads. Used by the topic rows
 * ({@link TGChat}), the chat header and the topic filter.
 */
public final class TopicIcon implements TdlibEmojiManager.Watcher, Destroyable {
  /** Color of topics without one (the first of the server's topic colors). */
  private static final int DEFAULT_COLOR = 0x6FB9F0;

  private final Tdlib tdlib;
  private final TdApi.ForumTopicInfo info;
  private final int size;
  private final @Nullable Runnable onLoaded;
  private final long customEmojiId;

  private @Nullable TdlibEmojiManager.Entry customEmoji;
  private @Nullable ImageFile thumbnail, imageFile;
  private @Nullable GifFile gifFile;
  private boolean isDestroyed;

  /** {@code onLoaded} runs on the UI thread once the custom emoji is loaded: its files are to be requested again. */
  public TopicIcon (Tdlib tdlib, TdApi.ForumTopicInfo info, int size, @Nullable Runnable onLoaded) {
    this.tdlib = tdlib;
    this.info = info;
    this.size = size;
    this.onLoaded = onLoaded;
    this.customEmojiId = info.icon != null ? info.icon.customEmojiId : 0;
    if (customEmojiId != 0) {
      TdlibEmojiManager.Entry entry = tdlib.emoji().findOrPostponeRequest(customEmojiId, this);
      if (entry != null) {
        setCustomEmoji(entry);
      } else {
        tdlib.emoji().performPostponedRequests();
      }
    }
  }

  public TdApi.ForumTopicInfo getInfo () {
    return info;
  }

  /** The letter on the topic color is shown: no custom emoji, or it isn't loaded (yet). */
  public boolean needPlaceholder () {
    return customEmoji == null || customEmoji.isNotFound() || (imageFile == null && gifFile == null);
  }

  /** The letter on the topic color, for {@link org.thunderdog.challegram.loader.AvatarReceiver#requestPlaceholder}. */
  public AvatarPlaceholder.Metadata placeholder () {
    String letter = letter(info);
    return new AvatarPlaceholder.Metadata(accentColor(info), new Letters(letter != null ? letter : "#"));
  }

  private void setCustomEmoji (TdlibEmojiManager.Entry entry) {
    this.customEmoji = entry;
    this.thumbnail = null;
    this.imageFile = null;
    this.gifFile = null;
    TdApi.Sticker sticker = entry.isNotFound() ? null : entry.value;
    if (sticker == null) {
      return;
    }
    thumbnail = TD.toImageFile(tdlib, sticker.thumbnail);
    if (thumbnail != null) {
      thumbnail.setSize(size);
      thumbnail.setScaleType(ImageFile.FIT_CENTER);
      thumbnail.setNoBlur();
    }
    switch (sticker.format.getConstructor()) {
      case TdApi.StickerFormatTgs.CONSTRUCTOR:
      case TdApi.StickerFormatWebm.CONSTRUCTOR: {
        if (Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI) && thumbnail != null) {
          // Animated emoji are disabled: the static preview instead of the animation
          imageFile = thumbnail;
          break;
        }
        gifFile = new GifFile(tdlib, sticker);
        gifFile.setScaleType(GifFile.FIT_CENTER);
        gifFile.setOptimizationMode(GifFile.OptimizationMode.EMOJI);
        gifFile.setRequestedSize(size);
        break;
      }
      case TdApi.StickerFormatWebp.CONSTRUCTOR: {
        imageFile = new ImageFile(tdlib, sticker.sticker);
        imageFile.setSize(size);
        imageFile.setScaleType(ImageFile.FIT_CENTER);
        imageFile.setNoBlur();
        break;
      }
    }
  }

  @Override
  public void onCustomEmojiLoaded (TdlibEmojiManager context, TdlibEmojiManager.Entry entry) {
    tdlib.ui().post(() -> {
      if (isDestroyed || entry.customEmojiId != customEmojiId) {
        return;
      }
      setCustomEmoji(entry);
      if (onLoaded != null) {
        onLoaded.run();
      }
    });
  }

  /** Requests the files of the custom emoji into the receiver (key 0), or clears it. */
  public void requestFiles (ComplexReceiver receiver) {
    if (needPlaceholder()) {
      receiver.clear();
      return;
    }
    receiver.getPreviewReceiver(0).requestFile(null, thumbnail);
    if (imageFile != null) {
      receiver.getImageReceiver(0).requestFile(imageFile);
    } else {
      receiver.getGifReceiver(0).requestFile(gifFile);
    }
  }

  /** Draws the custom emoji requested by {@link #requestFiles}; nothing when {@link #needPlaceholder}. */
  public void draw (Canvas c, ComplexReceiver receiver, int left, int top, int right, int bottom) {
    if (needPlaceholder()) {
      return;
    }
    boolean needRepainting = TD.needThemedColorFilter(customEmoji.value);
    Receiver content = imageFile != null ? receiver.getImageReceiver(0) : receiver.getGifReceiver(0);
    content.setBounds(left, top, right, bottom);
    if (content.needPlaceholder()) {
      DoubleImageReceiver preview = receiver.getPreviewReceiver(0);
      preview.setBounds(left, top, right, bottom);
      if (needRepainting) {
        preview.setThemedPorterDuffColorId(ColorId.icon);
      } else {
        preview.disablePorterDuffColorFilter();
      }
      preview.draw(c);
    }
    if (needRepainting) {
      content.setThemedPorterDuffColorId(ColorId.icon);
    } else {
      content.disablePorterDuffColorFilter();
    }
    content.draw(c);
  }

  @Override
  public void performDestroy () {
    isDestroyed = true;
    if (customEmojiId != 0) {
      tdlib.emoji().forgetWatcher(customEmojiId, this);
    }
  }

  // Shared by all drawings of topic icons

  /** Letter of the icon of a topic without a custom emoji: "#" for the General topic, null for an empty name. */
  public static @Nullable String letter (TdApi.ForumTopicInfo info) {
    if (info.isGeneral) {
      return "#";
    }
    if (StringUtils.isEmpty(info.name)) {
      return null;
    }
    // The whole first code point: substring(0, 1) splits surrogate pairs (emoji)
    return info.name.substring(0, Character.charCount(info.name.codePointAt(0))).toUpperCase();
  }

  /** Topic color (RGB from the server: one of the colors of forumTopicIcon) with full opacity. */
  public static @ColorInt int color (@Nullable TdApi.ForumTopicIcon icon) {
    int rgb = icon != null ? icon.color & 0xFFFFFF : 0;
    return 0xFF000000 | (rgb != 0 ? rgb : DEFAULT_COLOR);
  }

  /** The topic color as an accent color of avatars: the same color in light and dark themes. */
  public static TdlibAccentColor accentColor (TdApi.ForumTopicInfo info) {
    int rgb = color(info.icon) & 0xFFFFFF;
    return new TdlibAccentColor(new TdApi.AccentColor(rgb, TdlibAccentColor.BuiltInId.BLUE, new int[] {rgb}, new int[] {rgb}, 0));
  }
}
