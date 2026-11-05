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
import android.graphics.Color;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.unsorted.AppContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * Templates are parsed once per process; a palette change only re-resolves the parsed values.
 */
public final class ThemeMonet implements ThemeDelegate {
  private static final Pattern MIX = Pattern.compile("^mix\\((.+),(.+),(\\d+)\\)$");
  /** sys(role,fallback): Android 14+ system color role (android.R.color.system_<role>), fallback for older systems. */
  private static final Pattern SYS = Pattern.compile("^sys\\((\\w+),(.+)\\)$");
  private static final Pattern ALPHA = Pattern.compile("^[0-9A-Fa-f]{2}$");

  private static final Map<String, Template> templates = new HashMap<>();

  private final ThemeDelegate base;
  private final int[] colors = new int[ThemeColors.COUNT + 1];
  private final boolean[] hasColor = new boolean[ThemeColors.COUNT + 1];

  public ThemeMonet (@NonNull ThemeDelegate base, @NonNull String template, @Nullable Context context) {
    this.base = base;
    if (context == null) {
      return;
    }
    final int[][] palette = MonetPalette.get(context);
    final Template parsed = template(context, template);
    for (int i = 0; i < parsed.colorIds.length; i++) {
      int colorId = parsed.colorIds[i];
      colors[colorId] = parsed.values[i].resolve(palette);
      hasColor[colorId] = true;
    }
  }

  @Override
  public int getId () {
    return base.getId();
  }

  @Override
  public int getRawColor (int colorId) {
    if (colorId > 0 && colorId < colors.length && hasColor[colorId]) {
      return colors[colorId];
    }
    return base.getRawColor(colorId);
  }

  /** System dynamic color roles on Android 14+, see {@link M3.Roles}. */
  private final M3.Roles roles = new M3.Roles(true);

  @Override
  public int getRoleColor (int role) {
    return roles.get(this, role);
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

  private interface Value {
    @ColorInt int resolve (int[][] palette);
  }

  private static final class Template {
    final int[] colorIds;
    final Value[] values;

    Template (int[] colorIds, Value[] values) {
      this.colorIds = colorIds;
      this.values = values;
    }
  }

  private static synchronized Template template (@NonNull Context context, String name) {
    Template template = templates.get(name);
    if (template != null) {
      return template;
    }
    final Map<String, String> source = new LinkedHashMap<>();
    loadTemplate(context, name, source);
    final Map<String, Integer> ids = ThemeColors.getMap();
    final int[] colorIds = new int[source.size()];
    final Value[] values = new Value[source.size()];
    int count = 0;
    for (Map.Entry<String, String> entry : source.entrySet()) {
      Integer colorId = ids.get(entry.getKey());
      if (colorId == null) {
        Log.w("Unknown color in Monet template %s: %s", name, entry.getKey());
        continue;
      }
      try {
        values[count] = parse(entry.getValue());
        colorIds[count] = colorId;
        count++;
      } catch (IllegalArgumentException e) {
        Log.w("Bad value in Monet template %s: %s: %s", name, entry.getKey(), entry.getValue());
      }
    }
    template = new Template(Arrays.copyOf(colorIds, count), Arrays.copyOf(values, count));
    templates.put(name, template);
    return template;
  }

  private static void loadTemplate (@NonNull Context context, String name, Map<String, String> out) {
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
          loadTemplate(context, value, out);
        } else {
          out.put(key, value);
        }
      }
    } catch (IOException e) {
      Log.e("Cannot load Monet template %s", e, name);
    }
  }

  // Values

  private static Value parse (String value) {
    value = value.trim();
    Matcher sys = SYS.matcher(value);
    if (sys.matches()) {
      final int resId = systemRoleId(sys.group(1));
      final Value fallback = parse(sys.group(2));
      if (resId == 0) {
        return fallback;
      }
      return palette -> {
        int color = systemColor(resId);
        return color != 0 ? color : fallback.resolve(palette);
      };
    }
    Matcher mix = MIX.matcher(value);
    if (mix.matches()) {
      final Value a = parse(mix.group(1));
      final Value b = parse(mix.group(2));
      final float p = Integer.parseInt(mix.group(3)) / 100f;
      return palette -> mix(a.resolve(palette), b.resolve(palette), p);
    }
    if (value.startsWith("#")) {
      final int color = parseLiteral(value.substring(1));
      return palette -> color;
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
          final int p = paletteIndex, t = bestTone;
          final String alpha = rest.substring(Integer.toString(MonetPalette.TONES[bestTone]).length());
          if (alpha.isEmpty()) {
            return palette -> palette[p][t];
          }
          final int alphaMask = Integer.parseInt(alpha, 16) << 24;
          return palette -> (palette[p][t] & 0x00FFFFFF) | alphaMask;
        }
      }
    }
    throw new IllegalArgumentException(value);
  }

  private static @ColorInt int mix (int a, int b, float p) {
    return Color.argb(
      lerp(Color.alpha(a), Color.alpha(b), p),
      lerp(Color.red(a), Color.red(b), p),
      lerp(Color.green(a), Color.green(b), p),
      lerp(Color.blue(a), Color.blue(b), p)
    );
  }

  private static int lerp (int a, int b, float p) {
    return Math.round(a + (b - a) * p);
  }

  /** Resource id of a Material 3 dynamic color role of the system (Android 14+), or 0 when unavailable. */
  private static int systemRoleId (String role) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return 0;
    }
    return AppContext.get().getResources().getIdentifier("system_" + role, "color", "android");
  }

  private static @ColorInt int systemColor (int resId) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return 0;
    }
    try {
      return AppContext.get().getResources().getColor(resId, null) | 0xFF000000;
    } catch (Resources.NotFoundException e) {
      return 0;
    }
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
