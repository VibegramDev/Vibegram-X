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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.SeparatorView;

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

  private M3SearchContainerDecoration (ColorProvider colorProvider) {
    this.colorProvider = colorProvider;
  }

  /** The recycler background must not be set by the caller (the decoration draws the container). */
  public static M3SearchContainerDecoration attach (RecyclerView recyclerView, ColorProvider colorProvider) {
    M3SearchContainerDecoration decoration = new M3SearchContainerDecoration(colorProvider);
    recyclerView.setBackground(null);
    recyclerView.addItemDecoration(decoration);
    return decoration;
  }

  @Override
  public void onDraw (@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    int containerColor = colorProvider.getContainerColor();
    c.drawColor(containerColor);
    int stateLayerColor = ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface());
    for (int i = 0; i < parent.getChildCount(); i++) {
      apply(parent.getChildAt(i), containerColor, stateLayerColor);
    }
  }

  private void apply (View view, int containerColor, int stateLayerColor) {
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
