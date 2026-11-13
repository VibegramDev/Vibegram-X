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

import android.annotation.SuppressLint;
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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.drinkless.tdlib.TdApi;
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
import org.thunderdog.challegram.util.HapticMenuHelper;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Main sections of the app: chats, calls, contacts and settings (shown as the user's avatar).
 * Tablet: floating bar at the bottom of the chat list (search is the search bar in the app bar there).
 * Phone, navigation style "dock": the same floating bar, compact, centered at the bottom (search is in the app bar).
 * Phone, navigation style "bar": M3 flexible navigation bar (full width at the bottom, icons with labels).
 */
public class MainNavigationBar extends FrameLayoutFix implements Destroyable {
  // Item ids are also indexes in the items array.
  public static final int ITEM_CHATS = 0;
  public static final int ITEM_CALLS = 1;
  public static final int ITEM_CONTACTS = 2;
  public static final int ITEM_SETTINGS = 3;
  public interface Callback {
    void onNavigationItemClick (int item);
  }

  /** Tablet bar: icons only, full corner radius (pill). Also the height of the floating message composer. */
  private static final float BAR_HEIGHT = 56f;
  /** Center of the icon (and the active indicator) from the top of the tablet bar: vertically centered. */
  private static final float BAR_ICON_CENTER_Y = BAR_HEIGHT / 2f;
  private static final float AVATAR_SIZE = 28f;
  /** Navigation bar (labels): active indicator (and pressed state layer) height. */
  private static final float INDICATOR_HEIGHT = 32f;
  /**
   * Floating bar (dock): the active indicator is a tall pill with the same gap to the bar edge on every side:
   * 56 - 4 * 2 = 48dp tall, item width - 4dp wide (the bar has 2dp padding at the ends, so the outer items also get
   * 4dp to the end of the bar). Its 24dp radius is concentric with the 28dp radius of the bar.
   */
  private static final float BAR_INDICATOR_INSET = 4f;
  private static final float BAR_INDICATOR_HEIGHT = BAR_HEIGHT - BAR_INDICATOR_INSET * 2f;
  /** Unread badge (large badge, gravity start): center of its start cap from the icon center, over the icon's top end corner. */
  private static final float BADGE_OFFSET_X = 10f;
  private static final float BADGE_OFFSET_Y = 8f;

  private final ViewController<?> themeProvider;
  private final ItemView[] items;
  private final AvatarView avatarView;
  private final Counter unreadCounter;
  private @Nullable Callback callback;
  private boolean isBar;
  private int selectedItem = ITEM_CHATS;

  /**
   * Phone, navigation style "bar": M3 flexible navigation bar with vertical items (m3.material.io/components/navigation-bar).
   * "The container should always be placed at the bottom of the product and span the full length of the window",
   * "All navigation items require a label text". Search is the search button in the app bar.
   * Sizes are the flexible navigation bar tokens: 64dp container, 56x32dp active indicator, 24dp icon,
   * 6dp from the top to the indicator, 4dp between the indicator and the label (label medium, 16dp line).
   */
  private boolean isNavBar;
  /** Phone dock style: the floating bar is only as wide as its items, centered. */
  private boolean isCompactBar;
  /** Phone dock style in a short window (landscape): the compact bar is at the end, the folder chips in line with it. */
  private boolean isCompactBarAtEnd;
  private static final float NAV_BAR_HEIGHT = 64f;
  private static final float NAV_BAR_INDICATOR_WIDTH = 56f;
  private static final float NAV_BAR_INDICATOR_TOP = 6f;
  private static final float NAV_BAR_LABEL_GAP = 4f;
  private static final float NAV_BAR_LABEL_LINE_HEIGHT = 16f;
  private @Nullable TextPaint labelPaint;

  /** Compact floating bar (phone dock style): 74dp per item and 2dp padding at the ends (300dp for 4 items). */
  private static final float COMPACT_ITEM_WIDTH = 74f;
  private static final float COMPACT_PADDING = BAR_INDICATOR_INSET / 2f;

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
          // The badge is drawn over the active indicator when chats is selected.
          ItemView[] items = MainNavigationBar.this.items;
          float selected = items != null ? items[ITEM_CHATS].colorFactor : 0f;
          return ColorUtils.fromToArgb(containerColor(), indicatorColor(), selected);
        }
      })
      .callback((counter, sizeChanged) -> invalidateItems())
      .build();

    avatarView = new AvatarView(context);
    avatarView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(AVATAR_SIZE), Screen.dp(AVATAR_SIZE), Gravity.CENTER_HORIZONTAL | Gravity.TOP));

    items = new ItemView[] {
      // M3: outlined icon for unselected destinations, filled for the selected one (same glyph, Telegram 12.4 tab icons).
      new ItemView(context, ITEM_CHATS, R.drawable.vg_tab_chats_24, R.drawable.vg_tab_chats_active_24, R.string.Chats),
      new ItemView(context, ITEM_CALLS, R.drawable.vg_tab_calls_24, R.drawable.vg_tab_calls_active_24, R.string.Calls),
      new ItemView(context, ITEM_CONTACTS, R.drawable.vg_tab_contacts_24, R.drawable.vg_tab_contacts_active_24, R.string.Contacts),
      new ItemView(context, ITEM_SETTINGS, 0, 0, R.string.Settings)
    };
    items[ITEM_SETTINGS].addView(avatarView);
    for (ItemView item : items) {
      item.setOnClickListener(v -> {
        if (callback != null) {
          callback.onNavigationItemClick(((ItemView) v).id);
        }
      });
      addView(item);
      themeProvider.addThemeInvalidateListener(item);
    }
    themeProvider.addThemeInvalidateListener(this);

    // The floating bar has no labels (accepted deviation): plain tooltip with the label on tap and hold, 4dp above the
    // bar. Visual boundary: horizontal bounds of the item, vertical bounds of the bar container.
    M3Tooltip.BoundsProvider barBounds = (anchor, outRect) -> outRect.set(0, -anchor.getTop(), anchor.getWidth(), getHeight() - anchor.getTop());
    M3Tooltip.TextProvider tooltipText = anchor -> ((ItemView) anchor).label;
    for (ItemView item : items) {
      M3Tooltip.bind(item, M3Tooltip.PLACEMENT_ABOVE, barBounds, tooltipText);
    }
    // Tap and hold an item with a menu (chats: folders, settings: accounts), then slide to an entry
    // and release, as the tab bar of Telegram for iOS. Other items keep the tooltip of the dock.
    View.OnLongClickListener longClickListener = v -> {
      ItemView item = (ItemView) v;
      if (item.menu != null && item.menu.openMenu(v)) {
        M3Tooltip.hideCurrent(true);
        // The finger keeps moving over the screen to pick an entry: no swipe back or scrolling.
        v.getParent().requestDisallowInterceptTouchEvent(true);
      } else if (isNavBar || !M3Tooltip.show(v, M3Tooltip.PLACEMENT_ABOVE, barBounds, item.label)) {
        return false;
      }
      v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      return true;
    };
    for (ItemView item : items) {
      item.setOnLongClickListener(longClickListener);
    }
    // Screen readers: the sections are tabs of a single-selection collection (as MDC NavigationBarMenuView).
    ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
      @Override
      public void onInitializeAccessibilityNodeInfo (@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, items.length, false, AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE));
      }
    });
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          if (isNavBar) {
            outline.setRect(0, 0, view.getWidth(), view.getHeight());
          } else {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), getBarRadius());
          }
        }
      });
    }
    setLayoutMode(true, false, 0);
  }

  public void setCallback (@Nullable Callback callback) {
    this.callback = callback;
  }

  /** Menu opened by holding the item; the finger then selects an entry without lifting. */
  public void setItemMenu (int item, @Nullable HapticMenuHelper menu) {
    items[item].menu = menu;
    updateLongClickable(items[item]);
  }

  private void updateLongClickable (ItemView item) {
    // Navigation bar: labels are visible, no tooltips.
    item.setLongClickable(!isNavBar || item.menu != null);
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

  /**
   * Avatar of the settings item. Until TDLib has loaded the profile (my id or the user object unknown yet, e.g. a cold
   * start or an account that is still starting up), the avatar saved for the account is shown: an AvatarView with
   * neither draws nothing at all, leaving an empty slot in the dock. Called again on every update of the profile.
   */
  public void setMyUser (Tdlib tdlib) {
    TdApi.User myUser = tdlib.myUser();
    if (myUser != null) {
      avatarView.setUser(tdlib, myUser, false);
    } else {
      avatarView.setUser(tdlib.account());
    }
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

  /** surfaceContainer (M3 navigation area role). */
  private int containerColor () {
    return M3.surfaceContainer();
  }

  /**
   * Active indicator. Floating bar: neutral pill a step above the surfaceContainer bar: surfaceContainerHighest
   * (Monet: tone 22 on 12 dark, 90 on 94 light; classic themes: 14% / 9% tint on 7% / 5%), surfaceContainerHigh is too
   * close to the bar to read in light themes. Navigation bar: M3 secondaryContainer indicator.
   */
  private int indicatorColor () {
    return isNavBar ? M3.secondaryContainer() : M3.surfaceContainerHighest();
  }

  /** Icon of the selected item: onSurface on the neutral dock indicator, onSecondaryContainer in the navigation bar. */
  private int selectedIconColor () {
    return isNavBar ? M3.onSecondaryContainer() : M3.onSurface();
  }

  /**
   * Bounds of the active indicator of an item at full size, centered in the item.
   * Floating bar: 48dp pill, item width - 4dp. Navigation bar: 56x32dp, 6dp from the top.
   */
  private void getIndicatorRect (RectF rect, int itemLeft, int itemTop, int itemWidth) {
    float width, height;
    if (isNavBar) {
      width = Math.min(itemWidth - Screen.dp(8f), Screen.dp(NAV_BAR_INDICATOR_WIDTH));
      height = Screen.dp(INDICATOR_HEIGHT);
    } else {
      width = itemWidth - Screen.dp(BAR_INDICATOR_INSET);
      height = Screen.dp(BAR_INDICATOR_HEIGHT);
    }
    float cx = itemLeft + itemWidth / 2f;
    float cy = itemTop + getIconCenterY();
    rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
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
      ripple = Theme.transparentRoundSelector(Screen.dp((isNavBar ? INDICATOR_HEIGHT : BAR_INDICATOR_HEIGHT) / 2f));
    }
    view.setBackground(null);
    ForegroundCompat.set(view, ripple);
  }


  public boolean isBar () {
    return isBar;
  }

  public boolean isNavBar () {
    return isNavBar;
  }

  /** Navigation bar: height of the container above the system navigation bar. */
  public static int getNavBarHeight () {
    return Screen.dp(NAV_BAR_HEIGHT);
  }

  public static int getBarHeight () {
    return Screen.dp(BAR_HEIGHT);
  }

  /**
   * Tablet bar floats above the chat list (M3 Expressive floating toolbar look): full corner radius,
   * small margins at the sides and the bottom (our choice: 12dp; bottom only with the button navigation bar).
   */
  public static int getBarMargin () {
    return Screen.dp(12f);
  }

  private static boolean gestureNavigation = true;

  /**
   * System navigation mode: gesture navigation or the button navigation bar. The bottom margins of the bar and the
   * composer no longer depend on it (owner: margins in both modes).
   * @return true if the mode changed
   */
  public static boolean setGestureNavigation (boolean isGestureNavigation) {
    if (gestureNavigation != isGestureNavigation) {
      gestureNavigation = isGestureNavigation;
      return true;
    }
    return false;
  }

  public static boolean isGestureNavigation () {
    return gestureNavigation;
  }

  /** Bottom margin of the floating bar and the composer: same as the side margins in any navigation mode (owner). */
  public static int getBarBottomMargin () {
    return getBarMargin();
  }

  /** Full corner radius: half of the bar height (pill), concentric with the vertically centered indicator. */
  public static float getBarRadius () {
    return Screen.dp(BAR_ICON_CENTER_Y);
  }

  /** Space taken at the bottom of the chat list pane by the floating tablet bar (without the system inset). */
  public static int getBarTotalHeight () {
    return getBarHeight() + getBarBottomMargin();
  }

  /** Phone dock style: the floating bar is only as wide as its items, centered (applied by setLayoutMode). */
  public void setCompactBar (boolean isCompactBar) {
    this.isCompactBar = isCompactBar;
  }

  /**
   * Phone dock style in a short window: the compact bar is at the end of the screen instead of the center, the folder
   * chips take the rest of the row (applied by setLayoutMode).
   */
  public void setCompactBarAtEnd (boolean isCompactBarAtEnd) {
    this.isCompactBarAtEnd = isCompactBarAtEnd;
  }

  /** Width of the compact floating bar: 74dp per item and 2dp padding at the ends. */
  public int getCompactBarWidth () {
    return Screen.dp(COMPACT_ITEM_WIDTH) * items.length + Screen.dp(COMPACT_PADDING) * 2;
  }

  /**
   * @param isBar floating bar (tablet, phone dock style)
   * @param isNavBar phone only: navigation style "bar" (ignored with isBar)
   * @param bottomInset system navigation bar inset
   */
  public void setLayoutMode (boolean isBar, boolean isNavBar, int bottomInset) {
    isNavBar = isNavBar && !isBar;
    isBar = !isNavBar;
    boolean modeChanged = this.isBar != isBar || this.isNavBar != isNavBar || getLayoutParams() == null || (isBar && isCompactBar != (getLayoutParams().width != ViewGroup.LayoutParams.MATCH_PARENT));
    this.isBar = isBar;
    this.isNavBar = isNavBar;
    FrameLayoutFix.LayoutParams params;
    if (isNavBar) {
      // Edge to edge: the container goes under the system navigation bar, the items stay above it.
      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getNavBarHeight() + bottomInset, Gravity.BOTTOM);
      setPadding(0, 0, 0, bottomInset);
    } else {
      if (isCompactBar) {
        int horizontalGravity = isCompactBarAtEnd ? (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) : Gravity.CENTER_HORIZONTAL;
        params = FrameLayoutFix.newParams(getCompactBarWidth(), getBarHeight(), Gravity.BOTTOM | horizontalGravity);
      } else {
        params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getBarHeight(), Gravity.BOTTOM);
      }
      params.leftMargin = params.rightMargin = getBarMargin();
      params.bottomMargin = getBarBottomMargin() + bottomInset;
      // Half of the indicator inset: the outer indicators keep the same 4dp gap to the rounded ends as to the top and bottom.
      int padding = Screen.dp(BAR_INDICATOR_INSET / 2f);
      setPadding(padding, 0, padding, 0);
    }
    setLayoutParams(params);
    for (ItemView item : items) {
      updateLongClickable(item);
    }
    for (int i = 0; i < items.length; i++) {
      FrameLayoutFix.LayoutParams itemParams = FrameLayoutFix.newParams(0, isNavBar ? getNavBarHeight() : getBarHeight(), Gravity.TOP | Gravity.LEFT);
      items[i].setLayoutParams(itemParams);
      if (items[i].getForeground() == null) {
        setPressedStateLayer(items[i], new IndicatorMask());
      }
      items[i].invalidateMask();
    }
    Views.setTopMargin(avatarView, getIconCenterY() - Screen.dp(AVATAR_SIZE) / 2);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Floating bar: soft shadow drawn by M3BottomScrimView (same as the floating message composer).
      // Navigation bar: "M3: Taller and no drop shadow", the container color separates it from the content.
      setElevation(0);
      setClipToOutline(true);
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
    return Screen.dp(BAR_ICON_CENTER_Y);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    {
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
    int fillingColor = containerColor();
    if (isNavBar) {
      // Full width, square corners, surfaceContainer, no shadow (also behind the system navigation bar).
      c.drawRect(0, 0, getWidth(), getHeight(), Paints.fillingPaint(fillingColor));
      return;
    }
    {
      RectF rect = Paints.getRectF();
      rect.set(0, 0, getWidth(), getHeight());
      float radius = getBarRadius();
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(fillingColor));
    }
  }

  @Override
  public void performDestroy () {
    avatarView.performDestroy();
  }

  /** Opaque shape of the active indicator of an item, masks the pressed ripple. */
  private class IndicatorMask extends Drawable {
    @Override
    public void draw (@NonNull Canvas c) {
      Rect bounds = getBounds();
      RectF rect = Paints.getRectF();
      getIndicatorRect(rect, bounds.left, bounds.top, bounds.width());
      float radius = rect.height() / 2f;
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

  private class ItemView extends FrameLayoutFix implements FactorAnimator.Target {
    private final int id;
    private final @Nullable Drawable icon, selectedIcon;
    private final String label;
    private final FactorAnimator selectAnimator, colorAnimator;
    private float selectFactor, colorFactor;
    private @Nullable HapticMenuHelper menu;

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
          info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, index, 1, false, isSelected()));
        }
      });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent (MotionEvent e) {
      if (menu != null) {
        menu.onTouch(this, e);
      }
      return super.onTouchEvent(e);
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

      // Active indicator: opaque pill (dock: neutral 48dp pill; navigation bar: secondaryContainer 56x32dp), grows from the
      // icon center along the horizontal axis, stays fully rounded while it grows.
      float widthFactor = Math.max(0f, selectFactor);
      if (widthFactor > 0f) {
        RectF rect = Paints.getRectF();
        getIndicatorRect(rect, 0, 0, getWidth());
        float indicatorWidth = rect.width() * widthFactor;
        rect.left = cx - indicatorWidth / 2f;
        rect.right = cx + indicatorWidth / 2f;
        float radius = Math.min(rect.width(), rect.height()) / 2f;
        c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(selected, indicatorColor())));
      }
      // Icons: outlined onSurfaceVariant when unselected, filled (same glyph) when selected.
      if (icon != null && selected < 1f) {
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - selected, M3.onSurfaceVariant())));
      }
      if (selectedIcon != null && selected > 0f) {
        Drawables.draw(c, selectedIcon, cx - selectedIcon.getMinimumWidth() / 2f, cy - selectedIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(selected, selectedIconColor())));
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
        // Badge overlaps the top end corner of the icon: its start cap is centered 10dp to the end of and 8dp above the
        // icon center (inside the 24dp icon box), the badge grows towards the end.
        boolean rtl = Lang.rtl();
        int cx = getWidth() / 2 + Screen.dp(BADGE_OFFSET_X) * (rtl ? -1 : 1);
        int cy = getIconCenterY() - Screen.dp(BADGE_OFFSET_Y);
        unreadCounter.draw(canvas, cx, cy, rtl ? Gravity.RIGHT : Gravity.LEFT, 1f);
      }
    }
  }
}
