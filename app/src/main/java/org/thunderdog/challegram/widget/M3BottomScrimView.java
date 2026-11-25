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
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.core.ColorUtils;

/**
 * Shadow of a floating bottom container (dock, message composer). Nothing is dimmed behind the dock (Vibegram X: the
 * owner removed the fade of the official app). Behind the composer the messages are veiled by a flat surface
 * ({@link #COMPOSER_ALPHA}) after a short eased ramp above it ({@link FadeTopProvider}): the owner wants them only a bit
 * hidden there, without the darkening that grows down to the bottom. {@link Fade} is the fade of the official app,
 * still used under the strip of mini app tabs. On Android 12+ both blur the content behind them first
 * ({@link M3Backdrop}, setting «Blur under bars»), the composer veil is then weaker ({@link #COMPOSER_ALPHA_OVER_BLUR}).
 */
public class M3BottomScrimView extends View implements ThemeInvalidateListener, Settings.SettingsChangeListener {
  /** Content behind the container, blurred in its band. Never an ancestor of the scrim (the nodes would draw themselves). */
  public interface BackdropProvider {
    @Nullable M3Backdrop.Source[] getBackdropSources ();
  }

  public interface ShapeProvider {
    /** Fills the bounds of the floating container in the coordinates of this view, false when there is none. */
    boolean getShape (@NonNull M3BottomScrimView view, @NonNull RectF out);
  }

  public interface FadeTopProvider {
    /** Top of the band kept free for the container in the coordinates of this view (where resting content ends). */
    float getFadeTop (@NonNull M3BottomScrimView view, @NonNull RectF shape);
  }

  /** Opacity of the flat surface behind the composer: the messages stay barely visible there. */
  public static final int COMPOSER_ALPHA = 0xCC;

  /** The same over the blur (owner: blur first, a weaker veil over it). */
  public static final int COMPOSER_ALPHA_OVER_BLUR = 0x80;

  /** Factor of the composer veil: weaker over the blur. */
  public static float composerVeilAlpha () {
    return M3Backdrop.isEnabled() ? (float) COMPOSER_ALPHA_OVER_BLUR / COMPOSER_ALPHA : 1f;
  }

  /**
   * Ramp from transparent to {@link #COMPOSER_ALPHA}: it starts a bit above the composer (inside the gap left under the
   * last message at rest, so that message is never veiled) and ends behind it, where only its sides show the ramp.
   */
  private static final float COMPOSER_RAMP_ABOVE_DP = 8f, COMPOSER_RAMP_BELOW_DP = 20f;

  /** Part of the band the fade takes from its top, the surface is flat below (behind the bottom of the container). */
  private static final float FADE_SPREAD = .75f;

  /** Opacity of the surface at the end of the fade and below it (the official app: 0xE8). */
  private static final int ALPHA_MAX = 0xE8;

  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF shape = new RectF();
  private @Nullable ShapeProvider provider;
  private @Nullable FadeTopProvider fadeTopProvider;
  private Fade composerFade;
  private @Nullable BackdropProvider backdropProvider;
  private M3Backdrop.Blur blur;
  private float radius;

  public M3BottomScrimView (Context context) {
    super(context);
  }

  public void setShapeProvider (@Nullable ShapeProvider provider, float radius) {
    this.provider = provider;
    this.radius = radius;
    invalidate();
  }

  /** A flat veil behind the container from the top given by the provider (the composer), none without it (the dock). */
  public void setFadeTopProvider (@Nullable FadeTopProvider fadeTopProvider) {
    this.fadeTopProvider = fadeTopProvider;
    invalidate();
  }

  public void setBackdropProvider (@Nullable BackdropProvider backdropProvider) {
    if (this.backdropProvider != backdropProvider) {
      this.backdropProvider = backdropProvider;
      invalidate();
    }
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    if (M3Backdrop.isSupported()) {
      Settings.instance().addNewSettingsListener(this);
    }
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    if (M3Backdrop.isSupported()) {
      Settings.instance().removeNewSettingsListener(this);
    }
  }

  @Override
  public void onSettingsChanged (long newSettings, long oldSettings) {
    if (((newSettings ^ oldSettings) & Settings.SETTING_FLAG_NO_BACKDROP_BLUR) != 0) {
      invalidate();
    }
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
    // The composer: from the top of the band it keeps free (or a bit above it). The dock: a bit above it, in its band.
    final float rampTop = fadeTopProvider != null ? composerRampTop(fadeTopProvider.getFadeTop(this, shape), shape.top) : Math.max(0f, shape.top - Screen.dp(COMPOSER_RAMP_ABOVE_DP));
    final float rampBottom = composerRampBottom(shape.top);
    if (backdropProvider != null && M3Backdrop.isEnabled()) {
      if (blur == null) {
        blur = new M3Backdrop.Blur();
      }
      blur.draw(c, this, rampTop, rampBottom, getHeight(), backdropProvider.getBackdropSources());
    }
    if (fadeTopProvider != null) {
      if (composerFade == null) {
        composerFade = new Fade(COMPOSER_ALPHA);
      }
      composerFade.drawRamp(c, getWidth(), rampTop, rampBottom, getHeight(), composerVeilAlpha());
    }
    drawShadow(c, shadowPaint, shape, radius, 1f);
  }

  /** Start of the ramp of the composer veil: {@code fadeTop} (the top of the band kept free) or a bit above the composer. */
  public static float composerRampTop (float fadeTop, float containerTop) {
    return Math.min(fadeTop, containerTop - Screen.dp(COMPOSER_RAMP_ABOVE_DP));
  }

  /** End of the ramp of the composer veil, behind the composer. */
  public static float composerRampBottom (float containerTop) {
    return containerTop + Screen.dp(COMPOSER_RAMP_BELOW_DP);
  }

  /** The fade of this view, for views that draw their floating container themselves (dock, strip of mini app tabs). */
  public static final class Fade {
    // An eased gradient of unit height stretched by a local matrix (the band moves on every frame of the keyboard and
    // tab animations, the shader is created once per color).
    private final Paint fadePaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final int alphaMax;
    private int color;
    private boolean shaderReady;

    /** The fade of the official app (mini app tabs). */
    public Fade () {
      this(ALPHA_MAX);
    }

    /** A fade to {@code alphaMax} of the surface. */
    public Fade (int alphaMax) {
      this.alphaMax = alphaMax;
    }

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
      drawRamp(c, width, top, top + (bottom - top) * FADE_SPREAD, Math.max(bottom, drawBottom), alpha);
    }

    /** Transparent at {@code top}, eased to the full fade at {@code rampBottom}, flat from there down to {@code drawBottom}. */
    public void drawRamp (Canvas c, float width, float top, float rampBottom, float drawBottom, float alpha) {
      if (alpha <= 0f || drawBottom <= top || rampBottom <= top) {
        return;
      }
      final int surface = M3.surface();
      if (!shaderReady || color != surface) {
        shaderReady = true;
        color = surface;
        fadePaint.setShader(newEasedFade(surface, 0, alphaMax));
      }
      matrix.setScale(1f, rampBottom - top);
      matrix.postTranslate(0, top);
      fadePaint.getShader().setLocalMatrix(matrix);
      fadePaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
      c.drawRect(0, top, width, drawBottom, fadePaint);
    }
  }

  private static final int SMOOTH_FADE_STEPS = 24;

  /**
   * {@code color} from {@code alphaFrom} at y = 0 to {@code alphaTo} at y = 1, eased in and out (smoothstep): a linear
   * ramp shows a visible line where it starts.
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
