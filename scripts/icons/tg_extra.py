#!/usr/bin/env python3
"""Replace Vibegram-X drawables with icons from Telegram Desktop and Telegram for iOS.

Complements scripts/icons/tg_apply.py (Telegram for Android 12.3.1 icons) for names Telegram for
Android has no matching icon for. Sources (see docs/THIRDPARTY.md):
  * telegramdesktop/tdesktop      @ 93624f698c9a883c3a76428b96d6573c4dd63a6d  GPL-3.0 (with OpenSSL exception)
  * TelegramMessenger/Telegram-iOS @ 279f89e4d4f63f17227bf877dc3d8990f22ecd58  GPL-2.0

Usage:
  python3 scripts/icons/tg_extra.py --tdesktop <tdesktop clone> --ios <Telegram-iOS clone> [--dry-run] [name ...]
Requires: PyMuPDF (fitz), numpy, scipy, Pillow.

Input: scripts/icons/tg-extra.tsv, one row per drawable:
  our_file, source, source_path, commit, licence, box_dp, scale, stroke_adjust_dp, note
  scale            dp per source unit: PDF point, SVG user unit of the <svg> width/height, PNG @1x unit
                   (= @3x px / 3). PNG sources are never upscaled (scale <= 1).
  stroke_adjust_dp change of the stroke width in dp (+ bolder, - lighter), applied as an outline offset
                   of half that value on each side (signed distance field of a supersampled render).
                   0 for filled glyphs, letters and 48-96dp empty states (they keep source proportions).
The values were chosen so outline glyphs match the Telegram for Android set at the same box size
(1.667dp stroke and ~20-22dp glyph at 24dp, ~1.5dp at 20dp, ~1.4dp at 14-18dp) and verified by
measuring the xxhdpi output the same way the Telegram reference PNGs were measured.

Output for every row: res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/<our_file>.png, white alpha mask in a
box_dp x box_dp box, glyph centred (whole-pixel offset). Every density is rendered directly from the
vector source (8x supersampled, box-filtered); PNG sources are downscaled from @3x. Then
res/drawable/<our_file>.xml (the old Material Symbol) is deleted: same resource name, no code change.
"""
import argparse, csv, math, os, sys

import fitz  # PyMuPDF
import numpy as np
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, 'app/src/main/res')
TSV = os.path.join(HERE, 'tg-extra.tsv')
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0)]
SS = 8  # supersampling factor
MIN_GAP = 1.0  # dp, smallest gap a stroke offset may leave between separate parts (STYLE: >= 1.0)


def load_alpha(im):
    """RGBA/LA/RGB image -> float alpha. Opaque images (white glyph on black) use luminance."""
    im = im.convert('RGBA')
    a = np.asarray(im)[:, :, 3].astype(np.float64) / 255.0
    if a.min() > 0.99:
        a = np.asarray(im.convert('L')).astype(np.float64) / 255.0
    return a


def render_vector(path, zoom):
    """Render page 1 of a PDF/SVG at `zoom` px per unit -> float alpha."""
    doc = fitz.open(path) if path.lower().endswith('.pdf') else fitz.open(path, filetype='svg')
    pix = doc[0].get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=True)
    im = Image.frombytes('RGBA', (pix.width, pix.height), pix.samples)
    return load_alpha(im)


def bbox(a, t=1e-3):
    ys, xs = np.nonzero(a > t)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def offset(a, r, min_gap):
    """Grow (r > 0) or shrink (r < 0) the shape in `a` by r px; returns binary float array.

    When growing, separate parts never close the gap between them below `min_gap` px (or below
    their original gap if that was smaller): a pixel is added to its nearest part only if it is at
    least `min_gap` px closer to that part than to any other one (knock-outs, dots and the "+" of
    compound glyphs stay detached, as in Telegram's own icons)."""
    pad = int(math.ceil(abs(r) + min_gap)) + 2
    b = np.pad(a >= 0.5, pad)
    if r <= 0:
        return (ndimage.distance_transform_edt(b) >= -r).astype(np.float64), pad
    lab, n = ndimage.label(b, structure=np.ones((3, 3)))
    d1 = np.full(b.shape, np.inf)
    d2 = np.full(b.shape, np.inf)
    for i in range(1, n + 1):
        d = ndimage.distance_transform_edt(lab != i)
        d2 = np.minimum(d2, np.maximum(d1, d))
        d1 = np.minimum(d1, d)
    grow = (d1 <= r) & (d2 - d1 >= min_gap)
    return (b | grow).astype(np.float64), pad


