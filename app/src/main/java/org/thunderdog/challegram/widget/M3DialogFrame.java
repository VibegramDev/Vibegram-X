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
import android.graphics.Outline;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Basic M3 dialog on a large screen (m3.material.io/components/dialogs › Specs): a window-sized frame that keeps the
 * dialog in its center, at most 560dp wide, 28dp corners, Level 3 shadow. Touches outside the dialog fall through to
 * the popup (which closes it).
 */
public class M3DialogFrame extends FrameLayoutFix {
  /** Maximum width of a basic dialog (M3 Specs). */
  public static final float MAX_WIDTH = 560f;
  /** Distance to the window edges, so the dialog never touches them (the value is ours, M3 gives none). */
  private static final float WINDOW_MARGIN = 24f;

  private final FrameLayoutFix card;
  private final int maxHeight;

  /**
   * @param content view filling the dialog
   * @param maxHeight maximum height of the dialog, px (the dialog is lower when the window is lower)
   */
  public M3DialogFrame (Context context, View content, int maxHeight) {
    super(context);
    this.maxHeight = maxHeight;
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    card = new FrameLayoutFix(context);
    card.setLayoutParams(FrameLayoutFix.newParams(0, 0, Gravity.CENTER));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      card.setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Screen.dp(M3.CORNER_EXTRA_LARGE));
        }
      });
      card.setClipToOutline(true);
      card.setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    }
    card.addView(content);
    addView(card);
  }

  public View getCard () {
    return card;
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int margin = Screen.dp(WINDOW_MARGIN) * 2;
    int width = Math.min(Screen.dp(MAX_WIDTH), MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight() - margin);
    int height = Math.min(maxHeight, MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom() - margin);
    ViewGroup.LayoutParams params = card.getLayoutParams();
    params.width = width;
    params.height = height;
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }
}
