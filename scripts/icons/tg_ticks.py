#!/usr/bin/env python3
"""Message status ticks composed exactly like Telegram for Android 12.3.1 draws them.

Telegram has no single "double tick" bitmap for bubbles: ChatMessageCell draws msg_check_s (12dp) and
msg_halfcheck (12dp) with the half check 4.5dp to the right (layoutWidth - 22.5dp vs - 18dp), and the
unread single check at - 18.5dp, i.e. under the right-hand tick of the pair. TGX draws one drawable
(deproko_baseline_check_single_24 / _double_24) at the same position for both states, so both are
composed into the same 24dp box with the same scale and the same anchor:
  double = msg_check_s at x0 + msg_halfcheck at x0 + 4.5dp
  single = msg_check_s at x0 + 4.0dp
x0 centres the pair horizontally; the 12dp drawables are centred vertically.
The "Read at" menu item keeps Telegram's msg_seen as vg_menu_seen_24.

Usage: python3 scripts/icons/tg_ticks.py <Telegram 12.3.1 res dir>
"""
import os, shutil, sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, 'app/src/main/res')
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0)]


def main():
    src = sys.argv[1]
    for d, s in DENS:
        check = Image.open(os.path.join(src, 'drawable-' + d, 'msg_check_s.png')).convert('RGBA')
        half = Image.open(os.path.join(src, 'drawable-' + d, 'msg_halfcheck.png')).convert('RGBA')
        box = round(24 * s)
        shift = round(4.5 * s)
        pair_w = check.size[0] + shift
        x0 = (box - pair_w) // 2
        y0 = (box - check.size[1]) // 2
        double = Image.new('RGBA', (box, box), (0, 0, 0, 0))
        double.alpha_composite(check, (x0, y0))
        double.alpha_composite(half, (x0 + shift, y0))
        single = Image.new('RGBA', (box, box), (0, 0, 0, 0))
        single.alpha_composite(check, (x0 + round(4.0 * s), y0))
        out = os.path.join(RES, 'drawable-' + d)
        double.save(os.path.join(out, 'deproko_baseline_check_double_24.png'), optimize=True)
        single.save(os.path.join(out, 'deproko_baseline_check_single_24.png'), optimize=True)
        shutil.copyfile(os.path.join(src, 'drawable-' + d, 'msg_seen.png'), os.path.join(out, 'vg_menu_seen_24.png'))
        if d == 'xxhdpi':
            print('double', double.getbbox(), 'single', single.getbbox())


if __name__ == '__main__':
    main()
