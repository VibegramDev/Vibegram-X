#!/usr/bin/env python3
"""Replace Vibegram-X vector icons with Material Symbols Rounded, keeping resource names.

Reads scripts/icons/mapping.tsv (file -> symbol, fill, box), downloads the Android vector of
Material Symbols Rounded (wght 400, GRAD 0, opsz 24) from google/material-design-icons and
overwrites app/src/main/res/drawable/<file>.xml in place, keeping the existing file's
android:width/height, android:tint, android:autoMirrored and fill colour (TGX tints icons in code).
The symbol's own android:tint/autoMirrored are dropped on purpose: TGX mirrors icons in code.

Falls back to the web SVG (path + viewBox) when no Android vector is published.
Re-runnable: the attributes it keeps are read from the current file, so a second run is a no-op.
Downloads are cached in build/icons-cache (delete it to refresh).

Usage:
  python3 scripts/icons/convert.py            # convert + regenerate docs/ICONS.md
  python3 scripts/icons/convert.py --check    # validate mapping/downloads, write nothing
  python3 scripts/icons/convert.py --only deproko_baseline_pin_24
"""

import argparse
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ANDROID_NS = 'http://schemas.android.com/apk/res/android'
A = '{%s}' % ANDROID_NS
RAW = 'https://raw.githubusercontent.com/google/material-design-icons/master/symbols'
STYLE = 'materialsymbolsrounded'
PREFIXES = ('baseline_', 'outline_', 'round_', 'deproko_', 'templarian_')
DEFAULT_COLOR = '#FF000000'

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
DRAWABLE = os.path.join(REPO, 'app', 'src', 'main', 'res', 'drawable')


# Mapping

def read_mapping(path):
  rows = []
  with open(path, encoding='utf-8') as f:
    for line_no, line in enumerate(f, 1):
      line = line.rstrip('\n')
      if not line or line.startswith('#'):
        continue
      parts = line.split('\t')
      if len(parts) != 5:
        sys.exit('mapping.tsv:%d: expected file<TAB>symbol<TAB>fill<TAB>box<TAB>note' % line_no)
      file, symbol, fill, box, note = parts
      if symbol != '-':
        if fill not in ('0', '1'):
          sys.exit('mapping.tsv:%d: fill must be 0 or 1' % line_no)
        if box != '-' and len(box.split(',')) != 4:
          sys.exit('mapping.tsv:%d: box must be "-" or x0,y0,x1,y1' % line_no)
      rows.append((file, symbol, fill, box, note))
  return rows


# Download

def fetch(url, cache_dir):
  name = re.sub(r'[^A-Za-z0-9_.-]', '_', url.split('/symbols/', 1)[1])
  cached = os.path.join(cache_dir, name)
  if os.path.exists(cached):
    with open(cached, 'rb') as f:
      data = f.read()
    return data or None
  try:
    req = urllib.request.Request(url, headers={'User-Agent': 'vibegram-icons'})
    with urllib.request.urlopen(req, timeout=30) as r:
      data = r.read()
  except urllib.error.HTTPError as e:
    if e.code != 404:
      raise
    data = b''
  os.makedirs(cache_dir, exist_ok=True)
  with open(cached, 'wb') as f:
    f.write(data)  # empty file = 404, so misses are cached too
  return data or None


def load_symbol(symbol, fill, cache_dir):
  """Returns (viewportW, viewportH, [(pathData, fillType)], source); paths in a y-down viewport at 0,0."""
  suffix = '_fill1_24px' if fill == '1' else '_24px'
  url = '%s/android/%s/%s/%s%s.xml' % (RAW, symbol, STYLE, symbol, suffix)
  data = fetch(url, cache_dir)
  if data:
    root = ET.fromstring(data)
    paths = []
    for el in root.iter():
      if el is root:
        continue
      if el.tag != 'path':
        raise ValueError('%s: unsupported element <%s>' % (url, el.tag))
      paths.append((el.get(A + 'pathData'), el.get(A + 'fillType')))
    return (float(root.get(A + 'viewportWidth') or 960), float(root.get(A + 'viewportHeight') or 960), paths, 'android')
  url = '%s/web/%s/%s/%s%s.svg' % (RAW, symbol, STYLE, symbol, suffix)
  data = fetch(url, cache_dir)
  if not data:
    raise ValueError('no Android vector or SVG for %s (fill %s)' % (symbol, fill))
  root = ET.fromstring(data)
  min_x, min_y, w, h = [float(v) for v in (root.get('viewBox') or '').split()]
  paths = []
  for el in root.iter():
    tag = el.tag.split('}')[-1]
    if tag == 'path':
      d = el.get('d')
      if min_x or min_y:  # web symbols use viewBox="0 -960 960 960": move to 0,0
        d = translate_path(d, -min_x, -min_y)
      paths.append((d, 'evenOdd' if el.get('fill-rule') == 'evenodd' else None))
    elif tag not in ('svg', 'title', 'desc', 'g'):
      raise ValueError('%s: unsupported element <%s>' % (url, tag))
  return (w, h, paths, 'svg')


