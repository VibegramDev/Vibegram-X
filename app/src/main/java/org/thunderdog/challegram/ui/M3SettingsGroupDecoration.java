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
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import java.util.List;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

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
 *
 * Gaps and segment fills are painted with the current theme colors at draw time; on a theme change
 * (including the animated switch on the Themes screen) the list and its rows are redrawn, otherwise
 * the RecyclerView display list would keep the gaps of the previous theme.
 */
public final class M3SettingsGroupDecoration extends RecyclerView.ItemDecoration implements RecyclerView.OnChildAttachStateChangeListener, ThemeChangeListener, View.OnAttachStateChangeListener {
  private static final int KIND_OTHER = 0, KIND_CARD = 1, KIND_SEPARATOR = 2, KIND_HIDDEN = 3, KIND_TEXT = 4;
  private static final float GROUP_INSET = 16f;
  private static final float SEGMENT_GAP = 2f; // ListTokens.SegmentedGap
  private static final float SEPARATOR_HEIGHT = 1f; // SettingHolder TYPE_SEPARATOR height

  private final SettingsAdapter adapter;
  private final RecyclerView recyclerView;
  private final int[] customCardViewTypes;

  private M3SettingsGroupDecoration (RecyclerView recyclerView, SettingsAdapter adapter, int[] customCardViewTypes) {
    this.recyclerView = recyclerView;
    this.adapter = adapter;
    this.customCardViewTypes = customCardViewTypes;
  }

  /**
   * Call before {@code setAdapter}. Every row type of the screen must be classified in {@link #kindAt}.
   *
   * @param customCardViewTypes screen-specific view types ({@code ListItem.TYPE_CUSTOM - x}) whose rows sit on the
   *                            filling color and become segments; other custom types are left untouched.
   */
  public static M3SettingsGroupDecoration attach (RecyclerView recyclerView, SettingsAdapter adapter, int... customCardViewTypes) {
    M3SettingsGroupDecoration decoration = new M3SettingsGroupDecoration(recyclerView, adapter, customCardViewTypes);
    recyclerView.addItemDecoration(decoration);
    recyclerView.addOnChildAttachStateChangeListener(decoration);
    // Listen to theme changes only while the list is on screen.
    recyclerView.addOnAttachStateChangeListener(decoration);
    if (ViewCompat.isAttachedToWindow(recyclerView)) {
      ThemeManager.instance().addThemeListener(decoration);
    }
    return decoration;
  }

  @Override
  public void onViewAttachedToWindow (@NonNull View view) {
    ThemeManager.instance().addThemeListener(this);
    // The theme may have changed while the screen was in the back stack.
    redraw();
  }

  @Override
  public void onViewDetachedFromWindow (@NonNull View view) {
    ThemeManager.instance().removeThemeListener(this);
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, @Nullable ColorState state) {
    redraw();
  }

  private void redraw () {
    recyclerView.invalidate();
    for (int i = 0; i < recyclerView.getChildCount(); i++) {
      View view = recyclerView.getChildAt(i);
      Drawable background = view.getBackground();
      if (background instanceof SegmentBackground) {
        background.invalidateSelf();
      }
    }
  }

  /** Segment container: surfaceContainerLowest on the light page (surfaceContainer), surfaceContainerHigh on the dark page (surface). */
  private static int cardColor () {
    // Cards on the surface page: one containment level up (owner's choice of the literal M3 scheme).
    return M3.surfaceContainer();
  }

  private int kindAt (int position) {
    List<ListItem> items = adapter.getItems();
    if (position < 0 || position >= items.size()) {
      return KIND_OTHER;
    }
    int viewType = items.get(position).getViewType();
    if (viewType <= ListItem.TYPE_CUSTOM) {
      for (int customViewType : customCardViewTypes) {
        if (customViewType == viewType) {
          return KIND_CARD;
        }
      }
      return KIND_OTHER;
    }
    switch (viewType) {
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
      // Other rows on the filling color: sliders (SliderWrapView), sessions and websites (RelativeLayout),
      // chats and users (BetterChatView, UserView), cache entries (DoubleTextView)
      case ListItem.TYPE_SLIDER:
      case ListItem.TYPE_SLIDER_BRIGHTNESS:
      case ListItem.TYPE_SESSION:
      case ListItem.TYPE_SESSION_WITH_AVATAR:
      case ListItem.TYPE_CHAT_BETTER:
      case ListItem.TYPE_USER:
      case ListItem.TYPE_DOUBLE_TEXTVIEW_ROUNDED:
      case ListItem.TYPE_CHECKBOX_OPTION_DOUBLE_LINE:
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
      // Full-width "no other sessions / no websites" placeholders on the page color.
      case ListItem.TYPE_SESSIONS_EMPTY:
      case ListItem.TYPE_WEBSITES_EMPTY:
      // Bottom spacer (PaddingView): transparent, or the filling color, which is the page color (Theme.chromeColor).
      case ListItem.TYPE_PADDING:
        return KIND_OTHER;
    }
    return KIND_OTHER;
  }

