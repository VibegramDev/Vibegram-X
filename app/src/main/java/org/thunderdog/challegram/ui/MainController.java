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
 *
 * File created on 27/05/2017
 */
package org.thunderdog.challegram.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.CheckResult;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.collection.SparseArrayCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.os.CancellationSignal;
import androidx.core.util.ObjectsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.dialogs.SearchManager;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.filegen.PhotoGenerationInfo;
import org.thunderdog.challegram.filegen.VideoGenerationInfo;
import org.thunderdog.challegram.loader.ImageReader;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MenuMoreWrap;
import org.thunderdog.challegram.navigation.OptionsLayout;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.RecyclerViewProvider;
import org.thunderdog.challegram.navigation.SettingsWrap;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.navigation.ToggleHeaderView2;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.sync.SyncAdapter;
import org.thunderdog.challegram.telegram.ChatFilter;
import org.thunderdog.challegram.telegram.ChatFolderStyle;
import org.thunderdog.challegram.telegram.ChatFoldersListener;
import org.thunderdog.challegram.telegram.CounterChangeListener;
import org.thunderdog.challegram.telegram.GlobalCountersListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCounter;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeDeprecated;
import org.thunderdog.challegram.theme.PropertyId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.TGMimeType;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Size;
import org.thunderdog.challegram.unsorted.Passcode;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.unsorted.Test;
import org.thunderdog.challegram.util.AppUpdater;
import org.thunderdog.challegram.util.FeatureAvailability;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.IconSpan;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.v.HeaderEditText;
import org.thunderdog.challegram.widget.AvatarView;
import org.thunderdog.challegram.widget.M3FolderRailView;
import org.thunderdog.challegram.widget.BubbleLayout;
import org.thunderdog.challegram.widget.FillingSpace;
import org.thunderdog.challegram.widget.M3MainTitleView;
import org.thunderdog.challegram.widget.M3SearchBarView;
import org.thunderdog.challegram.widget.M3Tooltip;
import org.thunderdog.challegram.widget.M3BottomScrimView;
import org.thunderdog.challegram.widget.MainNavigationBar;
import org.thunderdog.challegram.widget.M3AccountItemView;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.ShadowView;
import org.thunderdog.challegram.widget.SnackBar;
import org.thunderdog.challegram.widget.ViewPager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.BitwiseUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.ObjectUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.collection.LongSparseIntArray;
import me.vkryl.core.lambda.CancellableRunnable;
import me.vkryl.core.lambda.FutureBool;
import tgx.td.ChatId;
import tgx.td.ChatPosition;
import tgx.td.Td;
import tgx.td.TdConstants;

public class MainController extends ViewPagerController<Void> implements Menu, MoreDelegate, TdlibOptionListener, AppUpdater.Listener, ChatFoldersListener, GlobalCountersListener, Settings.ChatFolderSettingsListener {
  private static final long MAIN_PAGER_ITEM_ID = Long.MIN_VALUE;
  private static final long ARCHIVE_PAGER_ITEM_ID = Long.MIN_VALUE + 1;
  private static final long INVALID_PAGER_ITEM_ID = Long.MAX_VALUE;
  private static final int NO_PENDING_FILTER = -1;

