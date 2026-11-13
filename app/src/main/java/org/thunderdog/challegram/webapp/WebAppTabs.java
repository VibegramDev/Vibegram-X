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
package org.thunderdog.challegram.webapp;

import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.collection.SparseArrayCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.telegram.GlobalAccountListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.RootFrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.vkryl.core.reference.ReferenceList;

/**
 * Minimized mini apps: per account, in memory only, newest first. Each of them keeps its WebView
 * running, so their number is limited and they are closed when the system is low on memory.
 */
@UiThread
public final class WebAppTabs {
  interface Listener {
    void onTabsChanged (int accountId);
  }

  private static final int MAX_TABS = 5;

  /** Height of the strip with one tab and with a stack of tabs. */
  static final float STRIP_HEIGHT_SINGLE = 60f, STRIP_HEIGHT_STACK = 68f;

  private static final SparseArrayCompat<ArrayList<WebAppTab>> tabs = new SparseArrayCompat<>();
  // Without the cached iterator: a listener may change the tabs while they are being notified
  private static final ReferenceList<Listener> listeners = new ReferenceList<>(false, false, null);

  private static boolean isObserving;

  private static final GlobalAccountListener accountListener = new GlobalAccountListener() {
    @Override
    public void onAccountSwitched (TdlibAccount newAccount, TdApi.User profile, int reason, TdlibAccount oldAccount) {
      // Apps of an account that is not shown would keep running unnoticed
      if (oldAccount != null && oldAccount.id != newAccount.id) {
        closeApps(oldAccount.id);
      }
    }

    @Override
    public void onAuthorizationStateChanged (TdlibAccount account, TdApi.AuthorizationState authorizationState, int status) {
      // Also covers sessions terminated from another device. The slot of an unauthorized
      // account is reused by the next login, which must not see the apps of the previous user.
      if (status == Tdlib.Status.UNAUTHORIZED) {
        closeApps(account.id);
      }
    }
  };

  private static final ComponentCallbacks2 memoryListener = new ComponentCallbacks2() {
    @Override
    public void onTrimMemory (int level) {
      // TRIM_MEMORY_UI_HIDDEN and TRIM_MEMORY_BACKGROUND only mean that the app is not visible
      if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
        closeAll();
      }
    }

    @Override
    public void onLowMemory () {
      closeAll();
    }

    @Override
    public void onConfigurationChanged (@NonNull Configuration newConfig) { }
  };

  private WebAppTabs () { }

  public static void add (WebAppTab tab) {
    int accountId = tab.tdlib().id();
    ArrayList<WebAppTab> list = tabs.get(accountId);
    if (list == null) {
      list = new ArrayList<>();
      tabs.put(accountId, list);
    } else {
      list.remove(tab);
    }
    list.add(0, tab);
    while (list.size() > MAX_TABS) {
      // Removed before closing, so the list does not change under this loop
      list.remove(list.size() - 1).closeFromTab(true);
    }
    notifyTabsChanged(accountId);
  }

  public static void remove (WebAppTab tab) {
    int accountId = tab.tdlib().id();
    ArrayList<WebAppTab> list = tabs.get(accountId);
    if (list != null && list.remove(tab)) {
      if (list.isEmpty()) {
        tabs.remove(accountId);
      }
      notifyTabsChanged(accountId);
    }
  }

  private static void closeApps (int accountId) {
    if (WebApp.isSupported()) {
      WebAppController.closeAll(accountId);
    }
    // Closed controllers remove their tabs; nothing of the account may outlive this,
    // as its id is reused by the next account
    if (tabs.get(accountId) != null) {
      tabs.remove(accountId);
      notifyTabsChanged(accountId);
    }
  }

  private static void closeAll () {
    ArrayList<WebAppTab> all = new ArrayList<>();
    for (int i = 0; i < tabs.size(); i++) {
      all.addAll(tabs.valueAt(i));
    }
    for (int i = 0; i < all.size(); i++) {
      all.get(i).closeFromTab(true);
    }
  }

  public static boolean contains (WebAppTab tab) {
    ArrayList<WebAppTab> list = tabs.get(tab.tdlib().id());
    return list != null && list.contains(tab);
  }

  public static List<WebAppTab> list (int accountId) {
    ArrayList<WebAppTab> list = tabs.get(accountId);
    return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
  }

  public static int getStripHeight (int accountId) {
    ArrayList<WebAppTab> list = tabs.get(accountId);
    int count = list != null ? list.size() : 0;
    if (count == 0) {
      return 0;
    }
    return Screen.dp(count == 1 ? STRIP_HEIGHT_SINGLE : STRIP_HEIGHT_STACK);
  }

  /**
   * Adds the strip of tabs to the activity, right above its content, so popups added later
   * stay above it. The strip hides itself while the content is covered.
   */
  public static void attach (BaseActivity activity) {
    if (!isObserving) {
      isObserving = true;
      TdlibManager.instance().global().addAccountListener(accountListener);
      activity.getApplicationContext().registerComponentCallbacks(memoryListener);
    }
    RootFrameLayout rootView = activity.getRootView();
    View contentView = activity.getContentView();
    WebAppTabsView stripView = new WebAppTabsView(activity);
    int index = rootView.indexOfChild(contentView);
    if (index != -1) {
      rootView.addView(stripView, index + 1);
    } else {
      rootView.addView(stripView);
    }
  }

  static void addListener (Listener listener) {
    listeners.add(listener);
  }

  static void removeListener (Listener listener) {
    listeners.remove(listener);
  }

  private static void notifyTabsChanged (int accountId) {
    for (Listener listener : listeners) {
      listener.onTabsChanged(accountId);
    }
  }
}
