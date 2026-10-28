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
 * File created on 16/05/2015 at 16:02
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeListenerList;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.PorterDuffPaint;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.HapticMenuHelper;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.NoScrollTextView;
import org.thunderdog.challegram.widget.RootFrameLayout;
import org.thunderdog.challegram.widget.SimplestCheckBox;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.Animated;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

public class MenuMoreWrap extends MenuMoreWrapAbstract implements Animated, RootFrameLayout.MarginModifier {
  public static final int ITEM_HEIGHT = 48;
  /** Transparent margin around the container (room for the shadow; anchoring math relies on it). */
  public static final int PADDING = 8;
  /** M3 vertical menu: inner padding between the container edge and the items (SegmentedMenuTokens.GroupPadding). */
  public static final int INSET = 4;
  /** M3 menu container width limits (112dp..280dp). */
  public static final int MIN_WIDTH = 112, MAX_WIDTH = 280;
  /** Horizontal padding of an item and the gap between its leading icon and label. */
  public static final int ITEM_PADDING = 12;
  /** Start of the label inside an item: padding + 24dp icon + gap. */
  public static final int ITEM_TEXT_START = ITEM_PADDING + 24 + ITEM_PADDING;

  public static final float START_SCALE = .56f;

  public static final long REVEAL_DURATION = 258l;
  public static final Interpolator REVEAL_INTERPOLATOR = AnimatorUtils.DECELERATE_INTERPOLATOR;

  public static final int ANCHOR_MODE_RIGHT = 0;
  public static final int ANCHOR_MODE_HEADER = 1;
  public static final int ANCHOR_MODE_CENTER = 2;
  /** Next to an element or a touch point given in screen coordinates ({@link #setAnchor}). */
  public static final int ANCHOR_MODE_RECT = 3;

  // private int currentWidth;
  private int anchorMode;

  private @Nullable ThemeListenerList themeListeners;
  private @Nullable ThemeDelegate forcedTheme;
  private final ComplexReceiver complexAvatarReceiver;

  private final LinearLayout itemsLayout;

  public MenuMoreWrap (Context context) {
    this(context, false);
  }

  public MenuMoreWrap (Context context, boolean scrollable) {
    super(context);
    setWillNotDraw(false);
    complexAvatarReceiver = new ComplexReceiver(this);
    factorAnimator.forceFactor(-1);
    if (scrollable) {
      itemsLayout = new LinearLayout(context);
      itemsLayout.setOrientation(LinearLayout.VERTICAL);
      ScrollView scrollView = new ScrollView(context);
      scrollView.addView(itemsLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      scrollView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      addView(scrollView);
    } else {
      itemsLayout = this;
    }
  }

  public void updateDirection () {
    if (Views.setGravity(this, Gravity.TOP | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT)))
      Views.updateLayoutParams(this);
  }

  public void init (@Nullable ThemeListenerList themeProvider) {
    init(themeProvider, /* forceTheme */ null);
  }

