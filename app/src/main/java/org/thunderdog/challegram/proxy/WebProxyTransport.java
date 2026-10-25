/*
 * This file is a part of Vibegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.proxy;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.util.Base64;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WEB proxy transport.
 *
 * Opens a loopback TCP server that TDLib talks to as to a regular MTProto proxy. Every accepted connection
 * becomes a stream, multiplexed over a hidden WebView that holds an HTTPS page of the proxy host.
 * The page speaks a small framed protocol through the {@code window.TelegramWebProxy} bridge
 * (the same protocol as in the official Telegram clients: HELLO/WELCOME, OPEN, DATA, WINDOW, CLOSE, PING/PONG, BYE).
 *
 * Based on the WEB proxy transport of Telegram for Android (GNU GPL v. 2 or later).
 * Only the Base64 flavour of the bridge is used, so no ArrayBuffer support is needed from the WebView.
 */
public final class WebProxyTransport {
  private static final int FRAME_OPEN = 0x01;
  private static final int FRAME_DATA = 0x02;
  private static final int FRAME_CLOSE = 0x03;
  private static final int FRAME_WINDOW = 0x04;
  private static final int FRAME_PING = 0x05;
  private static final int FRAME_PONG = 0x06;
  private static final int FRAME_HELLO = 0x10;
  private static final int FRAME_WELCOME = 0x11;
  private static final int FRAME_BYE = 0x1f;
  private static final int FRAME_HEADER = 8;
  private static final int FRAME_MAX_PAYLOAD = 1024 * 1024;
  private static final int DATA_CHUNK = 64 * 1024;
  private static final long INITIAL_WINDOW = 4L * 1024 * 1024;
  private static final int MAX_STREAMS = 64;
  private static final int MAX_OUTBOUND_ITEMS = 8192;
  private static final int MAX_OUTBOUND_BYTES = 64 * 1024 * 1024;
  private static final String BRIDGE_OBJECT = "TelegramWebProxy";

  private static final long RESTART_DELAY_MIN_MS = 1000L;
  private static final long RESTART_DELAY_MAX_MS = 30_000L;

  private static final String BASE64_PREFIX = "tproxy-base64:";
  private static final int MAX_BASE64_LENGTH = ((FRAME_HEADER + FRAME_MAX_PAYLOAD + 2) / 3) * 4;
  private static final String BASE64_BRIDGE_SCRIPT =
    "(function(){\n" +
      "'use strict';\n" +
      "try {\n" +
      " var bridge=window.TelegramWebProxy;\n" +
      " if(!bridge||typeof bridge.postMessage!=='function'||typeof bridge.onmessage!=='function')return false;\n" +
      " var nativePost=bridge.postMessage;\n" +
      " var receive=bridge.onmessage;\n" +
      " var prefix='tproxy-base64:';\n" +
      " var maxBytes=1048584;\n" +
      " var maxChars=1398112;\n" +
      " function post(value){\n" +
      "  if(value instanceof ArrayBuffer){\n" +
      "   if(value.byteLength>maxBytes)throw new Error('WEB proxy frame too large');\n" +
      "   var bytes=new Uint8Array(value),parts=[];\n" +
      "   for(var i=0;i<bytes.length;i+=8192){\n" +
      "    parts.push(String.fromCharCode.apply(null,bytes.subarray(i,Math.min(i+8192,bytes.length))));\n" +
      "   }\n" +
      "   nativePost.call(bridge,prefix+btoa(parts.join('')));\n" +
      "  }else nativePost.call(bridge,value);\n" +
      " }\n" +
      " function onmessage(event){\n" +
      "  var data=event.data;\n" +
      "  if(typeof data==='string'&&data.indexOf(prefix)===0){\n" +
      "   try {\n" +
      "    var encoded=data.substring(prefix.length);\n" +
      "    if(encoded.length>maxChars)throw new Error('WEB proxy frame too large');\n" +
      "    var binary=atob(encoded);\n" +
      "    if(binary.length>maxBytes)throw new Error('WEB proxy frame too large');\n" +
      "    var bytes=new Uint8Array(binary.length);\n" +
      "    for(var i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);\n" +
      "    receive.call(bridge,{data:bytes.buffer});\n" +
      "   }catch(error){nativePost.call(bridge,'{\\\"t\\\":\\\"close\\\"}');}\n" +
      "  }else receive.call(bridge,event);\n" +
      " }\n" +
      " bridge.postMessage=post;\n" +
      " bridge.onmessage=onmessage;\n" +
      " return bridge.postMessage===post&&bridge.onmessage===onmessage;\n" +
      "}catch(error){return false;}\n" +
      "})()\n";

