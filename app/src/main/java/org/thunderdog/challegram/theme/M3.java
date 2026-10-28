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

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.animation.Interpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.tool.UI;

import me.vkryl.core.ColorUtils;

/**
 * Material 3 (Expressive) design tokens.
 *
 * Values follow the official token set (m3.material.io, mirrored in androidx.compose.material3.tokens):
 * color roles, corner shape scale, elevation levels and spring motion schemes.
 *
 * Color roles: for built-in Monet themes on Android 14+ they come straight from the system dynamic color
 * roles (android.R.color.system_*_light/dark), otherwise they are derived from the current theme colors.
 */
public final class M3 {
  private M3 () { }

  // Shape scale (ShapeKeyTokens), dp
  public static final float CORNER_EXTRA_SMALL = 4f;
  public static final float CORNER_SMALL = 8f;
  public static final float CORNER_MEDIUM = 12f;
  public static final float CORNER_LARGE = 16f;
  public static final float CORNER_LARGE_INCREASED = 20f;
  public static final float CORNER_EXTRA_LARGE = 28f;
  public static final float CORNER_EXTRA_LARGE_INCREASED = 32f;
  public static final float CORNER_EXTRA_EXTRA_LARGE = 48f;

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
    SURFACE_CONTAINER_LOWEST = 3,
    SURFACE_CONTAINER_LOW = 4,
    SURFACE_CONTAINER = 5,
    SURFACE_CONTAINER_HIGH = 6,
    SURFACE_CONTAINER_HIGHEST = 7,
    PRIMARY = 8,
    ON_PRIMARY = 9,
    PRIMARY_CONTAINER = 10,
    ON_PRIMARY_CONTAINER = 11,
    SECONDARY = 12,
    SECONDARY_CONTAINER = 13,
    ON_SECONDARY_CONTAINER = 14,
    OUTLINE = 15,
    OUTLINE_VARIANT = 16,
    ERROR = 17,
    ON_ERROR = 18,
    INVERSE_SURFACE = 19,
    INVERSE_ON_SURFACE = 20,
    INVERSE_PRIMARY = 21,
    ROLE_COUNT = 22;

  private static final int[] cache = new int[ROLE_COUNT];
  private static long cacheKey;
  private static boolean hasCache;

