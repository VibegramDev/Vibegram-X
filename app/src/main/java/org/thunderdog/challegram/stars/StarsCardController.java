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
import android.net.Uri;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.EditBaseController;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;

/**
 * New payment card for the checkout, as in the official app: card number, MM/YY, CVC (and the fields Stripe asks
 * for). The card goes straight to the payment provider (Smart Glocal or Stripe) over HTTPS and only the provider's
 * token is passed to Telegram; neither the app nor Telegram keep the card data.
 */
public class StarsCardController extends EditBaseController<StarsCardController.Args> implements SettingsAdapter.TextChangeListener, View.OnClickListener {
  public interface Callback {
    void onCardTokenized (String credentialsJson, String title, boolean allowSave);
  }

  public static class Args {
    final TdApi.PaymentProvider provider;
    final boolean isTest, canSaveCredentials, needPassword;
    final Callback callback;

    public Args (TdApi.PaymentProvider provider, boolean isTest, boolean canSaveCredentials, boolean needPassword, Callback callback) {
      this.provider = provider;
      this.isTest = isTest;
      this.canSaveCredentials = canSaveCredentials;
      this.needPassword = needPassword;
      this.callback = callback;
    }
  }

  public StarsCardController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgCard;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_PaymentCardTitle);
  }

  private SettingsAdapter adapter;
  private ListItem numberItem, expiryItem, cvcItem, nameItem, countryItem, zipItem;
  private boolean saveCard;

  private boolean isStripe () {
    return getArgumentsStrict().provider.getConstructor() == TdApi.PaymentProviderStripe.CONSTRUCTOR;
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, RecyclerView recyclerView) {
    Args args = getArgumentsStrict();
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        final int id = item.getId();
        EditText edit = editText.getEditText();
        if (id == R.id.edit_vgCardNumber) {
          edit.setInputType(InputType.TYPE_CLASS_NUMBER);
          installFormatter(edit, true);
        } else if (id == R.id.edit_vgCardExpiry) {
          edit.setInputType(InputType.TYPE_CLASS_NUMBER);
          installFormatter(edit, false);
        } else if (id == R.id.edit_vgCardCvc) {
          edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
          editText.getEditText().setIsPassword(true);
        } else if (id == R.id.edit_vgCardName) {
          edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else if (id == R.id.edit_vgCardCountry) {
          edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else if (id == R.id.edit_vgCardZip) {
          edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        }
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgCardSave) {
          boolean available = args.canSaveCredentials && !args.needPassword;
          view.setEnabledAnimated(available, isUpdate);
          view.getToggler().setRadioEnabled(saveCard && available, isUpdate);
        }
      }
    };
    adapter.setLockFocusOn(this, true);
    adapter.setTextChangeListener(this);

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_HEADER_PADDED, 0, 0, R.string.vg_PaymentCardInfo));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(numberItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardNumber, 0, R.string.vg_PaymentCardNumber)
      .setInputFilters(new InputFilter[] {new InputFilter.LengthFilter(23)}));
    items.add(expiryItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardExpiry, 0, R.string.vg_PaymentCardExpireDate)
      .setInputFilters(new InputFilter[] {new InputFilter.LengthFilter(5)}));
    items.add(cvcItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardCvc, 0, R.string.vg_PaymentCardCvv)
      .setInputFilters(new InputFilter[] {new InputFilter.LengthFilter(4)}));
    if (isStripe()) {
      TdApi.PaymentProviderStripe stripe = (TdApi.PaymentProviderStripe) args.provider;
      if (stripe.needCardholderName) {
        items.add(nameItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardName, 0, R.string.vg_PaymentCardName));
      }
      if (stripe.needCountry) {
        items.add(countryItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardCountry, 0, R.string.vg_PaymentCardCountryCode)
          .setInputFilters(new InputFilter[] {new InputFilter.LengthFilter(2)}));
      }
      if (stripe.needPostalCode) {
        items.add(zipItem = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_vgCardZip, 0, R.string.vg_PaymentShippingZipPlaceholder));
      }
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_PaymentCardSecurityInfo));

    if (args.canSaveCredentials || args.needPassword) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_vgCardSave, 0, R.string.vg_PaymentCardSavePaymentInformation, R.id.btn_vgCardSave, false));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, args.needPassword ? R.string.vg_PaymentCardSaveNeedPassword : R.string.vg_PaymentCardSavePaymentInformationInfo));
    }

    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    setDoneIcon(R.drawable.baseline_check_24);
    setDoneVisible(false);
  }

  /** Card number as «1234 5678 9012 3456», expiry as «MM/YY». */
  private static void installFormatter (EditText edit, boolean isCardNumber) {
    if (edit.getTag(R.id.vg_tagFormatter) != null) {
      return;
    }
    TextWatcher watcher = new TextWatcher() {
      private boolean selfChange;

      @Override
      public void beforeTextChanged (CharSequence s, int start, int count, int after) { }

      @Override
      public void onTextChanged (CharSequence s, int start, int before, int count) { }

      @Override
      public void afterTextChanged (Editable s) {
        if (selfChange) {
          return;
        }
        String digits = digitsOf(s.toString());
        StringBuilder b = new StringBuilder();
        if (isCardNumber) {
          for (int i = 0; i < digits.length() && i < 19; i++) {
            if (i > 0 && i % 4 == 0) {
              b.append(' ');
            }
            b.append(digits.charAt(i));
          }
        } else {
          for (int i = 0; i < digits.length() && i < 4; i++) {
            if (i == 2) {
              b.append('/');
            }
            b.append(digits.charAt(i));
          }
        }
        String formatted = b.toString();
        if (!formatted.equals(s.toString())) {
          selfChange = true;
          s.replace(0, s.length(), formatted);
          selfChange = false;
        }
      }
    };
    edit.addTextChangedListener(watcher);
    edit.setTag(R.id.vg_tagFormatter, watcher);
  }

  private static String digitsOf (@Nullable String str) {
    if (str == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(str.length());
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (c >= '0' && c <= '9') {
        b.append(c);
      }
    }
    return b.toString();
  }

  private static String valueOf (@Nullable ListItem item) {
    String value = item != null ? item.getStringValue() : null;
    return value != null ? value.trim() : "";
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup v) {
    adapter.updateEditTextById(id, false, false);
    setDoneVisible(!digitsOf(valueOf(numberItem)).isEmpty() && digitsOf(valueOf(expiryItem)).length() == 4 && digitsOf(valueOf(cvcItem)).length() >= 3);
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_vgCardSave) {
      Args args = getArgumentsStrict();
      if (args.needPassword) {
        UI.showToast(R.string.vg_PaymentCardSaveNeedPassword, android.widget.Toast.LENGTH_SHORT);
        return;
      }
      saveCard = adapter.toggleView(v);
    }
  }

  // Validation

  private static boolean isLuhnValid (String number) {
    int sum = 0;
    boolean alternate = false;
    for (int i = number.length() - 1; i >= 0; i--) {
      int n = number.charAt(i) - '0';
      if (alternate) {
        n *= 2;
        if (n > 9) {
          n -= 9;
        }
      }
      sum += n;
      alternate = !alternate;
    }
    return sum % 10 == 0;
  }

  static String cardBrand (String number) {
    if (number.startsWith("4")) {
      return "Visa";
    }
    if (number.startsWith("220")) {
      return "Mir";
    }
    if (number.matches("^5[1-5].*") || number.matches("^2(2[2-9]|[3-6]\\d|7[01]|720).*")) {
      return "MasterCard";
    }
    if (number.startsWith("34") || number.startsWith("37")) {
      return "American Express";
    }
    if (number.startsWith("62")) {
      return "UnionPay";
    }
    if (number.startsWith("35")) {
      return "JCB";
    }
    if (number.startsWith("6011") || number.startsWith("65")) {
      return "Discover";
    }
    return Lang.getString(R.string.vg_PaymentCardGeneric);
  }

  private void showFieldError (int id) {
    adapter.updateEditTextById(id, false, true);
  }

  @Override
  protected boolean onDoneClick () {
    if (isInProgress()) {
      return false;
    }
    String number = digitsOf(valueOf(numberItem));
    String expiry = digitsOf(valueOf(expiryItem));
    String cvc = digitsOf(valueOf(cvcItem));
    if (number.length() < 12 || number.length() > 19 || !isLuhnValid(number)) {
      showFieldError(R.id.edit_vgCardNumber);
      return false;
    }
    int month = expiry.length() == 4 ? StringUtils.parseInt(expiry.substring(0, 2)) : 0;
    int year = expiry.length() == 4 ? StringUtils.parseInt(expiry.substring(2)) : -1;
    Calendar now = Calendar.getInstance();
    int currentYear = now.get(Calendar.YEAR) % 100, currentMonth = now.get(Calendar.MONTH) + 1;
    if (month < 1 || month > 12 || year < 0 || year < currentYear || (year == currentYear && month < currentMonth)) {
      showFieldError(R.id.edit_vgCardExpiry);
      return false;
    }
    if (cvc.length() < 3) {
      showFieldError(R.id.edit_vgCardCvc);
      return false;
    }
    if (nameItem != null && valueOf(nameItem).isEmpty()) {
      showFieldError(R.id.edit_vgCardName);
      return false;
    }
    if (countryItem != null && valueOf(countryItem).length() != 2) {
      showFieldError(R.id.edit_vgCardCountry);
      return false;
    }
    if (zipItem != null && valueOf(zipItem).isEmpty()) {
      showFieldError(R.id.edit_vgCardZip);
      return false;
    }
    final Args args = getArgumentsStrict();
    final String title = cardBrand(number) + " *" + number.substring(number.length() - 4);
    final boolean allowSave = saveCard && args.canSaveCredentials && !args.needPassword;
    final String name = valueOf(nameItem), country = valueOf(countryItem), zip = valueOf(zipItem);
    setInProgress(true);
    // HttpURLConnection goes directly (or through the system proxy of Android), not through the proxy of TDLib.
    Background.instance().post(() -> {
      String credentials = null;
      try {
        if (args.provider.getConstructor() == TdApi.PaymentProviderSmartGlocal.CONSTRUCTOR) {
          credentials = tokenizeSmartGlocal((TdApi.PaymentProviderSmartGlocal) args.provider, args.isTest, number, month, year, cvc);
        } else if (args.provider.getConstructor() == TdApi.PaymentProviderStripe.CONSTRUCTOR) {
          credentials = tokenizeStripe((TdApi.PaymentProviderStripe) args.provider, number, month, year, cvc, name, country, zip);
        }
      } catch (Throwable ignored) {
        // Network error or an unexpected response: reported below without details, the card data is never logged.
      }
      final String result = credentials;
      UI.post(() -> {
        if (isDestroyed()) {
          return;
        }
        setInProgress(false);
        if (result == null) {
          UI.showToast(R.string.vg_PaymentConnectionFailed, android.widget.Toast.LENGTH_LONG);
          return;
        }
        args.callback.onCardTokenized(result, title, allowSave);
        navigateBack();
      });
    });
    return true;
  }

  // Providers

  private static final String SMART_GLOCAL_URL = "https://tgb.smart-glocal.com/cds/v1/tokenize/card";
  private static final String SMART_GLOCAL_TEST_URL = "https://tgb-playground.smart-glocal.com/cds/v1/tokenize/card";

  private static @Nullable String tokenizeSmartGlocal (TdApi.PaymentProviderSmartGlocal provider, boolean isTest, String number, int month, int year, String cvc) throws Exception {
    String url = isSmartGlocalTokenizeUrl(provider.tokenizeUrl) ? provider.tokenizeUrl : isTest ? SMART_GLOCAL_TEST_URL : SMART_GLOCAL_URL;
    JSONObject card = new JSONObject();
    card.put("number", number);
    card.put("expiration_month", String.format(Locale.US, "%02d", month));
    card.put("expiration_year", String.format(Locale.US, "%02d", year));
    card.put("security_code", cvc);
    JSONObject body = new JSONObject();
    body.put("card", card);
    String response = post(url, body.toString().getBytes(StandardCharsets.UTF_8), "application/json", "X-PUBLIC-TOKEN", provider.publicToken);
    if (response == null) {
      return null;
    }
    String token = new JSONObject(response).getJSONObject("data").getString("token");
    JSONObject result = new JSONObject();
    result.put("token", token);
    result.put("type", "card");
    return result.toString();
  }

  /** The card goes only to a host of Smart Glocal: https, *.smart-glocal.com, the tokenization path, nothing else. */
  private static boolean isSmartGlocalTokenizeUrl (@Nullable String url) {
    if (StringUtils.isEmpty(url)) {
      return false;
    }
    Uri uri = Uri.parse(url);
    String host = uri.getHost();
    return "https".equals(uri.getScheme()) &&
      host != null && host.toLowerCase(Locale.ROOT).matches("[a-z0-9.-]+\\.smart-glocal\\.com") &&
      uri.getPort() == -1 &&
      "/cds/v1/tokenize/card".equals(uri.getPath()) &&
      uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
  }

  private static @Nullable String tokenizeStripe (TdApi.PaymentProviderStripe provider, String number, int month, int year, String cvc, String name, String country, String zip) throws Exception {
    StringBuilder form = new StringBuilder();
    appendParam(form, "card[number]", number);
    appendParam(form, "card[exp_month]", Integer.toString(month));
    appendParam(form, "card[exp_year]", Integer.toString(2000 + year));
    appendParam(form, "card[cvc]", cvc);
    if (!StringUtils.isEmpty(name)) {
      appendParam(form, "card[name]", name);
    }
    if (!StringUtils.isEmpty(country)) {
      appendParam(form, "card[address_country]", country);
    }
    if (!StringUtils.isEmpty(zip)) {
      appendParam(form, "card[address_zip]", zip);
    }
    String response = post("https://api.stripe.com/v1/tokens", form.toString().getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", "Authorization", "Bearer " + provider.publishableKey);
    if (response == null) {
      return null;
    }
    JSONObject json = new JSONObject(response);
    JSONObject result = new JSONObject();
    result.put("type", json.optString("type", "card"));
    result.put("id", json.getString("id"));
    return result.toString();
  }

  private static void appendParam (StringBuilder b, String key, String value) throws Exception {
    if (b.length() > 0) {
      b.append('&');
    }
    b.append(URLEncoder.encode(key, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"));
  }

  private static @Nullable String post (@NonNull String url, byte[] body, String contentType, String headerName, String headerValue) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    try {
      connection.setConnectTimeout(30_000);
      // Background is shared by the whole app: don't hold it longer than needed.
      connection.setReadTimeout(30_000);
      connection.setUseCaches(false);
      connection.setDoOutput(true);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", contentType);
      connection.setRequestProperty(headerName, headerValue);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body);
      }
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        return null;
      }
      try (InputStream in = connection.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
      }
    } finally {
      connection.disconnect();
    }
  }
}
