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
package org.thunderdog.challegram.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.SeparatorView;
import org.thunderdog.challegram.widget.ShadowView;

import me.vkryl.core.ColorUtils;

/**
 * Container of the contained full-screen search view (m3.material.io/components/search; the expanded
 * contained search bar uses surfaceContainerLow for the whole view).
 *
 * The rows of the result list keep their own content, but their filled backgrounds are replaced with a transparent
 * M3 state layer (pressed: 10% onSurface) on clickable rows and removed on the others, so the container color drawn
 * by this decoration is visible behind every row. Backgrounds set again by theme listeners are replaced on the next draw.
 */
public final class M3SearchContainerDecoration extends RecyclerView.ItemDecoration {
  public interface ColorProvider {
    @ColorInt int getContainerColor ();
  }

  private final ColorProvider colorProvider;
  private final boolean sheet;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private M3SearchContainerDecoration (ColorProvider colorProvider, boolean sheet) {
    this.colorProvider = colorProvider;
    this.sheet = sheet;
  }

  /**
   * M3 bottom sheet list ({@code ViewController.showSettings}): the container starts at the top of the first
   * adapter item (it scrolls with the list) and has 28dp top corners; the legacy top shadow row is hidden.
   */
  public static M3SearchContainerDecoration attachSheet (RecyclerView recyclerView, ColorProvider colorProvider) {
    M3SearchContainerDecoration decoration = new M3SearchContainerDecoration(colorProvider, true);
    recyclerView.setBackground(null);
    recyclerView.addItemDecoration(decoration);
    return decoration;
  }

  /**
   * Space above the first item for the drag handle (DragHandle: 32x4dp, 22dp vertical padding = 48dp). The first
   * item of a settings sheet is the 6dp TYPE_SHADOW_TOP row, so the offset added above it is 48 - 6 = 42dp.
   */
  public static final float SHEET_HANDLE_AREA = 48f, SHEET_HANDLE_OFFSET = 42f;

  /** Top of the sheet container inside the list (incl. the handle area), or above the view when it is scrolled away. */
  public static int sheetTop (RecyclerView parent) {
    RecyclerView.ViewHolder holder = parent.findViewHolderForAdapterPosition(0);
    if (holder == null) {
      return -Screen.dp(M3.CORNER_EXTRA_LARGE);
    }
    return holder.itemView.getTop() + Math.round(holder.itemView.getTranslationY()) - Screen.dp(SHEET_HANDLE_OFFSET);
  }

  @Override
  public void getItemOffsets (@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    if (sheet && parent.getChildAdapterPosition(view) == 0) {
      outRect.top = Screen.dp(SHEET_HANDLE_OFFSET);
    }
  }

  /** The recycler background must not be set by the caller (the decoration draws the container). */
  public static M3SearchContainerDecoration attach (RecyclerView recyclerView, ColorProvider colorProvider) {
    M3SearchContainerDecoration decoration = new M3SearchContainerDecoration(colorProvider, false);
    recyclerView.setBackground(null);
    recyclerView.addItemDecoration(decoration);
    return decoration;
  }

  @Override
  public void onDraw (@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    int containerColor = colorProvider.getContainerColor();
    if (sheet) {
      float radius = Screen.dp(M3.CORNER_EXTRA_LARGE);
      int top = sheetTop(parent);
      paint.setColor(containerColor);
      RectF rect = Paints.getRectF();
      rect.set(0, top, parent.getWidth(), parent.getHeight() + radius);
      c.drawRoundRect(rect, radius, radius, paint);
      // Drag handle: 32x4dp, onSurfaceVariant, 22dp below the container top.
      float handleWidth = Screen.dp(32f), handleHeight = Screen.dp(4f);
      float handleTop = top + Screen.dp(22f);
      paint.setColor(M3.onSurfaceVariant());
      float cx = parent.getWidth() / 2f;
      rect.set(cx - handleWidth / 2f, handleTop, cx + handleWidth / 2f, handleTop + handleHeight);
      c.drawRoundRect(rect, handleHeight / 2f, handleHeight / 2f, paint);
    } else {
      c.drawColor(containerColor);
    }
    int stateLayerColor = ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface());
    for (int i = 0; i < parent.getChildCount(); i++) {
      apply(parent.getChildAt(i), containerColor, stateLayerColor);
    }
  }

  private void apply (View view, int containerColor, int stateLayerColor) {
    if (sheet && view instanceof ShadowView) {
      // Legacy top shadow above the sheet: the M3 sheet has rounded corners and Level1 elevation instead.
      if (view.getVisibility() != View.INVISIBLE) {
        view.setVisibility(View.INVISIBLE);
      }
      return;
    }
    if (view instanceof SeparatorView) {
      // Draws its own filling under the divider line.
      SeparatorView separatorView = (SeparatorView) view;
      if (separatorView.getForcedFillingColor() != containerColor) {
        separatorView.forceFillingColor(containerColor);
        separatorView.invalidate();
      }
      return;
    }
    Drawable background = view.getBackground();
    if (background instanceof StateLayer) {
      ((StateLayer) background).setStateLayerColor(stateLayerColor);
    } else if (background != null) {
      view.setBackground(view.hasOnClickListeners() ? new StateLayer(stateLayerColor) : null);
    }
  }

  /** Transparent container with the M3 pressed state layer. */
  private static final class StateLayer extends M3StateLayerDrawable {
    StateLayer (int stateLayerColor) {
      super(null, new ColorDrawable(Color.WHITE), stateLayerColor);
    }

    void setStateLayerColor (int color) {
      setColor(color);
    }
  }
}
