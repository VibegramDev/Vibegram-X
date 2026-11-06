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
 * File created on 09/08/2015 at 16:11
 */
package org.thunderdog.challegram.navigation;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

public class TriangleView {
  private Paint paint;
  private Path path;

  private float width, height;

  public TriangleView () {
    this(0f, 0f);
  }

  public TriangleView (float x, float y) {
    paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    paint.setColor(Theme.headerBackColor());
    paint.setStyle(Paint.Style.FILL);

    width = Screen.dpf(10f * SCALE_X);
    height = Screen.dpf(5f * SCALE_Y);

    // Telegram `ic_arrow_drop_down`: a 9.67 x 4.88dp triangle with rounded corners, built from the rounded outline of
    // the 10 x 5dp Material Symbols `arrow_drop_down` (960-unit viewport, (280, 400) being the sharp top-left corner)
    // scaled to Telegram's size.
    path = new Path();
    path.moveTo(px(x, 459), py(y, 579));
    path.lineTo(px(x, 314), py(y, 434));
    path.quadTo(px(x, 311), py(y, 431), px(x, 309.5f), py(y, 427.5f));
    path.quadTo(px(x, 308), py(y, 424), px(x, 308), py(y, 420));
    path.quadTo(px(x, 308), py(y, 412), px(x, 313.5f), py(y, 406));
    path.quadTo(px(x, 319), py(y, 400), px(x, 328), py(y, 400));
    path.lineTo(px(x, 632), py(y, 400));
    path.quadTo(px(x, 641), py(y, 400), px(x, 646.5f), py(y, 406));
    path.quadTo(px(x, 652), py(y, 412), px(x, 652), py(y, 420));
    path.quadTo(px(x, 652), py(y, 424), px(x, 650.5f), py(y, 427.5f));
    path.quadTo(px(x, 649), py(y, 431), px(x, 646), py(y, 434));
    path.lineTo(px(x, 501), py(y, 579));
    path.quadTo(px(x, 496), py(y, 584), px(x, 491), py(y, 586));
    path.quadTo(px(x, 486), py(y, 588), px(x, 480), py(y, 588));
    path.quadTo(px(x, 474), py(y, 588), px(x, 469), py(y, 586));
    path.quadTo(px(x, 464), py(y, 584), px(x, 459), py(y, 579));
    path.close();
  }

  private static final float SCALE_X = 9.67f / 10f, SCALE_Y = 4.88f / 5f;

  private static float px (float x, float symbolX) {
    return x + Screen.dpf((symbolX - 280f) / 40f * SCALE_X);
  }

  private static float py (float y, float symbolY) {
    return y + Screen.dpf((symbolY - 400f) / 40f * SCALE_Y);
  }

  public float getCenterX () {
    return width * .5f;
  }

  public float getWidth () {
    return width;
  }

  public float getHeight () {
    return height;
  }

  public void setColor (int color) {
    paint.setColor(color);
  }

  public void draw (Canvas c) {
    c.drawPath(path, paint);
  }
}
