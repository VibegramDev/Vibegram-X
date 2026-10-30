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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Counter;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * Tablet: chat folders as an M3 navigation rail at the leading edge of the chat list pane
 * (m3.material.io/components/navigation-rail): icon in a 56×32dp active indicator, label below, badge on the icon.
 * Selection follows the pager position, so it moves together with page swipes like the tabs do.
 * Scrolls vertically when the folders don't fit.
 */
public class M3FolderRailView extends FrameLayoutFix implements ThemeInvalidateListener {
  public static final float RAIL_WIDTH = 80f;
  private static final float ITEM_HEIGHT = 64f;
  private static final float ITEM_SPACING = 4f;
  private static final float INDICATOR_WIDTH = 56f;
  private static final float INDICATOR_HEIGHT = 32f;
  private static final float INDICATOR_TOP = 6f;
  private static final float ICON_SIZE = 24f;
  private static final float LABEL_SIZE = 12f;
  private static final float LABEL_LINE_HEIGHT = 16f;

  public static class Entry {
    public final CharSequence label;
    public final @DrawableRes int iconRes;
    public final @Nullable Counter counter;

    public Entry (CharSequence label, @DrawableRes int iconRes, @Nullable Counter counter) {
      this.label = label;
      this.iconRes = iconRes;
      this.counter = counter;
    }
  }

  private final ScrollView scrollView;
  private final LinearLayout list;
  private final List<ItemView> items = new ArrayList<>();
  private @Nullable ViewPagerTopView.OnItemClickListener listener;
  private float selectionFactor;
  private int topInset, bottomInset;

  public M3FolderRailView (Context context) {
    super(context);
    setWillNotDraw(false);
    scrollView = new ScrollView(context);
    scrollView.setVerticalScrollBarEnabled(false);
    scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scrollView.setClipToPadding(false);
    scrollView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    list = new LinearLayout(context);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    scrollView.addView(list);
    addView(scrollView);
    setContentDescription(null);
    updatePadding();
  }

  public static int getRailWidth () {
    return Screen.dp(RAIL_WIDTH);
  }

  public void setListener (@Nullable ViewPagerTopView.OnItemClickListener listener) {
    this.listener = listener;
  }

  /** @param topInset status bar (the rail runs along the full height of the pane, next to the app bar) */
  public void setInsets (int topInset, int bottomInset) {
    if (this.topInset != topInset || this.bottomInset != bottomInset) {
      this.topInset = topInset;
      this.bottomInset = bottomInset;
      updatePadding();
    }
  }

  private void updatePadding () {
    scrollView.setPadding(0, topInset + Screen.dp(8f), 0, bottomInset + Screen.dp(8f));
  }

  public void setEntries (List<Entry> entries) {
    // Rebuild only what changed: counters are recreated when a folder filter changes.
    while (items.size() > entries.size()) {
      ItemView view = items.remove(items.size() - 1);
      list.removeView(view);
    }
    for (int i = 0; i < entries.size(); i++) {
      ItemView view;
      if (i < items.size()) {
        view = items.get(i);
      } else {
        view = new ItemView(getContext(), i);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Screen.dp(ITEM_SPACING);
        view.setLayoutParams(params);
        items.add(view);
        list.addView(view);
      }
      view.setEntry(entries.get(i));
    }
    invalidateItems();
  }

  public void setSelectionFactor (float factor) {
    if (this.selectionFactor != factor) {
      this.selectionFactor = factor;
      invalidateItems();
      ensureSelectionVisible();
    }
  }

  private void ensureSelectionVisible () {
    int index = Math.round(selectionFactor);
    if (index < 0 || index >= items.size() || Math.abs(selectionFactor - index) > .01f) {
      return;
    }
    View view = items.get(index);
    int top = view.getTop() - scrollView.getPaddingTop();
    int bottom = view.getBottom() + scrollView.getPaddingBottom() - scrollView.getHeight();
    if (scrollView.getHeight() == 0) {
      return;
    }
    if (top < scrollView.getScrollY()) {
      scrollView.smoothScrollTo(0, Math.max(0, top));
    } else if (bottom > scrollView.getScrollY()) {
      scrollView.smoothScrollTo(0, bottom);
    }
  }

