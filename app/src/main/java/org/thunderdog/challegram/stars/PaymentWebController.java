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
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.WebkitController;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;

/**
 * Web pages of a payment, as in the official checkout: the provider's card form (providers without a native form:
 * the page posts «payment_form_submit» with the credentials through TelegramWebviewProxy) and 3-D Secure
 * verification (finished when the page redirects to t.me).
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

  /** 3-D Secure: {@code onDone} receives true when the bank page finished (redirect to t.me), false when closed. */
  public static void openVerification (@NonNull ViewController<?> context, @NonNull String url, @NonNull RunnableBool onDone) {
    PaymentWebController c = new PaymentWebController(context.context(), context.tdlib());
    c.setArguments(new Args(url, Lang.getString(R.string.vg_PaymentVerification), true, onDone, null));
    context.navigateTo(c);
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

  private boolean finished;

  @SuppressLint({"AddJavascriptInterface", "JavascriptInterface"})
  @Override
  protected void onCreateWebView (DoubleHeaderView headerCell, WebView webView) {
    Args args = getArgumentsStrict();
    headerCell.setTitle(args.title);
    Uri uri = Uri.parse(args.url);
    if (uri.getHost() != null) {
      headerCell.setSubtitle(uri.getHost());
    }
    if (!args.isVerification) {
      webView.addJavascriptInterface(new TelegramWebviewProxy(), "TelegramWebviewProxy");
    }
    loadUrl(args.url);
  }

  private final class TelegramWebviewProxy {
    @Keep
    @JavascriptInterface
    public void postEvent (final String eventName, final String eventData) {
      UI.post(() -> {
        if (isDestroyed() || finished || !"payment_form_submit".equals(eventName)) {
          return;
        }
        String credentials, title;
        try {
          JSONObject json = new JSONObject(eventData);
          credentials = json.getJSONObject("credentials").toString();
          title = json.optString("title", "");
        } catch (Throwable t) {
          credentials = eventData;
          title = "";
        }
        finished = true;
        Args args = getArgumentsStrict();
        args.onCredentials.onCardTokenized(credentials, StringUtils.isEmpty(title) ? Lang.getString(R.string.vg_PaymentCardGeneric) : title, false);
        navigateBack();
      });
    }
  }

  @Override
  protected boolean hasSpecialProcessing () {
    return true;
  }

  @Override
  protected boolean processSpecial (Uri uri) {
    Args args = getArgumentsStrict();
    if ("t.me".equals(uri.getHost()) || "telegram.me".equals(uri.getHost())) {
      if (args.isVerification) {
        finishVerification(true);
      }
      return true;
    }
    String scheme = uri.getScheme();
    if (scheme != null && !"http".equals(scheme) && !"https".equals(scheme) && !"about".equals(scheme)) {
      // Bank apps of 3-D Secure and payment pages (deep links).
      try {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context().startActivity(intent);
      } catch (Throwable t) {
        UI.showToast(R.string.vg_PaymentAppNotFound, android.widget.Toast.LENGTH_SHORT);
      }
      return true;
    }
    return false;
  }

  private void finishVerification (boolean completed) {
    if (finished) {
      return;
    }
    finished = true;
    ViewController<?> previous = navigationController != null ? navigationController.getPreviousStackItem() : null;
    if (previous instanceof StarsCheckoutController) {
      destroyPreviousStackItem();
    }
    navigateBack();
    getArgumentsStrict().onVerificationDone.runWithBool(completed);
  }

  @Override
  public void destroy () {
    super.destroy();
    Args args = getArguments();
    if (args != null && args.isVerification && !finished) {
      // Closed before the bank page has finished: the payment may still be processed.
      finished = true;
      args.onVerificationDone.runWithBool(false);
    }
  }
}
