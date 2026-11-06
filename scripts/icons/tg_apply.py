#!/usr/bin/env python3
"""Swap Vibegram-X icon drawables for the official Telegram for Android icons (v12.3.1).

Source: DrKLO/Telegram, commit d7deedfa ("update to 12.3.1"), the last release before the
Feb-2026 redesign. License GPL-2.0-or-later (see docs/THIRDPARTY.md).

Usage:
  git -C <Telegram clone> archive d7deedfa TMessagesProj/src/main/res | tar -x -C <dir>
  python3 scripts/icons/tg_apply.py <dir>/TMessagesProj/src/main/res [--dry-run]

Input: scripts/icons/tg-mapping.tsv (our_file, tg_name, confidence, note). Only rows with
confidence high/medium and tg_name != '-' are applied; rows whose `apply` decision below is
'keep' stay Material Symbols.

For every applied row res/drawable/<our_file>.xml is replaced (same resource name, so no code
changes) by:
  * a Telegram vector: copied, android:width/height set to our size;
  * Telegram PNG masks: res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/<our_file>.png
      - same dp size        -> Telegram's own file of that density, byte for byte;
      - Telegram glyph smaller and our box <= 40dp -> Telegram's own pixels, centred in our box
        (padding, no resampling: stays crisp, keeps Telegram's visual size);
      - Telegram glyph larger -> downscaled from xxhdpi (Lanczos) to fit our box, centred;
      - our box > 40dp and Telegram glyph smaller (would need upscaling = blur) -> keep.
Tinting is unchanged: TGX tints drawables with SRC_IN colour filters, Telegram's masks are white.
"""
import os, re, sys, shutil
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, 'app/src/main/res')
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0)]
MAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tg-mapping.tsv')


def our_size(path):
    t = open(path).read()
    w = re.search(r'android:width="([\d.]+)dp"', t)
    h = re.search(r'android:height="([\d.]+)dp"', t)
    return float(w.group(1)), float(h.group(1))


def place(img, box_w, box_h, scale=None):
    """Centre img (RGBA) in a transparent box, optionally scaling to fit."""
    if scale is not None:
        nw, nh = max(1, round(img.size[0] * scale)), max(1, round(img.size[1] * scale))
        img = img.resize((nw, nh), Image.LANCZOS)
    out = Image.new('RGBA', (box_w, box_h), (0, 0, 0, 0))
    out.paste(img, ((box_w - img.size[0]) // 2, (box_h - img.size[1]) // 2), img)
    return out


def main():
    src = sys.argv[1]
    dry = '--dry-run' in sys.argv
    rows = []
    for line in open(MAP):
        if line.startswith('#') or line.startswith('our_file') or not line.strip():
            continue
        p = (line.rstrip('\n').split('\t') + [''] * 4)[:4]
        rows.append(p)
    applied, kept = [], []
    for our, tg, conf, note in rows:
        tg = tg.strip()
        if tg == '-' or conf not in ('high', 'medium'):
            continue
        ours = os.path.join(RES, 'drawable', our + '.xml')
        if not os.path.exists(ours):
            kept.append((our, tg, 'already swapped or missing')); continue
        ow, oh = our_size(ours)
        vec = os.path.join(src, 'drawable', tg + '.xml')
        xx = os.path.join(src, 'drawable-xxhdpi', tg + '.png')
        if not os.path.exists(xx) and os.path.exists(vec):
            t = open(vec).read()
            t = re.sub(r'android:width="[\d.]+dp"', 'android:width="%gdp"' % ow, t, count=1)
            t = re.sub(r'android:height="[\d.]+dp"', 'android:height="%gdp"' % oh, t, count=1)
            t = '<!-- Telegram for Android 12.3.1 "%s" (GPL-2.0-or-later), scripts/icons/tg_apply.py -->\n' % tg + \
                re.sub(r'^<\?xml[^>]*>\s*', '', t)
            if not dry:
                open(ours, 'w').write(t)
            applied.append((our, tg, 'vector')); continue
        if not os.path.exists(xx):
            kept.append((our, tg, 'no source')); continue
        xim = Image.open(xx).convert('RGBA')
        tw, th = xim.size[0] / 3.0, xim.size[1] / 3.0
        same = abs(tw - ow) < 0.4 and abs(th - oh) < 0.4
        smaller = tw <= ow + 0.4 and th <= oh + 0.4
        if not same and smaller and max(ow, oh) > 40:
            kept.append((our, tg, 'would upscale %gdp -> %gdp' % (max(tw, th), max(ow, oh)))); continue
        mode = 'copy' if same else ('pad' if smaller else 'downscale')
        outs = []
        for d, s in DENS:
            dfile = os.path.join(src, 'drawable-' + d, tg + '.png')
            bw, bh = round(ow * s), round(oh * s)
            if mode == 'copy' and os.path.exists(dfile):
                outs.append((d, None, dfile))
            elif mode == 'pad':
                im = Image.open(dfile).convert('RGBA') if os.path.exists(dfile) else \
                    xim.resize((max(1, round(xim.size[0] * s / 3)), max(1, round(xim.size[1] * s / 3))), Image.LANCZOS)
                if im.size[0] > bw or im.size[1] > bh:  # rounding at low densities
                    im = place(im, bw, bh, min(bw / im.size[0], bh / im.size[1]))
                outs.append((d, place(im, bw, bh), None))
            else:
                k = min(bw / xim.size[0], bh / xim.size[1])
                outs.append((d, place(xim, bw, bh, k), None))
        if not dry:
            for d, im, f in outs:
                dst = os.path.join(RES, 'drawable-' + d, our + '.png')
                if f:
                    shutil.copyfile(f, dst)
                else:
                    im.save(dst, optimize=True)
            os.remove(ours)
        applied.append((our, tg, mode))
    print('applied', len(applied), 'kept', len(kept))
    from collections import Counter
    print(Counter(m for _, _, m in applied))
    for k in kept:
        print('KEEP', *k)


if __name__ == '__main__':
    main()