  // Registry: one transport per (address, secret), reference counted

  private static final Object registryLock = new Object();
  private static final Map<String, Entry> registry = new HashMap<>();

  private static final class Entry {
    final WebProxyTransport transport;
    int refs;

    Entry (WebProxyTransport transport) {
      this.transport = transport;
    }
  }

  private static String key (String address, String secret) {
    return address + '|' + secret;
  }

  /**
   * Starts the transport (or reuses the running one) and returns the loopback port to connect to.
   * Every successful call must be balanced with {@link #release(String, String)}.
   *
   * @return port, or 0 if the transport cannot be used
   */
  @AnyThread
  public static int acquire (@NonNull String address, @NonNull String secret) {
    final Address normalized = Address.parse(address);
    final byte[] secretBytes = decodeSecret(secret);
    if (normalized == null || secretBytes == null || !isSupported()) {
      return 0;
    }
    synchronized (registryLock) {
      final String key = key(normalized.value, secret);
      Entry entry = registry.get(key);
      if (entry != null && entry.transport.serverSocket.isClosed()) {
        registry.remove(key);
        entry = null;
      }
      if (entry == null) {
        try {
          WebProxyTransport transport = new WebProxyTransport(normalized, secretBytes);
          transport.start();
          entry = new Entry(transport);
          registry.put(key, entry);
        } catch (Exception e) {
          Log.e("Cannot start WEB proxy transport", e);
          return 0;
        }
      }
      entry.refs++;
      return entry.transport.serverSocket.getLocalPort();
    }
  }

  /** @return port of the running transport, or 0 */
  @AnyThread
  static int peekPort (@NonNull String address, @NonNull String secret) {
    final Address normalized = Address.parse(address);
    if (normalized == null) {
      return 0;
    }
    synchronized (registryLock) {
      Entry entry = registry.get(key(normalized.value, secret));
      return entry != null && !entry.transport.serverSocket.isClosed() ? entry.transport.serverSocket.getLocalPort() : 0;
    }
  }

  @AnyThread
  public static void release (@NonNull String address, @NonNull String secret) {
    final Address normalized = Address.parse(address);
    if (normalized == null) {
      return;
    }
    synchronized (registryLock) {
      final String key = key(normalized.value, secret);
      Entry entry = registry.get(key);
      if (entry != null && --entry.refs <= 0) {
        registry.remove(key);
        entry.transport.stop();
      }
    }
  }

  /** The app has returned to foreground: reconnect immediately instead of waiting for the backoff timer */
  @UiThread
  public static void onForeground () {
    final ArrayList<WebProxyTransport> transports;
    synchronized (registryLock) {
      if (registry.isEmpty()) {
        return;
      }
      transports = new ArrayList<>(registry.size());
      for (Entry entry : registry.values()) {
        transports.add(entry.transport);
      }
    }
    for (WebProxyTransport transport : transports) {
      transport.retryNow();
    }
  }

  public static boolean isSupported () {
    try {
      return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
    } catch (Throwable t) {
      Log.e("WEB proxy: cannot check WebView features", t);
      return false;
    }
  }

  // Instance

  private final Object lock = new Object();
  private final String host;
  private final String bridgePath;
  private final String origin;
  private final String bridgeUrl;
  private final String androidNonce;
  private final ServerSocket serverSocket;
  private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
  private final ExecutorService carrierExecutor = Executors.newSingleThreadExecutor();
  private final AtomicInteger nextStreamId = new AtomicInteger(1);
  private final Map<Integer, Stream> streams = new HashMap<>();
  private final ArrayDeque<byte[]> outbound = new ArrayDeque<>();

