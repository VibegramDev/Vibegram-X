/*
 * This file is a part of Vibegram X, a fork of Telegram X
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
package org.thunderdog.challegram.stars;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.WebkitController;

import java.util.Collections;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;

/**
 * Web pages of a payment, as in the official checkout: the provider's card form (providers without a native form:
 * the page posts «payment_form_submit» with the credentials through TelegramWebviewProxy; accepted only while the
 * message comes from the provider’s HTTPS origin in the main frame) and 3-D Secure verification. Links to
 * other apps open only after the user confirms them.
 */
public class PaymentWebController extends WebkitController<PaymentWebController.Args> {
  public static class Args {
    final String url, title;
    final boolean isVerification;
    final RunnableBool onVerificationDone;
    final StarsCardController.Callback onCredentials;

    Args (String url, String title, boolean isVerification, RunnableBool onVerificationDone, StarsCardController.Callback onCredentials) {
      this.url = url;
      this.title = title;
      this.isVerification = isVerification;
      this.onVerificationDone = onVerificationDone;
      this.onCredentials = onCredentials;
    }
  }

  /**
   * 3-D Secure: {@code onDone} receives true when the bank page finished (redirect to t.me), false when closed.
   * The redirect doesn't prove the payment: its result comes with the updates of TDLib.
   */
  public static boolean openVerification (@NonNull ViewController<?> context, @NonNull String url, @NonNull RunnableBool onDone) {
    NavigationController navigation = context.navigationController();
    if (navigation == null || navigation.getCurrentStackItem() != context || context.isStackLocked()) {
      return false;
    }
    PaymentWebController c = new PaymentWebController(context.context(), context.tdlib());
    c.setArguments(new Args(url, Lang.getString(R.string.vg_PaymentVerification), true, onDone, null));
    // Always push onto checkout, including when the last touch was in the tablet master pane.
    return navigation.navigateToFromPopup(c);
  }

  public static void openCheckoutForm (@NonNull ViewController<?> context, @NonNull String url, @NonNull String botName, @NonNull StarsCardController.Callback callback) {
    PaymentWebController c = new PaymentWebController(context.context(), context.tdlib());
    c.setArguments(new Args(url, StringUtils.isEmpty(botName) ? Lang.getString(R.string.vg_PaymentCardInfo) : botName, false, null, callback));
    context.navigateTo(c);
  }

