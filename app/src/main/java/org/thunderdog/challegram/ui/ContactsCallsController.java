/*
 * This file is a part of Vibegram-X
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.dialogs.SearchManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.ViewPager;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Dock section "Contacts": the contacts and the recent calls on two tabs below the title (swipe or tap a tab), as the
 * other tabbed screens of TGX ({@link SettingsStickersController}). The calls have no item of their own in the dock;
 * holding "Contacts" in it opens "New contact" and "Recent calls", as Telegram for Android 12 does.
 */
public class ContactsCallsController extends ViewPagerController<Void> {
  public static final int POSITION_CONTACTS = 0;
  public static final int POSITION_CALLS = 1;

  private final int startPosition;

  public ContactsCallsController (Context context, Tdlib tdlib, int startPosition) {
    super(context, tdlib);
    this.startPosition = startPosition;
  }

  @Override
  public int getId () {
    return R.id.controller_vgContactsCalls;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.Contacts);
  }

  @Override
  protected int getBackButton () {
    // M3 top-level destination opened from the dock: no navigation icon, the dock switches destinations.
    return isTopLevelDestination() ? BackHeaderButton.TYPE_NONE : BackHeaderButton.TYPE_BACK;
  }

  @Override
  protected int getTitleStyle () {
    // Tabs right in the top bar instead of a "Contacts" title above a "Contacts" tab.
    return TITLE_STYLE_COMPACT;
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected boolean useDropPlayer () {
    return false;
  }

  @Override
  protected int getPagerItemCount () {
    return 2;
  }

  @Override
  protected String[] getPagerSections () {
    return new String[] {
      Lang.getString(R.string.Contacts),
      Lang.getString(R.string.Calls)
    };
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    pager.setOffscreenPageLimit(1);
    ViewPagerHeaderViewCompact header = (ViewPagerHeaderViewCompact) getCustomHeaderCell();
    if (header != null) {
      layoutTabs(header);
    }
    // The same search as in the contacts list before: contacts, then global results.
    generateChatSearchView(contentView);
    if (startPosition != POSITION_CONTACTS) {
      pager.setCurrentItem(startPosition, false);
    }
    prepareControllerForPosition(startPosition, this::executeScheduledAnimation);
  }

  /** Tabs where the title of a top-level destination is (18dp from the edge), clear of the header buttons. */
  private void layoutTabs (ViewPagerHeaderViewCompact header) {
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) header.getRecyclerView().getLayoutParams();
    int start = Screen.dp(18f) - Screen.dp(12f); // the item padding of ViewPagerTopView is 12dp
    int end = Screen.dp(49f) * 2; // "New contact" and search
    params.leftMargin = Lang.rtl() ? end : start;
    params.rightMargin = Lang.rtl() ? start : end;
    header.getRecyclerView().setLayoutParams(params);
    header.getTopView().setItemPadding(Screen.dp(12f));
  }

  @Override
  protected void handleLanguageDirectionChange () {
    super.handleLanguageDirectionChange();
    if (getCustomHeaderCell() instanceof ViewPagerHeaderViewCompact) {
      layoutTabs((ViewPagerHeaderViewCompact) getCustomHeaderCell());
    }
  }

  @Override
  public boolean needAsynchronousAnimation () {
    ViewController<?> c = getCachedControllerForPosition(startPosition);
    return c != null ? c.needAsynchronousAnimation() : super.needAsynchronousAnimation();
  }

  @Override
  public long getAsynchronousAnimationTimeout (boolean fastAnimation) {
    ViewController<?> c = getCachedControllerForPosition(startPosition);
    return c != null ? c.getAsynchronousAnimationTimeout(fastAnimation) : super.getAsynchronousAnimationTimeout(fastAnimation);
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, int position) {
    switch (position) {
      case POSITION_CONTACTS:
        return new PeopleController(this.context, this.tdlib);
      case POSITION_CALLS:
        return new CallListController(this.context, this.tdlib);
    }
    throw new IllegalArgumentException("position == " + position);
  }

  /** Recent calls from the dock menu while the section is already open. */
  public void showPage (int position) {
    if (getViewPager() != null && getCurrentPagerItemPosition() != position) {
      setCurrentPagerPosition(position, true);
    }
  }

  // Header: "New contact" on the contacts tab, search on both.

  @Override
  protected int getMenuId () {
    return R.id.menu_people;
  }

  @Override
  protected int getSearchMenuId () {
    return R.id.menu_clear;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_people) {
      header.addButton(menu, R.id.menu_btn_addContact, R.drawable.baseline_person_add_24, getHeaderIconColorId(), this, Screen.dp(49f))
        .setVisibility(startPosition == POSITION_CONTACTS ? View.VISIBLE : View.GONE);
      header.addSearchButton(menu, this);
    } else if (id == R.id.menu_clear) {
      header.addClearButton(menu, this);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_addContact) {
      PhoneController c = new PhoneController(context, tdlib);
      c.setMode(PhoneController.MODE_ADD_CONTACT);
      navigateTo(c);
    } else if (id == R.id.menu_btn_search) {
      openSearchMode();
    } else if (id == R.id.menu_btn_clear) {
      clearSearchInput();
    }
  }

  @Override
  public void onPageSelected (int position, int actualPosition) {
    super.onPageSelected(position, actualPosition);
    if (headerView != null) {
      headerView.updateButton(getMenuId(), R.id.menu_btn_addContact, position == POSITION_CONTACTS ? View.VISIBLE : View.GONE, 0);
    }
  }

  @Override
  protected View getSearchAntagonistView () {
    return getViewPager();
  }

  @Override
  protected int getChatSearchFlags () {
    return SearchManager.FLAG_NEED_TOP_CHATS | SearchManager.FLAG_ONLY_USERS | SearchManager.FLAG_ONLY_CONTACTS | SearchManager.FLAG_NO_BOTS | SearchManager.FLAG_NEED_GLOBAL_SEARCH;
  }
}
