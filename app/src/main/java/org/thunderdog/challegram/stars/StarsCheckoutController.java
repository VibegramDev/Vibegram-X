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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.PasswordController;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.util.DrawModifier;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.tool.Paints;
import android.graphics.Canvas;

import java.util.ArrayList;

import me.vkryl.core.CurrencyUtils;
import me.vkryl.core.StringUtils;

/**
 * «Checkout» of the official app: payment of an invoice in money through a payment provider. In Vibegram X it's
 * used to buy Telegram Stars from Premium Bot (Smart Glocal and other providers, as on Android without Google Play
 * billing) and for bot invoices.
 *
 * Paying with a saved card asks for the 2-Step Verification password (temporary password), a new card is tokenized
 * by the provider ({@link StarsCardController}) or entered on the provider's page ({@link PaymentWebController}),
 * and 3-D Secure opens in a web view. PAY asks once more before paying ({@link StarsUi#confirmAgain}).
 */
public class StarsCheckoutController extends RecyclerViewController<StarsCheckoutController.Args> implements View.OnClickListener {
  public static class Args {
    public final TdApi.InputInvoice invoice;
    public final TdApi.PaymentForm form;
    public final @Nullable TdApi.StarPaymentOption starOption;
    public final @Nullable Runnable onPaid;

    public Args (TdApi.InputInvoice invoice, TdApi.PaymentForm form, @Nullable TdApi.StarPaymentOption starOption, @Nullable Runnable onPaid) {
      this.invoice = invoice;
      this.form = form;
      this.starOption = starOption;
      this.onPaid = onPaid;
    }
  }

