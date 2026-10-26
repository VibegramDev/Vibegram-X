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
 * File created on 02/02/2016 at 18:42
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Anim;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.M3FabView;
import org.thunderdog.challegram.widget.NoScrollTextView;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Material 3 Expressive FAB menu: a toggle FAB that morphs into a close button and
 * a stack of pill-shaped menu items above it (FabMenuBaselineTokens, m3.material.io).
 */
public class OverlayButtonWrap extends FrameLayoutFix implements View.OnClickListener, FactorAnimator.Target, Destroyable {
  /** Menu items expand along the y axis from the FAB (M3: "Scale and z-axis motion is avoided"). */
  private static final float ITEM_ENTER_OFFSET = 16f;
  /** FAB margin from the window edges (FloatingToolbar/FAB placement: 16dp). */
  private static final float FAB_MARGIN = 16f;
  /** Space between the close button and the first menu item. */
  private static final float CLOSE_BUTTON_SPACE = 8f;
  /** FabMenuBaselineTokens.ListItemBetweenSpace */
  private static final float ITEM_SPACE = 4f;
  private static final float ITEM_HEIGHT = 56f;
  private static final float ITEM_LEADING_SPACE = 24f;
  private static final float ITEM_TRAILING_SPACE = 24f;
  private static final float ITEM_ICON_LABEL_SPACE = 8f;

  private Callback callback;
  private M3FabView mainButton;
  private @DrawableRes int mainButtonIcon;
  private @Nullable StateListener stateListener;
  private final FactorAnimator animator;
  private static final int EFFECTS_ANIMATOR_ID = 2;
  private final FactorAnimator effectsAnimator;