# Path geometry (M L H V C S Q T Z, absolute and relative; arcs are not used by Material Symbols)

_TOKEN = re.compile(r'[MmLlHhVvCcSsQqTtZzAa]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')
_ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'Z': 0}


def path_segments(d):
  """Yields absolute segments: ('M'|'L', (x, y)), ('Q', c, p), ('C', c1, c2, p), ('Z',)."""
  tokens = _TOKEN.findall(d)
  i, cmd = 0, None
  x = y = sx = sy = 0.0
  last_c = None  # last control point, for S/T
  while i < len(tokens):
    if tokens[i].isalpha():
      cmd = tokens[i]
      i += 1
      if cmd.upper() == 'A':
        raise ValueError('arcs are not supported')
    elif cmd is None:
      raise ValueError('path does not start with a command')
    up = cmd.upper()
    rel = cmd != up
    n = _ARGS[up]
    vals = [float(v) for v in tokens[i:i + n]]
    i += n
    ox, oy = (x, y) if rel else (0.0, 0.0)
    if up == 'Z':
      x, y = sx, sy
      last_c = None
      yield ('Z',)
      continue
    if up == 'H':
      x = vals[0] + (x if rel else 0.0)
      last_c = None
      yield ('L', (x, y))
    elif up == 'V':
      y = vals[0] + (y if rel else 0.0)
      last_c = None
      yield ('L', (x, y))
    elif up in ('M', 'L'):
      x, y = vals[0] + ox, vals[1] + oy
      last_c = None
      if up == 'M':
        sx, sy = x, y
        cmd = 'l' if rel else 'L'
      yield (up, (x, y))
    elif up in ('Q', 'T'):
      if up == 'Q':
        c = (vals[0] + ox, vals[1] + oy)
        p = (vals[2] + ox, vals[3] + oy)
      else:
        c = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
        p = (vals[0] + ox, vals[1] + oy)
      yield ('Q', c, p)
      last_c = c
      x, y = p
    else:  # C, S
      if up == 'C':
        c1 = (vals[0] + ox, vals[1] + oy)
        c2 = (vals[2] + ox, vals[3] + oy)
        p = (vals[4] + ox, vals[5] + oy)
      else:
        c1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
        c2 = (vals[0] + ox, vals[1] + oy)
        p = (vals[2] + ox, vals[3] + oy)
      yield ('C', c1, c2, p)
      last_c = c2
      x, y = p


def _fmt(v):
  s = ('%.3f' % v).rstrip('0').rstrip('.')
  return '0' if s in ('-0', '') else s


def translate_path(d, dx, dy):
  out = []
  for seg in path_segments(d):
    if seg[0] == 'Z':
      out.append('Z')
    else:
      out.append(seg[0] + ' '.join('%s,%s' % (_fmt(px + dx), _fmt(py + dy)) for px, py in seg[1:]))
  return ''.join(out)


def _extrema(p0, p1, p2, p3=None):
  """Parameter values in (0,1) where a quadratic/cubic Bezier coordinate has a zero derivative."""
  ts = []
  if p3 is None:
    den = p0 - 2 * p1 + p2
    if den:
      ts.append((p0 - p1) / den)
  else:
    a = -p0 + 3 * p1 - 3 * p2 + p3
    b = 2 * (p0 - 2 * p1 + p2)
    c = p1 - p0
    if abs(a) < 1e-12:
      if b:
        ts.append(-c / b)
    else:
      disc = b * b - 4 * a * c
      if disc >= 0:
        r = disc ** 0.5
        ts += [(-b + r) / (2 * a), (-b - r) / (2 * a)]
  return [t for t in ts if 0 < t < 1]


def _bez(t, *p):
  if len(p) == 3:
    return (1 - t) ** 2 * p[0] + 2 * (1 - t) * t * p[1] + t * t * p[2]
  return (1 - t) ** 3 * p[0] + 3 * (1 - t) ** 2 * t * p[1] + 3 * (1 - t) * t * t * p[2] + t ** 3 * p[3]


