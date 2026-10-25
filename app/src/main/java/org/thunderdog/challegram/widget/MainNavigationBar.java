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
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Counter;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Main sections of the app: chats, contacts and settings (shown as the user's avatar).
 * Phone: floating vertical rail with icons only, the compose button is its separate top segment
 * that collapses on scroll. Tablet: bar attached to the bottom of the chat list.
 */
public class MainNavigationBar extends FrameLayoutFix implements Destroyable, FactorAnimator.Target {
  public static final int ITEM_CHATS = 0;
  public static final int ITEM_CONTACTS = 1;
  public static final int ITEM_SETTINGS = 2;
  public static final int ITEM_COMPOSE = 3;

  public interface Callback {
    void onNavigationItemClick (int item);
  }

  private static final float RAIL_ITEM_SIZE = 56f;
  private static final float RAIL_PADDING = 6f;
  private static final float BAR_HEIGHT = 64f;
  private static final float ICON_SIZE = 24f;
  private static final float AVATAR_SIZE = 28f;
  /** Space between the compose segment and the sections, the separator is drawn in its middle. */
  private static final float COMPOSE_GAP = 8f;

  private final ViewController<?> themeProvider;
  private final ItemView[] items;
  private final ComposeView composeView;
  private final AvatarView avatarView;
  private final Counter unreadCounter;
  private @Nullable Callback callback;

  private boolean isBar;
  private int selectedItem = ITEM_CHATS;

  private final FactorAnimator composeAnimator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 220L, 1f);
  private float composeVisibility = 1f;
  private float composeOpenFactor;

  public MainNavigationBar (Context context, ViewController<?> themeProvider) {
    super(context);
    this.themeProvider = themeProvider;
    setWillNotDraw(false);

    unreadCounter = new Counter.Builder()
      .textSize(11f)
      .outlineColor(ColorId.filling)
      .callback((counter, sizeChanged) -> invalidateItems())
      .build();

    avatarView = new AvatarView(context);
    avatarView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(AVATAR_SIZE), Screen.dp(AVATAR_SIZE), Gravity.CENTER_HORIZONTAL | Gravity.TOP));

    items = new ItemView[] {
      new ItemView(context, ITEM_CHATS, R.drawable.baseline_forum_24, R.string.Chats),
      new ItemView(context, ITEM_CONTACTS, R.drawable.baseline_perm_contact_calendar_24, R.string.Contacts),
      new ItemView(context, ITEM_SETTINGS, 0, R.string.Settings)
    };
    items[ITEM_SETTINGS].addView(avatarView);
    composeView = new ComposeView(context);
    composeView.setOnClickListener(v -> {
      if (callback != null && composeVisibility == 1f) {
        callback.onNavigationItemClick(ITEM_COMPOSE);
      }
    });
    addView(composeView);
    themeProvider.addThemeInvalidateListener(composeView);
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          if (isBar) {
            outline.setRect(0, 0, view.getWidth(), view.getHeight());
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

  public Counter getUnreadCounter () {
    return unreadCounter;
  }

  public void setUser (Tdlib tdlib, long userId) {
    avatarView.setUser(tdlib, userId, false);
  }

  public void setSelectedItem (int item) {
    if (this.selectedItem != item) {
      this.selectedItem = item;
      invalidateItems();
    }
  }

  public boolean isBar () {
    return isBar;
  }

  public static int getBarHeight () {
    return Screen.dp(BAR_HEIGHT);
  }

  public static int getRailWidth () {
    return Screen.dp(RAIL_ITEM_SIZE + RAIL_PADDING * 2);
  }

  /** Full height of the rail including the compose segment. */
  public int getRailHeight () {
    return Screen.dp(RAIL_ITEM_SIZE) * items.length + Screen.dp(RAIL_PADDING) * 2 + getComposeSegmentHeight();
  }

  private static int getComposeSegmentHeight () {
    return Screen.dp(RAIL_ITEM_SIZE) + Screen.dp(COMPOSE_GAP);
  }

  /** Distance from the bottom of the rail to the center of the compose button. */
  public int getComposeCenterFromRailBottom () {
    return getRailHeight() - Screen.dp(RAIL_PADDING) - Screen.dp(RAIL_ITEM_SIZE) / 2;
  }

  /** Top of the visible rail: the compose segment collapses when the compose button is hidden. */
  private int getRailTop () {
    return isBar ? 0 : Math.round(getComposeSegmentHeight() * (1f - composeVisibility));
  }

  // Compose button

  public void setComposeIcon (@DrawableRes int icon) {
    composeView.setIcon(icon);
  }

  public void setComposeVisible (boolean visible, boolean animated) {
    if (animated && !isBar && getParent() != null) {
      composeAnimator.animateTo(visible ? 1f : 0f);
    } else {
      composeAnimator.forceFactor(visible ? 1f : 0f);
      setComposeVisibility(visible ? 1f : 0f);
    }
  }

  public void setComposeOpenFactor (float factor) {
    if (this.composeOpenFactor != factor) {
      this.composeOpenFactor = factor;
      composeView.invalidate();
    }
  }

  private void setComposeVisibility (float visibility) {
    if (this.composeVisibility != visibility) {
      this.composeVisibility = visibility;
      updateComposeView();
      invalidate();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        invalidateOutline();
      }
    }
  }

  private void updateComposeView () {
    composeView.setVisibility(isBar || composeVisibility == 0f ? View.GONE : View.VISIBLE);
    float scale = .6f + .4f * composeVisibility;
    composeView.setScaleX(scale);
    composeView.setScaleY(scale);
    composeView.setAlpha(composeVisibility);
    composeView.setTranslationY(getComposeSegmentHeight() * (1f - composeVisibility) * .5f);
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    setComposeVisibility(factor);
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
  public void setLayoutMode (boolean isBar, int bottomInset, int railBottomOffset) {
    boolean modeChanged = this.isBar != isBar || getLayoutParams() == null;
    this.isBar = isBar;
    int itemSize = Screen.dp(RAIL_ITEM_SIZE);
    int padding = Screen.dp(RAIL_PADDING);
    FrameLayoutFix.LayoutParams params;
    if (isBar) {
      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, getBarHeight() + bottomInset, Gravity.BOTTOM);
      setPadding(0, 0, 0, bottomInset);
    } else {
      params = FrameLayoutFix.newParams(getRailWidth(), getRailHeight(), Gravity.BOTTOM | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT));
      params.leftMargin = params.rightMargin = getRailMargin();
      params.bottomMargin = getRailMargin() + bottomInset + railBottomOffset;
      setPadding(padding, padding, padding, padding);
    }
    setLayoutParams(params);
    for (int i = 0; i < items.length; i++) {
      FrameLayoutFix.LayoutParams itemParams;
      if (isBar) {
        itemParams = FrameLayoutFix.newParams(0, getBarHeight(), Gravity.TOP | Gravity.LEFT);
      } else {
        itemParams = FrameLayoutFix.newParams(itemSize, itemSize, Gravity.TOP | Gravity.LEFT);
        itemParams.topMargin = itemSize * i;
      }
      if (!isBar) {
        itemParams.topMargin += getComposeSegmentHeight();
      }
      items[i].setLayoutParams(itemParams);
      if (modeChanged) {
        RippleSupport.setTransparentSelector(items[i], isBar ? 0 : getRailRadius(), themeProvider);
      }
    }
    composeView.setLayoutParams(FrameLayoutFix.newParams(itemSize, itemSize, Gravity.TOP | Gravity.LEFT));
    if (modeChanged) {
      RippleSupport.setTransparentSelector(composeView, getRailRadius(), themeProvider);
    }
    updateComposeView();
    Views.setTopMargin(avatarView, getIconCenterY() - Screen.dp(AVATAR_SIZE) / 2);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setElevation(isBar ? 0 : Screen.dp(4f));
      setClipToOutline(!isBar);
      invalidateOutline();
    }
    if (modeChanged) {
      requestLayout();
    }
    invalidateItems();
  }

  private int getIconCenterY () {
    return isBar ? Screen.dp(23f) : Screen.dp(RAIL_ITEM_SIZE) / 2;
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    if (isBar) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
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

  private void invalidateItems () {
    for (ItemView item : items) {
      item.invalidate();
    }
  }

  @Override
  protected void onDraw (Canvas c) {
    int fillingColor = Theme.fillingColor();
    if (isBar) {
      c.drawRect(0, 0, getWidth(), getHeight(), Paints.fillingPaint(fillingColor));
      c.drawRect(0, 0, getWidth(), Math.max(1, Screen.dp(.5f)), Paints.fillingPaint(Theme.separatorColor()));
    } else {
      RectF rect = Paints.getRectF();
      rect.set(0, getRailTop(), getWidth(), getHeight());
      float radius = getRailRadius();
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(fillingColor));
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || Theme.isDark()) {
        // Elevation shadow is barely visible on dark themes, outline the rail instead.
        c.drawRoundRect(rect, radius, radius, Paints.strokeSmallPaint(Theme.separatorColor()));
      }
      if (composeVisibility > 0f) {
        // Separates the compose action from the sections.
        int y = getPaddingTop() + Screen.dp(RAIL_ITEM_SIZE) + Screen.dp(COMPOSE_GAP) / 2;
        int inset = Screen.dp(14f);
        c.drawRect(inset, y, getWidth() - inset, y + Math.max(1, Screen.dp(1f)), Paints.fillingPaint(ColorUtils.alphaColor(composeVisibility, Theme.separatorColor())));
      }
    }
  }

  @Override
  public void performDestroy () {
    avatarView.performDestroy();
  }

  private class ComposeView extends View {
    private @DrawableRes int iconRes;
    private @Nullable Drawable icon;
    private final Drawable closeIcon;

    public ComposeView (Context context) {
      super(context);
      closeIcon = Drawables.get(getResources(), R.drawable.baseline_close_24);
      setIcon(R.drawable.baseline_create_24);
      setContentDescription(Lang.getString(R.string.NewChat));
    }

    public void setIcon (@DrawableRes int iconRes) {
      if (this.iconRes != iconRes) {
        this.iconRes = iconRes;
        this.icon = iconRes != 0 ? Drawables.get(getResources(), iconRes) : null;
        invalidate();
      }
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      int cx = getWidth() / 2;
      int cy = getHeight() / 2;
      float open = composeOpenFactor;
      // Same morph as the floating button: the icon turns into a cross while the menu is open.
      if (icon != null && open < 1f) {
        c.save();
        c.rotate(90f * open, cx, cy);
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - open, Theme.iconColor())));
        c.restore();
      }
      if (open > 0f) {
        c.save();
        c.rotate(-90f * (1f - open), cx, cy);
        Drawables.draw(c, closeIcon, cx - closeIcon.getMinimumWidth() / 2f, cy - closeIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(ColorUtils.alphaColor(open, Theme.getColor(ColorId.iconActive))));
        c.restore();
      }
    }
  }

  private class ItemView extends FrameLayoutFix {
    private final int id;
    private final @Nullable Drawable icon;
    private final String label;

    public ItemView (Context context, int id, @DrawableRes int iconRes, int labelRes) {
      super(context);
      this.id = id;
      this.icon = iconRes != 0 ? Drawables.get(getResources(), iconRes) : null;
      this.label = Lang.getString(labelRes);
      setWillNotDraw(false);
      setContentDescription(label);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      boolean isSelected = selectedItem == id;
      int activeColor = Theme.getColor(ColorId.iconActive);
      int color = isSelected ? activeColor : Theme.iconColor();
      int cx = getWidth() / 2;
      int cy = getIconCenterY();

      if (isSelected) {
        RectF rect = Paints.getRectF();
        int indicatorHeight = Screen.dp(32f);
        int indicatorWidth = Math.min(getWidth() - Screen.dp(8f), Screen.dp(isBar ? 60f : 48f));
        rect.set(cx - indicatorWidth / 2f, cy - indicatorHeight / 2f, cx + indicatorWidth / 2f, cy + indicatorHeight / 2f);
        c.drawRoundRect(rect, indicatorHeight / 2f, indicatorHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(.15f, activeColor)));
      }
      if (icon != null) {
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(color));
      }
      if (isBar) {
        float textSize = 12f;
        float maxWidth = getWidth() - Screen.dp(8f);
        CharSequence text = TextUtils.ellipsize(label, Paints.getMediumTextPaint(textSize, color, false), maxWidth, TextUtils.TruncateAt.END);
        float textWidth = Paints.getMediumTextPaint(textSize, color, false).measureText(text, 0, text.length());
        c.drawText(text, 0, text.length(), cx - textWidth / 2f, Screen.dp(56f), Paints.getMediumTextPaint(textSize, color, false));
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
