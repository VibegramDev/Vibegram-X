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
 * File created on 22/02/2018
 */
package org.thunderdog.challegram.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;

public class ExpanderView implements FactorAnimator.Target {
  private final Path path;
  private final Paint paint;
  private final BoolAnimator expandAnimator = new BoolAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  private Counter counter;

  private final View parentView;

  public ExpanderView (View parentView) {
    this.parentView = parentView;
    this.path = new Path();

    // Telegram `msg_expand` (the account switcher arrow of DrawerProfileCell): 1.67dp chevron with round caps and
    // join, 12.5dp wide, 6.2dp tall (centre line, 90° tip), centred on the rotation point.
    float halfWidth = Screen.dpf(6.25f);
    float halfHeight = Screen.dpf(3.1f);

    path.moveTo(-halfWidth, -halfHeight);
    path.lineTo(0, halfHeight);
    path.lineTo(halfWidth, -halfHeight);

    this.paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Screen.dpf(DrawAlgorithms.GLYPH_STROKE_DP));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  public void setUnreadCount (int unreadCount, boolean isMuted, boolean animated) {
    if (unreadCount <= 0 && counter == null) {
      return;
    }
    if (counter == null) {
      counter = new Counter.Builder().callback(parentView).colorSet(new TextColorSet() {
        @Override
        public int defaultTextColor () {
          return ColorUtils.fromToArgb(0xff000000, 0x7f000000, counter.getMuteFactor());
        }

        @Override
        public int backgroundColor (boolean isPressed) {
          return ColorUtils.fromToArgb(0xffffffff, 0x7fffffff, counter.getMuteFactor());
        }
      }).build();
    }
    counter.setCount(unreadCount, isMuted, animated);
  }

  public boolean toggleExpanded () {
    boolean newValue;
    setExpanded(newValue = !expandAnimator.getValue(), true);
    return newValue;
  }

  public void setExpanded (boolean isExpanded, boolean animated) {
    expandAnimator.setValue(isExpanded, animated);
  }

  private float expandFactor;

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (this.expandFactor != factor) {
      this.expandFactor = factor;
      parentView.invalidate();
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) { }

  public final void draw (Canvas c, int cx, int cy, int color) {
    c.save();
    c.translate(cx, cy);
    float degrees = 180 * expandFactor;
    if (degrees != 0) {
      c.rotate(degrees);
    }
    paint.setColor(color);
    c.drawPath(path, paint);
    c.restore();

    if (counter != null && expandFactor < 1f) {
      boolean rtl = Lang.rtl();
      cx += Screen.dp(24f) * (rtl ? 1 : -1);
      cy -= Screen.dp(2f);
      c.save();
      c.scale(.85f, .85f, cx, cy);
      counter.draw(c, cx, cy, rtl ? Gravity.LEFT : Gravity.RIGHT, 1f - expandFactor);
      c.restore();
    }
  }
}
