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
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.SettingHolder;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.InfoHeaderView;

import java.util.ArrayList;

/**
 * «Telegram Stars» of the current user, as in the official app: the balance, «Buy More Stars», the revenue of the
 * user (paid messages and other income, withdrawable through Fragment) and the transactions.
 */
public class StarsController extends RecyclerViewController<Void> implements View.OnClickListener, TdlibOptionListener {
  public StarsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgStars;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_TelegramStars);
  }

  private SettingsAdapter adapter;
  private StarsTransactions.Section transactions;
  private @Nullable TdApi.StarAmount loadedBalance;
  private TdApi.StarRevenueStatus revenueStatus;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    transactions = new StarsTransactions.Section(this, tdlib, new TdApi.MessageSenderUser(tdlib.myUserId()));
    transactions.onBalanceLoaded = amount -> {
      if (tdlib.ownedStarCount() == null) {
        loadedBalance = amount;
        adapter.updateValuedSettingById(R.id.btn_vgStarsHeader);
      }
    };
    adapter = new SettingsAdapter(this) {
      @Override
      protected SettingHolder initCustom (ViewGroup parent, int customViewType) {
        if (customViewType == StarsTransactions.VIEW_TABS) {
          return new SettingHolder(transactions.newTabsView(parent.getContext()));
        }
        if (customViewType == StarsTransactions.VIEW_TRANSACTION) {
          return new SettingHolder(transactions.newTransactionView(parent.getContext()));
        }
        return super.initCustom(parent, customViewType);
      }

      @Override
      protected void modifyCustom (SettingHolder holder, int position, ListItem item, int customViewType, View view, boolean isUpdate) {
        if (customViewType == StarsTransactions.VIEW_TRANSACTION) {
          transactions.bindTransaction(view, item);
        }
      }

      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        view.setIcon(new StarDrawable(Screen.dp(96f)), 96f);
        view.setTexts(tdlib, Lang.getString(R.string.vg_TelegramStars), StarsUi.linkText(Lang.getString(R.string.vg_TelegramStarsInfo), StarsUi.ABOUT_URL));
        TdApi.StarAmount balance = tdlib.ownedStarCount() != null ? tdlib.ownedStarCount() : loadedBalance;
        view.setAmount(balance != null ? Stars.amount(balance) : Stars.STAR + " …", null, 28f);
        view.setCaption(Lang.getString(R.string.vg_StarsYourBalanceLabel));
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgStarsRevenue) {
          view.setData(revenueStatus != null ? Stars.amount(revenueStatus.availableAmount) : "");
        }
      }
    };
    adapter.setSegmented(StarsTransactions.LIST_TYPE_TRANSACTION);

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER, R.id.btn_vgStarsHeader));
    if (!tdlib.options().starsPurchaseBlocked) {
      items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_vgStarsBuy, 0, R.string.vg_StarsBuyMore));
    }
    transactions.addItems(items);
    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    transactions.attach(adapter, recyclerView);

    tdlib.listeners().addOptionsListener(this);
    loadRevenue();
  }

  /** Users earn Stars too (paid messages, gifts); the revenue screen appears when there is something to withdraw. */
  private void loadRevenue () {
    tdlib.send(new TdApi.GetStarRevenueStatistics(new TdApi.MessageSenderUser(tdlib.myUserId()), org.thunderdog.challegram.theme.Theme.isDark()), (statistics, error) -> runOnUiThreadOptional(() -> {
      if (statistics != null && statistics.status != null) {
        setRevenueStatus(statistics.status);
      }
    }));
  }

  private void setRevenueStatus (TdApi.StarRevenueStatus status) {
    revenueStatus = status;
    if (adapter.indexOfViewById(R.id.btn_vgStarsRevenue) != -1) {
      adapter.updateValuedSettingById(R.id.btn_vgStarsRevenue);
      return;
    }
    if (!Stars.isPositive(status.totalAmount)) {
      return;
    }
    int index = adapter.indexOfViewById(R.id.btn_vgStarsBuy);
    if (index == -1) {
      index = adapter.indexOfViewById(R.id.btn_vgStarsHeader);
    }
    if (index == -1) {
      return;
    }
    adapter.addItems(index + 1,
      new ListItem(ListItem.TYPE_SHADOW_TOP),
      new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgStarsRevenue, R.drawable.baseline_account_balance_wallet_24, R.string.vg_StarsRevenueSelf),
      new ListItem(ListItem.TYPE_SHADOW_BOTTOM)
    );
  }

  @Override
  public void onOwnedStarCountChanged (TdApi.StarAmount starAmount) {
    runOnUiThreadOptional(() -> {
      adapter.updateValuedSettingById(R.id.btn_vgStarsHeader);
      // New purchases and payments show up on top of the list.
      transactions.refresh();
    });
  }

  @Override
  public void onStarRevenueStatusUpdated (TdApi.MessageSender ownerId, TdApi.StarRevenueStatus status) {
    if (tdlib.isSelfSender(ownerId)) {
      runOnUiThreadOptional(() -> setRevenueStatus(status));
    }
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_vgStarsBuy) {
      StarsBuySheet.show(this, tdlib, null);
    } else if (v.getId() == R.id.btn_vgStarsRevenue) {
      BotStarsController c = new BotStarsController(context, tdlib);
      c.setArguments(new BotStarsController.Args(new TdApi.MessageSenderUser(tdlib.myUserId()), true));
      navigateTo(c);
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().removeOptionListener(this);
  }
}
