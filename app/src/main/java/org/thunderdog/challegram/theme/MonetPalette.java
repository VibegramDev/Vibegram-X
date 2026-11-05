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

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import java.util.Arrays;

/**
 * Material You (Monet) color scheme of the device: the tonal palettes and the Material 3 color roles.
 *
 * Roles come from the system on Android 14+ (android.R.color.system_*_light/dark, they also follow the contrast
 * setting). Elsewhere, and for roles the system does not publish, they are tones of the tonal palettes as in the
 * Material 3 tonal spot scheme. On devices without dynamic color (Android &lt; 12) a fixed default palette is used.
 */
final class MonetPalette {
  // Palettes
  static final int PRIMARY = 0, SECONDARY = 1, TERTIARY = 2, NEUTRAL = 3, NEUTRAL_VARIANT = 4, ERROR = 5;
  /** Template names of the palettes, in palette order: P40 = tone 40 of the primary palette. */
  static final String[] PALETTE_NAMES = {"P", "S", "T", "N", "NV", "E"};

  /** Tones of the system palettes (android.R.color.system_accent1_0 ... _1000). */
  private static final int[] TONES = {100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0};

  private static final @ColorInt int[][] DEFAULT = {
    {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEDF0FF, 0xFFD8E2FF, 0xFFADC7FF, 0xFF91ABE3, 0xFF7691C7, 0xFF5D77AC, 0xFF435E91, 0xFF2A4678, 0xFF0F2F60, 0xFF001A41, 0xFF000000},
    {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEDF0FF, 0xFFDBE2F9, 0xFFBFC6DC, 0xFFA3ABC0, 0xFF8991A5, 0xFF6F778B, 0xFF565E71, 0xFF3F4759, 0xFF283041, 0xFF131B2C, 0xFF000000},
    {0xFFFFFFFF, 0xFFFFFBFF, 0xFFFFEBFD, 0xFFFBD7FC, 0xFFDEBCDF, 0xFFC1A1C3, 0xFFA687A8, 0xFF8B6D8D, 0xFF715574, 0xFF583E5B, 0xFF402843, 0xFF29132D, 0xFF000000},
    {0xFFFFFFFF, 0xFFFEFBFF, 0xFFF2F0F4, 0xFFE3E2E6, 0xFFC7C6CA, 0xFFABABAF, 0xFF919094, 0xFF77777A, 0xFF5E5E62, 0xFF46464A, 0xFF303033, 0xFF1B1B1F, 0xFF000000},
    {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEFF0FA, 0xFFE1E2EC, 0xFFC4C6D0, 0xFFA9ABB4, 0xFF8E9099, 0xFF74777F, 0xFF5C5E66, 0xFF44474F, 0xFF2D3038, 0xFF191C22, 0xFF000000},
  };

  /** Error palette of Material 3 (not dynamic). */
  private static final @ColorInt int[] ERROR_PALETTE = {0xFFFFFFFF, 0xFFFFFBF9, 0xFFFCEEEE, 0xFFF9DEDC, 0xFFF2B8B5, 0xFFEC928E, 0xFFE46962, 0xFFDC362E, 0xFFB3261E, 0xFF8C1D18, 0xFF601410, 0xFF410E0B, 0xFF000000};

  // Roles: the first M3.ROLE_COUNT follow the M3 role constants, the rest are used by the Monet templates only.
  static final int
    SURFACE_CONTAINER_LOWEST = M3.ROLE_COUNT,
    SURFACE_DIM = M3.ROLE_COUNT + 1,
    SURFACE_BRIGHT = M3.ROLE_COUNT + 2,
    SURFACE_VARIANT = M3.ROLE_COUNT + 3,
    ON_SECONDARY = M3.ROLE_COUNT + 4,
    TERTIARY_ROLE = M3.ROLE_COUNT + 5,
    ON_TERTIARY = M3.ROLE_COUNT + 6,
    TERTIARY_CONTAINER = M3.ROLE_COUNT + 7,
    ON_TERTIARY_CONTAINER = M3.ROLE_COUNT + 8,
    ERROR_CONTAINER = M3.ROLE_COUNT + 9,
    ON_ERROR_CONTAINER = M3.ROLE_COUNT + 10,
    SCRIM = M3.ROLE_COUNT + 11,
    ROLE_COUNT = M3.ROLE_COUNT + 12;

