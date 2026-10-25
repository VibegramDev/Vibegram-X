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
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in Material You ("Monet") theme.
 *
 * Colors are described by an asset template (assets/monet/*.monet) that refers to the tonal
 * palettes of the device, so the theme follows the system wallpaper colors. Everything the template
 * does not define (and all properties) comes from the statically baked theme with the same id.
 */
public final class ThemeMonet implements ThemeDelegate {
  private static final Pattern MIX = Pattern.compile("^mix\\((.+),(.+),(\\d+)\\)$");
  private static final Pattern ALPHA = Pattern.compile("^[0-9A-Fa-f]{2}$");

  private static Map<String, Integer> colorIdsByName;

  private final ThemeDelegate base;
  private final int[] colors = new int[ThemeColors.COUNT + 1];
  private final boolean[] hasColor = new boolean[ThemeColors.COUNT + 1];

  public ThemeMonet (@NonNull ThemeDelegate base, @NonNull String template, @Nullable Context context) {
    this.base = base;
    final int[][] palette = MonetPalette.get(context);
    final Map<String, String> values = loadTemplate(context, template);
    final Map<String, Integer> ids = colorIds();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      Integer colorId = ids.get(entry.getKey());
      if (colorId == null) {
        Log.w("Unknown color in Monet template %s: %s", template, entry.getKey());
        continue;
      }
      try {
        colors[colorId] = resolve(entry.getValue(), palette);
        hasColor[colorId] = true;
      } catch (IllegalArgumentException e) {
        Log.w("Bad value in Monet template %s: %s: %s", template, entry.getKey(), entry.getValue());
      }
    }
  }

  @Override
  public int getId () {
    return base.getId();
  }

  @Override
  public int getColor (int colorId) {
    if (colorId > 0 && colorId < colors.length && hasColor[colorId]) {
      return colors[colorId];
    }
    return base.getColor(colorId);
  }

  @Override
  public float getProperty (int propertyId) {
    return base.getProperty(propertyId);
  }

  @Override
  public String getDefaultWallpaper () {
    return base.getDefaultWallpaper();
  }

  @Override
  public boolean equals (@Nullable Object obj) {
    return obj instanceof ThemeDelegate && ((ThemeDelegate) obj).getId() == getId();
  }

  @Override
  public int hashCode () {
    return getId();
  }

  // Templates

  private static synchronized Map<String, Integer> colorIds () {
    if (colorIdsByName == null) {
      Map<String, Integer> map = new HashMap<>(ThemeColors.COUNT * 2);
      for (int colorId = 1; colorId <= ThemeColors.COUNT; colorId++) {
        map.put(ThemeColors.getName(colorId), colorId);
      }
      colorIdsByName = map;
    }
    return colorIdsByName;
  }

  private static Map<String, String> loadTemplate (@Nullable Context context, String name) {
    Map<String, String> values = new LinkedHashMap<>();
    if (context == null) {
      return values;
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("monet/" + name + ".monet"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#') {
          continue;
        }
        int i = line.indexOf(':');
        if (i <= 0) {
          continue;
        }
        String key = line.substring(0, i).trim();
        String value = line.substring(i + 1).trim();
        if ("include".equals(key)) {
          values.putAll(loadTemplate(context, value));
        } else {
          values.put(key, value);
        }
      }
    } catch (IOException e) {
      Log.e("Cannot load Monet template %s", e, name);
    }
    return values;
  }

  // Values

  static @ColorInt int resolve (String value, int[][] palette) {
    value = value.trim();
    Matcher mix = MIX.matcher(value);
    if (mix.matches()) {
      int a = resolve(mix.group(1), palette);
      int b = resolve(mix.group(2), palette);
      float p = Integer.parseInt(mix.group(3)) / 100f;
      return Color.argb(
        lerp(Color.alpha(a), Color.alpha(b), p),
        lerp(Color.red(a), Color.red(b), p),
        lerp(Color.green(a), Color.green(b), p),
        lerp(Color.blue(a), Color.blue(b), p)
      );
    }
    if (value.startsWith("#")) {
      return parseLiteral(value.substring(1));
    }
    if (value.length() > 3 && value.charAt(2) == '_') {
      for (int paletteIndex = 0; paletteIndex < MonetPalette.NAMES.length; paletteIndex++) {
        if (!value.startsWith(MonetPalette.NAMES[paletteIndex])) {
          continue;
        }
        String rest = value.substring(3);
        // Longest tone first, so that "1000" is not read as "100" + alpha
        int bestTone = -1;
        for (int toneIndex = 0; toneIndex < MonetPalette.TONES.length; toneIndex++) {
          String tone = Integer.toString(MonetPalette.TONES[toneIndex]);
          if (rest.startsWith(tone)) {
            String alpha = rest.substring(tone.length());
            if (alpha.isEmpty() || ALPHA.matcher(alpha).matches()) {
              if (bestTone == -1 || tone.length() > Integer.toString(MonetPalette.TONES[bestTone]).length()) {
                bestTone = toneIndex;
              }
            }
          }
        }
        if (bestTone != -1) {
          int color = palette[paletteIndex][bestTone];
          String alpha = rest.substring(Integer.toString(MonetPalette.TONES[bestTone]).length());
          return alpha.isEmpty() ? color : (color & 0x00FFFFFF) | (Integer.parseInt(alpha, 16) << 24);
        }
      }
    }
    throw new IllegalArgumentException(value);
  }

  private static int lerp (int a, int b, float p) {
    return Math.round(a + (b - a) * p);
  }

  private static @ColorInt int parseLiteral (String hex) {
    if (hex.length() == 3 || hex.length() == 4) {
      StringBuilder b = new StringBuilder(hex.length() * 2);
      for (int i = 0; i < hex.length(); i++) {
        b.append(hex.charAt(i)).append(hex.charAt(i));
      }
      hex = b.toString();
    }
    if (hex.length() == 6) {
      return 0xFF000000 | Integer.parseInt(hex, 16);
    }
    if (hex.length() == 8) {
      long rgba = Long.parseLong(hex, 16);
      return (int) (((rgba & 0xFF) << 24) | (rgba >> 8));
    }
    throw new IllegalArgumentException(hex);
  }
}
