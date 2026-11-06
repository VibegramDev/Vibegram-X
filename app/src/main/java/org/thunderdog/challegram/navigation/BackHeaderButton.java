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
 * File created on 13/02/2016 at 12:53
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemedColorAnimator;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BounceAnimator;
import me.vkryl.android.util.SingleViewProvider;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

public class BackHeaderButton extends HeaderButton implements View.OnClickListener {
  public static final int TYPE_NONE = 1;
  public static final int TYPE_MENU = 2;
  public static final int TYPE_BACK = 3;
  public static final int TYPE_CLOSE = 4;

  private boolean reverseAngle;
  private float factor;
  /** Horizontal shift of the drawn button (circle and arrow), e.g. to sit inside a contained search bar. */
  private float contentOffsetX;

  public void setContentOffsetX (float offsetX) {
    if (this.contentOffsetX != offsetX) {
      this.contentOffsetX = offsetX;
      // The shifted circle goes past the right edge of the 56dp button: let the header draw it unclipped meanwhile.
      if (getParent() instanceof ViewGroup) {
        ((ViewGroup) getParent()).setClipChildren(offsetX == 0f);
      }
      invalidate();
    }
  }

  private HeaderView parentHeader;
  private final Paint paint;

  public BackHeaderButton (Context context) {
    super(context);

    paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    paint.setStrokeWidth(Screen.dp(2f));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setColor(Theme.headerBackColor());
  }

  private int color;

  public void setColor (int color) {
    if (this.color != color) {
      this.color = color;
      paint.setColor(color);
      invalidate();
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (parentHeader != null && e.getAction() == MotionEvent.ACTION_DOWN) {
      parentHeader.onBackTouchDown(e);
    }
    return super.onTouchEvent(e);
  }

  public int getColor () {
    return color;
  }

  public void setParentHeader (HeaderView parent) {
    this.parentHeader = parent;
  }

  public void setIsReverse (boolean isReverse) {
    this.reverseAngle = isReverse;
  }

  public float getFactor () {
    return factor;
  }

  public void setFactor (float factor) {
    if (this.factor != factor) {
      this.factor = factor;
      invalidate();
    }
  }

  public static float toButtonFactor (int type) {
    switch (type) {
      case BackHeaderButton.TYPE_NONE:
      case BackHeaderButton.TYPE_MENU:
        return 0f;
      case BackHeaderButton.TYPE_BACK:
        return 1f;
      case BackHeaderButton.TYPE_CLOSE:
        return 2f;
    }
    return 0f;
  }

  public void setButtonFactor (int type) {
    if (type != BackHeaderButton.TYPE_NONE) {
      setFactor(toButtonFactor(type));
    }
  }

  @Override
  public void onClick (View view) {
    if (parentHeader != null) {
      if (parentHeader.inSelectMode()) {
        parentHeader.closeSelectMode(true, true);
        return;
      }
      if (parentHeader.inSearchMode()) {
        parentHeader.closeSearchMode(true, null);
        return;
      }
    }
    BaseActivity context = UI.getContext(getContext());
    if (context != null) {
      if (this.factor == 0f) { // Menu button
        if (context.getDrawer() != null && !context.getDrawer().isVisible()) {
          context.getDrawer().open();
        }
      } else {
        context.performBackPress(true);
      }
    }
  }

  private final ThemedColorAnimator menuHintBadgeColor = new ThemedColorAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l, ColorId.headerBadgeFailed);
  private final BounceAnimator menuHintBadgeVisible = new BounceAnimator(new SingleViewProvider(this));

  public void hideMenuBadge (boolean animated) {
    setMenuBadge(ColorId.NONE, null, animated);
  }

  public void setMenuBadge (@ColorId int colorId, boolean animated) {
    setMenuBadge(colorId, null, animated);
  }

  public void setMenuBadge (@ColorId int colorId, @Nullable ThemeDelegate forcedTheme, boolean animated) {
    animated = animated && (factor >= 0f && factor < 1f);
    boolean needShow = colorId != ColorId.NONE;
    if (needShow) {
      menuHintBadgeColor.setValue(colorId, forcedTheme, animated && menuHintBadgeVisible.getFloatValue() > 0f);
    }
    menuHintBadgeVisible.setValue(needShow, animated);
  }