def path_bounds(paths):
  xs, ys = [], []
  for d, _ in paths:
    cur = (0.0, 0.0)
    for seg in path_segments(d):
      if seg[0] == 'Z':
        continue
      pts = [cur] + list(seg[1:])
      xs.append(pts[-1][0])
      ys.append(pts[-1][1])
      if seg[0] in ('Q', 'C'):
        for axis, acc in ((0, xs), (1, ys)):
          coords = [p[axis] for p in pts]
          for t in _extrema(*coords):
            acc.append(_bez(t, *coords))
      cur = pts[-1]
  return min(xs), min(ys), max(xs), max(ys)


# Output

def read_target(path):
  root = ET.parse(path).getroot()
  if root.tag != 'vector':
    raise ValueError('%s: not a <vector>' % path)
  color = None
  for el in root.iter('path'):
    c = el.get(A + 'fillColor')
    transparent = c is not None and len(c) == 9 and c.startswith('#00')
    if c and not transparent:
      color = c
      break
  return {
    'width': root.get(A + 'width'),
    'height': root.get(A + 'height'),
    'tint': root.get(A + 'tint'),
    'autoMirrored': root.get(A + 'autoMirrored'),
    'color': color or DEFAULT_COLOR,
  }


def box_transform(box, viewport_w, viewport_h, paths):
  """Uniform scale + translate that centres the glyph's bounds in `box` (24-unit grid)."""
  bx0, by0, bx1, by1 = [float(v) for v in box.split(',')]
  ux, uy = viewport_w / 24.0, viewport_h / 24.0
  bx0, bx1, by0, by1 = bx0 * ux, bx1 * ux, by0 * uy, by1 * uy
  gx0, gy0, gx1, gy1 = path_bounds(paths)
  s = min((bx1 - bx0) / (gx1 - gx0), (by1 - by0) / (gy1 - gy0))
  tx = (bx0 + bx1) / 2 - s * (gx0 + gx1) / 2
  ty = (by0 + by1) / 2 - s * (gy0 + gy1) / 2
  return s, tx, ty


def render(symbol, fill, attrs, viewport_w, viewport_h, paths, transform):
  out = ['<!-- Material Symbols Rounded "%s", FILL %s, wght 400, GRAD 0, opsz 24 (scripts/icons/convert.py) -->' % (symbol, fill)]
  out.append('<vector xmlns:android="%s"' % ANDROID_NS)
  out.append('  android:width="%s"' % attrs['width'])
  out.append('  android:height="%s"' % attrs['height'])
  out.append('  android:viewportWidth="%s"' % _fmt(viewport_w))
  out.append('  android:viewportHeight="%s"' % _fmt(viewport_h))
  if attrs['tint']:
    out.append('  android:tint="%s"' % attrs['tint'])
  if attrs['autoMirrored']:
    out.append('  android:autoMirrored="%s"' % attrs['autoMirrored'])
  out[-1] += '>'
  indent = '  '
  if transform:
    s, tx, ty = transform
    out.append('  <group')
    out.append('    android:scaleX="%s"' % _fmt(s))
    out.append('    android:scaleY="%s"' % _fmt(s))
    out.append('    android:translateX="%s"' % _fmt(tx))
    out.append('    android:translateY="%s">' % _fmt(ty))
    indent = '    '
  for data, fill_type in paths:
    out.append('%s<path' % indent)
    out.append('%s  android:fillColor="%s"' % (indent, attrs['color']))
    if fill_type:
      out.append('%s  android:fillType="%s"' % (indent, fill_type))
    out.append('%s  android:pathData="%s" />' % (indent, data))
  if transform:
    out.append('  </group>')
  out.append('</vector>')
  return '\n'.join(out) + '\n'


