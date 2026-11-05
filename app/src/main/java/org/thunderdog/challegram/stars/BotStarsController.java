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
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.charts.Chart;
import org.thunderdog.challegram.charts.data.ChartDataUtil;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.PasswordController;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.Settings2FAController;
import org.thunderdog.challegram.ui.SettingHolder;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.VgSheets;

import java.util.ArrayList;

import me.vkryl.core.StringUtils;
import tgx.td.ChatId;
import tgx.td.Td;

/**
 * Telegram Stars of a bot owned by the current user (or the current user's own revenue), as BotStarsActivity of the
 * official app: revenue chart, proceeds overview, withdrawal of the available balance through Fragment (2-Step
 * Verification password), Telegram Ads account and the transactions.
 *
 * The withdrawal is confirmed twice; the button of the second confirmation unlocks after 3 seconds.
 */
public class BotStarsController extends RecyclerViewController<BotStarsController.Args> implements View.OnClickListener, TdlibOptionListener {
  public static class Args {
    public final TdApi.MessageSender ownerId;
    public final boolean isSelf;

    public Args (TdApi.MessageSender ownerId, boolean isSelf) {
      this.ownerId = ownerId;
      this.isSelf = isSelf;
    }
  }

  public static void open (@NonNull ViewController<?> context, long botUserId) {
    BotStarsController c = new BotStarsController(context.context(), context.tdlib());
    c.setArguments(new Args(new TdApi.MessageSenderUser(botUserId), false));
    context.navigateTo(c);
  }

