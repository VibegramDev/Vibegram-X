/*
 * This file is a part of Vibegram-X
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.stars;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class PaymentWebOrigin {
  private PaymentWebOrigin () { }

  static String fromUrl (String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      int port = uri.getPort();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || port == 0 || port > 65535) {
        return null;
      }
      return "https://" + host.toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
    } catch (URISyntaxException ignored) {
      return null;
    }
  }

  static boolean isVerificationReturn (String url, boolean isMainFrame) {
    if (!isMainFrame) {
      return false;
    }
    String origin = fromUrl(url);
    return "https://t.me".equals(origin) || "https://telegram.me".equals(origin);
  }

  static String committedHost (String callbackUrl, String currentUrl) {
    String origin = fromUrl(callbackUrl);
    return origin != null && origin.equals(fromUrl(currentUrl)) ? origin.substring("https://".length()) : null;
  }

  static boolean accepts (String expectedOrigin, String sourceOrigin, boolean isMainFrame) {
    return isMainFrame && expectedOrigin != null && expectedOrigin.equals(sourceOrigin);
  }
}
