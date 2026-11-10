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
 *
 * File created on 02/11/2016
 */
package org.thunderdog.challegram.tool;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.ColorId;

import me.vkryl.core.BitwiseUtils;

public class Icons {
  public static void reset () {
    livePinIcon = null;
    chatSelfDrawable = null;
    chatVerifyDrawable = null;
    secureDrawable = null;
    secureSmallDrawable = null;
    if (sparseDrawables != null)
      sparseDrawables.clear();
    livePinIcon = null;
  }

  private static Bitmap livePinIcon;

  private static Drawable chatSelfDrawable;
  public static Drawable getChatSelfDrawable () {
    if (chatSelfDrawable == null) {
      chatSelfDrawable = Drawables.get(UI.getResources(), R.drawable.baseline_bookmark_24);
    }
    return chatSelfDrawable;
  }

  private static Drawable chatVerifyDrawable;
  public static Drawable getChatVerifyDrawable () {
    if (chatVerifyDrawable == null) {
      chatVerifyDrawable = Drawables.get(UI.getResources(), R.drawable.deproko_baseline_verify_chat_24);
    }
    return chatVerifyDrawable;
  }

  public static int getEditedIconWidth () {
    return Screen.dp(12f);
  }

  private static Drawable secureDrawable;
  public static Drawable getSecureDrawable () {
    if (secureDrawable == null) {
      secureDrawable = Drawables.get(UI.getResources(), R.drawable.deproko_baseline_lock_24);
    }
    return secureDrawable;
  }

  private static Drawable secureSmallDrawable;
  public static Drawable getSecureSmallDrawable () {
    if (secureSmallDrawable == null) {
      secureSmallDrawable = Drawables.get(UI.getResources(), R.drawable.deproko_baseline_lock_18);
    }
    return secureSmallDrawable;
  }

  // Sparse

  private static LongSparseArray<Drawable> sparseDrawables;

  public static @Nullable Drawable getSparseDrawable (@DrawableRes int res, @ColorId int colorId) {
    if (colorId == 0) {
      return null;
    }
    if (!(res == R.drawable.deproko_baseline_check_single_24 ||
      res == R.drawable.deproko_baseline_check_double_24 ||
      res == R.drawable.deproko_baseline_clock_24 ||
      res == R.drawable.deproko_baseline_notifications_off_24)) {
      return null;
    }
    long key = BitwiseUtils.mergeLong(res, colorId);
    if (sparseDrawables == null) {
      sparseDrawables = new LongSparseArray<>();
      Drawable drawable = Drawables.get(UI.getResources(), res);
      sparseDrawables.put(key, drawable);
      return drawable;
    }
    Drawable drawable = sparseDrawables.get(key);
    if (drawable == null) {
      drawable = Drawables.get(UI.getResources(), res);
      sparseDrawables.put(key, drawable);
    }
    return drawable;
  }

  private static Drawable calendarSmallDrawable;

  public static Drawable getCalendarSmallDrawable () {
    if (calendarSmallDrawable == null)
      calendarSmallDrawable = Drawables.get(R.drawable.baseline_watch_later_10);
    return calendarSmallDrawable;
  }

  public static Bitmap getLivePin () {
    if (livePinIcon == null) {
      livePinIcon = BitmapFactory.decodeResource(UI.getResources(), R.drawable.bg_livepin);
    }
    return livePinIcon;
  }

  public static Drawable getChatMuteDrawable (@ColorId int colorId) {
    return getSparseDrawable(R.drawable.deproko_baseline_notifications_off_24, colorId);
  }

  public static int getChatMuteDrawableWidth () {
    return Screen.dp(18f);
  }

  public static Drawable getSingleTick (@ColorId int colorId) {
    return getSparseDrawable(R.drawable.deproko_baseline_check_single_24, colorId);
  }

  public static int getSingleTickWidth () {
    return Screen.dp(18f);
  }

  private static android.util.SparseArray<android.graphics.RectF> tickGlyphBounds;

  /**
   * Bounds of the visible glyph inside a tick drawable, as fractions of its box (scanned once from the bitmap alpha):
   * tg_ticks.py composes both ticks into the same 24dp box, so these fractions let callers size and align
   * the glyphs themselves rather than the padded box.
   */
  public static android.graphics.RectF getTickGlyphBounds (@DrawableRes int res) {
    if (tickGlyphBounds == null) {
      tickGlyphBounds = new android.util.SparseArray<>();
    }
    android.graphics.RectF bounds = tickGlyphBounds.get(res);
    if (bounds == null) {
      bounds = new android.graphics.RectF(0f, 0f, 1f, 1f);
      Drawable d = Drawables.get(res);
      if (d instanceof android.graphics.drawable.BitmapDrawable) {
        android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        int w = b.getWidth(), h = b.getHeight();
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
          for (int x = 0; x < w; x++) {
            if ((b.getPixel(x, y) >>> 24) > 0x40) {
              if (x < left) left = x;
              if (x > right) right = x;
              if (y < top) top = y;
              if (y > bottom) bottom = y;
            }
          }
        }
        if (right >= left && bottom >= top) {
          bounds.set((float) left / w, (float) top / h, (float) (right + 1) / w, (float) (bottom + 1) / h);
        }
      }
      tickGlyphBounds.put(res, bounds);
    }
    return bounds;
  }

  public static Drawable getDoubleTick (@ColorId int colorId) {
    return getSparseDrawable(R.drawable.deproko_baseline_check_double_24, colorId);
  }

  public static Drawable getClockIcon (@ColorId int colorId) {
    return getSparseDrawable(R.drawable.deproko_baseline_clock_24, colorId);
  }

  public static int getClockIconWidth () {
    return Screen.dp(12f);
  }

  public static float CLOCK_SHIFT_X = 6f;
  public static float CLOCK_SHIFT_Y = 6f;

  public static float TICKS_SHIFT_X = 3f;
  public static float TICKS_SHIFT_Y = 6f;
}
