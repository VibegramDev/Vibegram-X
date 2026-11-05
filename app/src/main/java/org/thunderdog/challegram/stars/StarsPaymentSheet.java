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

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.AvatarView;
import org.thunderdog.challegram.widget.VgSheets;

import me.vkryl.core.StringUtils;

/**
 * Payments in Telegram Stars, as in the official app: «Confirm Your Purchase» with the balance, the product and
 * «Confirm and Pay ⭐ N». Vibegram X asks once more before paying ({@link StarsUi#confirmAgain}).
 *
 * {@link #open} is the entry point of every payment (bot «Pay» buttons, invoice links): Stars invoices open this
 * sheet, invoices in money open the checkout screen ({@link StarsCheckoutController}).
 */
public final class StarsPaymentSheet {
  private StarsPaymentSheet () { }

  /** Loads the payment form of the invoice and opens the matching payment UI. */
  public static void open (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.InputInvoice invoice, @Nullable Runnable onPaid, @Nullable Runnable onFailed) {
    tdlib.send(new TdApi.GetPaymentForm(invoice, null), (form, error) -> UI.post(() -> {
      if (context.isDestroyed()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
        if (onFailed != null) {
          onFailed.run();
        }
        return;
      }
      switch (form.type.getConstructor()) {
        case TdApi.PaymentFormTypeStars.CONSTRUCTOR: {
          show(context, tdlib, invoice, form, ((TdApi.PaymentFormTypeStars) form.type).starCount, onPaid);
          break;
        }
        case TdApi.PaymentFormTypeStarSubscription.CONSTRUCTOR: {
          show(context, tdlib, invoice, form, ((TdApi.PaymentFormTypeStarSubscription) form.type).pricing.starCount, onPaid);
          break;
        }
        case TdApi.PaymentFormTypeRegular.CONSTRUCTOR: {
          StarsCheckoutController.open(context, tdlib, invoice, form, null, onPaid);
          break;
        }
      }
      if (onFailed != null) {
        // The UI is shown: the caller's progress can stop.
        onFailed.run();
      }
    }));
  }

