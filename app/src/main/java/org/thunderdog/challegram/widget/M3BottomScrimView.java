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
import android.graphics.Matrix;
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

import me.vkryl.core.ColorUtils;

/**
 * Shadow of a floating bottom container (dock, message composer). The content behind it is not dimmed (Vibegram X:
 * the owner removed the fade of the official app). {@link Fade} is the fade of the official app, still used under the
 * strip of mini app tabs.
 */
public class M3BottomScrimView extends View implements ThemeInvalidateListener {
  public interface ShapeProvider {
    /** Fills the bounds of the floating container in the coordinates of this view, false when there is none. */
    boolean getShape (@NonNull M3BottomScrimView view, @NonNull RectF out);
  }

  /** Part of the band the fade takes from its top, the surface is flat below (behind the bottom of the container). */
  private static final float FADE_SPREAD = .75f;

  /** Opacity of the surface at the end of the fade and below it (the official app: 0xE8). */
  private static final int ALPHA_MAX = 0xE8;

  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shape = new RectF();
  private @Nullable ShapeProvider provider;
  private float radius;

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
    // Vibegram X: no fade of the content behind the dock and the composer (owner's request), only the shadow.
    drawShadow(c, shadowPaint, shape, radius, 1f);
  }

  /** The fade of this view, for views that draw their floating container themselves (dock, strip of mini app tabs). */
  public static final class Fade {
    // An eased gradient of unit height stretched by a local matrix (the band moves on every frame of the keyboard and
    // tab animations, the shader is created once per color).
    private final Paint fadePaint = new Paint();
    private final Matrix matrix = new Matrix();
    private int color;
    private boolean shaderReady;

    /** Vibegram X: SRC_ATOP fades only what is already drawn (the round video player over the list). */
    public void setXfermode (@Nullable android.graphics.Xfermode xfermode) {
      fadePaint.setXfermode(xfermode);
    }

    /**
     * From {@code top} (the top of the band kept free for the container) to {@code bottom}: transparent at {@code top},
     * eased to {@link #ALPHA_MAX} of the surface over {@link #FADE_SPREAD} of the band, flat below (the official app spreads
     * the fade over the zone too: a short fixed ramp reads as a dark strip at its top). Nothing above {@code top}.
     */
    public void draw (Canvas c, float width, float top, float bottom, float alpha) {
      draw(c, width, top, bottom, bottom, alpha);
    }

    /** The same, the flat end of the fade goes on from {@code bottom} down to {@code drawBottom}. */
    public void draw (Canvas c, float width, float top, float bottom, float drawBottom, float alpha) {
      if (alpha <= 0f || bottom <= top) {
        return;
      }
      final int surface = M3.surface();
      if (!shaderReady || color != surface) {
        shaderReady = true;
        color = surface;
        fadePaint.setShader(newEasedFade(surface, 0, ALPHA_MAX));
      }
      matrix.setScale(1f, (bottom - top) * FADE_SPREAD);
      matrix.postTranslate(0, top);
      fadePaint.getShader().setLocalMatrix(matrix);
      fadePaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
      c.drawRect(0, top, width, Math.max(bottom, drawBottom), fadePaint);
    }
  }

  private static final int SMOOTH_FADE_STEPS = 24;

  /**
   * {@code color} from {@code alphaFrom} at y = 0 to {@code alphaTo} at y = 1, eased in and out (smoothstep): a linear
   * ramp shows a visible line where it starts.
   */
  private static Shader newEasedFade (int color, int alphaFrom, int alphaTo) {
    final int rgb = color & 0x00FFFFFF;
    final int steps = SMOOTH_FADE_STEPS;
    final int[] colors = new int[steps + 1];
    final float[] positions = new float[steps + 1];
    for (int i = 0; i <= steps; i++) {
      float t = (float) i / steps;
      float eased = t * t * (3f - 2f * t);
      colors[i] = (Math.round(alphaFrom + (alphaTo - alphaFrom) * eased) << 24) | rgb;
      positions[i] = t;
    }
    return new LinearGradient(0, 0, 0, 1, colors, positions, Shader.TileMode.CLAMP);
  }

  /**
   * Soft elevation shadow (M3 level 2 look): a wide faint key shadow below and a tight ambient one around, cast by a
   * container of {@code surfaceContainer} color ({@code alpha} for a container that fades in or out).
   */
  public static void drawShadow (Canvas c, Paint paint, RectF rect, float radius, float alpha) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || alpha <= 0f) {
      // Hardware accelerated shadow layers for shapes are supported since Android 9.
      return;
    }
    final boolean dark = Theme.isDark();
    paint.setColor(ColorUtils.alphaColor(alpha, M3.surfaceContainer()));
    paint.setShadowLayer(Screen.dp(6f), 0, Screen.dp(2f), ColorUtils.alphaColor(alpha, dark ? 0x66000000 : 0x1F000000));
    c.drawRoundRect(rect, radius, radius, paint);
    paint.setShadowLayer(Screen.dp(1.5f), 0, Screen.dp(.5f), ColorUtils.alphaColor(alpha, dark ? 0x4D000000 : 0x14000000));
    c.drawRoundRect(rect, radius, radius, paint);
  }
}