  public static @ColorInt int surface () { return get(SURFACE); }
  public static @ColorInt int surfaceContainerLowest () { return get(SURFACE_CONTAINER_LOWEST); }
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
        invalidateSelf();
      }
    }

    @Override
    public void draw (@NonNull Canvas c) {
      paint.setColor(get(role));
      if (topRadius == 0f && bottomRadius == 0f) {
        c.drawRect(getBounds(), paint);
        return;
      }
      rect.set(getBounds());
      radii[0] = radii[1] = radii[2] = radii[3] = topRadius;
      radii[4] = radii[5] = radii[6] = radii[7] = bottomRadius;
      path.reset();
      path.addRoundRect(rect, radii, Path.Direction.CW);
      c.drawPath(path, paint);
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

  public static synchronized @ColorInt int get (int role) {
    // Raw preset value: Theme.fillingColor() is itself mapped to surface (Theme.chromeColor).
    final int filling = ThemeManager.instance().currentTheme().getColor(ColorId.filling);
    final int accent = Theme.getColor(ColorId.iconActive);
    final int text = Theme.textAccentColor();
    final int themeId = ThemeManager.instance().currentThemeId();
    final boolean dark = Theme.isDark();
    long key = ((long) filling * 31 + accent) * 31 + text;
    key = key * 31 + themeId * 2 + (dark ? 1 : 0);
    if (!hasCache || key != cacheKey) {
      compute(themeId, dark, filling, accent, text);
      cacheKey = key;
      hasCache = true;
    }
    return cache[role];
  }

  public static synchronized void invalidate () {
    hasCache = false;
  }

  private static boolean isMonet (int themeId) {
    return themeId == ThemeId.MONET_WHITE || themeId == ThemeId.MONET_DARK || themeId == ThemeId.MONET_BLACK;
  }

  private static void compute (int themeId, boolean dark, int filling, int accent, int text) {
    if (isMonet(themeId) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      try {
        computeSystem(dark, themeId == ThemeId.MONET_BLACK);
        return;
      } catch (Throwable ignored) { }
    }
    computeDerived(dark, filling, accent, text);
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private static void computeSystem (boolean dark, boolean black) {
    Resources r = UI.getContext().getResources();
    if (dark) {
      cache[SURFACE] = c(r, android.R.color.system_surface_dark);
      cache[ON_SURFACE] = c(r, android.R.color.system_on_surface_dark);
      cache[ON_SURFACE_VARIANT] = c(r, android.R.color.system_on_surface_variant_dark);
      cache[SURFACE_CONTAINER_LOWEST] = c(r, android.R.color.system_surface_container_lowest_dark);
      cache[SURFACE_CONTAINER_LOW] = c(r, android.R.color.system_surface_container_low_dark);
      cache[SURFACE_CONTAINER] = c(r, android.R.color.system_surface_container_dark);
      cache[SURFACE_CONTAINER_HIGH] = c(r, android.R.color.system_surface_container_high_dark);
      cache[SURFACE_CONTAINER_HIGHEST] = c(r, android.R.color.system_surface_container_highest_dark);
      cache[PRIMARY] = c(r, android.R.color.system_primary_dark);
      cache[ON_PRIMARY] = c(r, android.R.color.system_on_primary_dark);
      cache[PRIMARY_CONTAINER] = c(r, android.R.color.system_primary_container_dark);
      cache[ON_PRIMARY_CONTAINER] = c(r, android.R.color.system_on_primary_container_dark);
      cache[SECONDARY] = c(r, android.R.color.system_secondary_dark);
      cache[SECONDARY_CONTAINER] = c(r, android.R.color.system_secondary_container_dark);
      cache[ON_SECONDARY_CONTAINER] = c(r, android.R.color.system_on_secondary_container_dark);
      cache[OUTLINE] = c(r, android.R.color.system_outline_dark);
      cache[OUTLINE_VARIANT] = c(r, android.R.color.system_outline_variant_dark);
      cache[ERROR] = c(r, android.R.color.system_error_dark);
      cache[ON_ERROR] = c(r, android.R.color.system_on_error_dark);
      // No public inverse roles: dark inverseSurface = N90 (= onSurface), inverseOnSurface = N20, inversePrimary = A40.
      cache[INVERSE_SURFACE] = c(r, android.R.color.system_on_surface_dark);
      cache[INVERSE_ON_SURFACE] = c(r, android.R.color.system_neutral1_800);
      cache[INVERSE_PRIMARY] = c(r, android.R.color.system_primary_light);
    } else {
      cache[SURFACE] = c(r, android.R.color.system_surface_light);
      cache[ON_SURFACE] = c(r, android.R.color.system_on_surface_light);
      cache[ON_SURFACE_VARIANT] = c(r, android.R.color.system_on_surface_variant_light);
      cache[SURFACE_CONTAINER_LOWEST] = c(r, android.R.color.system_surface_container_lowest_light);
      cache[SURFACE_CONTAINER_LOW] = c(r, android.R.color.system_surface_container_low_light);
      cache[SURFACE_CONTAINER] = c(r, android.R.color.system_surface_container_light);
      cache[SURFACE_CONTAINER_HIGH] = c(r, android.R.color.system_surface_container_high_light);
      cache[SURFACE_CONTAINER_HIGHEST] = c(r, android.R.color.system_surface_container_highest_light);
      cache[PRIMARY] = c(r, android.R.color.system_primary_light);
      cache[ON_PRIMARY] = c(r, android.R.color.system_on_primary_light);
      cache[PRIMARY_CONTAINER] = c(r, android.R.color.system_primary_container_light);
      cache[ON_PRIMARY_CONTAINER] = c(r, android.R.color.system_on_primary_container_light);
      cache[SECONDARY] = c(r, android.R.color.system_secondary_light);
      cache[SECONDARY_CONTAINER] = c(r, android.R.color.system_secondary_container_light);
      cache[ON_SECONDARY_CONTAINER] = c(r, android.R.color.system_on_secondary_container_light);
      cache[OUTLINE] = c(r, android.R.color.system_outline_light);
      cache[OUTLINE_VARIANT] = c(r, android.R.color.system_outline_variant_light);
      cache[ERROR] = c(r, android.R.color.system_error_light);
      cache[ON_ERROR] = c(r, android.R.color.system_on_error_light);
      // Light: inverseSurface = N20, inverseOnSurface = N95, inversePrimary = A80.
      cache[INVERSE_SURFACE] = c(r, android.R.color.system_neutral1_800);
      cache[INVERSE_ON_SURFACE] = c(r, android.R.color.system_neutral1_50);
      cache[INVERSE_PRIMARY] = c(r, android.R.color.system_primary_dark);
    }
    if (black) {
      // AMOLED variant: keep true-black surfaces, containers stay tonal.
      cache[SURFACE] = 0xFF000000;
      cache[SURFACE_CONTAINER_LOWEST] = 0xFF000000;
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static int c (Resources r, int id) {
    return r.getColor(id, null) | 0xFF000000;
  }

  private static void computeDerived (boolean dark, int filling, int accent, int text) {
    int surface = filling | 0xFF000000;
    int onSurface = text | 0xFF000000;
    int onSurfaceVariant = Theme.getColor(ColorId.icon) | 0xFF000000;
    int primary = accent | 0xFF000000;
    cache[SURFACE] = surface;
    cache[ON_SURFACE] = onSurface;
    cache[ON_SURFACE_VARIANT] = onSurfaceVariant;
    // Tonal surface containers: surface tinted towards on-surface with a hint of primary.
    int tint = ColorUtils.fromToArgb(onSurface, primary, .35f);
    float[] steps = dark ? new float[] {-.04f, .04f, .07f, .10f, .14f} : new float[] {.0f, .03f, .05f, .07f, .09f};
    for (int i = 0; i < 5; i++) {
      float step = steps[i];
      cache[SURFACE_CONTAINER_LOWEST + i] = step < 0 ?
        ColorUtils.fromToArgb(surface, 0xFF000000, -step) :
        ColorUtils.fromToArgb(surface, tint, step);
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
    cache[ERROR] = Theme.getColor(ColorId.textNegative) | 0xFF000000;
    cache[ON_ERROR] = contrast(cache[ERROR]);
    cache[INVERSE_SURFACE] = dark ? ColorUtils.fromToArgb(onSurface, surface, .1f) : ColorUtils.fromToArgb(onSurface, surface, .15f);
    cache[INVERSE_ON_SURFACE] = dark ? ColorUtils.fromToArgb(surface, onSurface, .1f) : ColorUtils.fromToArgb(surface, 0xFFFFFFFF, .5f);
    cache[INVERSE_PRIMARY] = dark ? ColorUtils.fromToArgb(primary, 0xFF000000, .4f) : ColorUtils.fromToArgb(primary, 0xFFFFFFFF, .45f);
  }

  private static int contrast (int color) {
    double l = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
    return l > .6 ? 0xFF000000 : 0xFFFFFFFF;
  }

  // Motion: spring schemes (StandardMotionTokens / ExpressiveMotionTokens)

  public static final float SPRING_FAST_SPATIAL_STIFFNESS = 800f, SPRING_FAST_SPATIAL_DAMPING = .6f;
  public static final float SPRING_DEFAULT_SPATIAL_STIFFNESS = 380f, SPRING_DEFAULT_SPATIAL_DAMPING = .8f;
  public static final float SPRING_DEFAULT_EFFECTS_STIFFNESS = 1600f, SPRING_DEFAULT_EFFECTS_DAMPING = 1f;

  /** Expressive "fast spatial" spring: small, snappy elements (indicators, toggles). */
  public static final Spring FAST_SPATIAL = new Spring(SPRING_FAST_SPATIAL_STIFFNESS, SPRING_FAST_SPATIAL_DAMPING);
  /** Expressive "default spatial" spring: medium elements (FAB menu, sheets). */
  public static final Spring DEFAULT_SPATIAL = new Spring(SPRING_DEFAULT_SPATIAL_STIFFNESS, SPRING_DEFAULT_SPATIAL_DAMPING);
  /** "Default effects" spring: color and opacity, no overshoot. */
  public static final Spring DEFAULT_EFFECTS = new Spring(SPRING_DEFAULT_EFFECTS_STIFFNESS, SPRING_DEFAULT_EFFECTS_DAMPING);
  /** Expressive "fast effects" spring: color and opacity of small components, no overshoot. */
  public static final Spring FAST_EFFECTS = new Spring(3800f, 1f);

  // Transitions between screens (m3.material.io/styles/motion/transitions). The site notes that M3 transitions still
  // use the legacy easing and duration system: MDC MaterialSharedAxis / MaterialFadeThrough use motionDurationLong1
  // (450ms) and the emphasized easing; fades follow FadeThroughProvider (outgoing out by 35%, then incoming in).

  /**
   * Forward/backward between screens: shared axis X (Android default: screens slide a short distance while
   * fading). Taps and buttons only; swipe back and predictive back keep following the finger.
   * Top-level destinations (dock sections) use fade-through. false: the original Telegram X slide.
   */
  public static final boolean SHARED_AXIS_NAVIGATION = true;
  /** Slide distance of shared axis X as a fraction of the pane width (user decision: ~10%). */
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