  private static void show (ViewController<?> context, Tdlib tdlib, TdApi.InputInvoice invoice, TdApi.PaymentForm form, long starCount, @Nullable Runnable onPaid) {
    final boolean isSubscription = form.type.getConstructor() == TdApi.PaymentFormTypeStarSubscription.CONSTRUCTOR;
    final String botName = form.sellerBotUserId != 0 ? tdlib.cache().userName(form.sellerBotUserId) : "";
    final String productTitle = form.productInfo != null ? form.productInfo.title : "";
    final boolean[] paying = new boolean[1];
    VgSheets.showSheet(context, popupLayout -> {
      Context ctx = context.context();
      LinearLayout content = VgSheets.newContent(ctx);

      // Balance (top right, as the balance cloud of the official app)
      LinearLayout balance = new LinearLayout(ctx);
      balance.setOrientation(LinearLayout.VERTICAL);
      balance.setGravity(Gravity.END);
      TextView balanceAmount = VgSheets.newBody(ctx, StarsUi.ownBalance(tdlib), false);
      balanceAmount.setTypeface(Fonts.getRobotoMedium());
      balanceAmount.setGravity(Gravity.END);
      balance.addView(balanceAmount);
      TextView balanceLabel = VgSheets.newCaption(ctx, Lang.getString(R.string.vg_StarsBalance), false);
      balanceLabel.setGravity(Gravity.END);
      balance.addView(balanceLabel);
      LinearLayout.LayoutParams balanceParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      balanceParams.gravity = Gravity.END;
      content.addView(balance, balanceParams);

      // Product photo or the bot
      FrameLayout imageWrap = new FrameLayout(ctx);
      View photoView = StarsUi.newPhotoView(ctx, tdlib, form.productInfo != null ? form.productInfo.photo : null, 96f, 16f);
      if (photoView != null) {
        imageWrap.addView(photoView, new FrameLayout.LayoutParams(Screen.dp(96f), Screen.dp(96f)));
      } else {
        AvatarView avatarView = new AvatarView(ctx);
        avatarView.setUser(tdlib, form.sellerBotUserId, false);
        imageWrap.addView(avatarView, new FrameLayout.LayoutParams(Screen.dp(96f), Screen.dp(96f)));
      }
      View badge = StarsUi.newStarIcon(ctx, 28f);
      FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(Screen.dp(28f), Screen.dp(28f), Gravity.BOTTOM | Gravity.END);
      imageWrap.addView(badge, badgeParams);
      content.addView(imageWrap, VgSheets.centered(Screen.dp(100f), 0, 16f));

      TextView titleView = VgSheets.newTitle(ctx, Lang.getString(isSubscription ? R.string.vg_StarsConfirmSubscriptionTitle : R.string.vg_StarsConfirmPurchaseTitle));
      content.addView(titleView, VgSheets.wide(0, 8f));

      CharSequence text = VgSheets.markdown(isSubscription ?
        Lang.plural(R.string.vg_StarsConfirmSubscriptionText, starCount, productTitle, botName) :
        Lang.plural(R.string.vg_StarsConfirmPurchaseText, starCount, productTitle, botName));
      content.addView(VgSheets.newBody(ctx, text, true), VgSheets.wide(0, 20f));

      CharSequence label = Stars.withIcons(isSubscription ?
        Lang.plural(R.string.vg_StarsConfirmSubscriptionButton, starCount) :
        Lang.plural(R.string.vg_StarsConfirmPurchaseButton, starCount));
      TextView button = VgSheets.newFilledButton(ctx, label, v -> {
        if (paying[0]) {
          return;
        }
        StarsUi.confirmAgain(context, Lang.getString(R.string.vg_StarsConfirmAgainPay, Stars.format(starCount), botName), Lang.getString(R.string.vg_StarsPay), confirmed -> {
          if (!confirmed || paying[0]) {
            return;
          }
          if (!StarsUi.checkBalance(context, tdlib, starCount, botName, false)) {
            popupLayout.hideWindow(true);
            return;
          }
          paying[0] = true;
          VgSheets.setButtonEnabled(v, false);
          tdlib.send(new TdApi.SendPaymentForm(invoice, form.id, "", "", null, 0), (result, error) -> UI.post(() -> {
            paying[0] = false;
            popupLayout.hideWindow(true);
            if (error != null) {
              UI.showError(error);
              return;
            }
            if (!StringUtils.isEmpty(result.verificationUrl)) {
              StarsUi.openUrl(context, result.verificationUrl);
            }
            showPurchaseCompleted(productTitle, botName, starCount, isSubscription);
            if (onPaid != null) {
              onPaid.run();
            }
          }));
        });
      });
      content.addView(button, VgSheets.buttonParams(0));

      TextView terms = VgSheets.newCaption(ctx, StarsUi.linkified(context, Lang.getString(isSubscription ? R.string.vg_StarsConfirmSubscriptionTOS : R.string.vg_StarsConfirmPurchaseTOS), StarsUi.TERMS_URL), true);
      VgSheets.setLinksClickable(terms);
      content.addView(terms, VgSheets.wide(12f, 0));
      return content;
    });
  }

  private static void showPurchaseCompleted (String productTitle, String botName, long starCount, boolean isSubscription) {
    String title = Lang.getString(isSubscription ? R.string.vg_StarsSubscriptionCompleted : R.string.vg_StarsPurchaseCompleted);
    String text = isSubscription ?
      Lang.plural(R.string.vg_StarsSubscriptionCompletedText, starCount, botName) :
      Lang.plural(R.string.vg_StarsPurchaseCompletedInfo, starCount, productTitle, botName);
    UI.showToast(title + "\n" + text.replace("**", ""), Toast.LENGTH_LONG);
  }
}
