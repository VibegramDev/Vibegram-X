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
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;
import org.thunderdog.challegram.tool.Screen;

/**
 * Backdrop of a floating bottom container (tablet dock, tablet message composer): content scrolled under the
 * container fades into {@code surface} above it, is hidden below its top edge, and the container casts a soft
 * shadow. Both floating containers use this view, so their fade and shadow are identical.
 */
public class M3BottomScrimView extends View implements ThemeInvalidateListener {
  public interface ShapeProvider {
    /** Fills the bounds of the floating container in the coordinates of this view, false when there is none. */
    boolean getShape (@NonNull M3BottomScrimView view, @NonNull RectF out);
  }

  /** Height of the fade above the floating container. */
  public static int getFadeHeight () {
    return Screen.dp(24f);
  }

  private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint solidPaint = new Paint();
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shape = new RectF();
  private @Nullable ShapeProvider provider;
  private float radius;
  private int fadeColor, fadeHeight;

  public M3BottomScrimView (Context context) {
    super(context);
  }

  public void setShapeProvider (@Nullable ShapeProvider provider, float radius) {
    this.provider = provider;
    this.radius = radius;
    invalidate();
  }

  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    invalidate();
  }

  @Override
  protected void onDraw (Canvas c) {
    if (provider == null || !provider.getShape(this, shape)) {
      return;
    }
    final int surface = M3.surface();
    final int fade = getFadeHeight();
    if (fadeColor != surface || fadeHeight != fade) {
      fadeColor = surface;
      fadeHeight = fade;
      int rgb = surface & 0x00FFFFFF;
      fadePaint.setShader(new LinearGradient(0, 0, 0, fade,
        new int[] {rgb, (0x99 << 24) | rgb, (0xFF << 24) | rgb},
        new float[] {0f, .5f, 1f},
        Shader.TileMode.CLAMP));
    }
    final int width = getWidth();
    final float top = shape.top;
    c.save();
    c.translate(0, top - fade);
    c.drawRect(0, 0, width, fade, fadePaint);
    c.restore();
    solidPaint.setColor(surface);
    c.drawRect(0, top, width, getHeight(), solidPaint);
    drawShadow(c, shape, radius);
  }

  /** Soft elevation shadow (M3 level 2 look): a wide faint key shadow below and a tight ambient one around. */
  private void drawShadow (Canvas c, RectF rect, float radius) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      // Hardware accelerated shadow layers for shapes are supported since Android 9.
      return;
    }
    final boolean dark = Theme.isDark();
    shadowPaint.setColor(M3.surfaceContainer());
    shadowPaint.setShadowLayer(Screen.dp(6f), 0, Screen.dp(2f), dark ? 0x66000000 : 0x1F000000);
    c.drawRoundRect(rect, radius, radius, shadowPaint);
    shadowPaint.setShadowLayer(Screen.dp(1.5f), 0, Screen.dp(.5f), dark ? 0x4D000000 : 0x14000000);
    c.drawRoundRect(rect, radius, radius, shadowPaint);
  }
}
