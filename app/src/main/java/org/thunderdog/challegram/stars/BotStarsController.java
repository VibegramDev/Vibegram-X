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
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.charts.Chart;
import org.thunderdog.challegram.charts.data.ChartDataUtil;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.PasswordController;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.Settings2FAController;
import org.thunderdog.challegram.ui.SettingHolder;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

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

  private SettingsAdapter adapter;
  private @Nullable StarsTransactions.Section transactions;
  private @Nullable TdApi.StarRevenueStatistics statistics;
  private double usdRate;

  /** Amount typed in the withdrawal field; empty — the whole available balance. */
  private String amountText = "";
  private boolean withdrawalShown;

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
        }
      }

      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        TdApi.StarRevenueStatus status = status();
        view.setAmount(status != null ? Stars.amount(status.availableAmount) : Stars.STAR + " …", null, 30f);
        String usd = status != null ? usdText(status.availableAmount) : null;
        view.setCaption(usd);
      }

      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        if (item.getId() == R.id.btn_vgBotStarsAmount) {
          editText.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);
        }
      }

      @Override
      protected void setM3Button (ListItem item, M3ButtonView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgBotStarsWithdraw) {
          TdApi.StarRevenueStatus status = status();
          view.setText(withdrawLabel(status));
          view.setEnabled(status != null && status.nextWithdrawalIn <= 0 && Stars.isPositive(status.availableAmount));
        } else {
          super.setM3Button(item, view, isUpdate);
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
    adapter.setSegmented(StarsTransactions.LIST_TYPE_TRANSACTION);
    adapter.setTextChangeListener((id, item, v) -> {
      if (id == R.id.btn_vgBotStarsAmount) {
        amountText = v.getText().toString().trim();
        item.setStringValue(amountText);
        adapter.updateValuedSettingById(R.id.btn_vgBotStarsWithdraw);
      }
    });
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
    return usd != null ? text + "   " + usd : text;
  }

  /**
   * «≈ $274.18». TDLib passes usdRate in US cents per Star (1.3 = $0.013, starRevenueStatistics is the server rate
   * × 100), so Stars × usdRate is the amount in cents.
   */
  private @Nullable String usdText (TdApi.StarAmount amount) {
    if (usdRate <= 0) {
      return null;
    }
    double stars = amount.starCount + amount.nanostarCount / 1e9;
    return "≈ " + Stars.usd(Math.round(stars * usdRate));
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
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER, R.id.btn_vgBotStarsBalance));
    TdApi.StarRevenueStatus status = status();
    withdrawalShown = status != null && status.withdrawalEnabled;
    if (withdrawalShown) {
      items.add(new ListItem(ListItem.TYPE_EDITTEXT, R.id.btn_vgBotStarsAmount, 0, R.string.vg_BotStarsWithdrawPlaceholder)
        .setStringValue(amountText)
        .setInputFilters(new InputFilter[] {new InputFilter.LengthFilter(12)}));
      items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgBotStarsWithdraw));
    }
    if (!args.isSelf) {
      items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgBotStarsAds, 0, R.string.vg_MonetizationStarsAds).setIntValue(M3ButtonView.STYLE_TONAL));
    }
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

  private @Nullable TdApi.StarRevenueStatus status () {
    return statistics != null ? statistics.status : null;
  }

  private void updateBalanceView () {
    TdApi.StarRevenueStatus status = status();
    if (status == null) {
      return;
    }
    if (status.withdrawalEnabled != withdrawalShown) {
      buildItems();
      return;
    }
    adapter.updateValuedSettingById(R.id.btn_vgBotStarsBalance);
    adapter.updateValuedSettingById(R.id.btn_vgBotStarsWithdraw);
  }

  /** Amount entered by the user, or the whole available balance. */
  private long withdrawalAmount () {
    TdApi.StarRevenueStatus status = status();
    if (!amountText.isEmpty()) {
      return StringUtils.parseLong(amountText);
    }
    return status != null ? status.availableAmount.starCount : 0;
  }

  private CharSequence withdrawLabel (@Nullable TdApi.StarRevenueStatus status) {
    if (status != null && status.nextWithdrawalIn > 0) {
      return Lang.getString(R.string.vg_BotStarsButtonWithdrawShortUntil) + " · " + Lang.getDuration(status.nextWithdrawalIn);
    }
    if (amountText.isEmpty()) {
      return Lang.getString(R.string.vg_BotStarsButtonWithdrawShortAll);
    }
    return Lang.plural(R.string.vg_BotStarsButtonWithdrawShort, withdrawalAmount());
  }

  private void requestWithdrawal () {
    TdApi.StarRevenueStatus status = status();
    if (status == null) {
      return;
    }
    if (status.nextWithdrawalIn > 0) {
      UI.showToast(StarsUi.markdownSpans(this, Lang.getString(R.string.vg_BotStarsWithdrawalToast, Lang.getDuration(status.nextWithdrawalIn))), Toast.LENGTH_SHORT);
      return;
    }
    long amount = withdrawalAmount();
    long min = tdlib.options().starWithdrawalCountMin;
    if (amount < min) {
      UI.showToast(StarsUi.markdownSpans(this, Lang.plural(R.string.vg_BotStarsWithdrawMinLimit, min)), Toast.LENGTH_SHORT);
      return;
    }
    if (amount > status.availableAmount.starCount) {
      UI.showToast(R.string.vg_BotStarsWithdrawTooMuch, Toast.LENGTH_SHORT);
      return;
    }
    // Main confirmation, the second one (3 s lock), then the 2-Step Verification password.
    StarsUi.confirm(this,
      Lang.getString(R.string.vg_BotStarsWithdrawTitle),
      Lang.getString(R.string.vg_BotStarsWithdrawConfirm, Stars.amount(amount)),
      Lang.getString(R.string.vg_MonetizationWithdraw),
      0, false,
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
    CharSequence text = "• " + Lang.getString(R.string.vg_EditAdminTransferAlertText1) + "\n• " + Lang.getString(R.string.vg_EditAdminTransferAlertText2) + "\n\n" + Lang.getString(R.string.vg_EditAdminTransferAlertText3);
    StarsUi.confirm(this, Lang.getString(R.string.vg_EditAdminTransferAlertTitle), text,
      Lang.getString(needPassword ? R.string.vg_EditAdminTransferSetPassword : R.string.OK), 0, false,
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
    if (v.getId() == R.id.btn_vgBotStarsWithdraw) {
      requestWithdrawal();
    } else if (v.getId() == R.id.btn_vgBotStarsAds) {
      openAds();
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().removeOptionListener(this);
  }
}
