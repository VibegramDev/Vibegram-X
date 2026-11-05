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
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The gold Telegram Star: a five-pointed star with rounded points and a yellow → orange gradient, drawn into the
 * bounds (centred, aspect kept). Used for icons, list rows and {@link StarSpan}.
 */
public class StarDrawable extends Drawable {
  public static final int COLOR_TOP = 0xFFFFD43B, COLOR_BOTTOM = 0xFFF29900;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private int pathWidth = -1, pathHeight = -1;
  private final int intrinsicSize;

  public StarDrawable () {
    this(0);
  }

  public StarDrawable (int intrinsicSize) {
    this.intrinsicSize = intrinsicSize;
    paint.setStyle(Paint.Style.FILL);
  }

  @Override
  public int getIntrinsicWidth () {
    return intrinsicSize > 0 ? intrinsicSize : -1;
  }

  @Override
  public int getIntrinsicHeight () {
    return intrinsicSize > 0 ? intrinsicSize : -1;
  }

  private void rebuild (int width, int height) {
    pathWidth = width;
    pathHeight = height;
    float size = Math.min(width, height);
    // A five-pointed star is 1.902 R wide and 1.809 R tall (R = outer radius).
    float outer = size / 1.95f;
    float inner = outer * .53f;
    float cx = width / 2f;
    float starHeight = outer * 1.809f;
    float cy = (height - starHeight) / 2f + outer;
    path.reset();
    for (int i = 0; i < 10; i++) {
      double angle = Math.toRadians(-90 + i * 36);
      float radius = i % 2 == 0 ? outer : inner;
      float x = cx + (float) (Math.cos(angle) * radius);
      float y = cy + (float) (Math.sin(angle) * radius);
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    path.close();
    paint.setPathEffect(new CornerPathEffect(outer * .3f));
    paint.setShader(new LinearGradient(cx - outer * .6f, cy - outer, cx + outer * .6f, cy + outer * .8f, COLOR_TOP, COLOR_BOTTOM, Shader.TileMode.CLAMP));
  }

  @Override
  public void draw (@NonNull Canvas c) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) {
      return;
    }
    if (bounds.width() != pathWidth || bounds.height() != pathHeight) {
      rebuild(bounds.width(), bounds.height());
    }
    int saveCount = c.save();
    c.translate(bounds.left, bounds.top);
    c.drawPath(path, paint);
    c.restoreToCount(saveCount);
  }

  @Override
  public void setAlpha (int alpha) {
    paint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter (@Nullable ColorFilter colorFilter) {
    paint.setColorFilter(colorFilter);
    invalidateSelf();
  }

  @Override
  public int getOpacity () {
    return PixelFormat.TRANSLUCENT;
  }
}
