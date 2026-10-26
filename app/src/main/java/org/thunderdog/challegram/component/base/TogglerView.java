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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;

public class TogglerView extends View implements FactorAnimator.Target, TooltipOverlayView.LocationProvider {
  private Paint circlePaint;

  private final BoolAnimator isEnabled = new BoolAnimator(0, (id, factor, fraction, callee) -> {
    updateOrigX();
    invalidate();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      invalidateOutline();
    }
  }, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
  private BoolAnimator isDisabled;

  private boolean useNegativeState;

  public TogglerView (Context context) {
    super(context);
  }

  public void init (boolean isEnabled) {
    setRadioEnabled(isEnabled, false);
    initDrawingItems();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new android.view.ViewOutlineProvider() {
        @Override
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        public void getOutline (View view, android.graphics.Outline outline) {
          if (isM3()) {
            outline.setEmpty(); // M3 switch has no shadow
            return;
          }
          outline.setRoundRect((int) (origX - origRadius), (int) (origY - origRadius), (int) (origX + origRadius), (int) (origY + origRadius), origRadius);
        }
      });
      setElevation(Math.max(1, Screen.dp(.5f)));
      setTranslationZ(Math.max(1, Screen.dp(.5f)));
      ViewUtils.setBackground(this, new Drawable() {
        @Override
        public void draw (@NonNull Canvas c) {
          if (isM3()) {
            return; // M3 switch draws its track in onDraw
          }
          final int fromBackgroundColor;
          final int toBackgroundColor;
          if (useNegativeState) {
            fromBackgroundColor = Theme.getColor(ColorId.togglerNegativeBackground);
            toBackgroundColor = Theme.getColor(ColorId.togglerPositiveBackground);
          } else {
            fromBackgroundColor = Theme.togglerInactiveFillingColor();
            toBackgroundColor = Theme.togglerActiveFillingColor();
          }
          final boolean isDisabled = !useNegativeState && TogglerView.this.isDisabled();
          c.drawRoundRect(fillingRect, fillRadius, fillRadius, Paints.fillingPaint(ColorUtils.fromToArgb(fromBackgroundColor, toBackgroundColor, isDisabled ? 0f : TogglerView.this.isEnabled.getFloatValue())));
        }

        @Override
        public void setAlpha (@IntRange(from = 0, to = 255) int i) {

        }

        @Override
        public void setColorFilter (@Nullable ColorFilter colorFilter) {

        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity () {
          return PixelFormat.UNKNOWN;
        }
      });
    } else {
      setLayerType(LAYER_TYPE_SOFTWARE, circlePaint);
    }
    buildLayout();
  }

  public TogglerView setUseNegativeState (boolean useNegativeState) {
    if (this.useNegativeState != useNegativeState) {
      this.useNegativeState = useNegativeState;
      invalidate();
    }
    return this;
  }

  private void initDrawingItems () {
    circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    circlePaint.setStyle(Paint.Style.FILL);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      float radius = Math.max(1, Screen.dpf(.5f));
      circlePaint.setShadowLayer(radius, 0f, radius, 0x7a000000);
    }
  }

  private boolean isDisabled () {
    return this.isDisabled != null && this.isDisabled.getValue();
  }

  private static final int ANIMATOR_DISABLE = 1;
  private static final int ANIMATOR_PRESS = 2;
  /** M3 switch: the handle grows to 28dp while the row is pressed (pressed state is dispatched by the parent row). */
  private BoolAnimator isPressedAnimator;

  @Override
  public void setPressed (boolean pressed) {
    super.setPressed(pressed);
    if (isPressedAnimator == null) {
      if (!pressed) {
        return;
      }
      isPressedAnimator = new BoolAnimator(ANIMATOR_PRESS, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration);
    }
    isPressedAnimator.setValue(pressed, isM3() && isLaidOut());
  }

  public void setDisabled (boolean isDisabled, boolean animated) {
    if (this.isDisabled != null || isDisabled) {
      if (this.isDisabled == null) {
        this.isDisabled = new BoolAnimator(ANIMATOR_DISABLE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 168l);
      }
      this.isDisabled.setValue(isDisabled, animated);
    }
  }

  private boolean showLock;
  private Drawable lockDrawable;

  public void setShowLock (boolean showLock) {
    if (this.showLock != showLock) {
      this.showLock = showLock;
      if (lockDrawable == null) {
        lockDrawable = Drawables.get(getResources(), R.drawable.baseline_lock_14);
      }
      invalidate();
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

  /*public boolean isAnimating () {
    return isAnimating;
  }*/

  public boolean isEnabled () {
    return isEnabled.getValue();
  }

  private RectF fillingRect;
  private float fillRadius;
  private float minX, maxX, diffX, origX, origY, origRadius;

  private int currentWidth;

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int width = getMeasuredWidth();
    if (currentWidth != width) {
      currentWidth = width;
      buildLayout();
    }
  }

  public void checkRtl (boolean allowLayout) {
    if (Views.setGravity(this, (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL) && allowLayout) {
      Views.updateLayoutParams(this);
      if (currentWidth > 0) {
        buildLayout();
        invalidate();
      }
    }
  }

  private void buildLayout () {
    if (fillingRect == null) {
      fillingRect = new RectF();
    }

    int start = Screen.dp(58f);
    int left = Screen.dp(4f);
    int right = left + Screen.dp(34f);

    fillingRect.left = currentWidth - start + left;
    fillingRect.right = currentWidth - start + right;

    int top = Screen.dp(20f);
    int bottom = top + Screen.dp(14f);

    fillingRect.top = top;
    fillingRect.bottom = bottom;

    origRadius = Screen.dpf(10f);
    fillRadius = Screen.dp(7f);
    float offset = Screen.dp(3f);

    minX = fillingRect.left - offset + origRadius;
    maxX = fillingRect.right + offset - origRadius;
    diffX = maxX - minX;
    origY = fillingRect.top - offset + origRadius;
    updateOrigX();
  }

  private void updateOrigX () {
    float factor = this.isEnabled.getFloatValue();
    if (Lang.rtl()) {
      origX = factor == 0f ? maxX : factor == 1f ? minX : maxX - factor * diffX;
    } else {
      origX = factor == 0f ? minX : factor == 1f ? maxX : minX + factor * diffX;
    }
  }

  @Override
  public void getTargetBounds (View targetView, Rect outRect) {
    final int bound = Screen.dp(2f);
    outRect.set((int) (origX - origRadius - bound), (int) (origY - origRadius - bound), (int) (origX + origRadius + bound), (int) (origY + origRadius + bound));
  }

  // Material 3 switch (every theme): track 52x32dp, handle 16dp off / 24dp on, no shadow
  // (m3.material.io/components/switch). Negative-state and lock variants still use the legacy drawing (not migrated yet).

  private boolean lastM3;
  private final RectF trackRect = new RectF();

  private boolean isM3 () {
    return !useNegativeState && !showLock;
  }

  private void drawM3 (Canvas c) {
    final float checked = this.isEnabled.getFloatValue();
    final float disabled = this.isDisabled != null ? this.isDisabled.getFloatValue() : 0f;
    final float alpha = 1f - .62f * disabled;
    final float trackWidth = Screen.dp(52f), trackHeight = Screen.dp(32f);
    float trailing = Math.min(Screen.dp(16f), Math.max(0f, getWidth() - trackWidth));
    float cy = getHeight() / 2f;
    ViewGroup.LayoutParams params = getLayoutParams();
    if (params instanceof ViewGroup.MarginLayoutParams) {
      // Center on the row, not on a view shifted by its margins.
      cy += (((ViewGroup.MarginLayoutParams) params).bottomMargin - ((ViewGroup.MarginLayoutParams) params).topMargin) / 2f;
    }
    float left = Lang.rtl() ? trailing : getWidth() - trailing - trackWidth;
    trackRect.set(left, cy - trackHeight / 2f, left + trackWidth, cy + trackHeight / 2f);
    float radius = trackHeight / 2f;

    int trackColor = ColorUtils.fromToArgb(M3.surfaceContainerHighest(), M3.primary(), checked);
    c.drawRoundRect(trackRect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, trackColor)));
    if (checked < 1f) {
      float stroke = Screen.dp(2f);
      trackRect.inset(stroke / 2f, stroke / 2f);
      c.drawRoundRect(trackRect, radius - stroke / 2f, radius - stroke / 2f, Paints.getProgressPaint(ColorUtils.alphaColor(alpha * (1f - checked), M3.outline()), stroke));
      trackRect.inset(-stroke / 2f, -stroke / 2f);
    }

    float handleRadius = Screen.dp(8f) + Screen.dp(4f) * checked;
    float pressed = isPressedAnimator != null ? isPressedAnimator.getFloatValue() : 0f;
    handleRadius += (Screen.dp(14f) - handleRadius) * pressed; // pressed handle: 28dp
    float startX = trackRect.left + radius, endX = trackRect.right - radius;
    float handleX = Lang.rtl() ? endX - (endX - startX) * checked : startX + (endX - startX) * checked;
    int handleColor = ColorUtils.fromToArgb(M3.outline(), M3.onPrimary(), checked);
    c.drawCircle(handleX, cy, handleRadius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, handleColor)));

    // Tooltip anchor follows the handle.
    origX = handleX;
    origY = cy;
    origRadius = handleRadius;
  }

  @Override
  public void onDraw (Canvas c) {
    boolean m3 = isM3();
    if (m3 != lastM3) {
      lastM3 = m3;
      invalidateOutline();
      // Lock / negative state toggled: the outline (shadow) depends on the style.
      post(this::requestLayout);
    }
    if (m3) {
      drawM3(c);
      return;
    }
    final float factor = this.isEnabled.getFloatValue();
    updateOrigX();
    final int fromBackgroundColor, fromColor;
    final int toBackgroundColor, toColor;
    int checkColor;
    if (useNegativeState) {
      fromBackgroundColor = Theme.getColor(ColorId.togglerNegativeBackground); // ThemeRed.getColor(ColorId.togglerFillingActive);
      fromColor = Theme.getColor(ColorId.togglerNegative); // ThemeRed.getColor(ColorId.togglerActive);
      toBackgroundColor = Theme.getColor(ColorId.togglerPositiveBackground); // ThemeBase.getColor(ColorId.togglerFillingActive);
      toColor = Theme.getColor(ColorId.togglerPositive); // ThemeBase.getColor(ColorId.togglerActive);
      checkColor = ColorUtils.fromToArgb(Theme.getColor(ColorId.togglerNegativeContent), Theme.getColor(ColorId.togglerPositiveContent), isEnabled.getFloatValue());
    } else {
      fromBackgroundColor = Theme.togglerInactiveFillingColor();
      fromColor = Theme.togglerInactiveColor();
      toBackgroundColor = Theme.togglerActiveFillingColor();
      toColor = Theme.togglerActiveColor();
      checkColor = 0;
    }

    final float disableFactor = !useNegativeState && this.isDisabled != null ? this.isDisabled.getFloatValue() : 0f;

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      c.drawRoundRect(fillingRect, fillRadius, fillRadius, Paints.fillingPaint(ColorUtils.fromToArgb(fromBackgroundColor, toBackgroundColor, factor * (1f - disableFactor))));
    }
    circlePaint.setColor(ColorUtils.fromToArgb(fromColor, toColor, factor * (1f - disableFactor)));
    c.drawCircle(origX, origY, origRadius, circlePaint);

    if (showLock) {
      Drawables.draw(c, lockDrawable, origX - lockDrawable.getMinimumWidth() / 2f, origY - lockDrawable.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(checkColor));
    } else if (useNegativeState) {
      Paint strokePaint = Paints.getProgressPaint(checkColor, Screen.dp(2f));

      int lineWidth = (int) (origRadius * .75f);
      int linePart = lineWidth / 2;

      /*c.save();
      c.rotate(-90f * (1f - factor), origX, origY);*/

      int offsetGlobal = (int) (Screen.dp(.5f) * factor);
      int offsetGlobalY = (int) (Screen.dp(.5f) * factor);
      int offsetX1 = (int) (Screen.dp(1.5f) * factor);
      c.drawLine(origX - linePart + offsetX1, origY + linePart + offsetGlobalY, origX + linePart + offsetX1, origY - linePart + offsetGlobalY, strokePaint);
      int offsetX = (int) (Screen.dp(-3.5f) * factor);
      int offsetY = (int) (Screen.dp(3f) * factor);
      int removeLeft = (int) (Screen.dp(.5f) * factor);
      c.drawLine(origX - linePart + offsetX + offsetGlobal + removeLeft, origY - linePart + offsetY + offsetGlobalY + removeLeft, origX + linePart * (1f - factor) + offsetX + offsetGlobal, origY + linePart * (1f - factor) + offsetY + offsetGlobalY, strokePaint);

      // c.restore();
    }
  }
}
