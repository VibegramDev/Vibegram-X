#!/usr/bin/env python3
"""Verified badge of the chat list composed like Telegram for Android 12.3.1 draws it.

Telegram draws the badge in two layers: verified_area (the rosette, tinted) and verified_check over it (the tick,
drawn in the background colour). tg_apply.py copied only verified_area into deproko_baseline_verify_chat_24, and TGX
draws that one drawable with a single tint, so the badge was a white rosette without a tick. The tick is cut out of
the rosette here instead (alpha of the area * (1 - alpha of the check)), so it shows the row background in any theme.
The check is anchored at the same top-left as the area, as Telegram draws both with the same bounds.

Usage: python3 scripts/icons/tg_verify.py <Telegram 12.3.1 res dir>   (run after tg_apply.py)
"""
import os, sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, 'app/src/main/res')
OUR = 'deproko_baseline_verify_chat_24'


def main():
    src = sys.argv[1]
    for d in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi'):
        ours = os.path.join(RES, 'drawable-' + d, OUR + '.png')
        area = Image.open(os.path.join(src, 'drawable-' + d, 'verified_area.png')).convert('RGBA')
        check = Image.open(os.path.join(src, 'drawable-' + d, 'verified_check.png')).convert('RGBA')
        im = Image.open(ours).convert('RGBA')
        ox = im.getchannel('A').getbbox()[0] - area.getchannel('A').getbbox()[0]
        oy = im.getchannel('A').getbbox()[1] - area.getchannel('A').getbbox()[1]
        px = im.load()
        for y in range(check.height):
            for x in range(check.width):
                ca = check.getpixel((x, y))[3]
                if ca and 0 <= x + ox < im.width and 0 <= y + oy < im.height:
                    r, g, b, a = px[x + ox, y + oy]
                    px[x + ox, y + oy] = (r, g, b, round(a * (255 - ca) / 255))
        im.save(ours, optimize=True)
        print(d, (ox, oy))


if __name__ == '__main__':
    main()
