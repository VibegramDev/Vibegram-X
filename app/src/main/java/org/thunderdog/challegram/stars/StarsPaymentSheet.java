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

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.widget.M3Sheets;
import org.thunderdog.challegram.widget.PopupLayout;

import java.util.ArrayList;

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
    final PopupLayout[] popup = new PopupLayout[1];
    final SettingsAdapter[] adapterRef = new SettingsAdapter[1];
    SettingsAdapter adapter = new SettingsAdapter(context, v -> {
      if (v.getId() != R.id.btn_vgStarsPay || paying[0]) {
        return;
      }
      StarsUi.confirmAgain(context, Lang.getString(R.string.vg_StarsConfirmAgainPay, Stars.format(starCount), botName), Lang.getString(R.string.vg_StarsPay), confirmed -> {
        if (!confirmed || paying[0]) {
          return;
        }
        if (!StarsUi.checkBalance(context, tdlib, starCount, botName, false)) {
          if (popup[0] != null) {
            popup[0].hideWindow(true);
          }
          return;
        }
        paying[0] = true;
        adapterRef[0].updateValuedSettingById(R.id.btn_vgStarsPay);
        tdlib.send(new TdApi.SendPaymentForm(invoice, form.id, "", "", null, 0), (result, error) -> UI.post(() -> {
          paying[0] = false;
          if (popup[0] != null) {
            popup[0].hideWindow(true);
          }
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
    }, context) {
      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        if (!view.setPhoto(tdlib, form.productInfo != null ? form.productInfo.photo : null, 96f, 16f)) {
          view.setAvatar(tdlib, new TdApi.MessageSenderUser(form.sellerBotUserId), 96f);
        }
        view.setTexts(tdlib,
          Lang.getString(isSubscription ? R.string.vg_StarsConfirmSubscriptionTitle : R.string.vg_StarsConfirmPurchaseTitle),
          StarsUi.markdownText(isSubscription ?
            Lang.plural(R.string.vg_StarsConfirmSubscriptionText, starCount, productTitle, botName) :
            Lang.plural(R.string.vg_StarsConfirmPurchaseText, starCount, productTitle, botName)));
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgStarsBalance) {
          view.setData(StarsUi.ownBalance(tdlib));
        }
      }

      @Override
      protected void setM3Button (ListItem item, M3ButtonView view, boolean isUpdate) {
        super.setM3Button(item, view, isUpdate);
        view.setEnabled(!paying[0]);
      }
    };
    adapterRef[0] = adapter;
    adapter.setSegmented();
    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgStarsBalance, R.drawable.baseline_account_balance_wallet_24, R.string.vg_StarsBalance));
    items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgStarsPay, 0, isSubscription ?
      Lang.plural(R.string.vg_StarsConfirmSubscriptionButton, starCount) :
      Lang.plural(R.string.vg_StarsConfirmPurchaseButton, starCount), false));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION_CENTERED, 0, 0, StarsUi.linkified(context, Lang.getString(isSubscription ? R.string.vg_StarsConfirmSubscriptionTOS : R.string.vg_StarsConfirmPurchaseTOS), StarsUi.TERMS_URL), false));
    adapter.setItems(items, false);
    popup[0] = M3Sheets.showList(context, adapter);
  }

  private static void showPurchaseCompleted (String productTitle, String botName, long starCount, boolean isSubscription) {
    String title = Lang.getString(isSubscription ? R.string.vg_StarsSubscriptionCompleted : R.string.vg_StarsPurchaseCompleted);
    String text = isSubscription ?
      Lang.plural(R.string.vg_StarsSubscriptionCompletedText, starCount, botName) :
      Lang.plural(R.string.vg_StarsPurchaseCompletedInfo, starCount, productTitle, botName);
    UI.showToast(title + "\n" + text.replace("**", ""), Toast.LENGTH_LONG);
  }
}
