/*
 * This file is a part of Vibegram X, a fork of Telegram X
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
package org.thunderdog.challegram.stars;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** The gold star inside text of a {@link android.widget.TextView}, about the height of a capital letter. */
public class StarSpan extends ReplacementSpan {
  // Spans are drawn on the main thread, and every draw sets the bounds and the alpha: one drawable serves all of them.
  private static StarDrawable sharedDrawable;

  private static StarDrawable drawable () {
    if (sharedDrawable == null) {
      sharedDrawable = new StarDrawable();
    }
    return sharedDrawable;
  }

  private final float scale;

  public StarSpan () {
    this(1.2f);
  }

  public StarSpan (float scale) {
    this.scale = scale;
  }

  private int size (Paint paint) {
    return Math.round(paint.getTextSize() * scale);
  }

  @Override
  public int getSize (@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
    if (fm != null) {
      paint.getFontMetricsInt(fm);
    }
    return size(paint);
  }

  @Override
  public void draw (@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
    int size = size(paint);
    Paint.FontMetricsInt fm = paint.getFontMetricsInt();
    float centerY = y + (fm.ascent + fm.descent) / 2f;
    int drawTop = Math.round(centerY - size / 2f);
    StarDrawable drawable = drawable();
    drawable.setAlpha(paint.getAlpha());
    drawable.setBounds(Math.round(x), drawTop, Math.round(x) + size, drawTop + size);
    drawable.draw(canvas);
  }
}
