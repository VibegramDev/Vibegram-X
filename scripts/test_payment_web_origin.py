#!/usr/bin/env python3
"""Run the production payment-origin policy on the JVM and check its WebView wiring."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/org/thunderdog/challegram/stars'
src = (base / 'PaymentWebController.java').read_text()
assert 'addJavascriptInterface(' not in src, 'payment checkout must not expose a global bridge'
assert 'MIXED_CONTENT_NEVER_ALLOW' in src
assert 'Collections.singleton(expectedOrigin)' in src
assert 'PaymentWebOrigin.accepts(expectedOrigin, sourceOrigin.toString(), isMainFrame)' in src
assert src.index('WebViewCompat.addDocumentStartJavaScript') < src.index('loadUrl(args.url)')
assert 'stringMessage = message.getData()' in src and 'catch (IllegalStateException ignored)' in src, 'ignore non-string WebView messages (including ArrayBuffers)'

assert 'boolean mainFrame = request.isForMainFrame()' in src
assert 'PaymentWebOrigin.isVerificationReturn(uri.toString(), mainFrame)' in src
legacy = src.split('shouldOverrideUrlLoading (WebView view, String url)', 1)[1].split('    };', 1)[0]
assert 'finishVerification(' not in legacy and 'confirmExternal(' not in legacy
assert 'onPageCommitVisible (WebView view, String url)' in src
assert 'PaymentWebOrigin.committedHost(url, view.getUrl())' in src
assert 'headerCell.setSubtitle("")' in src.split('onPageStarted', 1)[1].split('onPageCommitVisible', 1)[0]

probe = '''package org.thunderdog.challegram.stars;
class OriginProbe {
  static void check(boolean pass, String name) { if (!pass) throw new AssertionError(name); }
  public static void main(String[] args) {
    String origin = PaymentWebOrigin.fromUrl("HTTPS://PAY.EXAMPLE:443/card?session=1");
    check("https://pay.example".equals(origin), "canonical HTTPS origin");
    check(PaymentWebOrigin.accepts(origin, "https://pay.example", true), "provider main frame");
    check(!PaymentWebOrigin.accepts(origin, "https://pay.example", false), "child frame excluded");
    check(!PaymentWebOrigin.accepts(origin, "https://other.example", true), "other origin excluded");
    check(!PaymentWebOrigin.accepts(origin, "https://sub.pay.example", true), "subdomain excluded");
    check(!PaymentWebOrigin.accepts(origin, "https://pay.example:8443", true), "other port excluded");
    check(!PaymentWebOrigin.accepts(origin, "http://pay.example", true), "non-HTTPS excluded");
    check(!PaymentWebOrigin.accepts(null, "https://pay.example", true), "missing initial origin excluded");
    check(PaymentWebOrigin.fromUrl("https://user@pay.example/card") == null, "user info rejected");
    check(PaymentWebOrigin.fromUrl("https://pay.example:99999/card") == null, "invalid port rejected");
    check("https://pay.example:8443".equals(PaymentWebOrigin.fromUrl("https://pay.example:8443/card")), "explicit port preserved");
    check(PaymentWebOrigin.isVerificationReturn("https://t.me/", true), "known HTTPS return in main frame");
    check(PaymentWebOrigin.isVerificationReturn("https://telegram.me/", true), "second known HTTPS return");
    check(!PaymentWebOrigin.isVerificationReturn("https://t.me/", false), "return checks frame scope");
    check(!PaymentWebOrigin.isVerificationReturn("http://t.me/", true), "return requires TLS");
    check(!PaymentWebOrigin.isVerificationReturn("https://t.me:8443/", true), "return uses the known port");
    check(!PaymentWebOrigin.isVerificationReturn("https://bank.example/", true), "bank navigation is not a return");
    check("bank.example".equals(PaymentWebOrigin.committedHost("https://bank.example/new", "https://bank.example/new")), "header reflects committed bank origin");
    check(PaymentWebOrigin.committedHost("https://pay.example/old", "https://bank.example/new") == null, "old callback cannot label a different bank page");
    check(PaymentWebOrigin.committedHost("about:blank", "about:blank") == null, "no label for a non-HTTPS page");
    System.out.println("PASS payment origin, verified return policy and committed header (20 cases)");
  }
}
'''
with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'), prefix='vg-payment-origin-') as tmp:
    path = Path(tmp) / 'OriginProbe.java'
    path.write_text(probe)
    subprocess.run(['javac', '-d', tmp, str(base / 'PaymentWebOrigin.java'), str(path)], check=True)
    subprocess.run(['java', '-cp', tmp, 'org.thunderdog.challegram.stars.OriginProbe'], check=True)