  /** Counters and theme: the items draw their own state. */
  /** Theme change: the items draw M3 roles themselves, invalidating the rail alone does not redraw them. */
  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    invalidateItems();
  }

  public void invalidateItems () {
    invalidate();
    for (ItemView item : items) {
      item.invalidate();
    }
  }

  private float getSelectedFactor (int index) {
    return MathUtils.clamp(1f - Math.abs(selectionFactor - index));
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    // Same container as the tablet navigation bar of the pane.
    c.drawColor(M3.surfaceContainer());
  }

  /** Opaque shape of the active indicator, masks the pressed ripple. */
  private static class IndicatorMask extends Drawable {
    @Override
    public void draw (@NonNull Canvas c) {
      Rect bounds = getBounds();
      RectF rect = Paints.getRectF();
      float cx = bounds.exactCenterX();
      float top = bounds.top + Screen.dp(INDICATOR_TOP);
      float width = Screen.dp(INDICATOR_WIDTH), height = Screen.dp(INDICATOR_HEIGHT);
      rect.set(cx - width / 2f, top, cx + width / 2f, top + height);
      c.drawRoundRect(rect, height / 2f, height / 2f, Paints.fillingPaint(0xff000000));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  private class ItemView extends View {
    private final int index;
    private @Nullable Entry entry;
    private @Nullable Drawable icon;
    private @Nullable String[] labelLines;
    private int labelLinesWidth;

    public ItemView (Context context, int index) {
      super(context);
      this.index = index;
      setOnClickListener(v -> {
        if (listener != null) {
          listener.onPagerItemClick(this.index);
        }
      });
      setOnLongClickListener(v -> listener != null && listener.onPagerItemLongClick(this.index));
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        setForeground(new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE_COLOR), null, new IndicatorMask()));
      }
    }

    void setEntry (Entry entry) {
      if (this.entry == null || this.entry.iconRes != entry.iconRes) {
        icon = entry.iconRes != 0 ? Drawables.get(getResources(), entry.iconRes) : null;
      }
      if (this.entry == null || !TextUtils.equals(this.entry.label, entry.label)) {
        labelLines = null;
        requestLayout();
      }
      this.entry = entry;
      setContentDescription(entry.label);
      invalidate();
    }

    /** M3 rail: «Don’t truncate or display an ellipsis in place of label text» — a long label wraps between words, centered. */
    private String[] getLabelLines (int width) {
      CharSequence label = entry != null ? entry.label : null;
      int maxWidth = Math.max(0, width - Screen.dp(8f));
      if (TextUtils.isEmpty(label) || maxWidth == 0) {
        return new String[0];
      }
      if (labelLines == null || labelLinesWidth != maxWidth) {
        // Bold (selected) is the widest style: the item keeps its height when the selection moves.
        StaticLayout layout = new StaticLayout(label, Paints.getBoldTextPaint(LABEL_SIZE), maxWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        labelLines = new String[layout.getLineCount()];
        for (int i = 0; i < labelLines.length; i++) {
          labelLines[i] = label.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString().trim();
        }
        labelLinesWidth = maxWidth;
      }
      return labelLines;
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      int extraLines = Math.max(0, getLabelLines(width).length - 1);
      setMeasuredDimension(width, Screen.dp(ITEM_HEIGHT) + Screen.dp(LABEL_LINE_HEIGHT) * extraLines);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      if (entry == null) {
        return;
      }
      float selected = getSelectedFactor(index);
      float cx = getWidth() / 2f;
      float indicatorTop = Screen.dp(INDICATOR_TOP);
      float indicatorHeight = Screen.dp(INDICATOR_HEIGHT);
      float cy = indicatorTop + indicatorHeight / 2f;
      if (selected > 0f) {
        // Active indicator grows from the icon center.
        RectF rect = Paints.getRectF();
        float width = Screen.dp(INDICATOR_WIDTH) * (.5f + .5f * selected);
        rect.set(cx - width / 2f, indicatorTop, cx + width / 2f, indicatorTop + indicatorHeight);
        c.drawRoundRect(rect, indicatorHeight / 2f, indicatorHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(selected, M3.secondaryContainer())));
      }
      if (icon != null) {
        int iconColor = ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.onSecondaryContainer(), selected);
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(iconColor));
      }
      // Label: selected = bold secondary, others = medium onSurfaceVariant (as in the navigation bar).
      String[] lines = getLabelLines(getWidth());
      if (lines.length > 0) {
        int color = ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.secondary(), selected);
        TextPaint paint = selected >= .5f ? Paints.getBoldTextPaint(LABEL_SIZE, color) : Paints.getMediumTextPaint(LABEL_SIZE, color, false);
        float baseline = indicatorTop + indicatorHeight + Screen.dp(4f) - paint.ascent();
        for (String line : lines) {
          c.drawText(line, cx - paint.measureText(line) / 2f, baseline, paint);
          baseline += Screen.dp(LABEL_LINE_HEIGHT);
        }
      }
    }

    @Override
    public void draw (@NonNull Canvas c) {
      super.draw(c);
      // Badge over the top-end corner of the icon, above the pressed state layer.
      Counter counter = entry != null ? entry.counter : null;
      if (counter != null) {
        float iconHalf = Screen.dp(ICON_SIZE) / 2f;
        float cx = getWidth() / 2f + (Lang.rtl() ? -1 : 1) * (iconHalf - Screen.dp(4f));
        float cy = Screen.dp(INDICATOR_TOP) + Screen.dp(INDICATOR_HEIGHT) / 2f - iconHalf + Screen.dp(2f);
        counter.draw(c, cx, cy, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT, 1f);
      }
    }
  }
}