  private WebView webView;
  private JavaScriptReplyProxy replyProxy;
  private boolean bridgeReady;
  private boolean base64InstallTimeoutRetried;
  private boolean carrierConnected;
  private boolean stopped;
  private boolean restartScheduled;
  private int outboundBytes;
  private long restartDelayMs = RESTART_DELAY_MIN_MS;

  private final Runnable createWebViewRunnable = this::createWebView;

  private WebProxyTransport (Address address, byte[] secretBytes) throws Exception {
    this.host = address.host;
    bridgePath = address.path.isEmpty() ? "/" : "/" + address.path + "/";
    origin = "https://" + host;
    androidNonce = randomToken(32);
    String context = address.path.isEmpty()
      ? "tdesktop-web-proxy-bridge-v1\n" + host
      : "tdesktop-web-proxy-bridge-v2\n" + host + "\n" + address.path;
    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
    String capability = Base64.encodeToString(
      hmac.doFinal(context.getBytes(StandardCharsets.UTF_8)),
      Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP
    );
    bridgeUrl = origin + bridgePath + "?bridge=" + capability + "#android=" + androidNonce;
    serverSocket = new ServerSocket(0, MAX_STREAMS, InetAddress.getByName("127.0.0.1"));
  }

  private void start () {
    ioExecutor.execute(this::acceptLoop);
    UI.post(createWebViewRunnable);
  }

  private void stop () {
    ArrayList<Stream> close;
    synchronized (lock) {
      if (stopped) {
        return;
      }
      stopped = true;
      carrierConnected = false;
      restartScheduled = false;
      close = new ArrayList<>(streams.values());
      streams.clear();
      outbound.clear();
      outboundBytes = 0;
      lock.notifyAll();
    }
    try {
      serverSocket.close();
    } catch (Exception ignore) { }
    for (Stream stream : close) {
      closeSocket(stream.socket);
    }
    UI.post(() -> {
      UI.cancel(createWebViewRunnable);
      if (replyProxy != null) {
        try {
          replyProxy.postMessage("{\"t\":\"close\"}");
        } catch (Exception ignore) { }
      }
      destroyWebView();
    });
    ioExecutor.shutdownNow();
    carrierExecutor.shutdownNow();
  }

  @UiThread
  private void retryNow () {
    synchronized (lock) {
      if (stopped || carrierConnected) {
        return;
      }
      restartDelayMs = RESTART_DELAY_MIN_MS;
    }
    UI.cancel(createWebViewRunnable);
    if (webView == null) {
      createWebView();
    }
  }

  // Loopback server

  private void acceptLoop () {
    while (true) {
      try {
        Socket socket = serverSocket.accept();
        socket.setTcpNoDelay(true);
        Stream stream;
        synchronized (lock) {
          if (stopped || streams.size() >= MAX_STREAMS) {
            closeSocket(socket);
            continue;
          }
          int streamId = allocateStreamId();
          stream = new Stream(streamId, socket);
          streams.put(streamId, stream);
          if (carrierConnected) {
            stream.opened = true;
            sendFrame(FRAME_OPEN, streamId, null);
          }
        }
        final Stream value = stream;
        ioExecutor.execute(() -> readLoop(value));
      } catch (Exception e) {
        synchronized (lock) {
          if (stopped) {
            return;
          }
        }
        Log.e("WEB proxy: accept failed", e);
        failCarrier();
        return;
      }
    }
  }

  private int allocateStreamId () {
    while (true) {
      int result = nextStreamId.getAndUpdate(value -> value >= 0x00ffffff ? 1 : value + 1);
      if (result != 0 && !streams.containsKey(result)) {
        return result;
      }
    }
  }