def block_mean(a, s):
    h, w = a.shape
    H, W = -(-h // s) * s, -(-w // s) * s
    a = np.pad(a, ((0, H - h), (0, W - w)))
    return a.reshape(H // s, s, W // s, s).mean(axis=(1, 3))


def glyph_vector(path, scale, dens, adjust):
    """Return (alpha at density, glyph centre in output px relative to the array origin)."""
    a = render_vector(path, scale * dens * SS)
    x0, y0, x1, y1 = bbox(a)
    # crop on whole output pixels so the source keeps its phase against the pixel grid
    x0, y0 = (x0 // SS) * SS, (y0 // SS) * SS
    a = a[y0:y1, x0:x1]
    if adjust:
        a, pad = offset(a, adjust / 2.0 * dens * SS, MIN_GAP * dens * SS)
        p = -(-pad // SS) * SS  # keep whole-pixel phase
        a = np.pad(a, p - pad)
    gx0, gy0, gx1, gy1 = bbox(a, 0.5 if adjust else 1e-3)
    return block_mean(a, SS), ((gx0 + gx1) / 2.0 / SS, (gy0 + gy1) / 2.0 / SS)


def glyph_png(path, scale, dens, adjust):
    a = load_alpha(Image.open(path))
    x0, y0, x1, y1 = bbox(a)
    a = a[y0:y1, x0:x1]
    if adjust:  # outline offset on a supersampled copy, then back to @3x
        h, w = a.shape
        up = np.asarray(Image.fromarray(a.astype(np.float32)).resize((w * SS, h * SS), Image.BICUBIC))
        b, pad = offset(np.clip(up, 0, 1), adjust / 2.0 * 3 * SS, MIN_GAP * 3 * SS)
        p = -(-pad // SS) * SS
        a = block_mean(np.pad(b, p - pad), SS)
    k = scale * dens / 3.0
    if k > 1.0 + 1e-6:
        raise SystemExit('%s: PNG source would be upscaled (x%.3f)' % (path, k))
    if abs(k - 1.0) > 1e-6:
        h, w = a.shape
        im = Image.fromarray((a * 255).round().astype(np.uint8))
        a = np.asarray(im.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)).astype(np.float64) / 255
    gx0, gy0, gx1, gy1 = bbox(a)
    return a, ((gx0 + gx1) / 2.0, (gy0 + gy1) / 2.0)


def compose(glyph, centre, box_px, name):
    out = np.zeros((box_px, box_px))
    ox = int(round(box_px / 2.0 - centre[0]))
    oy = int(round(box_px / 2.0 - centre[1]))
    h, w = glyph.shape
    sx0, sy0 = max(0, -ox), max(0, -oy)
    sx1, sy1 = min(w, box_px - ox), min(h, box_px - oy)
    lost = glyph.sum() - glyph[sy0:sy1, sx0:sx1].sum()
    if lost > 0.5:
        print('WARN %s: glyph clipped by the %dpx box (%.1f px of ink)' % (name, box_px, lost))
    out[oy + sy0:oy + sy1, ox + sx0:ox + sx1] = glyph[sy0:sy1, sx0:sx1]
    a = (np.clip(out, 0, 1) * 255).round().astype(np.uint8)
    im = Image.new('RGBA', (box_px, box_px), (255, 255, 255, 0))
    im.putalpha(Image.fromarray(a))
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--tdesktop', required=True)
    ap.add_argument('--ios', required=True)
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('names', nargs='*')
    args = ap.parse_args()
    rows = [r for r in csv.DictReader(open(TSV), delimiter='\t') if not r['our_file'].startswith('#')]
    if args.names:
        rows = [r for r in rows if r['our_file'] in args.names]
    done = 0
    for r in rows:
        name = r['our_file']
        root = args.tdesktop if r['source'] == 'tdesktop' else args.ios
        src = os.path.join(root, r['source_path'])
        box, scale, adjust = float(r['box_dp']), float(r['scale']), float(r['stroke_adjust_dp'])
        xml = os.path.join(RES, 'drawable', name + '.xml')
        outs = []
        for d, s in DENS:
            px = round(box * s)
            if src.lower().endswith('.png'):
                g, c = glyph_png(src, scale, s, adjust)
            else:
                g, c = glyph_vector(src, scale, s, adjust)
            outs.append((d, compose(g, c, px, name)))
        if not args.dry_run:
            for d, im in outs:
                im.save(os.path.join(RES, 'drawable-' + d, name + '.png'), optimize=True)
            if os.path.exists(xml):
                os.remove(xml)
        done += 1
    print('rendered', done)


if __name__ == '__main__':
    main()
