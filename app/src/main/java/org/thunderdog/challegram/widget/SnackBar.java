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
 * File created on 30/06/2019
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;

/**
 * M3 snackbar (SnackbarTokens): inverseSurface container, CornerExtraSmall (4dp), Level3 elevation; supporting text
 * BodyMedium 14sp inverseOnSurface; action LabelLarge 14sp inversePrimary; 48dp for one line. Floating: the owner
 * places it with side margins above navigation ({@link #MARGIN}, {@link #MAX_WIDTH}); it fades in instead of sliding
 * out from under the screen edge.
 */
public class SnackBar extends RelativeLayout {
  public static final int MARGIN = 16, MAX_WIDTH = 600;

  public interface Callback {
    void onSnackBarTransition (SnackBar v, float factor);
    default void onDestroySnackBar (SnackBar v) { }
  }

  private TextView textView;
  private TextView actionView;

  private final BoolAnimator isShowing;

  public SnackBar (Context context) {
    super(context);

    RelativeLayout.LayoutParams rp;

    rp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
    rp.addRule(RelativeLayout.LEFT_OF, R.id.text_title);
    rp.addRule(RelativeLayout.CENTER_VERTICAL);

    textView = new TextView(context);
    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
    textView.setLineSpacing(Screen.dp(20f) - textView.getPaint().getFontMetricsInt(null), 1f);
    textView.setPadding(Screen.dp(16f), Screen.dp(14f), Screen.dp(8f), Screen.dp(14f));
    textView.setLayoutParams(rp);
    addView(textView);

    rp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(40f));
    rp.rightMargin = Screen.dp(8f);
    rp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
    rp.addRule(RelativeLayout.CENTER_VERTICAL);
    actionView = new TextView(context);
    actionView.setId(R.id.text_title);
    actionView.setGravity(Gravity.CENTER);
    actionView.setMinWidth(Screen.dp(48f));
    actionView.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
    actionView.setTypeface(Fonts.getRobotoMedium());
    actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
    actionView.setLayoutParams(rp);
    Views.setClickable(actionView);
    addView(actionView);

    setMinimumHeight(Screen.dp(48f));
    background = new GradientDrawable();
    background.setCornerRadius(Screen.dp(M3.CORNER_EXTRA_SMALL));
    setBackground(background);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    }
    applyColors();

    isShowing = new BoolAnimator(0, new FactorAnimator.Target() {
      @Override
      public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
        updateTranslation();
      }

      @Override
      public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
        updateTranslation();
        if (finalFactor == 0f && !isShowing.getValue() && callback != null) {
          callback.onDestroySnackBar(SnackBar.this);
        }
      }
    }, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    params.leftMargin = params.rightMargin = Screen.dp(MARGIN);
    setLayoutParams(params);
    setOnTouchListener((v, e) -> true);
  }

  private final GradientDrawable background;
  private int appliedContainerColor;
  private boolean hasContainerColor;

  /** M3 roles have no ColorId: re-read them when the view redraws (theme invalidate listener). */
  private void applyColors () {
    int container = M3.inverseSurface();
    if (appliedContainerColor != container || !hasContainerColor) {
      hasContainerColor = true;
      appliedContainerColor = container;
      background.setColor(container);
    }
    int text = M3.inverseOnSurface();
    if (textView.getCurrentTextColor() != text) {
      textView.setTextColor(text);
    }
    int action = M3.inversePrimary();
    if (actionView.getCurrentTextColor() != action) {
      actionView.setTextColor(action);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(Screen.dp(20f));
        actionView.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.alphaColor(M3.STATE_PRESSED, action)), null, mask));
      }
    }
  }

  @Override
  protected void dispatchDraw (Canvas canvas) {
    applyColors();
    super.dispatchDraw(canvas);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int mode = MeasureSpec.getMode(widthMeasureSpec);
    if (mode != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthMeasureSpec) > Screen.dp(MAX_WIDTH)) {
      widthMeasureSpec = MeasureSpec.makeMeasureSpec(Screen.dp(MAX_WIDTH), mode);
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    updateTranslation();
  }

  /** Start/end margins (mirrored in RTL); the end side may be larger to keep clear of the phone dock. */
  public void setHorizontalMargins (int start, int end) {
    if (getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
      ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
      int left = Lang.rtl() ? end : start, right = Lang.rtl() ? start : end;
      // FrameLayout centering adds (leftMargin - rightMargin) to the centered position: asymmetric margins
      // must use left gravity, otherwise the snackbar is shifted off-screen.
      int gravity = Gravity.BOTTOM | (left == right ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
      boolean gravityChanged = false;
      if (params instanceof android.widget.FrameLayout.LayoutParams && ((android.widget.FrameLayout.LayoutParams) params).gravity != gravity) {
        ((android.widget.FrameLayout.LayoutParams) params).gravity = gravity;
        gravityChanged = true;
      }
      if (gravityChanged || params.leftMargin != left || params.rightMargin != right) {
        params.leftMargin = left;
        params.rightMargin = right;
        setLayoutParams(params);
      }
    }
  }

  /** Distance from the bottom of the parent to the snackbar (above navigation, FAB and the system bar). */
  public void setBottomOffset (int offset) {
    if (getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
      Views.setBottomMargin(this, offset);
    }
  }

  private Callback callback;

  public SnackBar setCallback (Callback callback) {
    this.callback = callback;
    return this;
  }

  public SnackBar setText (String text) {
    textView.setText(text);
    return this;
  }

  public SnackBar setAction (String action, Runnable callback, boolean dismissAutomatically) {
    actionView.setText(action);
    actionView.setOnClickListener(v -> {
      callback.run();
      if (dismissAutomatically) {
        dismissSnackBar(true);
      }
    });
    return this;
  }

  public SnackBar showSnackBar (boolean animated) {
    isShowing.setValue(true, animated);
    return this;
  }

  public SnackBar dismissSnackBar (boolean animated) {
    isShowing.setValue(false, animated);
    return this;
  }

  public SnackBar addThemeListeners (@Nullable ViewController<?> themeProvider) {
    if (themeProvider != null) {
      themeProvider.addThemeInvalidateListener(this);
    }
    return this;
  }

  public SnackBar removeThemeListeners (@Nullable ViewController<?> themeProvider) {
    if (themeProvider != null) {
      themeProvider.removeThemeListenerByTarget(textView);
      themeProvider.removeThemeListenerByTarget(actionView);
      themeProvider.removeThemeListenerByTarget(this);
    }
    return this;
  }

  private void updateTranslation () {
    // Floating snackbar: fade in with a short rise (it no longer slides from under the screen edge).
    float factor = isShowing.getFloatValue();
    float y = Screen.dp(16f) * (1f - factor);
    if (getTranslationY() != y || getAlpha() != factor || y == 0) {
      if (callback != null) {
        callback.onSnackBarTransition(this, factor);
      }
      setTranslationY(y);
      setAlpha(factor);
    }
    // Hidden snackbar stays in the layout (measured) but must not swallow touches.
    int visibility = factor > 0f ? VISIBLE : INVISIBLE;
    if (getVisibility() != visibility) {
      setVisibility(visibility);
    }
  }

  public float getVisibilityFactor () {
    return isShowing.getFloatValue();
  }

}
