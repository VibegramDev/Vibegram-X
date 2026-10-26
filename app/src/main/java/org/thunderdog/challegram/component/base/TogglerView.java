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
 * File created on 07/08/2015 at 17:08
 */
package org.thunderdog.challegram.component.base;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;

/**
 * Material 3 switch (m3.material.io/components/switch), the same in every theme: track 52x32dp,
 * handle 16dp off / 24dp on / 28dp pressed, no shadow. Colors follow the M3 switch tokens
 * (androidx.compose.material3.tokens.SwitchTokens), disabled colors are translucent over the row.
 *
 * Variants of the original Telegram X toggler:
 * - negative state (allowed / denied, e.g. member permissions): "icon on selected and unselected switch",
 *   an X when off and a check when on, 24dp handle in both states;
 * - lock (the user can't change the value): drawn as a disabled switch. The handle icon must only communicate
 *   the selection ("Avoid using more ambiguous or non-binary icons"), so there is no lock glyph in the handle.
 */
public class TogglerView extends View implements FactorAnimator.Target, TooltipOverlayView.LocationProvider {
  private static final int ANIMATOR_DISABLE = 1;
  private static final int ANIMATOR_PRESS = 2;

  private static final float TRACK_WIDTH = 52f, TRACK_HEIGHT = 32f, TRACK_OUTLINE = 2f, TRAILING_SPACE = 16f;
  private static final float HANDLE_UNSELECTED = 16f, HANDLE_SELECTED = 24f, HANDLE_PRESSED = 28f, ICON_SIZE = 16f;
  private static final float DISABLED_TRACK_OPACITY = .12f, DISABLED_CONTENT_OPACITY = .38f;

