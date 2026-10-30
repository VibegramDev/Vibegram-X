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
 * File created on 19/10/2016
 */
package org.thunderdog.challegram.component.attach;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.M3Sheets;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;

public class MediaBottomBar extends FrameLayoutFix implements GestureDetector.OnGestureListener {
  static class BarItem {
    private final int iconResource;
    private final String name;

    // internal
    private float measuredWidth;
    private Drawable icon;
    private float factor;

    public BarItem (@DrawableRes int icon, int name) {
      this.iconResource = icon;
      this.name = Lang.getString(name);
    }
  }


  interface Callback {
    boolean onBottomPrepareSectionChange (int fromIndex, int toIndex, boolean ignorePermissionsRequest);
    void onBottomFactorChanged (float factor);
    void onBottomSectionChanged (int index);

    void onBottomTopRequested (int currentIndex);
  }

  // Data
  private BarItem[] items;
  private int index;
  private Callback callback;

  // Paints
  // private final TextPaint textPaint;

  // Metrics
  private final int barHeight;

  private int lastBarWidth, lastScreenHeight;
  private float maxTextWidth;
  private int itemNormalWidth;
  private int itemActiveDiff;
  private int itemStartX;

  private final GestureDetector flingDetector;

  private static final float TEXT_SIZE = 14f;