  @Override
  public void onDraw (Canvas c) {
    if (contentOffsetX != 0f) {
      c.save();
      c.translate(Lang.rtl() ? -contentOffsetX : contentOffsetX, 0);
      drawButton(c);
      c.restore();
    } else {
      drawButton(c);
    }
  }

  private void drawButton (Canvas c) {
    // M3 back button: the arrow in a tonal circle (as in Android settings). Fades in from the menu icon.
    float containerFactor = MathUtils.clamp(factor);
    if (containerFactor > 0f) {
      float cx = getPaddingLeft() + getMeasuredWidth() / 2f, cy = getMeasuredHeight() / 2f;
      c.drawCircle(cx, cy, Screen.dp(20f), Paints.fillingPaint(ColorUtils.alphaColor(.12f * containerFactor, paint.getColor())));
    }
    super.onDraw(c);

    boolean reverseAngle = Lang.rtl() != this.reverseAngle;

    c.save();
    c.translate(getPaddingLeft() + getMeasuredWidth() / 2, getMeasuredHeight() / 2);

    // Material Symbols Rounded geometry (24dp icon, 2dp stroke with round caps), centre lines in dp:
    // menu: three 16dp bars 5dp apart; arrow_back: shaft from the tip (-6.6) to 7, arms ending at (0, ±6.6);
    // close: diagonals of ±5.6dp, i.e. a ±7.92dp plus turned by 45°.
    // The glyph is built in a frame turned by 180°, so the arrow tip lies on local +x (mirrored in RTL).
    final float dir = Lang.rtl() ? -1f : 1f;
    final float menuHalfWidth = Screen.dpf(8f);
    final float menuGap = Screen.dpf(5f);
    final float arrowTip = Screen.dpf(6.6f);
    final float arrowTail = Screen.dpf(7f);
    final float arrowHead = Screen.dpf(6.6f);
    final float closeHalfSize = Screen.dpf(7.92f);

    if (factor <= 1f) {
      final float f = Math.abs(factor);
      c.rotate(factor * (reverseAngle ? -180f : 180f));
      c.drawLine(dir * MathUtils.fromTo(-menuHalfWidth, -arrowTail, f), 0, dir * MathUtils.fromTo(menuHalfWidth, arrowTip, f), 0, paint);

      final float startX = dir * MathUtils.fromTo(-menuHalfWidth, 0, f);
      final float startY = MathUtils.fromTo(menuGap, arrowHead, f);
      final float endX = dir * MathUtils.fromTo(menuHalfWidth, arrowTip, f);
      final float endY = MathUtils.fromTo(menuGap, 0, f);

      c.drawLine(startX, -startY, endX, -endY, paint);
      c.drawLine(startX, startY, endX, endY, paint);

      float badgeCenterX = endX;
      float badgeCenterY = -endY - Screen.dp(1.5f);
      float badgeVisibility = MathUtils.clamp(1f - factor) * menuHintBadgeVisible.getFloatValue();
      if (badgeVisibility > 0f) {
        int cutoutColor = parentHeader.getFilling().getColor();
        int badgeColor = ColorUtils.alphaColor(MathUtils.clamp(badgeVisibility), menuHintBadgeColor.getIntValue());
        c.drawCircle(badgeCenterX, badgeCenterY, Screen.dp(4.5f) * badgeVisibility, Paints.fillingPaint(cutoutColor));
        c.drawCircle(badgeCenterX, badgeCenterY, Screen.dp(3f) * badgeVisibility, Paints.fillingPaint(badgeColor));
      }
    } else {
      final float f = this.factor - 1f;

      c.rotate((reverseAngle ? 180 + (f * -135f) : 180f + (f * 135f)) * (Lang.rtl() ? -1 : 1));

      // Arrow -> plus: the shaft becomes the horizontal bar, the arms fold into the vertical one.
      c.drawLine(dir * MathUtils.fromTo(-arrowTail, -closeHalfSize, f), 0, dir * MathUtils.fromTo(arrowTip, closeHalfSize, f), 0, paint);

      final float startY = MathUtils.fromTo(arrowHead, closeHalfSize, f);
      final float endX = dir * MathUtils.fromTo(arrowTip, 0, f);

      c.drawLine(0, -startY, endX, 0, paint);
      c.drawLine(0, startY, endX, 0, paint);
    }

    c.restore();
  }
}
