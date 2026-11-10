/*
 * This file is a part of Telegram X
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

import android.view.View;
import android.widget.LinearLayout;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Topics of a chat as tabs of a {@link MessagesController}: forum topics ({@link ForumTopicTabs}) or conversations of
 * channel direct messages ({@link DirectMessagesTabs}). Each tab is a regular chat screen on the navigation stack.
 */
public interface ChatTopicTabs {
  boolean isSideTabs ();
  View getHeaderView ();
  default View wrapContent (View content) {
    return content;
  }
  void fillMenuItems (HeaderView header, LinearLayout menu, int iconColorId);
  /** Header buttons: true if handled. */
  boolean onMenuItemPressed (int id);
  /** Items of the more menu: true if handled. */
  boolean onMoreItemPressed (int id);
  void destroy ();

  /** Header with the tabs instead of the chat title, between the back button and the menu. */
  static ViewPagerHeaderViewCompact newHeaderView (ViewController<?> owner, int menuButtonsWidth, ViewPagerTopView.OnItemClickListener listener) {
    ViewPagerHeaderViewCompact headerView = new ViewPagerHeaderViewCompact(owner.context());
    FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) headerView.getRecyclerView().getLayoutParams();
    if (Lang.rtl()) {
      params.rightMargin = Screen.dp(56f);
      params.leftMargin = menuButtonsWidth;
    } else {
      params.leftMargin = Screen.dp(56f);
      params.rightMargin = menuButtonsWidth;
    }
    headerView.getTopView().setOnItemClickListener(listener);
    owner.addThemeInvalidateListener(headerView.getTopView());
    return headerView;
  }

  /** Puts the screen in place of the one with the tabs. */
  static boolean replace (ViewController<?> owner, ViewController<?> c) {
    c.addOneShotFocusListener(() -> {
      if (c.stackSize() > 1 && c.stackItemAt(c.stackSize() - 2) == owner) {
        c.destroyStackItemAt(c.stackSize() - 2);
      }
    });
    return owner.navigateTo(c);
  }
}
