/*
 * This file is a part of Vibegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.theme;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.Interpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.tool.UI;

import java.util.Arrays;

import me.vkryl.core.ColorUtils;

/**
 * Material 3 (Expressive) design tokens.
 *
 * Values follow the official token set (m3.material.io, mirrored in androidx.compose.material3.tokens):
 * color roles, corner shape scale, elevation levels and spring motion schemes.
 *
 * Color roles are part of every theme ({@link ThemeDelegate#getRoleColor}, stored in {@link Roles}): for built-in
 * Monet themes they are the device scheme ({@link MonetPalette}: system dynamic color roles on Android 14+, tonal
 * palette tones elsewhere), otherwise they are derived from the theme's own colors.
 * The accessors below read the roles of the current theme.
 */
public final class M3 {
  private M3 () { }

  // Shape scale (ShapeKeyTokens), dp
  public static final float CORNER_EXTRA_SMALL = 4f;
  public static final float CORNER_SMALL = 8f;
  public static final float CORNER_MEDIUM = 12f;
  public static final float CORNER_LARGE = 16f;
  public static final float CORNER_EXTRA_LARGE = 28f;

  // Elevation levels (ElevationTokens), dp
  public static final float ELEVATION_LEVEL1 = 1f;
  public static final float ELEVATION_LEVEL2 = 3f;
  public static final float ELEVATION_LEVEL3 = 6f;
  public static final float ELEVATION_LEVEL4 = 8f;

  // State layer opacities (StateTokens)
  public static final float STATE_PRESSED = .10f;
  public static final float STATE_DRAGGED = .16f;

  // Roles
  public static final int
    SURFACE = 0,
    ON_SURFACE = 1,
    ON_SURFACE_VARIANT = 2,
    SURFACE_CONTAINER_LOW = 3,
    SURFACE_CONTAINER = 4,
    SURFACE_CONTAINER_HIGH = 5,
    SURFACE_CONTAINER_HIGHEST = 6,
    PRIMARY = 7,
    ON_PRIMARY = 8,
    PRIMARY_CONTAINER = 9,
    ON_PRIMARY_CONTAINER = 10,
    SECONDARY = 11,
    SECONDARY_CONTAINER = 12,
    ON_SECONDARY_CONTAINER = 13,
    OUTLINE = 14,
    OUTLINE_VARIANT = 15,
    ERROR = 16,
    ON_ERROR = 17,
    INVERSE_SURFACE = 18,
    INVERSE_ON_SURFACE = 19,
    INVERSE_PRIMARY = 20,
    ROLE_COUNT = 21;

  public static @ColorInt int surface () { return get(SURFACE); }
  public static @ColorInt int onSurface () { return get(ON_SURFACE); }
  public static @ColorInt int onSurfaceVariant () { return get(ON_SURFACE_VARIANT); }
  public static @ColorInt int surfaceContainerLow () { return get(SURFACE_CONTAINER_LOW); }
  public static @ColorInt int surfaceContainer () { return get(SURFACE_CONTAINER); }
  public static @ColorInt int surfaceContainerHigh () { return get(SURFACE_CONTAINER_HIGH); }
  public static @ColorInt int surfaceContainerHighest () { return get(SURFACE_CONTAINER_HIGHEST); }
  public static @ColorInt int primary () { return get(PRIMARY); }
  public static @ColorInt int onPrimary () { return get(ON_PRIMARY); }
  public static @ColorInt int primaryContainer () { return get(PRIMARY_CONTAINER); }
  public static @ColorInt int onPrimaryContainer () { return get(ON_PRIMARY_CONTAINER); }
  public static @ColorInt int secondary () { return get(SECONDARY); }
  public static @ColorInt int secondaryContainer () { return get(SECONDARY_CONTAINER); }
  public static @ColorInt int onSecondaryContainer () { return get(ON_SECONDARY_CONTAINER); }
  public static @ColorInt int outline () { return get(OUTLINE); }
  public static @ColorInt int outlineVariant () { return get(OUTLINE_VARIANT); }
  public static @ColorInt int inverseSurface () { return get(INVERSE_SURFACE); }
  public static @ColorInt int inverseOnSurface () { return get(INVERSE_ON_SURFACE); }
  public static @ColorInt int inversePrimary () { return get(INVERSE_PRIMARY); }
  public static @ColorInt int error () { return get(ERROR); }
  public static @ColorInt int onError () { return get(ON_ERROR); }

