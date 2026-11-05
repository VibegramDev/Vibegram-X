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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.MenuMoreWrap;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.MessageOptionsPagerController;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.MathUtils;

/**
 * Reactions of the message menu in windows 600dp and wider: a separate rounded row attached above the M3 menu
 * ({@link MenuMoreWrap} at the touch point). It follows the menu's position and appearance animation.
 */
@SuppressLint("ViewConstructor")
public class MessageReactionsBar extends FrameLayoutFix implements ViewTreeObserver.OnPreDrawListener, RootFrameLayout.MarginModifier, ThemeInvalidateListener {
  /** 40dp reactions with 7dp vertical padding (ReactionsSelectorRecyclerView). */
  public static final int HEIGHT = 54;
  /** Gap between the row and the menu container. */
  public static final int GAP = 8;
  private static final int MAX_WIDTH = 360;
  private static final int EXPAND_WIDTH = 48;

  private final MenuMoreWrap menu;
  private final ViewController<?> boundsProvider;
  private final float touchX;
  private final ReactionsSelectorRecyclerView reactionsView;
  private final @Nullable ImageView expandButton;
  private final Rect insets = new Rect();
  private int expandIconColor;

  public MessageReactionsBar (Context context, ViewController<?> themeProvider, MenuMoreWrap menu, float touchX, MessageOptionsPagerController.State state, @Nullable View.OnClickListener onExpand) {
    super(context);
    this.menu = menu;
    this.boundsProvider = themeProvider;
    this.touchX = touchX;
    setWillNotDraw(false);
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(HEIGHT), Gravity.TOP | Gravity.LEFT));

    int expandWidth = onExpand != null ? Screen.dp(EXPAND_WIDTH) : 0;
    reactionsView = new ReactionsSelectorRecyclerView(context, state);
    reactionsView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT, 0, 0, expandWidth, 0));
    addView(reactionsView);

    if (onExpand != null) {
      // Expands the full reactions list (the picker of the message options sheet).
      expandButton = new ImageView(context);
      expandButton.setScaleType(ImageView.ScaleType.CENTER);
      expandButton.setImageResource(R.drawable.baseline_small_arrow_down_24);
      expandButton.setColorFilter(Paints.getColorFilter(expandIconColor = M3.onSurfaceVariant()));
      expandButton.setLayoutParams(FrameLayoutFix.newParams(expandWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
      expandButton.setOnClickListener(onExpand);
      Views.setClickable(expandButton);
      RippleSupport.setTransparentSelector(expandButton);
      addView(expandButton);
    } else {
      expandButton = null;
    }

    // Same container as the menu: surfaceContainerLow, Level2; fully rounded (my decision for a single-row container).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
        }
      });
      setClipToOutline(true);
      setElevation(Screen.dp(M3.ELEVATION_LEVEL2));
    }
  }

  @Override
  protected void onDraw (Canvas c) {
    RectF rect = Paints.getRectF();
    rect.set(0, 0, getWidth(), getHeight());
    c.drawRoundRect(rect, getHeight() / 2f, getHeight() / 2f, Paints.fillingPaint(M3.surfaceContainerLow()));
  }

  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    if (expandButton != null) {
      int color = M3.onSurfaceVariant();
      if (color != expandIconColor) {
        expandIconColor = color;
        expandButton.setColorFilter(Paints.getColorFilter(color));
      }
    }
    invalidate();
  }

  @Override
  public void onApplyMarginInsets (View child, FrameLayout.LayoutParams params, Rect legacyInsets, Rect insets, Rect insetsWithoutIme) {
    // Positioned by translation next to the menu; insets only bound the placement (as MenuMoreWrap#setAnchor).
    Views.setMargins(params, 0, 0, 0, 0);
    if (!this.insets.equals(insets)) {
      this.insets.set(insets);
      requestLayout();
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int padding = Screen.dp(MenuMoreWrap.PADDING);
    int available = MeasureSpec.getSize(widthMeasureSpec) - insets.left - insets.right - padding * 2;
    View pane = boundsProvider.getValue();
    if (pane != null && pane.getWidth() > 0) {
      available = Math.min(available, pane.getWidth() - padding * 2);
    }
    int expandWidth = expandButton != null ? Screen.dp(EXPAND_WIDTH) : 0;
    int maxWidth = Math.max(expandWidth, Math.min(Screen.dp(MAX_WIDTH), available));
    int height = Screen.dp(HEIGHT);
    reactionsView.measure(MeasureSpec.makeMeasureSpec(maxWidth - expandWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    super.onMeasure(MeasureSpec.makeMeasureSpec(reactionsView.getMeasuredWidth() + expandWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    boundsProvider.addThemeInvalidateListener(this);
    onThemeInvalidate(false);
    positionInvalidated = true;
    getViewTreeObserver().addOnPreDrawListener(this);
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    getViewTreeObserver().removeOnPreDrawListener(this);
    boundsProvider.removeThemeListenerByTarget(this);
  }

  // Inputs of the last placement: it is recomputed only when the menu or the row changes geometry.
  private boolean positionInvalidated = true;
  private float lastMenuX, lastMenuY;
  private int lastMenuWidth, lastWidth, lastHeight, lastLeft, lastTop, lastParentWidth;
  private final Rect lastInsets = new Rect();

  private boolean updatePositionInputs (View parent) {
    float menuX = menu.getLeft() + menu.getTranslationX();
    float menuY = menu.getTop() + menu.getTranslationY();
    if (!positionInvalidated && menuX == lastMenuX && menuY == lastMenuY && menu.getWidth() == lastMenuWidth &&
      getWidth() == lastWidth && getHeight() == lastHeight && getLeft() == lastLeft && getTop() == lastTop &&
      parent.getWidth() == lastParentWidth && insets.equals(lastInsets)) {
      return false;
    }
    positionInvalidated = false;
    lastMenuX = menuX;
    lastMenuY = menuY;
    lastMenuWidth = menu.getWidth();
    lastWidth = getWidth();
    lastHeight = getHeight();
    lastLeft = getLeft();
    lastTop = getTop();
    lastParentWidth = parent.getWidth();
    lastInsets.set(insets);
    return true;
  }

  /** Above the menu container (MenuMoreWrap#setReservedTop keeps the space), aligned to its edge closest to the touch. */
  @Override
  public boolean onPreDraw () {
    if (!(getParent() instanceof View) || menu.getParent() != getParent()) {
      return true;
    }
    View parent = (View) getParent();
    if (updatePositionInputs(parent)) {
      int padding = Screen.dp(MenuMoreWrap.PADDING);
      float menuLeft = menu.getLeft() + menu.getTranslationX() + padding;
      float menuRight = menu.getLeft() + menu.getTranslationX() + menu.getWidth() - padding;
      float menuTop = menu.getTop() + menu.getTranslationY() + padding;
      float touch = touchX - Views.getLocationOnScreen(parent)[0];
      float left = touch >= (menuLeft + menuRight) / 2f ? menuRight - getWidth() : menuLeft;
      // Stays inside the chat pane (in the tablet split the window also holds the chat list).
      int minLeft = insets.left + padding, maxRight = parent.getWidth() - insets.right - padding;
      View pane = boundsProvider.getValue();
      if (pane != null && pane.getWindowToken() != null && pane.getWidth() > 0) {
        int paneLeft = Views.getLocationOnScreen(pane)[0] - Views.getLocationOnScreen(parent)[0];
        minLeft = Math.max(minLeft, paneLeft + padding);
        maxRight = Math.min(maxRight, paneLeft + pane.getWidth() - padding);
      }
      left = MathUtils.clamp(left, minLeft, Math.max(minLeft, maxRight - getWidth()));
      float top = menuTop - Screen.dp(GAP) - getHeight();
      setTranslationX(left - getLeft());
      setTranslationY(top - getTop());
      setPivotX(MathUtils.clamp(touch - left, 0, getWidth()));
      setPivotY(getHeight());
    }
    // Appears and disappears with the menu (the setters ignore unchanged values).
    setAlpha(MathUtils.clamp(menu.getAlpha()));
    setScaleX(menu.getScaleX());
    setScaleY(menu.getScaleY());
    return true;
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    return true; // The container is not a tap outside.
  }
}
