#!/usr/bin/env python3
"""Guard against reintroducing automatic echo/watchdog diagnostics into app sources."""
from pathlib import Path

src = Path(__file__).resolve().parents[1] / 'app/src/main/java'
markers = ('RoundEchoWatch', 'StuckWatch', '"VGECHO"', '"VGSTUCK"')
violations = []
for path in src.rglob('*'):
    if path.suffix not in ('.java', '.kt'):
        continue
    text = path.read_text()
    for marker in markers:
        if marker in text:
            violations.append(f'{path.relative_to(src)}: {marker}')
assert not violations, '\n' + '\n'.join(violations)
print('PASS no retired automatic diagnostics in app sources')
