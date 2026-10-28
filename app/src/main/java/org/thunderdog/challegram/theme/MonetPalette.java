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

import java.util.Arrays;

/**
 * Material You (Monet) tonal palettes of the device.
 *
 * Palette indexes: 0 = accent1, 1 = accent2, 2 = accent3, 3 = neutral1, 4 = neutral2.
 * Tone indexes follow {@link #TONES}. On devices without dynamic color (Android &lt; 12)
 * a fixed default palette is used.
 */
final class MonetPalette {
  static final int[] TONES = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
  static final String[] NAMES = {"a1", "a2", "a3", "n1", "n2"};

  private static final @ColorInt int[][] DEFAULT = {
      {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEDF0FF, 0xFFD8E2FF, 0xFFADC7FF, 0xFF91ABE3, 0xFF7691C7, 0xFF5D77AC, 0xFF435E91, 0xFF2A4678, 0xFF0F2F60, 0xFF001A41, 0xFF000000},
      {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEDF0FF, 0xFFDBE2F9, 0xFFBFC6DC, 0xFFA3ABC0, 0xFF8991A5, 0xFF6F778B, 0xFF565E71, 0xFF3F4759, 0xFF283041, 0xFF131B2C, 0xFF000000},
      {0xFFFFFFFF, 0xFFFFFBFF, 0xFFFFEBFD, 0xFFFBD7FC, 0xFFDEBCDF, 0xFFC1A1C3, 0xFFA687A8, 0xFF8B6D8D, 0xFF715574, 0xFF583E5B, 0xFF402843, 0xFF29132D, 0xFF000000},
      {0xFFFFFFFF, 0xFFFEFBFF, 0xFFF2F0F4, 0xFFE3E2E6, 0xFFC7C6CA, 0xFFABABAF, 0xFF919094, 0xFF77777A, 0xFF5E5E62, 0xFF46464A, 0xFF303033, 0xFF1B1B1F, 0xFF000000},
      {0xFFFFFFFF, 0xFFFEFBFF, 0xFFEFF0FA, 0xFFE1E2EC, 0xFFC4C6D0, 0xFFA9ABB4, 0xFF8E9099, 0xFF74777F, 0xFF5C5E66, 0xFF44474F, 0xFF2D3038, 0xFF191C22, 0xFF000000},
  };

  private static int[][] current;
  private static int signature;

  private MonetPalette () { }

  /** Re-reads the system palette. Returns true when it differs from the previously loaded one. */
  static synchronized boolean refresh (@Nullable Context context) {
    int[][] palette = load(context);
    int newSignature = Arrays.deepHashCode(palette);
    boolean changed = current != null && newSignature != signature;
    current = palette;
    signature = newSignature;
    return changed;
  }

  static synchronized int[][] get (@Nullable Context context) {
    if (current == null) {
      refresh(context);
    }
    return current;
  }

  private static int[][] load (@Nullable Context context) {
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return DEFAULT;
    }
    try {
      return loadSystem(context.getResources());
    } catch (Throwable ignored) {
      return DEFAULT;
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private static int[][] loadSystem (Resources resources) throws Resources.NotFoundException {
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
}