  public MainController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_main;
  }

  private FrameLayoutFix mainWrap;
  /** Root of the main screen: hosts the dock section of the tablet left pane above the chat list, below the bar. */
  private FrameLayoutFix rootContentView;
  private @Nullable View tabletSectionView;
  private FrameLayoutFix pagerWrap;
  private MainNavigationBar navigationBar;
  /** Tablet: fades the chat list into surface behind the floating bar. */
  private M3BottomScrimView navigationScrim;
  /** Tablet layout only: M3 search app bar, the search bar in place of the app bar title. */
  private @Nullable M3SearchBarView searchBarView;
  /** Tablet layout with chat folders only: the folder tabs take the title place, search is an app bar button. */
  /** Phone: sections moved from the collapsed dock into the app bar while the chat list is scrolled. */
  /**
   * Every app bar menu filled for this screen. The tablet split layout has its own app bar for the left pane,
   * so after folding/unfolding the visible menu may be an older one: buttons are updated in all of them.
   */
  private final List<LinearLayout> mainMenus = new ArrayList<>();
  private final List<AvatarView> headerAvatarViews = new ArrayList<>();
  private @Nullable M3MainTitleView titleView;
  private CounterChangeListener navigationCounterListener;

  @Override
  protected View onCreateView (Context context) {
    initPagerSections();
    return super.onCreateView(context);
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    updateChatListNavigationInset();
    updateSnackBarOffset();
    updateBottomBarMargins();
    checkMargins();
    checkNavigationBarLayout();
  }

  private void updateBottomBarMargins () {
    if (bottomBar != null) {
      int height = extraBottomInsetWithoutIme;
      Views.setBottomMargin(bottomBar, height);
      bottomBarSpace.setLayoutHeight(height, true);
    }
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    this.rootContentView = contentView;
    if (BuildConfig.DEBUG) {
      Test.execute();
    }

    pagerWrap = new FrameLayoutFix(context);
    pagerWrap.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    pagerWrap.addView(pager);

    mainWrap = new FrameLayoutFix(context);
    mainWrap.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    mainWrap.addView(pagerWrap);
    generateChatSearchView(mainWrap);

    contentView.addView(mainWrap);

    navigationBar = new MainNavigationBar(context, this);
    navigationBar.setUser(tdlib, tdlib.myUserId());
    navigationBar.setCallback(this::onNavigationItemClick);
    navigationBar.setSettingsLongClickListener(v -> showAccountSwitcher());
    navigationBar.setCollapseListener(factor -> updateHeaderDockButtons());
    navigationBar.setUnreadCount(updateCounter(ChatPosition.CHAT_LIST_MAIN, navigationBar.getUnreadCounter(), tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN), false));
    navigationCounterListener = new CounterChangeListener() {
      @Override
      public void onChatCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, boolean availabilityChanged, int totalCount, int unreadCount, int unreadUnmutedCount) {
        updateNavigationCounter(chatList);
      }

      @Override
      public void onMessageCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, int unreadCount, int unreadUnmutedCount) {
        updateNavigationCounter(chatList);
      }
    };
    tdlib.listeners().addTotalChatCounterListener(navigationCounterListener);

    UI.setSoftInputMode(UI.getContext(context), Config.DEFAULT_WINDOW_PARAMS);

    navigationScrim = new M3BottomScrimView(context);
    navigationScrim.setShapeProvider((view, out) -> {
      if (navigationBar == null || !navigationBar.isBar() || navigationBar.getWidth() == 0) {
        return false;
      }
      float left = navigationBar.getLeft() - view.getLeft();
      float top = navigationBar.getTop() - view.getTop();
      out.set(left, top, left + navigationBar.getWidth(), top + navigationBar.getHeight());
      return true;
    }, MainNavigationBar.getBarRadius());
    addThemeInvalidateListener(navigationScrim);
    contentView.addView(navigationScrim);
    contentView.addView(navigationBar);
    navigationBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> navigationScrim.invalidate());
    checkNavigationBarLayout();

    makeStartupChecks();

    if (shareIntent != null) {
      shareIntentImpl();
    }

    tdlib.wallpaper().ensureWallpaperAvailability();
    tdlib.listeners().addOptionsListener(this);
    tdlib.listeners().subscribeToChatFoldersUpdates(this);
    tdlib.context().global().addCountersListener(this);
    Settings.instance().addChatFolderSettingsListener(this);
    if (Settings.instance().chatFoldersEnabled()) {
      if (this.chatFolderInfos != tdlib.chatFolders()) {
        updatePagerSections(true);
      }
      tdlib.settings().addChatListPositionListener(chatListPositionListener = new ChatListPositionListener());
    }

    prepareControllerForPosition(0, this::executeScheduledAnimation);

    if (headerCell != null) {
      headerCell.getTopView().setOnSlideOffListener(new ViewPagerTopView.OnSlideOffListener() {

        private long selectedPagerItemId = INVALID_PAGER_ITEM_ID;

        @Override
        public boolean onSlideOffPrepare (View view, MotionEvent event, int index) {
          if (hasFolders()) {
            return !useGlobalFilter() || globalFilter == FILTER_NONE;
          }
          return getPagerItemId(index) == MAIN_PAGER_ITEM_ID;
        }

        @Override
        public void onSlideOffStart (View view, MotionEvent event, int index) {
          selectedPagerItemId = getPagerItemId(index);
          setMenuVisible(view, selectedPagerItemId, true);
        }

        @Override
        public void onSlideOffFinish (View view, MotionEvent event, int index, boolean apply) {
          float offsetX = getOffsetX(view);
          float offsetY = getOffsetX(view);
          if (apply && selectedSection != -1) {
            View selectedView = menu.getChildAt(selectedSection);
            int selectedFilter = (int) selectedView.getTag(R.id.btn_filter);
            requestChatsFilter(selectedFilter, selectedPagerItemId);
            setSelectedSection(-1, event, offsetX, offsetY, true);
          } else {
            setSelectedSection(-1, event, offsetX, offsetY, false);
          }
          setMenuVisible(view, selectedPagerItemId, false);
          selectedPagerItemId = INVALID_PAGER_ITEM_ID;
        }

        @Override
        public void onSlideOffMovement (View view, MotionEvent event, int index) {
          float x = event.getX();
          float y = event.getY();
          float offsetX = getOffsetX(view);
          float offsetY = getOffsetY(view);
          x -= offsetX;
          y -= offsetY + mainWrap.getTranslationY() + pagerWrap.getTranslationY();
          int selectedSection = -1;
          if (x >= 0 && x < menu.getMeasuredWidth()) {
            int firstItemEnd = menu.getChildAt(0).getBottom();
            if (y <= firstItemEnd) {
              selectedSection = 0;
            } else if (y < menu.getHeight()) {
              for (int section = 1; section < menu.getChildCount(); section++) {
                View child = menu.getChildAt(section);
                if (y >= child.getTop() && y < child.getBottom() && child.getVisibility() != View.GONE) {
                  selectedSection = section;
                  break;
                }
              }
            }
          }
          setSelectedSection(selectedSection, event, offsetX, offsetY, false);
        }

        private float getOffsetX (View view) {
          return menu.getX() - getX(view, headerCell.getView());
        }

        private float getOffsetY (View view) {
          FrameLayoutFix.LayoutParams lp = (FrameLayoutFix.LayoutParams) menu.getLayoutParams();
          int verticalGravity = Gravity.VERTICAL_GRAVITY_MASK & lp.gravity;
          return verticalGravity == Gravity.BOTTOM ? -menu.getHeight() : view.getHeight();
        }
      });
      if (Settings.instance().chatFoldersEnabled()) {
        headerCell.getTopView().setCounterAlphaProvider(new ViewPagerTopView.CounterAlphaProvider() {
          @Override
          public float getTextAlpha (Counter counter, float alphaFactor) {
            return MathUtils.fromTo(1f, .5f + .5f * alphaFactor, counter.getMuteFactor());
          }
        });
      }
    }

    // tdlib.awaitConnection(this::unlockTabs);

    checkTabs();
    checkMargins();

    context().appUpdater().addListener(this);
    if (context().appUpdater().state() == AppUpdater.State.READY_TO_INSTALL) {
      onAppUpdateAvailable(context().appUpdater().flowType() == AppUpdater.FlowType.TELEGRAM_CHANNEL, true);
    }
  }

  @Override
  public CharSequence getName () {
    if (useGlobalFilter()) {
      return Lang.getString(getGlobalFilterName(globalFilter));
    }
    return Lang.getString(R.string.Chats);
  }

  private @Nullable ToggleHeaderView2 toggleHeaderView;

  private M3MainTitleView getTitleView () {
    if (titleView == null) {
      titleView = new M3MainTitleView(context, tdlib, this, this::showEmojiStatusSelector, getMenuId());
    }
    return titleView;
  }

  private void showEmojiStatusSelector (M3MainTitleView view) {
    int[] location = Views.getLocationInWindow(view);
    EmojiStatusSelectorEmojiPage.Wrapper c = new EmojiStatusSelectorEmojiPage.Wrapper(context, tdlib, this, new EmojiStatusSelectorEmojiPage.AnimationsEmojiStatusSetDelegate() {
      @Override
      public void onAnimationStart () {
        view.setIgnoreDrawEmojiStatus(true);
      }

      @Override
      public void onAnimationEnd () {
        view.setIgnoreDrawEmojiStatus(false);
      }

      @Override
      public int getDestX () {
        return location[0] + view.getEmojiLastDrawX() + Screen.dp(12);
      }

      @Override
      public int getDestY () {
        return location[1] + view.getEmojiLastDrawY() + Screen.dp(12);
      }
    });
    c.show();
  }

  private M3SearchBarView getSearchBarView () {
    if (searchBarView == null) {
      searchBarView = new M3SearchBarView(context, this, () -> {
        // Search bar: surfaceContainerHigh (m3.material.io/components/search); the app bar does not change on scroll.
        return M3.surfaceContainerHigh();
      }, BuildConfig.PROJECT_NAME, Lang.getString(R.string.Search), getMenuId());
      searchBarView.setOnBarClickListener(v -> openMainSearch());
    }
    return searchBarView;
  }

  @Override
  public View getCustomHeaderCell () {
    // Folder tabs are never in the app bar: they are at the bottom (phone), below the app bar or in a rail (tablet).
    // The app bar shows the app name with the emoji status (phone) or the search bar labeled with the app name (tablet).
    return isTabletNavigationBar() ? getSearchBarView() : getTitleView();
  }

  // Menu

  private int selectedSection = -1;
  private long downTime;

  private void setSelectedSection (int section, MotionEvent event, float xOffset, float yOffset, boolean apply) {
    float eventX = event.getX() - xOffset;
    float eventY = event.getY() - yOffset;
    if (this.selectedSection != section) {
      if (this.selectedSection != -1) {
        View view = menu.getChildAt(this.selectedSection);
        view.dispatchTouchEvent(MotionEvent.obtain(this.downTime, event.getEventTime(), apply ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL, eventX, eventY - view.getTop(), event.getMetaState()));
      }
      this.selectedSection = section;
      if (section != -1) {
        View view = menu.getChildAt(this.selectedSection);
        view.dispatchTouchEvent(MotionEvent.obtain(this.downTime = SystemClock.uptimeMillis(), event.getEventTime(), MotionEvent.ACTION_DOWN, eventX, eventY - view.getTop(), event.getMetaState()));
      }
    } else if (section != -1) {
      View view = menu.getChildAt(this.selectedSection);
      view.dispatchTouchEvent(MotionEvent.obtain(this.downTime, event.getEventTime(), MotionEvent.ACTION_MOVE, eventX, eventY - view.getTop(), event.getMetaState()));
    }
  }

  private @Nullable BubbleLayout menu;
  private boolean menuNeedArchive;
  private View menuAnchor;

  private @PendingFilter int pendingFilter = NO_PENDING_FILTER;
  private long pendingPagerItemId = INVALID_PAGER_ITEM_ID;
  private @Nullable ChatsController pendingChatsController;

  private void cancelPendingFilter () {
    if (pendingChatsController != null) {
      pendingChatsController.destroy();
      pendingChatsController = null;
    }
    this.pendingFilter = NO_PENDING_FILTER;
    this.pendingPagerItemId = INVALID_PAGER_ITEM_ID;
  }

  private int applyPendingFilter (@PendingFilter int filter, long pagerItemId, ChatsController chatsController) {
    if (this.pendingFilter != filter || this.pendingPagerItemId != pagerItemId || this.pendingChatsController != chatsController) {
      chatsController.destroy();
      return NO_POSITION;
    }

    this.pendingFilter = NO_PENDING_FILTER;
    this.pendingPagerItemId = INVALID_PAGER_ITEM_ID;
    this.pendingChatsController = null;

    int pagerItemPosition = getPagerItemPosition(pagerItemId);
    if (pagerItemPosition == NO_POSITION) {
      chatsController.destroy();
      return NO_POSITION;
    }
    if (!useGlobalFilter() || filter != globalFilter || filter == FILTER_NONE) {
      setSelectedFilter(pagerItemId, filter);
      replaceSectionItem(pagerItemId, pagerItemPosition);
    }
    replaceController(pagerItemId, chatsController);
    return pagerItemPosition;
  }

  private void replaceSectionItem (long pagerItemId, int pagerItemPosition) {
    if (headerCell == null) {
      return;
    }
    ViewPagerTopView.Item item = buildSectionItem(pagerItemId, pagerItemPosition);
    if (item == null) {
      return;
    }
    if (!hasFolders()) {
      getDefaultSectionItems().set(0, item);
    }
    headerCell.getTopView().setItemAt(pagerItemPosition, item, isFocused());
    if (folderRail != null && hasFolders()) {
      folderRail.setEntries(buildFolderRailEntries());
    }
  }

  private @Nullable ViewPagerTopView.Item buildSectionItem (long pagerItemId, int pagerItemPosition) {
    final ViewPagerTopView.Item item;
    if (hasFolders()) {
      int chatFolderStyle = tdlib.settings().chatFolderStyle();
      if (pagerItemId == MAIN_PAGER_ITEM_ID) {
        item = buildMainSectionItem(pagerItemPosition, chatFolderStyle);
      } else if (pagerItemId == ARCHIVE_PAGER_ITEM_ID) {
        item = buildArchiveSectionItem(pagerItemPosition, chatFolderStyle);
      } else {
        TdApi.ChatList chatList = pagerChatLists.get(pagerItemPosition);
        if (TD.isChatListFolder(chatList)) {
          TdApi.ChatListFolder chatListFolder = (TdApi.ChatListFolder) chatList;
          TdApi.ChatFolderInfo chatFolderInfo = tdlib.chatFolderInfo(chatListFolder.chatFolderId);
          if (chatFolderInfo == null) {
            return null;
          }
          item = buildSectionItem(pagerItemId, pagerItemPosition, chatList, chatFolderInfo, chatFolderStyle);
        } else {
          throw new UnsupportedOperationException("chatList = " + chatList);
        }
      }
    } else {
      int filter = getSelectedFilter(pagerItemId);
      if (filter == FILTER_NONE && !menuNeedArchive) {
        item = getDefaultMainItem();
      } else {
        CharSequence sectionName = getMenuSectionName(pagerItemId, pagerItemPosition, /* hasFolders */ false, ChatFolderStyle.LABEL_ONLY, /* upperCase */ false);
        item = new ViewPagerTopView.Item(sectionName);
      }
    }
    return item;
  }

  private void setNeedArchive (boolean needArchive) {
    if (this.menuNeedArchive != needArchive) {
      this.menuNeedArchive = needArchive;
    }
  }

  private void requestChatsFilter (@Filter int requestedFilter, long pagerItemId) {
    if (pagerItemId == INVALID_PAGER_ITEM_ID)
      return;
    int selectedFilter = getSelectedFilter(pagerItemId);
    if ((selectedFilter == requestedFilter && (requestedFilter != FILTER_NONE || (pagerItemId == MAIN_PAGER_ITEM_ID && !menuNeedArchive))) ||
        (pagerItemId == MAIN_PAGER_ITEM_ID && menuNeedArchive && selectedFilter == FILTER_NONE && requestedFilter == FILTER_ARCHIVE)) {
      cancelPendingFilter();
      int pagerItemPosition = getPagerItemPosition(pagerItemId);
      if (pagerItemPosition != NO_POSITION) {
        setCurrentPagerPosition(pagerItemPosition, true);
      }
      return;
    }
    if (this.pendingFilter == requestedFilter && this.pendingPagerItemId == pagerItemId) {
      return; // Still waiting to switch, do nothing
    }
    cancelPendingFilter();

    int position = getPagerItemPosition(pagerItemId);
    if (position == NO_POSITION)
      return;
    int filter;
    if (requestedFilter == FILTER_NONE) {
      filter = FILTER_NONE;
      if (pagerItemId == MAIN_PAGER_ITEM_ID) {
        setNeedArchive(false);
      }
    } else if (requestedFilter == FILTER_ARCHIVE) {
      filter = FILTER_NONE;
      if (pagerItemId == MAIN_PAGER_ITEM_ID) {
        setNeedArchive(selectedFilter != FILTER_NONE || !menuNeedArchive);
      }
    } else {
      filter = requestedFilter;
    }
    TdApi.ChatList chatList;
    if (pagerItemId == MAIN_PAGER_ITEM_ID) {
      chatList = menuNeedArchive ? ChatPosition.CHAT_LIST_ARCHIVE : ChatPosition.CHAT_LIST_MAIN;
    } else {
      chatList = pagerChatLists.get(position);
    }
    postApplyFilter(pagerItemId, chatList, filter);
  }

  private void postApplyFilter (long pagerItemId, TdApi.ChatList chatList, @Filter int filter) {
    ChatsController chatsController = newChatsController(chatList, filter);
    modifyNewPagerItemController(chatsController);

    this.pendingFilter = filter;
    this.pendingPagerItemId = pagerItemId;
    this.pendingChatsController = chatsController;

    chatsController.postOnAnimationExecute(() -> {
      int pagerItemPosition = applyPendingFilter(filter, pagerItemId, chatsController);
      if (pagerItemPosition != NO_POSITION && (!useGlobalFilter() || filter != globalFilter)) {
        setCurrentPagerPosition(pagerItemPosition, /* animated */ true);
      }
    });
  }

  private void layoutMenu (@NonNull View view) {
    if (menu == null || headerCell == null)
      return;
    menuAnchor = view;
    int menuWidth = menu.getMeasuredWidth();
    if (menuWidth == 0)
      return;
    float x = getX(view, headerCell.getView());
    boolean displayTabsAtBottom = displayTabsAtBottom();
    int viewWidth = headerCell.getTopView().getItemWidth(view);
    float translationX = x - menuWidth / 2 + viewWidth / 2;
    int dx = displayTabsAtBottom ? 0 : Screen.dp(7f);
    menu.setTranslationX(MathUtils.clamp(translationX, dx, Screen.currentWidth() - menuWidth - dx));
    int cornerCenterX = menuWidth / 2 + Math.round(translationX - menu.getTranslationX());
    menu.setCornerCenterX(MathUtils.clamp(cornerCenterX, Screen.dp(18f), menuWidth - Screen.dp(18f)));
  }

  private static float getX (View view, View parent) {
    float x = view.getX();
    View currentView = view;
    do {
      currentView = (View) currentView.getParent();
      if (currentView == null)
        break;
      x += currentView.getX();
    } while (currentView != parent);
    return x;
  }

  private void setMenuVisible (View anchorView, long pagerItemId, boolean visible) {
    if (!visible && menu == null)
      return;
    View parent = null;
    if (menu == null) {
      boolean top = !displayTabsAtBottom();
      menu = new BubbleLayout(context, this, top) {
        @Override
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
          super.onMeasure(widthMeasureSpec, heightMeasureSpec);
          if (menuAnchor != null) {
            layoutMenu(menuAnchor);
          }
        }
      };
      int gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT;
      int marginBottom = top ? 0 : getHeaderHeight();
      menu.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity, 0, 0, 0, marginBottom));

      menu.setPadding(menu.getPaddingLeft(), menu.getPaddingTop() + Screen.dp(14f), menu.getPaddingRight(), menu.getPaddingBottom() + Screen.dp(13f));
      for (int filter : FILTERS) {
        int filterName = getFilterName(filter);
        int filterIcon = getFilterIcon(filter, false);

        TextView sectionView = new AppCompatTextView(context);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;

        sectionView.setId(R.id.btn_filter);
        sectionView.setLayoutParams(params);
        sectionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        sectionView.setGravity(Lang.gravity(Gravity.CENTER_VERTICAL));
        sectionView.setPadding(Screen.dp(Lang.rtl()  ? 24f : 16f), Screen.dp(11.5f), Screen.dp(Lang.rtl() ? 16f : 24f), Screen.dp(11.5f));
        sectionView.setTypeface(Fonts.getRobotoMedium());
        sectionView.setTag(R.id.btn_filter, filter);
        sectionView.setOnClickListener(v -> { });
        sectionView.setCompoundDrawablePadding(Screen.dp(16));
        if (!Lang.rtl()) {
          sectionView.setCompoundDrawablesWithIntrinsicBounds(filterIcon, ResourcesCompat.ID_NULL, ResourcesCompat.ID_NULL, ResourcesCompat.ID_NULL);
        } else {
          sectionView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.ID_NULL, ResourcesCompat.ID_NULL, filterIcon, ResourcesCompat.ID_NULL);
        }
        Views.setMediumText(sectionView, Lang.getString(bindLocaleChanger(filterName, sectionView, false, true)));
        RippleSupport.setTransparentBlackSelector(sectionView);
        menu.addView(sectionView);
      }
      pagerWrap.addView(menu, 1);
      parent = pagerWrap;
    }
    if (visible) {
      int selectedFilter = getSelectedFilter(pagerItemId);

      boolean needArchive = pagerItemId == MAIN_PAGER_ITEM_ID && (menuNeedArchive || (!tdlib.settings().isChatListEnabled(ChatPosition.CHAT_LIST_ARCHIVE) && tdlib.hasArchivedChats()));
      menu.getChildAt(FILTER_ARCHIVE).setVisibility(needArchive ? View.VISIBLE : View.GONE);

      TdApi.ChatList chatList;
      if (pagerItemId == MAIN_PAGER_ITEM_ID) {
        chatList = selectedFilter == FILTER_ARCHIVE || menuNeedArchive ? ChatPosition.CHAT_LIST_ARCHIVE : ChatPosition.CHAT_LIST_MAIN;
      } else {
        int pagerItemPosition = getPagerItemPosition(pagerItemId);
        chatList = hasFolders() && pagerItemPosition < pagerChatLists.size() ? pagerChatLists.get(pagerItemPosition) : null;;
      }

      boolean hasUnreadChats = chatList != null && tdlib.hasUnreadChats(chatList);
      boolean needUnread = selectedFilter == FILTER_UNREAD || hasUnreadChats;
      menu.getChildAt(FILTER_UNREAD).setVisibility(needUnread ? View.VISIBLE : View.GONE);

      for (int index = 0; index < menu.getChildCount(); index++) {
        TextView sectionView = (TextView) menu.getChildAt(index);
        if (sectionView.getVisibility() != View.VISIBLE)
          continue;
        int filter = (int) sectionView.getTag(R.id.btn_filter);
        boolean isSelected;
        if (filter == FILTER_NONE && pagerItemId == MAIN_PAGER_ITEM_ID) {
          isSelected = selectedFilter == FILTER_NONE && !menuNeedArchive;
        } else if (filter == FILTER_ARCHIVE && pagerItemId == MAIN_PAGER_ITEM_ID) {
          isSelected = menuNeedArchive;
        } else {
          isSelected = selectedFilter == filter;
        }
        int textColorId = isSelected ? ColorId.textNeutral : ColorId.textLight;
        sectionView.setTextColor(Theme.getColor(textColorId));
        int iconColorId = isSelected ? ColorId.iconActive : ColorId.iconLight;
        TextViewCompat.setCompoundDrawableTintList(sectionView, ColorStateList.valueOf(Theme.getColor(iconColorId)));
        addThemeTextColorListener(sectionView, textColorId);
        addThemeCompoundDrawableColorListener(sectionView, iconColorId);
      }
      layoutMenu(anchorView);
    } else {
      for (int index = 0; index < menu.getChildCount(); index++) {
        removeThemeListenerByTarget(menu.getChildAt(index));
      }
    }
    menu.setBubbleVisible(visible, parent);
  }

  // Language

  @Override
  public void onSuggestedLanguagePackChanged (String languagePackId, TdApi.LanguagePackInfo languagePackInfo) {
    executeOnUiThreadOptional(() -> {
      if (isFocused()) {
        showSuggestions();
      }
    });
  }

  @Override
  protected void handleLanguageDirectionChange () {
    super.handleLanguageDirectionChange();
    checkHeaderMargins();
  }

  @Override
  public void onActivityResume () {
    super.onActivityResume();
    UI.startNotificationService();
    // The permission dialog itself pauses and resumes the activity. Asking again on that resume
    // creates an endless loop when the permission is denied permanently: the system answers instantly,
    // the activity is paused every second and e.g. voice recording gets cancelled. The chat list stays
    // focused in the tablet layout, so the loop is constant there.
    boolean resumedFromPermissionRequest = notificationsRequestPending;
    notificationsRequestPending = false;
    showAnnoyingAlertsForCompliance(!resumedFromPermissionRequest);
  }

  private void makeStartupChecks () {
    tdlib.context().checkDeviceToken();
  }

  @Override
  public boolean needAsynchronousAnimation () {
    ViewController<?> c = getCachedControllerForPosition(0);
    return c == null || c.needAsynchronousAnimation();
  }

  @Override
  protected boolean useCenteredTitle () {
    return true;
  }

  // Search

  /** Opens the focused search of the chat list (dock search button on phones, search bar on tablets). */
  private void openMainSearch () {
    tdlib.checkDeadlocks(() -> runOnUiThreadOptional(() -> {
      if (isFocused() && !inSearchMode() && navigationController != null && !navigationController.isAnimating() && (headerView == null || !headerView.isAnimating())) {
        openSearchMode();
      }
    }));
  }

  @Override
  protected void applySearchTransformFactor (float factor, boolean isOpening) {
    super.applySearchTransformFactor(factor, isOpening);
    if (navigationBar != null) {
      navigationBar.setSearchOpenFactor(factor);
    }
    updateDockCollapsed(true);
  }

  @Override
  protected void onLeaveSearchMode () {
    super.onLeaveSearchMode();
    updateDockCollapsed(true);
  }

  // Collapsed dock (phone)

  /**
   * Phone: while the current chat list is scrolled down, the dock collapses into the scroll to top button and
   * its sections (except chats, the current one) move into the app bar next to the compose button.
   * Search keeps the full dock, its item shows the close button.
   */
  private void updateDockCollapsed (boolean animated) {
    if (navigationBar == null) {
      return;
    }
    ChatsController c = getCurrentChatsController();
    // M3: "Don't hide the navigation bar on scroll when a screen reader is active".
    boolean collapsed = !isTabletNavigationBar() && !inSearchMode() && !inSelectMode() && c != null && c.isScrolledFromTop() && !UI.isScreenReaderActive();
    navigationBar.setCollapsed(collapsed, animated && isFocused());
  }

  private void updateHeaderDockButtons () {
    boolean changed = updateHeaderDockButtonsImpl();
    if (changed && titleView != null) {
      // The title gives way to the actions: it measures itself against the visible menu width
      // (a measure spec doesn't change when only the menu changes, so it must be re-measured explicitly).
      titleView.requestLayout();
    }
  }

  private boolean updateHeaderDockButtonsImpl () {
    boolean changed = false;
    boolean isTablet = isTabletNavigationBar();
    float factor = navigationBar != null && !isTablet ? navigationBar.getCollapseFactor() : 0f;
    // Tablet with chat folders: neither the dock nor the title has search.
    // Tablet: search is always the search bar in the app bar.
    float searchFactor = isTablet ? 0f : factor;
    for (LinearLayout menu : mainMenus) {
      for (int i = 0; i < menu.getChildCount(); i++) {
        View button = menu.getChildAt(i);
        int id = button.getId();
        if (id == R.id.menu_btn_search) {
          changed |= setHeaderDockButtonFactor(button, searchFactor);
        } else if (id == R.id.menu_btn_calls || id == R.id.menu_btn_contacts || id == R.id.menu_btn_account) {
          changed |= setHeaderDockButtonFactor(button, factor);
        }
      }
    }
    return changed;
  }

  /**
   * The buttons come up from below (from the dock) and fade in.
   * @return true if the visibility changed
   */
  private static boolean setHeaderDockButtonFactor (View button, float factor) {
    int visibility = factor > 0f ? View.VISIBLE : View.GONE;
    boolean changed = button.getVisibility() != visibility;
    if (changed) {
      button.setVisibility(visibility);
    }
    button.setAlpha(factor);
    button.setTranslationY(Screen.dp(12f) * (1f - factor));
    float scale = .7f + .3f * factor;
    button.setScaleX(scale);
    button.setScaleY(scale);
    return changed;
  }

  /**
   * Dock sections moved into the app bar: plain tooltip with the label on tap and hold, below the icon button
   * ("If the element is in an app bar, the plain tooltip appears below the element").
   */
  private static void bindHeaderDockTooltips (LinearLayout menu) {
    M3Tooltip.BoundsProvider iconButtonBounds = (anchor, outRect) -> {
      // 48dp icon button centered in the app bar button.
      int size = Math.min(anchor.getHeight(), Screen.dp(48f));
      int top = (anchor.getHeight() - size) / 2;
      outRect.set(0, top, anchor.getWidth(), top + size);
    };
    for (int i = 0; i < menu.getChildCount(); i++) {
      View button = menu.getChildAt(i);
      int id = button.getId();
      if (id == R.id.menu_btn_search || id == R.id.menu_btn_calls || id == R.id.menu_btn_contacts || id == R.id.menu_btn_account) {
        M3Tooltip.bind(button, M3Tooltip.PLACEMENT_BELOW, iconButtonBounds, View::getContentDescription);
      }
    }
  }

  private long lastAccountSwitchTime;

  /**
   * Dock avatar (and the app bar avatar of the collapsed phone dock), tap and hold: the other logged-in accounts and
   * «Добавить аккаунт» — the same actions as the «Аккаунты» group of Settings. Placed like the other options:
   * bottom sheet on compact windows, M3 menu at the touch point on wider ones.
   */
  private boolean showAccountSwitcher () {
    if (isStackLocked() || context.isPasscodeShowing()) {
      return false;
    }
    final PopupLayout popupLayout = new PopupLayout(context);
    popupLayout.setTag(this);
    popupLayout.init(true);
    popupLayout.setNeedFullScreen(true);

    final List<M3AccountItemView> accountViews = new ArrayList<>();
    View.OnClickListener onClick = v -> {
      if (v.getId() == R.id.btn_addAccount) {
        popupLayout.hideWindow(true);
        tdlib.ui().addAccount(context, true, false);
      } else if (v.getTag() instanceof TdlibAccount) {
        TdlibAccount account = (TdlibAccount) v.getTag();
        popupLayout.hideWindow(true);
        long now = SystemClock.uptimeMillis();
        if (account.context().preferredAccountId() != account.id && (lastAccountSwitchTime == 0 || now - lastAccountSwitchTime >= 720)) {
          lastAccountSwitchTime = now;
          account.context().changePreferredAccountId(account.id, TdlibManager.SWITCH_REASON_USER_CLICK);
        }
      }
    };
    for (TdlibAccount account : TdlibManager.instance().getActiveAccounts()) {
      if (account.id == tdlib.id()) {
        continue;
      }
      M3AccountItemView view = new M3AccountItemView(context, tdlib);
      view.setId(R.id.account);
      view.setTag(account);
      view.setAccount(account, false);
      view.attach();
      addThemeInvalidateListener(view);
      accountViews.add(view);
    }
    popupLayout.setDismissListener(popup -> {
      for (M3AccountItemView view : accountViews) {
        removeThemeListenerByTarget(view);
        view.performDestroy();
      }
    });

    float[] touchPoint;
    if (!isCompactWindow() && (touchPoint = context.getRecentTouchPoint()) != null) {
      MenuMoreWrap menu = new MenuMoreWrap(context);
      menu.init(getThemeListeners(), null);
      // Avatar in the 24dp leading icon slot of the menu items.
      float start = MenuMoreWrap.ITEM_PADDING, textStart = MenuMoreWrap.ITEM_TEXT_START;
      for (M3AccountItemView view : accountViews) {
        view.setMetrics(24f, start, textStart, MenuMoreWrap.ITEM_PADDING);
        menu.addCustomItem(view, view.getDesiredWidth(), onClick);
      }
      if (!accountViews.isEmpty()) {
        menu.addDivider();
      }
      menu.addItem(R.id.btn_addAccount, Lang.getString(R.string.AddAccount), R.drawable.baseline_person_add_24, null, onClick);
      menu.setAnchor((int) touchPoint[0], (int) touchPoint[1], (int) touchPoint[0], (int) touchPoint[1], false);
      popupLayout.showMoreView(menu);
    } else {
      OptionsLayout sheet = new OptionsLayout(context, this, null);
      sheet.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
      // Avatar in the icon slot of the sheet items (17dp start, 18dp gap): names line up with «Добавить аккаунт».
      for (M3AccountItemView view : accountViews) {
        view.setMetrics(24f, 17f, 17f + 24f + 18f, 17f);
        view.setOnClickListener(onClick);
        Views.setClickable(view);
        RippleSupport.setTransparentSelector(view);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(54f)));
        sheet.addView(view);
      }
      if (!accountViews.isEmpty()) {
        sheet.addDivider();
      }
      TextView addView = OptionsLayout.genOptionView(context, R.id.btn_addAccount, Lang.getString(R.string.AddAccount), OptionColor.NORMAL, R.drawable.baseline_person_add_24, OptionColor.NORMAL, onClick, getThemeListeners(), null);
      RippleSupport.setTransparentSelector(addView);
      addView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(54f)));
      sheet.addView(addView);
      popupLayout.showSimplePopupView(sheet, sheet.getEstimatedHeight() + extraBottomInsetWithoutIme);
    }
    onCreatePopupLayout(popupLayout);
    return true;
  }

  private View newAccountHeaderButton (HeaderView header) {
    FrameLayoutFix button = new FrameLayoutFix(context);
    button.setId(R.id.menu_btn_account);
    button.setBackgroundResource(ThemeDeprecated.headerSelector());
    button.setLayoutParams(new ViewGroup.LayoutParams(Screen.dp(49f), ViewGroup.LayoutParams.MATCH_PARENT));
    button.setContentDescription(Lang.getString(R.string.Settings));
    button.setOnClickListener(v -> onNavigationItemClick(MainNavigationBar.ITEM_SETTINGS));
    button.setOnLongClickListener(v -> showAccountSwitcher());
    AvatarView avatarView = new AvatarView(context);
    avatarView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(30f), Screen.dp(30f), Gravity.CENTER));
    avatarView.setUser(tdlib, tdlib.myUserId(), false);
    button.addView(avatarView);
    headerAvatarViews.add(avatarView);
    return button;
  }

  // Contained full-screen search (m3.material.io/components/search, ExpandedFullScreenContainedSearchBar):
  // the whole view (app bar and results) is surfaceContainerLow, the search bar inside it stays surfaceContainerHigh.

  @Override
  protected int getSearchHeaderColor () {
    return M3.surfaceContainerLow();
  }

  // Chat search uses the grey search header (legacy icon / text colors): the search bar takes onSurface
  // (input text, leading icon; the clear button uses it too).

  @Override
  protected int getSearchHeaderIconColorId () {
    return ColorId.headerIcon;
  }

  @Override
  protected int getSearchTextColorId () {
    return ColorId.headerText;
  }

  @Override
  protected int getChatSearchContainerColor () {
    return M3.surfaceContainerLow();
  }

  @Override
  protected SearchEditTextDelegate genSearchHeader (HeaderView headerView) {
    // Wrapper drawn behind the back and clear buttons of the app bar: draws the focused contained search bar
    // (phone and tablet; same rounded container and height as the unfocused tablet bar, 8dp horizontal padding
    // of the expanded full-screen search bar).
    HeaderEditText editText = headerView.genSearchHeader(useLightSearchHeader(), this);
    FrameLayoutFix wrap = new FrameLayoutFix(context) {
      @Override
      protected void onDraw (Canvas c) {
        int margin = Screen.dp(8f);
        int barHeight = Screen.dp(M3SearchBarView.BAR_HEIGHT);
        int top = (getMeasuredHeight() - barHeight) / 2;
        RectF rect = Paints.getRectF();
        rect.set(margin, top, getMeasuredWidth() - margin, top + barHeight);
        c.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, Paints.fillingPaint(M3.surfaceContainerHigh()));
      }
    };
    wrap.setWillNotDraw(false);
    addThemeInvalidateListener(wrap);
    wrap.addView(editText);
    return new SearchEditTextDelegate() {
      @NonNull
      @Override
      public View view () {
        return wrap;
      }

      @NonNull
      @Override
      public HeaderEditText editView () {
        return editText;
      }
    };
  }

  @Override
  protected SearchEditTextDelegate getSearchHeaderView (HeaderView headerView) {
    SearchEditTextDelegate delegate = super.getSearchHeaderView(headerView);
    // ViewController applies the 68dp / 49dp text margins to view(): move them to the text field,
    // so the wrapper spans the whole app bar.
    View wrap = delegate.view();
    FrameLayoutFix.LayoutParams wrapParams = (FrameLayoutFix.LayoutParams) wrap.getLayoutParams();
    if (wrapParams != null && (wrapParams.leftMargin != 0 || wrapParams.rightMargin != 0)) {
      FrameLayoutFix.LayoutParams editParams = (FrameLayoutFix.LayoutParams) delegate.editView().getLayoutParams();
      editParams.leftMargin = wrapParams.leftMargin;
      editParams.rightMargin = wrapParams.rightMargin;
      delegate.editView().setLayoutParams(editParams);
      wrapParams.leftMargin = wrapParams.rightMargin = 0;
      wrap.setLayoutParams(wrapParams);
    }
    return delegate;
  }

  @Override
  protected View getSearchAntagonistView () {
    return pagerWrap;
  }

  @Override
  protected int getSearchMenuId () {
    return R.id.menu_clear;
  }

  @Override
  protected int getChatSearchFlags () {
    return SearchManager.FLAG_NEED_TOP_CHATS | SearchManager.FLAG_NEED_MESSAGES | SearchManager.FLAG_NEED_GLOBAL_SEARCH;
  }

  @Override
  protected TdApi.ChatList getChatMessagesSearchChatList () {
    ChatsController c = findChatsControllerForSearchMessages();
    if (c != null && TD.isChatListArchive(c.chatList())) {
      return ChatPosition.CHAT_LIST_ARCHIVE;
    }
    return null;
  }

  @Nullable
  public final ChatsController findMainChatsController () {
    return (ChatsController) getCachedControllerForItemId(MAIN_PAGER_ITEM_ID);
  }

  @Nullable
  public final ChatsController getCurrentChatsController () {
    ViewController<?> current = getCurrentPagerItem();
    return current instanceof ChatsController ? (ChatsController) current : null;
  }

  @Nullable
  public final ChatsController findChatsControllerForSearchMessages () {
    return hasFolders() ? getCurrentChatsController() : findMainChatsController();
  }

  @Override
  protected boolean filterChatMessageSearchResult (TdApi.Chat chat) {
    ChatsController c = findChatsControllerForSearchMessages();
    if (c != null) {
      TdApi.ChatList chatList = c.chatList();
      if (TD.isChatListFolder(chatList) && Config.SEARCH_MESSAGES_ONLY_IN_SELECTED_FOLDER) {
        if (ChatPosition.findPosition(chat, chatList) == null)
          return false;
      }
      ChatFilter filter = c.getFilter();
      if (filter != null && filter.canFilterMessages()) {
        return filter.accept(chat);
      }
    }
    return super.filterChatMessageSearchResult(chat);
  }

  @Override
  protected int getChatMessagesSearchTitle () {
    ChatsController c = findChatsControllerForSearchMessages();
    ChatFilter filter = c != null ? c.getFilter() : null;
    boolean isArchive = c != null && TD.isChatListArchive(c.chatList());
    if (filter != null && filter.canFilterMessages())
      return filter.getMessagesStringRes(isArchive);
    if (isArchive)
      return R.string.MessagesArchive;
    return super.getChatMessagesSearchTitle();
  }

  // Main

  @Override
  protected boolean overridePagerParent () {
    return true;
  }

  @Override
  public View getViewForApplyingOffsets () {
    return mainWrap;
  }

  // Compose menu (app bar button)

  private void showComposeMenu () {
    showMore(new int[] {
      R.id.btn_float_newChat,
      R.id.btn_float_newGroup,
      R.id.btn_float_newChannel,
      R.id.btn_float_newSecretChat
    }, new String[] {
      Lang.getString(R.string.NewChat),
      Lang.getString(R.string.NewGroup),
      Lang.getString(R.string.NewChannel),
      Lang.getString(R.string.NewSecretChat)
    }, new int[] {
      R.drawable.baseline_person_24,
      R.drawable.baseline_group_24,
      R.drawable.baseline_bullhorn_24,
      R.drawable.baseline_lock_24
    }, 0);
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (!onComposeItemClick(id)) {
      super.onMoreItemPressed(id);
    }
  }

  private boolean onComposeItemClick (int id) {
    if (id == R.id.btn_float_newChannel) {
      navigateTo(new CreateChannelController(context, tdlib));
    } else if (id == R.id.btn_float_newGroup) {
      ContactsController c = new ContactsController(context, tdlib);
      c.initWithMode(ContactsController.MODE_NEW_GROUP);
      navigateTo(c);
    } else if (id == R.id.btn_float_newSecretChat) {
      ContactsController c = new ContactsController(context, tdlib);
      c.initWithMode(ContactsController.MODE_NEW_SECRET_CHAT);
      navigateTo(c);
    } else if (id == R.id.btn_float_newChat) {
      ContactsController c = new ContactsController(context, tdlib);
      c.initWithMode(ContactsController.MODE_NEW_CHAT);
      navigateTo(c);
    } else {
      return false;
    }
    return true;
  }

  @Override
  public void onPageScrolled (int position, int actualPosition, float actualPositionOffset, int actualPositionOffsetPixels) {
    super.onPageScrolled(position, actualPosition, actualPositionOffset, actualPositionOffsetPixels);
    if (folderRail != null) {
      folderRail.setSelectionFactor(actualPosition + actualPositionOffset);
    }
    if (Config.CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL && displayTabsAtBottom()) {
      showBottomBar();
    }
  }

  @Override
  public void onPageSelected (int position, int actualPosition) {
    // Use the selected page itself: the "current" cached item may still point to the previous page here.
    ViewController<?> selectedPage = getCachedControllerForPosition(position);
    updateDockCollapsed(true);
    if (hasFolders()) {
      return;
    }
    context.notifyBackPressAvailabilityChanged();
  }

  @Override
  public void onPrepareToShow () {
    super.onPrepareToShow();
    if (needUpdatePagerSections) {
      needUpdatePagerSections = false;
      updatePagerSections(true);
      if (headerCell != null) {
        ViewUtils.runJustBeforeBeingDrawn(headerCell.getView(), () ->
          headerCell.getTopView().updateAnchorPosition(false)
        );
      }
    }
    if (headerView != null) {
      headerView.updateLockButton(getMenuId());
    }
    checkTabs();
    checkMenu();
    checkMargins();
  }

  private void checkMargins () {
    checkPagerMargins();
    checkHeaderMargins();
    updateChatListNavigationInset();
    checkNavigationBarLayout();
  }

  /**
   * Extra bottom scroll space of chat lists, so the last chat can be scrolled above
   * the phone dock (M3: floating elements must not fully cover actionable content).
   */
  private int getChatListNavigationInset () {
    if (navigationBar == null) {
      return 0;
    }
    if (navigationBar.isBar()) {
      // Tablet: the list scrolls under the floating bar, the last chat can be scrolled above it.
      return MainNavigationBar.getBarTotalHeight() + Screen.dp(8f);
    }
    // Phone: floating dock at the bottom-end corner, above the folders bar if there is one.
    int foldersBar = displayTabsAtBottom() ? getHeaderHeight() : 0;
    return foldersBar + MainNavigationBar.getRailMargin() + navigationBar.getRailTotalHeight() + Screen.dp(8f);
  }

  /**
   * M3: the snackbar never covers navigation. Tablet bar: floats above the bar (and the system bar).
   * Phone dock (owner's decision): at the bottom next to the dock, aligned with its bottom, narrowed to the
   * width the dock leaves free (16dp from the screen edge, 8dp from the dock), above the folders bar.
   */
  private void updateSnackBarOffset () {
    if (updateSnackBar == null) {
      return;
    }
    int margin = Screen.dp(SnackBar.MARGIN);
    if (navigationBar != null && !navigationBar.isBar()) {
      int foldersBar = displayTabsAtBottom() ? getHeaderHeight() : 0;
      int dockSide = MainNavigationBar.getRailMargin() + MainNavigationBar.getRailWidth() + Screen.dp(8f);
      updateSnackBar.setHorizontalMargins(margin, dockSide);
      updateSnackBar.setBottomOffset(extraBottomInsetWithoutIme + foldersBar + MainNavigationBar.getRailMargin());
    } else {
      updateSnackBar.setHorizontalMargins(margin, margin);
      updateSnackBar.setBottomOffset(extraBottomInsetWithoutIme + Math.max(margin, getChatListNavigationInset()));
    }
  }

  private void updateChatListNavigationInset () {
    updateSnackBarOffset();
    int inset = getChatListNavigationInset();
    int count = getPagerItemCount();
    for (int position = 0; position < count; position++) {
      ViewController<?> c = getCachedControllerForPosition(position);
      if (c instanceof ChatsController) {
        ((ChatsController) c).setNavigationInset(inset);
      }
    }
  }

  public void onChatListScrolled (ChatsController c) {
    if (c == getCurrentChatsController()) {
      updateDockCollapsed(true);
    }
  }

  // Navigation bar

  private boolean isTabletNavigationBar () {
    return navigationController != null && navigationController.isTabletSplit() && navigationController.isTabletMaster(this);
  }

  /** Called when the chat list is moved into or out of the tablet split layout. */
  public void checkNavigationBarLayout () {
    if (navigationBar == null) {
      return;
    }
    boolean isBar = isTabletNavigationBar();
    boolean modeChanged = navigationBar.isBar() != isBar;
    // Folders move between the bottom bar (phone) and the rail / strip below the app bar (tablet).
    checkFoldersPlacement();
    if (folderRail != null) {
      folderRail.setInsets(HeaderView.getTopOffset(), extraBottomInsetWithoutIme);
    }
    navigationBar.setLayoutMode(isBar, extraBottomInsetWithoutIme, displayTabsAtBottom() ? getHeaderHeight() : 0);
    if (navigationScrim != null) {
      // Covers the bar, its margins and the fade above it.
      int scrimHeight = M3BottomScrimView.getFadeHeight() + MainNavigationBar.getBarTotalHeight() + extraBottomInsetWithoutIme;
      navigationScrim.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, scrimHeight, Gravity.BOTTOM));
      navigationScrim.setVisibility(isBar ? View.VISIBLE : View.GONE);
    }
    updateDockCollapsed(false);
    updateHeaderDockButtons();
    if (modeChanged && headerView != null && isFocused() && !inTransformMode() && navigationController != null && !navigationController.isAnimating()) {
      // The app bar title switches between the app name (phone) and the search bar (tablet).
      headerView.setTitle(this);
    }
    updateChatListNavigationInset();
    updateNavigationBarSelection();
  }

  /**
   * Tablet split: shows the content of a dock section (Calls, Contacts, Settings) in the left pane instead of the
   * chat list. It stays under the navigation bar and its scrim; the chat list keeps its state while hidden.
   */
  public void setTabletSectionView (@Nullable View view) {
    if (tabletSectionView == view || rootContentView == null) {
      return;
    }
    if (tabletSectionView != null && tabletSectionView.getParent() == rootContentView) {
      rootContentView.removeView(tabletSectionView);
    }
    tabletSectionView = view;
    if (view != null) {
      rootContentView.addView(view, rootContentView.indexOfChild(mainWrap) + 1);
    }
    mainWrap.setVisibility(view != null ? View.INVISIBLE : View.VISIBLE);
  }

  /** Tablet: the chat list content, under the app bar and the floating bar. */
  public @Nullable View getTabletChatList () {
    return mainWrap;
  }

  /** Tablet: the floating navigation bar at the bottom of the left pane. */
  public @Nullable View getTabletNavigationBar () {
    return navigationBar != null && navigationBar.isBar() ? navigationBar : null;
  }

  /** Bottom space a section list needs to scroll its last item above the floating bar of the left pane. */
  public int getTabletSectionNavigationInset () {
    return navigationBar != null && navigationBar.isBar() ? getChatListNavigationInset() : 0;
  }

  /** Highlights the section opened in the right pane of the tablet layout. */
  public void updateNavigationBarSelection () {
    if (navigationBar != null) {
      navigationBar.setSelectedItem(getTabletSection());
    }
  }

  private int getTabletSection () {
    if (!isTabletNavigationBar() || navigationController.getStackSize() < 2) {
      return MainNavigationBar.ITEM_CHATS;
    }
    ViewController<?> section = navigationController.getStack().get(1);
    if (section instanceof SettingsController) {
      return MainNavigationBar.ITEM_SETTINGS;
    }
    if (section instanceof PeopleController) {
      return MainNavigationBar.ITEM_CONTACTS;
    }
    if (section instanceof CallListController) {
      return MainNavigationBar.ITEM_CALLS;
    }
    return MainNavigationBar.ITEM_CHATS;
  }

  private void updateNavigationCounter (TdApi.ChatList chatList) {
    if (chatList.getConstructor() != TdApi.ChatListMain.CONSTRUCTOR && chatList.getConstructor() != TdApi.ChatListArchive.CONSTRUCTOR) {
      return;
    }
    executeOnUiThreadOptional(() -> {
      if (navigationBar != null) {
        navigationBar.setUnreadCount(updateCounter(ChatPosition.CHAT_LIST_MAIN, navigationBar.getUnreadCounter(), tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN), true));
      }
    });
  }

  private void onNavigationItemClick (int item) {
    if (navigationController == null || navigationController.isAnimating() || context.isNavigationBusy()) {
      return;
    }
    if (item == MainNavigationBar.ITEM_SCROLL_TOP) {
      ChatsController c = getCurrentChatsController();
      if (c != null) {
        c.onScrollToTopRequested();
      }
      return;
    }
    if (item == MainNavigationBar.ITEM_SEARCH) {
      // Phone dock: the search button opens the focused search and closes it while it is open.
      if (inSearchMode()) {
        if (headerView == null || !headerView.isAnimating()) {
          closeSearchMode(null);
        }
      } else {
        openMainSearch();
      }
      return;
    }
    boolean isTablet = isTabletNavigationBar();
    if (isTablet && item == getTabletSection() && item != MainNavigationBar.ITEM_CHATS) {
      // The section is already in the left pane.
      return;
    }
    switch (item) {
      case MainNavigationBar.ITEM_CHATS: {
        if (isTablet && getTabletSection() != MainNavigationBar.ITEM_CHATS) {
          // Close settings/contacts/profile, keep the chat list.
          navigationController.closeTabletDetail();
        } else {
          onPagerItemClick(0);
        }
        break;
      }
      case MainNavigationBar.ITEM_CALLS: {
        openNavigationSection(new CallListController(context, tdlib));
        break;
      }
      case MainNavigationBar.ITEM_CONTACTS: {
        tdlib.contacts().startSyncIfNeeded(context, true, () -> {
          PeopleController c = new PeopleController(context, tdlib);
          c.setNeedSearch();
          openNavigationSection(c);
        });
        break;
      }
      case MainNavigationBar.ITEM_SETTINGS: {
        openNavigationSection(new SettingsController(context, tdlib));
        break;
      }
    }
  }

  private void openNavigationSection (ViewController<?> c) {
    if (isDestroyed() || navigationController == null) {
      return;
    }
    // M3 top-level destination: fade-through instead of forward/backward.
    c.setTopLevelDestination(true);
    if (isTabletNavigationBar()) {
      // Replace whatever is opened in the right pane.
      navigationController.setControllerAnimated(c, true, true);
    } else {
      navigateTo(c);
    }
  }

  private void checkPagerMargins () {
    if (pagerWrap != null) {
      Views.setTopMargin(pagerWrap, displayTabsBelowSearch() ? getTopTabsBarHeight() : 0);
    }
    if (!Config.CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL && displayTabsAtBottom()) {
      Views.setBottomMargin(getViewPager(), getHeaderHeight());
      Views.setBottomMargin(pagerWrap, 0);
    } else {
      Views.setBottomMargin(getViewPager(), 0);
      Views.setBottomMargin(pagerWrap, 0);
    }
  }

  private void checkHeaderMargins () {
    if (headerCell == null)
      return;
    ViewPagerHeaderViewCompact headerCellView = ((ViewPagerHeaderViewCompact) headerCell.getView());
    RecyclerView recyclerView = headerCellView.getRecyclerView();
    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) recyclerView.getLayoutParams();
    if (params.rightMargin != 0 || params.leftMargin != 0) {
      params.leftMargin = 0;
      params.rightMargin = 0;
      recyclerView.setLayoutParams(params);
    }
    int paddingLeft, paddingRight;
    // The tabs are never inside the app bar, so there are no app bar buttons to leave room for.
    paddingLeft = paddingRight = 0;
    if (displayTabsAtBottom()) {
      recyclerView.setPadding(0, 0, 0, 0);
    } else {
      recyclerView.setClipToPadding(false);
      recyclerView.setPadding(Screen.dp(12), 0, Screen.dp(12), 0);
    }
    if (headerCellView.getPaddingLeft() != paddingLeft || headerCellView.getPaddingRight() != paddingRight) {
      headerCellView.setPadding(paddingLeft, headerCellView.getPaddingTop(), paddingRight, headerCellView.getPaddingBottom());
    }
  }

  private SnackBar updateSnackBar;

  private void onAppUpdateAvailable (boolean isApk, boolean immediate) {
    if (updateSnackBar == null) {
      updateSnackBar = new SnackBar(context);
      updateSnackBar.setCallback(new SnackBar.Callback() {
        @Override
        public void onSnackBarTransition (SnackBar v, float factor) {
          // Floating M3 snackbar: navigation and lists stay in place.
        }

        @Override
        public void onDestroySnackBar (SnackBar v) {
          if (updateSnackBar == v) {
            if (updateSnackBar.getParent() instanceof ViewGroup) {
              ((ViewGroup) updateSnackBar.getParent()).removeView(updateSnackBar);
            }
            updateSnackBar.removeThemeListeners(MainController.this);
            updateSnackBar = null;
          }
        }
      });
      updateSnackBar.addThemeListeners(this);
      updateSnackBar.setText(Lang.getString(R.string.AppUpdateReady));
      // Above the navigation scrim and bar (z-order), positioned above them by updateSnackBarOffset().
      ViewGroup snackBarParent = navigationBar != null && navigationBar.getParent() instanceof ViewGroup ? (ViewGroup) navigationBar.getParent() : mainWrap;
      snackBarParent.addView(updateSnackBar);
      updateSnackBarOffset();
    }
    updateSnackBar.setAction(Lang.getString(isApk ? R.string.AppUpdateInstall : R.string.AppUpdateRestart), context().appUpdater()::installUpdate, !isApk);
    updateSnackBar.showSnackBar(!immediate && isFocused());
  }

  @Override
  public void onAppUpdateStateChanged (int state, int oldState, boolean isApk) {
    if (state == AppUpdater.State.READY_TO_INSTALL) {
      onAppUpdateAvailable(isApk, false);
    } else if (updateSnackBar != null) {
      updateSnackBar.dismissSnackBar(isFocused());
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().removeOptionListener(this);
    context().appUpdater().removeListener(this);
    tdlib.context().global().removeCountersListener(this);
    Settings.instance().removeChatFolderSettingsListener(this);
    tdlib.listeners().unsubscribeFromChatFoldersUpdates(this);
    if (chatListUnreadCountListener != null) {
      tdlib.listeners().removeTotalChatCounterListener(chatListUnreadCountListener);
      chatListUnreadCountListener = null;
    }
    if (navigationCounterListener != null) {
      tdlib.listeners().removeTotalChatCounterListener(navigationCounterListener);
      navigationCounterListener = null;
    }
    if (navigationBar != null) {
      navigationBar.performDestroy();
    }
    if (titleView != null) {
      titleView.performDestroy();
      titleView = null;
    }
    for (AvatarView avatarView : headerAvatarViews) {
      avatarView.performDestroy();
    }
    headerAvatarViews.clear();
    if (defaultCounterListener != null) {
      tdlib.listeners().removeTotalChatCounterListener(defaultCounterListener);
      defaultCounterListener = null;
      defaultMainItem = null;
    }
    if (chatListPositionListener != null) {
      tdlib.settings().removeChatListPositionListener(chatListPositionListener);
      chatListPositionListener = null;
    }
  }

  private void showSuggestions () {
    if (!UI.inUiThread())
      throw new IllegalStateException();
    if (showLanguageSuggestion()) {
      return;
    }
    if (showEmojiUpdateSuggestion()) {
      return;
    }
    if (showFoldersSetupSuggestion()) {
      return;
    }
  }

  private TdApi.LanguagePackInfo loadingLanguagePack;

  private boolean showLanguageSuggestion () {
    String languagePackId = tdlib.suggestedLanguagePackId();
    TdApi.LanguagePackInfo languagePack = Settings.instance().suggestedLanguagePackInfo(languagePackId, tdlib);
    if (languagePack == null) {
      loadingLanguagePack = null;
      return false;
    }
    if (loadingLanguagePack != null && loadingLanguagePack.id.equals(languagePack.id))
      return true;
    loadingLanguagePack = languagePack;
    String[] keys = Lang.getKeys(new int[] {
      R.string.language_continueInLanguage,
      R.string.language_continueInLanguagePopupText,
      R.string.language_appliedLanguage
    });
    tdlib.getStrings(languagePack, keys, cloudStrings -> {
      boolean isBadLanguage = cloudStrings == null || cloudStrings.size() < keys.length;
      if (isBadLanguage) {
        Log.w("Suggested language is bad, ignoring. languagePackId:%s", languagePack.id);
        if (loadingLanguagePack != null && languagePack.id.equals(loadingLanguagePack.id))
          loadingLanguagePack = null;
        return;
      }
      tdlib.ui().post(() -> {
        if (!isDestroyed() && loadingLanguagePack != null && languagePack.id.equals(loadingLanguagePack.id)) {
          IntList ids = new IntList(3);
          StringList strings = new StringList(3);
          IntList colors = new IntList(3);
          IntList icons = new IntList(3);

          ids.append(R.id.btn_done);
          strings.append(TD.findOrdinary(cloudStrings, Lang.getResourceEntryName(R.string.language_continueInLanguage), () -> Lang.getString(R.string.language_continueInLanguage)));
          colors.append(OptionColor.BLUE);
          icons.append(R.drawable.baseline_check_24);

          ids.append(R.id.btn_cancel);
          icons.append(R.drawable.baseline_cancel_24);
          colors.append(OptionColor.NORMAL);
          strings.append(R.string.Cancel);

          ids.append(R.id.btn_languageSettings);
          strings.append(R.string.MoreLanguages);
          colors.append(OptionColor.NORMAL);
          icons.append(R.drawable.baseline_language_24);

          CharSequence text = Strings.buildMarkdown(this, TD.findOrdinary(cloudStrings, Lang.getResourceEntryName(R.string.language_continueInLanguagePopupText), () -> Lang.getString(R.string.language_continueInLanguagePopupText)), null);

          PopupLayout popupLayout = showOptions(text, ids.get(), strings.get(), colors.get(), icons.get(), (itemView, id) -> {
            if (id == R.id.btn_done) {
              tdlib.applyLanguage(languagePack, applySuccess -> {
                if (applySuccess) {
                  UI.showToast(R.string.language_appliedLanguage, Toast.LENGTH_SHORT);
                  Settings.instance().setRecommendedLanguagePackId(languagePackId);
                }
              }, true);
            } else if (id == R.id.btn_cancel) {
              Settings.instance().setRecommendedLanguagePackId(languagePackId);
            } else if (id == R.id.btn_languageSettings) {
              Settings.instance().setRecommendedLanguagePackId(languagePackId);
              navigateTo(new SettingsLanguageController(context, tdlib));
            }
            return true;
          });
          if (popupLayout != null) {
            popupLayout.setDisableCancelOnTouchDown(true);
          }
          loadingLanguagePack = null;
        }
      });
    });
    return true;
  }

  private boolean showEmojiUpdateSuggestion () {
    Settings.EmojiPack emojiPack = Settings.instance().getOutdatedEmojiPack();
    if (emojiPack == null)
      return false;
    tdlib.ui().postDelayed(() -> {
      if (!isFocused() || isDestroyed() || context.isPasscodeShowing())
        return;
      showOptions(Lang.getStringBold(R.string.EmojiSetUpdated, emojiPack.displayName), new int[] {R.id.btn_downloadFile, R.id.btn_cancel}, new String[] {Lang.getString(R.string.EmojiSetUpdate), Lang.getString(R.string.Cancel)}, new int[] {OptionColor.BLUE, OptionColor.NORMAL}, new int[] {R.drawable.baseline_sync_24, R.drawable.baseline_cancel_24}, (v, id) -> {
        if (id == R.id.btn_downloadFile) {
          SettingsCloudEmojiController c = new SettingsCloudEmojiController(context, tdlib);
          c.setArguments(new SettingsCloudController.Args<>(emojiPack));
          navigateTo(c);
        } else if (id == R.id.btn_cancel) {
          Settings.instance().revokeOutdatedEmojiPack();
        }
        return true;
      });
    }, 100);
    return true;
  }

  private boolean foldersAlertShown;

  private boolean showFoldersSetupSuggestion () {
    if (isFocused() && hasFolders() && Settings.instance().hasPendingFeatureAddedNotification(FeatureAvailability.Feature.CHAT_FOLDERS) && !foldersAlertShown) {
      foldersAlertShown = true;
      ChatFoldersFeatureController c = new ChatFoldersFeatureController(context(), tdlib());
      c.show();
      c.setDismissListener(popup -> {
        if (!isNavigationAnimating()) {
          Settings.instance().revokeFeatureNotifications(FeatureAvailability.Feature.CHAT_FOLDERS);
          showFolderTooltip(ChatPosition.CHAT_LIST_MAIN, Lang.getString(R.string.HoldToEditChatFoldersHint));
        }
      });
      return true;
    }
    return false;
  }

  private CancellationSignal suggestionsSignal;

  @Override
  protected void onFocusStateChanged () {
    super.onFocusStateChanged();
    if (suggestionsSignal != null) {
      suggestionsSignal.cancel();
      suggestionsSignal = null;
    }
    if (isFocused()) {
      CancellationSignal signal = new CancellationSignal();
      suggestionsSignal = signal;
      tdlib.awaitConnection(() -> {
        executeOnUiThreadOptional(() -> {
          if (isFocused() && !signal.isCanceled()) {
            signal.cancel();
            showSuggestions();
          }
        });
      });
    }
  }

  private boolean oneShot;

  @Override
  public void onFocus () {
    super.onFocus();
    // FIXME check tdlib.isUnauthorized()
    tdlib.context().changePreferredAccountId(tdlib.id(), TdlibManager.SWITCH_REASON_NAVIGATION);
    if (UI.TEST_MODE == UI.TEST_MODE_USER) {
      UI.TEST_MODE = UI.TEST_MODE_NONE;
    }
    if (!oneShot) {
      oneShot = true;
      ((MainActivity) context()).getMessagesController(tdlib, true);
    }
    showAnnoyingAlertsForCompliance(false);
  }

  private boolean syncContactsInitiated;

  private void syncContacts (Runnable after) {
    if (!syncContactsInitiated) {
      tdlib.contacts().startSyncIfNeeded(context(), false, () -> {
        syncContactsInitiated = true;
        U.run(after);
      });
    } else {
      U.run(after);
    }
  }

  private void addStartupMarker () {
    if (Config.ENABLE_BASELINE_PROFILE_HOOKS) {
      tdlib.awaitConnection(() -> runOnUiThreadOptional(() -> {
        ViewGroup group = (ViewGroup) getWrapUnchecked();
        if (group.findViewById(R.id.startup_marker) == null) {
          group.addView(newStartupMarker());
        }
      }));
    }
  }

  private boolean notificationsRequested;
  private boolean notificationsRequestPending;

  private void showAnnoyingAlertsForCompliance (boolean fromAppResume) {
    if (Config.ENABLE_BASELINE_PROFILE_HOOKS) {
      addStartupMarker();
    }
    if (checkSyncAlert()) {
      return;
    }
    tdlib.checkDeadlocks(() -> runOnUiThreadOptional(() -> {
      if (isFocused() && context.getActivityState() == UI.State.RESUMED) {
        boolean needNotifications = fromAppResume || !notificationsRequested;
        if (needNotifications && !notificationsRequested) {
          notificationsRequested = true;
        }
        if (needNotifications) {
          if (context().permissions().requestPostNotifications(granted -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted) {
              tdlib.notifications().onNotificationPermissionGranted();
            }
            syncContacts(null);
          })) {
            notificationsRequestPending = true;
          } else {
            syncContacts(null);
          }
        }
      }
    }, null, 1000L));
  }

  // Side drawer is disabled (product decision): no menu button, no edge-swipe / programmatic opening.
  // DrawerController stays in the code base unused. Search / select modes still get Back / Close.

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_NONE;
  }

  @Override
  protected boolean useDrawer () {
    return false;
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    if (!super.canSlideBackFrom(navigationController, x, y)) {
      return false;
    }
    if (headerCell != null) {
      int top, bottom;
      if (displayTabsAtBottom()) {
        top = Views.getLocationInWindow(headerCell.getView())[1];
        bottom = top + headerCell.getView().getHeight();
      } else {
        top = HeaderView.getTopOffset();
        bottom = HeaderView.getSize(true);
      }
      if (y < bottom && y >= top && x < headerCell.getView().getMeasuredWidth()) {
        return !((ViewPagerHeaderViewCompact) headerCell.getView()).canScrollLeft();
      }
    }
    return true;
  }

  @Override
  protected boolean useHeaderShadowLine () {
    // M3 app bar: no shadow line, it is separated from content by color on scroll (see getHeaderColor).
    return false;
  }

  @Override
  protected int getMenuButtonsWidth () {
    return 0; // disable header margins
  }

  /** Folders displayed at the bottom can hide on scroll ({@link Config#CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL}). */
  private void showHideBottomBarOnScroll (ViewController<?> controller, boolean show) {
    if (!Config.CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL || getCurrentPagerItem() != controller) {
      return;
    }
    if (show || UI.isScreenReaderActive()) {
      // M3: navigation stays visible for screen readers.
      showBottomBar();
    } else {
      if (getCurrentPagerItemId() == MAIN_PAGER_ITEM_ID) {
        ChatsController c = findMainChatsController();
        if (c != null && c.isPullingArchive()) {
          return;
        }
      }
      hideBottomBar();
    }
  }

  // Controllers

  private static final int POSITION_CHATS = 0;

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({FILTER_NONE, FILTER_ARCHIVE, FILTER_PRIVATE, FILTER_GROUPS, FILTER_CHANNELS, FILTER_BOTS, FILTER_UNREAD})
  private @interface Filter { }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({FILTER_NONE, FILTER_PRIVATE, FILTER_GROUPS, FILTER_CHANNELS, FILTER_BOTS, FILTER_UNREAD, NO_PENDING_FILTER})
  private @interface PendingFilter { }

  private static final int FILTER_NONE = 0;
  private static final int FILTER_ARCHIVE = 1;
  private static final int FILTER_PRIVATE = 2;
  private static final int FILTER_GROUPS = 3;
  private static final int FILTER_CHANNELS = 4;
  private static final int FILTER_BOTS = 5;
  private static final int FILTER_UNREAD = 6;

  private static final int[] FILTERS = {
    FILTER_NONE,
    FILTER_ARCHIVE,
    FILTER_PRIVATE,
    FILTER_GROUPS,
    FILTER_CHANNELS,
    FILTER_BOTS,
    FILTER_UNREAD,
  };

  private static final int[] GLOBAL_FILTERS = {
    FILTER_NONE,
    FILTER_PRIVATE,
    FILTER_GROUPS,
    FILTER_CHANNELS,
    FILTER_BOTS,
    FILTER_UNREAD,
  };

  private boolean useGlobalFilter () {
    // The app bar keeps the title / search bar, so the global filter toggle has no place: folders use their own filter menus.
    return false;
  }

  private @Filter int getSelectedFilter (long pagerItemId) {
    if (useGlobalFilter() && globalFilter != FILTER_NONE) {
      return globalFilter;
    }
    return pagerChatListFilters.get(pagerItemId, FILTER_NONE);
  }

  private void setSelectedFilter (long pagerItemId, @Filter int filter) {
    if (filter != FILTER_NONE) {
      pagerChatListFilters.put(pagerItemId, filter);
    } else {
      pagerChatListFilters.delete(pagerItemId);
    }
  }

  private boolean hasSelectedFilters () {
    for (int i = 0; i < pagerChatListFilters.size(); i++) {
      if (pagerChatListFilters.valueAt(i) != FILTER_NONE) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected int getPagerItemCount () {
    // Without folders the only page is the main chat list: calls are a section of the navigation dock.
    return hasFolders() ? pagerChatLists.size() : 1;
  }

  protected long getPagerItemId (int position) {
    if (hasFolders()) {
      return getPagerItemId(pagerChatLists.get(position));
    }
    if (position == POSITION_CHATS) {
      return MAIN_PAGER_ITEM_ID;
    }
    return position;
  }

  private long getPagerItemId (TdApi.ChatList chatList) {
    switch (chatList.getConstructor()) {
      case TdApi.ChatListMain.CONSTRUCTOR:
        return MAIN_PAGER_ITEM_ID;
      case TdApi.ChatListArchive.CONSTRUCTOR:
        return ARCHIVE_PAGER_ITEM_ID;
      case TdApi.ChatListFolder.CONSTRUCTOR:
        return ((TdApi.ChatListFolder) chatList).chatFolderId;
      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public boolean onPagerItemLongClick (int index) {
    if (hasFolders()) {
      TdApi.ChatList chatList = pagerChatLists.get(index);
      showChatListOptions(chatList);
      return true;
    }
    return false;
  }

  private ChatsController newChatsController (TdApi.ChatList chatList, @Filter int filter) {
    ChatsController chats = new ChatsController(this.context, tdlib).setParent(this);
    chats.setNavigationInset(getChatListNavigationInset());
    ChatFilter chatFilter;
    switch (filter) {
      case FILTER_NONE:
        chatFilter = null;
        break;
      case FILTER_UNREAD:
        chatFilter = ChatFilter.unreadFilter(tdlib);
        break;
      case FILTER_PRIVATE:
        chatFilter = ChatFilter.privateFilter(tdlib);
        break;
      case FILTER_GROUPS:
        chatFilter = ChatFilter.groupsFilter(tdlib);
        break;
      case FILTER_CHANNELS:
        chatFilter = ChatFilter.channelsFilter(tdlib);
        break;
      case FILTER_BOTS:
        chatFilter = ChatFilter.botsFilter(tdlib);
        break;
      case FILTER_ARCHIVE:
      default:
        throw new IllegalArgumentException();
    }
    if (chatFilter != null) {
      chats.setArguments(new ChatsController.Arguments(chatFilter).setTag(filter).setChatList(chatList).setIsBase(true));
    } else if (chatList != null) {
      chats.setArguments(new ChatsController.Arguments(chatList).setTag(filter).setIsBase(true));
    }
    return chats;
  }

  private CharSequence getMenuSectionName (long pagerItemId, int pagerItemPosition, boolean hasFolders, @ChatFolderStyle int chatFolderStyle, boolean upperCase) {
    int selectedFilter = getSelectedFilter(pagerItemId);
    boolean isMain = pagerItemId == MAIN_PAGER_ITEM_ID;
    boolean isArchive = pagerItemId == ARCHIVE_PAGER_ITEM_ID;
    if (!isMain && !isArchive) {
      throw new UnsupportedOperationException();
    }
    boolean showAsArchive = (isMain && menuNeedArchive) || isArchive;
    if (hasFolders) {
      String source;
      boolean iconOnly = chatFolderStyle == ChatFolderStyle.ICON_ONLY;
      if (iconOnly) {
        source = "";
      } else if (showAsArchive) {
        source = Lang.getString(getArchiveSectionNameRes(FILTER_NONE, chatFolderStyle));
      } else if (isMain && pagerItemPosition == 0) {
        // First tab (all chats): icon with a short label.
        source = Lang.getString(R.string.CategoryMainShort);
      } else {
        source = Lang.getString(getMainSectionNameRes(FILTER_NONE, /* hasFolders */ true));
      }
      if (upperCase) {
        source = Lang.uppercase(source);
      }
      if (useGlobalFilter() && selectedFilter == globalFilter || selectedFilter == FILTER_NONE) {
        return source;
      }
      return appendFilterIcon(source, selectedFilter);
    }
    String sectionName;
    if (showAsArchive) {
      sectionName = Lang.getString(getArchiveSectionNameRes(selectedFilter, chatFolderStyle));
    } else {
      sectionName = Lang.getString(getMainSectionNameRes(selectedFilter, hasFolders));
    }
    return upperCase ? Lang.uppercase(sectionName) : sectionName;
  }

  @StringRes
  private int getMainSectionNameRes (int selectedFilter, boolean hasFolders) {
    if (selectedFilter != FILTER_NONE) {
      return getFilterName(selectedFilter);
    }
    return hasFolders ? R.string.CategoryMain : R.string.Chats;
  }

  @StringRes
  private int getArchiveSectionNameRes (int selectedFilter, @ChatFolderStyle int chatFolderStyle) {
    if (chatFolderStyle == ChatFolderStyle.LABEL_ONLY) {
      switch (selectedFilter) {
        case FILTER_UNREAD:
          return R.string.CategoryArchiveUnread;
        case FILTER_PRIVATE:
          return R.string.CategoryArchivePrivate;
        case FILTER_GROUPS:
          return R.string.CategoryArchiveGroup;
        case FILTER_CHANNELS:
          return R.string.CategoryArchiveChannels;
        case FILTER_BOTS:
          return R.string.CategoryArchiveBots;
        case FILTER_NONE:
        case FILTER_ARCHIVE:
          return R.string.CategoryArchive;
        default:
          throw new UnsupportedOperationException("selectedFilter=" + selectedFilter);
      }
    }
    return selectedFilter == FILTER_NONE ? R.string.CategoryArchive : getFilterName(selectedFilter);
  }

  private CharSequence getFolderSectionName (long pagerItemId, CharSequence folderName, @ChatFolderStyle int chatFolderStyle) {
    int selectedFilter = getSelectedFilter(pagerItemId);
    CharSequence sectionName;
    if (selectedFilter != FILTER_NONE) {
      CharSequence source = chatFolderStyle == ChatFolderStyle.ICON_ONLY ? "" : folderName;
      if (useGlobalFilter() && selectedFilter == globalFilter) {
        sectionName = source;
      } else {
        sectionName = appendFilterIcon(source, selectedFilter);
      }
    } else {
      sectionName = chatFolderStyle == ChatFolderStyle.ICON_ONLY ? "" : folderName;
    }
    return Emoji.instance().replaceEmoji(sectionName);
  }

  @CheckResult
  private CharSequence appendFilterIcon (CharSequence source, @Filter int selectedFilter) {
    SpannableString string = new SpannableString(source + (StringUtils.isEmpty(source) ? "∇" : " ∇"));
    int filterIcon = getFilterVariantIcon(selectedFilter);
    string.setSpan(new IconSpan(filterIcon, ColorId.NONE), string.length() - 1, string.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    return string;
  }

  private @StringRes int getGlobalFilterName (@Filter int filter) {
    return filter == FILTER_NONE ? R.string.Chats : getFilterName(filter);
  }

  private @StringRes int getFilterName (@Filter int filter) {
    switch (filter) {
      case FILTER_UNREAD:
        return R.string.CategoryUnread;
      case FILTER_PRIVATE:
        return R.string.CategoryPrivate;
      case FILTER_GROUPS:
        return R.string.CategoryGroup;
      case FILTER_CHANNELS:
        return R.string.CategoryChannels;
      case FILTER_BOTS:
        return R.string.CategoryBots;
      case FILTER_ARCHIVE:
        return R.string.CategoryArchive;
      case FILTER_NONE:
        return R.string.NoFilter;
      default:
        throw new UnsupportedOperationException("filter=" + filter);
    }
  }

  @DrawableRes
  private int getFilterIcon (@Filter int filter, boolean isGlobal) {
    switch (filter) {
      case FILTER_NONE:
        return isGlobal ? R.drawable.baseline_forum_24 : R.drawable.baseline_filter_variant_remove_24;
      case FILTER_PRIVATE:
        return R.drawable.baseline_person_24;
      case FILTER_GROUPS:
        return R.drawable.baseline_group_24;
      case FILTER_CHANNELS:
        return R.drawable.baseline_bullhorn_24;
      case FILTER_ARCHIVE:
        return R.drawable.baseline_archive_24;
      case FILTER_BOTS:
        return R.drawable.deproko_baseline_bots_24;
      case FILTER_UNREAD:
        return R.drawable.baseline_mark_chat_unread_24;
      default:
        throw new UnsupportedOperationException("filter = " + filter);
    }
  }

  @DrawableRes
  private int getFilterVariantIcon (@Filter int filter) {
    switch (filter) {
      case FILTER_NONE:
      case FILTER_ARCHIVE:
        return ResourcesCompat.ID_NULL;
      case FILTER_PRIVATE:
        return R.drawable.baseline_filter_variant_user_18;
      case FILTER_GROUPS:
        return R.drawable.baseline_filter_variant_group_18;
      case FILTER_CHANNELS:
        return R.drawable.baseline_filter_variant_channel_18;
      case FILTER_BOTS:
        return R.drawable.baseline_filter_variant_bot_18;
      case FILTER_UNREAD:
        return R.drawable.baseline_filter_variant_unread_18;
      default:
        throw new UnsupportedOperationException("filter = " + filter);
    }
  }

  private void modifyNewPagerItemController (final ViewController<?> c) {
    if (c instanceof RecyclerViewProvider) {
      c.getValue();
      ((RecyclerViewProvider) c).provideRecyclerView().addOnScrollListener(new RecyclerView.OnScrollListener() {
        private float lastY;
        private float lastShowY;

        @Override
        public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
          lastY += dy;
          if (dy < 0 && lastShowY - lastY >= Screen.getTouchSlop()) {
            showHideBottomBarOnScroll(c, true);
            lastShowY = lastY;
          } else if (lastY - lastShowY > Screen.getTouchSlopBig()) {
            showHideBottomBarOnScroll(c, false);
            lastShowY = lastY;
          }
          if (Math.abs(lastY - lastShowY) > Screen.getTouchSlopBig()) {
            lastY = 0;
            lastShowY = 0;
          }
        }
      });
    }
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, final int position) {
    final ViewController<?> c;
    long pagerItemId = getPagerItemId(position);
    if (pagerItemId == MAIN_PAGER_ITEM_ID) {
      TdApi.ChatList chatList = this.menuNeedArchive ? ChatPosition.CHAT_LIST_ARCHIVE : ChatPosition.CHAT_LIST_MAIN;
      c = newChatsController(chatList, getSelectedFilter(MAIN_PAGER_ITEM_ID));
    } else if (hasFolders()) {
      TdApi.ChatList chatList = pagerChatLists.get(position);
      c = newChatsController(chatList, getSelectedFilter(pagerItemId));
    } else {
      throw new IllegalArgumentException("position == " + position);
    }
    modifyNewPagerItemController(c);
    return c;
  }

  private @Nullable TdApi.ChatFolderInfo[] chatFolderInfos;
  private List<ViewPagerTopView.Item> pagerSections = Collections.emptyList();
  private List<TdApi.ChatList> pagerChatLists = Collections.emptyList();
  private final LongSparseIntArray pagerChatListFilters = new LongSparseIntArray();
  private @Filter int globalFilter = FILTER_NONE;

  private boolean hasFolders () {
    return Settings.instance().chatFoldersEnabled() && !pagerChatLists.isEmpty();
  }

  @Override
  protected CharSequence[] getPagerSections () {
    if (hasFolders()) {
      throw new UnsupportedOperationException();
    }
    return new CharSequence[] {
      getMenuSectionName(MAIN_PAGER_ITEM_ID, /* pagerItemPosition */ 0, /* hasFolders */ false, ChatFolderStyle.LABEL_ONLY, /* upperCase */ false)
    };
  }

  private List<ViewPagerTopView.Item> defaultSectionItems;
  private ViewPagerTopView.Item defaultMainItem;
  private CounterChangeListener defaultCounterListener;

  private ViewPagerTopView.Item getDefaultMainItem () {
    if (defaultMainItem == null) {
      CharSequence mainItem = getMenuSectionName(MAIN_PAGER_ITEM_ID, 0, false, ChatFolderStyle.LABEL_ONLY,  /* upperCase */ false);
      UnreadCounterColorSet unreadCounterColorSet = UnreadCounterColorSet.create(this);
      Counter unreadCounter = new Counter.Builder()
        .textSize(12f)
        .backgroundPadding(4f)
        .outlineAffectsBackgroundSize(false)
        .colorSet(unreadCounterColorSet)
        .callback(unreadCounterCallback(MAIN_PAGER_ITEM_ID))
        .build();
      unreadCounterColorSet.setCounter(unreadCounter);
      updateCounter(ChatPosition.CHAT_LIST_MAIN, unreadCounter, tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN), false);
      defaultCounterListener = new CounterChangeListener() {
        @Override
        public void onChatCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, boolean availabilityChanged, int totalCount, int unreadCount, int unreadUnmutedCount) {
          handleCounterChange(chatList, counter, false);
        }

        @Override
        public void onMessageCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, int unreadCount, int unreadUnmutedCount) {
          handleCounterChange(chatList, counter, true);
        }

        private void handleCounterChange (@NonNull TdApi.ChatList chatList, TdlibCounter counter, boolean areMessages) {
          int badgeFlags = tdlib.settings().getChatFolderBadgeFlags();
          if (areMessages != BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_MESSAGES)) {
            return;
          }
          if (chatList.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
            executeOnUiThreadOptional(() -> {
              updateCounter(chatList, unreadCounter, counter, isFocused());
            });
          } else if (chatList.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR && BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_ARCHIVED)) {
            executeOnUiThreadOptional(() -> {
              updateCounter(ChatPosition.CHAT_LIST_MAIN, unreadCounter, tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN), isFocused());
            });
          }
        }
      };
      defaultMainItem = new ViewPagerTopView.Item(mainItem, unreadCounter);
      tdlib.listeners().addTotalChatCounterListener(defaultCounterListener);
    }
    return defaultMainItem;
  }

  private List<ViewPagerTopView.Item> getDefaultSectionItems () {
    if (defaultSectionItems == null) {
      // Not displayed (the app bar shows the title instead), still backs the pager adapter.
      // Mutable: ViewPagerTopView keeps this list and replaces items in place (setItemAt), e.g. when the unread counter resizes.
      defaultSectionItems = new ArrayList<>(1);
      defaultSectionItems.add(getDefaultMainItem());
    }
    return defaultSectionItems;
  }

  @Override
  protected List<ViewPagerTopView.Item> getPagerSectionItems () {
    return hasFolders() ? pagerSections : getDefaultSectionItems();
  }

  // Menu

  @Override
  protected int getMenuId () {
    return R.id.menu_main;
  }



  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_main) {
      header.addLockButton(menu);
      // Search moved to the navigation dock (phone) and the search bar (tablet).
      header.addSearchButton(menu, this).setContentDescription(Lang.getString(R.string.Search));
      // Phone: sections of the collapsed dock.
      header.addButton(menu, R.id.menu_btn_calls, R.drawable.baseline_phone_24, 49f, this, getHeaderIconColorId())
        .setContentDescription(Lang.getString(R.string.Calls));
      header.addButton(menu, R.id.menu_btn_contacts, R.drawable.baseline_perm_contact_calendar_24, 49f, this, getHeaderIconColorId())
        .setContentDescription(Lang.getString(R.string.Contacts));
      header.addButton(menu, newAccountHeaderButton(header));
      bindHeaderDockTooltips(menu);
      if (!mainMenus.contains(menu)) {
        mainMenus.add(menu);
      }
      updateHeaderDockButtons();
      header.addButton(menu, R.id.menu_btn_compose, R.drawable.baseline_create_24, 49f, this, getHeaderIconColorId())
        .setContentDescription(Lang.getString(R.string.NewChat));
    } else if (id == R.id.menu_clear) {
      header.addClearButton(menu, getSearchHeaderIconColorId(), getSearchBackButtonResource());
    } else {
      super.fillMenuItems(id, header, menu);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_compose) {
      showComposeMenu();
    } else if (id == R.id.menu_btn_search) {
      openMainSearch();
    } else if (id == R.id.menu_btn_calls) {
      onNavigationItemClick(MainNavigationBar.ITEM_CALLS);
    } else if (id == R.id.menu_btn_contacts) {
      onNavigationItemClick(MainNavigationBar.ITEM_CONTACTS);
    } else if (id == R.id.menu_btn_clear) {
      clearSearchInput();
    } else {
      super.onMenuItemPressed(id, view);
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (commit) {
      if (scrollToFirstPosition()) {
        return true;
      }
    } else {
      if (!isAtFirstPosition()) {
        return true;
      }
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  // Intent process

  private Tdlib shareTdlib;
  private String shareIntentAction;
  private Intent shareIntent;

  public void shareIntent (final Tdlib tdlib, final String intentAction, Intent intent) {
    this.shareTdlib = tdlib;
    this.shareIntentAction = intentAction;
    this.shareIntent = intent;
    shareIntentImpl();
  }

  private void shareIntentImpl () {
    final Tdlib tdlib = shareTdlib;
    final Intent intent = shareIntent;
    final String intentAction = shareIntentAction;
    if (intent == null) {
      return;
    }

    BaseActivity activity = context();
    if (activity.isPasscodeShowing()) {
      activity.addPasscodeListener(new BaseActivity.PasscodeListener() {
        @Override
        public void onPasscodeShowing (BaseActivity context, boolean isShowing) {
          if (isShowing) {
            activity.removePasscodeListener(this);
            shareIntentImpl();
          }
        }
      });
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      if (context().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        context().requestCustomPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, (code, permissions, grantResults, grantCount) -> {
          if (grantCount == permissions.length) {
            shareIntentImpl();
          } else {
            UI.showToast(R.string.NoStorageAccess, Toast.LENGTH_SHORT);
          }
        });
        return;
      }
    }

    shareTdlib = null;
    shareIntent = null;
    shareIntentAction = null;
    new Thread(() -> {
      CancellableRunnable runnable = new CancellableRunnable() {
        @Override
        public void act () {
          UI.showToast(R.string.ProcessingFileWait, Toast.LENGTH_SHORT);
        }
      };
      runnable.removeOnCancel(UI.getAppHandler());
      UI.post(runnable, 1000l);
      try {
        switch (intentAction) {
          case Intent.ACTION_SEND: {
            shareIntentImplSingle(tdlib, intent);
            break;
          }
          case Intent.ACTION_SEND_MULTIPLE: {
            shareIntentImplMultiple(tdlib, intent);
            break;
          }
        }
      } catch (Throwable t) {
        Log.e(t);
        UI.showToast(R.string.ShareContentUnsupported, Toast.LENGTH_SHORT);
      } finally {
        runnable.cancel();
      }
    }).start();
  }

  @SuppressWarnings("deprecation")
  @Nullable
  private static Uri getUri (@NonNull Bundle bundle, @NonNull String key) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return bundle.getParcelable(key, Uri.class);
    } else {
      return (Uri) bundle.get(key);
    }
  }

  private void shareIntentImplSingle (Tdlib tdlib, final Intent intent) throws Throwable {
    String type = intent.getType();
    final ArrayList<TdApi.InputMessageContent> out = new ArrayList<>();

    if (!StringUtils.isEmpty(type) && ContactsContract.Contacts.CONTENT_VCARD_TYPE.equals(type)) {
      Bundle extras = intent.getExtras();
      if (extras == null) {
        throw new IllegalArgumentException("extras == null");
      }
      Uri uri = getUri(extras, Intent.EXTRA_STREAM);
      if (uri == null) {
        throw new IllegalArgumentException("uri == null");
      }
      ContentResolver cr = UI.getContext().getContentResolver();
      try (InputStream stream = cr.openInputStream(uri)) {
        if (stream == null) {
          throw new IllegalArgumentException("stream == null (vcard)");
        }

        boolean vCardStarted = false;
        String currentName = null;

        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream, StringUtils.UTF_8))) {
          String line;
          while ((line = bufferedReader.readLine()) != null) {
            String[] args = line.split(":");
            if (args.length != 2) {
              continue;
            }
            if (args[0].equals("BEGIN") && args[1].equals("VCARD")) {
              vCardStarted = true;
              currentName = null;
            } else if (args[0].equals("END") && args[1].equals("VCARD")) {
              vCardStarted = false;
            }
            if (!vCardStarted) {
              continue;
            }
            if (args[0].startsWith("FN") || (args[0].startsWith("ORG") && StringUtils.isEmpty(currentName))) {
              String nameEncoding = null;
              String nameCharset = "UTF-8";
              String[] params = args[0].split(";");
              for (String param : params) {
                String[] args2 = param.split("=");
                if (args2.length != 2) {
                  continue;
                }
                if (args2[0].equals("CHARSET")) {
                  nameCharset = args2[1];
                } else if (args2[0].equals("ENCODING")) {
                  nameEncoding = args2[1];
                }
              }
              currentName = args[1];
              if (nameEncoding != null && nameEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
                while (currentName.endsWith("=")) {
                  currentName = currentName.substring(0, currentName.length() - 1);
                  line = bufferedReader.readLine();
                  if (line == null) {
                    break;
                  }
                  currentName += line;
                }
                byte[] bytes = U.decodeQuotedPrintable(currentName.getBytes());
                if (bytes != null && bytes.length != 0) {
                  currentName = new String(bytes, nameCharset);
                }
              }
            } else if (args[0].startsWith("TEL")) {
              String phone = Strings.getNumber(args[1]);
              if (!phone.isEmpty()) {
                boolean found = false;
                if (!out.isEmpty()) {
                  for (TdApi.InputMessageContent content : out) {
                    if (content instanceof TdApi.InputMessageContact && StringUtils.equalsOrBothEmpty(((TdApi.InputMessageContact) content).contact.phoneNumber, phone)) {
                      found = true;
                      break;
                    }
                  }
                }
                if (!found) {
                  out.add(new TdApi.InputMessageContact(new TdApi.Contact(phone, currentName, null, null, 0))); // TODO VCARD sharing
                }
              }
            }
          }
        }
        if (out.isEmpty()) {
          UI.showToast("No phone number available to share", Toast.LENGTH_SHORT);
          return;
        }
      }
    } else {
      // First, obtain text
      String sendingText = obtainText(intent);

      // Second, obtain media
      Parcelable parcelable = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      Uri uri = U.getUri(parcelable);
      if (uri != null) {
        if (U.isInternalUri(uri)) {
          throw new IllegalArgumentException("Tried to share internal file: " + uri.toString());
        }
        if (addShareUri(tdlib, out, type, uri, sendingText)) {
          sendingText = null;
        }
      }

      // If sendingText still unused, then add it to the beginning of send queue
      if (!StringUtils.isEmpty(sendingText)) {
        out.addAll(0, TD.explodeText(new TdApi.InputMessageText(new TdApi.FormattedText(sendingText, null), null, false), tdlib.maxMessageTextLength()));
      }
    }
    shareContents(tdlib, type, out, false);
  }

  private static String obtainText (Intent intent) {
    String text = intent.getStringExtra(Intent.EXTRA_TEXT);
    if (text == null) {
      CharSequence textSequence = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
      if (textSequence != null) {
        text = textSequence.toString();
      }
    }
    String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
    String result = getShareText(subject, text);
    return result != null ? result.trim() : null;
  }

  private void shareIntentImplMultiple (Tdlib tdlib, Intent intent) {
    ArrayList<Parcelable> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    ArrayList<TdApi.InputMessageContent> out = new ArrayList<>(uris.size());
    String type = intent.getType();

    // First, obtain text
    String sendingText = obtainText(intent);

    for (Parcelable parcelable : uris) {
      Uri uri = U.getUri(parcelable);
      if (uri == null) {
        throw new IllegalArgumentException("Unknown parcelable type: " + parcelable);
      }
      if (addShareUri(tdlib, out, type, uri, sendingText)) {
        sendingText = null;
      }
    }
    // If sendingText still unused, then add it to the beginning of send queue
    if (!StringUtils.isEmpty(sendingText)) {
      out.addAll(0, TD.explodeText(new TdApi.InputMessageText(new TdApi.FormattedText(sendingText, null), null, false), tdlib.maxMessageTextLength()));
    }

    shareContents(tdlib, type, out, true);
  }

  private static String getShareText (String subject, String text) {
    if (StringUtils.isEmpty(text) && StringUtils.isEmpty(subject)) {
      return null;
    }
    StringBuilder b = new StringBuilder(StringUtils.length(text) + 1 + StringUtils.length(subject));
    if (!StringUtils.isEmpty(subject) && (StringUtils.isEmpty(text) || text.startsWith("https://") || text.startsWith("http://"))) {
      b.append(subject);
    }
    if (!StringUtils.isEmpty(text)) {
      if (b.length() > 0) {
        b.append('\n');
      }
      b.append(text);
    }
    String res = b.toString().trim();
    return res.isEmpty() ? null : res;
  }

  /**
   *
   * @return true if provided caption parameter has been used as caption
   */
  private static boolean addShareUri (Tdlib tdlib, ArrayList<TdApi.InputMessageContent> out, String mimeType, Uri uri, final @Nullable String rawCaption) {
    if (uri == null) {
      return false;
    }

    String filePath = U.tryResolveFilePath(uri);

    if (StringUtils.isEmpty(filePath)) {
      throw new IllegalArgumentException("filePath cannot be resolved for type " + mimeType + ", uri: " + uri);
    }

    if (mimeType.isEmpty()) {
      String extension = U.getExtension(filePath);
      if (extension != null) {
        mimeType = TGMimeType.mimeTypeForExtension(extension);
      }
    }

    if (!U.canReadFile(filePath)) {
      filePath = uri.toString();

      if (!U.canReadContentUri(uri)) {
        return false;
      }
    }

    final int captionCodePointCount = rawCaption != null ? rawCaption.codePointCount(0, rawCaption.length()) : 0;
    final TdApi.FormattedText messageCaption = captionCodePointCount > 0 && captionCodePointCount <= tdlib.maxCaptionLength() ? new TdApi.FormattedText(rawCaption, null) : null;

    if (!StringUtils.isEmpty(mimeType)) {
      if (mimeType.equals("image/webp")) {
        BitmapFactory.Options options = ImageReader.getImageWebpSize(filePath);
        out.add(new TdApi.InputMessageSticker(new TdApi.InputSticker(TD.createInputFile(filePath), null, options.outWidth, options.outHeight), null));
        return false;
      }

      if (mimeType.equals("image/gif")) {
        BitmapFactory.Options options = ImageReader.getImageSize(filePath);
        out.add(new TdApi.InputMessageAnimation(new TdApi.InputAnimation(TD.createInputFile(filePath), null, null, 0, options.outWidth, options.outHeight), messageCaption, false, false));
        return messageCaption != null;
      }

      if (mimeType.startsWith("image/")) {
        BitmapFactory.Options opts = ImageReader.getImageSize(filePath);

        int rotation = U.getRotation(filePath);
        int inSampleSize = ImageReader.calculateInSampleSize(opts, 1280, 1280);
        int width = rotation == 90 || rotation == 270 ? opts.outHeight / inSampleSize : opts.outWidth / inSampleSize;
        int height = rotation == 90 || rotation == 270 ? opts.outWidth / inSampleSize : opts.outHeight / inSampleSize;

        TdApi.InputFileGenerated inputFile = PhotoGenerationInfo.newFile(filePath, rotation);
        out.add(new TdApi.InputMessagePhoto(new TdApi.InputPhoto(inputFile, null, null, null, width, height), messageCaption, false, null, false));
        return messageCaption != null;
      }

      if (mimeType.startsWith("video/")) {
        MediaMetadataRetriever media = null;
        try {
          media = U.openRetriever(filePath);
        } catch (Throwable ignored) { }
        if (media != null) {

          String rawDuration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
          int duration = StringUtils.isNumeric(rawDuration) ? (int) (Long.parseLong(rawDuration) / 1000l) : 0;
          String rawWidth = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
          int width = StringUtils.isNumeric(rawWidth) ? StringUtils.parseInt(rawWidth) : 0;
          String rawHeight = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
          int height = StringUtils.isNumeric(rawHeight) ? StringUtils.parseInt(rawHeight) : 0;

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            String rawRotation = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = StringUtils.isNumeric(rawRotation) ? StringUtils.parseInt(rawRotation) : 0;
            if (rotation == 90 || rotation == 270) {
              int temp = height;
              height = width;
              width = temp;
            }
          }


          TdApi.InputFile inputVideo;
          if (Config.USE_VIDEO_COMPRESSION) {
            inputVideo = VideoGenerationInfo.newFile(filePath, null, false);
          } else {
            inputVideo = TD.createInputFile(filePath);
          }

          U.closeRetriever(media);

          out.add(new TdApi.InputMessageVideo(new TdApi.InputVideo(inputVideo, null, null, 0, null, duration, width, height, U.canStreamVideo(inputVideo)), messageCaption, false, null, false));
          return messageCaption != null;
        }
      }
    }

    TD.FileInfo info = new TD.FileInfo();
    TdApi.InputFile file = TD.createInputFile(filePath, mimeType, info);

    out.add(TD.toInputMessageContent(filePath, file, info, messageCaption, false, false));
    return messageCaption != null;
  }

  private void shareContents (final Tdlib tdlib, final String type, final ArrayList<TdApi.InputMessageContent> contents, boolean mergeAlbum) {
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("Unsupported content type: " + type);
    }
    tdlib.ui().post(() -> tdlib.awaitInitialization(() -> tdlib.ui().post(() -> {
      ShareController c = new ShareController(context, tdlib);

      ShareController.ShareProviderDelegate shareDelegate = new ShareController.ShareProviderDelegate() {
        @Override
        public void generateFunctionsForChat (long chatId, TdApi.Chat chat, TdApi.MessageSendOptions sendOptions, ArrayList<TdApi.Function<?>> functions) {
          List<TdApi.InputMessageContent> album = null;
          for (TdApi.InputMessageContent content : contents) {
            content = tdlib.filegen().createThumbnail(content, ChatId.isSecret(chatId));
            if (mergeAlbum) {
              int combineMode = TD.getCombineMode(content);
              if (combineMode == TD.COMBINE_MODE_MEDIA) {
                if (album == null)
                  album = new ArrayList<>();
                album.add(content);
                if (album.size() == TdConstants.MAX_MESSAGE_GROUP_SIZE)
                  TD.processAlbum(tdlib, chatId, sendOptions, functions, album);
              } else {
                TD.processAlbum(tdlib, chatId, sendOptions, functions, album);
                TD.processSingle(tdlib, chatId, sendOptions, functions, content);
              }
            } else {
              TD.processSingle(tdlib, chatId, sendOptions, functions, content);
            }
          }
          if (mergeAlbum) {
            TD.processAlbum(tdlib, chatId, sendOptions, functions, album);
          }
        }

        @Override
        public CharSequence generateErrorMessageForChat (long chatId) {
          for (TdApi.InputMessageContent content : contents) {
            CharSequence errorMessage = tdlib.getRestrictionText(tdlib.chatStrict(chatId), content);
            if (errorMessage != null)
              return errorMessage;
          }
          return null;
        }
      };

      c.setArguments(new ShareController.Args(shareDelegate).setNeedOpenChat(true));
      c.show();
    })));
  }

  // Sync alert

  private SettingsWrap syncAlertWrap;
  private boolean syncShown;

  private boolean showSyncAlert () {
    if (syncShown || (syncAlertWrap != null && syncAlertWrap.window != null && !syncAlertWrap.window.isWindowHidden()))
      return true;
    if (!Settings.instance().needTutorial(Settings.TUTORIAL_SYNC_SETTINGS))
      return false;
    syncAlertWrap = showSettings(new SettingsWrapBuilder(R.id.btn_notificationSettings).setIntDelegate((id, result) -> {
      SyncAdapter.turnOnSync(context, tdlib, true);
    }).setDismissListener(popup -> {
      syncAlertWrap = null;
      showAnnoyingAlertsForCompliance(false);
    }).setOnActionButtonClick((wrap, view, isCancel) -> {
      if (isCancel) {
        int i = wrap.adapter.indexOfViewById(R.id.btn_neverAllow);
        if (i != -1 && wrap.adapter.getItems().get(i).isSelected()) {
          Settings.instance().markTutorialAsComplete(Settings.TUTORIAL_SYNC_SETTINGS);
        }
      }
      return false;
    }).setCancelStr(R.string.NotificationSyncDecline).setSaveStr(R.string.NotificationSyncAccept).setAllowResize(false).setRawItems(new ListItem[] {
      new ListItem(ListItem.TYPE_CHECKBOX_OPTION, R.id.btn_neverAllow, 0, R.string.NeverShowAgain, false)
    }).addHeaderItem(Lang.getMarkdownString(this, R.string.NotificationSyncOffWarn)));
    if (syncAlertWrap != null) {
      syncShown = true;
      return true;
    }
    return false;
  }

  private void hideSyncAlert () {
    if (syncAlertWrap != null) {
      if (syncAlertWrap.window != null)
        syncAlertWrap.window.hideWindow(true);
      syncAlertWrap = null;
      syncShown = false;
    }
  }

  private boolean checkSyncAlert () {
    if (tdlib.notifications().needSyncAlert()) {
      return showSyncAlert();
    } else {
      hideSyncAlert();
      return false;
    }
  }

  // Chat folders

  private void showChatListOptions (TdApi.ChatList chatList) {
    boolean isMain = TD.isChatListMain(chatList);
    boolean isFolder = TD.isChatListFolder(chatList);
    boolean isArchive = TD.isChatListArchive(chatList);
    int chatFolderId = isFolder ? ((TdApi.ChatListFolder) chatList).chatFolderId : 0;
    TdApi.ChatFolderInfo chatFolderInfo;
    CharSequence title;
    if (isMain) {
      title = Lang.getString(R.string.CategoryMain);
      chatFolderInfo = null;
    } else if (isArchive) {
      title = Lang.getString(R.string.CategoryArchive);
      chatFolderInfo = null;
    } else if (isFolder) {
      chatFolderInfo = tdlib.chatFolderInfo(chatFolderId);
      title = chatFolderInfo != null ? TD.toCharSequence(chatFolderInfo.name) : null;
    } else {
      title = null;
      chatFolderInfo = null;
    }
    Options.Builder options = new Options.Builder();
    if (!StringUtils.isEmptyOrBlank(title)) {
      options.info(title);
    }
    if (tdlib.hasUnreadChats(chatList)) {
      options.item(new OptionItem(R.id.btn_markFolderAsRead, Lang.getString(R.string.MarkFolderAsRead), OptionColor.NORMAL, R.drawable.baseline_done_all_24));
    }
    if (isFolder) {
      options.item(new OptionItem(R.id.btn_editFolder, Lang.getString(R.string.EditFolder), OptionColor.NORMAL, R.drawable.baseline_edit_24));
      int chatFolderStyle = tdlib.settings().chatFolderStyle();
      if (hasIcon(chatFolderStyle)) {
        options.item(new OptionItem(R.id.btn_changeFolderIcon, Lang.getString(R.string.ChatFolderChangeIcon), OptionColor.NORMAL, R.drawable.baseline_image_24));
      }
      options.item(new OptionItem(R.id.btn_folderIncludeChats, Lang.getString(R.string.ChatFolderAddChats), OptionColor.NORMAL, R.drawable.baseline_add_24));
      if (chatFolderInfo != null) {
        options.item(new OptionItem(R.id.btn_shareFolder, Lang.getString(R.string.ShareFolder), OptionColor.NORMAL, R.drawable.baseline_share_arrow_24));
      }
    }
    if (isMain) {
      if (!Config.RESTRICT_HIDING_MAIN_LIST) {
        options.item(new OptionItem(R.id.btn_hideFolder, Lang.getString(R.string.HideAllChats), OptionColor.NORMAL, R.drawable.baseline_eye_off_24));
      }
    } else if (isFolder || isArchive) {
      options.item(new OptionItem(R.id.btn_hideFolder, Lang.getString(R.string.HideFolder), OptionColor.NORMAL, R.drawable.baseline_eye_off_24));
    }
    if (isFolder) {
      options.item(new OptionItem(R.id.btn_removeFolder, Lang.getString(R.string.RemoveFolder), OptionColor.RED, R.drawable.baseline_delete_24));
    }
    if (options.itemCount() > 0) {
      options.item(OptionItem.SEPARATOR);
    }
    options.item(new OptionItem(R.id.btn_chatFolders, Lang.getString(R.string.EditFolders), OptionColor.NORMAL, R.drawable.baseline_edit_folders_24));
    showOptions(options.build(), (v, id) -> {
      if (id == R.id.btn_editFolder) {
        tdlib.send(new TdApi.GetChatFolder(chatFolderId), (chatFolder, error) -> runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            EditChatFolderController controller = new EditChatFolderController(context, tdlib);
            controller.setArguments(new EditChatFolderController.Arguments(chatFolderId, chatFolder));
            navigateTo(controller);
          }
        }));
      } else if (id == R.id.btn_hideFolder) {
        tdlib.settings().setChatListEnabled(chatList, false);
        if (headerCell != null && !StringUtils.isEmptyOrBlank(title)) {
          CharSequence text = TD.isChatListArchive(chatList) ?
            Lang.getString(R.string.HideArchiveFolderInfo) :
            Lang.getString(R.string.HideFolderInfo2, title);
          context()
            .tooltipManager()
            .builder(headerCell.getTopView())
            .locate((targetView, outRect) -> outRect.left = outRect.right = Screen.dp(56f) - (int) targetView.getX())
            .controller(this)
            .show(tdlib, text)
            .hideDelayed(3500, TimeUnit.MILLISECONDS);
        }
      } else if (id == R.id.btn_folderIncludeChats) {
        tdlib.send(new TdApi.GetChatFolder(chatFolderId), (chatFolder, error) -> runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            boolean showChatTypes = !chatFolder.isShareable;
            SelectChatsController controller = new SelectChatsController(context, tdlib);
            controller.setArguments(SelectChatsController.Arguments.includedChats(chatFolderId, chatFolder, showChatTypes));
            navigateTo(controller);
          }
        }));
      } else if (id == R.id.btn_changeFolderIcon) {
        tdlib.send(new TdApi.GetChatFolder(chatFolderId), (chatFolder, error) -> runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            ChatFolderIconSelector.show(this, chatFolder, selectedIcon ->
              tdlib.setChatFolderIcon(chatFolderId, selectedIcon, Config.CHAT_FOLDERS_UNSET_DEFAULT_ICONS)
            );
          }
        }));
      } else if (id == R.id.btn_shareFolder) {
        TdApi.ChatFolderInfo info = ObjectsCompat.requireNonNull(chatFolderInfo);
        if (info.hasMyInviteLinks) {
          tdlib.send(new TdApi.GetChatFolderInviteLinks(chatFolderId), (result, error) -> {
            if (error != null) {
              UI.showError(error);
            } else {
              runOnUiThreadOptional(() -> showChatFolderInviteLinks(chatFolderId, title, result.inviteLinks));
            }
          });
        } else {
          return onCreateChatFolderInviteLinkClick(v, chatFolderId, info.isShareable, /* chatFolderInviteLinkCount */ 0L);
        }
      } else if (id == R.id.btn_removeFolder) {
        tdlib.ui().showDeleteChatFolderOrLeaveChats(this, chatFolderId);
      } else if (id == R.id.btn_chatFolders) {
        navigateTo(new SettingsFoldersController(context, tdlib));
      } else if (id == R.id.btn_markFolderAsRead) {
        tdlib.readAllChats(chatList, /* after */ null);
      }
      return true;
    });
  }

  private void showChatFolderInviteLinks (int chatFolderId, @Nullable CharSequence title, TdApi.ChatFolderInviteLink[] inviteLinks) {
    Options.Builder options = new Options.Builder();
    if (!StringUtils.isEmptyOrBlank(title)) {
      options.info(title);
    }
    long chatFolderInviteLinkCount = inviteLinks.length;
    long chatFolderInviteLinkCountMax = tdlib.chatFolderInviteLinkCountMax();
    boolean canCreateInviteLink = chatFolderInviteLinkCount < chatFolderInviteLinkCountMax;
    int createNewLinkOptionColor = canCreateInviteLink ? OptionColor.BLUE : OptionColor.INACTIVE;
    SparseArrayCompat<TdApi.ChatFolderInviteLink> inviteLinkById = new SparseArrayCompat<>(inviteLinks.length);
    for (TdApi.ChatFolderInviteLink inviteLink : inviteLinks) {
      int id = ViewCompat.generateViewId();
      inviteLinkById.append(id, inviteLink);
      String name = StringUtils.isEmptyOrInvisible(inviteLink.name) ? StringUtils.urlWithoutProtocol(inviteLink.inviteLink) : inviteLink.name;
      options.item(new OptionItem(id, name, OptionColor.NORMAL, R.drawable.baseline_link_24));
    }
    options.item(new OptionItem(R.id.btn_createInviteLink, Lang.getString(R.string.CreateANewLink), createNewLinkOptionColor, R.drawable.baseline_add_link_24));
    PopupLayout popupLayout = showOptions(options.build(), (view, id) -> {
      if (id == R.id.btn_createInviteLink) {
        return onCreateChatFolderInviteLinkClick(view, chatFolderId, true, chatFolderInviteLinkCount);
      }
      TdApi.ChatFolderInviteLink inviteLink = ObjectsCompat.requireNonNull(inviteLinkById.get(id));
      showChatFolderInviteLinkOptions(chatFolderId, inviteLink);
      return true;
    });
    if (!canCreateInviteLink) {
      View button = popupLayout.getBoundView().findViewById(R.id.btn_createInviteLink);
      if (button instanceof TextView) {
        TextView textView = (TextView) button;
        Views.setMediumText(textView, textView.getText());
        Drawable[] drawables = textView.getCompoundDrawables();
        Drawable lock = Drawables.get(R.drawable.baseline_lock_16);
        lock.setColorFilter(Paints.getColorFilter(Theme.textAccentColor()));
        addThemeFilterListener(lock, ColorId.text);
        button.setTag(R.id.tag_tooltip_location_provider, (TooltipOverlayView.LocationProvider) (targetView, outRect) -> {
          outRect.set(lock.getBounds());
          int top = (targetView.getHeight() - outRect.height()) / 2;
          int left = Lang.rtl() ? targetView.getPaddingLeft() : targetView.getWidth() - targetView.getPaddingRight() - outRect.width();
          outRect.offsetTo(left, top);
          outRect.inset(0, -Screen.dp(10f));
        });
        if (Lang.rtl()) {
          textView.setCompoundDrawablesWithIntrinsicBounds(lock, null, drawables[2], null);
        } else {
          textView.setCompoundDrawablesWithIntrinsicBounds(drawables[0], null, lock, null);
        }
      }
    }
  }

  private boolean onCreateChatFolderInviteLinkClick (View view, int chatFolderId, boolean isChatFolderShareable, long chatFolderInviteLinkCount) {
    if (chatFolderInviteLinkCount == 0 && !isChatFolderShareable && !tdlib.canAddShareableFolder()) {
      showShareableFoldersLimitReached(view);
      return false;
    }
    if (chatFolderInviteLinkCount >= tdlib.chatFolderInviteLinkCountMax()) {
      showChatFolderInviteLinksLimitReached(view);
      return false;
    }
    createChatFolderInviteLink(chatFolderId);
    return true;
  }

  private void showShareableFoldersLimitReached (View view) {
    UI.forceVibrateError(view);
    PopupLayout popupLayout = PopupLayout.parentOf(view);
    tdlib.ui().showLimitReachedInfo(this, popupLayout.tooltipManager(), view, TdlibUi.PremiumLimit.SHAREABLE_FOLDER_COUNT);
  }

  private void showChatFolderInviteLinksLimitReached (View view) {
    UI.forceVibrateError(view);
    PopupLayout popupLayout = PopupLayout.parentOf(view);
    tdlib.ui().showLimitReachedInfo(this, popupLayout.tooltipManager(), view, TdlibUi.PremiumLimit.CHAT_FOLDER_INVITE_LINK_COUNT);
  }

  private void showChatFolderInviteLinkOptions (int chatFolderId, TdApi.ChatFolderInviteLink inviteLink) {
    String title = StringUtils.isEmptyOrInvisible(inviteLink.name) ? StringUtils.urlWithoutProtocol(inviteLink.inviteLink) : inviteLink.name;
    Options.Builder options = new Options.Builder();
    options.info(title);
    options.item(new OptionItem(R.id.btn_shareLink, Lang.getString(R.string.ShareLink), OptionColor.NORMAL, R.drawable.baseline_share_arrow_24));
    options.item(new OptionItem(R.id.btn_copyLink, Lang.getString(R.string.InviteLinkCopy), OptionColor.NORMAL, R.drawable.baseline_content_copy_24));
    options.item(new OptionItem(R.id.btn_edit, Lang.getString(R.string.InviteLinkEdit), OptionColor.NORMAL, R.drawable.baseline_edit_24));
    showOptions(options.build(), (view, id) -> {
      if (id == R.id.btn_shareLink) {
        tdlib.ui().shareUrl(this, inviteLink.inviteLink);
      } else if (id == R.id.btn_copyLink) {
        UI.copyText(inviteLink.inviteLink, R.string.CopiedLink);
      } else if (id == R.id.btn_edit) {
        tdlib.send(new TdApi.GetChatFolder(chatFolderId), (chatFolder, error) ->  runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            EditChatFolderInviteLinkController controller = new EditChatFolderInviteLinkController(context, tdlib);
            controller.setArguments(new EditChatFolderInviteLinkController.Arguments(chatFolderId, chatFolder, inviteLink));
            navigateTo(controller);
          }
        }));
      } else {
        throw new UnsupportedOperationException();
      }
      return true;
    });
  }

  @AnyThread
  private void createChatFolderInviteLink (int chatFolderId) {
    tdlib.send(new TdApi.GetChatFolder(chatFolderId), (chatFolder, error) -> {
      if (error != null) {
        UI.showError(error);
      } else {
        createChatFolderInviteLink(chatFolderId, chatFolder);
      }
    });
  }

  @AnyThread
  private void createChatFolderInviteLink (int chatFolderId, TdApi.ChatFolder chatFolder) {
    if (TD.countIncludedChatTypes(chatFolder) > 0 || TD.countExcludedChatTypes(chatFolder) > 0) {
      runOnUiThreadOptional(() -> {
        CharSequence message = Lang.getMarkdownString(this, R.string.ChatFolderInviteLinkChatTypesNotSupported);
        if (showFolderTooltip(chatFolderId, message)) {
          View topView = headerCell != null ? headerCell.getTopView() : null;
          if (topView != null) {
            UI.forceVibrateError(topView);
          }
        } else {
          UI.showCustomToast(message, Toast.LENGTH_LONG, 0);
        }
      });
      return;
    }
    tdlib.send(new TdApi.GetChatsForChatFolderInviteLink(chatFolderId), (chats, error) -> {
      if (error != null) {
        UI.showError(error);
      } else {
        createChatFolderInviteLink(chatFolderId, chatFolder, chats.chatIds);
      }
    });
  }

  @AnyThread
  private void createChatFolderInviteLink (int chatFolderId, TdApi.ChatFolder chatFolder, long[] shareableChatIds) {
    if (shareableChatIds.length == 0) {
      UI.showCustomToast(R.string.ChatFolderInviteLinkNoChatsToShare, Toast.LENGTH_SHORT, 0);
      return;
    }
    tdlib.createChatFolderInviteLink(chatFolderId, /* name */ "", shareableChatIds, (inviteLink, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
      } else {
        EditChatFolderInviteLinkController controller = new EditChatFolderInviteLinkController(context, tdlib);
        controller.setArguments(new EditChatFolderInviteLinkController.Arguments(chatFolderId, chatFolder, shareableChatIds, inviteLink));
        navigateTo(controller);
      }
    }));
  }

  private @Nullable ViewGroup bottomBar;
  private @Nullable FillingSpace bottomBarSpace;
  private @Px float bottomBarOffsetByPlayer;
  private @Px float bottomBarOffsetBySnackBar;
  private final BoolAnimator isBottomBarVisible = new BoolAnimator(0, (id, factor, fraction, callee) -> {
    invalidateBottomBarOffset();
  }, AnimatorUtils.DECELERATE_INTERPOLATOR, 200l, true);

  private void showBottomBar () {
    if (bottomBar != null) {
      isBottomBarVisible.setValue(true, isFocused());
    }
  }

  private void hideBottomBar () {
    if (bottomBar != null) {
      isBottomBarVisible.setValue(false, isFocused());
    }
  }

  private void invalidateBottomBarOffset () {
    if (bottomBar != null) {
      float bottomBarOffsetByScroll = (getHeaderHeight() + Screen.dp(2f) + Views.getBottomMargin(bottomBar)) * (1f - isBottomBarVisible.getFloatValue());
      float y = bottomBarOffsetByPlayer + bottomBarOffsetByScroll + bottomBarOffsetBySnackBar;
      bottomBar.setTranslationY(y);
      bottomBarSpace.setTranslationY(Math.max(0, y));
      if (navigationBar != null && !navigationBar.isBar()) {
        // Phone: the dock stands on the folders bar and goes down with it when it hides on scroll.
        navigationBar.setTranslationY(Math.min(y, getHeaderHeight()));
      }
    }
  }

  @Override
  protected boolean applyPlayerOffset (float factor, float top) {
    boolean result = super.applyPlayerOffset(factor, top);
    if (result) {
      bottomBarOffsetByPlayer = factor < 1f ? -top : 0;
      invalidateBottomBarOffset();
    }
    return result;
  }

  private boolean showFolderTooltip (int chatFolderId, CharSequence message) {
    return showFolderTooltip(new TdApi.ChatListFolder(chatFolderId), message);
  }

  private boolean showFolderTooltip (TdApi.ChatList chatList, CharSequence message) {
    if (headerCell == null) {
      return false;
    }
    int index = indexOfChatList(chatList);
    if (index == -1) {
      return false;
    }
    ViewPagerTopView topView = headerCell.getTopView();
    Rect itemRect = new Rect();
    if (topView == null || !topView.getItemRect(index, itemRect)) {
      return false;
    }
    int topViewX = Math.round(topView.getX());
    int topViewParentWidth = ((View) topView.getParent()).getWidth();
    int horizontalInset = Screen.dp(16f);
    itemRect.left = Math.max(itemRect.left, horizontalInset - topViewX);
    itemRect.right = Math.min(itemRect.right, topViewParentWidth - topViewX - horizontalInset);
    if (itemRect.isEmpty()) {
      return false;
    }
    context()
      .tooltipManager()
      .builder(topView)
      .locate((targetView, outRect) -> outRect.set(itemRect))
      .controller(this)
      .show(tdlib, message)
      .hideDelayed(3500, TimeUnit.MILLISECONDS);
    return true;
  }

  private int indexOfChatList (TdApi.ChatList chatList) {
    for (int i = 0; i < pagerChatLists.size(); i++) {
      if (Td.equalsTo(chatList, pagerChatLists.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private void checkTabs () {
    if (headerCell == null)
      return;
    ViewPagerHeaderViewCompact headerCellView = (ViewPagerHeaderViewCompact) headerCell.getView();
    int placement = getFoldersPlacement();
    appliedFoldersPlacement = placement;
    boolean hasFolders = placement != FOLDERS_NONE;
    boolean displayTabsAtBottom = placement == FOLDERS_BOTTOM;
    headerCell.getTopView().setAnimateItemChanges(!Settings.instance().needReduceMotion());
    headerCell.getTopView().setShowLabelOnActiveOnly(hasFolders && tdlib.settings().chatFolderStyle() == ChatFolderStyle.ICON_WITH_LABEL_ON_ACTIVE_FOLDER);
    headerCell.getTopView().setUseDarkBackground(displayTabsAtBottom);
    headerCell.getTopView().setDrawSelectionAtTop(displayTabsAtBottom);
    headerCell.getTopView().setSlideOffDirection(displayTabsAtBottom ? ViewPagerTopView.SLIDE_OFF_DIRECTION_TOP : ViewPagerTopView.SLIDE_OFF_DIRECTION_BOTTOM);
    headerCell.getTopView().setItemPadding(Screen.dp(hasFolders ? ViewPagerTopView.COMPACT_ITEM_PADDING : ViewPagerTopView.DEFAULT_ITEM_PADDING));
    headerCell.getTopView().setItemSpacing(Screen.dp(hasFolders ? ViewPagerTopView.COMPACT_ITEM_SPACING : ViewPagerTopView.DEFAULT_ITEM_SPACING));
    headerCellView.setFadingEdgeLength(displayTabsAtBottom ? 0f : 16f);
    headerCellView.setM3Divider(placement == FOLDERS_BELOW_SEARCH);

    // Where the tabs go: the bottom bar (phone), the strip below the app bar (tablet) or nowhere (rail / no folders).
    ViewGroup target;
    if (displayTabsAtBottom) {
      target = ensureBottomBar(headerCellView);
    } else if (placement == FOLDERS_BELOW_SEARCH) {
      target = ensureTopTabsBar();
    } else {
      target = null;
    }
    ViewGroup parent = (ViewGroup) headerCellView.getParent();
    if (parent != target) {
      if (parent != null) {
        parent.removeView(headerCellView);
      }
      if (target != null) {
        target.addView(headerCellView);
      }
    }
    if (displayTabsAtBottom) {
      Views.setTopMargin(headerCellView, Screen.dp(3f) - Screen.dp(1f));
      // Folder tabs at the bottom = navigation area: surfaceContainer (M3 roles, every theme).
      ViewUtils.setBackground(headerCellView, M3.roleDrawable(M3.SURFACE_CONTAINER));
      addThemeInvalidateListener(headerCellView);
      headerCell.getTopView().setSelectionColorId(ColorId.headerLightText, .9f);
      headerCell.getTopView().setTextFromToColorId(ColorId.headerLightText, ColorId.headerLightText, PropertyId.SUBTITLE_ALPHA);
    } else {
      Views.setTopMargin(headerCellView, 0);
      ViewUtils.setBackground(headerCellView, null);
      headerCell.getTopView().setSelectionColorId(ColorId.headerTabActive);
      headerCell.getTopView().setTextFromToColorId(ColorId.headerTabInactiveText, ColorId.headerTabActiveText);
    }
    if (target != null) {
      headerCellView.setAlpha(1f);
      headerCellView.setTranslationX(0f);
      headerCellView.setTranslationY(0f);
      headerCellView.setVisibility(View.VISIBLE);
      headerCellView.setScaleFactor(0f, 0f, 0f, false);
    }

    if (!displayTabsAtBottom && bottomBar != null) {
      pagerWrap.removeView(bottomBar);
      pagerWrap.removeView(bottomBarSpace);
      bottomBarSpace.performDestroy();
      bottomBar = null;
      bottomBarSpace = null;
      if (Config.CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL) {
        isBottomBarVisible.setValue(false, false);
      }
      if (navigationBar != null) {
        navigationBar.setTranslationY(0f);
      }
    }
    if (placement != FOLDERS_BELOW_SEARCH && topTabsBar != null) {
      mainWrap.removeView(topTabsBar);
      topTabsBar = null;
    }
    updateFolderRail();
  }

  private ViewGroup ensureBottomBar (ViewPagerHeaderViewCompact headerCellView) {
    if (bottomBar == null) {
      int shadowHeight = Screen.dp(3f);
      ShadowView shadowView = new ShadowView(context);
      shadowView.setVerticalShadow(new int[]{0x00000000, 0x40000000}, null, shadowHeight);
      shadowView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, shadowHeight));

      int headerHeight = getHeaderHeight();
      bottomBar = new FrameLayoutFix(context);
      bottomBar.addView(shadowView);
      pagerWrap.addView(bottomBar, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight + shadowHeight - Screen.dp(1f), Gravity.BOTTOM));

      bottomBarSpace = new FillingSpace(context);
      ViewUtils.setBackground(bottomBarSpace, M3.roleDrawable(M3.SURFACE_CONTAINER));
      addThemeInvalidateListener(bottomBarSpace);
      pagerWrap.addView(bottomBarSpace, FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));

      updateBottomBarMargins();

      if (Config.CHAT_FOLDERS_HIDE_BOTTOM_BAR_ON_SCROLL) {
        isBottomBarVisible.setValue(true, false);
        invalidateBottomBarOffset();
      }
    }
    return bottomBar;
  }

  private void checkMenu () {
    if (menu != null) {
      FrameLayoutFix.LayoutParams layoutParams = (FrameLayoutFix.LayoutParams) menu.getLayoutParams();
      boolean displayTabsAtBottom = displayTabsAtBottom();
      int gravity = (displayTabsAtBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.LEFT;
      int bottomMargin = displayTabsAtBottom ? getHeaderHeight() : 0;
      // Tablet: the folder menu opens below the tab strip under the app bar.
      int topMargin = displayTabsBelowSearch() ? getTopTabsBarHeight() : 0;
      if (layoutParams.topMargin != topMargin) {
        layoutParams.topMargin = topMargin;
        menu.setLayoutParams(layoutParams);
      }
      if (menu.setTop(!displayTabsAtBottom)) {
        menu.setDefaultPadding();
        menu.setPadding(menu.getPaddingLeft(), menu.getPaddingTop() + Screen.dp(14f), menu.getPaddingRight(), menu.getPaddingBottom() + Screen.dp(13f));
      }
      if (gravity != layoutParams.gravity || bottomMargin != layoutParams.bottomMargin) {
        layoutParams.gravity = gravity;
        layoutParams.bottomMargin = bottomMargin;
      }
    }
  }

  // Chat folders placement

  /** No chat folders: the app bar shows the title (phone) or the search bar (tablet). */
  private static final int FOLDERS_NONE = 0;
  /** Phone: tabs at the bottom of the screen. */
  private static final int FOLDERS_BOTTOM = 1;
  /** Tablet: tabs below the app bar with the search bar. */
  private static final int FOLDERS_BELOW_SEARCH = 2;
  /** Tablet: navigation rail at the leading edge of the left pane. */
  private static final int FOLDERS_SIDE = 3;

  private int getFoldersPlacement () {
    if (!hasFolders()) {
      return FOLDERS_NONE;
    }
    if (!isTabletNavigationBar()) {
      return FOLDERS_BOTTOM;
    }
    return tdlib.settings().displayTabletFoldersBelowSearch() ? FOLDERS_BELOW_SEARCH : FOLDERS_SIDE;
  }

  private boolean displayTabsAtBottom () {
    return getFoldersPlacement() == FOLDERS_BOTTOM;
  }

  private boolean displayTabsBelowSearch () {
    return getFoldersPlacement() == FOLDERS_BELOW_SEARCH;
  }

  private int appliedFoldersPlacement = -1;

  /** Moves the folder tabs when the placement changes (folders added/removed, setting, fold/unfold). */
  private void checkFoldersPlacement () {
    if (headerCell == null || pagerWrap == null) {
      return;
    }
    if (appliedFoldersPlacement != getFoldersPlacement()) {
      checkTabs();
      checkMenu();
      checkPagerMargins();
      checkHeaderMargins();
      updateChatListNavigationInset();
    }
  }

  // Tablet: folders rail

  private @Nullable M3FolderRailView folderRail;

  /** Rail shown by the tablet layout at the leading edge of the left pane, or null. */
  public @Nullable View getTabletFolderRail () {
    return getFoldersPlacement() == FOLDERS_SIDE ? folderRail : null;
  }

  private void updateFolderRail () {
    boolean needRail = getFoldersPlacement() == FOLDERS_SIDE;
    if (needRail && folderRail == null) {
      folderRail = new M3FolderRailView(context);
      folderRail.setListener(new ViewPagerTopView.OnItemClickListener() {
        @Override
        public void onPagerItemClick (int index) {
          if (isTabletNavigationBar() && getTabletSection() != MainNavigationBar.ITEM_CHATS) {
            // A dock section is in the left pane: a folder brings the chat list back.
            if (navigationController.isAnimating() || !navigationController.closeTabletDetail()) {
              return;
            }
          }
          MainController.this.onPagerItemClick(index);
        }

        @Override
        public boolean onPagerItemLongClick (int index) {
          return MainController.this.onPagerItemLongClick(index);
        }
      });
      addThemeInvalidateListener(folderRail);
    }
    if (folderRail != null) {
      if (needRail) {
        folderRail.setEntries(buildFolderRailEntries());
        folderRail.setInsets(HeaderView.getTopOffset(), extraBottomInsetWithoutIme);
        ViewPager pager = getViewPager();
        if (pager != null) {
          folderRail.setSelectionFactor(pager.getCurrentItem());
        }
      }
    }
    if (navigationController != null) {
      navigationController.updateTabletFolderRail();
    }
  }

  private List<M3FolderRailView.Entry> buildFolderRailEntries () {
    List<M3FolderRailView.Entry> entries = new ArrayList<>(pagerChatLists.size());
    for (int position = 0; position < pagerChatLists.size(); position++) {
      TdApi.ChatList chatList = pagerChatLists.get(position);
      ViewPagerTopView.Item item = position < pagerSections.size() ? pagerSections.get(position) : null;
      Counter counter = item != null ? item.counter : null;
      CharSequence label;
      int iconRes;
      // The rail always shows an icon and a label, whatever the folder style of the tabs is.
      if (TD.isChatListMain(chatList)) {
        label = Lang.getString(menuNeedArchive ? R.string.CategoryArchive : R.string.CategoryMainShort);
        iconRes = menuNeedArchive ? R.drawable.baseline_archive_24 : R.drawable.baseline_forum_24;
      } else if (TD.isChatListArchive(chatList)) {
        label = Lang.getString(R.string.CategoryArchive);
        iconRes = R.drawable.baseline_archive_24;
      } else if (TD.isChatListFolder(chatList)) {
        TdApi.ChatFolderInfo info = tdlib.chatFolderInfo(((TdApi.ChatListFolder) chatList).chatFolderId);
        label = info != null ? Emoji.instance().replaceEmoji(TD.toCharSequence(info.name)) : "";
        iconRes = TD.findFolderIcon(info != null ? info.icon : null, R.drawable.baseline_folder_24);
      } else {
        label = "";
        iconRes = R.drawable.baseline_folder_24;
      }
      entries.add(new M3FolderRailView.Entry(label, iconRes, counter));
    }
    return entries;
  }

  // Tablet: tabs below the search bar

  private @Nullable FrameLayoutFix topTabsBar;

  private int getTopTabsBarHeight () {
    return Size.getHeaderPortraitSize();
  }

  private FrameLayoutFix ensureTopTabsBar () {
    if (topTabsBar == null) {
      // Same color as the app bar above it (also when it changes with scroll).
      topTabsBar = new FrameLayoutFix(context) {
        @Override
        protected void onDraw (Canvas c) {
          c.drawColor(getHeaderColor());
        }
      };
      topTabsBar.setWillNotDraw(false);
      topTabsBar.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getTopTabsBarHeight(), Gravity.TOP));
      addThemeInvalidateListener(topTabsBar);
      // Below the search results, which cover it while searching.
      mainWrap.addView(topTabsBar, mainWrap.indexOfChild(pagerWrap) + 1);
    }
    return topTabsBar;
  }

  private void initPagerSections () {
    if (Settings.instance().chatFoldersEnabled()) {
      updatePagerSections(true);
    }
  }

  private boolean needUpdatePagerSections;

  private void updatePagerSections (boolean force) {
    if (force || isAttachedToNavigationController()) {
      updatePagerSections(tdlib.chatFolders(), tdlib.mainChatListPosition(), tdlib.settings().archiveChatListPosition());
    } else {
      needUpdatePagerSections = true;
    }
  }

  private void updatePagerSections (TdApi.ChatFolderInfo[] chatFolders, int mainChatListPosition, int archiveChatListPosition) {
    boolean oldHasFolders = hasFolders();
    List<TdApi.ChatList> chatLists;
    List<ViewPagerTopView.Item> sections;
    if (chatFolders.length > 0 || tdlib.settings().isChatListEnabled(ChatPosition.CHAT_LIST_ARCHIVE)) {
      int chatListCount = chatFolders.length;
      if (tdlib.settings().isChatListEnabled(ChatPosition.CHAT_LIST_MAIN)) {
        mainChatListPosition = MathUtils.clamp(mainChatListPosition, 0, chatListCount);
        chatListCount++;
      } else {
        mainChatListPosition = NO_POSITION;
      }
      if (tdlib.settings().isChatListEnabled(ChatPosition.CHAT_LIST_ARCHIVE)) {
        archiveChatListPosition = MathUtils.clamp(archiveChatListPosition, 0, chatListCount);
        chatListCount++;
        if (mainChatListPosition >= archiveChatListPosition) {
          mainChatListPosition++;
        }
      } else {
        archiveChatListPosition = NO_POSITION;
      }
      @ChatFolderStyle int chatFolderStyle = tdlib.settings().chatFolderStyle();
      chatLists = new ArrayList<>(chatListCount);
      sections = new ArrayList<>(chatListCount);
      int chatFolderIndex = 0;
      for (int position = 0; position < chatListCount; position++) {
        TdApi.ChatList chatList;
        ViewPagerTopView.Item sectionItem;
        if (position == mainChatListPosition) {
          chatList = ChatPosition.CHAT_LIST_MAIN;
          sectionItem = buildMainSectionItem(position, chatFolderStyle);
        } else if (position == archiveChatListPosition) {
          chatList = ChatPosition.CHAT_LIST_ARCHIVE;
          sectionItem = buildArchiveSectionItem(position, chatFolderStyle);
        } else {
          TdApi.ChatFolderInfo chatFolder = chatFolders[chatFolderIndex++];
          if (!tdlib.settings().isChatFolderEnabled(chatFolder.id))
            continue;
          chatList = new TdApi.ChatListFolder(chatFolder.id);
          sectionItem = buildSectionItem(getPagerItemId(chatList), position, chatList, chatFolder, chatFolderStyle);
        }
        chatLists.add(chatList);
        sections.add(sectionItem);
      }
    } else {
      sections = Collections.emptyList();
      chatLists = Collections.emptyList();
    }
    if (chatLists.size() > 1 || (!chatLists.isEmpty() && !TD.isChatListMain(chatLists.get(0)))) {
      this.pagerSections = sections;
      this.pagerChatLists = chatLists;
      this.chatFolderInfos = chatFolders;
      if (chatListUnreadCountListener == null) {
        chatListUnreadCountListener = new ChatListUnreadCountListener();
        tdlib.listeners().addTotalChatCounterListener(chatListUnreadCountListener);
      }
    } else {
      this.pagerSections = Collections.emptyList();
      this.pagerChatLists = Collections.emptyList();
      this.chatFolderInfos = null;
      if (chatListUnreadCountListener != null) {
        tdlib.listeners().removeTotalChatCounterListener(chatListUnreadCountListener);
        chatListUnreadCountListener = null;
      }
    }
    notifyPagerItemPositionsChanged();
    ViewPager pager = getViewPager();
    if (pager != null) {
      onPageSelected(pager.getCurrentItem());
    }
    if (oldHasFolders != hasFolders() && pagerWrap != null) {
      checkTabs();
      checkMenu();
      checkMargins();
      updateHeaderDockButtons();
      if (headerView != null && isFocused() && !inTransformMode()) {
        // The app bar switches between the title (no folders) and the folder tabs.
        headerView.setTitle(this);
      }
      showFoldersSetupSuggestion();
    }
    if (folderRail != null || getFoldersPlacement() == FOLDERS_SIDE) {
      updateFolderRail();
    }
  }

  private ViewPagerTopView.Item buildMainSectionItem (int pagerItemPosition, @ChatFolderStyle int chatFolderStyle) {
    CharSequence sectionName = getMenuSectionName(MAIN_PAGER_ITEM_ID, pagerItemPosition, /* hasFolders */ true, chatFolderStyle, /* upperCase */ false);
    int iconResource = menuNeedArchive ? R.drawable.baseline_archive_24 : R.drawable.baseline_forum_24;
    return buildSectionItem(MAIN_PAGER_ITEM_ID, pagerItemPosition, ChatPosition.CHAT_LIST_MAIN, sectionName, iconResource, chatFolderStyle);
  }

  private ViewPagerTopView.Item buildArchiveSectionItem (int pagerItemPosition, @ChatFolderStyle int chatFolderStyle) {
    CharSequence sectionName = getMenuSectionName(ARCHIVE_PAGER_ITEM_ID, pagerItemPosition, /* hasFolders */ true, chatFolderStyle, /* upperCase */ false);
    int iconResource = R.drawable.baseline_archive_24;
    return buildSectionItem(ARCHIVE_PAGER_ITEM_ID, pagerItemPosition, ChatPosition.CHAT_LIST_ARCHIVE, sectionName, iconResource, chatFolderStyle);
  }

  private ViewPagerTopView.Item buildSectionItem (long pagerItemId, int pagerItemPosition, TdApi.ChatList chatList, TdApi.ChatFolderInfo chatFolderInfo, @ChatFolderStyle int chatFolderStyle) {
    CharSequence sectionName = getFolderSectionName(pagerItemId, TD.toCharSequence(chatFolderInfo.name), chatFolderStyle);
    int iconResource = TD.findFolderIcon(chatFolderInfo.icon, R.drawable.baseline_folder_24);
    ViewPagerTopView.Item item = buildSectionItem(pagerItemId, pagerItemPosition, chatList, sectionName, iconResource, chatFolderStyle);
    if (chatFolderStyle != ChatFolderStyle.ICON_ONLY && TD.hasCustomEmoji(chatFolderInfo.name.text)) {
      // Rendered via Text, which is able to draw custom emoji; Layout-based path would show placeholder emoji only
      item.setCustomEmojiText(tdlib, chatFolderInfo.name.text, chatFolderInfo.name.animateCustomEmoji);
      if (hasIconSpans(sectionName)) {
        // Selected chat filter: Text cannot draw IconSpan, so the filter icon is drawn after the label
        item.setTrailingIcon(getFilterVariantIcon(getSelectedFilter(pagerItemId)));
      }
    }
    return item;
  }

  private static boolean hasIconSpans (CharSequence text) {
    return text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), IconSpan.class).length > 0;
  }

  private ViewPagerTopView.Item buildSectionItem (long pagerItemId, int pagerItemPosition, TdApi.ChatList chatList, CharSequence sectionName, @DrawableRes int iconResource, @ChatFolderStyle int chatFolderStyle) {
    int selectedFilter = getSelectedFilter(pagerItemId);
    Counter unreadCounter = buildUnreadCounter(pagerItemId, selectedFilter);
    if (unreadCounter != null) {
      TdlibCounter counter = tdlib.getCounter(chatList);
      updateCounter(chatList, unreadCounter, counter, /* animated */ false);
    }
    boolean showIcon = hasIcon(chatFolderStyle) || (pagerItemId == MAIN_PAGER_ITEM_ID && pagerItemPosition == 0);
    if (!showIcon) {
      iconResource = ResourcesCompat.ID_NULL;
    }
    if (TextUtils.isEmpty(sectionName)) {
      sectionName = null;
    }
    ViewPagerTopView.Item item = new ViewPagerTopView.Item(sectionName, iconResource, unreadCounter);
    item.setMinWidth(Screen.dp(56f - ViewPagerTopView.COMPACT_ITEM_PADDING * 2));
    return item;
  }

  private @Nullable Counter buildUnreadCounter (long pagerItemId, int selectedFilter) {
    if (selectedFilter != FILTER_NONE || (pagerItemId == MAIN_PAGER_ITEM_ID && menuNeedArchive)) {
      return null;
    }
    UnreadCounterColorSet unreadCounterColorSet = UnreadCounterColorSet.create(this);
    Counter unreadCounter = new Counter.Builder()
      .textSize(12f)
      .backgroundPadding(4f)
      .outlineAffectsBackgroundSize(false)
      .colorSet(unreadCounterColorSet)
      .callback(unreadCounterCallback(pagerItemId))
      .build();
    unreadCounterColorSet.setCounter(unreadCounter);
    return unreadCounter;
  }

  private Counter.Callback unreadCounterCallback (long pagerItemId) {
    return (counter, sizeChanged) -> {
      if (headerCell != null) {
        int position = getPagerItemPosition(pagerItemId);
        if (position != NO_POSITION) {
          ViewPagerTopView topView = headerCell.getTopView();
          if (sizeChanged) {
            topView.requestItemLayoutAt(position);
          }
          topView.invalidate();
        }
      }
      if (folderRail != null) {
        folderRail.invalidateItems();
      }
    };
  }

  private long updateCounter (TdApi.ChatList chatList, Counter target, TdlibCounter counter, boolean animated) {
    return updateCounter(tdlib, chatList, target, counter, animated);
  }

  /** @return the number shown by the badge (0: hidden) */
  public static long updateCounter (Tdlib tdlib, TdApi.ChatList chatList, Counter target, TdlibCounter counter, boolean animated) {
    int mutedCount, unmutedCount;
    int badgeFlags = tdlib.settings().getChatFolderBadgeFlags();
    boolean countMessages = BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_MESSAGES);
    if (countMessages) {
      mutedCount = Math.max(0, counter.messageCount);
      unmutedCount = Math.max(0, counter.messageUnmutedCount);
    } else {
      mutedCount = Math.max(0, counter.chatCount);
      unmutedCount = Math.max(0, counter.chatUnmutedCount);
    }
    if (BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_ARCHIVED) && chatList.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
      TdlibCounter archiveCounter = tdlib.getCounter(ChatPosition.CHAT_LIST_ARCHIVE);
      if (countMessages) {
        mutedCount += Math.max(0, archiveCounter.messageCount);
        unmutedCount += Math.max(0, archiveCounter.messageUnmutedCount);
      } else {
        mutedCount += Math.max(0, archiveCounter.chatCount);
        unmutedCount += Math.max(0, archiveCounter.chatUnmutedCount);
      }
    }
    if (BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_MUTED)) {
      boolean muted = mutedCount > 0 ? unmutedCount == 0 : target.isMuted();
      target.setCount(mutedCount, muted, animated);
      return mutedCount;
    } else {
      target.setCount(unmutedCount, false, animated);
      return unmutedCount;
    }
  }

  /** M3 badge of the folder tabs (every theme): error / onError; muted chats fade to a neutral outlined badge. */
  public static class UnreadCounterColorSet implements TextColorSet {
    private @Nullable Counter counter;

    private static UnreadCounterColorSet create (MainController parent) {
      return new UnreadCounterColorSet();
    }

    public void setCounter (@Nullable Counter counter) {
      this.counter = counter;
    }

    @ColorInt
    @Override
    public int defaultTextColor () {
      return counter != null ? ColorUtils.fromToArgb(M3.onError(), M3.onSurfaceVariant(), counter.getMuteFactor()) : M3.onError();
    }

    @ColorInt
    @Override
    public int backgroundColor (boolean isPressed) {
      return counter != null ? ColorUtils.alphaColor(1f - counter.getMuteFactor(), M3.error()) : M3.error();
    }

    @ColorInt
    @Override
    public int outlineColor (boolean isPressed) {
      return counter != null ? ColorUtils.fromToArgb(M3.error(), M3.onSurfaceVariant(), counter.getMuteFactor()) : Color.TRANSPARENT;
    }
  }

  private @Nullable ChatListPositionListener chatListPositionListener;
  private @Nullable ChatListUnreadCountListener chatListUnreadCountListener;

  private class ChatListPositionListener implements TdlibSettingsManager.ChatListPositionListener {
    @Override
    public void onArchiveChatListPositionChanged (Tdlib tdlib, int archiveChatListPosition) {
      if (tdlib.settings().isChatListEnabled(ChatPosition.CHAT_LIST_ARCHIVE)) {
        updatePagerSections(false);
      }
    }

    @Override
    public void onChatListStateChanged (Tdlib tdlib, TdApi.ChatList chatList, boolean isEnabled) {
      updatePagerSections(false);
    }

    @Override
    public void onBadgeFlagsChanged (Tdlib tdlib, int newFlags) {
      if (hasFolders()) {
        updatePagerCounters();
      }
    }

    @Override
    public void onChatFolderStyleChanged (Tdlib tdlib, int newStyle) {
      onFoldersAppearanceChanged();
    }

    @Override
    public void onChatFolderOptionsChanged (Tdlib tdlib, int newOptions) {
      onFoldersAppearanceChanged();
    }
  }

  @Override
  public void onBadgeSettingsChanged () {
    if (hasFolders()) {
      updatePagerCounters();
    }
  }

  private void onFoldersAppearanceChanged () {
    if (hasFolders()) {
      if (!useGlobalFilter() && globalFilter != FILTER_NONE) {
        applyGlobalFilter(FILTER_NONE);
      }
      updatePagerSections(false);
      // Tablet folders placement setting: in the split layout this screen stays visible, so apply right away.
      checkFoldersPlacement();
    }
  }

  @Override
  public void onChatFolderOptionsChanged (int newOptions) {
    onFoldersAppearanceChanged();
  }

  @Override
  public void onChatFolderStyleChanged (int newStyle) {
    onFoldersAppearanceChanged();
  }

  private class ChatListUnreadCountListener implements CounterChangeListener {
    @Override
    public void onChatCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, boolean availabilityChanged, int totalCount, int unreadCount, int unreadUnmutedCount) {
      dispatchCounterChange(chatList, counter, false);
      /*TODO update visibility?
      if (chatList instanceof TdApi.ChatListArchive && availabilityChanged) {
        runOnUiThreadOptional(() -> {
          if (menu != null) {
            menu.getChildAt(FILTER_ARCHIVE).setVisibility(tdlib.hasArchivedChats() ? View.VISIBLE : View.GONE);
          }
        });
      }*/
    }

    @Override
    public void onMessageCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter, int unreadCount, int unreadUnmutedCount) {
      dispatchCounterChange(chatList, counter, true);
    }

    private void dispatchCounterChange (TdApi.ChatList chatList, TdlibCounter counter, boolean areMessages) {
      runOnUiThreadOptional(() -> {
        int badgeFlags = tdlib.settings().getChatFolderBadgeFlags();
        if (areMessages != BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_MESSAGES)) {
          return;
        }
        dispatchCounter(chatList, counter);
        if (chatList.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR && BitwiseUtils.hasFlag(badgeFlags, Settings.BADGE_FLAG_ARCHIVED)) {
          dispatchCounter(ChatPosition.CHAT_LIST_MAIN, tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN));
        }
      });
    }

    private void dispatchCounter (TdApi.ChatList chatList, TdlibCounter counter) {
      long itemId = getPagerItemId(chatList);
      int positionToUpdate = getPagerItemPosition(itemId);
      if (positionToUpdate != NO_POSITION) {
        ViewPagerTopView.Item pagerSection = pagerSections.get(positionToUpdate);
        if (pagerSection.counter != null) {
          updateCounter(chatList, pagerSection.counter, counter, isFocused());
        }
      }
    }
  }

  private void updatePagerCounters () {
    for (int i = 0; i < pagerSections.size(); i++) {
      TdApi.ChatList chatList = pagerChatLists.get(i);
      ViewPagerTopView.Item section = pagerSections.get(i);
      if (section.counter != null) {
        TdlibCounter counter = tdlib.getCounter(chatList);
        updateCounter(chatList, section.counter, counter, isFocused());
      }
    }
  }

  @Override
  public void onChatFoldersChanged (TdApi.ChatFolderInfo[] chatFolders, int mainChatListPosition) {
    if (Settings.instance().chatFoldersEnabled()) {
      runOnUiThreadOptional(() ->
        updatePagerSections(false)
      );
    }
  }

  private @Nullable PopupLayout globalFilterLayout;

  private void showGlobalFilter () {
    if (headerView == null) return;
    MenuMoreWrap menu = new MenuMoreWrap(context, /* scrollable */ true);
    menu.init(getThemeListeners());
    View.OnClickListener onFilterClickListener = v -> {
      PopupLayout popupLayout = PopupLayout.parentOf(v);
      popupLayout.hideWindow(true);
      int selectedFilter = (int) v.getTag(R.id.btn_filter);
      applyGlobalFilter(selectedFilter);
    };
    for (int globalFilter : GLOBAL_FILTERS) {
      if (globalFilter == FILTER_UNREAD && !tdlib.hasUnreadChats(pagerChatLists)) {
        continue;
      }
      int name = getGlobalFilterName(globalFilter);
      int icon = getFilterIcon(globalFilter, true);
      TextView itemView = menu.addItem(View.NO_ID, Lang.getString(name), icon, null, onFilterClickListener);
      itemView.setTag(R.id.btn_filter, globalFilter);
    }
    menu.setAnchorMode(MenuMoreWrap.ANCHOR_MODE_HEADER);
    menu.setTranslationY(headerView.getTranslationY() + headerView.getCurrentHeaderOffset());
    globalFilterLayout = new PopupLayout(context);
    globalFilterLayout.init(true);
    globalFilterLayout.setNeedRootInsets();
    globalFilterLayout.setOverlayStatusBar(true);
    globalFilterLayout.setDismissListener((popup) -> globalFilterLayout = null);
    globalFilterLayout.showMoreView(menu);
  }

  private void hideGlobalFilter () {
    if (globalFilterLayout != null) {
      globalFilterLayout.hideWindow(/* animated */ isFocused());
      globalFilterLayout = null;
    }
  }

  private boolean applyGlobalFilter (@Filter int filterToApply) {
    if ((!useGlobalFilter() && filterToApply != FILTER_NONE) || (globalFilter == filterToApply && !menuNeedArchive && !hasSelectedFilters())) {
      return false;
    }
    globalFilter = filterToApply;
    pagerChatListFilters.clear();
    cancelPendingFilter();
    setNeedArchive(false);
    if (toggleHeaderView != null) {
      String title = Lang.getString(getGlobalFilterName(filterToApply));
      toggleHeaderView.setTitle(title, /* animated */ isFocused());
    }
    int itemCount = getPagerItemCount();
    List<ViewPagerTopView.Item> sections = new ArrayList<>(itemCount);
    int currentItemPosition = getCurrentPagerItemPosition();
    for (int itemPosition = 0; itemPosition < itemCount; itemPosition++) {
      // replace item
      long itemId = getPagerItemId(itemPosition);
      sections.add(buildSectionItem(itemId, itemPosition));
      ViewController<?> cachedController = getCachedControllerForPosition(itemPosition);
      TdApi.ChatList chatList = pagerChatLists.get(itemPosition);
      if (sameChatsController(cachedController, chatList, filterToApply)) {
        continue;
      }
      if (currentItemPosition == itemPosition) {
        postApplyFilter(itemId, chatList, filterToApply);
        continue;
      }
      // replace controller (if cached)
      if (cachedController != null) {
        ViewController<?> controller = newChatsController(chatList, filterToApply);
        modifyNewPagerItemController(controller);
        replaceController(itemId, controller, /* notifyAdapter */ false);
      }
    }
    notifyPagerItemsChanged();
    pagerSections = sections;
    if (headerCell != null) {
      headerCell.getTopView().setItems(sections);
    } else {
      needUpdatePagerSections = true;
    }
    return true;
  }

  private boolean hasIcon (@ChatFolderStyle int chatFolderStyle) {
    switch (chatFolderStyle) {
      case ChatFolderStyle.ICON_ONLY:
      case ChatFolderStyle.LABEL_AND_ICON:
      case ChatFolderStyle.ICON_WITH_LABEL_ON_ACTIVE_FOLDER:
        return true;
      case ChatFolderStyle.LABEL_ONLY:
        return false;
      default:
        throw new IllegalArgumentException("chatFolderStyle = " + chatFolderStyle);
    }
  }

  private boolean sameChatsController (@Nullable ViewController<?> controller, TdApi.ChatList chatList, @Filter int filter) {
      if (!(controller instanceof ChatsController)) {
          return false;
      }
      ChatsController c = (ChatsController) controller;
      return Td.equalsTo(c.chatList(), chatList) && ObjectUtils.equals(filter, c.getArgumentsStrict().tag);
  }
}