  /** «Buy Stars»: the Premium Bot invoice for the package. */
  public static void buyStars (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.StarPaymentOption option, @Nullable Runnable onPurchased) {
    TdApi.InputInvoice invoice = new TdApi.InputInvoiceTelegram(new TdApi.TelegramPaymentPurposeStars(option.currency, option.amount, option.starCount, 0));
    tdlib.send(new TdApi.GetPaymentForm(invoice, null), (form, error) -> UI.post(() -> {
      if (context.isDestroyed()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (form.type.getConstructor() != TdApi.PaymentFormTypeRegular.CONSTRUCTOR) {
        StarsUi.showUnavailable();
        return;
      }
      open(context, tdlib, invoice, form, option, onPurchased);
    }));
  }

  public static void open (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.InputInvoice invoice, @NonNull TdApi.PaymentForm form, @Nullable TdApi.StarPaymentOption starOption, @Nullable Runnable onPaid) {
    TdApi.PaymentFormTypeRegular regular = (TdApi.PaymentFormTypeRegular) form.type;
    if (regular.invoice.needShippingAddress || regular.invoice.isFlexible) {
      // Delivery of physical goods isn't supported by this checkout.
      UI.showToast(R.string.vg_PaymentShippingUnsupported, Toast.LENGTH_LONG);
      return;
    }
    StarsCheckoutController c = new StarsCheckoutController(context.context(), tdlib);
    c.setArguments(new Args(invoice, form, starOption, onPaid));
    context.navigateTo(c);
  }

  public StarsCheckoutController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgCheckout;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_PaymentCheckout);
  }

  private SettingsAdapter adapter;
  private TdApi.PaymentFormTypeRegular regular;

  // Payment method
  private @Nullable TdApi.SavedCredentials savedCredentials;
  private @Nullable String newCredentialsJson, newCredentialsTitle;
  private boolean newCredentialsAllowSave;

  // Order info
  private String orderName = "", orderPhone = "", orderEmail = "";

  private boolean termsAccepted;
  private boolean isConfirming, isPaying, isPaid;

  private M3ButtonView payButton;

  private boolean hasTerms () {
    return !StringUtils.isEmpty(regular.invoice.termsOfServiceUrl) || !StringUtils.isEmpty(regular.invoice.recurringPaymentTermsOfServiceUrl);
  }

  private String termsUrl () {
    return !StringUtils.isEmpty(regular.invoice.termsOfServiceUrl) ? regular.invoice.termsOfServiceUrl : regular.invoice.recurringPaymentTermsOfServiceUrl;
  }

  private boolean needOrderInfo () {
    TdApi.Invoice invoice = regular.invoice;
    return invoice.needName || invoice.needPhoneNumber || invoice.needEmailAddress;
  }

  private String botName () {
    Args args = getArgumentsStrict();
    return args.form.sellerBotUserId != 0 ? tdlib.cache().userName(args.form.sellerBotUserId) : "";
  }

  private String providerName () {
    return regular.paymentProviderUserId != 0 ? tdlib.cache().userName(regular.paymentProviderUserId) : "";
  }

  private long totalAmount () {
    long total = 0;
    for (TdApi.LabeledPricePart part : regular.invoice.priceParts) {
      total += part.amount;
    }
    return total;
  }

  private String totalText () {
    return CurrencyUtils.buildAmount(regular.invoice.currency, totalAmount());
  }

  private boolean hasPaymentMethod () {
    return savedCredentials != null || newCredentialsJson != null;
  }

  private boolean isOrderInfoComplete () {
    TdApi.Invoice invoice = regular.invoice;
    return (!invoice.needName || !StringUtils.isEmpty(orderName.trim())) &&
      (!invoice.needPhoneNumber || !StringUtils.isEmpty(orderPhone.trim())) &&
      (!invoice.needEmailAddress || !StringUtils.isEmpty(orderEmail.trim()));
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    Args args = getArgumentsStrict();
    regular = (TdApi.PaymentFormTypeRegular) args.form.type;
    if (regular.savedCredentials != null && regular.savedCredentials.length > 0) {
      savedCredentials = regular.savedCredentials[0];
    }
    if (regular.savedOrderInfo != null) {
      orderName = nonNull(regular.savedOrderInfo.name);
      orderPhone = nonNull(regular.savedOrderInfo.phoneNumber);
      orderEmail = nonNull(regular.savedOrderInfo.emailAddress);
    }

    adapter = new SettingsAdapter(this) {
      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        TdApi.ProductInfo product = args.form.productInfo;
        if (!view.setPhoto(tdlib, product != null ? product.photo : null, 88f, 12f)) {
          if (args.starOption != null) {
            view.setIcon(Drawables.get(R.drawable.vg_star_96), 88f);
          } else {
            view.setAvatar(tdlib, new TdApi.MessageSenderUser(args.form.sellerBotUserId), 88f);
          }
        }
        view.setTexts(tdlib, product != null ? product.title : "", product != null ? product.description : null);
        view.setCaption(botName());
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        // Rows are recycled between the receipt and the other settings.
        view.setDrawModifier(item.getData() instanceof PriceLine ? (PriceLine) item.getData() : null);
        if (itemId == R.id.btn_vgPaymentMethod) {
          String title = savedCredentials != null ? savedCredentials.title : newCredentialsTitle != null ? newCredentialsTitle : Lang.getString(R.string.vg_PaymentCheckoutMethodAdd);
          view.setName(title);
          view.setData(R.string.vg_PaymentCheckoutMethod);
        } else if (itemId == R.id.btn_vgPaymentProvider) {
          view.setName(providerName());
          view.setData(R.string.vg_PaymentCheckoutProvider);
        } else if (itemId == R.id.btn_vgOrderName) {
          view.setName(StringUtils.isEmpty(orderName) ? Lang.getString(R.string.vg_PaymentShippingName) : orderName);
          view.setData(R.string.vg_PaymentCheckoutName);
        } else if (itemId == R.id.btn_vgOrderPhone) {
          view.setName(StringUtils.isEmpty(orderPhone) ? Lang.getString(R.string.vg_PaymentShippingPhoneNumber) : orderPhone);
          view.setData(R.string.vg_PaymentCheckoutPhoneNumber);
        } else if (itemId == R.id.btn_vgOrderEmail) {
          view.setName(StringUtils.isEmpty(orderEmail) ? Lang.getString(R.string.vg_PaymentShippingEmailPlaceholder) : orderEmail);
          view.setData(R.string.vg_PaymentCheckoutEmail);
        } else if (itemId == R.id.btn_vgPaymentTerms) {
          view.getToggler().setRadioEnabled(termsAccepted, isUpdate);
        }
      }
    };
    adapter.setSegmented();

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER, R.id.btn_vgPaymentProduct));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    boolean first = true;
    for (TdApi.LabeledPricePart part : regular.invoice.priceParts) {
      if (!first) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      }
      first = false;
      items.add(newPriceItem(new PriceLine(part.label, CurrencyUtils.buildAmount(regular.invoice.currency, part.amount), false)));
    }
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(newPriceItem(new PriceLine(Lang.getString(R.string.vg_PaymentTransactionTotal), totalText(), true)));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgPaymentMethod, R.drawable.vg_credit_card_24, R.string.vg_PaymentCheckoutMethod));
    if (regular.paymentProviderUserId != 0) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgPaymentProvider, R.drawable.vg_payment_provider_24, R.string.vg_PaymentCheckoutProvider));
    }
    if (regular.invoice.needName) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgOrderName, R.drawable.baseline_person_24, R.string.vg_PaymentCheckoutName));
    }
    if (regular.invoice.needPhoneNumber) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgOrderPhone, R.drawable.baseline_phone_24, R.string.vg_PaymentCheckoutPhoneNumber));
    }
    if (regular.invoice.needEmailAddress) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgOrderEmail, R.drawable.baseline_alternate_email_24, R.string.vg_PaymentCheckoutEmail));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    if (hasTerms()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_vgPaymentTerms, 0, Lang.getString(R.string.vg_PaymentCheckoutAccept, botName()), R.id.btn_vgPaymentTerms, false));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_vgPaymentTermsOpen, R.drawable.baseline_open_in_browser_24, R.string.vg_PaymentTermsOfService));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else {
      termsAccepted = true;
    }
    String provider = providerName();
    if (!StringUtils.isEmpty(provider)) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getString(R.string.vg_PaymentWarningText, botName(), provider), false));
    }
    if (regular.invoice.isTest) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_PaymentTestInvoice));
    }
    items.add(new ListItem(ListItem.TYPE_PADDING).setHeight(Screen.dp(96f)));

    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
  }

  private static String nonNull (@Nullable String str) {
    return str != null ? str : "";
  }

  private static ListItem newPriceItem (PriceLine line) {
    return new ListItem(ListItem.TYPE_SETTING, R.id.btn_vgPaymentPrice, 0, line.label, false).setData(line);
  }

  /**
   * Line of the receipt: label as the row name, amount drawn at the end of the Telegram X row
   * (M3 list item: trailing supporting text); the total in bold.
   */
  private static final class PriceLine implements DrawModifier {
    final String label, amount;
    final boolean isTotal;
    private @Nullable Text text;

    PriceLine (String label, String amount, boolean isTotal) {
      this.label = label;
      this.amount = amount;
      this.isTotal = isTotal;
    }

    private Text text () {
      if (text == null) {
        Text.Builder b = new Text.Builder(amount, Screen.currentWidth() / 2, Paints.robotoStyleProvider(16f), isTotal ? TextColorSets.Regular.NORMAL : TextColorSets.Regular.LIGHT).singleLine();
        if (isTotal) {
          b.allBold();
        }
        text = b.build();
      }
      return text;
    }

    @Override
    public int getWidth () {
      return text().getWidth() + Screen.dp(16f);
    }

    @Override
    public void afterDraw (View view, Canvas c) {
      Text text = text();
      int x = Lang.rtl() ? Screen.dp(16f) : view.getWidth() - Screen.dp(16f) - text.getWidth();
      text.draw(c, x, (view.getHeight() - text.getHeight()) / 2);
    }
  }

  // PAY button

  @Override
  protected View onCreateView (Context context) {
    FrameLayout wrap = (FrameLayout) super.onCreateView(context);
    payButton = new M3ButtonView(context, M3ButtonView.STYLE_FILLED, M3ButtonView.SIZE_MEDIUM);
    payButton.addThemeListeners(this);
    payButton.setOnClickListener(v -> onPayClick());
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(56f), Gravity.BOTTOM);
    params.leftMargin = params.rightMargin = Screen.dp(16f);
    params.bottomMargin = Screen.dp(16f);
    wrap.addView(payButton, params);
    updatePayButton();
    return wrap;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    if (payButton != null) {
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) payButton.getLayoutParams();
      params.bottomMargin = Screen.dp(16f) + extraBottomInset;
      payButton.setLayoutParams(params);
    }
  }

  private void updatePayButton () {
    if (payButton == null) {
      return;
    }
    payButton.setText(Lang.getString(R.string.vg_PaymentCheckoutPay, totalText()));
    // As in the official checkout: active while not paying, a tap tells what's missing (opens the payment method).
    payButton.setEnabled(!isPaying && !isPaid);
  }

  private void onPayClick () {
    if (isConfirming || isPaying || isPaid) {
      return;
    }
    if (!hasPaymentMethod()) {
      openPaymentMethod();
      return;
    }
    if (!isOrderInfoComplete()) {
      UI.showToast(R.string.vg_PaymentOrderInfoRequired, Toast.LENGTH_SHORT);
      return;
    }
    if (!termsAccepted) {
      UI.showToast(Lang.getString(R.string.vg_PaymentAcceptTerms, botName()), Toast.LENGTH_SHORT);
      return;
    }
    Args args = getArgumentsStrict();
    String what = args.starOption != null ?
      Lang.plural(R.string.vg_StarsCount, args.starOption.starCount) :
      (args.form.productInfo != null ? args.form.productInfo.title : botName());
    isConfirming = true;
    StarsUi.confirmAgain(this, Lang.getString(R.string.vg_StarsConfirmAgainBuy, totalText(), what), Lang.getString(R.string.vg_StarsPay), confirmed -> {
      isConfirming = false;
      if (confirmed && !isPaying && !isPaid && !isDestroyed()) {
        prepareCredentials();
      }
    });
  }

  // Payment

  private void setPaying (boolean isPaying) {
    this.isPaying = isPaying;
    if (!isDestroyed()) {
      setStackLocked(isPaying);
      updatePayButton();
    }
  }

  /** A saved card needs a valid temporary password (2-Step Verification). */
  private void prepareCredentials () {
    if (newCredentialsJson != null) {
      validateAndPay(new TdApi.InputCredentialsNew(newCredentialsJson, newCredentialsAllowSave));
      return;
    }
    if (savedCredentials == null) {
      return;
    }
    final TdApi.InputCredentials credentials = new TdApi.InputCredentialsSaved(savedCredentials.id);
    setPaying(true);
    tdlib.send(new TdApi.GetTemporaryPasswordState(), (state, error) -> runOnUiThreadOptional(() -> {
      if (error == null && state.hasPassword && state.validFor > 60) {
        validateAndPay(credentials);
        return;
      }
      setPaying(false);
      requestPassword(password -> {
        setPaying(true);
        tdlib.send(new TdApi.CreateTemporaryPassword(password, 30 * 60), (tmpState, error1) -> runOnUiThreadOptional(() -> {
          if (error1 != null) {
            setPaying(false);
            UI.showError(error1);
          } else {
            validateAndPay(credentials);
          }
        }));
      });
    }));
  }

  private void requestPassword (me.vkryl.core.lambda.RunnableData<String> onPassword) {
    tdlib.send(new TdApi.GetPasswordState(), (passwordState, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (!passwordState.hasPassword) {
        UI.showToast(R.string.vg_PaymentPasswordRequired, Toast.LENGTH_LONG);
        return;
      }
      PasswordController controller = new PasswordController(context, tdlib);
      controller.setArguments(new PasswordController.Args(PasswordController.MODE_CONFIRM, passwordState).setSuccessListener(onPassword));
      navigateTo(controller);
    }));
  }

  private void validateAndPay (TdApi.InputCredentials credentials) {
    setPaying(true);
    if (!needOrderInfo()) {
      pay(credentials, "");
      return;
    }
    TdApi.OrderInfo orderInfo = new TdApi.OrderInfo(orderName.trim(), orderPhone.trim(), orderEmail.trim(), null);
    tdlib.send(new TdApi.ValidateOrderInfo(getArgumentsStrict().invoice, orderInfo, false), (validated, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        setPaying(false);
        UI.showError(error);
      } else {
        pay(credentials, validated.orderInfoId);
      }
    }));
  }

  private void pay (TdApi.InputCredentials credentials, String orderInfoId) {
    final Args args = getArgumentsStrict();
    // The result is handled even when the screen is already destroyed: the payment is done anyway.
    tdlib.send(new TdApi.SendPaymentForm(args.invoice, args.form.id, orderInfoId, "", credentials, 0), (result, error) -> UI.post(() -> {
      if (error == null) {
        isPaid = true;
      }
      setPaying(false);
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (!StringUtils.isEmpty(result.verificationUrl) && !isDestroyed()) {
        // 3-D Secure
        // The bank page finishing (or being closed) doesn't prove the payment: it's reported as being processed.
        PaymentWebController.openVerification(this, result.verificationUrl, completed -> onPaymentDone(args, false));
        return;
      }
      onPaymentDone(args, result.success);
    }));
  }

  /** Runs even after the screen is destroyed: arguments are passed, as a destroyed controller has none. */
  private void onPaymentDone (Args args, boolean success) {
    if (success) {
      if (args.starOption != null) {
        UI.showToast(Lang.getString(R.string.vg_StarsAcquired) + "\n" + Lang.plural(R.string.vg_StarsAcquiredInfo, args.starOption.starCount).replace("**", ""), Toast.LENGTH_LONG);
      } else {
        UI.showToast(Lang.getString(R.string.vg_PaymentSuccessfullyPaid, totalText(), botName(), args.form.productInfo != null ? args.form.productInfo.title : ""), Toast.LENGTH_LONG);
      }
      if (args.onPaid != null) {
        args.onPaid.run();
      }
    } else {
      UI.showToast(R.string.vg_PaymentProcessing, Toast.LENGTH_LONG);
    }
    if (!isDestroyed()) {
      // After 3-D Secure the checkout is already removed from the stack.
      navigateBack();
    }
  }

  // Payment method

  private void openPaymentMethod () {
    if (regular.savedCredentials == null || regular.savedCredentials.length == 0) {
      openNewCard();
      return;
    }
    int count = regular.savedCredentials.length;
    int[] ids = new int[count + 1];
    String[] titles = new String[count + 1];
    int[] icons = new int[count + 1];
    for (int i = 0; i < count; i++) {
      ids[i] = i + 1;
      titles[i] = regular.savedCredentials[i].title;
      icons[i] = R.drawable.vg_credit_card_24;
    }
    ids[count] = R.id.btn_vgPaymentNewCard;
    titles[count] = Lang.getString(R.string.vg_PaymentCheckoutMethodNewCard);
    icons[count] = R.drawable.baseline_add_24;
    showOptions(Lang.getString(R.string.vg_PaymentCheckoutMethod), ids, titles, null, icons, (itemView, id) -> {
      if (id == R.id.btn_vgPaymentNewCard) {
        openNewCard();
      } else if (id >= 1 && id <= count) {
        savedCredentials = regular.savedCredentials[id - 1];
        newCredentialsJson = null;
        newCredentialsTitle = null;
        adapter.updateValuedSettingById(R.id.btn_vgPaymentMethod);
        updatePayButton();
      }
      return true;
    });
  }

  private void openNewCard () {
    StarsCardController.Callback callback = (credentialsJson, title, allowSave) -> {
      newCredentialsJson = credentialsJson;
      newCredentialsTitle = title;
      newCredentialsAllowSave = allowSave;
      savedCredentials = null;
      adapter.updateValuedSettingById(R.id.btn_vgPaymentMethod);
      updatePayButton();
    };
    TdApi.PaymentProvider provider = regular.paymentProvider;
    switch (provider.getConstructor()) {
      case TdApi.PaymentProviderSmartGlocal.CONSTRUCTOR:
      case TdApi.PaymentProviderStripe.CONSTRUCTOR: {
        StarsCardController c = new StarsCardController(context, tdlib);
        c.setArguments(new StarsCardController.Args(provider, regular.invoice.isTest, regular.canSaveCredentials, regular.needPassword, callback));
        navigateTo(c);
        break;
      }
      case TdApi.PaymentProviderOther.CONSTRUCTOR: {
        PaymentWebController.openCheckoutForm(this, ((TdApi.PaymentProviderOther) provider).url, botName(), callback);
        break;
      }
    }
  }

  private void editOrderField (int id) {
    final String current;
    final int title;
    final int inputType;
    if (id == R.id.btn_vgOrderName) {
      current = orderName;
      title = R.string.vg_PaymentShippingName;
      inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
    } else if (id == R.id.btn_vgOrderPhone) {
      current = orderPhone;
      title = R.string.vg_PaymentShippingPhoneNumber;
      inputType = InputType.TYPE_CLASS_PHONE;
    } else {
      current = orderEmail;
      title = R.string.vg_PaymentShippingEmailPlaceholder;
      inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
    }
    org.thunderdog.challegram.widget.MaterialEditTextGroup input = openInputAlert(Lang.getString(title), Lang.getString(title), R.string.Done, R.string.Cancel, current, (inputView, result) -> {
      String value = result != null ? result.trim() : "";
      if (id == R.id.btn_vgOrderName) {
        orderName = value;
      } else if (id == R.id.btn_vgOrderPhone) {
        orderPhone = value;
      } else {
        orderEmail = value;
      }
      adapter.updateValuedSettingById(id);
      updatePayButton();
      return true;
    }, true);
    if (input != null) {
      input.getEditText().setInputType(inputType);
    }
  }

  @Override
  public void onClick (View v) {
    final int viewId = v.getId();
    if (isPaying || isPaid) {
      return;
    }
    if (viewId == R.id.btn_vgPaymentMethod) {
      openPaymentMethod();
    } else if (viewId == R.id.btn_vgPaymentTerms) {
      termsAccepted = adapter.toggleView(v);
      updatePayButton();
    } else if (viewId == R.id.btn_vgPaymentTermsOpen) {
      StarsUi.openUrl(this, termsUrl());
    } else if (viewId == R.id.btn_vgOrderName || viewId == R.id.btn_vgOrderPhone || viewId == R.id.btn_vgOrderEmail) {
      editOrderField(viewId);
    }
  }

}
