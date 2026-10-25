#!/usr/bin/env python3
"""
Bakes the built-in Monet themes: app/src/main/assets/monet/*.monet -> app/src/main/other/themes/Monet *.tgx-theme

The .monet templates are resolved at runtime against the device's Material You palette
(see ThemeMonet.java). The baked .tgx-theme files are the static fallback used at build time
(ThemeId / ColorId generation, devices without dynamic color) and are resolved against a
fixed default palette below. Re-run after editing a template:

    python3 scripts/monet/bake_monet_themes.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/monet"
THEMES = ROOT / "app/src/main/other/themes"

TONES = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]
# Default palette (Tonal Spot, seed #1A73E8)
PALETTE = {
  "a1": ["FFFFFF", "FEFBFF", "EDF0FF", "D8E2FF", "ADC7FF", "91ABE3", "7691C7", "5D77AC", "435E91", "2A4678", "0F2F60", "001A41", "000000"],
  "a2": ["FFFFFF", "FEFBFF", "EDF0FF", "DBE2F9", "BFC6DC", "A3ABC0", "8991A5", "6F778B", "565E71", "3F4759", "283041", "131B2C", "000000"],
  "a3": ["FFFFFF", "FFFBFF", "FFEBFD", "FBD7FC", "DEBCDF", "C1A1C3", "A687A8", "8B6D8D", "715574", "583E5B", "402843", "29132D", "000000"],
  "n1": ["FFFFFF", "FEFBFF", "F2F0F4", "E3E2E6", "C7C6CA", "ABABAF", "919094", "77777A", "5E5E62", "46464A", "303033", "1B1B1F", "000000"],
  "n2": ["FFFFFF", "FEFBFF", "EFF0FA", "E1E2EC", "C4C6D0", "A9ABB4", "8E9099", "74777F", "5C5E66", "44474F", "2D3038", "191C22", "000000"],
}

THEME_FILES = {
  # template: (id, name, tgx properties)
  "white": (12, "Monet White", "bubbleOutline, lightStatusBar, replaceShadowsWithSeparators: 1\nbubbleOutlineSize, wallpaperId: 0\nparentTheme: 1"),
  "dark": (13, "Monet Dark", "bubbleOutline, replaceShadowsWithSeparators: 1\nbubbleOutlineSize: 0\nparentTheme: 2"),
  "black": (14, "Monet Black", "bubbleOutline, replaceShadowsWithSeparators: 1\nbubbleOutlineSize: 0\nparentTheme: 13"),
}

TOKEN = re.compile(r"^(a[123]|n[12])_(\d+)$")


def load(name):
  colors = {}
  for line in (ASSETS / f"{name}.monet").read_text().splitlines():
    line = line.split("#", 1)[0] if line.lstrip().startswith("#") else line
    m = re.match(r"^\s*([A-Za-z0-9_]+)\s*:\s*(.+?)\s*$", line)
    if not m:
      continue
    key, value = m.groups()
    if key == "include":
      colors.update(load(value))
    else:
      colors[key] = value
  return colors


def parse_literal(h):
  h = h.lstrip("#")
  if len(h) in (3, 4):
    h = "".join(c * 2 for c in h)
  if len(h) == 6:
    h += "FF"
  assert len(h) == 8, h
  return tuple(int(h[i:i + 2], 16) for i in range(0, 8, 2))  # r g b a


def resolve(value):
  value = value.strip()
  m = re.match(r"^mix\((.+),(.+),(\d+)\)$", value)
  if m:
    a, b, p = resolve(m.group(1)), resolve(m.group(2)), int(m.group(3)) / 100
    return tuple(round(a[i] + (b[i] - a[i]) * p) for i in range(4))
  if value.startswith("#"):
    return parse_literal(value)
  # token + optional 2-digit alpha
  for tone in sorted(map(str, TONES), key=len, reverse=True):
    prefix = value[:2]
    if prefix in PALETTE and value[2] == "_" and value[3:].startswith(tone):
      rest = value[3 + len(tone):]
      if rest == "" or re.fullmatch(r"[0-9A-Fa-f]{2}", rest):
        r, g, b, _ = parse_literal(PALETTE[prefix][TONES.index(int(tone))])
        return (r, g, b, int(rest, 16) if rest else 255)
  raise ValueError(f"Cannot resolve {value!r}")


def main():
  for name, (theme_id, theme_name, properties) in THEME_FILES.items():
    colors = load(name)
    lines = ["!", f"id: {theme_id}", f'name: "{theme_name}"', "time: 1790000000", "@", properties, "#"]
    for key, value in colors.items():
      r, g, b, a = resolve(value)
      hex_value = f"#{r:02X}{g:02X}{b:02X}" + (f"{a:02X}" if a != 255 else "")
      lines.append(f"{key}: {hex_value}")
    path = THEMES / f"{theme_name}.tgx-theme"
    path.write_text("\n".join(lines) + "\n")
    print(f"{path.relative_to(ROOT)}: {len(colors)} colors")


if __name__ == "__main__":
  sys.exit(main())
