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
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.RecyclerViewController;
import org.thunderdog.challegram.ui.SettingHolder;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.VgSheets;

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

  private static final int VIEW_HEADER = 1;

  private SettingsAdapter adapter;
  private StarsTransactions.Section transactions;
  private TextView balanceView;
  private TdApi.StarRevenueStatus revenueStatus;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    transactions = new StarsTransactions.Section(this, tdlib, new TdApi.MessageSenderUser(tdlib.myUserId()));
    transactions.onBalanceLoaded = amount -> {
      if (tdlib.ownedStarCount() == null && balanceView != null) {
        balanceView.setText(Stars.withIcons(Stars.amount(amount)));
      }
    };
    adapter = new SettingsAdapter(this) {
      @Override
      protected SettingHolder initCustom (ViewGroup parent, int customViewType) {
        if (customViewType == VIEW_HEADER) {
          return new SettingHolder(newHeaderView(parent.getContext()));
        }
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
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgStarsRevenue) {
          view.setData(revenueStatus != null ? Stars.withIcons(Stars.amount(revenueStatus.availableAmount)) : "");
        }
      }
    };
    adapter.setSegmented(StarsTransactions.LIST_TYPE_TRANSACTION);

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_CUSTOM - VIEW_HEADER, R.id.btn_vgStarsHeader));
    transactions.addItems(items);
    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    transactions.attach(adapter, recyclerView);

    tdlib.listeners().addOptionsListener(this);
    loadRevenue();
  }

  private View newHeaderView (Context context) {
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(Screen.dp(24f), Screen.dp(8f), Screen.dp(24f), Screen.dp(8f));
    content.addView(StarsUi.newStarIcon(context, 96f), VgSheets.centered(Screen.dp(96f), 8f, 12f));
    content.addView(VgSheets.newTitle(context, Lang.getString(R.string.vg_TelegramStars)), VgSheets.wide(0, 8f));
    TextView info = VgSheets.newBody(context, StarsUi.linkified(this, Lang.getString(R.string.vg_TelegramStarsInfo), StarsUi.ABOUT_URL), true);
    info.setTextColor(M3.onSurfaceVariant());
    VgSheets.setLinksClickable(info);
    content.addView(info, VgSheets.wide(0, 20f));

    // Balance card
    LinearLayout card = VgSheets.newCard(context);
    card.setPadding(Screen.dp(16f), Screen.dp(20f), Screen.dp(16f), Screen.dp(16f));
    balanceView = VgSheets.newTitle(context, StarsUi.ownBalance(tdlib));
    balanceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32f);
    balanceView.setTypeface(Fonts.getRobotoMedium());
    card.addView(balanceView, VgSheets.wide(0, 0));
    card.addView(VgSheets.newCaption(context, Lang.getString(R.string.vg_StarsYourBalanceLabel), true), VgSheets.wide(2f, 16f));
    TextView buy = VgSheets.newFilledButton(context, Lang.getString(R.string.vg_StarsBuyMore), v -> StarsBuySheet.show(this, tdlib, null));
    card.addView(buy, VgSheets.buttonParams(0));
    if (tdlib.options().starsPurchaseBlocked) {
      buy.setVisibility(View.GONE);
    }
    content.addView(card, VgSheets.wide(0, 8f));
    content.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return content;
  }

  /** Users earn Stars too (paid messages, gifts); the revenue screen appears when there is something to withdraw. */
  private void loadRevenue () {
    tdlib.send(new TdApi.GetStarRevenueStatistics(new TdApi.MessageSenderUser(tdlib.myUserId()), org.thunderdog.challegram.theme.Theme.isDark()), (statistics, error) -> runOnUiThreadOptional(() -> {
      if (statistics == null || statistics.status == null) {
        return;
      }
      boolean hadRevenue = revenueStatus != null;
      revenueStatus = statistics.status;
      if (!Stars.isPositive(revenueStatus.totalAmount)) {
        return;
      }
      if (hadRevenue) {
        adapter.updateValuedSettingById(R.id.btn_vgStarsRevenue);
        return;
      }
      int index = adapter.indexOfViewById(R.id.btn_vgStarsHeader);
      if (index == -1) {
        return;
      }
      adapter.addItems(index + 1,
        new ListItem(ListItem.TYPE_SHADOW_TOP),
        new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgStarsRevenue, R.drawable.baseline_account_balance_wallet_24, R.string.vg_StarsRevenueSelf),
        new ListItem(ListItem.TYPE_SHADOW_BOTTOM)
      );
    }));
  }

  @Override
  public void onOwnedStarCountChanged (TdApi.StarAmount starAmount) {
    runOnUiThreadOptional(() -> {
      if (balanceView != null) {
        balanceView.setText(StarsUi.ownBalance(tdlib));
      }
      // New purchases and payments show up in the list.
      transactions.reload();
    });
  }

  @Override
  public void onStarRevenueStatusUpdated (TdApi.MessageSender ownerId, TdApi.StarRevenueStatus status) {
    if (tdlib.isSelfSender(ownerId)) {
      runOnUiThreadOptional(this::loadRevenue);
    }
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_vgStarsRevenue) {
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