  /**
   * Background that paints a role of the current scheme at draw time (no theme ColorId exists for M3 roles).
   * The owner must invalidate the view on theme changes ({@code ViewController.addThemeInvalidateListener}).
   */
  public static Drawable roleDrawable (int role) {
    return new Drawable() {
      @Override
      public void draw (@NonNull Canvas c) {
        c.drawColor(get(role));
      }

      @Override
      public void setAlpha (int alpha) { }

      @Override
      public void setColorFilter (@Nullable ColorFilter colorFilter) { }

      @Override
      public int getOpacity () {
        return PixelFormat.OPAQUE;
      }
    };
  }

  /**
   * Rounded background that paints a role of the current scheme at draw time, with separate radii for the top and
   * the bottom corners. The owner must invalidate the view on theme changes.
   */
  public static final class RoleShape extends Drawable {
    private final int role;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private float topRadius, bottomRadius;

    public RoleShape (int role) {
      this.role = role;
    }

    public void setRadii (float topRadius, float bottomRadius) {
      if (this.topRadius != topRadius || this.bottomRadius != bottomRadius) {
        this.topRadius = topRadius;
        this.bottomRadius = bottomRadius;
        buildPath();
        invalidateSelf();
      }
    }

    @Override
    protected void onBoundsChange (@NonNull Rect bounds) {
      rect.set(bounds);
      buildPath();
    }

    private void buildPath () {
      path.reset();
      if (topRadius != bottomRadius) {
        radii[0] = radii[1] = radii[2] = radii[3] = topRadius;
        radii[4] = radii[5] = radii[6] = radii[7] = bottomRadius;
        path.addRoundRect(rect, radii, Path.Direction.CW);
      }
    }

