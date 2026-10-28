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
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * Material 3 Expressive toggle FAB (FAB menu trigger).
 *
 * Closed: baseline FAB, 56dp, CornerLarge (16dp), primaryContainer / onPrimaryContainer, 24dp icon, Level3.
 * Open: CornerFull, primary / onPrimary, 20dp close icon (FabMenuBaselineTokens.CloseButton*).
 */
public class M3FabView extends View implements ForegroundCompat.Host {
  private final ForegroundCompat foregroundCompat = new ForegroundCompat(this);

  @Nullable
  @Override
  public ForegroundCompat foregroundCompat () {
    return foregroundCompat;
  }

  @Override
  public void draw (@NonNull Canvas c) {
    super.draw(c);
    if (foregroundCompat != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      foregroundCompat.draw(c);
    }
  }

  @Override
  protected boolean verifyDrawable (@NonNull Drawable who) {
    return super.verifyDrawable(who) || (foregroundCompat != null && foregroundCompat.verifyDrawable(who));
  }

  @Override
  protected void drawableStateChanged () {
    super.drawableStateChanged();
    if (foregroundCompat != null) {
      foregroundCompat.drawableStateChanged();
    }
  }

  @Override
  public void drawableHotspotChanged (float x, float y) {
    super.drawableHotspotChanged(x, y);
    if (foregroundCompat != null) {
      foregroundCompat.drawableHotspotChanged(x, y);
    }
  }

  @Override
  public void jumpDrawablesToCurrentState () {
    super.jumpDrawablesToCurrentState();
    if (foregroundCompat != null) {
      foregroundCompat.jumpDrawablesToCurrentState();
    }
  }
  public static final float SIZE = 56f;
  private static final float ICON_SIZE = 24f;
  private static final float CLOSE_ICON_SIZE = 20f;

  private @DrawableRes int iconRes;
  private @Nullable Drawable icon;
  private final Drawable closeIcon;
  private float openFactor;
  private float colorFactor;

  private final GradientDrawable rippleMask;
  private final M3StateLayerDrawable ripple;
  private int rippleColor;
  private final RectF rect = new RectF();

  public M3FabView (Context context) {
    super(context);
    closeIcon = Drawables.get(getResources(), R.drawable.baseline_close_24);
    rippleMask = new GradientDrawable();
    rippleMask.setColor(0xFFFFFFFF);
    rippleMask.setCornerRadius(Screen.dp(M3.CORNER_LARGE));
    rippleColor = computeRippleColor();
    ripple = new M3StateLayerDrawable(null, rippleMask, rippleColor);
    ForegroundCompat.set(this, ripple);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), getCornerRadius());
        }
      });
      setClipToOutline(true);
      setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    }
  }

  public void setIcon (@DrawableRes int iconRes) {
    if (this.iconRes != iconRes) {
      this.iconRes = iconRes;
      this.icon = iconRes != 0 ? Drawables.get(getResources(), iconRes) : null;
      invalidate();
    }
  }

  public @DrawableRes int getIcon () {
    return iconRes;
  }

  /** 0: closed FAB, 1: menu open (close button). */
  public void setOpenFactor (float factor) {
    factor = MathUtils.clamp(factor);
    if (this.openFactor != factor) {
      this.openFactor = factor;
      rippleMask.setCornerRadius(getCornerRadius());
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        invalidateOutline();
      }
      invalidate();
    }
  }

  /** Color / icon crossfade of the open state (effects spring, no overshoot). */
  public void setColorFactor (float factor) {
    factor = MathUtils.clamp(factor);
    if (this.colorFactor != factor) {
      this.colorFactor = factor;
      invalidate();
    }
  }

  private float getCornerRadius () {
    float rest = Screen.dp(M3.CORNER_LARGE);
    float size = Math.min(getWidth(), getHeight());
    float full = (size > 0 ? size : Screen.dp(SIZE)) / 2f;
    return rest + (full - rest) * openFactor;
  }

  private int computeRippleColor () {
    return ColorUtils.alphaColor(M3.STATE_PRESSED, ColorUtils.fromToArgb(M3.onPrimaryContainer(), M3.onPrimary(), colorFactor));
  }

  @Override
  protected void onSizeChanged (int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    rippleMask.setCornerRadius(getCornerRadius());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      invalidateOutline();
    }
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    int newRippleColor = computeRippleColor();
    if (newRippleColor != rippleColor) {
      rippleColor = newRippleColor;
      ripple.setColor(newRippleColor);
    }
    float radius = getCornerRadius();
    rect.set(0, 0, getWidth(), getHeight());
    c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(ColorUtils.fromToArgb(M3.primaryContainer(), M3.primary(), colorFactor)));

    int iconColor = ColorUtils.fromToArgb(M3.onPrimaryContainer(), M3.onPrimary(), colorFactor);
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;
    // Rotation follows the spatial factor, opacity the effects factor.
    if (icon != null && colorFactor < 1f) {
      c.save();
      c.rotate(90f * openFactor, cx, cy);
      Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - colorFactor, iconColor)));
      c.restore();
    }
    if (colorFactor > 0f) {
      c.save();
      c.rotate(-90f * (1f - openFactor), cx, cy);
      float scale = CLOSE_ICON_SIZE / ICON_SIZE;
      c.scale(scale, scale, cx, cy);
      Drawables.draw(c, closeIcon, cx - closeIcon.getMinimumWidth() / 2f, cy - closeIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(colorFactor, iconColor)));
      c.restore();
    }
  }
}
