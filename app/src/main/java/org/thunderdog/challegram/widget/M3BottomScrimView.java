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
 * Backdrop of a floating bottom container (tablet dock, tablet message composer): content scrolled under the
 * container fades into {@code surface} above it and stays faintly visible below its top edge (the fade of the
 * phone dock), and the container casts a soft
 * shadow. Both floating containers use this view, so their fade and shadow are identical.
 */
public class M3BottomScrimView extends View implements ThemeInvalidateListener {
  public interface ShapeProvider {
    /** Fills the bounds of the floating container in the coordinates of this view, false when there is none. */
    boolean getShape (@NonNull M3BottomScrimView view, @NonNull RectF out);
  }

  /**
   * Height of the fade above the floating container: as the fade of the phone dock (from a little above the folder
   * chips to the top edge of the dock).
   */
  public static int getFadeHeight () {
    return Screen.dp(80f);
  }

  /** Opacity of the surface at the top edge of the container and at the bottom of the view (as the phone dock). */
  private static final int ALPHA_AT_CONTAINER = 0xB3, ALPHA_AT_BOTTOM = 0xEB;

  private final Fade fade = new Fade();
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shape = new RectF();
  private @Nullable ShapeProvider provider;
  private float radius;
  private boolean shadowOnly;

  public M3BottomScrimView (Context context) {
    super(context);
  }

  /** Draws only the shadow of the floating container: the content behind it is washed out by another view. */
  public void setShadowOnly (boolean shadowOnly) {
    if (this.shadowOnly != shadowOnly) {
      this.shadowOnly = shadowOnly;
      invalidate();
    }
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
    if (!shadowOnly) {
      fade.draw(c, getWidth(), shape.top, getHeight(), getFadeHeight(), 1f);
    }
    drawShadow(c, shadowPaint, shape, radius, 1f);
  }

  /** The fade of this view, for views that draw their floating container themselves (strip of mini app tabs). */
  public static final class Fade {
    // Two eased gradients of unit height (above / below the top edge of the container), stretched by a local matrix:
    // the container moves on every frame of its animations, the shaders are created once per color.
    // No anti-aliasing, so the two rects meet without a seam.
    private final Paint abovePaint = new Paint(), belowPaint = new Paint();
    private final Matrix aboveMatrix = new Matrix(), belowMatrix = new Matrix();
    private int color;
    private boolean shadersReady, aboveVisible;

    /**
     * From {@code top - fadeHeight} to {@code bottom}: transparent -> 70% surface at the top edge of the container
     * ({@code top}) -> 92% at {@code bottom} (the content stays faintly visible below the container, as under the
     * phone dock).
     */
    public void draw (Canvas c, float width, float top, float bottom, int fadeHeight, float alpha) {
      if (alpha <= 0f) {
        return;
      }
      final int surface = M3.surface();
      final boolean fadeAbove = fadeHeight > 0;
      if (!shadersReady || color != surface || aboveVisible != fadeAbove) {
        shadersReady = true;
        color = surface;
        aboveVisible = fadeAbove;
        // No fade above the container: the surface starts from zero at its top edge.
        int alphaAtContainer = fadeAbove ? ALPHA_AT_CONTAINER : 0;
        abovePaint.setShader(newEasedFade(surface, 0, alphaAtContainer));
        belowPaint.setShader(newEasedFade(surface, alphaAtContainer, ALPHA_AT_BOTTOM));
      }
      final int paintAlpha = Math.round(255 * Math.min(1f, alpha));
      if (fadeAbove) {
        aboveMatrix.setScale(1f, fadeHeight);
        aboveMatrix.postTranslate(0, top - fadeHeight);
        abovePaint.getShader().setLocalMatrix(aboveMatrix);
        abovePaint.setAlpha(paintAlpha);
        c.drawRect(0, top - fadeHeight, width, top, abovePaint);
      }
      final float below = Math.max(1f, bottom - top);
      belowMatrix.setScale(1f, below);
      belowMatrix.postTranslate(0, top);
      belowPaint.getShader().setLocalMatrix(belowMatrix);
      belowPaint.setAlpha(paintAlpha);
      c.drawRect(0, top, width, top + below, belowPaint);
    }
  }

  private static final int SMOOTH_FADE_STEPS = 12;

  /**
   * Vertical fade of {@code color} from transparent at 0 to {@code alphaAtContainer} at {@code containerFraction} of
   * {@code height} and on to {@code alphaAtBottom} at {@code height}. Both parts ease in and out (smoothstep), so the
   * opacity changes without a jump in its rate: a linear ramp shows a visible line where it starts and where its slope
   * changes, at the top of the fade and beside the floating container.
   */
  public static Shader newSmoothFade (float height, int color, float containerFraction, int alphaAtContainer, int alphaAtBottom) {
    final int rgb = color & 0x00FFFFFF;
    final float split = Math.max(0f, Math.min(1f, containerFraction));
    final int steps = SMOOTH_FADE_STEPS;
    final int[] colors = new int[steps * 2 + 1];
    final float[] positions = new float[steps * 2 + 1];
    for (int i = 0; i <= steps * 2; i++) {
      boolean upper = i <= steps;
      float t = upper ? (float) i / steps : (float) (i - steps) / steps;
      float eased = t * t * (3f - 2f * t);
      float alpha = upper ? alphaAtContainer * eased : alphaAtContainer + (alphaAtBottom - alphaAtContainer) * eased;
      colors[i] = (Math.round(alpha) << 24) | rgb;
      positions[i] = upper ? split * t : split + (1f - split) * t;
    }
    return new LinearGradient(0, 0, 0, height, colors, positions, Shader.TileMode.CLAMP);
  }

  /** One eased part of {@link #newSmoothFade}: {@code alphaFrom} at y = 0 to {@code alphaTo} at y = 1. */
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