  public PaymentWebController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgPaymentWeb;
  }

  private static final String MESSAGE_LISTENER = "VibegramPaymentBridge";
  private static final String PROXY_SCRIPT =
    "(function(){var c=window." + MESSAGE_LISTENER + ";if(window.top!==window||!c)return;" +
    "window.TelegramWebviewProxy={postEvent:function(t,d){c.postMessage(JSON.stringify([String(t),String(d)]));}};})();";

  private boolean finished, isAskingExternal;

  @SuppressLint("RestrictedApi") // DOCUMENT_START_SCRIPT is restricted in the legacy AndroidX WebKit.
  @Override
  protected void onCreateWebView (DoubleHeaderView headerCell, WebView webView) {
    Args args = getArgumentsStrict();
    headerCell.setTitle(args.title);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    }
    if (!args.isVerification) {
      String expectedOrigin = PaymentWebOrigin.fromUrl(args.url);
      if (expectedOrigin == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
          !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        headerCell.setSubtitle(Lang.getString(R.string.vg_PaymentFormUnavailable));
        return;
      }
      try {
        WebViewCompat.addWebMessageListener(webView, MESSAGE_LISTENER, Collections.singleton(expectedOrigin),
          (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            if (!isDestroyed() && !finished && PaymentWebOrigin.accepts(expectedOrigin, sourceOrigin.toString(), isMainFrame) &&
                expectedOrigin.equals(PaymentWebOrigin.fromUrl(view.getUrl()))) {
              submitCredentials(U.getWebMessageText(message));
            }
          });
        WebViewCompat.addDocumentStartJavaScript(webView, PROXY_SCRIPT, Collections.singleton(expectedOrigin));
      } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
        // Do not load the form with a partially installed or unscoped bridge.
        headerCell.setSubtitle(Lang.getString(R.string.vg_PaymentFormUnavailable));
        return;
      }
    }
    loadUrl(args.url);
  }

  private void submitCredentials (String data) {
    if (data == null) {
      return;
    }
    String credentials, title;
    try {
      JSONArray event = new JSONArray(data);
      if (!"payment_form_submit".equals(event.getString(0))) {
        return;
      }
      JSONObject json = new JSONObject(event.getString(1));
      credentials = json.getJSONObject("credentials").toString();
      title = json.optString("title", "");
    } catch (JSONException ignored) {
      return;
    }
    finished = true;
    getArgumentsStrict().onCredentials.onCardTokenized(credentials,
      StringUtils.isEmpty(title) ? Lang.getString(R.string.vg_PaymentCardGeneric) : title, false);
    navigateBack();
  }

  @Override
  protected WebViewClient createWebViewClient (DoubleHeaderView headerCell) {
    return new WebViewClient() {
      @Override
      public void onPageStarted (WebView view, String url, Bitmap favicon) {
        headerCell.setSubtitle("");
      }

      @Override
      public void onPageCommitVisible (WebView view, String url) {
        updateCommittedDocument(view, url);
      }

      @Override
      public void onPageFinished (WebView view, String url) {
        // Older WebViews have no commit callback. This callback is also restricted to the main frame.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
          updateCommittedDocument(view, url);
        }
      }

      private void updateCommittedDocument (WebView view, String url) {
        if (isDestroyed() || finished) {
          return;
        }
        String host = PaymentWebOrigin.committedHost(url, view.getUrl());
        if (host != null) {
          headerCell.setSubtitle(host);
          if (getArgumentsStrict().isVerification && PaymentWebOrigin.isVerificationReturn(url, true)) {
            finishVerification();
          }
        }
      }

      @RequiresApi(Build.VERSION_CODES.N)
      @Override
      public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        boolean mainFrame = request.isForMainFrame();
        if (PaymentWebOrigin.isVerificationReturn(uri.toString(), mainFrame)) {
          if (getArgumentsStrict().isVerification) {
            finishVerification();
          }
          return true;
        }
        if (isExternal(uri)) {
          if (mainFrame) {
            confirmExternal(uri);
          }
          return true;
        }
        return false;
      }

      @Override
      public boolean shouldOverrideUrlLoading (WebView view, String url) {
        // No frame information: neither finish verification nor open an app from here.
        return isExternal(Uri.parse(url));
      }
    };
  }

  private static boolean isExternal (Uri uri) {
    String scheme = uri.getScheme();
    return scheme != null && !"http".equalsIgnoreCase(scheme) &&
      !"https".equalsIgnoreCase(scheme) && !"about".equalsIgnoreCase(scheme);
  }

  private void confirmExternal (Uri uri) {
    // Bank apps and provider deep links must not open without user consent.
    if (!isAskingExternal && !isDestroyed() && !finished) {
      isAskingExternal = true;
      StarsUi.confirm(this, Lang.getString(R.string.OpenInExternalApp), Lang.getString(R.string.OpenThisLink, uri.toString()), Lang.getString(R.string.Open), 0, false, confirmed -> {
        isAskingExternal = false;
        if (confirmed && !isDestroyed() && !finished) {
          openExternal(uri);
        }
      });
    }
  }

  private void openExternal (Uri uri) {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, uri);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context().startActivity(intent);
    } catch (Throwable t) {
      UI.showToast(R.string.vg_PaymentAppNotFound, android.widget.Toast.LENGTH_SHORT);
    }
  }

  private void finishVerification () {
    if (finished || isDestroyed()) {
      return;
    }
    finished = true;
    if (isFocused()) {
      UI.post(this::returnAfterVerification);
    }
  }

  @Override
  public void onFocus () {
    super.onFocus();
    // The WebView starts loading before the forward transition ends.
    if (finished && getArgumentsStrict().isVerification) {
      UI.post(this::returnAfterVerification);
    }
  }

  private void returnAfterVerification () {
    NavigationController navigation = navigationController();
    if (!isDestroyed() && isFocused() && navigation != null && navigation.getCurrentStackItem() == this) {
      navigateBack();
    }
  }

  @Override
  public void destroy () {
    boolean wasDestroyed = isDestroyed();
    super.destroy();
    Args args = getArguments();
    if (!wasDestroyed && args != null && args.isVerification) {
      boolean completed = finished;
      // Let the native Back transition settle before checkout closes. Keep the Mini App below both screens.
      UI.post(() -> args.onVerificationDone.runWithBool(completed));
    }
  }
}
