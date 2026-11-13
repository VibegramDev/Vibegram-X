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

import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.collection.SparseArrayCompat;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.RootFrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.vkryl.core.reference.ReferenceList;

/**
 * Minimized mini apps (ui.md §4.3): per account, in memory only, newest first, without a
 * maximum, like in the official app.
 */
@UiThread
public final class WebAppTabs {
  public interface Filter {
    boolean accept (WebAppTab tab);
  }

  interface Listener {
    void onTabsChanged (int accountId);
  }

  private static final SparseArrayCompat<ArrayList<WebAppTab>> tabs = new SparseArrayCompat<>();
  // Without the cached iterator: a listener may change the tabs while they are being notified
  private static final ReferenceList<Listener> listeners = new ReferenceList<>(false, false, null);

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
    notifyTabsChanged(accountId);
  }

  public static void remove (WebAppTab tab) {
    int accountId = tab.tdlib().id();
    ArrayList<WebAppTab> list = tabs.get(accountId);
    if (list != null && list.remove(tab)) {
      notifyTabsChanged(accountId);
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

  public static @Nullable WebAppTab find (int accountId, Filter filter) {
    ArrayList<WebAppTab> list = tabs.get(accountId);
    if (list != null) {
      for (int i = 0; i < list.size(); i++) {
        WebAppTab tab = list.get(i);
        if (filter.accept(tab)) {
          return tab;
        }
      }
    }
    return null;
  }

  public static int getStripHeight (int accountId) {
    ArrayList<WebAppTab> list = tabs.get(accountId);
    int count = list != null ? list.size() : 0;
    if (count == 0) {
      return 0;
    }
    return Screen.dp(count == 1 ? 60f : 68f);
  }

  /**
   * Adds the strip of tabs to the activity, right above its content, so popups added later
   * stay above it.
   */
  public static void attach (BaseActivity activity) {
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

  static @Nullable WebAppTabsView findStrip (BaseActivity activity) {
    RootFrameLayout rootView = activity.getRootView();
    if (rootView != null) {
      for (int i = 0; i < rootView.getChildCount(); i++) {
        View view = rootView.getChildAt(i);
        if (view instanceof WebAppTabsView) {
          return (WebAppTabsView) view;
        }
      }
    }
    return null;
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