  public OverlayButtonWrap (Context context) {
    super(context);

    // FAB menu motion: expressive fast spatial (position) + clamped alpha (effects, no overshoot).
    this.animator = new FactorAnimator(0, this, M3.FAST_SPATIAL, M3.FAST_SPATIAL.duration);
    this.effectsAnimator = new FactorAnimator(EFFECTS_ANIMATOR_ID, this, M3.FAST_EFFECTS, M3.FAST_EFFECTS.duration);

    ViewUtils.setBackground(this, new OverlayBackground(this));
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  /**
   * Color ids are kept for compatibility, Material 3 color roles are used instead:
   * primaryContainer for the FAB and the menu items, primary for the close button.
   */
  public void initWithList (final @NonNull ViewController<?> parent, int overlayColorId, int overlayIconColorId, int[] ids, int[] resources, int[] backgrounds, int[] colors, int[] items, boolean reverse) {
    if (reverse) {
      for (int i = ids.length - 1, j = 0; i >= 1; i--, j++) {
        addButton(parent, j, ids[i], resources[i], items[i - 1]);
      }
    } else {
      for (int i = 1; i < ids.length; i++) {
        addButton(parent, i - 1, ids[i], resources[i], items[i - 1]);
      }
    }
    addMainButton(parent, ids[0], resources[0]);
  }

  @IdRes
  public int getMainButtonId () {
    return mainButton != null ? mainButton.getId() : ResourcesCompat.ID_NULL;
  }

  public void replaceMainButton (@IdRes int id, @DrawableRes int icon) {
    mainButton.setId(id);
    mainButton.setIcon(icon);
    if (mainButtonIcon != icon) {
      mainButtonIcon = icon;
      if (stateListener != null) {
        stateListener.onMainButtonIconChanged(icon);
      }
    }
  }

  @DrawableRes
  public int getMainButtonIcon () {
    return mainButtonIcon;
  }

  public boolean isHidden () {
    return hidden;
  }

  public interface StateListener {
    void onMainButtonIconChanged (@DrawableRes int icon);
    void onHiddenChanged (boolean hidden);
    void onOpenFactorChanged (float factor);
  }

  public void setStateListener (@Nullable StateListener listener) {
    this.stateListener = listener;
  }

  private boolean mainButtonDetached;

  /**
   * Hides the main button while keeping the menu: the button is rendered elsewhere (phone dock),
   * and the menu opens above the place where the main button would be.
   */
  public void setMainButtonDetached (boolean detached) {
    if (this.mainButtonDetached != detached) {
      this.mainButtonDetached = detached;
      mainButton.setVisibility(detached ? View.INVISIBLE : View.VISIBLE);
    }
  }

  // Views

  private int endGravity () {
    return (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM;
  }

  private void addMainButton (@NonNull ViewController<?> parent, int id, int resource) {
    int size = Screen.dp(M3FabView.SIZE);
    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(size, size, endGravity());
    params.rightMargin = params.leftMargin = params.bottomMargin = Screen.dp(FAB_MARGIN);

    M3FabView button = new M3FabView(getContext());
    button.setIcon(resource);
    button.setId(id);
    button.setOnClickListener(this);
    button.setLayoutParams(params);
    parent.addThemeInvalidateListener(button);

    mainButtonIcon = resource;
    addView(mainButton = button);
  }

  public void updateRtl () {
    int gravity = endGravity();
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      if (view == null)
        continue;
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
      if (params.gravity != gravity) {
        params.gravity = gravity;
        view.setLayoutParams(params);
      }
    }
  }

  private int menuOffset;

  /**
   * Extra space between the main button bounds and the menu: when the main button is rendered by
   * a container around it (phone dock), the menu opens above that container and aligns with its edge.
   */
  public void setMenuOffset (int offset) {
    if (this.menuOffset == offset) {
      return;
    }
    this.menuOffset = offset;
    for (int index = 0; index < getChildCount(); index++) {
      View view = getChildAt(index);
      if (view == mainButton) {
        continue;
      }
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
      params.leftMargin = params.rightMargin = itemSideMargin();
      params.bottomMargin = itemBottomMargin(index);
      view.setLayoutParams(params);
    }
  }

  private int itemSideMargin () {
    return Math.max(0, Screen.dp(FAB_MARGIN) - menuOffset);
  }

  private int itemBottomMargin (int index) {
    return Screen.dp(FAB_MARGIN) + Screen.dp(M3FabView.SIZE) + menuOffset + Screen.dp(CLOSE_BUTTON_SPACE) + (Screen.dp(ITEM_HEIGHT) + Screen.dp(ITEM_SPACE)) * index;
  }

  private void addButton (@NonNull ViewController<?> parent, int index, int id, int resource, int stringRes) {
    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(ITEM_HEIGHT), endGravity());
    params.leftMargin = params.rightMargin = itemSideMargin();
    params.bottomMargin = itemBottomMargin(index);

    MenuItemView item = new MenuItemView(getContext(), resource);
    item.setId(id);
    item.setOnClickListener(this);
    item.setText(Lang.getString(parent.bindLocaleChanger(stringRes, item, false, false)));
    item.setLayoutParams(params);
    parent.addThemeInvalidateListener(item);

    if (easeFactor == 0f) {
      item.setEnabled(false);
      item.setTranslationY(Screen.dp(ITEM_ENTER_OFFSET));
      item.setAlpha(0f);
    }

    addView(item, index);
  }

  /** FAB menu item: 56dp pill, primaryContainer, 24dp icon, TitleMedium label, Level3. */
  private static final class MenuItemView extends NoScrollTextView {
    private final Drawable icon;
    private final GradientDrawable rippleMask;
    private final RippleDrawable ripple;
    private int textColor, rippleColor;