  private void readLoop (Stream stream) {
    byte[] buffer = new byte[DATA_CHUNK];
    try {
      InputStream input = stream.socket.getInputStream();
      while (true) {
        int allowance;
        synchronized (lock) {
          while (!stopped && streams.get(stream.id) == stream
            && (!carrierConnected || !stream.opened || stream.sendWindow == 0)) {
            lock.wait();
          }
          if (stopped || streams.get(stream.id) != stream) {
            return;
          }
          allowance = (int) Math.min(DATA_CHUNK, stream.sendWindow);
        }
        int count = input.read(buffer, 0, allowance);
        if (count < 0) {
          closeStream(stream, true);
          return;
        }
        if (count == 0) {
          continue;
        }
        byte[] data = new byte[count];
        System.arraycopy(buffer, 0, data, 0, count);
        synchronized (lock) {
          if (stopped || streams.get(stream.id) != stream || !carrierConnected) {
            return;
          }
          stream.sendWindow -= count;
          sendFrame(FRAME_DATA, stream.id, data);
        }
      }
    } catch (Exception e) {
      closeStream(stream, true);
    }
  }

  // WebView carrier

  @UiThread
  @SuppressLint("SetJavaScriptEnabled")
  private void createWebView () {
    synchronized (lock) {
      if (stopped || webView != null) {
        return;
      }
      restartScheduled = false;
    }
    destroyWebView();
    final WebView view;
    try {
      view = new WebView(AppContext.get());
    } catch (Throwable t) {
      Log.e("WEB proxy: cannot create WebView", t);
      failCarrier();
      return;
    }
    webView = view;
    bridgeReady = false;
    view.setBackgroundColor(Color.TRANSPARENT);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(false);
    settings.setDatabaseEnabled(false);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setGeolocationEnabled(false);
    settings.setMediaPlaybackRequiresUserGesture(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      settings.setSafeBrowsingEnabled(true);
    }
    view.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading (WebView current, WebResourceRequest request) {
        return !request.isForMainFrame() || !isBridgeNavigation(request.getUrl());
      }

      @Override
      public WebResourceResponse shouldInterceptRequest (WebView current, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
          && !isAllowedNetworkRequest(url)) {
          return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
      }

      @Override
      public void onReceivedSslError (WebView current, SslErrorHandler handler, SslError error) {
        handler.cancel();
        failWebView(current);
      }

      @Override
      public void onReceivedError (WebView current, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) {
          failWebView(current);
        }
      }

      @Override
      public void onReceivedHttpError (WebView current, WebResourceRequest request, WebResourceResponse response) {
        if (request.isForMainFrame()) {
          failWebView(current);
        }
      }

