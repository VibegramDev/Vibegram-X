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
package org.thunderdog.challegram.ui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import java.util.List;

import me.vkryl.core.ColorUtils;

/**
 * Material 3 Expressive segmented list for settings screens (opt-in per screen, every theme:
 * the theme changes only the colors, never the layout).
 *
 * Groups are the existing Telegram X sections (rows between shadows). Each row becomes a segment:
 * outer corners 16dp, inner corners 4dp, 2dp gaps ("4dp inner, 16dp outer", "Use gaps for contained lists",
 * m3.material.io/components/lists). Shadows and separators are replaced by the page color.
 *
 * Group boundaries are computed from the adapter model, not from visible views. Row backgrounds are wrapped
 * (the original drawable is kept inside the wrapper) and restored when a row is detached (recycling), so a
 * holder reused for a row type outside the group gets its own background back.
 */
public final class M3SettingsGroupDecoration extends RecyclerView.ItemDecoration implements RecyclerView.OnChildAttachStateChangeListener {
  private static final int KIND_OTHER = 0, KIND_CARD = 1, KIND_SEPARATOR = 2, KIND_HIDDEN = 3, KIND_TEXT = 4;
  private static final float GROUP_INSET = 16f;
  private static final float SEGMENT_GAP = 2f; // ListTokens.SegmentedGap
  private static final float SEPARATOR_HEIGHT = 1f; // SettingHolder TYPE_SEPARATOR height

  private final SettingsAdapter adapter;

  private M3SettingsGroupDecoration (SettingsAdapter adapter) {
    this.adapter = adapter;
  }

  /** Call before {@code setAdapter}. Every row type of the screen must be classified in {@link #kindAt}. */
  public static M3SettingsGroupDecoration attach (RecyclerView recyclerView, SettingsAdapter adapter) {
    M3SettingsGroupDecoration decoration = new M3SettingsGroupDecoration(adapter);
    recyclerView.addItemDecoration(decoration);
    recyclerView.addOnChildAttachStateChangeListener(decoration);
    return decoration;
  }

  /** Segment container: surfaceContainerLowest on the light page (surfaceContainer), surfaceContainerHigh on the dark page (surface). */
  private static int cardColor () {
    return Theme.isDark() ? M3.surfaceContainerHigh() : M3.surfaceContainerLowest();
  }

  private int kindAt (int position) {
    List<ListItem> items = adapter.getItems();
    if (position < 0 || position >= items.size()) {
      return KIND_OTHER;
    }
    switch (items.get(position).getViewType()) {
      // SettingView rows (SettingHolder)
      case ListItem.TYPE_SETTING:
      case ListItem.TYPE_VALUED_SETTING:
      case ListItem.TYPE_VALUED_SETTING_WITH_RADIO:
      case ListItem.TYPE_VALUED_SETTING_COMPACT:
      case ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_COLOR:
      case ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_RADIO:
      case ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_RADIO_2:
      case ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_TOGGLER:
      case ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_CHECKBOX:
      case ListItem.TYPE_RADIO_SETTING:
      case ListItem.TYPE_RADIO_SETTING_WITH_NEGATIVE_STATE:
      case ListItem.TYPE_INFO_SETTING:
      case ListItem.TYPE_INFO_MULTILINE:
      case ListItem.TYPE_CHECKBOX_OPTION:
      case ListItem.TYPE_CHECKBOX_OPTION_REVERSE:
      case ListItem.TYPE_CHECKBOX_OPTION_MULTILINE:
      case ListItem.TYPE_CHECKBOX_OPTION_WITH_AVATAR:
      case ListItem.TYPE_RADIO_OPTION:
      case ListItem.TYPE_RADIO_OPTION_LEFT:
      case ListItem.TYPE_RADIO_OPTION_WITH_AVATAR:
      // CustomTextView on the filling color (wrap content): a non-clickable segment
      case ListItem.TYPE_INFO:
        return KIND_CARD;
      case ListItem.TYPE_SEPARATOR:
      case ListItem.TYPE_SEPARATOR_FULL:
        return KIND_SEPARATOR;
      case ListItem.TYPE_SHADOW_TOP:
      case ListItem.TYPE_SHADOW_BOTTOM:
        return KIND_HIDDEN;
      case ListItem.TYPE_HEADER:
      case ListItem.TYPE_HEADER_PADDED:
      case ListItem.TYPE_HEADER_MULTILINE:
      case ListItem.TYPE_DESCRIPTION:
      case ListItem.TYPE_DESCRIPTION_SMALL:
        return KIND_TEXT;
      // 4dp spacer painted with the page color, full-screen placeholder text (loading error): not part of a group.
      case ListItem.TYPE_EMPTY_OFFSET_SMALL:
      case ListItem.TYPE_EMPTY:
        return KIND_OTHER;
    }
    return KIND_OTHER;
  }

  /** Whether the row at position continues the group in the given direction (another row, maybe after a separator). */
  private boolean isConnected (int position, int direction) {
    int next = position + direction;
    int kind = kindAt(next);
    return kind == KIND_CARD || (kind == KIND_SEPARATOR && kindAt(next + direction) == KIND_CARD);
  }

