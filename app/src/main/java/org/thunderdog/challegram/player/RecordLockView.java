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
 * File created on 10/12/2017
 */
package org.thunderdog.challegram.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.PorterDuffPaint;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

public class RecordLockView extends View {
  public static final int BUTTON_SIZE = RecordControllerButton.BUTTON_SIZE;
  public static final int BUTTON_EXPANDED = 33;

  private final Drawable drawableVoice;
  private final Drawable drawableRound;

  public RecordLockView (Context context) {
    super(context);

    drawableVoice = Drawables.get(context.getResources(), R.drawable.baseline_mic_24);
    drawableRound = Drawables.get(context.getResources(), R.drawable.deproko_baseline_msg_video_24);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new android.view.ViewOutlineProvider() {
        @Override
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        public void getOutline (View view, android.graphics.Outline outline) {
          outline.setRoundRect(0, 0, view.getMeasuredWidth(), (int) (view.getMeasuredHeight() - Screen.dp(BUTTON_EXPANDED) * collapseFactor), Screen.dp(BUTTON_SIZE / 2f));
        }
      });
    }
    setLayoutParams(new ViewGroup.LayoutParams(Screen.dp(BUTTON_SIZE), Screen.dp(BUTTON_SIZE + BUTTON_EXPANDED)));
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      float bottom = getMeasuredHeight() - Screen.dp(BUTTON_EXPANDED) * collapseFactor;
      if (e.getY() > bottom) {
        return false;
      }
    }

    return Views.onTouchEvent(this, e) && super.onTouchEvent(e);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    setPivotX(getMeasuredWidth() / 2);
    setPivotY(getCenterY());
  }

  private float getCenterY () {
    return (int) (getMeasuredHeight() - Screen.dp(BUTTON_EXPANDED) * collapseFactor) - Screen.dp(BUTTON_SIZE / 2f);
  }

  private float collapseFactor;

  public void setCollapseFactor (float factor) {
    if (this.collapseFactor != factor) {
      this.collapseFactor = factor;
      setPivotY(getCenterY());
      invalidate();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        invalidateOutline();
      }
    }
  }

  private float sendFactor;

  public void setSendFactor (float sendFactor) {
    if (this.sendFactor != sendFactor) {
      this.sendFactor = sendFactor;
      invalidate();
    }
  }

  private float editFactor;

  public void setEditFactor (float editFactor) {
    if (this.editFactor != editFactor) {
      this.editFactor = editFactor;
      invalidate();
    }
  }

  public static final int MODE_DEFAULT = 0;
  public static final int MODE_AUDIO = 1;
  public static final int MODE_VIDEO = 2;

  private int mode = MODE_DEFAULT;

  public void setMode (int mode) {
    this.mode = mode;
  }


  private static final RectF tmpRect = new RectF();
  private final Path pausePath = new Path(), shacklePath = new Path();

  @Override
  protected void onDraw (Canvas c) {
    int fillingColor = Theme.fillingColor();

    RectF rectF = Paints.getRectF();
    final int viewWidth = getMeasuredWidth();
    final int viewHeight = getMeasuredHeight();
    rectF.set(0, 0, viewWidth, viewHeight - Screen.dp(BUTTON_EXPANDED) * collapseFactor);
    int radius = Screen.dp(BUTTON_SIZE) / 2;
    c.drawRoundRect(rectF, radius, radius, Paints.fillingPaint(fillingColor));

    int bottomCy = (int) rectF.bottom - radius;

    int cx = viewWidth / 2;
    int cy = Screen.dp(BUTTON_SIZE) / 2;

    final int grayColor = Theme.iconColor();
    final int redColor = Theme.getColor(ColorId.iconNegative);

    // Lock: Material Symbols "lock" (filled body): 16 x 14dp body with 2dp corners and a 2dp keyhole, shackle of
    // radius 4dp (stroke centre) standing 2dp above the body. As in the 24dp symbol, the body centre sits 3dp low.
    int totalDy = (int) (Screen.dp(3f) * collapseFactor * (1f - sendFactor));

    int width = (int) (Screen.dp(6f) + Screen.dp(2f) * (1f - sendFactor));
    int height = (int) (Screen.dp(6f) + Screen.dp(1f) * (mode == MODE_DEFAULT ? (1f - sendFactor) : 1f));
    int dy = (int) (Screen.dp(BUTTON_SIZE) / 3f * (1f - collapseFactor));
    rectF.set(cx - width, cy - height + dy + totalDy, cx + width, cy + height + dy + totalDy);

    final float bodyRadius = Screen.dp(2f);
    if (mode == MODE_DEFAULT) {
      // Turns into Material Symbols "stop" (FILL 1): 12dp square with 2dp corners
      c.drawRoundRect(rectF, bodyRadius, bodyRadius, Paints.fillingPaint(ColorUtils.fromToArgb(grayColor, redColor, sendFactor)));
    } else {
      final float pauseAlpha = 1f - editFactor;
      final float alpha = editFactor;

      if (editFactor < 1f) {
        // Splits into the bars of Material Symbols "pause" (FILL 1): 4 x 14dp, 4dp apart, fully rounded
        final float halfGap = Screen.dp(2f);
        final float seam = halfGap * (1f - sendFactor * 2f);
        pausePath.reset();
        tmpRect.set(rectF.left, rectF.top, cx + seam, rectF.bottom);
        pausePath.addRoundRect(tmpRect, bodyRadius, bodyRadius, Path.Direction.CW);
        tmpRect.set(cx - seam, rectF.top, rectF.right, rectF.bottom);
        pausePath.addRoundRect(tmpRect, bodyRadius, bodyRadius, Path.Direction.CW);
        c.drawPath(pausePath, Paints.fillingPaint(ColorUtils.alphaColor(pauseAlpha, grayColor)));
        // Keyhole, stretching into the gap between the bars
        final float h = MathUtils.fromTo(halfGap, rectF.height() / 2f + halfGap, sendFactor);
        tmpRect.set(cx - halfGap, rectF.centerY() - h, cx + halfGap, rectF.centerY() + h);
        c.drawRoundRect(tmpRect, halfGap, halfGap, Paints.fillingPaint(ColorUtils.alphaColor(pauseAlpha, fillingColor)));
      }

      if (editFactor > 0f) {
        Drawables.drawCentered(c, mode == MODE_VIDEO ? drawableRound : drawableVoice, rectF.centerX(), rectF.centerY(), PorterDuffPaint.get(ColorId.icon, alpha));
      }
    }

    if (sendFactor < 1f) {
      if (mode == MODE_DEFAULT) {
        c.drawCircle(cx, rectF.centerY(), Screen.dp(2f), Paints.fillingPaint(ColorUtils.alphaColor(1f - sendFactor, fillingColor)));
      }
      // Shackle, lifted by half of dy while the lock is open: the left leg stays in the body, the right one opens
      final float shackleRadius = Screen.dp(4f);
      final float legHeight = Screen.dp(2f);
      final float bodyTop = rectF.top;
      final float arcCy = bodyTop - legHeight - dy / 2f;
      tmpRect.set(cx - shackleRadius, arcCy - shackleRadius, cx + shackleRadius, arcCy + shackleRadius);
      shacklePath.reset();
      shacklePath.moveTo(cx - shackleRadius, bodyTop);
      shacklePath.arcTo(tmpRect, 180f, 180f, false);
      shacklePath.rLineTo(0, legHeight);
      c.save();
      c.clipRect(0, 0, viewWidth, bodyTop);
      c.drawPath(shacklePath, DrawAlgorithms.getGlyphPaint(ColorUtils.alphaColor(1f - sendFactor, grayColor), Screen.dp(2f)));
      c.restore();
    }

    if (collapseFactor < 1f) {
      DrawAlgorithms.drawDirection(c, cx, bottomCy, ColorUtils.alphaColor(1f - (collapseFactor >= .5f ? 1f : collapseFactor / .5f), grayColor), Gravity.TOP);
    }
  }


}