  public MediaBottomBar (Context context) {
    super(context);

    flingDetector = new GestureDetector(context, this);

    barHeight = getBarHeight();

    setWillNotDraw(false);
    ViewUtils.setBackground(this, new MediaBottomBarBackground());
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM));
  }

  public void setBottomInset (int inset) {
    Views.setPaddingBottom(this, inset);
    Views.setLayoutHeight(this, barHeight + inset);
  }

  // Getters

  /** M3 navigation bar with labels under the icons: 64dp, as MainNavigationBar's bar. */
  public static int getBarHeight () {
    return Screen.dp(64f);
  }

  public int getCurrentBarWidth () {
    /*if (lastBarWidth != 0 && lastBarWidth == getMeasuredWidth() && false) {
      return lastBarWidth;
    }*/
    int barWidth = M3Sheets.width(Screen.currentWidth());
    int maxItemWidth = Screen.dp(168f);
    int itemWidth = barWidth / items.length;
    return itemWidth > maxItemWidth ? maxItemWidth * items.length : barWidth;
  }

  public int getCurrentIndex () {
    return index;
  }

  // Setters

  public void setItems (BarItem[] items, int currentIndex) {
    float maxTextWidth = 0;
    int index = 0;
    for (BarItem item : items) {
      if (item.name == null || item.name.isEmpty()) {
        throw new IllegalArgumentException("item.itemIconResource == 0 || item.itemName == null || item.itemName.isEmpty()");
      }
      item.measuredWidth = U.measureText(item.name, Paints.getRegularTextPaint(TEXT_SIZE));
      if (item.measuredWidth > maxTextWidth) {
        maxTextWidth = item.measuredWidth;
      }
      item.icon = item.iconResource == 0 ? null : Drawables.get(getResources(), item.iconResource);
      if (index == currentIndex) {
        item.factor = 1f;
      }
      index++;
    }
    this.index = currentIndex;
    this.maxTextWidth = maxTextWidth;
    this.items = items;
    this.lastBarWidth = 0; // Resetting all related metrics
  }

  public void setCallback (Callback callback) {
    this.callback = callback;
  }

  // Metrics

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    updateBarMetrics();
  }

  private void updateBarMetrics () {
    int barWidth = getMeasuredWidth();
    if (barWidth <= 0) {
      return;
    }
    if (lastBarWidth == barWidth && lastScreenHeight == Screen.currentHeight()) {
      return;
    }

    lastBarWidth = barWidth;
    lastScreenHeight = Screen.currentHeight();

    int maxItemWidth = Screen.dp(168f);
    int itemWidth = barWidth / items.length;

    int totalWidth;
    if (itemWidth > maxItemWidth) {
      this.itemStartX = (barWidth - maxItemWidth * items.length) / 2;
      totalWidth = maxItemWidth * items.length;
      itemWidth = maxItemWidth;
    } else {
      this.itemStartX = 0;
      totalWidth = barWidth;
    }

    // M3 navigation bar: all destinations have the same width (the selected one no longer widens).
    this.itemNormalWidth = totalWidth / items.length;
    this.itemActiveDiff = 0;
  }

  // Event handler

  private float touchStartX, touchStartY;
  private int touchIndex = -1, touchCenterX = -1;

  private int findCenterX (int lookupIndex) {
    if (items == null || items.length == 0) {
      return -1;
    }
    int cx = itemStartX;
    int index = 0;
    for (BarItem item : items) {
      int itemWidth = itemNormalWidth + (int) ((float) itemActiveDiff * item.factor);
      if (index == lookupIndex) {
        return cx + itemWidth / 2;
      }
      index++;
      cx += itemWidth;
    }
    return -1;
  }

  private int findTouchIndex (float x) {
    if (items == null || items.length == 0) {
      return -1;
    }
    int cx = itemStartX;
    int index = 0;
    for (BarItem item : items) {
      int itemWidth = itemNormalWidth + (int) ((float) itemActiveDiff * item.factor);
      if (x >= cx && x < cx + itemWidth) {
        return index;
      }
      index++;
      cx +=  itemWidth;
    }
    return -1;
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    flingDetector.onTouchEvent(e);

    if (items == null || items.length == 0 || overlayFactor != 0f) {
      return true;
    }

    float x = e.getX();
    float y = e.getY();

    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        touchIndex = -1;
        touchCenterX = -1;
        touchStartX = x;
        touchStartY = y;

        int cx = itemStartX;
        int index = 0;
        for (BarItem item : items) {
          int itemWidth = itemNormalWidth + (int) ((float) itemActiveDiff * item.factor);
          if (x >= cx && x < cx + itemWidth) {
            touchIndex = index;
            touchCenterX = cx + (int) ((float) itemWidth * .5f);
            break;
          }
          index++;
          cx += itemWidth;
        }

        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        if (touchIndex != -1) {
          float xDiff = Math.abs(touchStartX - x);
          float yDiff = Math.abs(touchStartY - y);
          if (Math.max(xDiff, yDiff) > Screen.getTouchSlopBig()) {
            touchIndex = -1;
          }
        }
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        touchIndex = -1;
        return true;
      }
      case MotionEvent.ACTION_UP: {
        if (touchIndex != -1 && findTouchIndex(x) == touchIndex) {
          performSelection(touchIndex, false);
          return true;
        }
        break;
      }
    }
    return true;
  }

  // Animation

  public boolean setSelectedIndex (int index) {
   return setSelectedIndex(index, false);
  }

  public boolean setSelectedIndex (int index, boolean ignorePermissionsRequest) {
    int centerX = findCenterX(index);
    if (centerX == -1) {
      return false;
    }
    touchCenterX = centerX;
    touchIndex = index;
    return performSelection(index, ignorePermissionsRequest);
  }

  private boolean isAnimating;
  private BarItem fromItem, toItem;
  private float factor;

  private boolean performSelection (final int index, boolean ignorePermissionsRequest) {
    if (isAnimating) {
      return false;
    }
    ViewUtils.onClick(this);
    if (callback != null) {
      if (this.index == index) {
        callback.onBottomTopRequested(index);
      } else if (!callback.onBottomPrepareSectionChange(this.index, index, ignorePermissionsRequest)) {
        return false;
      }
    }
    if (this.index == index) {
      return false;
    }
    this.isAnimating = true;
    this.fromItem = items[this.index];
    this.toItem = items[index];

    ValueAnimator obj;
    obj = AnimatorUtils.simpleValueAnimator();
    obj.addUpdateListener(animation -> setFactor(AnimatorUtils.getFraction(animation)));
    obj.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd (Animator animation) {
        factor = 0f;
        MediaBottomBar.this.index = index;
        if (callback != null) {
          callback.onBottomSectionChanged(index);
        }
        isAnimating = false;
      }
    });
    obj.setDuration(240l);
    obj.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    obj.start();

    return true;
  }

  private void setFactor (float factor) {
    if (this.factor != factor) {
      this.factor = factor;
      this.fromItem.factor = 1f - factor;
      this.toItem.factor = factor;
      invalidate();
      if (callback != null) {
        callback.onBottomFactorChanged(factor);
      }
    }
  }

  private float overlayFactor, overlayRadius;
  private int overlayCenterX, overlayCenterY;

  public void prepareOverlayAnimation () {
    overlayCenterX = itemStartX;
    overlayCenterY = Screen.dp(INDICATOR_TOP + INDICATOR_HEIGHT / 2f);
    overlayRadius = (float) Math.sqrt((getMeasuredWidth() * getMeasuredWidth()) + (barHeight * barHeight)) * .5f;
    int cx = itemStartX;
    int i = 0;
    for (BarItem item : items) {
      int itemWidth = itemNormalWidth + Math.round((float) itemActiveDiff * item.factor);
      if (i == index) {
        overlayCenterX = cx + itemWidth / 2;
        break;
      }
      cx += itemWidth;
      i++;
    }
  }

  public void setOverlayFactor (float factor) {
    if (this.overlayFactor != factor) {
      this.overlayFactor = factor;
      invalidate();
    }
  }

  // Drawing

  // M3 navigation bar item (components/navigation-bar), as in MainNavigationBar: 56x32dp active indicator pill
  // (secondaryContainer) around the icon, label below it. "Don’t remove the labels from navigation items".
  private static final float INDICATOR_TOP = 6f, INDICATOR_WIDTH = 56f, INDICATOR_HEIGHT = 32f, LABEL_GAP = 4f, LABEL_LINE_HEIGHT = 16f;

  private TextPaint labelPaint;

  private TextPaint getLabelPaint () {
    if (labelPaint == null) {
      labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      labelPaint.setTextSize(Screen.sp(12f)); // M3 label medium, as MainNavigationBar
    }
    return labelPaint;
  }

  private class MediaBottomBarBackground extends Drawable {
    @Override
    public void draw (@NonNull Canvas c) {
      updateBarMetrics();
      int barWidth = getMeasuredWidth();
      if (items == null || items.length == 0 || barWidth == 0) {
        return;
      }

      int centerX = barWidth / 2;
      int centerY = barHeight / 2;
      int barHeight = MediaBottomBar.this.barHeight + MediaBottomBar.this.getPaddingBottom();
      final int containerColor = M3.surfaceContainer();

      if (overlayFactor != 1f) {
        c.drawRect(0, 0, barWidth, barHeight, Paints.fillingPaint(containerColor));

        RectF rect = Paints.getRectF();
        float indicatorHeight = Screen.dp(INDICATOR_HEIGHT);
        int iconCenterY = Screen.dp(INDICATOR_TOP + INDICATOR_HEIGHT / 2f);
        TextPaint paint = getLabelPaint();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = Screen.dp(INDICATOR_TOP + INDICATOR_HEIGHT + LABEL_GAP) + Screen.dp(LABEL_LINE_HEIGHT) / 2f - (metrics.ascent + metrics.descent) / 2f;

        int cx = itemStartX;
        for (BarItem item : items) {
          int itemWidth = itemNormalWidth + Math.round((float) itemActiveDiff * item.factor);
          int itemCenterX = cx + itemWidth / 2;
          float selected = item.factor;

          // Active indicator: expands from the icon center along the horizontal axis only.
          if (selected > 0f) {
            float indicatorWidth = Math.min(itemWidth - Screen.dp(8f), Screen.dp(INDICATOR_WIDTH)) * selected;
            rect.set(itemCenterX - indicatorWidth / 2f, iconCenterY - indicatorHeight / 2f, itemCenterX + indicatorWidth / 2f, iconCenterY + indicatorHeight / 2f);
            c.drawRoundRect(rect, indicatorHeight / 2f, indicatorHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(selected, M3.secondaryContainer())));
          }
          if (item.icon != null) {
            int iconColor = ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.onSecondaryContainer(), selected);
            Drawables.draw(c, item.icon, itemCenterX - item.icon.getMinimumWidth() / 2f, iconCenterY - item.icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(iconColor));
          }

          // Label: secondary and bold when active, onSurfaceVariant when inactive.
          paint.setColor(ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.secondary(), selected));
          paint.setTypeface(selected >= .5f ? Fonts.getRobotoBold() : Fonts.getRobotoMedium());
          String text = item.name;
          if (paint.measureText(text) > itemWidth) {
            text = TextUtils.ellipsize(text, paint, itemWidth, TextUtils.TruncateAt.END).toString();
          }
          c.drawText(text, itemCenterX - paint.measureText(text) / 2f, baseline, paint);

          cx += itemWidth;
        }
      }

      if (overlayFactor != 0f) {
        // Overlay under the panel that replaces the bar while media is selected (caption): that panel's color.
        final int fillingColor = Theme.fillingColor();
        if (overlayFactor != 1f) {
          c.drawCircle(overlayCenterX + (centerX - overlayCenterX) * overlayFactor, overlayCenterY + (centerY - overlayCenterY) * overlayFactor, overlayRadius * overlayFactor, Paints.fillingPaint(fillingColor));
          c.drawRect(0, 0, barWidth, barHeight, Paints.fillingPaint(ColorUtils.color((int) (255f * overlayFactor), fillingColor)));
        } else {
          c.drawRect(0, 0, barWidth, barHeight, Paints.fillingPaint(fillingColor));
        }
      }
    }

    @Override
    public void setAlpha (int alpha) {

    }

    @Override
    public void setColorFilter (ColorFilter colorFilter) {

    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity () {
      return PixelFormat.UNKNOWN;
    }
  }

  /** Navigation bar container color (system navigation bar follows it). */
  public int getCurrentColor ()  {
    return M3.surfaceContainer();
  }

  // Gestures


  @Override
  public boolean onDown (MotionEvent e) {
    return true;
  }

  @Override
  public void onShowPress (MotionEvent e) {

  }

  @Override
  public boolean onSingleTapUp (MotionEvent e) {
    return false;
  }

  @Override
  public boolean onScroll (MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
    return false;
  }

  @Override
  public void onLongPress (MotionEvent e) {

  }

  private boolean handleFling (boolean left) {
    if (items == null || items.length == 0 || overlayFactor != 0f) {
      return false;
    }
    int toIndex = left ? Math.max(0, index - 1) : Math.min(index + 1, items.length - 1);
    return toIndex != index && setSelectedIndex(toIndex);
  }

  @Override
  public boolean onFling (MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
    return Math.abs(velocityX) > Screen.dp(250, 1f) && handleFling(velocityX >= 0);
  }
}