  public void init (@Nullable ThemeListenerList themeProvider, @Nullable ThemeDelegate forcedTheme) {
    this.themeListeners = themeProvider;
    this.forcedTheme = forcedTheme;

    // M3 vertical menu (standard): surfaceContainerLow container, CornerLarge (16dp), Level2 elevation (3dp), 112..280dp wide.
    setMinimumWidth(Screen.dp(MIN_WIDTH + PADDING * 2));
    ViewUtils.setBackground(this, new ContainerDrawable(this));
    int padding = Screen.dp(PADDING + INSET);
    setPadding(padding, padding, padding, padding);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          int margin = Screen.dp(PADDING);
          outline.setRoundRect(margin, margin, view.getWidth() - margin, view.getHeight() - margin, Screen.dp(M3.CORNER_LARGE));
        }
      });
      setElevation(Screen.dp(M3.ELEVATION_LEVEL2));
    }

    if (themeProvider != null && forcedTheme == null) {
      themeProvider.addThemeInvalidateListener(this);
    }

    setOrientation(VERTICAL);
    setLayerType(LAYER_TYPE_HARDWARE, Views.getLayerPaint());
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT)));
  }

  public void setRightNumber (int number) {
    setTranslationX(-Screen.dp(49f) * number);
  }

  public void setAnchorMode (int anchorMode) {
    if (this.anchorMode != anchorMode) {
      this.anchorMode = anchorMode;
      FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) getLayoutParams();
      switch (anchorMode) {
        case ANCHOR_MODE_RIGHT: {
          params.gravity = Gravity.TOP | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT);
          break;
        }
        case ANCHOR_MODE_CENTER: {
          params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
          break;
        }
        case ANCHOR_MODE_HEADER: {
          params.gravity = Gravity.TOP | (Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT);
          setTranslationX(Lang.rtl() ? -Screen.dp(46f) : Screen.dp(46f));
          break;
        }
        case ANCHOR_MODE_RECT: {
          params.gravity = Gravity.TOP | Gravity.LEFT;
          setTranslationX(0);
          break;
        }
      }
    }
  }

  // M3 menu placement (m3.material.io/components/menus › Guidelines › Placement): "It typically appears below, next to,
  // or in front of the element that generates it. If a menu is in a position to be cut off, it should automatically
  // reposition to appear to the left, right, or above the element that generates it."

  private final Rect anchorRect = new Rect();
  private final Rect anchorInsets = new Rect();
  private boolean anchorAlignEnd;

  /**
   * Places the menu next to {@code left..bottom} (screen coordinates; a touch point is an empty rect): below it if it
   * fits, otherwise above, otherwise inside the window. Horizontally the menu starts at the anchor's start edge
   * ({@code alignEnd}: ends at its end edge) and flips when it would be cut off.
   */
  public void setAnchor (int left, int top, int right, int bottom, boolean alignEnd) {
    setAnchorMode(ANCHOR_MODE_RECT);
    anchorRect.set(left, top, right, bottom);
    anchorAlignEnd = alignEnd;
    setTranslationX(0);
    setTranslationY(0);
    requestLayout();
  }

  @Override
  public void onApplyMarginInsets (View child, FrameLayout.LayoutParams params, Rect legacyInsets, Rect insets, Rect insetsWithoutIme) {
    if (anchorMode == ANCHOR_MODE_RECT) {
      // Positioned by translation inside the whole popup; insets only bound the placement.
      Views.setMargins(params, 0, 0, 0, 0);
      if (!anchorInsets.equals(insets)) {
        anchorInsets.set(insets);
        requestLayout();
      }
    } else {
      Views.setMargins(params, legacyInsets.left, legacyInsets.top, legacyInsets.right, legacyInsets.bottom);
    }
  }

  /** @return top of the menu view in parent coordinates for the given height, or Integer.MIN_VALUE if it is cut off both below and above the anchor. */
  private int findAnchorTop (View parent, int parentHeight, int height, boolean force) {
    int parentTop = Views.getLocationOnScreen(parent)[1];
    int anchorTop = anchorRect.top - parentTop, anchorBottom = anchorRect.bottom - parentTop;
    int padding = Screen.dp(PADDING);
    // The visible container keeps an 8dp gap from the window edges (as the edge-anchored menu does).
    int minTop = anchorInsets.top + padding, maxBottom = parentHeight - anchorInsets.bottom - padding;
    if (anchorBottom + height - padding * 2 <= maxBottom) {
      return anchorBottom - padding;
    }
    if (anchorTop - height + padding * 2 >= minTop) {
      return anchorTop - height + padding;
    }
    if (!force) {
      return Integer.MIN_VALUE;
    }
    return MathUtils.clamp(anchorBottom - padding, minTop - padding, Math.max(minTop - padding, maxBottom - height + padding));
  }

  private int findAnchorLeft (View parent, int width) {
    int parentLeft = Views.getLocationOnScreen(parent)[0];
    int anchorLeft = anchorRect.left - parentLeft, anchorRight = anchorRect.right - parentLeft;
    int padding = Screen.dp(PADDING);
    int minLeft = anchorInsets.left + padding, maxRight = parent.getWidth() - anchorInsets.right - padding;
    int startAligned, endAligned;
    if (Lang.rtl()) {
      startAligned = anchorRight - width + padding;
      endAligned = anchorLeft - padding;
    } else {
      startAligned = anchorLeft - padding;
      endAligned = anchorRight - width + padding;
    }
    int first = anchorAlignEnd ? endAligned : startAligned;
    int second = anchorAlignEnd ? startAligned : endAligned;
    int left;
    if (first + padding >= minLeft && first + width - padding <= maxRight) {
      left = first;
    } else if (second + padding >= minLeft && second + width - padding <= maxRight) {
      left = second;
    } else {
      left = first;
    }
    return MathUtils.clamp(left, minLeft - padding, Math.max(minLeft - padding, maxRight - width + padding));
  }

  private void layoutAtAnchor () {
    if (!(getParent() instanceof View)) {
      return;
    }
    View parent = (View) getParent();
    int width = getMeasuredWidth(), height = getMeasuredHeight();
    int left = findAnchorLeft(parent, width);
    int top = findAnchorTop(parent, parent.getHeight(), height, true);
    setTranslationX(left - getLeft());
    setTranslationY(top - getTop());
    // Expands from the anchor.
    int[] parentLocation = Views.getLocationOnScreen(parent);
    setPivotX(MathUtils.clamp(anchorRect.centerX() - parentLocation[0] - left, 0, width));
    setPivotY(MathUtils.clamp(anchorRect.centerY() - parentLocation[1] - top, 0, height));
  }

  // Non-interactive rows above the items (options info / title) and dividers

  private @Nullable View droppableHeader;

  /**
   * Adds a non-interactive row above the items. {@code droppable}: hidden when the menu does not fit next to its
   * anchor otherwise (the value the actions apply to is visible in the anchor row anyway).
   */
  public void addHeaderView (View view, int desiredWidth, boolean droppable) {
    view.setTag(Math.min(desiredWidth, Screen.dp(MAX_WIDTH)));
    itemsLayout.addView(view);
    if (droppable) {
      droppableHeader = view;
    }
  }

  /** M3 menu divider: 1dp outlineVariant with 8dp above and below. */
  public void addDivider () {
    View divider = new View(getContext()) {
      @Override
      protected void onDraw (Canvas c) {
        c.drawRect(0, Screen.dp(8f), getMeasuredWidth(), Screen.dp(8f) + Math.max(1, Screen.dp(1f)), Paints.fillingPaint(M3.get(M3.OUTLINE_VARIANT, forcedTheme)));
      }
    };
    divider.setWillNotDraw(false);
    if (themeListeners != null && forcedTheme == null) {
      themeListeners.addThemeInvalidateListener(divider);
    }
    itemsLayout.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(17f)));
  }

  @Override
  public int getAnchorMode () {
    return anchorMode;
  }

  public void updateItem (int index, int id, CharSequence title, int icon, OnClickListener listener, @Nullable ThemeListenerList themeProvider) {
    TextView menuItem = (TextView) getChildAt(index);
    menuItem.setId(id);
    menuItem.setOnClickListener(listener);
    menuItem.setText(title);
    Drawable drawable = icon != 0 ? Drawables.get(getResources(), icon) : null;
    menuItem.setGravity(Gravity.CENTER_VERTICAL | Lang.gravity());
    menuItem.setVisibility(View.VISIBLE);
    if (drawable != null) {
      drawable.setColorFilter(Paints.getColorFilter(Theme.getColor(ColorId.icon)));
      if (themeProvider != null) {
        themeProvider.addThemeFilterListener(drawable, ColorId.icon);
      }
      if (Drawables.needMirror(icon)) {
        // TODO
      }
      if (Lang.rtl()) {
        menuItem.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
      } else {
        menuItem.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
      }
    } else {
      menuItem.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
    }

    menuItem.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    menuItem.setTag(menuItem.getMeasuredWidth());
  }

  public View addItem (@Nullable Tdlib tdlib, HapticMenuHelper.MenuItem menuItem, OnClickListener listener) {
    if (!menuItem.isCheckbox && ((StringUtils.isEmpty(menuItem.subtitle) && menuItem.messageSenderId == null) || tdlib == null)) {
      return addItem(menuItem.id, menuItem.title, menuItem.iconResId, menuItem.icon, listener);
    }

    final int maxWidth = Screen.dp(250);
    final int textRightOffset = Screen.dp(menuItem.isLocked ? ITEM_PADDING + 24 : ITEM_PADDING);
    final Drawable finalIcon = menuItem.iconResId != 0 ? Drawables.get(getResources(), menuItem.iconResId) : menuItem.icon;
    final AvatarReceiver receiver = (menuItem.messageSenderId != null && menuItem.iconResId == 0) ?
      complexAvatarReceiver.getAvatarReceiver(Td.getSenderId(menuItem.messageSenderId)) : null;
    if (receiver != null) {
      receiver.requestMessageSender(tdlib, menuItem.messageSenderId, AvatarReceiver.Options.NONE);
    }

    FrameLayout.LayoutParams lp = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(ITEM_HEIGHT), Gravity.LEFT);
    FrameLayout frameLayout = new FrameLayoutFix(getContext()) {
      @Override
      protected void onDraw (Canvas canvas) {
        super.onDraw(canvas);
        if (receiver != null) {
          receiver.draw(canvas);
        }

        if (menuItem.isLocked) {
          Drawable icon = Drawables.get(getResources(), R.drawable.baseline_lock_16);
          if (icon != null) {
            float x = getMeasuredWidth() - Screen.dp(ITEM_PADDING + 16);
            float y = (getMeasuredHeight() - icon.getMinimumHeight()) / 2f;
            Drawables.draw(canvas, icon, x, y, PorterDuffPaint.get(ColorId.text));
          }
        }

        if (menuItem.isCheckbox) {
          int fillingColor, checkColor, outlineColor;
          if (forcedTheme != null) {
            fillingColor = forcedTheme.getColor(ColorId.checkActive);
            checkColor = forcedTheme.getColor(ColorId.checkContent);
            outlineColor = forcedTheme.getColor(ColorId.icon);
          } else {
            fillingColor = Theme.checkFillingColor();
            checkColor = Theme.checkCheckColor();
            outlineColor = Theme.getColor(ColorId.icon);
          }

          // TODO move into SimplestCheckbox
          int checkBoxCenterX = Screen.dp(ITEM_PADDING + 12);
          int progressCy = getMeasuredHeight() / 2;
          int progressRadius = Screen.dp(9f);
          RectF rectF = Paints.getRectF();
          progressRadius -= Screen.dp(1f);
          rectF.set(checkBoxCenterX - progressRadius, progressCy - progressRadius, checkBoxCenterX + progressRadius, progressCy + progressRadius);
          int outlineRadius = Screen.dp(3f);
          canvas.drawRoundRect(rectF, outlineRadius, outlineRadius, Paints.getProgressPaint(outlineColor, Screen.dp(1f)));

          float selectionFactor = menuItem.isCheckboxSelectedAnimated != null ?
            menuItem.isCheckboxSelectedAnimated.getFloatValue() :
            menuItem.isCheckboxSelected ? 1f :
            0f;

          SimplestCheckBox.draw(
            canvas,
            Screen.dp(ITEM_PADDING + 12), getMeasuredHeight() / 2,
            selectionFactor, null, null,
            fillingColor, checkColor,
            SimplestCheckBox.MODE_NORMAL, 1f
          );
        } else if (finalIcon != null) {
          canvas.save();
          canvas.translate(Screen.dp(ITEM_PADDING + 12) - finalIcon.getMinimumWidth() / 2f, (getMeasuredHeight() - finalIcon.getMinimumHeight()) / 2f);
          if (menuItem.iconResId == 0) {
            finalIcon.draw(canvas);
          } else {
            Drawables.draw(canvas, finalIcon, 0, 0, PorterDuffPaint.get(ColorId.icon));
          }
          canvas.restore();
        }
      }
    };

    frameLayout.setLayoutParams(lp);
    frameLayout.setId(menuItem.id);
    if (menuItem.isCheckbox) {
      menuItem.isCheckboxSelectedAnimated = new BoolAnimator(frameLayout, AnimatorUtils.DECELERATE_INTERPOLATOR, 165l, menuItem.isCheckboxSelected);
      frameLayout.setOnClickListener(v -> {
        menuItem.isCheckboxSelected = menuItem.isCheckboxSelectedAnimated.toggleValue(true);
        listener.onClick(v);
      });
    } else {
      frameLayout.setOnClickListener(listener);
    }
    frameLayout.setWillNotDraw(false);
    if (receiver != null) {
      receiver.setBounds(Screen.dp(ITEM_PADDING + 2), Screen.dp(ITEM_HEIGHT / 2f - 10), Screen.dp(ITEM_PADDING + 22), Screen.dp(ITEM_HEIGHT / 2f + 10));
      receiver.setUpdateListener(r -> frameLayout.invalidate());
    }

    TextView titleTextItem = new NoScrollTextView(getContext());
    titleTextItem.setTypeface(Fonts.getRobotoRegular());
    titleTextItem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    titleTextItem.setText(menuItem.title);
    titleTextItem.setSingleLine(true);
    titleTextItem.setEllipsize(TextUtils.TruncateAt.END);
    titleTextItem.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(ITEM_HEIGHT)));
    if (!StringUtils.isEmpty(menuItem.subtitle)) {
      titleTextItem.setGravity(Gravity.TOP | Lang.gravity());
      titleTextItem.setPadding(Screen.dp(ITEM_TEXT_START), Screen.dp(5f), textRightOffset, 0);
    } else {
      titleTextItem.setGravity(Gravity.CENTER_HORIZONTAL | Lang.gravity());
      titleTextItem.setPadding(Screen.dp(ITEM_TEXT_START), 0, textRightOffset, 0);
      titleTextItem.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Lang.gravity()));
    }
    titleTextItem.setMaxWidth(maxWidth);

    TextView subtitleTextItem;
    if (!StringUtils.isEmpty(menuItem.subtitle)) {
      subtitleTextItem = new NoScrollTextView(getContext());
      subtitleTextItem.setTypeface(Fonts.getRobotoRegular());
      subtitleTextItem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
      subtitleTextItem.setText(menuItem.subtitle);
      subtitleTextItem.setGravity(Gravity.BOTTOM | Lang.gravity());
      subtitleTextItem.setSingleLine(true);
      subtitleTextItem.setEllipsize(TextUtils.TruncateAt.END);
      subtitleTextItem.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(ITEM_HEIGHT)));
      subtitleTextItem.setPadding(Screen.dp(ITEM_TEXT_START), 0, textRightOffset, Screen.dp(8f));
      subtitleTextItem.setMaxWidth(maxWidth);

      if (forcedTheme != null) {
        subtitleTextItem.setTextColor(forcedTheme.getColor(ColorId.textLight));
      } else {
        subtitleTextItem.setTextColor(Theme.getColor(ColorId.textLight));
        if (themeListeners != null) {
          themeListeners.addThemeTextDecentColorListener(subtitleTextItem);
        }
      }
    } else {
      subtitleTextItem = null;
    }

    titleTextItem.setTextColor(getOnContainerColor());

    if (themeListeners != null) {
      themeListeners.addThemeInvalidateListener(frameLayout);
    }

    frameLayout.addView(titleTextItem);
    if (subtitleTextItem != null) {
      frameLayout.addView(subtitleTextItem);
    }

    Views.setClickable(frameLayout);
    setItemStateLayer(frameLayout);
    itemsLayout.addView(frameLayout);
    frameLayout.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    frameLayout.setTag(frameLayout.getMeasuredWidth());
    return frameLayout;
  }

  public TextView addItem (int id, CharSequence title, int iconRes, Drawable icon, OnClickListener listener) {
    TextView menuItem = new NoScrollTextView(getContext());
    menuItem.setId(id);
    menuItem.setTypeface(Fonts.getRobotoRegular());
    menuItem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    // M3 menu: label onSurface, icons onSurfaceVariant.
    menuItem.setTextColor(getOnContainerColor());
    menuItem.setText(title);
    menuItem.setGravity(Gravity.CENTER_VERTICAL | Lang.gravity());
    menuItem.setSingleLine(true);
    menuItem.setEllipsize(TextUtils.TruncateAt.END);
    menuItem.setOnClickListener(listener);
    menuItem.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(ITEM_HEIGHT)));
    menuItem.setPadding(Screen.dp(ITEM_PADDING), 0, Screen.dp(ITEM_PADDING), 0);
    menuItem.setCompoundDrawablePadding(Screen.dp(ITEM_PADDING));
    icon = iconRes != 0 ? Drawables.get(getResources(), iconRes) : icon;
    if (icon != null) {
      icon = icon.mutate();
      icon.setColorFilter(Paints.getColorFilter(M3.get(M3.ON_SURFACE_VARIANT, forcedTheme)));
      if (Drawables.needMirror(iconRes)) {
        // TODO
      }
      if (Lang.rtl()) {
        menuItem.setCompoundDrawablesWithIntrinsicBounds(null, null, icon, null);
      } else {
        menuItem.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
      }
    }
    Views.setClickable(menuItem);
    setItemStateLayer(menuItem);
    itemsLayout.addView(menuItem);
    menuItem.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    menuItem.setTag(menuItem.getMeasuredWidth());
    return menuItem;
  }

  /** Interactive item drawn by the caller (e.g. an account row with an avatar): 48dp row with the menu state layer. */
  public void addCustomItem (View item, int desiredWidth, OnClickListener listener) {
    item.setOnClickListener(listener);
    item.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(ITEM_HEIGHT)));
    Views.setClickable(item);
    setItemStateLayer(item);
    item.setTag(Math.min(desiredWidth, Screen.dp(MAX_WIDTH)));
    itemsLayout.addView(item);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(MeasureSpec.makeMeasureSpec(getItemsWidth(), MeasureSpec.EXACTLY), heightMeasureSpec);
    if (anchorMode == ANCHOR_MODE_RECT && droppableHeader != null && droppableHeader.getVisibility() == View.VISIBLE && getParent() instanceof View && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
      if (findAnchorTop((View) getParent(), MeasureSpec.getSize(heightMeasureSpec), getMeasuredHeight(), false) == Integer.MIN_VALUE) {
        droppableHeader.setVisibility(View.GONE);
        super.onMeasure(MeasureSpec.makeMeasureSpec(getItemsWidth(), MeasureSpec.EXACTLY), heightMeasureSpec);
      }
    }
  }

  @Override
  public int getItemsWidth () {
    int padding = Screen.dp(PADDING);
    int childCount = getChildCount();
    int maxWidth = 0;
    for (int i = 0; i < childCount; i++) {
      View v = getChildAt(i);
      if (v == null || v.getVisibility() == View.GONE) {
        continue;
      }
      if (v instanceof TextView) {
        // Items may be changed after they were added (e.g. a trailing lock icon); their tag may belong to the caller.
        v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        maxWidth = Math.max(maxWidth, v.getMeasuredWidth());
      } else if (v.getTag() instanceof Integer) {
        maxWidth = Math.max(maxWidth, (Integer) v.getTag());
      }
    }
    int extra = (padding + Screen.dp(INSET)) * 2;
    return Math.min(Screen.dp(MAX_WIDTH + PADDING * 2), Math.max(getMinimumWidth(), maxWidth + extra));
  }

  private boolean shouldPivotBottom;

  public void setShouldPivotBottom (boolean shouldPivotBottom) {
    this.shouldPivotBottom = shouldPivotBottom;
  }

  @Override
  public boolean shouldPivotBottom () {
    return shouldPivotBottom;
  }

  @Override
  public int getItemsHeight () {
    int itemHeight = Screen.dp(ITEM_HEIGHT);
    int padding = Screen.dp(PADDING);
    int total = 0;
    int childCount = getChildCount();
    for (int i = 0; i < childCount; i++) {
      View v = getChildAt(i);
      if (v != null && v.getVisibility() != View.GONE) {
        ViewGroup.LayoutParams params = v.getLayoutParams();
        total += params != null && params.height > 0 ? params.height : itemHeight;
      }
    }
    return total + (padding + Screen.dp(INSET)) * 2;
  }

  private @Nullable Runnable pendingAction;

  @Override
  public void runOnceViewBecomesReady (View view, Runnable action) {
    pendingAction = action;
  }

  @Override
  protected void onLayout (boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    if (anchorMode == ANCHOR_MODE_RECT) {
      layoutAtAnchor();
    }
    if (pendingAction != null) {
      pendingAction.run();
      pendingAction = null;
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    int padding = Screen.dp(PADDING);
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getX() >= padding && e.getX() < getWidth() - padding && e.getY() >= padding && e.getY() < getHeight() - padding) {
      return true; // Container (headers, gaps): not a tap outside.
    }
    return super.onTouchEvent(e);
  }

  private final FactorAnimator factorAnimator = new FactorAnimator(0, (a, b, c, d) -> invalidate(), AnimatorUtils.DECELERATE_INTERPOLATOR, 250L);
  private int lastSelectedIndex = -1;

  public void processMoveEvent (View v, float x, float y, float startX, float startY) {
    int[] location = Views.getLocationOnScreen(v);
    int sourceX = location[0] + (int) x;
    int sourceY = location[1] + (int) y;
    location = Views.getLocationOnScreen(this);
    int innerX = sourceX - location[0];
    int innerY = sourceY - location[1];

    int index = Math.floorDiv(innerY - Screen.dp(PADDING + INSET), Screen.dp(ITEM_HEIGHT));
    setSelectedIndex(index == MathUtils.clamp(index, 0, getChildCount() - 1) ? index : -1);

    // Log.i("HAPTIC INNER", String.format("INDEX %d", index));
  }

  public void onApply () {
    if (lastSelectedIndex != -1) {
      View v = getChildAt(lastSelectedIndex);
      if (v != null) v.callOnClick();
    }
  }

  private void setSelectedIndex (int index) {
    if (index == lastSelectedIndex) return;
    if (lastSelectedIndex == -1 || index == -1) {
      factorAnimator.forceFactor(index);
    } else {
      factorAnimator.animateTo(index);
    }
    lastSelectedIndex = index;
    invalidate();
  }

  private int bubbleTailX = -1;

  public void setBubbleTailX (int bubbleTailX) {
    this.bubbleTailX = bubbleTailX;
    invalidate();
  }

  @Override
  protected void onDraw (Canvas canvas) {
    if (bubbleTailX > 0) {
      float cx = bubbleTailX;
      float cy = getMeasuredHeight() - Screen.dp(PADDING);
      canvas.save();
      canvas.rotate(45f, cx, cy);
      canvas.drawRect(
        cx - Screen.dp(4.5f),
        cy - Screen.dp(4.5f),
        cx + Screen.dp(4.5f),
        cy + Screen.dp(4.5f),
        Paints.fillingPaint(getContainerColor())
      );
      canvas.restore();
    }

    if (lastSelectedIndex != -1 && itemsLayout == this) {
      int childCount = getChildCount();
      int inset = Screen.dp(PADDING + INSET);
      for (int i = 0; i < childCount; i++) {
        final float alpha = MathUtils.clamp(1f - Math.abs(factorAnimator.getFactor() - i));
        View child = getChildAt(i);
        if (alpha > 0f && child != null && child.getVisibility() == View.VISIBLE) {
          // M3 pressed state layer: rounded plaque inside the container (onSurface 10%).
          RectF rectF = Paints.getRectF();
          rectF.set(inset, child.getTop(), getMeasuredWidth() - inset, child.getBottom());
          float radius = Screen.dp(itemCornerRadius(child));
          canvas.drawRoundRect(rectF, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(alpha * M3.STATE_PRESSED, getOnContainerColor())));
        }
      }
    }


    super.onDraw(canvas);
  }

  // M3 container and item state layer

  @ColorInt int getContainerColor () {
    return M3.get(M3.SURFACE_CONTAINER_LOW, forcedTheme);
  }

  @ColorInt int getOnContainerColor () {
    return M3.get(M3.ON_SURFACE, forcedTheme);
  }

  /** Outer corners of the first/last visible row are concentric with the container (16 - 4 = 12dp), inner ones 4dp. */
  private float itemCornerRadius (View child) {
    return isEdgeItem(child, true) || isEdgeItem(child, false) ? M3.CORNER_MEDIUM : M3.CORNER_EXTRA_SMALL;
  }

  /** Whether no visible row (item, header or divider) follows {@code child} towards the container's top/bottom edge. */
  private static boolean isEdgeItem (View child, boolean first) {
    if (!(child.getParent() instanceof ViewGroup)) {
      return false;
    }
    ViewGroup parent = (ViewGroup) child.getParent();
    int count = parent.getChildCount();
    int index = parent.indexOfChild(child);
    int step = first ? -1 : 1;
    for (int i = index + step; i >= 0 && i < count; i += step) {
      if (parent.getChildAt(i).getVisibility() != View.GONE) {
        return false;
      }
    }
    return true;
  }

  private void setItemStateLayer (View item) {
    int color = ColorUtils.alphaColor(M3.STATE_PRESSED, getOnContainerColor());
    // Rounded plaque inside the container; M3StateLayerDrawable is a RippleDrawable on API 21+ and a plain fill below.
    ViewUtils.setBackground(item, new M3StateLayerDrawable(null, new ItemMaskDrawable(item), color));
    if (themeListeners != null && forcedTheme == null) {
      themeListeners.addThemeInvalidateListener(item);
    }
  }

  /** Mask of the item state layer: first/last rows get 12dp outer corners, the rest 4dp. */
  private static final class ItemMaskDrawable extends Drawable {
    private final View item;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private final android.graphics.Path path = new android.graphics.Path();

    ItemMaskDrawable (View item) {
      this.item = item;
      paint.setColor(0xffffffff);
    }

    @Override
    public void draw (@NonNull Canvas c) {
      float small = Screen.dp(M3.CORNER_EXTRA_SMALL), large = Screen.dp(M3.CORNER_MEDIUM);
      float top = isEdgeItem(item, true) ? large : small;
      float bottom = isEdgeItem(item, false) ? large : small;
      radii[0] = radii[1] = radii[2] = radii[3] = top;
      radii[4] = radii[5] = radii[6] = radii[7] = bottom;
      rect.set(getBounds());
      path.reset();
      path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW);
      c.drawPath(path, paint);
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

  /** Container: rounded rect inset by {@link #PADDING} (the margin keeps the shadow and the anchoring math). */
  private static final class ContainerDrawable extends Drawable {
    private final MenuMoreWrap menu;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    ContainerDrawable (MenuMoreWrap menu) {
      this.menu = menu;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      int margin = Screen.dp(PADDING);
      rect.set(getBounds());
      rect.inset(margin, margin);
      float radius = Screen.dp(M3.CORNER_LARGE);
      paint.setColor(menu.getContainerColor());
      c.drawRoundRect(rect, radius, radius, paint);
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

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    complexAvatarReceiver.attach();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    complexAvatarReceiver.detach();
  }
}