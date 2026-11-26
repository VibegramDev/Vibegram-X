#!/usr/bin/env python3
"""Release contracts shared by the app's three text-only WebView bridges."""
from pathlib import Path
import re
import subprocess
import tempfile

base = Path(__file__).resolve().parents[1] / 'app/src/main/java/org/thunderdog/challegram'
bridge = (base / 'webapp/WebAppBridge.java').read_text()
app = (base / 'webapp/WebAppController.java').read_text()
assert 'webView.addJavascriptInterface(' not in bridge, 'no global fallback when scoped WebKit is unavailable'
assert '!bridge.attachTo(' in app and 'return;' in app.split('!bridge.attachTo(', 1)[1].split('webView.loadUrl', 1)[0]
assert 'requestWebAppPlaceholder(' in app and 'new TdApi.GetWebAppPlaceholder(' not in app
assert 'isMainFrame' in bridge and 'WebViewCompat.removeWebMessageListener' in bridge
for path in ['webapp/WebAppBridge.java', 'proxy/WebProxyTransport.java', 'stars/PaymentWebController.java']:
    assert 'U.getWebMessageText(message)' in (base / path).read_text(), path
source = (base / 'U.java').read_text()
match = re.search(r'public static (?:@Nullable )?String getWebMessageText .*?\n  }', source, re.S)
assert match
method = match.group()
with tempfile.TemporaryDirectory() as tmp:
    f = Path(tmp) / 'Probe.java'
    f.write_text('''class Probe { @interface Nullable {} @interface NonNull {}
      static class WebMessageCompat { boolean text; String getData() { if (!text) throw new IllegalStateException(); return "ok"; } }
      ''' + method + '''
      public static void main(String[] args) { var m = new WebMessageCompat();
        if (getWebMessageText(m) != null) throw new AssertionError();
        m.text=true; if (!"ok".equals(getWebMessageText(m))) throw new AssertionError();
      } }''')
    subprocess.run(['javac', str(f)], check=True)
    subprocess.run(['java', '-cp', tmp, 'Probe'], check=True)
print('PASS: text-only callbacks, scoped Mini App bridge, shared outline request')