def write_docs(rows, path):
  replaced = [r for r in rows if r[1] != '-']
  kept = [r for r in rows if r[1] == '-']
  fill1 = [r for r in replaced if r[2] == '1']
  boxed = [r for r in replaced if r[3] != '-']
  lines = [
    '# Icons',
    '',
    'App vector icons use [Material Symbols](https://fonts.google.com/icons) **Rounded**, weight 400, grade 0,',
    'optical size 24, FILL 0 by default. Resource names did not change, so Java/Kotlin code is untouched.',
    '',
    '- Source: `google/material-design-icons`, `symbols/android/<name>/materialsymbolsrounded/<name>[_fill1]_24px.xml`',
    '  (Apache-2.0, see [THIRDPARTY.md](THIRDPARTY.md)).',
    '- Map: `scripts/icons/mapping.tsv`; converter: `python3 scripts/icons/convert.py` (re-runnable, keeps each',
    '  file\'s `android:width/height`, `android:tint`, `android:autoMirrored` and fill colour; TGX tints and',
    '  mirrors icons in code, so the symbol\'s own `tint`/`autoMirrored` are not copied).',
    '- FILL 1 only for: the selected counterpart of an outline icon (navigation bar, emoji panel sections),',
    '  files whose name says "filled", media transport glyphs (play/pause/stop/skip/fast-forward), the solid',
    '  verified badge and "on" status markers (rating star, favourite marker over media thumbnails).',
    '- Symbols renamed or redrew some Material Icons: e.g. `phone` -> `call`, `insert_drive_file` -> `draft`,',
    '  `push_pin` -> `keep`, and `whatshot` is now a trend arrow, so fire glyphs use `local_fire_department`.',
    '- Box: inline glyphs whose size/position code relies on (message ticks and clock, chat-list mute, secret-chat',
    '  lock, verified badge, inline-button link arrow, muted video, Instant View) are scaled into the old glyph\'s',
    '  bounds with a `<group>` so layout offsets in code (`Icons.TICKS_SHIFT_*`, `CLOCK_SHIFT_*`...) still fit.',
    '- Kept as is: brand/logo glyphs (Telegram, Premium star, stores, browsers, OS logos), layered drawables that',
    '  code animates part by part, composites with no equivalent. `vg_star*` (Telegram Stars), multi-colour and',
    '  animated drawables, PNGs and `intro_*` art are outside the converted prefixes.',
    '',
    'Totals: %d files, %d replaced (%d with FILL 1, %d boxed), %d kept.' % (len(rows), len(replaced), len(fill1), len(boxed), len(kept)),
    '',
    '| File | Material Symbol | Fill | Box | Note |',
    '|---|---|---|---|---|',
  ]
  for file, symbol, fill, box, note in rows:
    note = note.replace('|', '/')
    if symbol == '-':
      lines.append('| `%s` | kept | – | – | %s |' % (file, note))
    else:
      lines.append('| `%s` | `%s` | %s | %s | %s |' % (file, symbol, fill, '–' if box == '-' else box, note))
  extra = os.path.join(HERE, 'code-drawn.md')
  if os.path.exists(extra):
    with open(extra, encoding='utf-8') as f:
      lines += ['', f.read().rstrip('\n')]
  with open(path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines) + '\n')


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument('--mapping', default=os.path.join(HERE, 'mapping.tsv'))
  ap.add_argument('--cache', default=os.path.join(REPO, 'build', 'icons-cache'))
  ap.add_argument('--check', action='store_true', help='validate only, write nothing')
  ap.add_argument('--only', action='append', help='convert only these files (stem)')
  ap.add_argument('--no-docs', action='store_true')
  args = ap.parse_args()

  rows = read_mapping(args.mapping)
  mapped = {r[0] for r in rows}
  on_disk = sorted(f[:-4] for f in os.listdir(DRAWABLE) if f.endswith('.xml') and f.startswith(PREFIXES))
  errors = ['no mapping row for drawable/%s.xml' % s for s in on_disk if s not in mapped]
  errors += ['mapping row for missing file drawable/%s.xml' % s for s in sorted(mapped)
             if not os.path.exists(os.path.join(DRAWABLE, s + '.xml'))]
  if len(mapped) != len(rows):
    errors.append('duplicate rows in mapping.tsv')
  if errors:
    sys.exit('\n'.join(errors))

  replaced = kept = 0
  for file, symbol, fill, box, note in rows:
    if args.only and file not in args.only:
      continue
    if symbol == '-':
      kept += 1
      continue
    target = os.path.join(DRAWABLE, file + '.xml')
    attrs = read_target(target)
    vw, vh, paths, source = load_symbol(symbol, fill, args.cache)
    transform = box_transform(box, vw, vh, paths) if box != '-' else None
    xml = render(symbol, fill, attrs, vw, vh, paths, transform)
    ET.fromstring(xml)  # must parse
    if not args.check:
      with open(target, 'w', encoding='utf-8') as f:
        f.write(xml)
    replaced += 1
    if source != 'android':
      print('%s: converted from web SVG' % file)
  if not args.check and not args.only and not args.no_docs:
    write_docs(rows, os.path.join(REPO, 'docs', 'ICONS.md'))
  print('replaced %d, kept %d%s' % (replaced, kept, ' (check only)' if args.check else ''))


if __name__ == '__main__':
  main()
