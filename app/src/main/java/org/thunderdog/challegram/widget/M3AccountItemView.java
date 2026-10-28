/*
 * This file is a part of Vibegram X
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
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.telegram.TGLegacyManager;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibBadgeCounter;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.EmojiStatusHelper;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextColorSetOverride;
import org.thunderdog.challegram.util.text.TextColorSets;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * M3 one-line list item for another logged-in account (Settings → «Аккаунты»): 56dp, leading 40dp avatar at 16dp,
 * label (body large) after the 16dp gap, emoji status after the label, trailing unread badge 24dp from the edge
 * ({@link https://m3.material.io/components/lists/specs}). Same data as the old drawer account row.
 */
public class M3AccountItemView extends BaseView implements AttachDelegate, Destroyable, TGLegacyManager.EmojiLoadListener {
  private static final float HEIGHT = 56f, AVATAR_SIZE = 40f, PADDING = 16f;

  private final AvatarReceiver receiver;
  private final EmojiStatusHelper emojiStatusHelper;
  private final Counter counter;

  private @Nullable String name;
  private @Nullable Text trimmedName;
  private float lastAvailWidth;

  public M3AccountItemView (Context context, Tdlib tdlib) {
    super(context, tdlib);
    receiver = new AvatarReceiver(this);
    emojiStatusHelper = new EmojiStatusHelper(tdlib, this, null);
    counter = new Counter.Builder().callback((counter, sizeChanged) -> {
      if (sizeChanged) {
        trimName(false);
      }
      invalidate();
    }).build();
    TGLegacyManager.instance().addEmojiListener(this);
    setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(HEIGHT)));
  }

  public void setAccount (TdlibAccount account, boolean isUpdate) {
    receiver.requestAccount(tdlib, account.id, AvatarReceiver.Options.NONE);
    TextColorSet colorSet = new TextColorSetOverride(TextColorSets.Regular.NORMAL) {
      @Override
      public long mediaTextComplexColor () {
        return Theme.newComplexColor(true, ColorId.iconActive);
      }
    };
    emojiStatusHelper.setSharedUsageId("settings_account_" + account.id);
    emojiStatusHelper.updateEmoji(account, colorSet);
    TdlibBadgeCounter badge = account.getUnreadBadge();
    counter.setCount(badge.getCount(), badge.isMuted(), isUpdate);
    String name = Lang.getDebugString(account.getName(), account.isDebug());
    setContentDescription(name);
    if (!StringUtils.equalsOrBothEmpty(this.name, name)) {
      this.name = name;
    }
    trimName(true);
    invalidate();
  }

  @Override
  public void onEmojiUpdated (boolean isPackSwitch) {
    invalidate();
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    trimName(false);
  }

  private int textStart () {
    return Screen.dp(PADDING + AVATAR_SIZE + PADDING);
  }

  private void trimName (boolean force) {
    if (StringUtils.isEmpty(name)) {
      trimmedName = null;
      return;
    }
    int availWidth = getMeasuredWidth() - textStart() - Screen.dp(PADDING);
    availWidth -= counter.getScaledWidth(Screen.dp(8f));
    if (emojiStatusHelper.needDrawEmojiStatus()) {
      availWidth -= emojiStatusHelper.getWidth() + Screen.dp(6f);
    }
    if (availWidth <= 0) {
      trimmedName = null;
      return;
    }
    if (force || lastAvailWidth != availWidth || trimmedName == null) {
      lastAvailWidth = availWidth;
      trimmedName = new Text.Builder(name, availWidth, Paints.robotoStyleProvider(16), TextColorSets.Regular.NORMAL).singleLine().build();
    }
  }

  @Override
  protected void onDraw (Canvas c) {
    final boolean rtl = Lang.rtl();
    final int width = getMeasuredWidth();
    final int height = getMeasuredHeight();
    final int avatarSize = Screen.dp(AVATAR_SIZE);
    final int avatarStart = Screen.dp(PADDING);
    final int avatarTop = (height - avatarSize) / 2;
    if (rtl) {
      receiver.setBounds(width - avatarStart - avatarSize, avatarTop, width - avatarStart, avatarTop + avatarSize);
    } else {
      receiver.setBounds(avatarStart, avatarTop, avatarStart + avatarSize, avatarTop + avatarSize);
    }
    if (receiver.needPlaceholder()) {
      receiver.drawPlaceholder(c);
    }
    receiver.draw(c);

    int textStart = textStart();
    if (trimmedName != null) {
      int textWidth = trimmedName.getWidth();
      int textTop = (height - trimmedName.getHeight()) / 2;
      int left = rtl ? width - textStart - textWidth : textStart;
      trimmedName.draw(c, left, left + textWidth, 0, textTop);
      if (emojiStatusHelper.needDrawEmojiStatus()) {
        int emojiWidth = emojiStatusHelper.getWidth();
        int emojiLeft = rtl ? left - Screen.dp(6f) - emojiWidth : left + textWidth + Screen.dp(6f);
        emojiStatusHelper.draw(c, emojiLeft, textTop);
      }
    }
    counter.draw(c, rtl ? Screen.dp(24f) : width - Screen.dp(24f), height / 2f, rtl ? Gravity.LEFT : Gravity.RIGHT, 1f, this, ColorId.badgeFailedText);
  }

  @Override
  public void attach () {
    receiver.attach();
    emojiStatusHelper.attach();
  }

  @Override
  public void detach () {
    receiver.detach();
    emojiStatusHelper.detach();
  }

  @Override
  public void performDestroy () {
    receiver.destroy();
    emojiStatusHelper.performDestroy();
  }
}
