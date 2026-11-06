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

/**
 * Backdrop of a floating bottom container (tablet dock, tablet message composer): content scrolled under the
 * container fades into {@code surface} above it and stays faintly visible below its top edge, and the container casts
 * a soft shadow. Both floating containers use this view, so their fade and shadow are identical. The phone dock uses
 * only the shadow: the folder chips above it need an opaque backdrop ({@link M3DockScrimView}).
 */
public class M3BottomScrimView extends View implements ThemeInvalidateListener {
  public interface ShapeProvider {
    /** Fills the bounds of the floating container in the coordinates of this view, false when there is none. */
    boolean getShape (@NonNull M3BottomScrimView view, @NonNull RectF out);
  }

  /**
   * Height of the fade above the floating container.
   */
  public static int getFadeHeight () {
    return Screen.dp(80f);
  }

  /** Opacity of the surface at the top edge of the container and at the bottom of the view. */
  private static final int ALPHA_AT_CONTAINER = 0xB3, ALPHA_AT_BOTTOM = 0xEB;

  // The fade is two eased gradients of unit height (above / below the top edge of the container), stretched by a
  // local matrix: the container moves on every frame of its animations, the shaders are created once per color.
  // No anti-aliasing, so the two rects meet without a seam.
  private final Paint fadeAbovePaint = new Paint(), fadeBelowPaint = new Paint();
  private final Matrix fadeAboveMatrix = new Matrix(), fadeBelowMatrix = new Matrix();
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shape = new RectF();
  private @Nullable ShapeProvider provider;
  private float radius;
  private int fadeColor;
  private boolean fadeShadersReady, fadeAboveVisible;
  private boolean shadowOnly;
  private int fadeHeightOverride = -1;

  public M3BottomScrimView (Context context) {
    super(context);
  }

  /**
   * Height of the fade above the container for this view (message composer: short, so the last message right above
   * the composer stays undimmed), -1 for {@link #getFadeHeight()}.
   */
  public void setFadeHeight (int fadeHeight) {
    if (this.fadeHeightOverride != fadeHeight) {
      this.fadeHeightOverride = fadeHeight;
      invalidate();
    }
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
    if (shadowOnly) {
      drawShadow(c, shape, radius);
      return;
    }
    final int surface = M3.surface();
    final int fade = fadeHeightOverride >= 0 ? fadeHeightOverride : getFadeHeight();
    final int width = getWidth();
    final float top = shape.top;
    final boolean fadeAbove = fade > 0;
    // From the top of the fade to the bottom of the view: transparent -> 70% surface at the top edge of the container
    // -> 92% at the bottom (the content stays faintly visible below the container).
    if (!fadeShadersReady || fadeColor != surface || fadeAboveVisible != fadeAbove) {
      fadeShadersReady = true;
      fadeColor = surface;
      fadeAboveVisible = fadeAbove;
      // No fade above the container (message composer): the surface starts from zero at its top edge.
      int alphaAtContainer = fadeAbove ? ALPHA_AT_CONTAINER : 0;
      fadeAbovePaint.setShader(newEasedFade(surface, 0, alphaAtContainer));
      fadeBelowPaint.setShader(newEasedFade(surface, alphaAtContainer, ALPHA_AT_BOTTOM));
    }
    if (fadeAbove) {
      fadeAboveMatrix.setScale(1f, fade);
      fadeAboveMatrix.postTranslate(0, top - fade);
      fadeAbovePaint.getShader().setLocalMatrix(fadeAboveMatrix);
      c.drawRect(0, top - fade, width, top, fadeAbovePaint);
    }
    final float below = Math.max(1f, getHeight() - top);
    fadeBelowMatrix.setScale(1f, below);
    fadeBelowMatrix.postTranslate(0, top);
    fadeBelowPaint.getShader().setLocalMatrix(fadeBelowMatrix);
    c.drawRect(0, top, width, top + below, fadeBelowPaint);
    drawShadow(c, shape, radius);
  }

  private static final int SMOOTH_FADE_STEPS = 12;

  /**
   * Vertical fade of {@code color} from {@code alphaFrom} at y = 0 to {@code alphaTo} at y = 1 (stretch it with a local
   * matrix). Eased in and out (smoothstep), so the opacity changes without a jump in its rate: a linear ramp shows a
   * visible line where it starts and where its slope changes.
   */
  static Shader newEasedFade (int color, int alphaFrom, int alphaTo) {
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
