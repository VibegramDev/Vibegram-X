#!/usr/bin/env python3
"""Keep repeated row draws free of resource loading and label shaping."""
from pathlib import Path
base = Path(__file__).resolve().parents[1] / 'app/src/main/java/org/thunderdog/challegram'
for name, start, banned in [('stars/StarsTransactions.java', 'class TransactionView', ('Drawables.get(',)), ('navigation/MessageSeenMenu.java', 'class HeaderView', ('TextUtils.ellipsize(', 'getFontMetrics()', 'measureText('))]:
    src = (base / name).read_text().split(start, 1)[1]
    draw = src.split('protected void onDraw (', 1)[1].split('\n    }', 1)[0]
    for op in banned:
        assert op not in draw, f'{name}: repeated {op} in onDraw'
header = (base / 'navigation/MessageSeenMenu.java').read_text().split('class HeaderView', 1)[1].split('void setViewers', 1)[1].split('int getAvatarsWidth', 1)[0]
assert 'getWindowToken() != null' in header and 'isAttachedToWindow()' not in header, 'avatar lifecycle must also work on API 16–18'
print('PASS: Stars icon and viewers label prepared outside onDraw; API 16 avatar attachment')