    MenuItemView (Context context, @DrawableRes int iconRes) {
      super(context);
      icon = Drawables.get(getResources(), iconRes).mutate();
      int iconSize = Screen.dp(24f);
      icon.setBounds(0, 0, iconSize, iconSize);
      setCompoundDrawablesRelative(icon, null, null, null);
      setCompoundDrawablePadding(Screen.dp(ITEM_ICON_LABEL_SPACE));
      setGravity(Gravity.CENTER_VERTICAL);
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
      setTypeface(Fonts.getRobotoMedium());
      setLetterSpacing(.15f / 16f);
      setSingleLine(true);
      setEllipsize(TextUtils.TruncateAt.END);
      setPaddingRelative(Screen.dp(ITEM_LEADING_SPACE), 0, Screen.dp(ITEM_TRAILING_SPACE), 0);
      setWillNotDraw(false);
      rippleMask = new GradientDrawable();
      rippleMask.setColor(0xFFFFFFFF);
      rippleMask.setCornerRadius(Screen.dp(ITEM_HEIGHT) / 2f);
      rippleColor = computeRippleColor();
      ripple = new RippleDrawable(ColorStateList.valueOf(rippleColor), null, rippleMask);
      setForeground(ripple);
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
        }
      });
      setClipToOutline(true);
      setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
      updateColors();
    }

    private static int computeRippleColor () {
      return ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onPrimaryContainer());
    }

    private void updateColors () {
      int color = M3.onPrimaryContainer();
      if (color != textColor) {
        textColor = color;
        setTextColor(color);
        setCompoundDrawableTintList(ColorStateList.valueOf(color));
      }
      int newRippleColor = computeRippleColor();
      if (newRippleColor != rippleColor) {
        rippleColor = newRippleColor;
        ripple.setColor(ColorStateList.valueOf(newRippleColor));
      }
    }

    @Override
    protected void onDraw (Canvas c) {
      updateColors();
      c.drawColor(M3.primaryContainer());
      super.onDraw(c);
    }

    @Override
    public boolean onTouchEvent (MotionEvent e) {
      return !(e.getAction() == MotionEvent.ACTION_DOWN && getAlpha() == 0f) && super.onTouchEvent(e);
    }
  }

  @Override
  public boolean onInterceptTouchEvent (MotionEvent ev) {
    return !Views.isValid(this) || super.onInterceptTouchEvent(ev);
  }

  private boolean isDestroyed;

  @Override
  public void performDestroy () {
    this.isDestroyed = true;
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      if (view instanceof Destroyable) {
        ((Destroyable) view).performDestroy();
      }
    }
  }

  // Openers

  private boolean isOpen;

  public void hideIfShowing () {
    if (isOpen) {
      toggle();
    }
  }

  public void toggle () {
    if (!isOpen && (hidden || forceHidden || UI.getContext(getContext()).isNavigationBusy())) {
      return;
    }
    isOpen = !isOpen;
    if (Views.HARDWARE_LAYER_ENABLED) {
      if (isOpen && animator.getFactor() == 0f) {
        setChildrenLayerType(LAYER_TYPE_HARDWARE);
      }
    }
    animator.animateTo(isOpen ? 1f : 0f);
    effectsAnimator.animateTo(isOpen ? 1f : 0f);
  }

  private void setChildrenLayerType (int layerType) {
    for (int i = 0; i < getChildCount() - 1; i++) {
      Views.setLayerType(getChildAt(i), layerType);
    }
  }

  public void close () {
    if (isOpen) {
      toggle();
    }
  }

  public void show () {
    if (mainButton != null && hidden && !forceHidden) {
      hidden = false;
      animateHideFactor(0f);
      if (stateListener != null) {
        stateListener.onHiddenChanged(false);
      }
    }
  }

  private boolean hidden;
  private float hideFactor;
  private FactorAnimator hideAnimator;

  private void setHideFactor (float factor) {
    factor = Anim.anticipateRange(factor);
    if (this.hideFactor != factor) {
      this.hideFactor = factor;
      mainButton.setTranslationY((float) (Screen.dp(FAB_MARGIN) * 2 + mainButton.getMeasuredHeight() + getPaddingBottom()) * factor);
      mainButton.setAlpha(factor < 1f ? 1f : 0f);
    }
  }

  private void animateHideFactor (float toFactor) {
    if (hideAnimator == null) {
      hideAnimator = new FactorAnimator(1, this, AnimatorUtils.ANTICIPATE_OVERSHOOT_INTERPOLATOR, 440l, this.hideFactor);
    }
    hideAnimator.animateTo(toFactor);
  }

  private void forceHideFactor (float factor) {
    if (hideAnimator != null) {
      hideAnimator.forceFactor(factor);
    }
    setHideFactor(factor);
  }

  public void hide () {
    if (mainButton != null && !hidden) {
      hideIfShowing();
      hidden = true;
      animateHideFactor(1f);
      if (stateListener != null) {
        stateListener.onHiddenChanged(true);
      }
    }
  }

  private boolean forceHidden;

  public void forceHide () {
    if (mainButton != null && !hidden) {
      hide();
      forceHidden = true;
    } else {
      forceHidden = false;
    }
  }

  public void showIfWasHidden () {
    if (forceHidden) {
      forceHidden = false;
      show();
    }
  }

  // Animation

  private float easeFactor;
  /** Spatial open factor (may overshoot): FAB shape, item positions. easeFactor is the effects factor: colors, alpha, scrim. */
  private float spatialFactor;

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (id == 1) {
      setHideFactor(factor);
      return;
    }
    if (id == EFFECTS_ANIMATOR_ID) {
      easeFactor = MathUtils.clamp(factor);
      if (stateListener != null) {
        stateListener.onOpenFactorChanged(easeFactor);
      }
      mainButton.setColorFactor(easeFactor);
      HeaderView headerView = UI.getHeaderView(getContext());
      if (headerView != null) {
        headerView.setOverlayColor(calculateOverlayColor());
      }
    } else {
      spatialFactor = factor;
      mainButton.setOpenFactor(MathUtils.clamp(factor));
    }
    updateMenuItems();
    invalidate();
  }

  /** Menu items, staggered from the FAB upwards: y offset follows the spatial spring, alpha the effects spring. */
  private void updateMenuItems () {
    final int itemCount = getChildCount() - 1;
    final float stepFactor = itemCount > 0 ? 1f / (float) itemCount * .6f : 0f;
    for (int index = 0; index < itemCount; index++) {
      final float startFactor = stepFactor * index;
      final float range = 1f - startFactor;
      final float itemSpatial = range > 0f ? Math.max(0f, (spatialFactor - startFactor) / range) : spatialFactor;
      final float itemAlpha = MathUtils.clamp(range > 0f ? (easeFactor - startFactor) / range : easeFactor);

      View v = getChildAt(index);
      // Clickable only when open and fully faded in; disabled immediately on close (no flicker on spring overshoot).
      v.setEnabled(isOpen && easeFactor >= 1f);
      v.setTranslationY(Screen.dp(ITEM_ENTER_OFFSET) * (1f - itemSpatial));
      //noinspection Range
      v.setAlpha(itemAlpha);
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (id == EFFECTS_ANIMATOR_ID) {
      return;
    }
    if (Views.HARDWARE_LAYER_ENABLED) {
      if (finalFactor == 0f) {
        setChildrenLayerType(LAYER_TYPE_NONE);
      }
    }
    UI.getContext(getContext()).notifyBackPressAvailabilityChanged();
  }

  // Scrim

  private int calculateOverlayColor () {
    if (easeFactor != 0f) {
      int color = ColorUtils.fromToArgb(ColorUtils.color(0, Theme.overlayColor()), Theme.overlayColor(), easeFactor);
      if (Color.alpha(color) != 0) {
        return color;
      }
    }
    return 0;
  }

  private static class OverlayBackground extends Drawable {
    private final OverlayButtonWrap wrap;

    public OverlayBackground (OverlayButtonWrap wrap) {
      this.wrap = wrap;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      int color = wrap.calculateOverlayColor();
      if (color != 0) {
        c.drawColor(color);
      }
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (ColorFilter colorFilter) { }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity () {
      return PixelFormat.UNKNOWN;
    }
  }

  // Touch stuff

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      close();
    }
    return Views.isValid(this) && (super.onTouchEvent(e) || animator.getFactor() != 0f);
  }

  public boolean isShowing () {
    return animator.isAnimating() || animator.getFactor() != 0f;
  }

  // Callback stuff

  @Override
  public void onClick (View v) {
    if (!hidden) {
      invokeCallback(v.getId(), v);
    }
  }

  private void invokeCallback (int id, View v) {
    if (callback != null) {
      if (callback.onOverlayButtonClick(id, v)) {
        close();
      }
    }
  }

  public void setCallback (Callback callback) {
    this.callback = callback;
  }

  public interface Callback {
    boolean onOverlayButtonClick (int id, View view);
  }
}