  /** Template names of the roles, by role index. */
  static final String[] ROLE_NAMES = new String[ROLE_COUNT];
  /** Palette, light tone and dark tone of each role (Material 3 tonal spot scheme, standard contrast). */
  private static final int[][] ROLE_TONES = new int[ROLE_COUNT][];

  private static void role (int role, String name, int palette, int lightTone, int darkTone) {
    ROLE_NAMES[role] = name;
    ROLE_TONES[role] = new int[] {palette, lightTone, darkTone};
  }

  static {
    role(M3.SURFACE, "surface", NEUTRAL, 98, 6);
    role(M3.ON_SURFACE, "onSurface", NEUTRAL, 10, 90);
    role(M3.ON_SURFACE_VARIANT, "onSurfaceVariant", NEUTRAL_VARIANT, 30, 80);
    role(M3.SURFACE_CONTAINER_LOW, "surfaceContainerLow", NEUTRAL, 96, 10);
    role(M3.SURFACE_CONTAINER, "surfaceContainer", NEUTRAL, 94, 12);
    role(M3.SURFACE_CONTAINER_HIGH, "surfaceContainerHigh", NEUTRAL, 92, 17);
    role(M3.SURFACE_CONTAINER_HIGHEST, "surfaceContainerHighest", NEUTRAL, 90, 22);
    role(M3.PRIMARY, "primary", PRIMARY, 40, 80);
    role(M3.ON_PRIMARY, "onPrimary", PRIMARY, 100, 20);
    role(M3.PRIMARY_CONTAINER, "primaryContainer", PRIMARY, 90, 30);
    role(M3.ON_PRIMARY_CONTAINER, "onPrimaryContainer", PRIMARY, 30, 90);
    role(M3.SECONDARY, "secondary", SECONDARY, 40, 80);
    role(M3.SECONDARY_CONTAINER, "secondaryContainer", SECONDARY, 90, 30);
    role(M3.ON_SECONDARY_CONTAINER, "onSecondaryContainer", SECONDARY, 30, 90);
    role(M3.OUTLINE, "outline", NEUTRAL_VARIANT, 50, 60);
    role(M3.OUTLINE_VARIANT, "outlineVariant", NEUTRAL_VARIANT, 80, 30);
    role(M3.ERROR, "error", ERROR, 40, 80);
    role(M3.ON_ERROR, "onError", ERROR, 100, 20);
    role(M3.INVERSE_SURFACE, "inverseSurface", NEUTRAL, 20, 90);
    role(M3.INVERSE_ON_SURFACE, "inverseOnSurface", NEUTRAL, 95, 20);
    role(M3.INVERSE_PRIMARY, "inversePrimary", PRIMARY, 80, 40);
    role(SURFACE_CONTAINER_LOWEST, "surfaceContainerLowest", NEUTRAL, 100, 4);
    role(SURFACE_DIM, "surfaceDim", NEUTRAL, 87, 6);
    role(SURFACE_BRIGHT, "surfaceBright", NEUTRAL, 98, 24);
    role(SURFACE_VARIANT, "surfaceVariant", NEUTRAL_VARIANT, 90, 30);
    role(ON_SECONDARY, "onSecondary", SECONDARY, 100, 20);
    role(TERTIARY_ROLE, "tertiary", TERTIARY, 40, 80);
    role(ON_TERTIARY, "onTertiary", TERTIARY, 100, 20);
    role(TERTIARY_CONTAINER, "tertiaryContainer", TERTIARY, 90, 30);
    role(ON_TERTIARY_CONTAINER, "onTertiaryContainer", TERTIARY, 30, 90);
    role(ERROR_CONTAINER, "errorContainer", ERROR, 90, 30);
    role(ON_ERROR_CONTAINER, "onErrorContainer", ERROR, 30, 90);
    role(SCRIM, "scrim", NEUTRAL, 0, 0);
  }

