/*
 * This file is a part of Vibegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.ui;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.GlobalAccountListener;
import org.thunderdog.challegram.telegram.GlobalCountersListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.widget.M3AccountItemView;

import java.util.ArrayList;
import java.util.List;

/**
 * «Аккаунты» group shared by the main Settings page and the «Аккаунт» section: the other logged-in accounts
 * (tap switches to it, the same call the old drawer made) and «Добавить аккаунт» (the existing login flow).
 * The current account is not listed: it is the profile shown above.
 */
final class SettingsAccountsBlock implements GlobalAccountListener, GlobalCountersListener {
  /** Custom view type of the account rows ({@code ListItem.TYPE_CUSTOM - VIEW_TYPE_ACCOUNT}). */
  static final int VIEW_TYPE_ACCOUNT = 70;
  static final int LIST_TYPE_ACCOUNT = ListItem.TYPE_CUSTOM - VIEW_TYPE_ACCOUNT;

  private final ViewController<?> controller;
  private final Tdlib tdlib;
  private SettingsAdapter adapter;
  private long lastSwitchTime;

  SettingsAccountsBlock (ViewController<?> controller, Tdlib tdlib) {
    this.controller = controller;
    this.tdlib = tdlib;
  }

  void attach (SettingsAdapter adapter) {
    this.adapter = adapter;
    TdlibManager.instance().global().addAccountListener(this);
    TdlibManager.instance().global().addCountersListener(this);
  }

  void destroy () {
    TdlibManager.instance().global().removeAccountListener(this);
    TdlibManager.instance().global().removeCountersListener(this);
  }

  void fillItems (List<ListItem> items) {
    items.add(new ListItem(ListItem.TYPE_HEADER, R.id.vg_accountsHeader, 0, R.string.vg_Accounts));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    for (TdlibAccount account : TdlibManager.instance().getActiveAccounts()) {
      if (account.id == tdlib.id()) {
        continue;
      }
      items.add(new ListItem(LIST_TYPE_ACCOUNT, R.id.account).setLongId(account.id).setData(account));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    }
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_addAccount, R.drawable.baseline_person_add_24, R.string.AddAccount));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
  }

  SettingHolder createView (ViewGroup parent) {
    M3AccountItemView view = new M3AccountItemView(parent.getContext(), tdlib);
    RippleSupport.setSimpleWhiteBackground(view, controller);
    if (controller instanceof View.OnClickListener) {
      view.setOnClickListener((View.OnClickListener) controller);
    }
    controller.addThemeInvalidateListener(view);
    return new SettingHolder(view);
  }

  void bindView (ListItem item, View view, boolean isUpdate) {
    if (view instanceof M3AccountItemView && item.getData() instanceof TdlibAccount) {
      ((M3AccountItemView) view).setAccount((TdlibAccount) item.getData(), isUpdate);
    }
  }

  /** @return true if the click was an account block row. */
  boolean onClick (View v) {
    final int viewId = v.getId();
    if (viewId == R.id.account) {
      Object tag = v.getTag();
      if (tag instanceof ListItem && ((ListItem) tag).getData() instanceof TdlibAccount) {
        TdlibAccount account = (TdlibAccount) ((ListItem) tag).getData();
        long now = SystemClock.uptimeMillis();
        if (account.context().preferredAccountId() != account.id && (lastSwitchTime == 0 || now - lastSwitchTime >= 720)) {
          lastSwitchTime = now;
          account.context().changePreferredAccountId(account.id, TdlibManager.SWITCH_REASON_USER_CLICK);
        }
      }
      return true;
    }
    if (viewId == R.id.btn_addAccount) {
      tdlib.ui().addAccount(controller.context(), true, false);
      return true;
    }
    return false;
  }

  private void rebuild () {
    if (adapter == null || controller.isDestroyed()) {
      return;
    }
    int start = adapter.indexOfViewById(R.id.vg_accountsHeader);
    if (start == -1) {
      return;
    }
    List<ListItem> all = adapter.getItems();
    int end = start;
    while (end < all.size() && all.get(end).getViewType() != ListItem.TYPE_SHADOW_BOTTOM) {
      end++;
    }
    if (end >= all.size()) {
      return;
    }
    List<ListItem> items = new ArrayList<>();
    fillItems(items);
    adapter.removeRange(start, end - start + 1);
    adapter.addItems(start, items.toArray(new ListItem[0]));
  }

  private void updateAccountRows () {
    if (adapter == null || controller.isDestroyed()) {
      return;
    }
    List<ListItem> items = adapter.getItems();
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).getViewType() == LIST_TYPE_ACCOUNT) {
        adapter.notifyItemChanged(i);
      }
    }
  }

  // Global listeners (posted: some are called from TDLib threads)

  @Override
  public void onActiveAccountAdded (TdlibAccount account, int position) {
    controller.runOnUiThreadOptional(this::rebuild);
  }

  @Override
  public void onActiveAccountRemoved (TdlibAccount account, int position) {
    controller.runOnUiThreadOptional(this::rebuild);
  }

  @Override
  public void onActiveAccountMoved (TdlibAccount account, int fromPosition, int toPosition) {
    controller.runOnUiThreadOptional(this::rebuild);
  }

  @Override
  public void onAccountProfileChanged (TdlibAccount account, TdApi.User profile, boolean isCurrent, boolean isLoaded) {
    controller.runOnUiThreadOptional(this::updateAccountRows);
  }

  @Override
  public void onAccountProfilePhotoChanged (TdlibAccount account, boolean big, boolean isCurrent) {
    controller.runOnUiThreadOptional(this::updateAccountRows);
  }

  @Override
  public void onAccountProfileEmojiStatusChanged (TdlibAccount account, boolean isCurrent) {
    controller.runOnUiThreadOptional(this::updateAccountRows);
  }

  @Override
  public void onTotalUnreadCounterChanged (@NonNull TdApi.ChatList chatList, boolean isReset) {
    controller.runOnUiThreadOptional(this::updateAccountRows);
  }

  @Override
  public void onBadgeSettingsChanged () {
    controller.runOnUiThreadOptional(this::updateAccountRows);
  }
}