      @Override
      public boolean onRenderProcessGone (WebView current, RenderProcessGoneDetail detail) {
        failWebView(current);
        return true;
      }
    });
    Set<String> rules = new HashSet<>();
    rules.add(origin);
    try {
      WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, rules, this::onWebMessage);
    } catch (Throwable t) {
      Log.e("WEB proxy: cannot add message listener", t);
      failCarrier();
      return;
    }
    view.loadUrl(bridgeUrl);
  }

  private void failWebView (WebView source) {
    if (source == webView) {
      failCarrier();
    }
  }

  private boolean isBridgeNavigation (Uri value) {
    return value != null && bridgeUrl.equals(value.toString());
  }

  private boolean isAllowedNetworkRequest (Uri value) {
    String requestPath = value.getPath();
    return "https".equalsIgnoreCase(value.getScheme())
      && host.equalsIgnoreCase(value.getHost())
      && value.getUserInfo() == null
      && (value.getPort() == -1 || value.getPort() == 443)
      && requestPath != null
      && requestPath.startsWith(bridgePath);
  }

  private boolean isCurrentBridgeDocument (WebView source) {
    String currentUrl = source.getUrl();
    if (currentUrl == null) {
      return false;
    }
    Uri value = Uri.parse(currentUrl);
    return "https".equalsIgnoreCase(value.getScheme())
      && host.equalsIgnoreCase(value.getHost())
      && value.getUserInfo() == null
      && value.getPort() == -1
      && bridgePath.equals(value.getPath());
  }

  private void onWebMessage (
    @NonNull WebView sourceView,
    @NonNull WebMessageCompat message,
    @NonNull Uri sourceOrigin,
    boolean isMainFrame,
    @NonNull JavaScriptReplyProxy sourceReplyProxy
  ) {
    if (sourceView != webView
      || !isMainFrame
      || !origin.equals(sourceOrigin.toString())
      || !isCurrentBridgeDocument(sourceView)) {
      return;
    }
    final String data = message.getData();
    if (data == null) {
      return;
    }
    if (data.startsWith(BASE64_PREFIX)) {
      synchronized (lock) {
        if (stopped || !bridgeReady || replyProxy != sourceReplyProxy) {
          return;
        }
      }
      if (data.length() - BASE64_PREFIX.length() > MAX_BASE64_LENGTH) {
        failCarrier();
        return;
      }
      try {
        carrierExecutor.execute(() -> {
          try {
            byte[] decoded = Base64.decode(data.substring(BASE64_PREFIX.length()), Base64.NO_WRAP);
            if (decoded.length < FRAME_HEADER || decoded.length > FRAME_HEADER + FRAME_MAX_PAYLOAD) {
              failCarrier();
              return;
            }
            processFrames(decoded);
          } catch (IllegalArgumentException e) {
            Log.e("WEB proxy: bad frame", e);
            failCarrier();
          }
        });
      } catch (Exception ignore) { /* executor is shut down */ }
    } else {
      handleControl(data, sourceReplyProxy);
    }
  }

  private void handleControl (String data, JavaScriptReplyProxy sourceReplyProxy) {
    try {
      JSONObject object = new JSONObject(data);
      String type = object.optString("t");
      if ("tproxy-android-init".equals(type)
        && object.optInt("v") == 1
        && androidNonce.equals(object.optString("nonce"))) {
        synchronized (lock) {
          if (stopped || replyProxy != null) {
            return;
          }
          replyProxy = sourceReplyProxy;
        }
        installBase64Bridge(webView, sourceReplyProxy);
        return;
      }
      if ("close".equals(type) || "failed".equals(object.optString("state"))) {
        failCarrier();
      }
    } catch (Exception ignore) { }
  }

  private void installBase64Bridge (WebView sourceView, JavaScriptReplyProxy sourceReplyProxy) {
    // The provider has installed onmessage, but cannot start its session until HELLO.
    final Runnable timeout = () -> {
      synchronized (lock) {
        if (stopped || webView != sourceView || replyProxy != sourceReplyProxy || bridgeReady) {
          return;
        }
      }
      if (base64InstallTimeoutRetried) {
        Log.e("WEB proxy: Base64 bridge installation timed out again");
        failCarrier();
      } else {
        Log.w("WEB proxy: Base64 bridge installation timed out; retrying once");
        base64InstallTimeoutRetried = true;
        failCarrier();
      }
    };
    UI.post(timeout, 5000);
    try {
      sourceView.evaluateJavascript(BASE64_BRIDGE_SCRIPT, result -> {
        UI.cancel(timeout);
        synchronized (lock) {
          if (stopped || webView != sourceView || replyProxy != sourceReplyProxy || bridgeReady) {
            return;
          }
        }
        if (!"true".equals(result)) {
          Log.e("WEB proxy: Base64 bridge installation failed");
          failCarrier();
          return;
        }
        if (!isCurrentBridgeDocument(sourceView)) {
          failCarrier();
          return;
        }
        base64InstallTimeoutRetried = false;
        bridgeReady = true;
        Log.i("WEB proxy: bridge ready");
        sendFrame(FRAME_HELLO, 0, new byte[] {1});
      });
    } catch (Exception e) {
      UI.cancel(timeout);
      Log.e("WEB proxy: bridge installation threw an exception", e);
      failCarrier();
    }
  }

  // Frames

  private void processFrames (byte[] input) {
    int offset = 0;
    while (offset < input.length) {
      if (input.length - offset < FRAME_HEADER) {
        failCarrier();
        return;
      }
      int type = input[offset] & 0xff;
      int streamId = (input[offset + 1] & 0xff) << 16
        | (input[offset + 2] & 0xff) << 8
        | (input[offset + 3] & 0xff);
      long length = (input[offset + 4] & 0xffL) << 24
        | (input[offset + 5] & 0xffL) << 16
        | (input[offset + 6] & 0xffL) << 8
        | (input[offset + 7] & 0xffL);
      long end = offset + FRAME_HEADER + length;
      if (length > FRAME_MAX_PAYLOAD || end > input.length) {
        failCarrier();
        return;
      }
      byte[] payload = new byte[(int) length];
      System.arraycopy(input, offset + FRAME_HEADER, payload, 0, payload.length);
      if (!processFrame(type, streamId, payload)) {
        failCarrier();
        return;
      }
      offset = (int) end;
    }
  }

  private boolean processFrame (int type, int streamId, byte[] payload) {
    if (streamId == 0) {
      if (type == FRAME_WELCOME && payload.length == 0) {
        synchronized (lock) {
          if (stopped || carrierConnected) {
            return false;
          }
          carrierConnected = true;
          restartDelayMs = RESTART_DELAY_MIN_MS;
          for (Stream stream : new ArrayList<>(streams.values())) {
            stream.opened = true;
            sendFrame(FRAME_OPEN, stream.id, null);
          }
          lock.notifyAll();
        }
        Log.i("WEB proxy: carrier connected");
        return true;
      } else if (type == FRAME_PING && payload.length <= 64) {
        sendFrame(FRAME_PONG, 0, payload);
        return true;
      }
      // FRAME_BYE and everything else: drop the carrier
      return false;
    }
    final Stream stream;
    synchronized (lock) {
      stream = streams.get(streamId);
      if (stream == null) {
        return type == FRAME_DATA || type == FRAME_WINDOW || type == FRAME_CLOSE;
      }
    }
    if (type == FRAME_DATA) {
      if (payload.length == 0) {
        return false;
      }
      synchronized (lock) {
        if (stream.receiveWindow < payload.length) {
          return false;
        }
        stream.receiveWindow -= payload.length;
      }
      try {
        OutputStream output = stream.socket.getOutputStream();
        output.write(payload);
        synchronized (lock) {
          if (streams.get(stream.id) == stream) {
            stream.receiveWindow += payload.length;
            sendFrame(FRAME_WINDOW, stream.id, uint32(payload.length));
          }
        }
      } catch (Exception e) {
        closeStream(stream, true);
      }
      return true;
    } else if (type == FRAME_WINDOW && payload.length == 4) {
      long amount = ByteBuffer.wrap(payload).getInt() & 0xffffffffL;
      if (amount == 0) {
        return false;
      }
      synchronized (lock) {
        if (stream.sendWindow > 0xffffffffL - amount) {
          return false;
        }
        stream.sendWindow += amount;
        lock.notifyAll();
      }
      return true;
    } else if (type == FRAME_CLOSE && payload.length == 0) {
      closeStream(stream, false);
      return true;
    }
    return false;
  }

  private void closeStream (Stream stream, boolean notifyRelay) {
    boolean sendClose;
    synchronized (lock) {
      if (streams.get(stream.id) != stream) {
        return;
      }
      streams.remove(stream.id);
      sendClose = notifyRelay && carrierConnected && stream.opened;
      lock.notifyAll();
    }
    closeSocket(stream.socket);
    if (sendClose) {
      sendFrame(FRAME_CLOSE, stream.id, null);
    }
  }

  private void sendFrame (int type, int streamId, @Nullable byte[] payload) {
    if (payload == null) {
      payload = new byte[0];
    }
    byte[] frame = ByteBuffer.allocate(FRAME_HEADER + payload.length)
      .put((byte) type)
      .put((byte) (streamId >> 16))
      .put((byte) (streamId >> 8))
      .put((byte) streamId)
      .putInt(payload.length)
      .put(payload)
      .array();
    synchronized (lock) {
      if (stopped) {
        return;
      }
      if (outbound.size() >= MAX_OUTBOUND_ITEMS || outboundBytes > MAX_OUTBOUND_BYTES - frame.length) {
        UI.post(this::failCarrier);
        return;
      }
      outbound.add(frame);
      outboundBytes += frame.length;
    }
    UI.post(this::drainOutbound);
  }

  @UiThread
  private void drainOutbound () {
    while (true) {
      byte[] frame;
      JavaScriptReplyProxy target;
      synchronized (lock) {
        target = replyProxy;
        if (stopped || target == null || !bridgeReady || outbound.isEmpty()) {
          return;
        }
        frame = outbound.removeFirst();
        outboundBytes -= frame.length;
      }
      try {
        target.postMessage(BASE64_PREFIX + Base64.encodeToString(frame, Base64.NO_WRAP));
      } catch (Exception e) {
        Log.e("WEB proxy: cannot post frame", e);
        failCarrier();
        return;
      }
    }
  }

  private void failCarrier () {
    final ArrayList<Stream> close;
    final long delay;
    synchronized (lock) {
      if (stopped || restartScheduled) {
        return;
      }
      restartScheduled = true;
      carrierConnected = false;
      replyProxy = null;
      outbound.clear();
      outboundBytes = 0;
      close = new ArrayList<>(streams.values());
      streams.clear();
      lock.notifyAll();
      delay = restartDelayMs;
      restartDelayMs = Math.min(restartDelayMs * 2, RESTART_DELAY_MAX_MS);
    }
    for (Stream stream : close) {
      closeSocket(stream.socket);
    }
    UI.post(() -> {
      destroyWebView();
      synchronized (lock) {
        if (stopped) {
          return;
        }
      }
      UI.post(createWebViewRunnable, delay);
    });
  }

  @UiThread
  private void destroyWebView () {
    WebView value = webView;
    webView = null;
    replyProxy = null;
    bridgeReady = false;
    if (value != null) {
      try {
        value.stopLoading();
        value.loadUrl("about:blank");
        value.removeAllViews();
        value.destroy();
      } catch (Exception e) {
        Log.e("WEB proxy: cannot destroy WebView", e);
      }
    }
  }

  // Utils

  /** @return 16 bytes, or 17 bytes with the leading 0xdd, or null */
  static @Nullable byte[] decodeSecret (@Nullable String value) {
    if (value == null) {
      return null;
    }
    value = value.trim();
    final byte[] result;
    if ((value.length() == 32 || value.length() == 34) && value.matches("[0-9a-fA-F]+")) {
      result = new byte[value.length() / 2];
      for (int i = 0; i < result.length; i++) {
        result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
      }
    } else {
      try {
        return validateSecretBytes(Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP));
      } catch (Exception e) {
        return null;
      }
    }
    return validateSecretBytes(result);
  }

  private static @Nullable byte[] validateSecretBytes (byte[] result) {
    if (result.length == 16 || result.length == 17 && (result[0] & 0xff) == 0xdd) {
      return result;
    }
    return null;
  }

  private static String randomToken (int size) {
    byte[] bytes = new byte[size];
    new SecureRandom().nextBytes(bytes);
    return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
  }

  private static byte[] uint32 (int value) {
    return ByteBuffer.allocate(4).putInt(value).array();
  }

  private static void closeSocket (Socket socket) {
    try {
      socket.close();
    } catch (Exception ignore) { }
  }

  private static final class Stream {
    final int id;
    final Socket socket;
    long sendWindow = INITIAL_WINDOW;
    long receiveWindow = INITIAL_WINDOW;
    boolean opened;

    Stream (int id, Socket socket) {
      this.id = id;
      this.socket = socket;
    }
  }

  /** {@code host[/path]} */
  static final class Address {
    final String host;
    final String path;
    final String value;

    private Address (String host, String path) {
      this.host = host;
      this.path = path;
      this.value = path.isEmpty() ? host : host + "/" + path;
    }

    static @Nullable Address parse (@Nullable String value) {
      if (value == null) {
        return null;
      }
      int slash = value.indexOf('/');
      String host = WebProxy.normalizeHost(slash >= 0 ? value.substring(0, slash) : value);
      String path = slash >= 0 ? value.substring(slash + 1) : "";
      if (host.isEmpty() || !WebProxy.isValidPath(path)) {
        return null;
      }
      return new Address(host, path);
    }
  }
}
