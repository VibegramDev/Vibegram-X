#!/usr/bin/env python3
"""Compile actual bridge installation, removal and gesture gating against an inert WebKit provider."""
from pathlib import Path
import re, subprocess, tempfile
src = (Path(__file__).resolve().parents[1] / 'app/src/main/java/org/thunderdog/challegram/webapp/WebAppBridge.java').read_text()
methods = []
for name in ('attachTo', 'detachFrom', 'consumeUserGesture', 'onUserInteraction'):
    m = re.search(r'^  (?:void|boolean) '+name+r' \([^\n]*\) \{.*?^  }', src, re.M | re.S)
    assert m, name
    methods.append(m.group())
fixture = '''import java.util.*;
class BridgeProbe {
 @interface Nullable {} static class WebView {}
 static class SystemClock { static long time; static long uptimeMillis(){return time;} }
 static class Log { static void w(String msg, Throwable t){} }
 static class WebViewFeature { static int WEB_MESSAGE_LISTENER=1,DOCUMENT_START_SCRIPT=2, mask;
   static boolean isFeatureSupported(int flag){return (mask & flag)!=0;} }
 static class WebViewCompat { static int listeners; static boolean rejectScript; static Set<String> expected;
   static void addWebMessageListener(WebView v,String n,Set<String> rules,Runnable callback){check(expected.equals(rules));check(++listeners==1);}
   static void addDocumentStartJavaScript(WebView v,String script,Set<String> rules){check(expected.equals(rules));if(rejectScript)throw new IllegalArgumentException();}
   static void removeWebMessageListener(WebView v,String n){check(--listeners==0);} }
 boolean isAttached; String trustedOrigin; String LISTENER_NAME="listener", PROXY_SCRIPT="script";
 long lastUserGestureMs; static final long USER_GESTURE_TIMEOUT_MS=10000; void onWebMessage(){}
 static void check(boolean b){if(!b)throw new AssertionError();}
''' + '\n'.join(methods) + '''
 public static void main(String[] args){BridgeProbe bridge=new BridgeProbe();WebView v=new WebView();
   for(int flags=0;flags<3;flags++){WebViewFeature.mask=flags;check(!bridge.attachTo(v,"https://app.invalid"));check(!bridge.isAttached && WebViewCompat.listeners==0);}
   WebViewFeature.mask=3; WebViewCompat.expected=Set.of("https://app.invalid"); WebViewCompat.rejectScript=true;
   check(!bridge.attachTo(v,"https://app.invalid"));check(!bridge.isAttached && WebViewCompat.listeners==0);
   WebViewCompat.rejectScript=false;check(bridge.attachTo(v,"https://app.invalid"));check(bridge.attachTo(v,"https://app.invalid"));
   bridge.detachFrom(v);bridge.detachFrom(v);check(WebViewCompat.listeners==0);
   WebViewCompat.expected=Set.of("*");check(bridge.attachTo(v,null));bridge.detachFrom(v);
   SystemClock.time=3;check(!bridge.consumeUserGesture());bridge.onUserInteraction();check(bridge.consumeUserGesture());check(!bridge.consumeUserGesture());
   bridge.onUserInteraction();SystemClock.time+=10001;check(!bridge.consumeUserGesture());
   System.out.println("PASS: missing provider capabilities, partial registration cleanup, reuse, detach, gestures");
 }}'''
with tempfile.TemporaryDirectory() as d:
    f=Path(d)/'BridgeProbe.java'; f.write_text(fixture)
    subprocess.run(['javac', str(f)], check=True)
    subprocess.run(['java','-cp',d,'BridgeProbe'], check=True)
