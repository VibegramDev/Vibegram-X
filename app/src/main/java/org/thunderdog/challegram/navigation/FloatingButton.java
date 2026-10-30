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
 * File created on 17/08/2015 at 02:49
 */
package org.thunderdog.challegram.navigation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.RootFrameLayout;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * M3 FAB (FabBaselineTokens): 56dp, CornerLarge (16dp), primaryContainer / onPrimaryContainer, Level3,
 * bottom trailing corner with 16dp margins. "FABs remain in place on scroll".
 */
public class FloatingButton extends View implements Destroyable, RootFrameLayout.InsetsChangeListener {
  private static final float SIZE = 56f;
  private static final float MARGIN = 16f;

  private final float center;

  private boolean isShowing;

  private static final float MIN_SCALE = .4f;

  public FloatingButton (Context context) {
    super(context);

    int size = Screen.dp(SIZE);
    center = (float) size * .5f;

    setM3FabBackground(this);

    FrameLayoutFix.LayoutParams params;

    params = FrameLayoutFix.newParams(size, size, Gravity.BOTTOM | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT));
    params.rightMargin = params.leftMargin = params.bottomMargin = Screen.dp(MARGIN);

    setLayoutParams(params);

    isShowing = true;

    setAlpha(0f);
    setScaleX(MIN_SCALE);
    setScaleY(MIN_SCALE);
  }

  /**
   * M3 FAB container for a 56dp view: primaryContainer, CornerLarge, onPrimaryContainer pressed state layer,
   * Level3 shadow (API 21+). Colors are read on every draw, so they follow theme changes.
   */
  public static void setM3FabBackground (View view) {
    GradientDrawable mask = new GradientDrawable();
    mask.setCornerRadius(Screen.dp(M3.CORNER_LARGE));
    mask.setColor(0xFFFFFFFF);
    Drawable container = new Drawable() {
      @Override
      public void draw (@NonNull Canvas c) {
        RectF rect = Paints.getRectF();
        rect.set(getBounds());
        float radius = Screen.dp(M3.CORNER_LARGE);
        c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(M3.primaryContainer()));
      }

      @Override
      public void setAlpha (int alpha) { }

      @Override
      public void setColorFilter (@Nullable ColorFilter colorFilter) { }

      @Override
      public int getOpacity () {
        return PixelFormat.TRANSLUCENT;
      }
    };
    view.setBackgroundDrawable(new M3StateLayerDrawable(container, mask, ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onPrimaryContainer())) {
      @Override
      public void draw (@NonNull Canvas c) {
        setColor(ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onPrimaryContainer()));
        super.draw(c);
      }
    });
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Screen.dp(M3.CORNER_LARGE));
        }
      });
      view.setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    }
  }

  private RootFrameLayout rootView;

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    rootView = Views.findAncestor(this, RootFrameLayout.class, true);
    if (rootView != null) {
      rootView.addInsetsChangeListener(this);
      applyBottomInset(rootView.getSystemInsets().bottom);
    }
  }

  @Override
  public void onInsetsChanged (RootFrameLayout viewGroup, Rect effectiveInsets, Rect effectiveInsetsWithoutIme, Rect systemInsets, Rect systemInsetsWithoutIme, boolean isUpdate) {
    applyBottomInset(systemInsets.bottom);
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    performDestroy();
  }

  private int lastBottomInset, extraBottomOffset;

  /** Space taken by a bottom navigation shown over the screen (phone sections): the button stays above it. */
  public void setExtraBottomOffset (int offset) {
    if (this.extraBottomOffset != offset) {
      this.extraBottomOffset = offset;
      applyBottomInset(lastBottomInset);
    }
  }

  private void applyBottomInset (int bottomInset) {
    lastBottomInset = bottomInset;
    Views.setBottomMargin(this, Screen.dp(MARGIN) + bottomInset + extraBottomOffset);
  }

  @Override
  public void performDestroy () {
    if (rootView != null) {
      rootView.removeInsetsChangeListener(this);
      rootView = null;
    }
  }

  private Drawable leftIcon, rightIcon;
  private int leftIconRes, rightIconRes;
  private float leftIconTop, rightIconTop;
  private Drawable icon;
  private int iconRes;
  private float iconTop;

  public void setIcons (int leftIcon, int rightIcon) {
    if (leftIcon != 0 && rightIcon != 0 && leftIcon != rightIcon) {
      this.icon = null;
      this.iconTop = 0;
      this.iconRes = 0;

      this.leftIcon = Drawables.get(getResources(), leftIcon);
      this.leftIconRes = leftIcon;
      this.leftIconTop = getIconTop(leftIcon, this.leftIcon);

      this.rightIcon = Drawables.get(getResources(), rightIcon);
      this.rightIconRes = rightIcon;
      this.rightIconTop = getIconTop(rightIcon, this.rightIcon);

      setFactor(0f);

      return;
    }

    this.leftIcon = null;
    this.leftIconRes = 0;
    this.rightIcon = null;
    this.rightIconRes = 0;

    if (leftIcon != 0) {
      this.icon = Drawables.get(getResources(), leftIcon);
      this.iconRes = leftIcon;
      this.iconTop = getIconTop(leftIcon, icon);
    } else {
      this.icon = Drawables.get(getResources(), rightIcon);
      this.iconRes = rightIcon;
      this.iconTop = getIconTop(rightIcon, icon);
    }

    invalidate();
  }

  private float factor;

  public void setFactor (float factor) { // Switch factor. 0f - showing left icon, 1f - right
    if (this.factor != factor && leftIcon != null && rightIcon != null) {
      this.factor = factor;
      invalidate();
    }
  }

  private float scaleFactor = -1f;

  private Animator.AnimatorListener hideListener;

  private boolean touchEnabled;

  @Override
  public boolean onTouchEvent (MotionEvent event) {
    return touchEnabled && super.onTouchEvent(event);
  }

  public void setTouchEnabled (boolean enabled) {
    if (touchEnabled != enabled) {
      touchEnabled = enabled;
      setEnabled(enabled);
    }
  }

  private static final long OVERSHOOT_DURATION = 380l;

  public void initHeightFactor (float heightFactor, float heightFactor2, float scaleFactor, boolean animate, boolean translation) {
    setHeightFactor(heightFactor, scaleFactor, animate);
  }

  /**
   * M3: "FABs remain in place on scroll" — the header height neither moves nor hides the button.
   * Only screen transitions ({@code animate == false}) fade it in and out by {@code scaleFactor}.
   */
  public void setHeightFactor (float heightFactor, float scaleFactor, boolean animate) {
    if (!animate && this.scaleFactor != scaleFactor) {
      this.scaleFactor = scaleFactor;
      float realAlpha = MathUtils.clamp(scaleFactor);
      setAlpha(realAlpha);
      float scale = MIN_SCALE + (1f - MIN_SCALE) * scaleFactor;
      setScaleX(scale);
      setScaleY(scale);
      setTouchEnabled(isShowing = realAlpha != 0f);
    }
  }

  private float getIconTop (int resource, Drawable icon) {
    if (resource == 0 || icon == null) {
      return 0;
    }
    if (resource == R.drawable.baseline_chat_bubble_24) {
      return Screen.dp(17f);
      /*case R.drawable.ic_camera_gray: {
        return Screen.dp(19f) + Screen.dp(4f);
      }*/
    }
    return center - icon.getMinimumHeight() / 2f;
  }

  private static void draw (Canvas c, int res, Drawable d, float cx, float top, Paint paint, float alpha) {
    if (d != null) {
      boolean needReverse = Drawables.needMirror(res);
      if (needReverse) {
        c.save();
        c.scale(-1f, 1f, cx, top + d.getMinimumHeight() / 2);
      }
      int savedAlpha = paint.getAlpha();
      paint.setAlpha((int) (255f * alpha));
      Drawables.draw(c, d, cx - d.getMinimumWidth() / 2, top, paint);
      paint.setAlpha(savedAlpha);
      if (needReverse) {
        c.restore();
      }
    }
  }

  @Override
  protected void onDraw (Canvas c) {
    if (icon != null) {
      draw(c, iconRes, icon, center, iconTop, Paints.getPorterDuffPaint(M3.onPrimaryContainer()), 1f);
    } else if (leftIcon != null || rightIcon != null) {
      Paint paint = Paints.getPorterDuffPaint(M3.onPrimaryContainer());
      if (leftIcon != null)
        draw(c, leftIconRes, leftIcon, center, leftIconTop, paint, 1f - MathUtils.clamp(factor));
      if (rightIcon != null)
        draw(c, rightIconRes, rightIcon, center, rightIconTop, paint, MathUtils.clamp(factor));
    }
  }

  private Animator.AnimatorListener getHideListener () {
    if (hideListener == null) {
      hideListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd (Animator animation) {
          if (!isShowing) {
            setTouchEnabled(false);
          }
        }
      };
    }
    return hideListener;
  }

  // show/hide for EditHeaderView

  private float showFactor;

  public float getShowFactor () {
    return showFactor;
  }

  public void setShowFactor (float factor) {
    if (this.showFactor != factor) {
      this.showFactor = factor;
      setAlpha(MathUtils.clamp(factor));
      final float scale = MIN_SCALE + (1f - MIN_SCALE) * factor;
      setScaleX(scale);
      setScaleY(scale);
    }
  }

  public void show (ViewController<?> c) {
    int icon = c.getFloatingButtonId();

    isShowing = true;

    setTouchEnabled(true);
    setIcons(0, icon);

    showFactor = 0f;

    ValueAnimator obj;
    obj = AnimatorUtils.simpleValueAnimator();
    obj.addUpdateListener(animation -> setShowFactor(AnimatorUtils.getFraction(animation)));
    obj.setInterpolator(AnimatorUtils.ANTICIPATE_OVERSHOOT_INTERPOLATOR);
    obj.setDuration(OVERSHOOT_DURATION);

    obj.start();
  }

  public void hide () {
    if (isShowing) {
      isShowing = false;

      showFactor = 1f;

      ValueAnimator obj;

      obj = AnimatorUtils.simpleValueAnimator();
      obj.addUpdateListener(animation -> setShowFactor(1f - AnimatorUtils.getFraction(animation)));
      obj.setInterpolator(AnimatorUtils.ANTICIPATE_OVERSHOOT_INTERPOLATOR);
      obj.setDuration(OVERSHOOT_DURATION);
      obj.addListener(getHideListener());
      obj.start();
    }
  }
}