  public BotStarsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgBotStars;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(getArgumentsStrict().isSelf ? R.string.vg_StarsRevenueSelf : R.string.vg_BotStarsTitle);
  }

  private static final int VIEW_BALANCE = 2;

  private SettingsAdapter adapter;
  private @Nullable StarsTransactions.Section transactions;
  private @Nullable TdApi.StarRevenueStatistics statistics;
  private double usdRate;

  private TextView balanceAmountView, balanceUsdView, withdrawButton;
  private EditText amountInput;

  private long ownerUserId () {
    TdApi.MessageSender ownerId = getArgumentsStrict().ownerId;
    return ownerId.getConstructor() == TdApi.MessageSenderUser.CONSTRUCTOR ? ((TdApi.MessageSenderUser) ownerId).userId : 0;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    Args args = getArgumentsStrict();
    if (!args.isSelf) {
      transactions = new StarsTransactions.Section(this, tdlib, args.ownerId);
    }
    adapter = new SettingsAdapter(this) {
      @Override
      protected SettingHolder initCustom (ViewGroup parent, int customViewType) {
        if (customViewType == VIEW_BALANCE) {
          return new SettingHolder(newBalanceView(parent.getContext()));
        }
        if (transactions != null && customViewType == StarsTransactions.VIEW_TABS) {
          return new SettingHolder(transactions.newTabsView(parent.getContext()));
        }
        if (transactions != null && customViewType == StarsTransactions.VIEW_TRANSACTION) {
          return new SettingHolder(transactions.newTransactionView(parent.getContext()));
        }
        return super.initCustom(parent, customViewType);
      }

      @Override
      protected void modifyCustom (SettingHolder holder, int position, ListItem item, int customViewType, View view, boolean isUpdate) {
        if (transactions != null && customViewType == StarsTransactions.VIEW_TRANSACTION) {
          transactions.bindTransaction(view, item);
        } else if (customViewType == VIEW_BALANCE) {
          updateBalanceView();
        }
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        TdApi.StarRevenueStatus status = statistics != null ? statistics.status : null;
        final int itemId = item.getId();
        TdApi.StarAmount amount;
        int labelRes;
        if (itemId == R.id.btn_vgBotStarsAvailable) {
          amount = status != null ? status.availableAmount : null;
          labelRes = R.string.vg_BotStarsOverviewAvailableBalance;
        } else if (itemId == R.id.btn_vgBotStarsCurrent) {
          amount = status != null ? status.currentAmount : null;
          labelRes = R.string.vg_BotStarsOverviewTotalBalance;
        } else if (itemId == R.id.btn_vgBotStarsTotal) {
          amount = status != null ? status.totalAmount : null;
          labelRes = R.string.vg_BotStarsOverviewTotalProceeds;
        } else {
          return;
        }
        view.setName(amountWithUsd(amount));
        view.setData(labelRes);
      }
    };
    adapter.setSegmented(ListItem.TYPE_CUSTOM - VIEW_BALANCE, StarsTransactions.LIST_TYPE_TRANSACTION);
    adapter.setItems(new ArrayList<>(), false);
    recyclerView.setAdapter(adapter);
    tdlib.listeners().addOptionsListener(this);
    load();
  }

  private CharSequence amountWithUsd (@Nullable TdApi.StarAmount amount) {
    if (amount == null) {
      return Lang.getString(R.string.LoadingInformation);
    }
    String text = Stars.amount(amount);
    String usd = usdText(amount);
    return Stars.withIcons(usd != null ? text + "   " + usd : text);
  }

  private @Nullable String usdText (TdApi.StarAmount amount) {
    if (usdRate <= 0) {
      return null;
    }
    double stars = amount.starCount + amount.nanostarCount / 1e9;
    return String.format(java.util.Locale.US, "≈ $%.2f", stars * usdRate);
  }

  private void load () {
    Args args = getArgumentsStrict();
    tdlib.send(new TdApi.GetStarRevenueStatistics(args.ownerId, Theme.isDark()), (result, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      boolean firstLoad = statistics == null;
      statistics = result;
      usdRate = result.usdRate;
      if (firstLoad) {
        buildItems();
      } else {
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsAvailable);
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsCurrent);
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsTotal);
        updateBalanceView();
      }
    }));
  }

  private void buildItems () {
    Args args = getArgumentsStrict();
    ArrayList<ListItem> items = new ArrayList<>();
    TdApi.StatisticalGraph graph = statistics.revenueByDayGraph;
    long chatId = ownerUserId() != 0 ? ChatId.fromUserId(ownerUserId()) : 0;
    Chart chart = graph != null && graph.getConstructor() == TdApi.StatisticalGraphData.CONSTRUCTOR ?
      new Chart(R.id.vg_starsRevenueChart, tdlib, chatId, R.string.vg_BotStarsChartRevenue, ChartDataUtil.TYPE_STACK_BAR, graph, 0) : null;
    if (chart != null && !chart.isError()) {
      items.add(new ListItem(ListItem.TYPE_CHART_HEADER).setData(chart));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(chart.getViewType(), chart.getId()).setData(chart));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_BotStarsOverview));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgBotStarsAvailable, 0, R.string.vg_BotStarsOverviewAvailableBalance));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgBotStarsCurrent, 0, R.string.vg_BotStarsOverviewTotalBalance));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgBotStarsTotal, 0, R.string.vg_BotStarsOverviewTotalProceeds));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, args.isSelf ? R.string.vg_SelfStarsOverviewInfo : R.string.vg_BotStarsOverviewInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_BotStarsAvailableBalance));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_CUSTOM - VIEW_BALANCE, R.id.btn_vgBotStarsBalance));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.plural(args.isSelf ? R.string.vg_SelfStarsWithdrawInfo : R.string.vg_BotStarsWithdrawInfo, tdlib.options().starWithdrawalCountMin), false));

    if (transactions != null) {
      transactions.addItems(items);
    }
    adapter.setItems(items, false);
    if (transactions != null) {
      transactions.attach(adapter, getRecyclerView());
    }
  }

  // Balance and withdrawal

  private View newBalanceView (Context context) {
    Args args = getArgumentsStrict();
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(Screen.dp(16f), Screen.dp(20f), Screen.dp(16f), Screen.dp(16f));

    balanceAmountView = VgSheets.newTitle(context, "");
    balanceAmountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30f);
    balanceAmountView.setTypeface(Fonts.getRobotoMedium());
    content.addView(balanceAmountView, VgSheets.wide(0, 0));
    balanceUsdView = VgSheets.newCaption(context, "", true);
    content.addView(balanceUsdView, VgSheets.wide(2f, 16f));

    amountInput = new EditText(context);
    amountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
    amountInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
    amountInput.setHint(Lang.getString(R.string.vg_BotStarsWithdrawPlaceholder));
    amountInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f);
    amountInput.setTextColor(M3.onSurface());
    amountInput.setHintTextColor(M3.onSurfaceVariant());
    amountInput.setSingleLine(true);
    amountInput.setGravity(Gravity.CENTER_VERTICAL);
    android.graphics.drawable.GradientDrawable field = VgSheets.roundRect(0, 12f);
    field.setStroke(Screen.dp(1f), M3.outlineVariant());
    amountInput.setBackground(field);
    amountInput.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
    amountInput.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged (CharSequence s, int start, int count, int after) { }

      @Override
      public void onTextChanged (CharSequence s, int start, int before, int count) { }

      @Override
      public void afterTextChanged (Editable s) {
        updateWithdrawButton();
      }
    });
    content.addView(amountInput, VgSheets.params(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(52f), 0, 12f));

    withdrawButton = VgSheets.newFilledButton(context, "", v -> requestWithdrawal());
    content.addView(withdrawButton, VgSheets.buttonParams(0));
    if (!args.isSelf) {
      TextView ads = VgSheets.newTonalButton(context, Lang.getString(R.string.vg_MonetizationStarsAds), v -> openAds());
      content.addView(ads, VgSheets.buttonParams(8f));
    }
    content.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    updateBalanceView();
    return content;
  }

  private @Nullable TdApi.StarRevenueStatus status () {
    return statistics != null ? statistics.status : null;
  }

  private void updateBalanceView () {
    TdApi.StarRevenueStatus status = status();
    if (balanceAmountView == null || status == null) {
      return;
    }
    balanceAmountView.setText(Stars.withIcons(Stars.amount(status.availableAmount)));
    String usd = usdText(status.availableAmount);
    balanceUsdView.setText(usd != null ? usd : "");
    boolean enabled = status.withdrawalEnabled;
    amountInput.setVisibility(enabled ? View.VISIBLE : View.GONE);
    withdrawButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
    updateWithdrawButton();
  }

  /** Amount entered by the user, or the whole available balance. */
  private long withdrawalAmount () {
    TdApi.StarRevenueStatus status = status();
    String text = amountInput != null ? amountInput.getText().toString().trim() : "";
    if (!text.isEmpty()) {
      return StringUtils.parseLong(text);
    }
    return status != null ? status.availableAmount.starCount : 0;
  }

  private void updateWithdrawButton () {
    TdApi.StarRevenueStatus status = status();
    if (withdrawButton == null || status == null) {
      return;
    }
    String text = amountInput.getText().toString().trim();
    CharSequence label;
    if (status.nextWithdrawalIn > 0) {
      label = Lang.getString(R.string.vg_BotStarsButtonWithdrawShortUntil) + " · " + Lang.getDuration(status.nextWithdrawalIn);
    } else if (text.isEmpty()) {
      label = Lang.getString(R.string.vg_BotStarsButtonWithdrawShortAll);
    } else {
      label = Stars.withIcons(Lang.plural(R.string.vg_BotStarsButtonWithdrawShort, withdrawalAmount()));
    }
    withdrawButton.setText(label);
    VgSheets.setButtonEnabled(withdrawButton, status.nextWithdrawalIn <= 0 && Stars.isPositive(status.availableAmount));
  }

  private void requestWithdrawal () {
    TdApi.StarRevenueStatus status = status();
    if (status == null) {
      return;
    }
    if (status.nextWithdrawalIn > 0) {
      UI.showToast(VgSheets.markdown(Lang.getString(R.string.vg_BotStarsWithdrawalToast, Lang.getDuration(status.nextWithdrawalIn))), Toast.LENGTH_SHORT);
      return;
    }
    long amount = withdrawalAmount();
    long min = tdlib.options().starWithdrawalCountMin;
    if (amount < min) {
      UI.showToast(VgSheets.markdown(Lang.plural(R.string.vg_BotStarsWithdrawMinLimit, min)), Toast.LENGTH_SHORT);
      return;
    }
    if (amount > status.availableAmount.starCount) {
      UI.showToast(R.string.vg_BotStarsWithdrawTooMuch, Toast.LENGTH_SHORT);
      return;
    }
    // Main confirmation, the second one (3 s lock), then the 2-Step Verification password.
    VgSheets.showLockedDialog(this,
      Lang.getString(R.string.vg_BotStarsWithdrawTitle),
      Stars.withIcons(VgSheets.markdown(Lang.getString(R.string.vg_BotStarsWithdrawConfirm, Stars.amount(amount)))),
      Lang.getString(R.string.vg_MonetizationWithdraw),
      null, null, 0,
      confirmed -> {
        if (!confirmed) {
          return;
        }
        StarsUi.confirmAgain(this, Lang.getString(R.string.vg_StarsConfirmAgainWithdraw, Stars.format(amount)), Lang.getString(R.string.vg_MonetizationWithdraw), confirmedAgain -> {
          if (confirmedAgain) {
            askPasswordAndWithdraw(amount);
          }
        });
      }
    );
  }

  private void askPasswordAndWithdraw (long amount) {
    tdlib.send(new TdApi.GetPasswordState(), (passwordState, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (!passwordState.hasPassword) {
        showSecurityCheck(true);
        return;
      }
      PasswordController controller = new PasswordController(context, tdlib);
      controller.setArguments(new PasswordController.Args(PasswordController.MODE_CONFIRM, passwordState).setSuccessListener(password -> withdraw(amount, password)));
      navigateTo(controller);
    }));
  }

  private void withdraw (long amount, String password) {
    Args args = getArgumentsStrict();
    tdlib.send(new TdApi.GetStarWithdrawalUrl(args.ownerId, amount, password), (httpUrl, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        String message = error.message != null ? error.message : "";
        if (message.startsWith("PASSWORD_TOO_FRESH") || message.startsWith("SESSION_TOO_FRESH") || message.startsWith("PASSWORD_MISSING")) {
          showSecurityCheck(message.startsWith("PASSWORD_MISSING"));
        } else {
          UI.showError(error);
        }
        return;
      }
      StarsUi.openUrl(this, httpUrl.url);
    }));
  }

  /** «Security Check» of the official app: withdrawals need 2-Step Verification enabled 7 days and a 24 hours old session. */
  private void showSecurityCheck (boolean needPassword) {
    CharSequence text = VgSheets.markdown("• " + Lang.getString(R.string.vg_EditAdminTransferAlertText1) + "\n• " + Lang.getString(R.string.vg_EditAdminTransferAlertText2) + "\n\n" + Lang.getString(R.string.vg_EditAdminTransferAlertText3));
    VgSheets.showLockedDialog(this, Lang.getString(R.string.vg_EditAdminTransferAlertTitle), text,
      Lang.getString(needPassword ? R.string.vg_EditAdminTransferSetPassword : R.string.OK), null, null, 0,
      confirmed -> {
        if (confirmed && needPassword) {
          Settings2FAController c = new Settings2FAController(context, tdlib);
          c.setArguments(new Settings2FAController.Args(null));
          navigateTo(c);
        }
      });
  }

  private void openAds () {
    tdlib.send(new TdApi.GetStarAdAccountUrl(getArgumentsStrict().ownerId), (httpUrl, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
      } else {
        StarsUi.openUrl(this, httpUrl.url);
      }
    }));
  }

  @Override
  public void onStarRevenueStatusUpdated (TdApi.MessageSender ownerId, TdApi.StarRevenueStatus status) {
    runOnUiThreadOptional(() -> {
      Args args = getArgumentsStrict();
      if (statistics != null && Td.getSenderId(ownerId) == Td.getSenderId(args.ownerId)) {
        statistics.status = status;
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsAvailable);
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsCurrent);
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsTotal);
        updateBalanceView();
        if (transactions != null) {
          transactions.reload();
        }
      }
    });
  }

  @Override
  public void onClick (View v) {
    // Rows of the overview are informational.
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().removeOptionListener(this);
  }
}