  /**
   * Height of the spacer rows (shadows painted with the page color, 4dp offsets) above the first row of the list,
   * or -1 if there is any other row above the given position.
   */
  private int leadingSpace (int position) {
    List<ListItem> items = adapter.getItems();
    int height = 0;
    for (int i = 0; i < position && i < items.size(); i++) {
      int viewType = items.get(i).getViewType();
      if (viewType != ListItem.TYPE_EMPTY_OFFSET_SMALL && kindAt(i) != KIND_HIDDEN) {
        return -1;
      }
      height += SettingHolder.measureHeightForType(viewType);
    }
    return height;
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
        int leading = leadingSpace(position);
        if (leading != -1) {
          // The list starts with a group (no section header above it): keep it off the app bar
          // by the same 16dp as the side margins (our choice), counting spacer rows already there.
          outRect.top = Math.max(0, inset - leading);
        } else if (previous == KIND_CARD) {
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
    // A lifted (dragged) row floats above the list: never paint the gaps over it.
    boolean clipped = false;
    for (int i = 0; i < parent.getChildCount(); i++) {
      View view = parent.getChildAt(i);
      Drawable background = view.getBackground();
      if (background instanceof SegmentBackground && ((SegmentBackground) background).isLifted()) {
        if (!clipped) {
          c.save();
          clipped = true;
        }
        float dx = view.getTranslationX(), dy = view.getTranslationY();
        float left = view.getLeft() + dx, top = view.getTop() + dy, right = view.getRight() + dx, bottom = view.getBottom() + dy;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          c.clipOutRect(left, top, right, bottom);
        } else {
          //noinspection deprecation
          c.clipRect(left, top, right, bottom, Region.Op.DIFFERENCE);
        }
      }
    }
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
    if (clipped) {
      c.restore();
    }
  }

  @Override
  public void onChildViewAttachedToWindow (@NonNull View view) { }

  @Override
  public void onChildViewDetachedFromWindow (@NonNull View view) {
    // A recycled holder may be reused for another row type or after a theme switch: give it back its own background.
    restore(view);
  }

  /**
   * Dragged state of a reorderable segment (call when ItemTouchHelper picks the row up and when it is released).
   * M3 list item tokens (ListItemTokens): dragged container shape CornerLarge (16dp on every corner),
   * elevation Level4 (8dp), state layer onSurface 16%. Corners follow the fast spatial spring,
   * elevation and the state layer the fast effects spring.
   */
  public void setDragged (View view, boolean isDragged) {
    Drawable background = view.getBackground();
    if (!(background instanceof SegmentBackground)) {
      int position = recyclerView.getChildAdapterPosition(view);
      if (!isDragged || position == RecyclerView.NO_POSITION || kindAt(position) != KIND_CARD) {
        return;
      }
      applySegment(view, isConnected(position, -1), isConnected(position, 1));
      background = view.getBackground();
    }
    ((SegmentBackground) background).setDragged(view, isDragged);
  }

  /**
   * Clips the canvas to the segment shape of the view, if the view is a segment. Used by rows that draw over their
   * whole bounds (swipe-to-remove background of {@code SettingView}). Returns whether {@link Canvas#save()} was called.
   */
  public static boolean clipToSegment (View view, Canvas c) {
    Drawable background = view.getBackground();
    if (background instanceof SegmentBackground) {
      c.save();
      c.clipPath(((SegmentBackground) background).content.buildPath());
      return true;
    }
    return false;
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
    if (segment.setShape(connectedTop, connectedBottom) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.invalidateOutline();
    }
  }

  private static void restore (View view) {
    Drawable background = view.getBackground();
    if (background instanceof SegmentBackground) {
      ((SegmentBackground) background).resetDragged(view);
      view.setBackground(((SegmentBackground) background).original);
    }
  }