  private final BoolAnimator isEnabled = new BoolAnimator(0, (id, factor, fraction, callee) -> invalidate(), AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
  private @Nullable BoolAnimator isDisabled;
  /** The handle grows to 28dp while the row is pressed (pressed state is dispatched by the parent row). */
  private @Nullable BoolAnimator isPressedAnimator;

  private boolean useNegativeState, showLock;
  private @Nullable Drawable checkIcon, closeIcon;

  private final RectF trackRect = new RectF();
  private float handleX, handleY, handleRadius;

  public TogglerView (Context context) {
    super(context);
  }

  public void init (boolean isEnabled) {
    setRadioEnabled(isEnabled, false);
  }

  /** Allowed / denied switch: X and check icons in the handle. */
  public TogglerView setUseNegativeState (boolean useNegativeState) {
    if (this.useNegativeState != useNegativeState) {
      this.useNegativeState = useNegativeState;
      if (useNegativeState && checkIcon == null) {
        checkIcon = Drawables.get(getResources(), R.drawable.baseline_check_18);
        closeIcon = Drawables.get(getResources(), R.drawable.baseline_close_18);
      }
      invalidate();
    }
    return this;
  }

  /** The value can't be changed by the user: drawn as a disabled switch. */
  public void setShowLock (boolean showLock) {
    if (this.showLock != showLock) {
      this.showLock = showLock;
      invalidate();
    }
  }

  @Override
  public void setPressed (boolean pressed) {
    super.setPressed(pressed);
    if (isPressedAnimator == null) {
      if (!pressed) {
        return;
      }
      isPressedAnimator = new BoolAnimator(ANIMATOR_PRESS, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration);
    }
    isPressedAnimator.setValue(pressed, getWidth() > 0);
  }

  public void setDisabled (boolean isDisabled, boolean animated) {
    if (this.isDisabled != null || isDisabled) {
      if (this.isDisabled == null) {
        this.isDisabled = new BoolAnimator(ANIMATOR_DISABLE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 168l);
      }
      this.isDisabled.setValue(isDisabled, animated);
    }
  }

  public void setRadioEnabled (boolean isEnabled, boolean animated) {
    this.isEnabled.setValue(isEnabled, animated);
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_DISABLE:
      case ANIMATOR_PRESS:
        invalidate();
        break;
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) { }

  public boolean toggle (boolean animated) {
    return isEnabled.toggleValue(animated);
  }

  public boolean isEnabled () {
    return isEnabled.getValue();
  }

  public void checkRtl (boolean allowLayout) {
    if (Views.setGravity(this, (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL) && allowLayout) {
      Views.updateLayoutParams(this);
      invalidate();
    }
  }

  private float disabledFactor () {
    return showLock ? 1f : isDisabled != null ? isDisabled.getFloatValue() : 0f;
  }

  private float pressedFactor () {
    return isPressedAnimator != null ? isPressedAnimator.getFloatValue() * (1f - disabledFactor()) : 0f;
  }

  /** Track and handle geometry for the current state. */
  private void layoutSwitch () {
    final float checked = isEnabled.getFloatValue();
    final float trackWidth = Screen.dp(TRACK_WIDTH), trackHeight = Screen.dp(TRACK_HEIGHT);
    final float trailing = Math.min(Screen.dp(TRAILING_SPACE), Math.max(0f, getWidth() - trackWidth));
    float cy = getHeight() / 2f;
    ViewGroup.LayoutParams params = getLayoutParams();
    if (params instanceof ViewGroup.MarginLayoutParams) {
      // Center on the row, not on a view shifted by its margins.
      ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
      cy += (margins.bottomMargin - margins.topMargin) / 2f;
    }
    float left = Lang.rtl() ? trailing : getWidth() - trailing - trackWidth;
    trackRect.set(left, cy - trackHeight / 2f, left + trackWidth, cy + trackHeight / 2f);

    final float selected = Screen.dp(HANDLE_SELECTED);
    float diameter = useNegativeState ? selected : Screen.dp(HANDLE_UNSELECTED) + (selected - Screen.dp(HANDLE_UNSELECTED)) * checked;
    diameter += (Screen.dp(HANDLE_PRESSED) - diameter) * pressedFactor();
    handleRadius = diameter / 2f;
    float startX = trackRect.left + trackHeight / 2f, endX = trackRect.right - trackHeight / 2f;
    handleX = Lang.rtl() ? endX - (endX - startX) * checked : startX + (endX - startX) * checked;
    handleY = cy;
  }

  @Override
  public void getTargetBounds (View targetView, Rect outRect) {
    layoutSwitch();
    final int bound = Screen.dp(2f);
    outRect.set((int) (handleX - handleRadius - bound), (int) (handleY - handleRadius - bound), (int) (handleX + handleRadius + bound), (int) (handleY + handleRadius + bound));
  }

  @Override
  protected void onDraw (Canvas c) {
    layoutSwitch();
    final float checked = isEnabled.getFloatValue();
    final float disabled = disabledFactor();
    final float pressed = pressedFactor();
    final float radius = trackRect.height() / 2f;

    // Track: surfaceContainerHighest + outline (off) -> primary (on); disabled: onSurface / surfaceContainerHighest at 12%.
    int track = ColorUtils.fromToArgb(M3.surfaceContainerHighest(), M3.primary(), checked);
    int disabledTrack = ColorUtils.alphaColor(DISABLED_TRACK_OPACITY, ColorUtils.fromToArgb(M3.surfaceContainerHighest(), M3.onSurface(), checked));
    c.drawRoundRect(trackRect, radius, radius, Paints.fillingPaint(ColorUtils.fromToArgb(track, disabledTrack, disabled)));
    if (checked < 1f) {
      int outline = ColorUtils.fromToArgb(M3.outline(), ColorUtils.alphaColor(DISABLED_TRACK_OPACITY, M3.onSurface()), disabled);
      float stroke = Screen.dp(TRACK_OUTLINE);
      trackRect.inset(stroke / 2f, stroke / 2f);
      c.drawRoundRect(trackRect, radius - stroke / 2f, radius - stroke / 2f, Paints.getProgressPaint(ColorUtils.alphaColor(1f - checked, outline), stroke));
      trackRect.inset(-stroke / 2f, -stroke / 2f);
    }

    // Handle: outline -> onPrimary, pressed onSurfaceVariant -> primaryContainer; disabled: onSurface 38% -> surface.
    int handle = ColorUtils.fromToArgb(
      ColorUtils.fromToArgb(M3.outline(), M3.onPrimary(), checked),
      ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.primaryContainer(), checked),
      pressed
    );
    int disabledHandle = ColorUtils.fromToArgb(ColorUtils.alphaColor(DISABLED_CONTENT_OPACITY, M3.onSurface()), M3.surface(), checked);
    c.drawCircle(handleX, handleY, handleRadius, Paints.fillingPaint(ColorUtils.fromToArgb(handle, disabledHandle, disabled)));

    // Icons: surfaceContainerHighest X (off) -> onPrimaryContainer check (on); disabled at 38%.
    if (useNegativeState && checkIcon != null && closeIcon != null) {
      int icon = ColorUtils.fromToArgb(M3.surfaceContainerHighest(), M3.onPrimaryContainer(), checked);
      int disabledIcon = ColorUtils.alphaColor(DISABLED_CONTENT_OPACITY, ColorUtils.fromToArgb(M3.surfaceContainerHighest(), M3.onSurface(), checked));
      int iconColor = ColorUtils.fromToArgb(icon, disabledIcon, disabled);
      drawIcon(c, closeIcon, ColorUtils.alphaColor(1f - checked, iconColor));
      drawIcon(c, checkIcon, ColorUtils.alphaColor(checked, iconColor));
    }
  }

  private void drawIcon (Canvas c, Drawable icon, @ColorInt int color) {
    if (Color.alpha(color) == 0 || icon.getMinimumWidth() <= 0) {
      return;
    }
    float scale = Screen.dp(ICON_SIZE) / (float) icon.getMinimumWidth();
    c.save();
    c.scale(scale, scale, handleX, handleY);
    Drawables.draw(c, icon, handleX - icon.getMinimumWidth() / 2f, handleY - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(color));
    c.restore();
  }
}
