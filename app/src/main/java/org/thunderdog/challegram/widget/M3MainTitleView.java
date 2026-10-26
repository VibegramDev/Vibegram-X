/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.emoji.EmojiInfo;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Size;
import org.thunderdog.challegram.util.EmojiStatusHelper;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.lambda.Destroyable;

/**
 * Title of the main screen app bar: app name followed by the emoji status of the current user.
 * Without a status (or without Telegram Premium) a rocket is shown instead.
 * The status (and the rocket for Premium users) opens the emoji status picker.
 */
public class M3MainTitleView extends View implements Destroyable, TdlibCache.MyUserDataChangeListener, TextColorSet {
  /** M3 small app bar: title large, emphasized for the brand name. */
  private static final float TITLE_SIZE = 22f;
  private static final float MIN_TITLE_SIZE = 18f;
  /** Emoji status: text size 21 gives a 24dp emoji, the size of an app bar icon. */
  private static final int EMOJI_TEXT_SIZE = EmojiStatusHelper.emojiSizeToTextSize(24);
  private static final float EMOJI_GAP = 6f;
  /** M3 app bar leading padding when there is no navigation icon. */
  private static final float START_PADDING = 16f;
  private static final float END_PADDING = 4f;

  private final Tdlib tdlib;
  private final String title = BuildConfig.PROJECT_NAME;
  private final EmojiStatusHelper emojiStatusHelper;
  private final @Nullable Delegate delegate;
  /** Default emoji when the user has no emoji status. */
  private static final String DEFAULT_EMOJI = "\uD83D\uDE80"; // 🚀
  private final @Nullable EmojiInfo defaultEmoji = Emoji.instance().getEmojiInfo(DEFAULT_EMOJI);
  private boolean hasEmojiStatus, isPremium;
  private final Rect defaultEmojiRect = new Rect();
  private boolean defaultEmojiPressed;

  public interface Delegate {
    void onEmojiStatusClick (M3MainTitleView view);
  }

  /** App bar menu: its actions change visibility at runtime, the title gives way to them. */
  private final @IdRes int menuId;

