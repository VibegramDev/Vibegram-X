#!/usr/bin/env python3
"""The notifications settings link must route to the same native screen as Settings."""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/org/thunderdog/challegram'
ui=(p/'telegram/TdlibUi.java').read_text().split('case TdApi.SettingsSectionNotifications.CONSTRUCTOR:',1)[1].split('break;',1)[0]
menu=(p/'ui/SettingsController.java').read_text().split('viewId == R.id.btn_notificationSettings)',1)[1].split('} else',1)[0]
assert 'new SettingsNotificationController(' in menu and 'result = new SettingsNotificationController(' in ui, 'notification section opens no screen'
print('PASS: notifications link routes to native notifications settings')
