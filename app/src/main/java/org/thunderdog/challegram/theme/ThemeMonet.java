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
import org.thunderdog.challegram.core.Background;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in Material You ("Monet") theme.
 *
 * Colors are described by asset templates (assets/monet/*.monet) in terms of Material 3 color roles and tonal palette
 * tones of the device ({@link MonetPalette}), so the theme follows the system wallpaper colors. Everything a template
 * does not define (and all properties) comes from the statically baked theme with the same id.
 *
 * Templates are read and parsed once per process, off the UI thread when {@link #preload} runs early enough; a theme
 * built before that waits for the same single load. A palette change only re-resolves the parsed values.
 */
public final class ThemeMonet implements ThemeDelegate {
  static final String[] TEMPLATE_NAMES = {"white", "dark", "black"};

  private static volatile Context loaderContext;
  /** Reads and parses all templates exactly once: on {@link #preload}'s thread or on the first theme's thread. */
  private static final FutureTask<Map<String, Template>> TEMPLATES = new FutureTask<>(ThemeMonet::loadTemplates);

  private final ThemeDelegate base;
  private final int[] colors = new int[ThemeColors.COUNT + 1];
  private final boolean[] hasColor = new boolean[ThemeColors.COUNT + 1];
  /** Color roles of this theme (Material 3 scheme of the device), see {@link M3.Roles}. */
  private final M3.Roles roles = new M3.Roles(true);

  public ThemeMonet (@NonNull ThemeDelegate base, @NonNull String template, @Nullable Context context) {
    this.base = base;
    if (context == null) {
      return;
    }
    final boolean dark = base.isDark();
    final int[] scheme = MonetPalette.scheme(context, dark, base.getId() == ThemeId.MONET_BLACK);
    final int[][] palette = MonetPalette.palette(context);
    final Template parsed = template(context, template);
    if (parsed == null) {
      return;
    }
    for (int i = 0; i < parsed.colorIds.length; i++) {
      int colorId = parsed.colorIds[i];
      colors[colorId] = parsed.values[i].resolve(scheme, palette);
      hasColor[colorId] = true;
    }
  }

  /**
   * Starts reading the templates and the system scheme on a background thread. Safe to call more than once and from
   * any thread; themes built meanwhile wait for this load instead of starting their own.
   */
  public static void preload (@Nullable Context context) {
    if (context == null) {
      return;
    }
    if (loaderContext == null) {
      loaderContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }
    final Context appContext = loaderContext;
    Background.instance().post(() -> {
      TEMPLATES.run();
      MonetPalette.palette(appContext);
    });
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
    /** @param scheme roles of the scheme, {@link MonetPalette#scheme} */
    @ColorInt int resolve (int[] scheme, int[][] palette);
  }

  private static final class Template {
    final int[] colorIds;
    final Value[] values;

    Template (int[] colorIds, Value[] values) {
      this.colorIds = colorIds;
      this.values = values;
    }
  }

  private static @Nullable Template template (@NonNull Context context, String name) {
    if (loaderContext == null) {
      loaderContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }
    // FutureTask runs its body at most once: if preload() is already loading, run() returns at once and get() waits.
    TEMPLATES.run();
    try {
      return TEMPLATES.get().get(name);
    } catch (ExecutionException e) {
      Log.e("Cannot load Monet templates", e.getCause());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static Map<String, Template> loadTemplates () {
    final Context context = loaderContext;
    final Map<String, Integer> ids = ThemeColors.getMap();
    final Map<String, Template> templates = new HashMap<>();
    for (String name : TEMPLATE_NAMES) {
      final Map<String, String> source = new LinkedHashMap<>();
      loadTemplate(context, name, source);
      final Map<String, String> variables = new HashMap<>();
      for (java.util.Iterator<Map.Entry<String, String>> it = source.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<String, String> entry = it.next();
        if (entry.getKey().startsWith("$")) {
          variables.put(entry.getKey(), entry.getValue());
          it.remove();
        }
      }
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
          values[count] = parse(expand(entry.getValue(), variables, 0));
          colorIds[count] = colorId;
          count++;
        } catch (IllegalArgumentException e) {
          Log.w("Bad value in Monet template %s: %s: %s", name, entry.getKey(), entry.getValue());
        }
      }
      templates.put(name, new Template(Arrays.copyOf(colorIds, count), Arrays.copyOf(values, count)));
    }
    return templates;
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

  private static final Pattern VARIABLE = Pattern.compile("\\$[A-Za-z][A-Za-z0-9_]*");

  /** Substitutes `$name` variables (`$name: value` lines of the template; a variable may use other variables). */
  private static String expand (String value, Map<String, String> variables, int depth) {
    if (value.indexOf('$') == -1) {
      return value;
    }
    if (depth > 8) {
      throw new IllegalArgumentException(value);
    }
    Matcher m = VARIABLE.matcher(value);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      String definition = variables.get(m.group());
      if (definition == null) {
        throw new IllegalArgumentException(value);
      }
      m.appendReplacement(b, Matcher.quoteReplacement(expand(definition, variables, depth + 1)));
    }
    m.appendTail(b);
    return b.toString();
  }

  private static Map<String, Integer> roleIndexes;

  private static int roleIndex (String name) {
    if (roleIndexes == null) {
      Map<String, Integer> map = new HashMap<>();
      for (int i = 0; i < MonetPalette.ROLE_NAMES.length; i++) {
        map.put(MonetPalette.ROLE_NAMES[i], i);
      }
      roleIndexes = map;
    }
    Integer index = roleIndexes.get(name);
    return index != null ? index : -1;
  }

  /**
   * Parses `role`, `P40` (palette tone), `#RRGGBB[AA]`, `mix(A,B,P)`, `contrast(A,BG,R)`, each optionally followed by
   * `@NN` (opacity, %).
   */
  private static Value parse (String value) {
    value = value.trim();
    int at = value.lastIndexOf('@');
    if (at > 0 && value.indexOf(')', at) == -1) {
      final Value color = parse(value.substring(0, at));
      final int alpha = Math.round(Integer.parseInt(value.substring(at + 1).trim()) * 255 / 100f);
      if (alpha < 0 || alpha > 255) {
        throw new IllegalArgumentException(value);
      }
      return (scheme, palette) -> (color.resolve(scheme, palette) & 0x00FFFFFF) | (alpha << 24);
    }
    if (value.startsWith("mix(") && value.endsWith(")")) {
      String[] args = splitArguments(value.substring(4, value.length() - 1));
      final Value a = parse(args[0]);
      final Value b = parse(args[1]);
      final float p = Integer.parseInt(args[2].trim()) / 100f;
      return (scheme, palette) -> mix(a.resolve(scheme, palette), b.resolve(scheme, palette), p);
    }
    if (value.startsWith("contrast(") && value.endsWith(")")) {
      String[] args = splitArguments(value.substring(9, value.length() - 1));
      final Value color = parse(args[0]);
      final Value background = parse(args[1]);
      final double ratio = Double.parseDouble(args[2].trim());
      if (!(ratio >= 1 && ratio <= 21)) {
        throw new IllegalArgumentException(value);
      }
      return (scheme, palette) -> ensureContrast(color.resolve(scheme, palette), background.resolve(scheme, palette), ratio);
    }
    if (value.startsWith("#")) {
      final int color = parseLiteral(value.substring(1));
      return (scheme, palette) -> color;
    }
    final int role = roleIndex(value);
    if (role != -1) {
      return (scheme, palette) -> scheme[role];
    }
    int digits = 0;
    while (digits < value.length() && !Character.isDigit(value.charAt(digits))) {
      digits++;
    }
    if (digits > 0 && digits < value.length()) {
      String paletteName = value.substring(0, digits);
      for (int paletteIndex = 0; paletteIndex < MonetPalette.PALETTE_NAMES.length; paletteIndex++) {
        if (MonetPalette.PALETTE_NAMES[paletteIndex].equals(paletteName)) {
          final int p = paletteIndex;
          final int tone = Integer.parseInt(value.substring(digits));
          if (tone < 0 || tone > 100) {
            break;
          }
          return (scheme, palette) -> MonetPalette.tone(palette, p, tone);
        }
      }
    }
    throw new IllegalArgumentException(value);
  }

  /**
   * `contrast(A, BG, R)`: color A, or, when it is less than R:1 apart from BG (WCAG contrast ratio), the nearest color
   * with the same hue and chroma (CIELAB a*, b*) and a lightness (= M3 tone) that gives R:1, as Material 3 dynamic color
   * moves a role's tone along its contrast curve. The schemes keep their accents apart by hue, and in schemes without
   * hue (monochrome, low-chroma wallpapers) `primary` and `primaryContainer` can get almost the same tone.
   * When no tone reaches R:1, the tone with the highest contrast is used.
   */
  static @ColorInt int ensureContrast (@ColorInt int color, @ColorInt int background, double ratio) {
    final int alpha = Color.alpha(color);
    final int opaque = color | 0xFF000000;
    background |= 0xFF000000;
    if (androidx.core.graphics.ColorUtils.calculateContrast(opaque, background) >= ratio) {
      return color;
    }
    final double[] lab = new double[3];
    androidx.core.graphics.ColorUtils.colorToLAB(opaque, lab);
    final double l = lab[0];
    final double[] backgroundLab = new double[3];
    androidx.core.graphics.ColorUtils.colorToLAB(background, backgroundLab);
    int best = 0;
    double bestDistance = Double.MAX_VALUE, bestContrast = 0;
    int fallback = opaque;
    for (int direction = -1; direction <= 1; direction += 2) {
      final double limit = direction < 0 ? 0 : 100;
      final int extreme = androidx.core.graphics.ColorUtils.LABToColor(limit, lab[1], lab[2]);
      final double extremeContrast = androidx.core.graphics.ColorUtils.calculateContrast(extreme, background);
      if (extremeContrast < ratio) {
        if (extremeContrast > bestContrast) {
          bestContrast = extremeContrast;
          fallback = extreme;
        }
        continue;
      }
      // Contrast grows while the tone moves away from the background's tone: the nearest tone is a bisection
      // (on the far side of the background it starts from the background's tone).
      double near = direction < 0 ? Math.min(l, backgroundLab[0]) : Math.max(l, backgroundLab[0]), far = limit;
      for (int i = 0; i < 16; i++) {
        double mid = (near + far) / 2;
        if (androidx.core.graphics.ColorUtils.calculateContrast(androidx.core.graphics.ColorUtils.LABToColor(mid, lab[1], lab[2]), background) >= ratio) {
          far = mid;
        } else {
          near = mid;
        }
      }
      if (Math.abs(far - l) < bestDistance) {
        bestDistance = Math.abs(far - l);
        best = androidx.core.graphics.ColorUtils.LABToColor(far, lab[1], lab[2]);
      }
    }
    final int result = bestDistance != Double.MAX_VALUE ? best : fallback;
    return (result & 0x00FFFFFF) | (alpha << 24);
  }

  /** Splits top-level comma-separated arguments (nested mix() calls keep their commas). */
  private static String[] splitArguments (String args) {
    String[] result = new String[3];
    int depth = 0, start = 0, count = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        if (count == 2) {
          throw new IllegalArgumentException(args);
        }
        result[count++] = args.substring(start, i);
        start = i + 1;
      }
    }
    if (count != 2) {
      throw new IllegalArgumentException(args);
    }
    result[2] = args.substring(start);
    return result;
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