  private static int rippleColor () {
    return ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface());
  }

  /** Segment fill + M3 state layer (pressed 10% of onSurface), clipped to the segment shape. */
  private static final class SegmentBackground extends RippleDrawable implements FactorAnimator.Target {
    private static final int ANIMATOR_SHAPE = 0, ANIMATOR_LIFT = 1;

    final @Nullable Drawable original;
    private final SegmentShape content, mask;
    private int currentRippleColor;

    private @Nullable View draggedView;
    private @Nullable BoolAnimator shapeAnimator, liftAnimator;

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

    /** Returns whether the shape changed (the view outline must be rebuilt). */
    boolean setShape (boolean connectedTop, boolean connectedBottom) {
      boolean changed = content.setShape(connectedTop, connectedBottom) | mask.setShape(connectedTop, connectedBottom);
      if (changed) {
        invalidateSelf();
      }
      int color = rippleColor();
      if (color != currentRippleColor) {
        currentRippleColor = color;
        setColor(ColorStateList.valueOf(color));
      }
      return changed;
    }

    void setDragged (View view, boolean isDragged) {
      this.draggedView = view;
      if (shapeAnimator == null || liftAnimator == null) {
        if (!isDragged) {
          return;
        }
        shapeAnimator = new BoolAnimator(ANIMATOR_SHAPE, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration);
        liftAnimator = new BoolAnimator(ANIMATOR_LIFT, this, M3.FAST_EFFECTS, M3.FAST_EFFECTS.duration);
      }
      shapeAnimator.setValue(isDragged, true);
      liftAnimator.setValue(isDragged, true);
    }

    void resetDragged (View view) {
      if (shapeAnimator != null) {
        shapeAnimator.setValue(false, false);
      }
      if (liftAnimator != null) {
        liftAnimator.setValue(false, false);
      }
      content.setDragFactors(0f, 0f);
      mask.setDragFactors(0f, 0f);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        view.setTranslationZ(0f);
      }
      draggedView = null;
    }

    boolean isLifted () {
      return content.liftFactor > 0f || content.shapeFactor > 0f;
    }

    @Override
    public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
      View view = draggedView;
      if (id == ANIMATOR_SHAPE) {
        content.setDragFactors(factor, content.liftFactor);
        mask.setDragFactors(factor, mask.liftFactor);
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          view.invalidateOutline();
        }
      } else {
        content.setDragFactors(content.shapeFactor, factor);
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          view.setTranslationZ(Screen.dp(M3.ELEVATION_LEVEL4) * factor);
        }
      }
      invalidateSelf();
    }
  }

  private static final class SegmentShape extends Drawable {
    private final boolean isContent;
    private boolean connectedTop, connectedBottom;
    /** 0 = resting segment, 1 = dragged (16dp on every corner); may overshoot (spatial spring). */
    private float shapeFactor;
    /** Dragged state layer (onSurface 16%) and elevation, 0..1. */
    private float liftFactor;
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

    void setDragFactors (float shapeFactor, float liftFactor) {
      this.shapeFactor = shapeFactor;
      this.liftFactor = liftFactor;
    }

    Path buildPath () {
      float outer = Screen.dp(M3.CORNER_LARGE);
      float inner = Screen.dp(M3.CORNER_EXTRA_SMALL);
      Rect bounds = getBounds();
      float maxRadius = Math.min(bounds.width(), bounds.height()) / 2f;
      float top = MathUtils.clamp(MathUtils.fromTo(connectedTop ? inner : outer, outer, shapeFactor), 0f, maxRadius);
      float bottom = MathUtils.clamp(MathUtils.fromTo(connectedBottom ? inner : outer, outer, shapeFactor), 0f, maxRadius);
      radii[0] = radii[1] = radii[2] = radii[3] = top;
      radii[4] = radii[5] = radii[6] = radii[7] = bottom;
      rect.set(bounds);
      path.reset();
      path.addRoundRect(rect, radii, Path.Direction.CW);
      return path;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      Path path = buildPath();
      c.drawPath(path, Paints.fillingPaint(isContent ? cardColor() : 0xFFFFFFFF));
      if (isContent && liftFactor > 0f) {
        c.drawPath(path, Paints.fillingPaint(ColorUtils.alphaColor(M3.STATE_DRAGGED * MathUtils.clamp(liftFactor), M3.onSurface())));
      }
    }

    /** Outline for the elevation shadow of a dragged segment. */
    @Override
    public void getOutline (@NonNull Outline outline) {
      Path path = buildPath();
      if (radii[0] == radii[4]) {
        outline.setRoundRect(getBounds(), radii[0]);
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        outline.setPath(path);
      } else {
        //noinspection deprecation
        outline.setConvexPath(path);
      }
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