    @Override
    public void draw (@NonNull Canvas c) {
      paint.setColor(get(role));
      if (topRadius != bottomRadius) {
        c.drawPath(path, paint);
      } else if (topRadius != 0f) {
        c.drawRoundRect(rect, topRadius, topRadius, paint);
      } else {
        c.drawRect(rect, paint);
      }
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Role of the current theme. */
  public static @ColorInt int get (int role) {
    return ThemeManager.instance().currentTheme().getRoleColor(role);
  }

  /** Role of {@code forcedTheme} (e.g. the media viewer's dark theme), or of the current theme. */
  public static @ColorInt int get (int role, @Nullable ThemeDelegate forcedTheme) {
    return forcedTheme != null ? forcedTheme.getRoleColor(role) : get(role);
  }

  /**
   * Color roles of one theme, stored in the theme itself. Computed once, on the first read: for Monet themes from the
   * device scheme ({@link MonetPalette#scheme}: system roles on Android 14+, palette tones elsewhere), for the other
   * themes from the theme's raw colors ({@link ThemeDelegate#getRawColor}, never from the role-mapped ones), so surface
   * derived from filling cannot feed back into itself. Themes call {@link #invalidate} when their colors change (theme
   * editor); Monet themes are rebuilt when the system scheme changes ({@link ThemeManager#checkMonetPalette}).
   */
  public static final class Roles {
    private final boolean system;
    private volatile int[] values;

    /** @param system Monet themes: Material 3 scheme of the device. */
    public Roles (boolean system) {
      this.system = system;
    }

    public @ColorInt int get (ThemeDelegate theme, int role) {
      int[] values = this.values;
      if (values == null) {
        values = compute(theme, system);
        this.values = values;
      }
      return values[role];
    }

    public void invalidate () {
      values = null;
    }
  }

  private static int[] compute (ThemeDelegate theme, boolean system) {
    if (system) {
      return Arrays.copyOf(MonetPalette.scheme(UI.getContext(), theme.isDark(), theme.getId() == ThemeId.MONET_BLACK), ROLE_COUNT);
    }
    final int[] cache = new int[ROLE_COUNT];
    computeDerived(cache, theme.isDark(), theme);
    return cache;
  }

  private static void computeDerived (int[] cache, boolean dark, ThemeDelegate theme) {
    int surface = theme.getRawColor(ColorId.filling) | 0xFF000000;
    int onSurface = theme.getRawColor(ColorId.text) | 0xFF000000;
    int onSurfaceVariant = theme.getRawColor(ColorId.icon) | 0xFF000000;
    int primary = theme.getRawColor(ColorId.iconActive) | 0xFF000000;
    cache[SURFACE] = surface;
    cache[ON_SURFACE] = onSurface;
    cache[ON_SURFACE_VARIANT] = onSurfaceVariant;
    // Tonal surface containers: surface tinted towards on-surface with a hint of primary.
    int tint = ColorUtils.fromToArgb(onSurface, primary, .35f);
    float[] steps = dark ? new float[] {.04f, .07f, .10f, .14f} : new float[] {.03f, .05f, .07f, .09f};
    for (int i = 0; i < steps.length; i++) {
      cache[SURFACE_CONTAINER_LOW + i] = ColorUtils.fromToArgb(surface, tint, steps[i]);
    }
    cache[PRIMARY] = primary;
    cache[ON_PRIMARY] = contrast(primary);
    cache[PRIMARY_CONTAINER] = dark ? ColorUtils.fromToArgb(primary, 0xFF000000, .55f) : ColorUtils.fromToArgb(primary, 0xFFFFFFFF, .75f);
    cache[ON_PRIMARY_CONTAINER] = dark ? ColorUtils.fromToArgb(primary, 0xFFFFFFFF, .75f) : ColorUtils.fromToArgb(primary, 0xFF000000, .65f);
    cache[SECONDARY] = ColorUtils.fromToArgb(onSurfaceVariant, primary, .6f);
    cache[SECONDARY_CONTAINER] = ColorUtils.fromToArgb(surface, primary, dark ? .28f : .18f);
    cache[ON_SECONDARY_CONTAINER] = dark ? ColorUtils.fromToArgb(primary, 0xFFFFFFFF, .8f) : ColorUtils.fromToArgb(primary, 0xFF000000, .7f);
    cache[OUTLINE] = ColorUtils.fromToArgb(surface, onSurface, .5f);
    cache[OUTLINE_VARIANT] = ColorUtils.fromToArgb(surface, onSurface, .2f);
    cache[ERROR] = theme.getRawColor(ColorId.textNegative) | 0xFF000000;
    cache[ON_ERROR] = contrast(cache[ERROR]);
    cache[INVERSE_SURFACE] = dark ? ColorUtils.fromToArgb(onSurface, surface, .1f) : ColorUtils.fromToArgb(onSurface, surface, .15f);
    cache[INVERSE_ON_SURFACE] = dark ? ColorUtils.fromToArgb(surface, onSurface, .1f) : ColorUtils.fromToArgb(surface, 0xFFFFFFFF, .5f);
    cache[INVERSE_PRIMARY] = dark ? ColorUtils.fromToArgb(primary, 0xFF000000, .4f) : ColorUtils.fromToArgb(primary, 0xFFFFFFFF, .45f);
  }

  /** Black or white, whichever reads better on {@code color}. */
  public static int contrast (int color) {
    double l = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
    return l > .6 ? 0xFF000000 : 0xFFFFFFFF;
  }

  // Motion: spring schemes (StandardMotionTokens / ExpressiveMotionTokens)

  /** Expressive "fast spatial" spring: small, snappy elements (indicators, toggles). */
  public static final Spring FAST_SPATIAL = new Spring(800f, .6f);
  /** Expressive "fast effects" spring: color and opacity of small components, no overshoot. */
  public static final Spring FAST_EFFECTS = new Spring(3800f, 1f);

  // Transitions between screens (m3.material.io/styles/motion/transitions). The site notes that M3 transitions still
  // use the legacy easing and duration system: MDC MaterialSharedAxis / MaterialFadeThrough use motionDurationLong1
  // (450ms) and the emphasized easing; fades follow FadeThroughProvider (outgoing out by 35%, then incoming in).

  /**
   * Forward/backward between screens: shared axis X (Android default: screens slide a short distance while
   * fading). Taps and buttons only; swipe back and predictive back keep following the finger.
   * Top-level destinations (dock sections) use fade-through.
   * Slide distance of shared axis X as a fraction of the pane width (user decision: ~10%).
   */
  public static final float SHARED_AXIS_DISTANCE = .1f;
  public static final long TRANSITION_DURATION = 450L;
  /** FadeThroughProvider.FADE_THROUGH_THRESHOLD. */
  public static final float FADE_THROUGH_THRESHOLD = .35f;

  /** Alpha of the outgoing screen at transition progress {@code p} ("Fully fade out content before fading new content in"). */
  public static float fadeThroughOut (float p) {
    return 1f - Math.max(0f, Math.min(1f, p / FADE_THROUGH_THRESHOLD));
  }

  /** MaterialFadeThrough DEFAULT_START_SCALE: the incoming screen scales up from 92% over the whole transition. */
  public static float fadeThroughScale (float p) {
    return .92f + .08f * Math.max(0f, Math.min(1f, p));
  }

  /** Alpha of the incoming screen at transition progress {@code p}. */
  public static float fadeThroughIn (float p) {
    return Math.max(0f, Math.min(1f, (p - FADE_THROUGH_THRESHOLD) / (1f - FADE_THROUGH_THRESHOLD)));
  }

  /**
   * Emphasized easing (MotionTokens.EasingEmphasized): path M0,0 C.05,0 .133333,.06 .166666,.4
   * C.208333,.82 .25,1 1,1. Evaluated directly (PathInterpolator is API 21).
   */
  public static final Interpolator EMPHASIZED = new Interpolator() {
    private final float[][] segments = {
      {0f, 0f, .05f, 0f, .133333f, .06f, .166666f, .4f},
      {.166666f, .4f, .208333f, .82f, .25f, 1f, 1f, 1f}
    };

    private float bezier (float a, float b, float c, float d, float t) {
      float u = 1f - t;
      return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d;
    }

    @Override
    public float getInterpolation (float x) {
      if (x <= 0f) return 0f;
      if (x >= 1f) return 1f;
      float[] s = x < segments[1][0] ? segments[0] : segments[1];
      // x(t) is monotonic on each segment: bisection.
      float lo = 0f, hi = 1f, t = .5f;
      for (int i = 0; i < 24; i++) {
        t = (lo + hi) / 2f;
        if (bezier(s[0], s[2], s[4], s[6], t) < x) {
          lo = t;
        } else {
          hi = t;
        }
      }
      return bezier(s[1], s[3], s[5], s[7], t);
    }
  };

  /**
   * Damped spring (unit mass) expressed as a time interpolator, so it can drive the existing
   * duration-based animators. {@link #duration} is the time until the motion visually settles.
   */
  public static final class Spring implements Interpolator {
    private final double omega, zeta;
    public final long duration;

    Spring (float stiffness, float dampingRatio) {
      this.omega = Math.sqrt(stiffness);
      this.zeta = dampingRatio;
      // Settle when the envelope e^(-zeta*omega*t) drops below 0.2% (critically damped needs a bit longer).
      double settle = Math.log(500) / (Math.max(zeta, .1) * omega);
      if (zeta >= 1.0) {
        settle *= 1.35;
      }
      this.duration = Math.max(120L, Math.round(settle * 1000.0));
    }

    @Override
    public float getInterpolation (float input) {
      if (input <= 0f) return 0f;
      if (input >= 1f) return 1f;
      double t = input * (duration / 1000.0);
      double value;
      if (zeta < 1.0) {
        double wd = omega * Math.sqrt(1 - zeta * zeta);
        value = 1 - Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t));
      } else {
        value = 1 - Math.exp(-omega * t) * (1 + omega * t);
      }
      return (float) value;
    }
  }
}
