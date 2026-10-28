/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;

import java.lang.ref.WeakReference;

import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.MathUtils;

/**
 * M3 plain tooltip (m3.material.io/components/tooltips; compose PlainTooltipTokens, Tooltip.kt):
 * inverseSurface container, CornerExtraSmall (4dp), supporting text BodySmall (12sp, line 16sp, tracking .4sp)
 * in inverseOnSurface, at least 40×24dp, padding 8dp × 4dp, at most 200dp wide on one line ("Avoid wrapping text").
 * 4dp from the visual boundary of the element.
 * <p>
 * Behavior: shown by tap and hold, disappears 1.5s after the finger leaves the element
 * (BasicTooltipDefaults.TooltipDuration); a new tooltip closes the previous one. Enter/exit as in compose TooltipBox:
 * scale .8 → 1 on the fast spatial spring, alpha on the fast effects spring.
 * <p>
 * Shown in its own non-touchable window, so the finger stays on the element and the content below keeps its touches.
 */
public final class M3Tooltip implements FactorAnimator.Target {
  /** Before the element (left in LTR, right in RTL), vertically centered: the vertical dock at the screen edge. */
  public static final int PLACEMENT_START = 0;
  /** Below the element: "If the element is in an app bar, the plain tooltip appears below the element". */
  public static final int PLACEMENT_BELOW = 1;
  /** Above the element, horizontally centered: the horizontal dock at the bottom of the screen. */
  public static final int PLACEMENT_ABOVE = 2;

  /** Placement chosen when the tooltip is shown (an element that moves between a vertical and a horizontal dock). */
  public interface PlacementProvider {
    int getTooltipPlacement (View anchor);
  }

  private static final float GAP = 4f;
  private static final float MIN_WIDTH = 40f, MAX_WIDTH = 200f, MIN_HEIGHT = 24f;
  private static final float PADDING_HORIZONTAL = 8f, PADDING_VERTICAL = 4f;
  private static final float TEXT_SIZE = 12f, LINE_HEIGHT = 16f;
  private static final float SCREEN_MARGIN = 8f;
  private static final long HIDE_DELAY = 1500L;

  public interface TextProvider {
    @Nullable CharSequence getTooltipText (View anchor);
  }

  /** Visual boundary of the element in its own coordinates (e.g. the dock container instead of the touch target). */
  public interface BoundsProvider {
    void getTooltipBounds (View anchor, Rect outRect);
  }

  private static @Nullable WeakReference<M3Tooltip> current;

  /**
   * Shows the tooltip when the element is held. Takes over the long click and the touch listener of the element
   * (the touch listener never consumes events).
   */
  @SuppressLint("ClickableViewAccessibility")
  public static void bind (@NonNull View anchor, int placement, @Nullable BoundsProvider bounds, @NonNull TextProvider text) {
    bind(anchor, v -> placement, bounds, text);
  }