  @Override
  public void getItemOffsets (@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    outRect.setEmpty();
    int position = parent.getChildAdapterPosition(view);
    if (position == RecyclerView.NO_POSITION) {
      return;
    }
    int inset = Screen.dp(GROUP_INSET);
    switch (kindAt(position)) {
      case KIND_CARD: {
        outRect.left = outRect.right = inset;
        int previous = kindAt(position - 1);
        if (previous == KIND_CARD) {
          outRect.top = Screen.dp(SEGMENT_GAP);
        } else if (previous == KIND_SEPARATOR && kindAt(position - 2) == KIND_CARD) {
          // The separator row itself (painted with the page color) is part of the gap.
          outRect.top = Math.max(0, Screen.dp(SEGMENT_GAP) - Screen.dp(SEPARATOR_HEIGHT));
        }
        break;
      }
      case KIND_SEPARATOR:
      case KIND_TEXT:
        outRect.left = outRect.right = inset;
        break;
    }
  }

  @Override
  public void onDraw (@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      View view = parent.getChildAt(i);
      int position = parent.getChildAdapterPosition(view);
      if (position == RecyclerView.NO_POSITION) {
        // Removal animation: keep the current look until the view is detached.
        continue;
      }
      if (kindAt(position) == KIND_CARD) {
        applySegment(view, isConnected(position, -1), isConnected(position, 1));
      } else {
        restore(view);
      }
    }
  }

  @Override
  public void onDrawOver (@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    // Separators and shadows become gaps of the page color.
    int pageColor = Theme.backgroundColor();
    for (int i = 0; i < parent.getChildCount(); i++) {
      View view = parent.getChildAt(i);
      int position = parent.getChildAdapterPosition(view);
      if (position == RecyclerView.NO_POSITION) {
        continue;
      }
      int kind = kindAt(position);
      if (kind == KIND_SEPARATOR || kind == KIND_HIDDEN) {
        float dx = view.getTranslationX(), dy = view.getTranslationY();
        c.drawRect(view.getLeft() + dx, view.getTop() + dy, view.getRight() + dx, view.getBottom() + dy, Paints.fillingPaint(pageColor));
      }
    }
  }

  @Override
  public void onChildViewAttachedToWindow (@NonNull View view) { }

  @Override
  public void onChildViewDetachedFromWindow (@NonNull View view) {
    // A recycled holder may be reused for another row type or after a theme switch: give it back its own background.
    restore(view);
  }

  private static void applySegment (View view, boolean connectedTop, boolean connectedBottom) {
    Drawable background = view.getBackground();
    SegmentBackground segment;
    if (background instanceof SegmentBackground) {
      segment = (SegmentBackground) background;
    } else {
      // Also re-wraps a background replaced by a theme listener (e.g. setBackgroundColor).
      segment = new SegmentBackground(background);
      view.setBackground(segment);
    }
    segment.setShape(connectedTop, connectedBottom);
  }

  private static void restore (View view) {
    Drawable background = view.getBackground();
    if (background instanceof SegmentBackground) {
      view.setBackground(((SegmentBackground) background).original);
    }
  }

  private static int rippleColor () {
    return ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface());
  }

  /** Segment fill + M3 state layer (pressed 10% of onSurface), clipped to the segment shape. */
  private static final class SegmentBackground extends RippleDrawable {
    final @Nullable Drawable original;
    private final SegmentShape content, mask;
    private int currentRippleColor;

    SegmentBackground (@Nullable Drawable original) {
      this(original, new SegmentShape(true), new SegmentShape(false));
    }

    private SegmentBackground (@Nullable Drawable original, SegmentShape content, SegmentShape mask) {
      super(ColorStateList.valueOf(rippleColor()), content, mask);
      this.original = original;
      this.content = content;
      this.mask = mask;
      this.currentRippleColor = rippleColor();
    }

    void setShape (boolean connectedTop, boolean connectedBottom) {
      if (content.setShape(connectedTop, connectedBottom) | mask.setShape(connectedTop, connectedBottom)) {
        invalidateSelf();
      }
      int color = rippleColor();
      if (color != currentRippleColor) {
        currentRippleColor = color;
        setColor(ColorStateList.valueOf(color));
      }
    }
  }

  private static final class SegmentShape extends Drawable {
    private final boolean isContent;
    private boolean connectedTop, connectedBottom;
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];

    SegmentShape (boolean isContent) {
      this.isContent = isContent;
    }

    boolean setShape (boolean connectedTop, boolean connectedBottom) {
      if (this.connectedTop != connectedTop || this.connectedBottom != connectedBottom) {
        this.connectedTop = connectedTop;
        this.connectedBottom = connectedBottom;
        return true;
      }
      return false;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      float outer = Screen.dp(M3.CORNER_LARGE);
      float inner = Screen.dp(M3.CORNER_EXTRA_SMALL);
      float top = connectedTop ? inner : outer;
      float bottom = connectedBottom ? inner : outer;
      radii[0] = radii[1] = radii[2] = radii[3] = top;
      radii[4] = radii[5] = radii[6] = radii[7] = bottom;
      rect.set(getBounds());
      path.reset();
      path.addRoundRect(rect, radii, Path.Direction.CW);
      c.drawPath(path, Paints.fillingPaint(isContent ? cardColor() : 0xFFFFFFFF));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