  public M3MainTitleView (Context context, Tdlib tdlib, ViewController<?> themeProvider, @Nullable Delegate delegate, @IdRes int menuId) {
    super(context);
    this.tdlib = tdlib;
    this.menuId = menuId;
    this.emojiStatusHelper = new EmojiStatusHelper(tdlib, this, "main_title");
    this.delegate = delegate;
    if (delegate != null) {
      emojiStatusHelper.setClickListener(v -> delegate.onEmojiStatusClick(this));
    }
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, Size.getHeaderPortraitSize(), Gravity.TOP | (Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT)));
    setContentDescription(title);
    themeProvider.addThemeInvalidateListener(this);
    tdlib.cache().addMyUserListener(this);
    setUser(tdlib.myUser());
  }

  private void setUser (@Nullable TdApi.User user) {
    isPremium = user != null && user.isPremium;
    hasEmojiStatus = isPremium && user.emojiStatus != null;
    if (hasEmojiStatus) {
      emojiStatusHelper.updateEmoji(tdlib, user, this, R.drawable.baseline_premium_star_24, EMOJI_TEXT_SIZE);
    } else {
      // Premium users without a status get the rocket instead of the default premium star.
      emojiStatusHelper.clear();
    }
    requestLayout();
    invalidate();
  }

  @Override
  public void onMyUserUpdated (TdApi.User myUser) {
    if (myUser == null) { // Log out
      return;
    }
    tdlib.ui().post(() -> {
      if (!isDestroyed) {
        setUser(myUser);
      }
    });
  }

  public void setIgnoreDrawEmojiStatus (boolean ignore) {
    emojiStatusHelper.setIgnoreDraw(ignore);
    invalidate();
  }

  // TextColorSet (emoji status tint for themed emoji and the default premium star)

  @Override
  public int defaultTextColor () {
    return Theme.headerTextColor();
  }

  private TextPaint titlePaint () {
    return Paints.getMediumTextPaint(TITLE_SIZE, Theme.headerTextColor(), false);
  }

  private boolean drawEmojiStatus () {
    return hasEmojiStatus && emojiStatusHelper.needDrawEmojiStatus();
  }

  private int emojiWidth () {
    return drawEmojiStatus() ? emojiStatusHelper.getWidth() : defaultEmoji != null ? emojiSize() : 0;
  }

  private int getActionsWidth () {
    ViewParent parent = getParent();
    View menu = parent instanceof View ? ((View) parent).findViewById(menuId) : null;
    if (!(menu instanceof ViewGroup) || menu.getVisibility() == View.GONE) {
      return 0;
    }
    ViewGroup group = (ViewGroup) menu;
    int width = 0;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() != View.GONE && child.getLayoutParams() != null) {
        width += Math.max(0, child.getLayoutParams().width);
      }
    }
    return width;
  }

  /** Whether the emoji fits next to the title in the current width (it is dropped first when space is short). */
  private boolean emojiFits (int width) {
    int emojiWidth = emojiWidth();
    return emojiWidth > 0 && Screen.dp(START_PADDING) + titlePaint().measureText(title) + Screen.dp(EMOJI_GAP) + emojiWidth + Screen.dp(END_PADDING) <= width;
  }

  private static int emojiSize () {
    return Screen.dp(EmojiStatusHelper.textSizeToEmojiSize(EMOJI_TEXT_SIZE));
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int emojiWidth = emojiWidth();
    int width = Screen.dp(START_PADDING) + (int) Math.ceil(titlePaint().measureText(title)) + (emojiWidth > 0 ? Screen.dp(EMOJI_GAP) + emojiWidth : 0) + Screen.dp(END_PADDING);
    int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
    if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && maxWidth > 0) {
      width = Math.min(width, Math.max(0, maxWidth - getActionsWidth()));
    }
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Size.getHeaderPortraitSize(), MeasureSpec.EXACTLY));
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    TextPaint paint = titlePaint();
    boolean rtl = Lang.rtl();
    int width = getMeasuredWidth();
    boolean emojiFits = emojiFits(width);
    // Narrow app bar (actions of the collapsed dock): no emoji, then the title is ellipsized.
    float maxTextWidth = width - Screen.dp(START_PADDING) - Screen.dp(END_PADDING);
    if (maxTextWidth <= 0) {
      return;
    }
    if (!emojiFits && paint.measureText(title) > maxTextWidth) {
      // Then a smaller title size, down to title medium-like 18sp, and only then the title is ellipsized.
      float size = Math.max(MIN_TITLE_SIZE, TITLE_SIZE * maxTextWidth / paint.measureText(title));
      paint = Paints.getMediumTextPaint(size, Theme.headerTextColor(), false);
      if (paint.measureText(title) > maxTextWidth) {
        // Narrow phones with the collapsed dock: a cut "Vibe…" is worse than no title, the actions take the app bar.
        return;
      }
    }
    CharSequence text = title;
    float textWidth = paint.measureText(text, 0, text.length());
    int cy = getMeasuredHeight() / 2;
    float baseline = cy - (paint.ascent() + paint.descent()) / 2f;
    float textLeft = rtl ? width - Screen.dp(START_PADDING) - textWidth : Screen.dp(START_PADDING);
    c.drawText(text, 0, text.length(), textLeft, baseline, paint);
    int emojiWidth = emojiFits ? emojiWidth() : 0;
    if (emojiWidth == 0) {
      defaultEmojiRect.setEmpty();
    }
    if (emojiWidth > 0) {
      int emojiSize = emojiSize();
      int emojiLeft = rtl ? (int) (textLeft - Screen.dp(EMOJI_GAP) - emojiWidth) : (int) (textLeft + textWidth + Screen.dp(EMOJI_GAP));
      if (drawEmojiStatus()) {
        emojiStatusHelper.draw(c, emojiLeft, cy - emojiSize / 2);
      } else {
        defaultEmojiRect.set(emojiLeft, cy - emojiSize / 2, emojiLeft + emojiSize, cy - emojiSize / 2 + emojiSize);
        Rect rect = Paints.getRect();
        rect.set(defaultEmojiRect);
        if (!Emoji.instance().draw(c, defaultEmoji, rect)) {
          // Emoji bitmaps are still loading.
          postInvalidateDelayed(100);
        }
      }
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent event) {
    if (hasEmojiStatus) {
      if (!emojiFits(getMeasuredWidth())) {
        return super.onTouchEvent(event);
      }
      return emojiStatusHelper.onTouchEvent(this, event) || super.onTouchEvent(event);
    }
    // The rocket opens the status picker only for Premium users (others can't set a status).
    if (!isPremium || delegate == null) {
      return super.onTouchEvent(event);
    }
    int slop = Screen.dp(8f);
    boolean inside = event.getX() >= defaultEmojiRect.left - slop && event.getX() <= defaultEmojiRect.right + slop &&
      event.getY() >= defaultEmojiRect.top - slop && event.getY() <= defaultEmojiRect.bottom + slop;
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        defaultEmojiPressed = inside;
        return inside || super.onTouchEvent(event);
      case MotionEvent.ACTION_UP:
        if (defaultEmojiPressed && inside) {
          defaultEmojiPressed = false;
          delegate.onEmojiStatusClick(this);
          return true;
        }
        defaultEmojiPressed = false;
        break;
      case MotionEvent.ACTION_CANCEL:
        defaultEmojiPressed = false;
        break;
    }
    return defaultEmojiPressed || super.onTouchEvent(event);
  }

  /** Last drawn position of the emoji (status or rocket), the picker animates the selected status into it. */
  public int getEmojiLastDrawX () {
    return hasEmojiStatus ? emojiStatusHelper.getLastDrawX() : defaultEmojiRect.left;
  }

  public int getEmojiLastDrawY () {
    return hasEmojiStatus ? emojiStatusHelper.getLastDrawY() : defaultEmojiRect.top;
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    emojiStatusHelper.attach();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    emojiStatusHelper.detach();
  }

  private boolean isDestroyed;

  @Override
  public void performDestroy () {
    isDestroyed = true;
    tdlib.cache().removeMyUserListener(this);
    emojiStatusHelper.performDestroy();
  }
}