  private static int[][] palette;
  /** System roles: [0] light, [1] dark; 0 = not published by the system. Null below Android 14. */
  private static int[][] systemRoles;
  /** Resolved schemes: light, dark, black. */
  private static final int[][] schemes = new int[3][];
  private static int signature;

  private MonetPalette () { }

  /** Re-reads the system palette and roles. Returns true when they differ from the previously loaded ones. */
  static synchronized boolean refresh (@Nullable Context context) {
    int[][] newPalette = loadPalette(context);
    int[][] newRoles = loadSystemRoles(context);
    int newSignature = 31 * Arrays.deepHashCode(newPalette) + Arrays.deepHashCode(newRoles);
    boolean changed = palette != null && newSignature != signature;
    palette = newPalette;
    systemRoles = newRoles;
    signature = newSignature;
    Arrays.fill(schemes, null);
    return changed;
  }

  static synchronized int[][] palette (@Nullable Context context) {
    if (palette == null) {
      refresh(context);
    }
    return palette;
  }

  /** All roles ({@link #ROLE_COUNT}) of the light, dark or true-black (AMOLED) scheme. Do not modify. */
  static synchronized int[] scheme (@Nullable Context context, boolean dark, boolean black) {
    int[][] palette = palette(context);
    int index = black ? 2 : dark ? 1 : 0;
    int[] scheme = schemes[index];
    if (scheme == null) {
      scheme = new int[ROLE_COUNT];
      for (int role = 0; role < ROLE_COUNT; role++) {
        int systemColor = systemRoles != null ? systemRoles[dark ? 1 : 0][role] : 0;
        int[] tones = ROLE_TONES[role];
        scheme[role] = systemColor != 0 ? systemColor : tone(palette, tones[0], tones[dark ? 2 : 1]);
      }
      if (black) {
        // Containers stay tonal, the page is true black.
        scheme[M3.SURFACE] = 0xFF000000;
      }
      schemes[index] = scheme;
    }
    return scheme;
  }

  /**
   * Tone 0..100 of a palette. Tones between the published ones are interpolated in CIELAB: tone is L*, and a tonal
   * palette keeps hue and chroma, so a* and b* change little between neighbours.
   */
  static @ColorInt int tone (int[][] palette, int paletteIndex, int tone) {
    int[] colors = paletteIndex == ERROR ? ERROR_PALETTE : palette[paletteIndex];
    for (int i = 0; i < TONES.length; i++) {
      if (TONES[i] == tone) {
        return colors[i];
      }
      if (TONES[i] < tone) {
        double[] a = new double[3], b = new double[3];
        ColorUtils.colorToLAB(colors[i - 1], a);
        ColorUtils.colorToLAB(colors[i], b);
        double p = (double) (TONES[i - 1] - tone) / (TONES[i - 1] - TONES[i]);
        return ColorUtils.LABToColor(tone, a[1] + (b[1] - a[1]) * p, a[2] + (b[2] - a[2]) * p);
      }
    }
    throw new IllegalArgumentException("tone == " + tone);
  }

