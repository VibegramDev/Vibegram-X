#!/usr/bin/env python3
"""
Bakes the built-in Monet themes: app/src/main/assets/monet/*.monet -> app/src/main/other/themes/Monet *.tgx-theme

The .monet templates describe Telegram X colors with Material 3 color roles and tonal palette tones; they are
resolved at runtime against the device scheme (ThemeMonet.java, MonetPalette.java). The baked .tgx-theme files are
the static fallback used at build time (ThemeId / ColorId generation) and are resolved here against the default
palette of MonetPalette.java, with the role tones of the M3 tonal spot scheme declared there (`role(...)` lines),
so the Java code stays the only place that defines them. Re-run after editing a template or MonetPalette.java:

    python3 scripts/monet/bake_monet_themes.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/monet"
THEMES = ROOT / "app/src/main/other/themes"
MONET_PALETTE = ROOT / "app/src/main/java/org/thunderdog/challegram/theme/MonetPalette.java"

TONES = [100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0]
PALETTE_NAMES = {"P": "PRIMARY", "S": "SECONDARY", "T": "TERTIARY", "N": "NEUTRAL", "NV": "NEUTRAL_VARIANT", "E": "ERROR"}
PALETTE_ORDER = ["PRIMARY", "SECONDARY", "TERTIARY", "NEUTRAL", "NEUTRAL_VARIANT"]

THEME_FILES = {
  # template: (id, name, dark, black, tgx properties)
  "white": (12, "Monet White", False, False, "bubbleOutline, lightStatusBar, replaceShadowsWithSeparators: 1\nbubbleOutlineSize, wallpaperId: 0\nparentTheme: 1"),
  "dark": (13, "Monet Dark", True, False, "bubbleOutline, replaceShadowsWithSeparators: 1\nbubbleOutlineSize: 0\nparentTheme: 2"),
  "black": (14, "Monet Black", True, True, "bubbleOutline, replaceShadowsWithSeparators: 1\nbubbleOutlineSize: 0\nparentTheme: 13"),
}


def java_palettes():
  source = MONET_PALETTE.read_text()
  def colors(block):
    return [int(c, 16) & 0xFFFFFF for c in re.findall(r"0x([0-9A-Fa-f]{8})", block)]
  default = source[source.index("DEFAULT = {"):]
  default = default[:default.index("};")]
  rows = re.findall(r"\{([^{}]+)\}", default)
  palettes = {name: colors(row) for name, row in zip(PALETTE_ORDER, rows)}
  error = source[source.index("ERROR_PALETTE = {"):]
  palettes["ERROR"] = colors(error[:error.index("};")])
  for name, values in palettes.items():
    assert len(values) == len(TONES), name
  roles = {}
  for name, palette, light, dark in re.findall(r'role\([\w.]+, "(\w+)", (\w+), (\d+), (\d+)\);', source):
    roles[name] = (palette, int(light), int(dark))
  assert len(roles) >= 30, roles
  return palettes, roles


# CIELAB (D65), as androidx.core.graphics.ColorUtils
WHITE = (95.047, 100.0, 108.883)


def to_lab(rgb):
  def lin(c):
    c /= 255.0
    return c / 12.92 if c < 0.04045 else ((c + 0.055) / 1.055) ** 2.4
  r, g, b = (lin((rgb >> s) & 0xFF) for s in (16, 8, 0))
  xyz = (100 * (r * 0.4124 + g * 0.3576 + b * 0.1805), 100 * (r * 0.2126 + g * 0.7152 + b * 0.0722), 100 * (r * 0.0193 + g * 0.1192 + b * 0.9505))
  def f(t):
    return t ** (1 / 3) if t > 0.008856 else (903.3 * t + 16) / 116
  fx, fy, fz = (f(xyz[i] / WHITE[i]) for i in range(3))
  return (max(0.0, 116 * fy - 16), 500 * (fx - fy), 200 * (fy - fz))


def from_lab(l, a, b):
  fy = (l + 16) / 116
  fx = a / 500 + fy
  fz = fy - b / 200
  def inv(t, lin_l=False):
    return t ** 3 if t ** 3 > 0.008856 else (116 * t - 16) / 903.3
  x = WHITE[0] * inv(fx)
  y = WHITE[1] * (fy ** 3 if l > 903.3 * 0.008856 else l / 903.3)
  z = WHITE[2] * inv(fz)
  x, y, z = x / 100, y / 100, z / 100
  r = x * 3.2406 + y * -1.5372 + z * -0.4986
  g = x * -0.9689 + y * 1.8758 + z * 0.0415
  bb = x * 0.0557 + y * -0.2040 + z * 1.0570
  def gamma(c):
    c = 1.055 * c ** (1 / 2.4) - 0.055 if c > 0.0031308 else 12.92 * c
    return min(255, max(0, round(c * 255)))
  return (gamma(r) << 16) | (gamma(g) << 8) | gamma(bb)


def tone(palettes, palette, value):
  colors = palettes[palette]
  for i, t in enumerate(TONES):
    if t == value:
      return colors[i]
    if t < value:
      a, b = to_lab(colors[i - 1]), to_lab(colors[i])
      p = (TONES[i - 1] - value) / (TONES[i - 1] - t)
      return from_lab(value, a[1] + (b[1] - a[1]) * p, a[2] + (b[2] - a[2]) * p)
  raise ValueError(value)


def scheme(palettes, roles, dark, black):
  result = {name: tone(palettes, palette, d if dark else l) for name, (palette, l, d) in roles.items()}
  if black:
    result["surface"] = 0x000000
  return result


def load(name):
  colors = {}
  for line in (ASSETS / f"{name}.monet").read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#"):
      continue
    key, value = (part.strip() for part in line.split(":", 1))
    if key == "include":
      colors.update(load(value))
    else:
      colors[key] = value
  return colors


def parse_literal(h):
  if len(h) in (3, 4):
    h = "".join(c * 2 for c in h)
  if len(h) == 6:
    h += "FF"
  assert len(h) == 8, h
  return tuple(int(h[i:i + 2], 16) for i in range(0, 8, 2))  # r g b a


def split_arguments(args):
  depth, start, result = 0, 0, []
  for i, c in enumerate(args):
    depth += c == "("
    depth -= c == ")"
    if c == "," and depth == 0:
      result.append(args[start:i])
      start = i + 1
  result.append(args[start:])
  assert len(result) == 3, args
  return result


def resolve(value, colors, palettes):
  value = value.strip()
  at = value.rfind("@")
  if at > 0 and ")" not in value[at:]:
    r, g, b, _ = resolve(value[:at], colors, palettes)
    return (r, g, b, round(int(value[at + 1:]) * 255 / 100))
  if value.startswith("mix(") and value.endswith(")"):
    a_value, b_value, p = split_arguments(value[4:-1])
    a, b, p = resolve(a_value, colors, palettes), resolve(b_value, colors, palettes), int(p) / 100
    return tuple(round(a[i] + (b[i] - a[i]) * p) for i in range(4))
  if value.startswith("#"):
    return parse_literal(value[1:])
  if value in colors:
    c = colors[value]
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 255)
  m = re.fullmatch(r"([A-Z]+)(\d{1,3})", value)
  if m and m.group(1) in PALETTE_NAMES and int(m.group(2)) <= 100:
    c = tone(palettes, PALETTE_NAMES[m.group(1)], int(m.group(2)))
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 255)
  raise ValueError(f"Cannot resolve {value!r}")


def main():
  palettes, roles = java_palettes()
  for name, (theme_id, theme_name, dark, black, properties) in THEME_FILES.items():
    colors = load(name)
    roles_colors = scheme(palettes, roles, dark, black)
    lines = ["!", f"id: {theme_id}", f'name: "{theme_name}"', "time: 1790000000", "@", properties, "#"]
    for key, value in colors.items():
      r, g, b, a = resolve(value, roles_colors, palettes)
      hex_value = f"#{r:02X}{g:02X}{b:02X}" + (f"{a:02X}" if a != 255 else "")
      lines.append(f"{key}: {hex_value}")
    path = THEMES / f"{theme_name}.tgx-theme"
    path.write_text("\n".join(lines) + "\n")
    print(f"{path.relative_to(ROOT)}: {len(colors)} colors")


if __name__ == "__main__":
  sys.exit(main())
