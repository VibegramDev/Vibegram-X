#!/usr/bin/env python3
"""Account settings own the editable username row; root Settings only displays the current user's handle."""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/org/thunderdog/challegram/ui'
main=(p/'SettingsController.java').read_text();account=(p/'SettingsAccountController.java').read_text()
assert 'R.id.btn_username' not in main and 'setUsername (' not in main, 'root screen duplicates the account editor it no longer creates'
assert 'R.id.btn_username' in account and 'R.id.btn_vgAccount' in main and 'new SettingsAccountController(' in main
print('PASS: username row belongs to account settings, root header keeps no duplicate username state')
