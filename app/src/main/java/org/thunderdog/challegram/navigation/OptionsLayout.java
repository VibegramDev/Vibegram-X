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
 * File created on 26/03/2016 at 23:51
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeListenerList;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.M3SearchContainerDecoration;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.widget.CustomTextView;
import org.thunderdog.challegram.widget.EmojiTextView;
import org.thunderdog.challegram.widget.M3Sheets;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.RootFrameLayout;

import me.vkryl.android.ViewUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.android.animator.Animated;
import me.vkryl.core.StringUtils;

public class OptionsLayout extends LinearLayout implements Animated, RootFrameLayout.MarginModifier {
  private final CustomTextView textView;
  private final CustomTextView headerView;
  private final ViewController<?> parent;
  private final @Nullable ThemeDelegate forcedTheme;

  public OptionsLayout (Context context, ViewController<?> parent, @Nullable ThemeDelegate forcedTheme) {
    super(context);
    this.parent = parent;
    this.forcedTheme = forcedTheme;

    setOrientation(LinearLayout.VERTICAL);

    headerView = new CustomTextView(context, parent.tdlib());
    headerView.setTextSize(18f);
    headerView.setPadding(Screen.dp(16f), Screen.dp(18f), Screen.dp(16f), Screen.dp(6f));
    headerView.setTextColorId(ColorId.text);
    headerView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    headerView.setMaxLineCount(1);
    headerView.setVisibility(View.GONE);
    addView(headerView);

    textView = new CustomTextView(context, parent.tdlib());
    textView.setPadding(Screen.dp(16f), Screen.dp(14f), Screen.dp(16f), Screen.dp(6f));
    textView.setTextColorId(ColorId.textLight);
    textView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    addView(textView);

    ViewUtils.setBackground(this, new Drawable() {
      @Override
      public void draw (@NonNull Canvas c) {
        if (m3Sheet) {
          // M3 sheet container; the shape (28dp top corners) is applied by the outline clip.
          c.drawColor(getContainerColor());
          return;
        }
        View view = getChildAt(0);
        int height = view != null ? view.getMeasuredHeight() : 0;
        if (height > 0)
          c.drawRect(0, height, getMeasuredWidth(), getMeasuredHeight(), Paints.fillingPaint(forcedTheme != null ? forcedTheme.getColor(ColorId.filling) : Theme.getColor(ColorId.filling)));
      }

      @Override
      public void setAlpha (int alpha) { }

      @Override
      public void setColorFilter (@Nullable ColorFilter colorFilter) { }

      @Override
      @SuppressWarnings("deprecation")
      public int getOpacity () {
        return PixelFormat.UNKNOWN;
      }
    });

    if (forcedTheme != null) {
      textView.setForcedTheme(forcedTheme);
      headerView.setForcedTheme(forcedTheme);
    } else {
      // parent.addThemeTextColorListener(textView, ColorId.textLight);
      parent.addThemeInvalidateListener(textView);
      parent.addThemeInvalidateListener(headerView);
      parent.addThemeInvalidateListener(this);
    }
  }

  private boolean m3Sheet;

