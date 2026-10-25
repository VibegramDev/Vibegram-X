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

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * WEB proxy support.
 *
 * TDLib knows nothing about WEB proxies, so inside the app one is stored as
 * {@link TdApi.Proxy}{@code (server = "host[/path]", port = 0, ProxyTypeMtproto(secret))}.
 * A real MTProto proxy never has port 0, which makes the two distinguishable.
 * Right before reaching TDLib such a proxy is replaced with a loopback MTProto proxy
 * served by {@link WebProxyTransport}.
 *
 * Link format and normalization follow the official Telegram clients.
 */
public final class WebProxy {
  public static final int PORT = 0;

  private static final String LOOPBACK = "127.0.0.1";
  private static final int UNAVAILABLE_PORT = 9; // discard: used when the transport cannot be started
  private static final int IDN_FLAGS = IDN.ALLOW_UNASSIGNED | IDN.USE_STD3_ASCII_RULES;
  private static final int PATH_SECRET_MARKER = 0x70;
  private static final Pattern PATH_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)*");
  private static final Pattern MARKED_SECRET_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

  private WebProxy () { }

  public static boolean isWeb (@Nullable TdApi.Proxy proxy) {
    return proxy != null && proxy.port == PORT && proxy.type != null && proxy.type.getConstructor() == TdApi.ProxyTypeMtproto.CONSTRUCTOR;
  }

  /**
   * @param address {@code host[/path]}
   * @param secret 32 hex digits, optionally preceded with "dd"
   * @return normalized proxy or null, if the input is invalid
   */
  public static @Nullable TdApi.Proxy create (@Nullable String address, @Nullable String secret) {
    address = normalizeAddress(address != null ? address.trim() : null);
    if (!isValidAddress(address) || !isValidSecret(secret)) {
      return null;
    }
    return new TdApi.Proxy(address, PORT, new TdApi.ProxyTypeMtproto(secret.toLowerCase(Locale.US)));
  }

  public static @NonNull String getAddress (@NonNull TdApi.Proxy proxy) {
    return proxy.server;
  }

  public static @NonNull String getSecret (@NonNull TdApi.Proxy proxy) {
    String secret = ((TdApi.ProxyTypeMtproto) proxy.type).secret;
    return secret != null ? secret : "";
  }

  // Loopback proxy

  private static final Object lock = new Object();
  private static String activeAddress, activeSecret;
  private static boolean activeHeld;

  /**
   * Makes the given proxy the active one: starts its transport, stops the previously active one.
   * Safe to call many times with the same proxy (every TDLib instance does that).
   *
   * @return proxy to be passed to TDLib: the same object for regular proxies, a loopback proxy for WEB ones
   */
  @AnyThread
  public static @Nullable TdApi.Proxy activate (@Nullable TdApi.Proxy proxy) {
    final boolean web = isWeb(proxy);
    final String address = web ? getAddress(proxy) : null;
    final String secret = web ? getSecret(proxy) : null;
    final int port;
    String stopAddress = null, stopSecret = null;
    synchronized (lock) {
      final boolean same = web && address.equals(activeAddress) && secret.equals(activeSecret);
      if (same && activeHeld) {
        port = WebProxyTransport.peekPort(address, secret);
      } else {
        if (activeHeld) {
          stopAddress = activeAddress;
          stopSecret = activeSecret;
        }
        port = web ? WebProxyTransport.acquire(address, secret) : 0;
        activeAddress = address;
        activeSecret = secret;
        activeHeld = port != 0;
      }
    }
    if (stopAddress != null) {
      WebProxyTransport.release(stopAddress, stopSecret);
    }
    return web ? loopback(proxy, port) : proxy;
  }

  /**
   * Resolves a proxy for a short-lived use (e.g. ping).
   * The returned handle must be closed when the work is done.
   */
  @AnyThread
  public static @NonNull Lease lease (@Nullable TdApi.Proxy proxy) {
    if (!isWeb(proxy)) {
      return new Lease(proxy, null, null);
    }
    final String address = getAddress(proxy);
    final String secret = getSecret(proxy);
    final int port = WebProxyTransport.acquire(address, secret);
    return new Lease(loopback(proxy, port), port != 0 ? address : null, secret);
  }

  /** Resolves a proxy without starting anything. Only suitable for validation */
  private static TdApi.Proxy loopback (@NonNull TdApi.Proxy proxy, int port) {
    return new TdApi.Proxy(LOOPBACK, port != 0 ? port : UNAVAILABLE_PORT, new TdApi.ProxyTypeMtproto(getSecret(proxy)));
  }

  public static final class Lease {
    public final @Nullable TdApi.Proxy proxy;
    private final @Nullable String address, secret;
    private boolean released;

    private Lease (@Nullable TdApi.Proxy proxy, @Nullable String address, @Nullable String secret) {
      this.proxy = proxy;
      this.address = address;
      this.secret = secret;
    }

    public synchronized void release () {
      if (!released) {
        released = true;
        if (address != null) {
          WebProxyTransport.release(address, secret);
        }
      }
    }
  }

  // Validation

  public static boolean isValidAddress (@Nullable String address) {
    if (address == null || address.isEmpty()) {
      return false;
    }
    final int slash = address.indexOf('/');
    final String host = slash >= 0 ? address.substring(0, slash) : address;
    final String path = slash >= 0 ? address.substring(slash + 1) : null;
    if (host.isEmpty() || !normalizeHost(host).equals(host) || path != null && !isValidPath(path)) {
      return false;
    }
    return true;
  }

  public static boolean isValidSecret (@Nullable String secret) {
    if (secret == null) {
      return false;
    }
    if (secret.length() == 32) {
      return isHex(secret, 0);
    }
    return secret.length() == 34 && secret.regionMatches(true, 0, "dd", 0, 2) && isHex(secret, 2);
  }

  static boolean isValidPath (@NonNull String path) {
    // The empty path means "root"
    return path.isEmpty() || path.length() <= 128 && PATH_PATTERN.matcher(path).matches();
  }

  /** @return lowercase ASCII host name, or an empty string for anything that is not a plain domain name */
  static @NonNull String normalizeHost (@Nullable String value) {
    if (value == null) {
      return "";
    }
    value = value.trim();
    if (value.endsWith(".")) {
      value = value.substring(0, value.length() - 1);
    }
    try {
      value = IDN.toASCII(value, IDN_FLAGS).toLowerCase(Locale.US);
    } catch (Exception e) {
      return "";
    }
    if (value.length() > 253 || value.indexOf('.') <= 0 || value.contains(":") || value.matches("[0-9.]+")) {
      return "";
    }
    for (String label : value.split("\\.", -1)) {
      if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
        return "";
      }
    }
    return value;
  }

  /** Canonical {@code host[/path]}. Invalid input is preserved, so that it never silently falls back to another address */
  private static String normalizeAddress (@Nullable String address) {
    if (address == null || address.isEmpty()) {
      return "";
    }
    final int slash = address.indexOf('/');
    final String host = slash >= 0 ? address.substring(0, slash) : address;
    final String path = slash >= 0 ? address.substring(slash + 1) : null;
    final String normalizedHost = normalizeHost(host);
    if (normalizedHost.isEmpty() || path != null && !isValidPath(path)) {
      return address;
    }
    return path == null ? normalizedHost : normalizedHost + '/' + path;
  }

  private static boolean hasPath (@NonNull String address) {
    return address.indexOf('/') >= 0;
  }

  private static boolean isHex (String value, int offset) {
    for (int i = offset; i < value.length(); i++) {
      if (Character.digit(value.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  // Links

  public static boolean isWebProxyLink (@Nullable String url) {
    if (url == null) {
      return false;
    }
    final String lower = url.trim().toLowerCase(Locale.US);
    return lower.startsWith("tg://webproxy") || lower.startsWith("tg:webproxy")
      || lower.matches("https?://(www\\.)?(t\\.me|telegram\\.me|telegram\\.dog|tx\\.me)/webproxy([/?#].*)?");
  }

  /** Parses {@code tg://webproxy?server=..&secret=..} and {@code https://t.me/webproxy?server=..&secret=..} */
  public static @Nullable TdApi.Proxy parseLink (@Nullable String url) {
    if (!isWebProxyLink(url)) {
      return null;
    }
    try {
      final Uri uri = Uri.parse(url.trim());
      String server = uri.getQueryParameter("server");
      if (server == null) {
        server = uri.getQueryParameter("host");
      }
      String secret = uri.getQueryParameter("secret");
      if (server == null || secret == null) {
        return null;
      }
      server = normalizeAddress(server.trim());
      if (hasPath(server)) {
        secret = decodeMarkedSecret(secret);
        if (secret == null) {
          return null;
        }
      }
      return create(server, secret);
    } catch (Exception e) {
      Log.w("Cannot parse WEB proxy link", e);
      return null;
    }
  }

  public static @NonNull String toLink (@NonNull TdApi.Proxy proxy) {
    final String address = getAddress(proxy);
    final String secret = getSecret(proxy);
    return "https://t.me/webproxy?server=" + Uri.encode(address) + "&secret=" + Uri.encode(hasPath(address) ? encodeMarkedSecret(secret) : secret);
  }

  /** With a path, the secret travels as base64url(0x70 | 16 secret bytes), so that the link cannot be mistaken for a root proxy */
  private static @Nullable String decodeMarkedSecret (@Nullable String secret) {
    if (secret == null || !MARKED_SECRET_PATTERN.matcher(secret).matches()) {
      return null;
    }
    try {
      final byte[] marked = Base64.decode(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
      if (marked.length < 17 || (marked[0] & 0xff) != PATH_SECRET_MARKER) {
        return null;
      }
      final String decoded = bytesToHex(marked, 1);
      if (!isValidSecret(decoded)) {
        return null;
      }
      // Reject padded and other non-canonical encodings of the same bytes
      final String canonical = Base64.encodeToString(marked, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
      return canonical.equals(secret) ? decoded : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String encodeMarkedSecret (String secret) {
    if (!isValidSecret(secret)) {
      return secret;
    }
    final byte[] decoded = new byte[secret.length() / 2];
    for (int i = 0; i < decoded.length; i++) {
      decoded[i] = (byte) ((Character.digit(secret.charAt(i * 2), 16) << 4) | Character.digit(secret.charAt(i * 2 + 1), 16));
    }
    final byte[] marked = new byte[decoded.length + 1];
    marked[0] = (byte) PATH_SECRET_MARKER;
    System.arraycopy(decoded, 0, marked, 1, decoded.length);
    return Base64.encodeToString(marked, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  private static String bytesToHex (byte[] value, int offset) {
    final char[] digits = "0123456789abcdef".toCharArray();
    final char[] result = new char[(value.length - offset) * 2];
    for (int i = offset; i < value.length; i++) {
      final int b = value[i] & 0xff;
      result[(i - offset) * 2] = digits[b >>> 4];
      result[(i - offset) * 2 + 1] = digits[b & 0x0f];
    }
    return new String(result);
  }
}
