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
 * File created on 14/02/2016 at 22:17
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

public class CheckBoxView extends View {
  private final BoolAnimator isChecked = new BoolAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
  private final BoolAnimator isHidden = new BoolAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
  private final BoolAnimator isPartially = new BoolAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
  private final BoolAnimator isDisabled = new BoolAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
  private final BoolAnimator isCircle = new BoolAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l);
  // TODO isIntermediate state, when check angle smoothly changes from 90 to 180 degrees

  public CheckBoxView (Context context) {
    super(context);
  }

  public void setChecked (boolean checked, final boolean animated) {
    isChecked.setValue(checked, animated);
  }

  public void setHidden (boolean hidden, final boolean animated) {
    isHidden.setValue(hidden, animated);
  }

  public void setPartially (boolean partially, boolean animated) {
    isPartially.setValue(partially, animated);
  }

  public void setCircular (boolean circular, boolean animated) {
    isCircle.setValue(circular, animated);
  }

  public boolean toggle () {
    return isChecked.toggleValue(true);
  }

  public void setDisabled (boolean disabled, boolean animated) {
    isDisabled.setValue(disabled, animated);
  }

  // Internal

  private static final float FACTOR_DIFF = .65f;
  private static final float SCALE_DIFF = .15f;

  @Override
  protected void onDraw (Canvas c) {
    final float showFactor = 1f - isHidden.getFloatValue();
    if (showFactor == 0f) {
      return;
    }
    final int paddingLeft = getPaddingLeft();
    final int paddingTop = getPaddingTop();
    final int size = Math.min(getMeasuredWidth() - paddingLeft - getPaddingRight(), getMeasuredHeight() - paddingTop - getPaddingBottom());
    // M3 CheckboxTokens: unselected outline onSurfaceVariant, selected container primary with an onPrimary check;
    // disabled: outline and container onSurface 38%, check surface.
    final float disabledFactor = isDisabled.getFloatValue();
    final int disabledColor = ColorUtils.alphaColor(.38f, M3.onSurface());
    drawBox(c, paddingLeft + size / 2f, paddingTop + size / 2f, size, isChecked.getFloatValue(), isPartially.getFloatValue(), isCircle.getFloatValue(), showFactor,
      ColorUtils.fromToArgb(M3.onSurfaceVariant(), disabledColor, disabledFactor),
      ColorUtils.fromToArgb(M3.primary(), disabledColor, disabledFactor),
      ColorUtils.fromToArgb(M3.onPrimary(), M3.surface(), disabledFactor)
    );
  }

  /**
   * M3-coloured checkbox in Telegram's CheckBoxSquare geometry (18dp, 2dp corners, 2dp outline) drawn by a parent view, e.g. next to a selectable message.
   * Unchecked: {@code outlineColor} outline. Checked: {@code fillColor} container with a {@code checkColor} check mark.
   */
  public static void drawCheckBox (Canvas c, float cx, float cy, float factor, float alpha, int outlineColor, int fillColor, int checkColor) {
    drawBox(c, cx, cy, Screen.dp(18f), factor, 0f, 0f, alpha, outlineColor, fillColor, checkColor);
  }

  private static Path boxPath;

  private static void drawBox (Canvas c, float cx, float cy, float size, float factor, float partFactor, float circleFactor, float alpha, int outlineColor, int fillColor, int checkColor) {
    if (alpha <= 0f) {
      return;
    }
    final float rectFactor = Math.min(factor / FACTOR_DIFF, 1f);
    final float checkFactor = factor <= FACTOR_DIFF ? 0f : (factor - FACTOR_DIFF) / (1f - FACTOR_DIFF);
    final float scaleFactor = 1f - (rectFactor == 1f ? 1f - checkFactor : rectFactor) * SCALE_DIFF;
    final float strokeWidth = Screen.dp(2f);
    final float half = size / 2f;
    // 2dp container corners (Telegram CheckBoxSquare), a circle for quiz answers.
    final float radius = MathUtils.fromTo(strokeWidth, half, circleFactor);
    // Unchecked: 2dp outline. Checking fills the container from the outline to the centre.
    final float hole = (half - strokeWidth) * (1f - rectFactor);
    final float holeRadius = Math.max(radius - strokeWidth, 0f) * (1f - rectFactor);

    if (boxPath == null) {
      boxPath = new Path();
      boxPath.setFillType(Path.FillType.EVEN_ODD);
    }
    boxPath.reset();
    RectF rect = Paints.getRectF();
    rect.set(cx - half, cy - half, cx + half, cy + half);
    boxPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
    if (hole > 0f) {
      rect.set(cx - hole, cy - hole, cx + hole, cy + hole);
      boxPath.addRoundRect(rect, holeRadius, holeRadius, Path.Direction.CW);
    }

    final int restoreToCount = Views.save(c);
    c.scale(scaleFactor, scaleFactor, cx, cy);
    c.drawPath(boxPath, Paints.fillingPaint(ColorUtils.alphaColor(alpha, ColorUtils.fromToArgb(outlineColor, fillColor, rectFactor))));
    if (checkFactor > 0f) {
      final float markScale = size / Screen.dpf(18f) * (1f - .15f * circleFactor);
      drawCheckMark(c, cx, cy, MARK_BOX, markScale, checkFactor, partFactor, ColorUtils.alphaColor(alpha, checkColor));
    }
    Views.restore(c, restoreToCount);
  }

  // Check marks of Telegram for Android: centre line {start x, start y, corner x, corner y, end x, end y, stroke}
  // in dp from the centre of the container, drawn with round caps and joins.

  /** CheckBoxSquare: the mark inside the 18dp box, 2dp stroke. */
  public static final float[] MARK_BOX = {-5f, 1f, -2f, 4f, 5f, -3f, 2f};
  /** CheckBoxBase: the mark inside the round checkbox (20–24dp), short arm 4dp, long arm 9dp, 1.9dp stroke. */
  public static final float[] MARK_CIRCLE = {-4.33f, 1.17f, -1.5f, 4f, 4.86f, -2.36f, 1.9f};
  /** ic_ab_done: the 24dp check icon, 2dp stroke. */
  public static final float[] MARK_CHECK = {-7.33f, 1.33f, -2.83f, 5.33f, 7.67f, -5f, 2f};

  private static Paint markPaint;
  private static Path markPath;

  /**
   * Draws {@code mark} scaled by {@code scale} (stroke included). {@code fraction} draws it from the start of the short
   * arm to the end of the long one, {@code dash} flattens it into the indeterminate dash.
   */
  public static void drawCheckMark (Canvas c, float cx, float cy, float[] mark, float scale, float fraction, float dash, int color) {
    if (fraction <= 0f || Color.alpha(color) == 0) {
      return;
    }
    final float dp = Screen.dpf(1f) * scale;
    // The indeterminate dash keeps 8dp between the cap centres (12dp in the 14dp inside of the box at most)
    final float x1 = cx + MathUtils.fromTo(mark[0], -4f, dash) * dp;
    final float y1 = cy + MathUtils.fromTo(mark[1], 0f, dash) * dp;
    final float x2 = cx + MathUtils.fromTo(mark[2], 0f, dash) * dp;
    final float y2 = cy + MathUtils.fromTo(mark[3], 0f, dash) * dp;
    final float x3 = cx + MathUtils.fromTo(mark[4], 4f, dash) * dp;
    final float y3 = cy + MathUtils.fromTo(mark[5], 0f, dash) * dp;

    if (markPaint == null) {
      markPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
      markPaint.setStyle(Paint.Style.STROKE);
      markPaint.setStrokeCap(Paint.Cap.ROUND);
      markPaint.setStrokeJoin(Paint.Join.ROUND);
      markPath = new Path();
    }
    markPaint.setColor(color);
    markPaint.setStrokeWidth(Screen.dpf(mark.length > 6 ? mark[6] : 2f) * scale);

    markPath.reset();
    markPath.moveTo(x1, y1);
    if (fraction >= 1f) {
      markPath.lineTo(x2, y2);
      markPath.lineTo(x3, y3);
    } else {
      final float length1 = (float) Math.hypot(x2 - x1, y2 - y1);
      final float length2 = (float) Math.hypot(x3 - x2, y3 - y2);
      final float length = (length1 + length2) * fraction;
      if (length <= length1) {
        final float f = length1 > 0f ? length / length1 : 1f;
        markPath.lineTo(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f);
      } else {
        final float f = length2 > 0f ? (length - length1) / length2 : 1f;
        markPath.lineTo(x2, y2);
        markPath.lineTo(x2 + (x3 - x2) * f, y2 + (y3 - y2) * f);
      }
    }
    c.drawPath(markPath, markPaint);
  }

  @Override
  public boolean onTouchEvent (MotionEvent event) {
    boolean isEnabled = isEnabled();
    return isEnabled && super.onTouchEvent(event);
  }

  public static CheckBoxView simpleCheckBox (Context context) {
    return simpleCheckBox(context, Lang.rtl());
  }

  public static CheckBoxView simpleCheckBox (Context context, boolean rtl) {
    FrameLayoutFix.LayoutParams params;

    params = FrameLayoutFix.newParams(Screen.dp(18f), Screen.dp(18f));
    params.gravity = Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT);
    params.leftMargin = params.rightMargin = Screen.dp(19f);

    CheckBoxView checkBox;
    checkBox = new CheckBoxView(context);
    checkBox.setLayoutParams(params);

    return checkBox;
  }
}