  @SuppressLint("ClickableViewAccessibility")
  public static void bind (@NonNull View anchor, @NonNull PlacementProvider placement, @Nullable BoundsProvider bounds, @NonNull TextProvider text) {
    anchor.setOnLongClickListener(v -> {
      CharSequence value = text.getTooltipText(v);
      if (TextUtils.isEmpty(value) || v.getWindowToken() == null) {
        return false;
      }
      show(v, placement.getTooltipPlacement(v), bounds, value);
      v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      return true;
    });
    anchor.setOnTouchListener((v, e) -> {
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        M3Tooltip tooltip = current != null ? current.get() : null;
        if (tooltip != null && tooltip.anchor == v) {
          tooltip.hideDelayed();
        }
      }
      return false;
    });
  }

  public static void show (@NonNull View anchor, int placement, @Nullable BoundsProvider bounds, @NonNull CharSequence text) {
    hideCurrent(false);
    M3Tooltip tooltip = new M3Tooltip(anchor, placement, bounds, text);
    current = new WeakReference<>(tooltip);
    tooltip.showImpl();
  }

  public static void hideCurrent (boolean animated) {
    M3Tooltip tooltip = current != null ? current.get() : null;
    current = null;
    if (tooltip != null) {
      tooltip.hide(animated);
    }
  }

  private final View anchor;
  private final int placement;
  private final @Nullable BoundsProvider bounds;
  private final TooltipView view;
  private final PopupWindow window;
  private final FactorAnimator scaleAnimator, alphaAnimator;
  private final int[] anchorPosition = new int[2];
  private final Runnable hideRunnable = () -> hide(true);
  private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::checkAnchorPosition;
  private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
    @Override
    public void onViewAttachedToWindow (@NonNull View v) { }

    @Override
    public void onViewDetachedFromWindow (@NonNull View v) {
      hide(false);
    }
  };
  private boolean isHiding, isDismissed;

  private M3Tooltip (View anchor, int placement, @Nullable BoundsProvider bounds, CharSequence text) {
    this.anchor = anchor;
    this.placement = placement;
    this.bounds = bounds;
    this.view = new TooltipView(anchor.getContext(), text);
    this.window = new PopupWindow(view, view.getTooltipWidth(), view.getTooltipHeight(), false);
    window.setTouchable(false);
    window.setFocusable(false);
    window.setOutsideTouchable(false);
    window.setBackgroundDrawable(new ColorDrawable(0));
    window.setAnimationStyle(0);
    this.scaleAnimator = new FactorAnimator(0, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration, 0f);
    this.alphaAnimator = new FactorAnimator(1, this, M3.FAST_EFFECTS, M3.FAST_EFFECTS.duration, 0f);
  }

  private void showImpl () {
    if (anchor.getWindowToken() == null) {
      return;
    }
    int[] position = computePosition();
    boolean rtl = Lang.rtl();
    // Grows from the side that faces the element.
    view.setPivotX(placement == PLACEMENT_START ? (rtl ? 0 : view.getTooltipWidth()) : view.getTooltipWidth() / 2f);
    view.setPivotY(placement == PLACEMENT_START ? view.getTooltipHeight() / 2f : placement == PLACEMENT_ABOVE ? view.getTooltipHeight() : 0);
    applyFactors(0f, 0f);
    try {
      window.showAtLocation(anchor, Gravity.LEFT | Gravity.TOP, position[0], position[1]);
    } catch (Throwable ignored) {
      return;
    }
    anchor.getLocationInWindow(anchorPosition);
    anchor.addOnAttachStateChangeListener(attachListener);
    anchor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    scaleAnimator.animateTo(1f);
    alphaAnimator.animateTo(1f);
  }

  /** Position of the popup in the window of the element. */
  private int[] computePosition () {
    Rect rect = new Rect(0, 0, anchor.getWidth(), anchor.getHeight());
    if (bounds != null) {
      bounds.getTooltipBounds(anchor, rect);
    }
    int[] location = new int[2];
    anchor.getLocationInWindow(location);
    rect.offset(location[0], location[1]);
    int width = view.getTooltipWidth();
    int height = view.getTooltipHeight();
    int gap = Screen.dp(GAP);
    int x, y;
    if (placement == PLACEMENT_START) {
      x = Lang.rtl() ? rect.right + gap : rect.left - gap - width;
      y = rect.centerY() - height / 2;
    } else {
      x = rect.centerX() - width / 2;
      y = placement == PLACEMENT_ABOVE ? rect.top - gap - height : rect.bottom + gap;
    }
    // Dynamic positioning: stay on screen.
    View root = anchor.getRootView();
    int margin = Screen.dp(SCREEN_MARGIN);
    x = Math.max(margin, Math.min(x, root.getWidth() - width - margin));
    y = Math.max(margin, Math.min(y, root.getHeight() - height - margin));
    return new int[] {x, y};
  }

  /** The element moved (layout change, fold/unfold): the tooltip no longer points at it. */
  private void checkAnchorPosition () {
    int x = anchorPosition[0], y = anchorPosition[1];
    anchor.getLocationInWindow(anchorPosition);
    if (x != anchorPosition[0] || y != anchorPosition[1]) {
      hide(false);
    }
  }

  private void hideDelayed () {
    anchor.removeCallbacks(hideRunnable);
    anchor.postDelayed(hideRunnable, HIDE_DELAY);
  }

  private void hide (boolean animated) {
    anchor.removeCallbacks(hideRunnable);
    if (current != null && current.get() == this) {
      current = null;
    }
    if (isDismissed) {
      return;
    }
    if (animated && window.isShowing()) {
      isHiding = true;
      scaleAnimator.animateTo(0f);
      alphaAnimator.animateTo(0f);
    } else {
      dismiss();
    }
  }

  private void dismiss () {
    if (isDismissed) {
      return;
    }
    isDismissed = true;
    scaleAnimator.cancel();
    alphaAnimator.cancel();
    anchor.removeOnAttachStateChangeListener(attachListener);
    anchor.getViewTreeObserver().removeGlobalOnLayoutListener(layoutListener);
    try {
      window.dismiss();
    } catch (Throwable ignored) { }
  }

  private float scale, alpha;

  private void applyFactors (float scale, float alpha) {
    this.scale = scale;
    this.alpha = alpha;
    float s = .8f + .2f * scale;
    view.setScaleX(s);
    view.setScaleY(s);
    view.setAlpha(MathUtils.clamp(alpha));
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (id == 0) {
      applyFactors(factor, alpha);
    } else {
      applyFactors(scale, factor);
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (isHiding && finalFactor == 0f && scaleAnimator.getFactor() == 0f && alphaAnimator.getFactor() == 0f) {
      dismiss();
    }
  }

  private static final class TooltipView extends View {
    private final TextPaint paint;
    private final CharSequence text;
    private final int width, height;
    private final float textWidth;

    TooltipView (Context context, CharSequence text) {
      super(context);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      paint.setTypeface(Fonts.getRobotoRegular());
      paint.setTextSize(Screen.dp(TEXT_SIZE));
      int padding = Screen.dp(PADDING_HORIZONTAL);
      float maxTextWidth = Screen.dp(MAX_WIDTH) - padding * 2;
      this.text = TextUtils.ellipsize(text, paint, maxTextWidth, TextUtils.TruncateAt.END);
      this.textWidth = paint.measureText(this.text, 0, this.text.length());
      this.width = Math.max(Screen.dp(MIN_WIDTH), (int) Math.ceil(textWidth) + padding * 2);
      this.height = Math.max(Screen.dp(MIN_HEIGHT), Screen.dp(LINE_HEIGHT) + Screen.dp(PADDING_VERTICAL) * 2);
    }

    int getTooltipWidth () {
      return width;
    }

    int getTooltipHeight () {
      return height;
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      RectF rect = new RectF(0, 0, getWidth(), getHeight());
      float radius = Screen.dp(M3.CORNER_EXTRA_SMALL);
      Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
      fill.setColor(M3.inverseSurface());
      c.drawRoundRect(rect, radius, radius, fill);
      paint.setColor(M3.inverseOnSurface());
      Paint.FontMetrics metrics = paint.getFontMetrics();
      float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
      c.drawText(text, 0, text.length(), (getWidth() - textWidth) / 2f, baseline, paint);
    }
  }
}