  /**
   * M3 modal bottom sheet look (used by {@link ViewController#showOptions} and {@link ViewController#showPopup}):
   * surfaceContainerLow container, 28dp top corners, width up to 640dp (centered, 56dp margins in wide windows).
   * Index 0 keeps a top spacer instead of the legacy top shadow, so callers inserting views at index 1 still work.
   */
  public void setM3Sheet (ViewController<?> themeProvider) {
    this.m3Sheet = true;
    m3Spacer = new View(getContext());
    m3Spacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(M3_TOP_SPACE)));
    addView(m3Spacer, 0);
    M3Sheets.setOutline(this, this::getSheetTop);
    setWillNotDraw(false);
    if (forcedTheme == null && themeProvider != null) {
      themeProvider.addThemeInvalidateListener(this);
    }
  }

  public static final float M3_TOP_SPACE = 8f;

  // Scrollable M3 sheet: when the options are taller than 50% of the window, the sheet opens partially expanded
  // (50%), gets a drag handle and is dragged up to expand; once it reaches the top inset the options scroll inside
  // it. Dragging it down from the initial position dismisses it. The view then fills the window: the transparent
  // part above the container (sheetOffset - scrollY) passes touches to the PopupLayout (tap outside = dismiss).

  private View m3Spacer;
  private boolean scrollable;
  private int sheetOffset, maxScroll, topInset;
  private Paint handlePaint;
  private OverScroller scroller;
  private VelocityTracker velocityTracker;
  private int touchSlop, minFlingVelocity, maxFlingVelocity;
  private boolean isDragging;
  private float lastTouchY, downTouchY;

  /** Top of the sheet container in view coordinates (pinned to the status bar inset when expanded). */
  private int getSheetTop () {
    if (!scrollable) {
      return 0;
    }
    return Math.max(topInset, sheetOffset - getScrollY());
  }

  public boolean isM3Scrollable () {
    return scrollable;
  }

  private int getSpacerBaseHeight (boolean scrollable) {
    if (!scrollable) {
      return Screen.dp(M3_TOP_SPACE);
    }
    // 48dp drag handle area; the header (18dp) or info text (14dp) brings its own top padding.
    int textPadding = headerView.getVisibility() != View.GONE ? headerView.getPaddingTop() : textView.getVisibility() != View.GONE ? textView.getPaddingTop() : 0;
    return Screen.dp(M3SearchContainerDecoration.SHEET_HANDLE_AREA) - textPadding;
  }

  @Override
  protected void onScrollChanged (int l, int t, int oldl, int oldt) {
    super.onScrollChanged(l, t, oldl, oldt);
    if (m3Sheet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      invalidateOutline();
    }
  }

  @Override
  protected void onLayout (boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    if (m3Sheet) {
      int scrollY = scrollable ? MathUtils.clamp(getScrollY(), 0, maxScroll) : 0;
      if (getScrollY() != scrollY) {
        if (scroller != null) {
          scroller.forceFinished(true);
        }
        scrollTo(0, scrollY);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        invalidateOutline();
      }
    }
    if (pendingAction != null) {
      pendingAction.run();
      pendingAction = null;
    }
  }

  @Override
  protected void dispatchDraw (Canvas c) {
    super.dispatchDraw(c);
    if (scrollable) {
      // Drag handle: 32x4dp, onSurfaceVariant, 22dp below the container top; scrolls with the content.
      if (handlePaint == null) {
        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      }
      handlePaint.setColor(forcedTheme != null ? forcedTheme.getColor(ColorId.textLight) : M3.onSurfaceVariant());
      float width = Screen.dp(32f), height = Screen.dp(4f);
      float top = sheetOffset + Screen.dp(22f);
      float cx = getWidth() / 2f;
      RectF rect = Paints.getRectF();
      rect.set(cx - width / 2f, top, cx + width / 2f, top + height);
      c.drawRoundRect(rect, height / 2f, height / 2f, handlePaint);
    }
  }

  private void ensureScroller () {
    if (scroller == null) {
      scroller = new OverScroller(getContext());
      ViewConfiguration configuration = ViewConfiguration.get(getContext());
      touchSlop = configuration.getScaledTouchSlop();
      minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
      maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
    }
  }

  @Override
  public boolean dispatchTouchEvent (MotionEvent e) {
    if (scrollable && e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getY() < getSheetTop()) {
      // Transparent area above the sheet: let the PopupLayout handle it (dismiss).
      return false;
    }
    return super.dispatchTouchEvent(e);
  }

  @Override
  public boolean onInterceptTouchEvent (MotionEvent e) {
    if (!scrollable) {
      return super.onInterceptTouchEvent(e);
    }
    ensureScroller();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        downTouchY = lastTouchY = e.getY();
        isDragging = !scroller.isFinished();
        if (isDragging) {
          scroller.forceFinished(true);
        }
        trackVelocity(e, true);
        break;
      }
      case MotionEvent.ACTION_MOVE: {
        trackVelocity(e, false);
        if (!isDragging && Math.abs(e.getY() - downTouchY) > touchSlop) {
          isDragging = true;
          lastTouchY = e.getY();
          if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
          }
        }
        break;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        isDragging = false;
        break;
      }
    }
    return isDragging;
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (!scrollable) {
      super.onTouchEvent(e);
      return true;
    }
    ensureScroller();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        downTouchY = lastTouchY = e.getY();
        trackVelocity(e, true);
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        trackVelocity(e, false);
        if (!isDragging && Math.abs(e.getY() - downTouchY) > touchSlop) {
          isDragging = true;
          lastTouchY = e.getY();
        }
        if (isDragging) {
          int dy = Math.round(lastTouchY - e.getY());
          lastTouchY = e.getY();
          // Below 0 the whole sheet follows the finger down (swipe to dismiss).
          int minScroll = -(getHeight() - sheetOffset);
          scrollTo(0, MathUtils.clamp(getScrollY() + dy, minScroll, maxScroll));
        }
        return true;
      }
      case MotionEvent.ACTION_UP: {
        float velocity = 0;
        if (velocityTracker != null) {
          velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
          velocity = velocityTracker.getYVelocity();
        }
        if (isDragging) {
          onDragEnd(velocity);
        }
        isDragging = false;
        recycleVelocity();
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (isDragging) {
          onDragEnd(0);
        }
        isDragging = false;
        recycleVelocity();
        return true;
      }
    }
    return true;
  }

  private void onDragEnd (float velocityY) {
    int scrollY = getScrollY();
    if (scrollY < 0) {
      int visible = getHeight() - sheetOffset;
      if (-scrollY > visible / 4 || velocityY > minFlingVelocity * 8) {
        ViewParent parent = getParent();
        if (parent instanceof PopupLayout) {
          ((PopupLayout) parent).hideWindow(true);
          return;
        }
      }
      scroller.startScroll(0, scrollY, 0, -scrollY);
    } else if (Math.abs(velocityY) > minFlingVelocity) {
      scroller.fling(0, scrollY, 0, Math.round(-velocityY), 0, 0, 0, maxScroll);
    }
    postInvalidateOnAnimation();
  }

  @Override
  public void computeScroll () {
    if (scroller != null && scroller.computeScrollOffset()) {
      scrollTo(0, scroller.getCurrY());
      postInvalidateOnAnimation();
    }
  }

  private void trackVelocity (MotionEvent e, boolean reset) {
    if (velocityTracker == null) {
      velocityTracker = VelocityTracker.obtain();
    } else if (reset) {
      velocityTracker.clear();
    }
    velocityTracker.addMovement(e);
  }

  private void recycleVelocity () {
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
  }

  public boolean isM3Sheet () {
    return m3Sheet;
  }

  public @ColorInt int getContainerColor () {
    return forcedTheme != null ? forcedTheme.getColor(ColorId.filling) : M3.surfaceContainerLow();
  }

  @Override
  public void onApplyMarginInsets (View child, FrameLayout.LayoutParams params, Rect legacyInsets, Rect insets, Rect insetsWithoutIme) {
    Views.setMargins(params, insets.left, 0, insets.right, 0);
    if (topInset != insets.top) {
      topInset = insets.top;
      requestLayout();
    }
    Views.setPaddingBottom(this, insetsWithoutIme.bottom);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    if (m3Sheet) {
      widthMeasureSpec = M3Sheets.measureSpec(widthMeasureSpec);
      if (measureM3Sheet(widthMeasureSpec, heightMeasureSpec)) {
        return;
      }
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);

    int currentHeight = getMeasuredHeight();
    int actualHeight = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      if (view != null) {
        actualHeight += view.getMeasuredHeight();
      }
    }
    int diff = Math.min(0, currentHeight - actualHeight);
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      if (view != null) {
        view.setTranslationY(diff);
      }
    }
  }

  /** @return true if the sheet is scrollable and has been measured to fill the available height */
  private boolean measureM3Sheet (int widthMeasureSpec, int heightMeasureSpec) {
    int heightMode = MeasureSpec.getMode(heightMeasureSpec);
    int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
    LinearLayout.LayoutParams spacerParams = (LinearLayout.LayoutParams) m3Spacer.getLayoutParams();
    spacerParams.height = getSpacerBaseHeight(false);
    boolean scrollable = false;
    if (heightMode != MeasureSpec.UNSPECIFIED && availableHeight > 0) {
      super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      int limit = availableHeight / 2;
      if (getMeasuredHeight() > limit) {
        scrollable = true;
        int contentHeight = getMeasuredHeight() - spacerParams.height + getSpacerBaseHeight(true);
        sheetOffset = availableHeight - limit;
        spacerParams.height = getSpacerBaseHeight(true) + sheetOffset;
        // Content bottom reaches the view bottom; above the top inset the container is pinned and clips it.
        maxScroll = Math.max(0, sheetOffset + contentHeight - availableHeight);
      }
    }
    if (this.scrollable != scrollable) {
      this.scrollable = scrollable;
      if (!scrollable) {
        sheetOffset = maxScroll = 0;
      }
    }
    if (!scrollable) {
      return false;
    }
    // UNSPECIFIED: an EXACTLY spec would measure WRAP_CONTENT children below the viewport with 0 height.
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    setMeasuredDimension(getMeasuredWidth(), availableHeight);
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      if (view != null) {
        view.setTranslationY(0);
      }
    }
    return true;
  }

  public static @ColorId int getOptionColorId (@ViewController.OptionColor int color) {
    switch (color) {
      case ViewController.OptionColor.NORMAL:
        return ColorId.text;
      case ViewController.OptionColor.LIGHT:
        return ColorId.textLight;
      case ViewController.OptionColor.INACTIVE:
        return ColorId.controlInactive;
      case ViewController.OptionColor.RED:
        return ColorId.textNegative;
      case ViewController.OptionColor.BLUE:
        return ColorId.textNeutral;
      case ViewController.OptionColor.GREEN:
        return ColorId.iconPositive;
    }
    throw new IllegalArgumentException("color == " + color);
  }

  public static TextView genOptionView (Context context, int id, CharSequence string, @ViewController.OptionColor int textColor, int icon, @ViewController.OptionColor int iconColor, OnClickListener onClickListener, @Nullable ThemeListenerList themeProvider, @Nullable ThemeDelegate forcedTheme) {
    EmojiTextView text = new EmojiTextView(context);
    text.setScrollDisabled(true);

    text.setId(id);
    text.setTypeface(Fonts.getRobotoRegular());
    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    final int textColorId = getOptionColorId(textColor);
    if (forcedTheme != null) {
      text.setTextColor(forcedTheme.getColor(textColorId));
    } else {
      text.setTextColor(Theme.getColor(textColorId));
      if (themeProvider != null)
        themeProvider.addThemeColorListener(text, textColorId);
    }
    text.setOnClickListener(onClickListener);
    text.setSingleLine(true);
    text.setEllipsize(TextUtils.TruncateAt.END);
    text.setGravity(Lang.rtl() ? Gravity.RIGHT | Gravity.CENTER_VERTICAL : Gravity.LEFT | Gravity.CENTER_VERTICAL);
    text.setPadding(Screen.dp(17f), Screen.dp(1f), Screen.dp(17f), 0);
    text.setCompoundDrawablePadding(Screen.dp(18f));
    if (icon != 0) {
      Drawable drawable = Drawables.get(context.getResources(), icon);
      if (drawable != null) {
        final int drawableColorId;
        if (iconColor == ViewController.OptionColor.NORMAL) {
          drawableColorId = ColorId.icon;
        } else if (iconColor == ViewController.OptionColor.INACTIVE) {
          drawableColorId = ColorId.controlInactive;
        } else {
          drawableColorId = getOptionColorId(iconColor);
        }
        drawable.setColorFilter(Paints.getColorFilter(forcedTheme != null ? forcedTheme.getColor(drawableColorId) : Theme.getColor(drawableColorId)));
        if (themeProvider != null) {
          themeProvider.addThemeFilterListener(drawable, drawableColorId);
        }
        if (Drawables.needMirror(icon)) {
          // TODO
        }
        if (Lang.rtl()) {
          text.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
        } else {
          text.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
      }
    }
    Views.setClickable(text);
    if (!StringUtils.isEmpty(string)) {
      text.setText(string);
    }

    return text;
  }

  public void setHeader (CharSequence text) {
    if (!StringUtils.isEmpty(text)) {
      headerView.setBoldText(text, null, false);
      headerView.setVisibility(View.VISIBLE);
    } else {
      headerView.setVisibility(View.GONE);
    }
  }

  public static void updateSubtitle (EmojiTextView text, CharSequence name, @DrawableRes int icon, int color, @Nullable ThemeDelegate forcedTheme, @Nullable ViewController<?> themeProvider) {
    updateSubtitle(text, name, icon, color, color, forcedTheme, themeProvider);
  }

  public static void updateSubtitle (EmojiTextView text, CharSequence name, @DrawableRes int icon, int textColor, int iconColor, @Nullable ThemeDelegate forcedTheme, @Nullable ViewController<?> themeProvider) {
    final int textColorId = getOptionColorId(textColor);
    if (forcedTheme != null) {
      text.setTextColor(forcedTheme.getColor(textColorId));
    } else {
      text.setTextColor(Theme.getColor(textColorId));
      if (themeProvider != null) {
        themeProvider.addThemeTextColorListener(text, textColorId);
      }
    }
    final int iconColorId = getOptionColorId(iconColor);
    if (icon != 0) {
      Drawable drawable = Drawables.get(text.getContext().getResources(), icon);
      if (drawable != null) {
        final int drawableColorId = iconColor == ViewController.OptionColor.NORMAL ? ColorId.icon : iconColorId;
        drawable.setColorFilter(Paints.getColorFilter(forcedTheme != null ? forcedTheme.getColor(drawableColorId) : Theme.getColor(drawableColorId)));
        if (themeProvider != null) {
          themeProvider.addThemeFilterListener(drawable, drawableColorId);
        }
        if (Drawables.needMirror(icon)) {
          // TODO
        }
        if (Lang.rtl()) {
          text.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
        } else {
          text.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
      }
    }
    if (!StringUtils.isEmpty(name)) {
      text.setText(name);
    }
  }

  public static EmojiTextView genSubtitle (Context context) {
    EmojiTextView text = new EmojiTextView(context);
    text.setScrollDisabled(true);
    text.setMinHeight(Screen.dp(40f));
    text.setTypeface(Fonts.getRobotoRegular());
    text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    text.setGravity(Lang.rtl() ? Gravity.RIGHT | Gravity.CENTER_VERTICAL : Gravity.LEFT | Gravity.CENTER_VERTICAL);
    text.setPadding(Screen.dp(17f), Screen.dp(6f), Screen.dp(17f), 0);
    text.setCompoundDrawablePadding(Screen.dp(8f));
    text.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return text;
  }

  public void setSubtitle (ViewController.OptionItem item) {
    setSubtitle(item.name, item.icon, item.textColor, item.iconColor);
  }

  public void setSubtitle (CharSequence name, @DrawableRes int icon, int textColor, int iconColor) {
    EmojiTextView text = genSubtitle(getContext());
    updateSubtitle(text, name, icon, textColor, iconColor, forcedTheme, parent);
    addView(text, 1);
  }

  public void setInfo (ViewController<?> context, Tdlib tdlib, CharSequence info, boolean isTitle, int maxLineCount) {
    if (!StringUtils.isEmpty(info)) {
      String str = info.toString();
      TextEntity[] parsed = TD.collectAllEntities(context, tdlib, info, false, null);
      setInfo(str, parsed, isTitle, maxLineCount);
    } else {
      textView.setVisibility(View.GONE);
    }
  }

  public void setInfo (String text, TextEntity[] entities, boolean isTitle, int maxLineCount) {
    if (!StringUtils.isEmpty(text)) {
      textView.setVisibility(View.VISIBLE);
      textView.setMaxLineCount(maxLineCount);
      if (isTitle) {
        textView.setBoldText(text, entities, false);
        textView.setTextSize(19f);
        textView.setTextColorId(ColorId.text);
      } else {
        textView.setText(text, entities, false);
        textView.setTextSize(15f);
        textView.setTextColorId(ColorId.textLight);
      }
    } else {
      textView.setVisibility(View.GONE);
    }
  }

  public int getTextHeight () {
    if (textView.getVisibility() == View.VISIBLE) {
      return textView.getCurrentHeight(UI.getContext(getContext()).getControllerWidth(textView));
    } else {
      return 0;
    }
  }

  private Runnable pendingAction;

  @Override
  public void runOnceViewBecomesReady (View view, Runnable action) {
    pendingAction = action;
  }

}
