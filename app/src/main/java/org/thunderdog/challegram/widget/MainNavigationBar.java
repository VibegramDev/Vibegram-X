/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Main sections of the app: chats, calls, contacts and settings (shown as the user's avatar).
 * Phone: floating vertical rail with icons only, search is its first item.
 * Tablet: bar attached to the bottom of the chat list (search is the search bar in the app bar there).
 * Phone, navigation style "bar": M3 flexible navigation bar (full width at the bottom, icons with labels).
 */
public class MainNavigationBar extends FrameLayoutFix implements Destroyable {
  // Item ids are also indexes in the items array.
  public static final int ITEM_CHATS = 0;
  public static final int ITEM_CALLS = 1;
  public static final int ITEM_CONTACTS = 2;
  public static final int ITEM_SETTINGS = 3;
  public static final int ITEM_SEARCH = 4;
  /** Phone, collapsed rail: scrolls the chat list to the top. */
  public static final int ITEM_SCROLL_TOP = 5;

  public interface Callback {
    void onNavigationItemClick (int item);
  }

  public interface CollapseListener {
    /** 0: full rail, 1: collapsed to the scroll to top button (the sections are in the app bar). */
    void onNavigationCollapseFactorChanged (float factor);
  }

  private static final float RAIL_ITEM_SIZE = 56f;
  private static final float RAIL_PADDING = 6f;
  /** Tablet bar: icons only, full corner radius (pill). Also the height of the floating message composer. */
  private static final float BAR_HEIGHT = 56f;
  /** Center of the icon (and the active indicator) from the top of the tablet bar: vertically centered. */
  private static final float BAR_ICON_CENTER_Y = BAR_HEIGHT / 2f;
  private static final float ICON_SIZE = 24f;
  private static final float AVATAR_SIZE = 28f;
  /** Active indicator (and pressed state layer) size. */
  private static final float INDICATOR_HEIGHT = 32f;
  private static final float BAR_INDICATOR_WIDTH = 60f;
  private static final float RAIL_INDICATOR_WIDTH = 48f;

  private final ViewController<?> themeProvider;
  private final ItemView[] items;
  private final SimpleItemView searchView, scrollTopView;
  private final AvatarView avatarView;
  private final Counter unreadCounter;
  private @Nullable Callback callback;
  private @Nullable View.OnLongClickListener settingsLongClickListener;
  private @Nullable CollapseListener collapseListener;
  /** Phone: the rail collapses into the scroll to top button while the chat list is scrolled. */
  private final FactorAnimator collapseAnimator;
  private boolean isCollapsed;
  private float collapseFactor;

  private boolean isBar;
  /**
   * Phone, navigation style "dock": horizontal floating toolbar at the bottom with the sections, the search FAB next to
   * it and a small scroll to top FAB above the search FAB (shown while the chat list is scrolled).
   */
  private boolean isDock;
  private final View dockBackground;
  private int selectedItem = ITEM_CHATS;

  /**
   * Phone, navigation style "bar": M3 flexible navigation bar with vertical items (m3.material.io/components/navigation-bar).
   * "The container should always be placed at the bottom of the product and span the full length of the window",
   * "All navigation items require a label text". Search is the search button in the app bar.
   * Sizes are the flexible navigation bar tokens: 64dp container, 56x32dp active indicator, 24dp icon,
   * 6dp from the top to the indicator, 4dp between the indicator and the label (label medium, 16dp line).
   */
  private boolean isNavBar;
  /** Phone dock style: the floating bar is only as wide as the former dock toolbar, centered. */
  private boolean isCompactBar;
  private static final float NAV_BAR_HEIGHT = 64f;
  private static final float NAV_BAR_INDICATOR_WIDTH = 56f;
  private static final float NAV_BAR_INDICATOR_TOP = 6f;
  private static final float NAV_BAR_LABEL_GAP = 4f;
  private static final float NAV_BAR_LABEL_LINE_HEIGHT = 16f;
  private @Nullable TextPaint labelPaint;

  /** Floating toolbar: compact pill centered at the bottom, only as wide as its items. */
  private static final float DOCK_HEIGHT = 60f;
  private static final float DOCK_ITEM_WIDTH = 72f;
  private static final float DOCK_PADDING = 6f;
  /** Search FAB: outside the toolbar, above its bottom-end corner, the main action of the screen. */
  private static final float DOCK_FAB_SIZE = 56f;
  private static final float DOCK_FAB_RADIUS = M3.CORNER_LARGE;
  /** Space between the toolbar and the search FAB row. */
  private static final float DOCK_FAB_GAP = 12f;
  /** Small FAB (scroll to top) above the search FAB. */
  private static final float DOCK_SMALL_FAB_SIZE = 40f;
  private static final float DOCK_SMALL_FAB_RADIUS = M3.CORNER_MEDIUM;
  private static final float DOCK_SMALL_FAB_GAP = 12f;

  /** 0: search icon, 1: search is open, the segment shows a close icon. */
  private float searchOpenFactor;
  /** Number shown by the badge of the chats item, read by screen readers after the label. */
  private long unreadCount;

  public MainNavigationBar (Context context, ViewController<?> themeProvider) {
    super(context);
    this.themeProvider = themeProvider;
    setWillNotDraw(false);

    // M3 large badge: error / onError ("Keep the default color mapping"). Muted chats: neutral, still readable.
    unreadCounter = new Counter.Builder()
      .textSize(11f)
      .colorSet(new TextColorSet() {
        @Override
        public int defaultTextColor () {
          return ColorUtils.fromToArgb(M3.onError(), M3.surface(), badgeMuteFactor());
        }

        @Override
        public int backgroundColor (boolean isPressed) {
          return ColorUtils.fromToArgb(M3.error(), M3.onSurfaceVariant(), badgeMuteFactor());
        }

        @Override
        public int outlineColor (boolean isPressed) {
          return containerColor();
        }
      })
      .callback((counter, sizeChanged) -> invalidateItems())
      .build();

    dockBackground = new View(context) {
      @Override
      protected void onDraw (@NonNull Canvas c) {
        RectF rect = Paints.getRectF();
        rect.set(0, 0, getWidth(), getHeight());
        float radius = getHeight() / 2f;
        c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(containerColor()));
        // Thin outline in every theme (as exteragram's dock): the toolbar floats over washed out content.
        c.drawRoundRect(rect, radius, radius, Paints.strokeSmallPaint(needOutlineStroke() ? Theme.separatorColor() : M3.outlineVariant()));
      }
    };
    dockBackground.setWillNotDraw(false);
    dockBackground.setVisibility(View.GONE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      dockBackground.setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
        }
      });
    }
    addView(dockBackground);
    themeProvider.addThemeInvalidateListener(dockBackground);

    avatarView = new AvatarView(context);
    avatarView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(AVATAR_SIZE), Screen.dp(AVATAR_SIZE), Gravity.CENTER_HORIZONTAL | Gravity.TOP));

    items = new ItemView[] {
      // M3: outlined icon for unselected destinations, filled for the selected one.
      new ItemView(context, ITEM_CHATS, R.drawable.outline_forum_24, R.drawable.baseline_forum_24, R.string.Chats),
      new ItemView(context, ITEM_CALLS, R.drawable.outline_phone_24, R.drawable.baseline_phone_24, R.string.Calls),
      new ItemView(context, ITEM_CONTACTS, R.drawable.outline_perm_contact_calendar_24, R.drawable.baseline_perm_contact_calendar_24, R.string.Contacts),
      new ItemView(context, ITEM_SETTINGS, 0, 0, R.string.Settings)
    };
    items[ITEM_SETTINGS].addView(avatarView);
    searchView = new SimpleItemView(context, R.drawable.baseline_search_24, R.drawable.baseline_close_24, R.string.Search);
    searchView.setOnClickListener(v -> {
      if (callback != null) {
        callback.onNavigationItemClick(ITEM_SEARCH);
      }
    });
    setPressedStateLayer(searchView, new IndicatorMask(false));
    addView(searchView);
    themeProvider.addThemeInvalidateListener(searchView);
    scrollTopView = new SimpleItemView(context, R.drawable.baseline_arrow_upward_24, 0, R.string.ScrollToTop);
    scrollTopView.setOnClickListener(v -> {
      if (callback != null && collapseFactor > .5f) {
        callback.onNavigationItemClick(ITEM_SCROLL_TOP);
      }
    });
    setPressedStateLayer(scrollTopView, new IndicatorMask(false));
    scrollTopView.setVisibility(View.INVISIBLE);
    themeProvider.addThemeInvalidateListener(scrollTopView);
    for (ItemView item : items) {
      item.setOnClickListener(v -> {
        if (callback != null) {
          callback.onNavigationItemClick(((ItemView) v).id);
        }
      });
      addView(item);
      themeProvider.addThemeInvalidateListener(item);
    }
    // Above the settings item: they swap places when the rail collapses.
    addView(scrollTopView);
    themeProvider.addThemeInvalidateListener(this);

    // Phone rail and tablet bar have no labels (accepted deviation): plain tooltip with the label on tap and hold,
    // 4dp from the dock edge: before the rail, above the bar.
    M3Tooltip.BoundsProvider railBounds = (anchor, outRect) -> {
      if (isDock) {
        // Every element of the dock is its own visual boundary (toolbar items: the toolbar height).
        outRect.set(0, 0, anchor.getWidth(), anchor.getHeight());
      } else if (isBar) {
        // Horizontal bounds of the item, vertical bounds of the bar container (the visual boundary).
        outRect.set(0, -anchor.getTop(), anchor.getWidth(), getHeight() - anchor.getTop());
      } else {
        // Horizontal bounds of the rail container (the visual boundary), vertical bounds of the item.
        outRect.set(-anchor.getLeft(), 0, getWidth() - anchor.getLeft(), anchor.getHeight());
      }
    };
    M3Tooltip.PlacementProvider tooltipPlacement = anchor -> isHorizontal() ? M3Tooltip.PLACEMENT_ABOVE : M3Tooltip.PLACEMENT_START;
    M3Tooltip.TextProvider tooltipText = anchor -> anchor == searchView && searchOpenFactor >= .5f ? Lang.getString(R.string.vg_CloseSearch) : anchor instanceof ItemView ? ((ItemView) anchor).label : anchor.getContentDescription();
    M3Tooltip.bind(searchView, M3Tooltip.PLACEMENT_START, railBounds, tooltipText);
    M3Tooltip.bind(scrollTopView, M3Tooltip.PLACEMENT_START, railBounds, tooltipText);
    for (ItemView item : items) {
      M3Tooltip.bind(item, tooltipPlacement, railBounds, tooltipText);
    }
    // Settings (avatar), tap and hold: account switcher in both layouts, instead of the tooltip.
    items[ITEM_SETTINGS].setOnLongClickListener(v -> {
      M3Tooltip.hideCurrent(true);
      return settingsLongClickListener != null && settingsLongClickListener.onLongClick(v);
    });
    // Screen readers: the sections are tabs of a single-selection collection (as MDC NavigationBarMenuView).
    ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
      @Override
      public void onInitializeAccessibilityNodeInfo (@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        int rows = isHorizontal() ? 1 : items.length, columns = isHorizontal() ? items.length : 1;
        info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(rows, columns, false, AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE));
      }
    });
    // Container height: spatial spring; contents fade with derived factors.
    collapseAnimator = new FactorAnimator(0, (id, factor, fraction, callee) -> setCollapseFactor(factor), M3.DEFAULT_SPATIAL, M3.DEFAULT_SPATIAL.duration, 0f);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          if (isDock) {
            outline.setEmpty();
          } else if (isNavBar) {
            outline.setRect(0, 0, view.getWidth(), view.getHeight());
          } else if (isBar) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), getBarRadius());
          } else {
            outline.setRoundRect(0, getRailTop(), view.getWidth(), view.getHeight(), getRailRadius());
          }
        }
      });
    }
    setLayoutMode(false, 0, 0);
  }

  public void setCallback (@Nullable Callback callback) {
    this.callback = callback;
  }

  public void setSettingsLongClickListener (@Nullable View.OnLongClickListener listener) {
    this.settingsLongClickListener = listener;
  }

  public void setCollapseListener (@Nullable CollapseListener listener) {
    this.collapseListener = listener;
  }

  // Collapse (phone)

  /**
   * Phone: collapses the rail into the scroll to top button (the owner moves the sections into the app bar).
   * Ignored in the tablet bar mode.
   */
  public void setCollapsed (boolean collapsed, boolean animated) {
    collapsed = collapsed && !isBar && !isNavBar;
    if (this.isCollapsed == collapsed) {
      return;
    }
    this.isCollapsed = collapsed;
    float to = collapsed ? 1f : 0f;
    if (animated && ViewCompat.isLaidOut(this) && getVisibility() == View.VISIBLE) {
      collapseAnimator.animateTo(to);
    } else {
      collapseAnimator.forceFactor(to);
      setCollapseFactor(to);
    }
  }

  public boolean isCollapsed () {
    return isCollapsed;
  }

  public float getCollapseFactor () {
    return MathUtils.clamp(collapseFactor);
  }

  private void setCollapseFactor (float factor) {
    if (this.collapseFactor != factor) {
      this.collapseFactor = factor;
      updateCollapsedViews();
      invalidate();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        invalidateOutline();
      }
      if (collapseListener != null) {
        collapseListener.onNavigationCollapseFactorChanged(MathUtils.clamp(factor));
      }
    }
  }

  private static int getCollapsedRailHeight () {
    return Screen.dp(RAIL_ITEM_SIZE) + Screen.dp(RAIL_PADDING) * 2;
  }

  /** Top of the visible rail: it shrinks towards the bottom while collapsing. */
  private int getRailTop () {
    if (isBar || isDock || isNavBar) {
      return 0;
    }
    int height = getHeight() > 0 ? getHeight() : getRailHeight();
    return Math.round((height - getCollapsedRailHeight()) * MathUtils.clamp(collapseFactor));
  }

  private void updateCollapsedViews () {
    if (isNavBar) {
      // Navigation bar: only the sections, it never collapses (search is in the app bar, scroll to top: tap Chats again).
      applyCollapse(searchView, 0f, 0, true);
      applyCollapse(scrollTopView, 0f, 0, true);
      for (ItemView item : items) {
        applyCollapse(item, 1f, 0, false);
      }
      return;
    }
    if (isDock) {
      // Dock: the sections and search stay, only the small scroll to top FAB comes up above the search FAB.
      float factor = MathUtils.clamp(collapseFactor);
      applyCollapse(searchView, 1f, 0, false);
      for (ItemView item : items) {
        applyCollapse(item, 1f, 0, false);
      }
      scrollTopView.setAlpha(factor);
      scrollTopView.setScaleX(.6f + .4f * factor);
      scrollTopView.setScaleY(.6f + .4f * factor);
      scrollTopView.setTranslationY(Screen.dp(DOCK_SMALL_FAB_GAP) * (1f - factor));
      scrollTopView.setVisibility(factor > 0f ? View.VISIBLE : View.INVISIBLE);
      return;
    }
    scrollTopView.setTranslationY(0f);
    float factor = isBar ? 0f : MathUtils.clamp(collapseFactor);
    // Sections leave first (upwards, towards the app bar), the settings item turns into the scroll to top button.
    float leave = MathUtils.clamp(factor / .6f);
    float swap = MathUtils.clamp((factor - .3f) / .7f);
    float lift = -Screen.dp(12f) * leave;
    applyCollapse(searchView, 1f - leave, lift, isBar);
    for (ItemView item : items) {
      if (item.id == ITEM_SETTINGS) {
        applyCollapse(item, 1f - swap, 0, false);
      } else {
        applyCollapse(item, 1f - leave, isBar ? 0 : lift, false);
      }
    }
    scrollTopView.setAlpha(swap);
    scrollTopView.setScaleX(.6f + .4f * swap);
    scrollTopView.setScaleY(.6f + .4f * swap);
    scrollTopView.setVisibility(swap > 0f && !isBar ? View.VISIBLE : View.INVISIBLE);
  }

  private static void applyCollapse (View view, float alpha, float translationY, boolean gone) {
    view.setAlpha(alpha);
    view.setTranslationY(translationY);
    // Invisible items must not take touches (the list below gets them).
    view.setVisibility(gone ? View.GONE : alpha > 0f ? View.VISIBLE : View.INVISIBLE);
  }

  public Counter getUnreadCounter () {
    return unreadCounter;
  }

  /**
   * Badge for screen readers: M3 "Badges with numbers should announce the count", after the label
   * ("Chats, 5 unread"). The real number, not the shortened one drawn by the badge.
   */
  public void setUnreadCount (long count) {
    count = Math.max(0, count);
    if (this.unreadCount != count) {
      this.unreadCount = count;
      items[ITEM_CHATS].updateDescription();
    }
  }

  public void setUser (Tdlib tdlib, long userId) {
    avatarView.setUser(tdlib, userId, false);
  }

  public void setSelectedItem (int item) {
    if (this.selectedItem != item) {
      this.selectedItem = item;
      boolean animated = ViewCompat.isLaidOut(this) && getVisibility() == View.VISIBLE;
      for (ItemView view : items) {
        view.setItemSelected(view.id == item, animated);
      }
    }
  }

  private float badgeMuteFactor () {
    return unreadCounter != null ? unreadCounter.getMuteFactor() : 0f;
  }

  /** Tablet bar and phone dock: surfaceContainer (M3 navigation area role). */
  private int containerColor () {
    return M3.surfaceContainer();
  }

  /**
   * M3 pressed state layer: ripple clipped to the shape of the element (the active indicator for
   * destinations, the container for the search button), drawn above it so it stays visible.
   */
  private void setPressedStateLayer (View view, Drawable mask) {
    Drawable ripple;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      ripple = new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE_COLOR), null, mask);
    } else {
      ripple = Theme.transparentRoundSelector(Screen.dp(INDICATOR_HEIGHT / 2f));
    }
    view.setBackground(null);
    ForegroundCompat.set(view, ripple);
  }


  public boolean isBar () {
    return isBar;
  }

  public boolean isDock () {
    return isDock;
  }

  public boolean isNavBar () {
    return isNavBar;
  }

  /** Navigation bar: height of the container above the system navigation bar. */
  public static int getNavBarHeight () {
    return Screen.dp(NAV_BAR_HEIGHT);
  }

  /** Sections are laid out in a row (tablet bar, phone dock and navigation bar). */
  private boolean isHorizontal () {
    return isBar || isDock || isNavBar;
  }

  /** Dock: full height of the dock area (small FAB, search FAB, toolbar and the gaps), without the margin. */
  public static int getDockAreaHeight () {
    return Screen.dp(DOCK_HEIGHT + DOCK_FAB_GAP + DOCK_FAB_SIZE + DOCK_SMALL_FAB_GAP + DOCK_SMALL_FAB_SIZE);
  }

  /** Dock: height taken by the toolbar and the row of the search FAB (the folder chips row). */
  public static int getDockContentHeight () {
    return Screen.dp(DOCK_HEIGHT + DOCK_FAB_GAP + DOCK_FAB_SIZE);
  }

  public static int getDockHeight () {
    return Screen.dp(DOCK_HEIGHT);
  }

  /** Dock: width of the column with the search FAB, including the gap to the folder chips. */
  public static int getDockFabColumnWidth () {
    return Screen.dp(DOCK_FAB_SIZE + DOCK_FAB_GAP);
  }

  /** Dock: distance from the bottom of the dock area to the center of the search FAB (and the folder chips row). */
  public static int getDockFabRowCenterY () {
    return Screen.dp(DOCK_HEIGHT + DOCK_FAB_GAP + DOCK_FAB_SIZE / 2f);
  }

  public static int getBarHeight () {
    return Screen.dp(BAR_HEIGHT);
  }

  /**
   * Tablet bar floats above the chat list (M3 Expressive floating toolbar look): full corner radius,
   * small margins at the sides and the bottom (our choice: 12dp).
   */
  public static int getBarMargin () {
    return Screen.dp(12f);
  }

  /** Full corner radius: half of the bar height (pill), concentric with the vertically centered indicator. */
  public static float getBarRadius () {
    return Screen.dp(BAR_ICON_CENTER_Y);
  }

  /** Space taken at the bottom of the chat list pane by the floating tablet bar (without the system inset). */
  public static int getBarTotalHeight () {
    return getBarHeight() + getBarMargin();
  }

  public static int getRailWidth () {
    return Screen.dp(RAIL_ITEM_SIZE + RAIL_PADDING * 2);
  }

  /** Height of the rail itself. */
  private int getRailHeight () {
    // Search item + sections.
    return Screen.dp(RAIL_ITEM_SIZE) * (items.length + 1) + Screen.dp(RAIL_PADDING) * 2;
  }

  /** Full height of the floating rail. */
  public int getRailTotalHeight () {
    return getRailHeight();
  }

  // Search button

  /** Search transform factor of the chat list: the search icon turns into a close icon while search is open. */
  public void setSearchOpenFactor (float factor) {
    factor = MathUtils.clamp(factor);
    if (this.searchOpenFactor != factor) {
      boolean wasOpen = this.searchOpenFactor >= .5f;
      this.searchOpenFactor = factor;
      searchView.invalidate();
      if (wasOpen != factor >= .5f) {
        searchView.setContentDescription(Lang.getString(factor >= .5f ? R.string.vg_CloseSearch : R.string.Search));
      }
    }
  }

  /** Distance from the edge of the parent to the rail. */
  public static int getRailMargin () {
    return Screen.dp(16f);
  }

  private static float getRailRadius () {
    return Screen.dp(RAIL_ITEM_SIZE / 2f + RAIL_PADDING);
  }

  /**
   * @param isBar true for the bottom bar of the tablet chat list, false for the floating rail
   * @param bottomInset system navigation bar inset
   * @param railBottomOffset extra space below the rail (chat folders displayed at the bottom)
   */
  /** Phone dock style: the floating bar keeps the width of the former dock toolbar (applied by setLayoutMode). */
  public void setCompactBar (boolean isCompactBar) {
    this.isCompactBar = isCompactBar;
  }

  /** Width of the compact floating bar: as the former dock toolbar (72dp per item and 6dp padding at the ends). */
  private int getCompactBarWidth () {
    return Screen.dp(DOCK_ITEM_WIDTH) * items.length + Screen.dp(DOCK_PADDING) * 2;
  }

  public void setLayoutMode (boolean isBar, int bottomInset, int railBottomOffset) {
    setLayoutMode(isBar, false, false, bottomInset, railBottomOffset);
  }

  public void setLayoutMode (boolean isBar, boolean isDock, int bottomInset, int railBottomOffset) {
    setLayoutMode(isBar, isDock, false, bottomInset, railBottomOffset);
  }

  /**
   * @param isDock phone only: navigation style "dock" (ignored with isBar)
   * @param isNavBar phone only: navigation style "bar" (ignored with isBar)
   */
  public void setLayoutMode (boolean isBar, boolean isDock, boolean isNavBar, int bottomInset, int railBottomOffset) {
    isNavBar = isNavBar && !isBar;
    isDock = isDock && !isBar && !isNavBar;
    boolean modeChanged = this.isBar != isBar || this.isDock != isDock || this.isNavBar != isNavBar || getLayoutParams() == null || (isBar && isCompactBar != (getLayoutParams().width != ViewGroup.LayoutParams.MATCH_PARENT));
    this.isBar = isBar;
    this.isDock = isDock;
    this.isNavBar = isNavBar;
    int itemSize = Screen.dp(RAIL_ITEM_SIZE);
    int padding = Screen.dp(RAIL_PADDING);
    FrameLayoutFix.LayoutParams params;
    dockBackground.setVisibility(isDock ? View.VISIBLE : View.GONE);
    // Dock: shadows of the toolbar and the FABs go beyond the bounds of their elements.
    setClipChildren(!isDock);
    if (isNavBar) {
      // Edge to edge: the container goes under the system navigation bar, the items stay above it.
      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getNavBarHeight() + bottomInset, Gravity.BOTTOM);
      setPadding(0, 0, 0, bottomInset);
    } else if (isDock) {
      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getDockAreaHeight(), Gravity.BOTTOM);
      params.leftMargin = params.rightMargin = getRailMargin();
      params.bottomMargin = getRailMargin() + bottomInset;
      setPadding(0, 0, 0, 0);
    } else if (isBar) {
      if (isCompactBar) {
        params = FrameLayoutFix.newParams(getCompactBarWidth(), getBarHeight(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      } else {
        params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getBarHeight(), Gravity.BOTTOM);
      }
      params.leftMargin = params.rightMargin = getBarMargin();
      params.bottomMargin = getBarMargin() + bottomInset;
      // Keeps the outer indicators away from the rounded ends.
      setPadding(Screen.dp(8f), 0, Screen.dp(8f), 0);
    } else {
      params = FrameLayoutFix.newParams(getRailWidth(), getRailHeight(), Gravity.BOTTOM | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT));
      params.leftMargin = params.rightMargin = getRailMargin();
      params.bottomMargin = getRailMargin() + bottomInset + railBottomOffset;
      setPadding(padding, padding, padding, padding);
    }
    setLayoutParams(params);
    if (isDock) {
      // Search FAB above the toolbar at the end edge, the small scroll to top FAB centered above it.
      int endGravity = Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT;
      int fabSize = Screen.dp(DOCK_FAB_SIZE);
      FrameLayoutFix.LayoutParams searchParams = FrameLayoutFix.newParams(fabSize, fabSize, Gravity.BOTTOM | endGravity);
      searchParams.bottomMargin = Screen.dp(DOCK_HEIGHT + DOCK_FAB_GAP);
      searchView.setLayoutParams(searchParams);
      int smallFabSize = Screen.dp(DOCK_SMALL_FAB_SIZE);
      FrameLayoutFix.LayoutParams scrollTopParams = FrameLayoutFix.newParams(smallFabSize, smallFabSize, Gravity.TOP | endGravity);
      scrollTopParams.leftMargin = scrollTopParams.rightMargin = (fabSize - smallFabSize) / 2;
      scrollTopView.setLayoutParams(scrollTopParams);
      dockBackground.setLayoutParams(FrameLayoutFix.newParams(0, getDockHeight(), Gravity.BOTTOM | Gravity.LEFT));
    } else {
      // Phone: search is the first item of the rail, the scroll to top button takes the place of the last one.
      searchView.setLayoutParams(FrameLayoutFix.newParams(itemSize, itemSize, Gravity.TOP | Gravity.LEFT));
      FrameLayoutFix.LayoutParams scrollTopParams = FrameLayoutFix.newParams(itemSize, itemSize, Gravity.TOP | Gravity.LEFT);
      scrollTopParams.topMargin = itemSize * items.length;
      scrollTopView.setLayoutParams(scrollTopParams);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Dock: every element floats on its own (M3 floating toolbar level 3, FABs level 3).
      float dockElevation = isDock ? Screen.dp(M3.ELEVATION_LEVEL3) : 0;
      dockBackground.setElevation(dockElevation);
      searchView.setElevation(isDock ? Screen.dp(M3.ELEVATION_LEVEL3) : 0);
      scrollTopView.setElevation(isDock ? Screen.dp(M3.ELEVATION_LEVEL3) : 0);
      searchView.setOutlineProvider(isDock ? new FabOutline(DOCK_FAB_RADIUS) : ViewOutlineProvider.BACKGROUND);
      scrollTopView.setOutlineProvider(isDock ? new FabOutline(DOCK_SMALL_FAB_RADIUS) : ViewOutlineProvider.BACKGROUND);
      for (ItemView item : items) {
        // Above the toolbar background (children are drawn in Z order), without own shadows (no outline).
        item.setTranslationZ(dockElevation);
      }
    }
    setPressedStateLayer(searchView, isDock ? new FabMask(DOCK_FAB_RADIUS) : new IndicatorMask(false));
    setPressedStateLayer(scrollTopView, isDock ? new FabMask(DOCK_SMALL_FAB_RADIUS) : new IndicatorMask(false));
    // Tooltips on every section item (no labels in both modes); search and scroll to top exist in the rail only.
    searchView.setLongClickable(!isBar);
    scrollTopView.setLongClickable(!isBar);
    for (ItemView item : items) {
      // Navigation bar: labels are visible, no tooltips (settings keeps the account switcher).
      item.setLongClickable(!isNavBar || item.id == ITEM_SETTINGS);
    }
    if (!isBar) {
      M3Tooltip.hideCurrent(false);
    }
    if (isBar && isCollapsed) {
      // Tablet bar never collapses.
      isCollapsed = false;
      collapseAnimator.forceFactor(0f);
      setCollapseFactor(0f);
    }
    for (int i = 0; i < items.length; i++) {
      FrameLayoutFix.LayoutParams itemParams;
      if (isNavBar) {
        itemParams = FrameLayoutFix.newParams(0, getNavBarHeight(), Gravity.TOP | Gravity.LEFT);
      } else if (isDock) {
        itemParams = FrameLayoutFix.newParams(0, getDockHeight(), Gravity.BOTTOM | Gravity.LEFT);
      } else if (isBar) {
        itemParams = FrameLayoutFix.newParams(0, getBarHeight(), Gravity.TOP | Gravity.LEFT);
      } else {
        itemParams = FrameLayoutFix.newParams(itemSize, itemSize, Gravity.TOP | Gravity.LEFT);
        itemParams.topMargin = itemSize * (i + 1);
      }
      items[i].setLayoutParams(itemParams);
      if (items[i].getForeground() == null) {
        setPressedStateLayer(items[i], new IndicatorMask());
      }
      items[i].invalidateMask();
    }
    // Tablet: search is the search bar in the app bar, not a bar item.
    updateCollapsedViews();
    Views.setTopMargin(avatarView, getIconCenterY() - Screen.dp(AVATAR_SIZE) / 2);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Tablet bar: soft shadow drawn by M3BottomScrimView (same as the floating message composer).
      // Dock: the container is transparent, its elements have their own shadows.
      // Navigation bar: "M3: Taller and no drop shadow", the container color separates it from the content.
      setElevation(isBar || isDock || isNavBar ? 0 : Screen.dp(4f));
      setClipToOutline(!isDock);
      invalidateOutline();
    }
    if (modeChanged) {
      requestLayout();
    }
    invalidateItems();
  }

  private int getIconCenterY () {
    if (isNavBar) {
      return Screen.dp(NAV_BAR_INDICATOR_TOP + INDICATOR_HEIGHT / 2f);
    }
    return isDock ? Screen.dp(DOCK_HEIGHT) / 2 : isBar ? Screen.dp(BAR_ICON_CENTER_Y) : Screen.dp(RAIL_ITEM_SIZE) / 2;
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    if (isDock) {
      // Toolbar: a pill centered horizontally, as wide as its items (narrower screens: as wide as possible).
      int width = MeasureSpec.getSize(widthMeasureSpec);
      int padding = Screen.dp(DOCK_PADDING);
      int toolbarWidth = Math.min(width, Screen.dp(DOCK_ITEM_WIDTH) * items.length + padding * 2);
      int toolbarLeft = (width - toolbarWidth) / 2;
      FrameLayoutFix.LayoutParams backgroundParams = (FrameLayoutFix.LayoutParams) dockBackground.getLayoutParams();
      if (backgroundParams.width != toolbarWidth || backgroundParams.leftMargin != toolbarLeft) {
        backgroundParams.width = toolbarWidth;
        backgroundParams.leftMargin = toolbarLeft;
      }
      int contentWidth = toolbarWidth - padding * 2;
      int itemWidth = contentWidth / items.length;
      for (int i = 0; i < items.length; i++) {
        FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) items[i].getLayoutParams();
        int left = toolbarLeft + padding + itemWidth * (Lang.rtl() ? items.length - 1 - i : i);
        if (params.width != itemWidth || params.leftMargin != left) {
          params.width = itemWidth;
          params.leftMargin = left;
        }
      }
    } else if (isBar || isNavBar) {
      // Navigation bar: "Vertical navigation items dynamically change width to equally fit the container".
      int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
      int itemWidth = width / items.length;
      for (int i = 0; i < items.length; i++) {
        FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) items[i].getLayoutParams();
        int left = itemWidth * (Lang.rtl() ? items.length - 1 - i : i);
        int right = i == items.length - 1 && !Lang.rtl() ? width : left + itemWidth;
        if (params.width != right - left || params.leftMargin != left) {
          params.width = right - left;
          params.leftMargin = left;
        }
      }
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  /** Label medium: 12sp, medium weight, 0.5sp tracking. */
  private TextPaint getLabelPaint () {
    if (labelPaint == null) {
      labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      labelPaint.setTypeface(Fonts.getRobotoMedium());
      labelPaint.setTextSize(Screen.sp(12f));
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        labelPaint.setLetterSpacing(.5f / 12f);
      }
    }
    return labelPaint;
  }

  private void invalidateItems () {
    for (ItemView item : items) {
      item.invalidate();
    }
  }

  @Override
  protected void onDraw (Canvas c) {
    if (isDock) {
      // Transparent container: the toolbar and the FABs draw themselves.
      return;
    }
    int fillingColor = containerColor();
    if (isNavBar) {
      // Full width, square corners, surfaceContainer, no shadow (also behind the system navigation bar).
      c.drawRect(0, 0, getWidth(), getHeight(), Paints.fillingPaint(fillingColor));
      return;
    }
    {
      RectF rect = Paints.getRectF();
      rect.set(0, getRailTop(), getWidth(), getHeight());
      float radius = isBar ? getBarRadius() : getRailRadius();
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(fillingColor));
      if (!isBar && needOutlineStroke()) {
        c.drawRoundRect(rect, radius, radius, Paints.strokeSmallPaint(Theme.separatorColor()));
      }
    }
  }

  /** Elevation shadow is barely visible on dark themes, floating elements are outlined instead. */
  private static boolean needOutlineStroke () {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || Theme.isDark();
  }

  @Override
  public void performDestroy () {
    avatarView.performDestroy();
  }

  /** Dock FAB shape: rounded square filling the view. */
  private static class FabOutline extends ViewOutlineProvider {
    private final float radiusDp;

    FabOutline (float radiusDp) {
      this.radiusDp = radiusDp;
    }

    @Override
    public void getOutline (View view, Outline outline) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Screen.dp(radiusDp));
      }
    }
  }

  /** Opaque dock FAB shape, masks the pressed ripple. */
  private static class FabMask extends Drawable {
    private final float radiusDp;

    FabMask (float radiusDp) {
      this.radiusDp = radiusDp;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      RectF rect = Paints.getRectF();
      rect.set(getBounds());
      float radius = Screen.dp(radiusDp);
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(0xffffffff));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Opaque shape of the active indicator of an item, masks the pressed ripple. */
  private class IndicatorMask extends Drawable {
    private final boolean followLayoutMode;

    IndicatorMask () {
      this(true);
    }

    /** @param followLayoutMode false: always the rail indicator, centered (search button) */
    IndicatorMask (boolean followLayoutMode) {
      this.followLayoutMode = followLayoutMode;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      Rect bounds = getBounds();
      RectF rect = Paints.getRectF();
      boolean bar = followLayoutMode && (isBar || isDock);
      float width = Math.min(bounds.width() - Screen.dp(8f), Screen.dp(followLayoutMode && isNavBar ? NAV_BAR_INDICATOR_WIDTH : bar ? BAR_INDICATOR_WIDTH : RAIL_INDICATOR_WIDTH));
      float height = Screen.dp(INDICATOR_HEIGHT);
      if (followLayoutMode && isDock) {
        // Same shape as the dock indicator: fills the item inside the toolbar padding.
        width = bounds.width();
        height = bounds.height() - Screen.dp(DOCK_PADDING) * 2;
      }
      float cx = bounds.exactCenterX();
      float cy = followLayoutMode ? bounds.top + getIconCenterY() : bounds.exactCenterY();
      rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
      c.drawRoundRect(rect, height / 2f, height / 2f, Paints.fillingPaint(0xffffffff));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /**
   * Icon-only item of the rail without a section: search (its icon turns into a close icon with the
   * active indicator while search is open) and scroll to top.
   */
  private class SimpleItemView extends View implements ForegroundCompat.Host {
    private final ForegroundCompat foregroundCompat = new ForegroundCompat(this);

    @Nullable
    @Override
    public ForegroundCompat foregroundCompat () {
      return foregroundCompat;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      super.draw(c);
      if (foregroundCompat != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        foregroundCompat.draw(c);
      }
    }

    @Override
    protected boolean verifyDrawable (@NonNull Drawable who) {
      return super.verifyDrawable(who) || (foregroundCompat != null && foregroundCompat.verifyDrawable(who));
    }

    @Override
    protected void drawableStateChanged () {
      super.drawableStateChanged();
      if (foregroundCompat != null) {
        foregroundCompat.drawableStateChanged();
      }
    }

    @Override
    public void drawableHotspotChanged (float x, float y) {
      super.drawableHotspotChanged(x, y);
      if (foregroundCompat != null) {
        foregroundCompat.drawableHotspotChanged(x, y);
      }
    }

    @Override
    public void jumpDrawablesToCurrentState () {
      super.jumpDrawablesToCurrentState();
      if (foregroundCompat != null) {
        foregroundCompat.jumpDrawablesToCurrentState();
      }
    }
    private final Drawable icon;
    private final @Nullable Drawable openIcon;

    public SimpleItemView (Context context, @DrawableRes int iconRes, @DrawableRes int openIconRes, int labelRes) {
      super(context);
      icon = Drawables.get(getResources(), iconRes);
      openIcon = openIconRes != 0 ? Drawables.get(getResources(), openIconRes) : null;
      setContentDescription(Lang.getString(labelRes));
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float open = openIcon != null ? searchOpenFactor : 0f;
      if (isDock) {
        // FAB: search is the primary action (primaryContainer), scroll to top is a small surface FAB.
        boolean isPrimary = openIcon != null;
        RectF rect = Paints.getRectF();
        rect.set(0, 0, getWidth(), getHeight());
        float radius = Screen.dp(isPrimary ? DOCK_FAB_RADIUS : DOCK_SMALL_FAB_RADIUS);
        c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(isPrimary ? M3.primaryContainer() : M3.surfaceContainerHigh()));
        if (needOutlineStroke() && !isPrimary) {
          c.drawRoundRect(rect, radius, radius, Paints.strokeSmallPaint(Theme.separatorColor()));
        }
        int iconColor = isPrimary ? M3.onPrimaryContainer() : M3.onSurfaceVariant();
        if (open < 1f) {
          c.save();
          c.rotate(90f * open, cx, cy);
          Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - open, iconColor)));
          c.restore();
        }
        if (open > 0f && openIcon != null) {
          c.save();
          c.rotate(-90f * (1f - open), cx, cy);
          Drawables.draw(c, openIcon, cx - openIcon.getMinimumWidth() / 2f, cy - openIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(open, iconColor)));
          c.restore();
        }
        return;
      }
      if (open > 0f) {
        RectF rect = Paints.getRectF();
        float indicatorHeight = Screen.dp(INDICATOR_HEIGHT);
        float indicatorWidth = Screen.dp(RAIL_INDICATOR_WIDTH) * open;
        rect.set(cx - indicatorWidth / 2f, cy - indicatorHeight / 2f, cx + indicatorWidth / 2f, cy + indicatorHeight / 2f);
        c.drawRoundRect(rect, indicatorHeight / 2f, indicatorHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(open, M3.secondaryContainer())));
      }
      int iconColor = ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.onSecondaryContainer(), open);
      if (open < 1f) {
        c.save();
        c.rotate(90f * open, cx, cy);
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - open, iconColor)));
        c.restore();
      }
      if (open > 0f && openIcon != null) {
        c.save();
        c.rotate(-90f * (1f - open), cx, cy);
        Drawables.draw(c, openIcon, cx - openIcon.getMinimumWidth() / 2f, cy - openIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(open, iconColor)));
        c.restore();
      }
    }
  }

  private class ItemView extends FrameLayoutFix implements FactorAnimator.Target {
    private final int id;
    private final @Nullable Drawable icon, selectedIcon;
    private final String label;
    private final FactorAnimator selectAnimator, colorAnimator;
    private float selectFactor, colorFactor;

    public ItemView (Context context, int id, @DrawableRes int iconRes, @DrawableRes int selectedIconRes, int labelRes) {
      super(context);
      this.id = id;
      this.icon = iconRes != 0 ? Drawables.get(getResources(), iconRes) : null;
      this.selectedIcon = selectedIconRes != 0 ? Drawables.get(getResources(), selectedIconRes) : null;
      this.label = Lang.getString(labelRes);
      this.selectFactor = this.colorFactor = id == selectedItem ? 1f : 0f;
      // Indicator size: fast spatial spring (may overshoot). Colors and icon crossfade: fast effects (no overshoot).
      this.selectAnimator = new FactorAnimator(0, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration, selectFactor);
      this.colorAnimator = new FactorAnimator(1, this, M3.FAST_EFFECTS, M3.FAST_EFFECTS.duration, colorFactor);
      setWillNotDraw(false);
      setContentDescription(label);
      setSelected(id == selectedItem);
      ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
        @Override
        public void onInitializeAccessibilityNodeInfo (@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          // Tab, "N of M", selected (MDC NavigationBarItemView).
          info.setRoleDescription(getResources().getString(R.string.vg_TabRole));
          int index = ItemView.this.id - ITEM_CHATS;
          info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(isHorizontal() ? 0 : index, 1, isHorizontal() ? index : 0, 1, false, isSelected()));
        }
      });
    }

    void updateDescription () {
      if (id == ITEM_CHATS && unreadCount > 0) {
        int count = (int) Math.min(Integer.MAX_VALUE, unreadCount);
        setContentDescription(label + ", " + getResources().getQuantityString(R.plurals.vg_UnreadBadge, count, count));
      } else {
        setContentDescription(label);
      }
    }

    /** The ripple mask depends on the layout mode. */
    void invalidateMask () {
      Drawable foreground = getForeground();
      if (foreground != null) {
        foreground.invalidateSelf();
      }
    }

    void setItemSelected (boolean isSelected, boolean animated) {
      setSelected(isSelected);
      float to = isSelected ? 1f : 0f;
      if (animated) {
        selectAnimator.animateTo(to);
        colorAnimator.animateTo(to);
      } else {
        selectAnimator.forceFactor(to);
        colorAnimator.forceFactor(to);
        selectFactor = colorFactor = to;
        invalidate();
      }
    }

    @Override
    public void onFactorChanged (int animatorId, float factor, float fraction, FactorAnimator callee) {
      if (animatorId == 1) {
        factor = MathUtils.clamp(factor);
        if (this.colorFactor != factor) {
          this.colorFactor = factor;
          invalidate();
        }
      } else if (this.selectFactor != factor) {
        this.selectFactor = factor;
        invalidate();
      }
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      float selected = colorFactor;
      int cx = getWidth() / 2;
      int cy = getIconCenterY();

      // Active indicator: opaque secondaryContainer pill, grows from the icon center along one axis.
      float widthFactor = Math.max(0f, selectFactor);
      if (widthFactor > 0f) {
        RectF rect = Paints.getRectF();
        float indicatorHeight = Screen.dp(INDICATOR_HEIGHT);
        float indicatorWidth = Math.min(getWidth() - Screen.dp(8f), Screen.dp(isNavBar ? NAV_BAR_INDICATOR_WIDTH : isBar || isDock ? BAR_INDICATOR_WIDTH : RAIL_INDICATOR_WIDTH)) * widthFactor;
        if (isDock) {
          // Dock: the indicator fills the item inside the toolbar padding (a pill concentric with the toolbar).
          indicatorHeight = getHeight() - Screen.dp(DOCK_PADDING) * 2;
          indicatorWidth = getWidth() * widthFactor;
        }
        rect.set(cx - indicatorWidth / 2f, cy - indicatorHeight / 2f, cx + indicatorWidth / 2f, cy + indicatorHeight / 2f);
        c.drawRoundRect(rect, indicatorHeight / 2f, indicatorHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(selected, M3.secondaryContainer())));
      }
      // Icons: outlined onSurfaceVariant when unselected, filled onSecondaryContainer when selected.
      if (icon != null && selected < 1f) {
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - selected, M3.onSurfaceVariant())));
      }
      if (selectedIcon != null && selected > 0f) {
        Drawables.draw(c, selectedIcon, cx - selectedIcon.getMinimumWidth() / 2f, cy - selectedIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(selected, M3.onSecondaryContainer())));
      }
      if (isNavBar) {
        // Label below the indicator: secondary when active, onSurfaceVariant when inactive ("Active label changed from
        // on-surface-variant to secondary"). Never truncated by design: 1-2 short words.
        TextPaint paint = getLabelPaint();
        paint.setColor(ColorUtils.fromToArgb(M3.onSurfaceVariant(), M3.secondary(), selected));
        paint.setTypeface(selected >= .5f ? Fonts.getRobotoBold() : Fonts.getRobotoMedium());
        float lineTop = Screen.dp(NAV_BAR_INDICATOR_TOP + INDICATOR_HEIGHT + NAV_BAR_LABEL_GAP);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = lineTop + Screen.dp(NAV_BAR_LABEL_LINE_HEIGHT) / 2f - (metrics.ascent + metrics.descent) / 2f;
        String text = label;
        if (paint.measureText(text) > getWidth()) {
          text = TextUtils.ellipsize(text, paint, getWidth(), TextUtils.TruncateAt.END).toString();
        }
        c.drawText(text, cx - paint.measureText(text) / 2f, baseline, paint);
      }
    }

    @Override
    protected void dispatchDraw (Canvas canvas) {
      super.dispatchDraw(canvas);
      if (id == ITEM_CHATS) {
        // Badge goes over the top-right corner of the icon.
        int cx = getWidth() / 2 + Screen.dp(ICON_SIZE) / 2 - Screen.dp(4f);
        int cy = getIconCenterY() - Screen.dp(ICON_SIZE) / 2 + Screen.dp(2f);
        unreadCounter.draw(canvas, cx, cy, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT, 1f);
      }
    }
  }
}
