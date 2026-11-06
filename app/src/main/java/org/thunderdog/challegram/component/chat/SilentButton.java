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
 * File created on 13/03/2016 at 16:47
 */
package org.thunderdog.challegram.component.chat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.core.ColorUtils;

public class SilentButton extends View {
  private boolean isSilent;

  private final Drawable icon;

  public SilentButton (Context context) {
    super(context);
    icon = Drawables.get(getResources(), R.drawable.outline_notifications_24);
  }

  public boolean setVisible (boolean visible) {
    boolean isVisible = getVisibility() == VISIBLE;
    if (isVisible != visible) {
      setVisibility(visible ? VISIBLE : INVISIBLE);
      return true;
    }
    return false;
  }

  public void forceState (boolean isSilent) {
    if (lastAnimator != null) {
      lastAnimator.cancel();
      lastAnimator = null;
    }
    this.isSilent = isSilent;
    factor = isSilent ? 1f : 0f;
  }

  public boolean getIsSilent () {
    return isSilent;
  }

  private ValueAnimator lastAnimator;
  public boolean toggle () {
    setValue(!isSilent);
    return isSilent;
  }

  public void setValue (boolean value) {
    if (this.isSilent == value)
      return;
    this.isSilent = value;
    ValueAnimator obj;
    final float startFactor = getFactor();
    obj = AnimatorUtils.simpleValueAnimator();
    if (isSilent) {
      final float diffFactor = 1f - startFactor;
      obj.addUpdateListener(animation -> setFactor(startFactor + diffFactor * AnimatorUtils.getFraction(animation)));
    } else {
      obj.addUpdateListener(animation -> setFactor(startFactor - startFactor * AnimatorUtils.getFraction(animation)));
    }
    obj.setDuration(150l);
    obj.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    if (lastAnimator != null) {
      lastAnimator.cancel();
    }
    lastAnimator = obj;

    obj.start();
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    return getVisibility() == View.VISIBLE && super.onTouchEvent(e);
  }

  private float factor;

  public void setFactor (float factor) {
    if (this.factor != factor) {
      this.factor = factor;
      invalidate();
    }
  }

  public float getFactor () {
    return factor;
  }

  @Override
  protected void onDraw (Canvas c) {
    if (getVisibility() != View.VISIBLE) {
      return;
    }

    int width = getMeasuredWidth();
    float cx = (float) width * .5f;
    float cy = (float) getMeasuredHeight() * .5f;

    Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2, cy - icon.getMinimumHeight() / 2, Paints.getIconGrayPorterDuffPaint());
    if (factor == 0f) {
      return;
    }

    // Slash of Material Symbols "notifications_off"
    int alpha = (int) (255f * Math.min(1f, ((Screen.dpf(24f) * factor) / Screen.dpf(8f))));
    DrawAlgorithms.drawCross(c, cx, cy, factor,
      alpha == 255 ? Theme.iconColor() : ColorUtils.color(alpha, Theme.iconColor()),
      ColorUtils.color((int) (255f * factor), Theme.fillingColor())
    );
  }
}