  private static int[][] loadPalette (@Nullable Context context) {
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return DEFAULT;
    }
    try {
      return loadSystemPalette(context.getResources());
    } catch (Throwable ignored) {
      return DEFAULT;
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private static int[][] loadSystemPalette (Resources resources) throws Resources.NotFoundException {
    final int[][] ids = {
      {android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900, android.R.color.system_accent1_1000},
      {android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50, android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300, android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900, android.R.color.system_accent2_1000},
      {android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50, android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300, android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900, android.R.color.system_accent3_1000},
      {android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900, android.R.color.system_neutral1_1000},
      {android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900, android.R.color.system_neutral2_1000},
    };
    final int[][] palette = new int[ids.length][ids[0].length];
    for (int i = 0; i < ids.length; i++) {
      for (int j = 0; j < ids[i].length; j++) {
        palette[i][j] = resources.getColor(ids[i][j], null) | 0xFF000000;
      }
    }
    return palette;
  }

  private static @Nullable int[][] loadSystemRoles (@Nullable Context context) {
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return null;
    }
    try {
      return loadSystemRoles(context.getResources());
    } catch (Throwable ignored) {
      return null;
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private static int[][] loadSystemRoles (Resources resources) throws Resources.NotFoundException {
    // {role, light, dark}; inverse roles, scrim and the true-black surface are not published and come from tones.
    final int[][] ids = {
      {M3.SURFACE, android.R.color.system_surface_light, android.R.color.system_surface_dark},
      {M3.ON_SURFACE, android.R.color.system_on_surface_light, android.R.color.system_on_surface_dark},
      {M3.ON_SURFACE_VARIANT, android.R.color.system_on_surface_variant_light, android.R.color.system_on_surface_variant_dark},
      {M3.SURFACE_CONTAINER_LOW, android.R.color.system_surface_container_low_light, android.R.color.system_surface_container_low_dark},
      {M3.SURFACE_CONTAINER, android.R.color.system_surface_container_light, android.R.color.system_surface_container_dark},
      {M3.SURFACE_CONTAINER_HIGH, android.R.color.system_surface_container_high_light, android.R.color.system_surface_container_high_dark},
      {M3.SURFACE_CONTAINER_HIGHEST, android.R.color.system_surface_container_highest_light, android.R.color.system_surface_container_highest_dark},
      {M3.PRIMARY, android.R.color.system_primary_light, android.R.color.system_primary_dark},
      {M3.ON_PRIMARY, android.R.color.system_on_primary_light, android.R.color.system_on_primary_dark},
      {M3.PRIMARY_CONTAINER, android.R.color.system_primary_container_light, android.R.color.system_primary_container_dark},
      {M3.ON_PRIMARY_CONTAINER, android.R.color.system_on_primary_container_light, android.R.color.system_on_primary_container_dark},
      {M3.SECONDARY, android.R.color.system_secondary_light, android.R.color.system_secondary_dark},
      {M3.SECONDARY_CONTAINER, android.R.color.system_secondary_container_light, android.R.color.system_secondary_container_dark},
      {M3.ON_SECONDARY_CONTAINER, android.R.color.system_on_secondary_container_light, android.R.color.system_on_secondary_container_dark},
      {M3.OUTLINE, android.R.color.system_outline_light, android.R.color.system_outline_dark},
      {M3.OUTLINE_VARIANT, android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark},
      {M3.ERROR, android.R.color.system_error_light, android.R.color.system_error_dark},
      {M3.ON_ERROR, android.R.color.system_on_error_light, android.R.color.system_on_error_dark},
      {SURFACE_CONTAINER_LOWEST, android.R.color.system_surface_container_lowest_light, android.R.color.system_surface_container_lowest_dark},
      {SURFACE_DIM, android.R.color.system_surface_dim_light, android.R.color.system_surface_dim_dark},
      {SURFACE_BRIGHT, android.R.color.system_surface_bright_light, android.R.color.system_surface_bright_dark},
      {SURFACE_VARIANT, android.R.color.system_surface_variant_light, android.R.color.system_surface_variant_dark},
      {ON_SECONDARY, android.R.color.system_on_secondary_light, android.R.color.system_on_secondary_dark},
      {TERTIARY_ROLE, android.R.color.system_tertiary_light, android.R.color.system_tertiary_dark},
      {ON_TERTIARY, android.R.color.system_on_tertiary_light, android.R.color.system_on_tertiary_dark},
      {TERTIARY_CONTAINER, android.R.color.system_tertiary_container_light, android.R.color.system_tertiary_container_dark},
      {ON_TERTIARY_CONTAINER, android.R.color.system_on_tertiary_container_light, android.R.color.system_on_tertiary_container_dark},
      {ERROR_CONTAINER, android.R.color.system_error_container_light, android.R.color.system_error_container_dark},
      {ON_ERROR_CONTAINER, android.R.color.system_on_error_container_light, android.R.color.system_on_error_container_dark},
    };
    final int[][] roles = new int[2][ROLE_COUNT];
    for (int[] id : ids) {
      roles[0][id[0]] = resources.getColor(id[1], null) | 0xFF000000;
      roles[1][id[0]] = resources.getColor(id[2], null) | 0xFF000000;
    }
    return roles;
  }
}
