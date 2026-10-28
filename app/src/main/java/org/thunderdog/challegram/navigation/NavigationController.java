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
 * File created on 23/04/2015 at 17:36
 */
package org.thunderdog.challegram.navigation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.BackEventCompat;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.support.SimpleShapeDrawable;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeListenerList;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.ui.MainController;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.unsorted.Size;
import org.thunderdog.challegram.widget.ShadowView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.annotation.MadeByLLM;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Future;

public class NavigationController implements Future<View>, ThemeChangeListener, Lang.Listener, SystemBackEventListener {
  static final boolean DROP_SHADOW_ENABLED = true;
  static final boolean USE_PREVIEW_FADE = true;
  static final boolean USE_LAYOUT_LIMITS = false;

  static final int SLOW_HARD_TRANSITION_DURATION = 500;
  static final int HARD_TRANSITION_DURATION = 400; // 400; //400

  static final int MINIMUM_DROP_DURATION = 60; //60
  static final int MAXIMUM_DROP_DURATION = 200; //200

  static final int MINIMUM_VERTICAL_DROP_DURATION = 160;
  static final int MAXIMUM_VERTICAL_DROP_DURATION = 300;

  static final float PREVIEW_FADED_FACTOR       = .92f;
  static final float PREVIEW_FADE_CHANGE_FACTOR = .08f;

  static final boolean USE_PREVIEW_TRANSLATION = true;

  private NavigationProcessor processor;

  private RootLayout rootView;
  private TabletNavigationLayout tabletLayout;
  private NavigationLayout contentWrapper;
  private HeaderView headerView;
  private ShadowView shadowView, shadowTop;
  private View fadeView;
  private FloatingButton floatingButton;

  private boolean isAnimating;

  private ArrayList<ViewController<?>> childWrappers;
  private final Context context;

  public NavigationController (Context context) {
    this.context = context;
    processor = new NavigationProcessor(this);
  }

  public Context getContext () {
    return context;
  }

 /* @Override
  public void onActivityPause () {
    if (childWrappers != null) {
      for (ViewController c : childWrappers) {
        c.onActivityPause();
      }
    }
  }

  @Override
  public void onActivityResume () {
    if (childWrappers != null) {
      for (ViewController c : childWrappers) {
        c.onActivityResume();
      }
    }
  }

  @Override
  public void onActivityDestroy () {
    if (childWrappers != null) {
      for (ViewController c : childWrappers) {
        c.onActivityDestroy();
      }
    }
  }

  @Override
  public void onActivityPermissionResult (int code, boolean granted) {
    if (childWrappers != null) {
      for (ViewController c : childWrappers) {
        c.onActivityPermissionResult(code, granted);
      }
    }
  }*/

  public void onFocus () {
    ViewController<?> c = getStack().getCurrent();
    if (c != null) {
      c.onFocus();
    }
  }

  public void onBlur () {
    ViewController<?> c = getStack().getCurrent();
    if (c != null) {
      c.onBlur();
    }
  }

  public void onPrepareToShow () {
    ViewController<?> c = getStack().getCurrent();
    if (c != null) {
      c.onPrepareToShow();
    }
  }

  public NavigationStack getStack () {
    return processor.getStack();
  }

  public int getStackSize () {
    return processor.getStackSize();
  }

  public @Nullable ViewController<?> getCurrentStackItem () {
    return getStack().getCurrent();
  }

  public @Nullable ViewController<?> getPreviousStackItem () {
    return getStack().getPrevious();
  }

  public boolean isAnimating () {
    return isAnimating;
  }

  public boolean isAnimatingWithEffect () {
    return isAnimating && translationMode != TRANSLATION_NONE;
  }

  public boolean isAnimatingBackward () {
    return isAnimating && !translatingForward && lastTranslation != 0f && lastTranslation != 1f && animatorRunning;
  }

  public boolean isEmpty () {
    return processor.getStack().size() == 0;
  }

  public HeaderView getHeaderView () {
    return headerView;
  }

  public HeaderView getHeaderViewFor (ViewController<?> controller) {
    if (isTabletSection(controller)) {
      return tabletLayout.getSectionHeader();
    }
    return isTabletMaster(controller) ? tabletLayout.getMasterHeader() : headerView;
  }

  public boolean isTabletMaster (ViewController<?> controller) {
    return tabletLayout != null && tabletLayout.getMain() == controller;
  }

  /** Dock section shown in the left pane instead of the chat list. */
  public boolean isTabletSection (ViewController<?> controller) {
    return controller != null && tabletLayout != null && tabletLayout.getSection() == controller;
  }

  /** Lives in the left pane: the chat list or the dock section. */
  private boolean isTabletPane (ViewController<?> controller) {
    return isTabletMaster(controller) || isTabletSection(controller);
  }

  public boolean isTabletSplit () {
    return tabletLayout != null && tabletLayout.isSplit();
  }

  public int getContentWidth () {
    return isTabletSplit() ? tabletLayout.getDetailWidth() : rootView.getMeasuredWidth();
  }

  /** Whether the chat is opened in the right pane (also while its profile or another child screen is on top). */
  public boolean isTabletChatOpened (Tdlib tdlib, long chatId) {
    if (!isTabletSplit()) {
      return false;
    }
    NavigationStack stack = getStack();
    for (int i = stack.size() - 1; i > 0; i--) {
      ViewController<?> c = stack.get(i);
      if (c instanceof MessagesController) {
        return c.tdlib() == tdlib && ((MessagesController) c).getChatId() == chatId;
      }
    }
    return false;
  }

  public @Nullable ViewController<?> getDrawerController () {
    return isTabletSplit() ? tabletLayout.getMain() : getCurrentStackItem();
  }

  /** The main screen shows or hides its chat folders rail in the left pane. */
  public void updateTabletFolderRail () {
    if (tabletLayout != null) {
      tabletLayout.updateFolderRail();
    }
  }

  void updateTabletLayout (int listWidth) {
    if (isAnimating || isDestroyed) {
      return;
    }
    ViewController<?> first = getStack().get(0);
    MainController target = listWidth > 0 && first instanceof MainController && !first.isDestroyed() ? (MainController) first : null;
    MainController previous = tabletLayout.getMain();
    if (previous == target) {
      return;
    }
    if (previous != null) {
      if (previous.isFocused() && previous != getCurrentStackItem()) {
        previous.onBlur();
      }
      ViewController<?> section = tabletLayout.getSection();
      tabletLayout.clearMain();
      if (section != null && !section.isDestroyed()) {
        // Single pane again: the dock section is a regular screen of the stack.
        if (section == getCurrentStackItem()) {
          TabletNavigationLayout.resetTransition(section.getValue());
          addChildWrapper(section);
          headerView.setTitle(section);
          headerView.resetTitleTransition(section);
        } else if (section.isFocused()) {
          section.onBlur();
        }
      }
      if (!previous.isDestroyed()) {
        if (previous == getCurrentStackItem()) {
          contentWrapper.addView(previous.getValue());
          previous.attachNavigationController(this);
          headerView.setTitle(previous);
          // Its app bar title (the app name on the phone) may be left hidden by the last single-pane transition.
          headerView.resetTitleTransition(previous);
        } else {
          removeChildWrapper(previous);
        }
      }
    }
    if (target != null) {
      if (childWrappers == null || !childWrappers.contains(target)) {
        addChildWrapper(target, 0);
      }
      tabletLayout.setMain(target);
      ViewController<?> section = getStack().size() > 1 ? getStack().get(1) : null;
      if (section != null && section.isTopLevelDestination() && !section.isDestroyed()) {
        // Opened from the dock in the single pane layout: moves to the left pane.
        if (childWrappers != null && childWrappers.remove(section)) {
          contentWrapper.removeView(section.getValue());
        }
        tabletLayout.setSection(section);
        if (section != getCurrentStackItem() && !section.isFocused()) {
          section.onFocus();
        }
      }
      if (!target.isFocused()) {
        target.onFocus();
        ViewController<?> current = getCurrentStackItem();
        if (current != null && current != target && current.isFocused()) {
          current.getValue().requestFocus();
        }
      }
    }
    if (previous != null && !previous.isDestroyed()) {
      previous.checkNavigationBarLayout();
    }
    if (target != null) {
      target.checkNavigationBarLayout();
    }
  }

  /** Closes everything opened in the right pane, keeping the chat list. */
  public boolean closeTabletDetail () {
    if (!isTabletSplit() || isAnimating || getStackSize() < 2) {
      return false;
    }
    NavigationStack stack = getStack();
    while (stack.size() > 2) {
      ViewController<?> c = stack.remove(1);
      if (c != null && c == tabletLayout.getSection()) {
        tabletLayout.setSection(null);
        if (c.isFocused()) {
          c.onBlur();
        }
      }
      if (c != null) {
        c.attachNavigationController(this);
        c.destroy();
        c.detachNavigationController();
      }
    }
    return navigateBack();
  }

  private boolean navigateTablet (ViewController<?> controller, int mode) {
    if (!isTabletSplit()) {
      return false;
    }
    boolean forward = (mode & MODE_BACKWARD) == 0;
    boolean rebase = forward && (mode & MODE_REBASE) != 0;
    boolean saveFirst = (mode & MODE_ARG_SAVE_FIRST) != 0;
    ViewController<?> previous = forward ? getPreviousStackItem() : getCurrentStackItem();
    ViewController<?> oldSection = tabletLayout.getSection();
    // A dock section (opened from the bar, rebasing on the chat list) replaces the chat list in the left pane.
    boolean enterSection = rebase && saveFirst && controller.isTopLevelDestination();
    boolean leaveSection = !forward && previous != null && previous == oldSection;
    boolean previousInPane = previous != null && isTabletPane(previous);
    boolean defaultDetail = forward && !rebase && pendingTabletDefaultDetail && oldSection != null && previous == oldSection;
    pendingTabletDefaultDetail = false;
    if (previous != null && previous.isFocused() && !previousInPane) {
      previous.onBlur();
    }
    if (oldSection != null && (enterSection || leaveSection || (rebase && !saveFirst))) {
      if (oldSection != controller && oldSection.isFocused()) {
        oldSection.onBlur();
      }
      tabletLayout.setSection(enterSection ? controller : null);
    } else if (enterSection) {
      tabletLayout.setSection(controller);
    }
    if (!isTabletPane(controller)) {
      // A reused controller may keep the end state of a single-pane transition
      // (e.g. translated off-screen after closing a chat on the cover screen).
      TabletNavigationLayout.resetTransition(controller.getValue());
      addChildWrapper(controller);
    }
    if (previous != null && !previousInPane) {
      removeChildWrapper(previous);
    }
    if (!forward) {
      getStack().removeLast();
      if (previous != null) {
        previous.attachNavigationController(this);
        previous.destroy();
        previous.detachNavigationController();
      }
    } else if (rebase) {
      // Screens opened next to a dock section keep it in the left pane: [main, section, new screen].
      ViewController<?> section = tabletLayout.getSection();
      int keepCount = !saveFirst ? 0 : section != null && section != controller ? 2 : 1;
      getStack().reset(this, keepCount);
    }
    if (defaultDetail) {
      controller.setTabletDefaultDetail(true);
    }
    boolean inPane = isTabletPane(controller);
    if (!inPane) {
      headerView.setTitle(controller);
      // No header animation here: show the title and the back button even if the last
      // single-pane transition faded them out (e.g. back from a chat to the list on the cover screen).
      headerView.resetTitleTransition(controller);
    }
    if (inPane || HeaderView.getFloatingButtonId(controller) == 0) {
      // setTitle() only shows the shared floating button; hide the one left from the previous screen
      // (e.g. edit button of settings), as there's no switch animation in the split layout.
      floatingButton.setHeightFactor(0f, 0f, false);
    }
    tabletLayout.updateVisibility();
    if (!inPane) {
      // M3: the new detail screen enters with shared axis X, dock sections (and going back from them) with fade-through.
      // A rebase replaces the whole detail pane (another chat selected in the list): fade-through as well,
      // the panes have no forward/backward relationship. Pushes inside the pane: shared axis X.
      boolean fadeThrough = forward ? controller.isTopLevelDestination() || rebase || defaultDetail : previous != null && previous.isTopLevelDestination();
      tabletLayout.animateDetailEnter(Settings.instance().needReduceMotion() || fadeThrough ? 0 : forward ? 1 : -1);
    } else {
      tabletLayout.cancelDetailAnimation();
    }
    tabletLayout.invalidateMaster();
    MainController main = tabletLayout.getMain();
    if (main != null) {
      main.updateNavigationBarSelection();
    }
    tabletLayout.requestLayout();
    setIsAnimating(false);
    controller.executeAnimationReadyListeners();
    if (!controller.isFocused()) {
      controller.onFocus();
    }
    controller.onAfterShow();
    if (enterSection) {
      rootView.post(() -> {
        if (!controller.isDestroyed() && tabletLayout != null && tabletLayout.getSection() == controller && getCurrentStackItem() == controller) {
          controller.onOpenedAsTabletSection();
        }
      });
    }
    return true;
  }

  private boolean pendingTabletDefaultDetail;

  /**
   * Tablet split: the next screen opened from the dock section is its default right pane content
   * (e.g. the own profile next to Settings). It enters with fade-through, Back from it brings the chat list back.
   */
  public void prepareTabletDefaultDetail () {
    pendingTabletDefaultDetail = isTabletSplit() && tabletLayout.getSection() != null;
  }

  public void setShadowsVisibility (int visibility) {
    shadowTop.setVisibility(visibility);
    shadowView.setVisibility(visibility);
  }

  public FloatingButton getFloatingButton () {
    return floatingButton;
  }

  public void showFadeView () {
    if (fadeView.getParent() != null) {
      contentWrapper.removeView(fadeView);
    }
    fadeView.setVisibility(View.VISIBLE);
    contentWrapper.addView(fadeView, 1);
  }

  public void removeFadeView () {
    if (USE_PREVIEW_FADE) {
      fadeView.setVisibility(View.GONE);
      contentWrapper.removeView(fadeView);
    }
  }

  protected final void clearChildWrappers () {
    if (childWrappers != null) {
      childWrappers.clear();
      childWrappers = null;
    }
  }

  public void addChildWrapper (ViewController<?> controller) {
    if (childWrappers == null) {
      childWrappers = new ArrayList<>();
    }
    childWrappers.add(controller);

    controller.applyPlayerOffset(currentPlayerFactor, currentPlayerOffset);
    if (controller.usePopupMode()) {
      if (controller.preventRootInteractions()) {
        rootView.addView(controller.getValue(), 0);
      } else {
        rootView.addView(controller.getValue(), rootView.getChildCount() - 2);
      }
    } else {
      contentWrapper.addView(controller.getValue());
    }
    controller.attachNavigationController(this);
    syncKeyboardState(controller); // @MadeByLLM("opus-5.5")
    controller.onPrepareToShow();
    controller.onAttachStateChanged(this, true);
    checkDisallowScreenshots();
  }

  public void addChildWrapper (ViewController<?> controller, int index) {
    if (childWrappers == null) {
      childWrappers = new ArrayList<>();
    }
    childWrappers.add(index, controller);

    if (controller.usePopupMode()) {
      rootView.addView(controller.getValue(), 0);
    } else {
      contentWrapper.addView(controller.getValue(), index);
    }
    controller.attachNavigationController(this);
    syncKeyboardState(controller); // @MadeByLLM("opus-5.5")
    controller.onPrepareToShow();
    controller.onAttachStateChanged(this, true);
    checkDisallowScreenshots();
  }

  @MadeByLLM(value = "opus-5.5", id = "stale-keyboard-state-fix")
  private void syncKeyboardState (ViewController<?> controller) {
    if (controller.getKeyboardState() && !UI.getContext(context).isKeyboardVisible()) {
      controller.onKeyboardStateChanged(false);
    }
  }

  public void removeChildWrapper (ViewController<?> controller) {
    if (isTabletMaster(controller)) {
      tabletLayout.clearMain();
    }
    if (controller == null)
      throw new NullPointerException();

    if (childWrappers != null) {
      childWrappers.remove(controller);
    }

    if (controller.usePopupMode()) {
      rootView.removeView(controller.getValue());
    } else {
      contentWrapper.removeView(controller.getValue());
    }
    controller.onCleanAfterHide();
    controller.detachNavigationController();
    controller.onAttachStateChanged(this, false);
    checkDisallowScreenshots();
  }

  public void removeChildren () {
    if (tabletLayout != null) {
      tabletLayout.clearMain();
    }
    if (childWrappers != null) {
      for (int i = childWrappers.size() - 1; i >= 0; i--) {
        removeChildWrapper(childWrappers.get(i));
      }
    }

    contentWrapper.removeAllViews();
    checkDisallowScreenshots();
  }

  public void onKeyboardStateChanged (boolean visible) {
    if (childWrappers != null) {
      for (ViewController<?> c : childWrappers) {
        c.onKeyboardStateChanged(visible);
      }
    }
  }

  private void checkDisallowScreenshots () {
    UI.getContext(context).checkDisallowScreenshots();
  }

  public int getId () {
    return R.id.controller_navigation;
  }

  private View wrap;
  private boolean isAttachedToWindow;

  @Override
  public View getValue () {
    if (wrap == null) {
      wrap = onCreateView(context);
      wrap.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow (View v) {
          isAttachedToWindow = true;
        }

        @Override
        public void onViewDetachedFromWindow (View v) {
          isAttachedToWindow = false;
          // TODO force end animations?
        }
      });
      wrap.setTag(this);
      ThemeManager.instance().addThemeListener(this);
    }
    return wrap;
  }

  public boolean shouldDisallowScreenshots () {
    if (childWrappers != null) {
      for (ViewController<?> controller : childWrappers) {
        if (controller.shouldDisallowScreenshots()) {
          return true;
        }
      }
    }
    return false;
  }

  protected View onCreateView (Context context) {
    rootView = new RootLayout(context);
    rootView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    rootView.setId(R.id.nav_root);

    if (DROP_SHADOW_ENABLED) {
      FrameLayoutFix.LayoutParams params;

      params = FrameLayoutFix.newParams(Size.getNavigationShadowSize(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
      params.setMargins(0, HeaderView.getSize(true), 0, 0);

      shadowView = new ShadowView(context);
      shadowView.setLayoutParams(params);
      checkShadowDirection();
      if (needRtl()) {
        shadowView.setSimpleRightShadow(false);
      } else {
        shadowView.setSimpleLeftShadow(false);
      }
      shadowView.setVisibility(View.GONE);

      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Size.getNavigationShadowvertSize(), Gravity.TOP);

      shadowTop = new ShadowView(context);
      shadowTop.setSimpleTopShadow(true);
      shadowTop.setVisibility(View.GONE);
      shadowTop.setLayoutParams(params);
    }

    if (USE_PREVIEW_FADE) {
      fadeView = new View(context);
      fadeView.setBackgroundColor(0xff000000);
      fadeView.setVisibility(View.GONE);
      fadeView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    contentWrapper = new NavigationLayout(context);
    contentWrapper.setController(this);
    contentWrapper.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    contentWrapper.setId(R.id.nav_wrapper);

    headerView = new HeaderView(context);
    headerView.initWithController(this);
    headerView.setId(R.id.nav_header);

    rootView.addView(contentWrapper);

    if (DROP_SHADOW_ENABLED) {
      rootView.addView(shadowView);
      rootView.addView(shadowTop);
    }

    rootView.addView(headerView);

    floatingButton = new FloatingButton(context);
    floatingButton.setOnClickListener(v -> {
      ViewController<?> c = processor.getStack().getCurrent();
      if (c != null) {
        c.onFloatingButtonPressed();
      }
    });
    rootView.addView(floatingButton);

    Lang.addLanguageListener(this);

    tabletLayout = new TabletNavigationLayout(context, this, rootView);
    getStack().addChangeListener(stack -> tabletLayout.requestLayout());
    return tabletLayout;
  }

  public void addViewUnderHeader (View view) {
    rootView.addView(view, rootView.indexOfChild(headerView));
  }

  private ThemeListenerList themeListeners;

  public ThemeListenerList getThemeListeners () {
    if (themeListeners == null) {
      themeListeners = new ThemeListenerList();
    }
    return themeListeners;
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    ViewController<?> c;
    if (isAnimating && ((translatingForward && translationFactor == 1f) || (!translatingForward && translationFactor == 0f))) {
      c = getPreviousStackItem();
    } else {
      c = getCurrentStackItem();
    }
    if (c != null) {
      headerView.resetColors(c, null);
    }
    if (tabletLayout != null) {
      tabletLayout.updateColors();
    }
    if (themeListeners != null) {
      themeListeners.onThemeColorsChanged(areTemp);
    }
  }

  private boolean isDestroyed;

  public boolean isDestroyed () {
    return isDestroyed;
  }

  public void reuse () {
    getStack().clear(this);
    clearChildWrappers();
  }

  public void destroy () {
    isDestroyed = true;
    if (tabletLayout != null) {
      tabletLayout.clearMain();
    }
    ThemeManager.instance().removeThemeListener(this);
    getStack().clear(this);
    clearChildWrappers();
    Lang.removeLanguageListener(this);
    if (headerView != null)
      headerView.performDestroy();
    if (contentWrapper != null)
      contentWrapper.performDestroy();
    if (floatingButton != null)
      floatingButton.performDestroy();
  }

  // Single

  public void insertController (ViewController<?> controller, int index) {
    if (!isAnimating && controller != null) {
      processor.insertController(controller, index);
    }
  }

  public void initController (ViewController<?> with) {
    if (!isAnimating && with != null) {
      NavigationStack stack = getStack();

      with.postOnAnimationExecute(() ->
        with.addOneShotFocusListener(with.context()::markActivityReady)
      );

      with.getValue();

      stack.clear(this);
      stack.push(with, true);

      removeChildren();
      addChildWrapper(with);
      processor.callFocusDelayed(with);
      getHeaderView().setTitle(with);

      if (with.usePopupMode()) {
        with.applyCustomHeaderAnimations(1f);
      }
    }
  }

  public final void setController (ViewController<?> controller) {
    if (!isAnimating && controller != null) {
      if (getStack().isEmpty()) {
        initController(controller);
      } else {
        processor.setController(controller);
      }
    }
  }

  public ViewController<?> getPendingController () {
    return processor.getFutureRebaseController();
  }

  public void setControllerAnimated (ViewController<?> controller, boolean asForward, boolean saveFirst) {
    if (controller != null) {
      clearStackLock();
      if (getStack().isEmpty()) {
        initController(controller);
      } else {
        processor.setControllerAnimated(controller, asForward, saveFirst);
      }
    }
  }

  void rebaseStack (final ViewController<?> item, boolean asForward, final boolean saveFirst) {
    if (asForward) {
      navigate(item, saveFirst ? MODE_REBASE | MODE_ARG_SAVE_FIRST : MODE_REBASE);
    } else {
      navigate(item, saveFirst ? MODE_REBASE | MODE_ARG_SAVE_FIRST | MODE_FADE : MODE_REBASE | MODE_FADE);
    }
  }

  // Pairs

  public boolean passBackPressToActivity (boolean fromTop) {
    ViewController<?> c = getStack().getCurrent();
    return c != null && c.needPassBackPressToActivity(fromTop);
  }

  public @BackPressMode int performOnBackPressed (boolean fromTop, boolean commit) {
    HeaderView masterHeader = isTabletSplit() ? tabletLayout.getMasterHeader() : null;
    if (masterHeader != null && (masterHeader.inSelectMode() || masterHeader.inSearchMode())) {
      if (commit) {
        if (masterHeader.inSelectMode()) {
          masterHeader.closeSelectMode(true, true);
        } else {
          masterHeader.closeSearchMode(true, null);
        }
      }
      return BackPressMode.CUSTOM_ACTION_PERFORMED;
    }
    if (headerView.inSelectMode()) {
      if (commit) {
        headerView.closeSelectMode(true, true);
      }
      return BackPressMode.CUSTOM_ACTION_PERFORMED;
    }
    ViewController<?> c = getStack().getCurrent();
    if (headerView.inSearchMode()) {
      if (c != null && c.closeSearchModeByBackPress(fromTop, commit)) {
        return BackPressMode.CUSTOM_ACTION_PERFORMED;
      }
      if (commit) {
        headerView.closeSearchMode(true, null); // delayed: (c != null && c.inChatSearchMode())
      }
      return BackPressMode.CUSTOM_ACTION_PERFORMED;
    }
    /*if (headerView.inCustomMode()) {
      headerView.closeCustomMode();
      return true;
    }*/
    if (c != null) {
      if (c.performOnBackPressed(fromTop, commit)) {
        return BackPressMode.CUSTOM_ACTION_PERFORMED;
      }
      if (getStackSize() > 1) {
        if (commit) {
          navigateBack();
        }
        return BackPressMode.NAVIGATE_BACK_IN_STACK;
      }
    }
    return BackPressMode.SYSTEM_ACTION_REQUIRED;
  }

  private boolean isCurrentControllerAnimating () {
    ViewController<?> c = getStack().getCurrent();
    return c != null && c.isTransforming();
  }

  final void hideContextualPopups () {
    UI.getContext(context).hideContextualPopups(true);
    DrawerController c = UI.getDrawer(context);
    if (c != null) {
      c.close(0f, null);
    }
  }

  private void clearStackLock () {
    if (getStack().isLocked()) {
      getStack().setIsLocked(false);
    }
  }

  public final boolean navigateTo (ViewController<?> controller) {
    if (!isAnimating && controller != null && isTabletSplit() && tabletLayout.getSection() != null
      && getStackSize() > 2 && tabletLayout.isLastTouchInMaster() && !isCurrentControllerAnimating()) {
      // Tablet split: a screen opened from the dock section in the left pane replaces the right pane.
      setControllerAnimated(controller, true, true);
      return true;
    }
    if (!isAnimating && getStackSize() > 0 && controller != null && !isCurrentControllerAnimating()) {
      isAnimating = true;
      UI.getContext(context).notifyBackPressAvailabilityChanged();
      clearStackLock();
      processor.navigateTo(controller);
      return true;
    } else {
      return false;
    }
  }

  public final boolean navigateBack () {
    if (!isAnimating && isTabletSplit() && tabletLayout.getSection() != null && getStackSize() == 3) {
      ViewController<?> current = getCurrentStackItem();
      if (current != null && current.isTabletDefaultDetail() && !isCurrentControllerAnimating()) {
        // The default right pane screen of the section (own profile next to Settings): back to the chat list.
        return closeTabletDetail();
      }
    }
    if (!isAnimating && getStack().getCurrentIndex() > 0 && !isCurrentControllerAnimating()) {
      processor.navigateBack();
      return true;
    } else {
      return false;
    }
  }

  private static final int TRANSLATION_HORIZONTAL = 1;
  private static final int TRANSLATION_VERTICAL = 2;
  private static final int TRANSLATION_FADE = 3;
  private static final int TRANSLATION_NONE = 4;
  /** M3 top-level transition request; prepareFactorAnimation turns it into TRANSLATION_FADE + {@link #m3FadeThrough}. */
  private static final int TRANSLATION_FADE_THROUGH = 5;

  private int translationMode;
  private boolean translatingForward;
  /**
   * M3 forward/backward ({@link M3#SHARED_AXIS_NAVIGATION}): horizontal transitions draw no shadow and no scrim.
   * Taps and buttons: shared axis X (both screens slide {@link M3#SHARED_AXIS_DISTANCE} of the width, the outgoing
   * screen fades out, then the incoming one fades in). Gestures ({@link #m3Gesture}): the screen follows the finger.
   */
  private boolean m3Horizontal, m3Gesture;
  /** M3 top-level destinations: fade-through (outgoing fades out, then incoming fades in), no movement. */
  private boolean m3FadeThrough;
  /** Set by swipe back / predictive back right before {@link #prepareFactorAnimation}. */
  private boolean nextTransitionIsGesture;
  private float sharedAxisDistance, lastRightTranslationX;
  private float fromHeaderHeight;
  private float currentWidth, currentHeight, currentPrevWidth;
  private float lastTranslation, lastHeaderFactor;
  private ViewController<?> currentLeft, currentRight;
  private View leftWrap, rightWrap;

  private void preventPopupMode (ViewController<?> current) {
    if (!current.preventRootInteractions()) {
      rootView.removeView(current.getValue());
      rootView.addView(current.getValue(), 0);
    }
    headerView.getFilling().setCollapsed(false);
  }

  private void applyPopupMode (ViewController<?> current) {
    if (!current.preventRootInteractions()) {
      rootView.removeView(current.getValue());
      rootView.addView(current.getValue(), rootView.getChildCount() - 2);
    }
    headerView.getFilling().setCollapsed(true);
  }

  private float currentPlayerFactor;
  private float currentPlayerOffset;

  protected boolean applyPlayerOffset (float factor, float top) {
    if (currentPlayerFactor != factor) {
      currentPlayerFactor = factor;
      currentPlayerOffset = top;
      if (childWrappers != null) {
        for (ViewController<?> c : childWrappers) {
          c.applyPlayerOffset(factor, top);
        }
      }
      return true;
    }
    return false;
  }

  private void prepareHeaderAnimation (ViewController<?> left, ViewController<?> right, boolean forward, int direction, boolean forceRtl) {
    lastHeaderFactor = forward ? 1f : 0f;
    if (m3FadeThrough || (m3Horizontal && !m3Gesture)) {
      // App bar contents follow the screens: clean fade, and the same slide distance for shared axis.
      headerView.setNextTransitionStyle(true, m3Horizontal ? M3.SHARED_AXIS_DISTANCE : 0f);
    }
    headerView.openPreview(left, right, forward, direction, lastHeaderFactor, forceRtl);

    if (!forward && right.usePopupMode()) {
      fromHeaderHeight = left.getHeaderHeight();
    } else {
      fromHeaderHeight = headerView.getCurrentHeight();
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    PredictiveGesture.NONE,
    PredictiveGesture.BACK_FROM_LEFT_EDGE,
    PredictiveGesture.BACK_FROM_RIGHT_EDGE
  })
  public @interface PredictiveGesture {
    int
      NONE = 0,
      BACK_FROM_LEFT_EDGE = 1,
      BACK_FROM_RIGHT_EDGE = 2;
  }

  public void prepareFactorAnimation (ViewController<?> left, ViewController<?> right, boolean forward, int direction, @PredictiveGesture int predictiveGestureMode) {
    preventLayout();
    if (Views.HARDWARE_LAYER_ENABLED) {
      setLayerType(View.LAYER_TYPE_HARDWARE, left, right);
    }

    boolean forceRtl = false;

    if (predictiveGestureMode != PredictiveGesture.NONE) {
      if (Settings.instance().needReduceMotion()) {
        direction = TRANSLATION_FADE;
      } else if (direction == TRANSLATION_HORIZONTAL && predictiveGestureMode == PredictiveGesture.BACK_FROM_RIGHT_EDGE) {
        forceRtl = !Settings.instance().getNewSetting(Settings.SETTING_FLAG_FORCE_DEFAULT_ANIMATION_FOR_RIGHT_SWIPE_EDGE);
      }
    }

    if (forceRtlAnimation != forceRtl) {
      forceRtlAnimation = forceRtl;
      checkShadowDirection();
    }

    boolean isGesture = nextTransitionIsGesture;
    nextTransitionIsGesture = false;
    m3FadeThrough = direction == TRANSLATION_FADE_THROUGH;
    if (m3FadeThrough) {
      direction = TRANSLATION_FADE;
    }
    // Popup-mode screens below keep the original slide: their app bar pixels are restored by the offset.
    m3Horizontal = M3.SHARED_AXIS_NAVIGATION && direction == TRANSLATION_HORIZONTAL && !left.usePopupMode();
    m3Gesture = m3Horizontal && isGesture;

    if (forward) {
      right.applyPlayerOffset(currentPlayerFactor, currentPlayerOffset);
      addChildWrapper(right);
      if (left.usePopupMode()) {
        preventPopupMode(left);
      }
    } else {
      left.applyPlayerOffset(currentPlayerFactor, currentPlayerOffset);
      addChildWrapper(left, 0);
    }

    if (USE_PREVIEW_FADE) {
      if (direction != TRANSLATION_FADE && direction != TRANSLATION_NONE && !left.usePopupMode() && !m3Horizontal) {
        showFadeView();
      }
    }

    translationFactor = forward ? 1f : 0f;

    currentLeft = left;
    currentRight = right;
    leftWrap = left.getValue();
    rightWrap = right.getValue();
    translationMode = direction;
    translatingForward = forward;

    currentWidth = rootView.getMeasuredWidth();
    currentHeight = rootView.getMeasuredHeight();
    currentPrevWidth = -(currentWidth / Size.NAVIGATION_PREVIEW_TRANSLATE_FACTOR);
    sharedAxisDistance = currentWidth * M3.SHARED_AXIS_DISTANCE;

    if (forward) {
      currentLeft.onBlur();

      switch (translationMode) {
        case TRANSLATION_NONE: {
          lastTranslation = 0;
          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(0f);
          rightWrap.setTranslationY(0f);
          rightWrap.setAlpha(1f);
          leftWrap.setAlpha(1f);
          headerView.getFilling().setRestorePixels(false, 0f, 0);
          break;
        }
        case TRANSLATION_FADE: {
          lastTranslation = 0;
          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(0f);
          rightWrap.setTranslationY(0f);
          rightWrap.setAlpha(0f);
          headerView.getFilling().setRestorePixels(false, 0f, 0);
          break;
        }
        case TRANSLATION_VERTICAL: {
          lastTranslation = currentHeight;

          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(0f);
          rightWrap.setTranslationY(currentHeight);

          headerView.getFilling().setRestorePixels(false, 0f, 0);

          if (USE_PREVIEW_FADE) {
            if (!left.usePopupMode()) {
              fadeView.setAlpha(0f);
              fadeView.setVisibility(View.VISIBLE);
            }
          }

          if (DROP_SHADOW_ENABLED) {
            shadowTop.setTranslationY(currentHeight - Size.getNavigationShadowvertSize());
            shadowTop.setAlpha(1f);
            shadowTop.setVisibility(right.useDropShadow() ? View.VISIBLE : View.GONE);
          }

          break;
        }
        case TRANSLATION_HORIZONTAL: {
          lastTranslation = currentWidth;
          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(currentWidth);
          rightWrap.setTranslationY(0f);
          rightWrap.setAlpha(1f);

          headerView.getFilling().setRestorePixels(left.usePopupMode(), 0f, left.getPopupRestoreColor());

          if (m3Horizontal) {
            applyM3Horizontal(translationFactor);
            break;
          }

          if (USE_PREVIEW_FADE) {
            if (!left.usePopupMode()) {
              fadeView.setAlpha(0f);
              fadeView.setVisibility(View.VISIBLE);
            }
          }

          if (DROP_SHADOW_ENABLED) {
            shadowView.setTranslationX(currentWidth - Size.getNavigationShadowSize());
            shadowView.setAlpha(PREVIEW_FADED_FACTOR);
            shadowView.setVisibility(View.VISIBLE);
          }

          break;
        }
      }
    } else {
      lastTranslation = 0f;
      currentRight.onBlur();

      leftWrap.setVisibility(View.VISIBLE);

      switch (translationMode) {
        case TRANSLATION_NONE: {
          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(0f);
          rightWrap.setTranslationY(0f);
          rightWrap.setAlpha(1f);
          leftWrap.setAlpha(1f);

          headerView.getFilling().setRestorePixels(false, 0f, 0);

          break;
        }
        case TRANSLATION_FADE: {
          leftWrap.setTranslationX(0f);
          rightWrap.setTranslationX(0f);
          rightWrap.setTranslationY(0f);
          if (m3FadeThrough) {
            // The incoming screen appears only after the outgoing one has faded out.
            leftWrap.setAlpha(0f);
            rightWrap.setAlpha(1f);
          }

          headerView.getFilling().setRestorePixels(false, 0f, 0);
          break;
        }
        case TRANSLATION_VERTICAL: {
          rightWrap.setTranslationY(0f);
          rightWrap.setTranslationX(0f);
          leftWrap.setTranslationX(0f);
          leftWrap.setTranslationY(0f);

          headerView.getFilling().setRestorePixels(false, 0f, 0);

          if (USE_PREVIEW_FADE) {
            if (!left.usePopupMode()) {
              fadeView.setAlpha(PREVIEW_FADE_CHANGE_FACTOR);
              fadeView.setVisibility(View.VISIBLE);
            }
          }

          if (DROP_SHADOW_ENABLED) {
            shadowTop.setAlpha(1f);
            shadowTop.setTranslationY(-Size.getNavigationShadowvertSize());
            shadowTop.setVisibility(right.useDropShadow() ? View.VISIBLE : View.GONE);
          }

          break;
        }
        case TRANSLATION_HORIZONTAL: {
          rightWrap.setTranslationX(0f);
          if (USE_PREVIEW_TRANSLATION) {
            leftWrap.setTranslationX(currentPrevWidth);
          } else {
            leftWrap.setTranslationX(0f);
          }
          leftWrap.setAlpha(1f);

          headerView.getFilling().setRestorePixels(left.usePopupMode(), -currentPrevWidth, left.getPopupRestoreColor());

          if (m3Horizontal) {
            applyM3Horizontal(translationFactor);
            break;
          }

          if (USE_PREVIEW_FADE) {
            if (!left.usePopupMode()) {
              fadeView.setAlpha(PREVIEW_FADE_CHANGE_FACTOR);
              fadeView.setVisibility(View.VISIBLE);
            }
          }

          if (DROP_SHADOW_ENABLED) {
            shadowView.setAlpha(1f);
            shadowView.setTranslationX(-Size.getNavigationShadowSize());
            shadowView.setVisibility(View.VISIBLE);
          }

          break;
        }
      }
    }

    prepareHeaderAnimation(left, right, forward, direction, forceRtlAnimation);

    layoutIfRequested();
    onAfterShow();

    preventNextLayouts(forward ? 1 : 2);
  }

  private void onAfterShow () {
    if (childWrappers != null) {
      int size = childWrappers.size();
      for (int i = size - 1; i >= 0; i--) {
        childWrappers.get(i).onAfterShow();
      }
    }
  }

  void navigate (final ViewController<?> controller, int mode) {
    hideContextualPopups();
    if (navigateTablet(controller, mode)) {
      return;
    }

    final boolean forward = (mode & MODE_BACKWARD) != MODE_BACKWARD;
    final boolean fastAnimation;
    int direction;

    if ((mode & MODE_FADE) == MODE_FADE) {
      direction = TRANSLATION_FADE;
      fastAnimation = false;
    } else {
      if (forward) {
        fastAnimation = controller.forceFastAnimation();
        direction = fastAnimation || controller.forceFadeMode() ? TRANSLATION_FADE : controller.usePopupMode() ? TRANSLATION_VERTICAL : TRANSLATION_HORIZONTAL;
      } else {
        ViewController<?> current = getStack().getCurrent();
        fastAnimation = controller.forceFastAnimation();
        direction = fastAnimation || current == null || current.forceFadeMode() ? TRANSLATION_FADE : current.usePopupMode() ? TRANSLATION_VERTICAL : TRANSLATION_HORIZONTAL;
      }
      if (Settings.instance().needReduceMotion() && (direction == TRANSLATION_HORIZONTAL)) {
        direction = TRANSLATION_FADE;
      } else if (M3.SHARED_AXIS_NAVIGATION && direction == TRANSLATION_HORIZONTAL) {
        // Dock sections are top-level destinations: fade-through when opening them and when going back from them.
        ViewController<?> target = forward ? controller : getStack().getCurrent();
        if (target != null && target.isTopLevelDestination()) {
          direction = TRANSLATION_FADE_THROUGH;
        }
      }
    }

    int rebase = 0;

    if ((mode & MODE_REBASE) == MODE_REBASE) {
      rebase = MODE_REBASE;
      if ((mode & MODE_ARG_SAVE_FIRST) == MODE_ARG_SAVE_FIRST) {
        rebase |= MODE_ARG_SAVE_FIRST;
      }
    }

    if (USE_ASYNC_IN_STEP_2 || !controller.needAsynchronousAnimation() || !isAttachedToWindow) {
      controller.executeAnimationReadyListeners();
      animateNavigate(controller, direction, forward, rebase, fastAnimation);
    } else {
      final int directionFinal = direction;
      final int rebaseFinal = rebase;
      final long timeout = controller.getAsynchronousAnimationTimeout(fastAnimation);
      controller.scheduleAnimation(new Runnable() {
        @Override
        public void run () {
          if (controller.getScheduledAnimation() == this) {
            controller.resetScheduledAnimation();
            if (isDestroyed) {
              controller.destroy();
            } else {
              controller.executeAnimationReadyListeners();
              animateNavigate(controller, directionFinal, forward, rebaseFinal, fastAnimation);
            }
          }
        }
      }, timeout);
    }
  }

  static final int MODE_BACKWARD = 0x01;
  static final int MODE_FORWARD = 0x02;
  static final int MODE_REBASE = 0x04;
  static final int MODE_FADE = 0x08;
  static final int MODE_ARG_SAVE_FIRST = 0x10;

  private boolean animatorRunning;

  private static final boolean USE_ASYNC_IN_STEP_2 = false;

  void animateNavigate (final ViewController<?> controller, int direction, final boolean forward, final int rebase, final boolean fastAnimation) {
    // The animation may have been scheduled (waiting for messages) before the split layout appeared.
    if (navigateTablet(controller, forward ? (rebase != 0 ? rebase : MODE_FORWARD) : MODE_BACKWARD)) {
      return;
    }
    final ViewController<?> left, right;

    if (forward) {
      prepareFactorAnimation(left = getStack().getPrevious(), right = controller, true, direction, PredictiveGesture.NONE);
    } else {
      prepareFactorAnimation(left = controller, right = getStack().getCurrent(), false, direction, PredictiveGesture.NONE);
    }

    final boolean[] isDone = new boolean[1];
    Runnable onDone = () -> {
      setFactor(forward ? 0f : 1f);
      isDone[0] = true;
      if (forward) {
        removeFadeView();
        if (rebase != 0) {
          headerView.applyPreview(controller);
          processor.rebaseStack(controller, (rebase & MODE_ARG_SAVE_FIRST) == MODE_ARG_SAVE_FIRST);
        } else {
          headerView.applyPreview(getStack().getCurrent());
          processor.setNextAsCurrent();
        }
        if (left != null && (m3Horizontal || m3FadeThrough)) {
          // Faded out by the M3 transition; it stays in the stack (other paths expect it opaque).
          left.getValue().setAlpha(1f);
        }
        completeNextLayout();
        if (Views.HARDWARE_LAYER_ENABLED) {
          setLayerType(View.LAYER_TYPE_NONE, left, right);
        }
        updateHackyViews();
      } else {
        finishTransaction(FINISH_NAVIGATION);
      }
    };

    if (!isAttachedToWindow) {
      onDone.run();
      return;
    }

    final ValueAnimator animator;

    this.animatorRunning = true;

    animator = AnimatorUtils.simpleValueAnimator();
    if (forward) {
      animator.addUpdateListener(animation -> {
        if (!isDone[0]) {
          setFactor(1f - AnimatorUtils.getFraction(animation));
        }
      });
    } else {
      animator.addUpdateListener(animation -> {
        if (!isDone[0]) {
          setFactor(AnimatorUtils.getFraction(animation));
        }
      });
    }

    switch (direction) {
      case TRANSLATION_FADE_THROUGH: {
        animator.setDuration(Config.DEBUG_NAV_ANIM ? 1000l : M3.TRANSITION_DURATION);
        animator.setInterpolator(M3.EMPHASIZED);
        break;
      }
      case TRANSLATION_FADE: {
        animator.setDuration(Config.DEBUG_NAV_ANIM ? 1000l : fastAnimation ? 120l : 180l);
        animator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
        break;
      }
      case TRANSLATION_VERTICAL: {
        animator.setDuration(forward ? HARD_TRANSITION_DURATION : SLOW_HARD_TRANSITION_DURATION);
        animator.setInterpolator(AnimatorUtils.NAVIGATION_INTERPOLATOR);
        break;
      }
      case TRANSLATION_HORIZONTAL: {
        if (m3Horizontal) {
          animator.setDuration(Config.DEBUG_NAV_ANIM ? 1000l : M3.TRANSITION_DURATION);
          animator.setInterpolator(M3.EMPHASIZED);
        } else {
          animator.setDuration(HARD_TRANSITION_DURATION);
          animator.setInterpolator(AnimatorUtils.NAVIGATION_INTERPOLATOR);
        }
        break;
      }
    }

    animator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd (Animator animation) {
        onDone.run();
      }
    });

    if (!fastAnimation) {
      if (forward) {
        animator.setStartDelay(left != null && left.usePopupMode() ? 120l : controller.getStartDelay(true));
      } else {
        animator.setStartDelay(right != null && right.usePopupMode() ? 120l : controller.getStartDelay(false));
      }
    }

    if (USE_ASYNC_IN_STEP_2 && forward && right.needAsynchronousAnimation()) {
      final long timeout = right.getAsynchronousAnimationTimeout(fastAnimation);
      controller.scheduleAnimation(new Runnable() {
        @Override
        public void run () {
          if (controller.getScheduledAnimation() == this) {
            controller.resetScheduledAnimation();
            AnimatorUtils.startAnimator(right.getValue(), animator);
          }
        }
      }, timeout);
    } else {
      AnimatorUtils.startAnimator(forward ? right.getValue() : left.getValue(), animator);
    }
  }

  // Anim

  void setIsAnimating (boolean animating) {
    if (isAnimating != animating) {
      isAnimating = animating;
      ((BaseActivity) context).notifyBackPressAvailabilityChanged();
      ((BaseActivity) context).setIsKeyboardBlocked(animating);
      UI.getContext(context).setOrientationLockFlagEnabled(BaseActivity.ORIENTATION_FLAG_NAVIGATING, animating);
      if (actionMode != null) {
        ((android.view.ActionMode) actionMode).finish();
      }
      if (!animating) {
        if (tabletLayout != null) {
          tabletLayout.requestLayout();
        }
        processor.checkRebaseMessage();
      }
    }
  }

  private void clearPreview () {
    setFactor(0f);

    headerView.clearPreview();

    removeChildWrapper(currentLeft);

    if (DROP_SHADOW_ENABLED) {
      switch (translationMode) {
        case TRANSLATION_HORIZONTAL: {
          shadowView.setVisibility(View.GONE);
          break;
        }
        case TRANSLATION_VERTICAL: {
          shadowTop.setVisibility(View.GONE);
          break;
        }
      }
    }

    if (USE_PREVIEW_FADE) {
      if (!currentLeft.usePopupMode()) {
        removeFadeView();
      }
    }

    currentRight.onFocus();

    currentLeft = null;
    currentRight = null;

    leftWrap = null;
    rightWrap = null;
  }

  boolean openPreview (float y) {
    return openPreviewImpl(y, false, PredictiveGesture.NONE);
  }

  public boolean forcePreviewPreviouewItem () {
    return openPreviewImpl(0, true, PredictiveGesture.NONE);
  }

  public void closePreviousPreviewItem () {
    closePreview(0f);
  }

  private boolean forceRtlAnimation;

  private boolean needRtl () {
    return forceRtlAnimation || Lang.rtl();
  }

  private boolean shadowIsRtl;
  private void checkShadowDirection () {
    boolean rtl = needRtl();
    if (shadowView != null && shadowIsRtl != rtl) {
      shadowIsRtl = rtl;
      if (rtl) {
        shadowView.setSimpleRightShadow(false);
      } else {
        shadowView.setSimpleLeftShadow(false);
      }
    }
  }

  private boolean openPreviewImpl (float y, boolean force, int predictiveGestureMode) {
    if (getStack().getCurrentIndex() <= 0) {
      processor.clearAnimation();
      return false;
    }

    if (isAnimating) {
      return false;
    }

    final ViewController<?> right = getStack().getCurrent();

    if (right == null) {
      return false;
    }

    setIsAnimating(true);

    nextTransitionIsGesture = true;
    if (right.usePopupMode()) {
      prepareFactorAnimation(getStack().getPrevious(), right, false, force ? TRANSLATION_NONE : TRANSLATION_VERTICAL, predictiveGestureMode);
    } else {
      prepareFactorAnimation(getStack().getPrevious(), right, false, force ? TRANSLATION_NONE : TRANSLATION_HORIZONTAL, predictiveGestureMode);
    }

    return true;
  }

  void closePreview (float velocity) {
    if (isAnimating && getStack().getCurrentIndex() > 0) {
      if (lastTranslation == 0f || translationMode == TRANSLATION_NONE) {
        forceClosePreview();
        return;
      }

      if (translationMode == TRANSLATION_VERTICAL) {
        translationFactor = lastTranslation / currentHeight;
      } else {
        translationFactor = lastTranslation / currentWidth;
      }

      ValueAnimator animator;

      final float startFactor = getFactor();
      animator = AnimatorUtils.simpleValueAnimator();
      animator.addUpdateListener(animation -> setFactor(startFactor - startFactor * AnimatorUtils.getFraction(animation)));
      animator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
      animator.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd (Animator animation) {
          finishTransaction(FINISH_PREVIEW);
        }
      });

      if (translationMode == TRANSLATION_VERTICAL) {
        animator.setDuration(calculateDropDuration(lastTranslation, velocity, MAXIMUM_VERTICAL_DROP_DURATION, MINIMUM_VERTICAL_DROP_DURATION));
      } else {
        animator.setDuration(calculateDropDuration(lastTranslation, velocity, MAXIMUM_DROP_DURATION, MINIMUM_DROP_DURATION));
      }
      animator.start();
    }
  }

  private int swipeNavigationLocked = 0;

  public void lockSwipeNavigation () {
    this.swipeNavigationLocked += 1;
  }

  public void unlockSwipeNavigation () {
    if (swipeNavigationLocked > 0) {
      this.swipeNavigationLocked -= 1;
    }
  }

  public boolean swipeNavigationEnabled () {
    return swipeNavigationLocked == 0 && !isTabletSplit();
  }

  void forceClosePreview () {
    finishTransaction(FINISH_PREVIEW_FORCE);
  }

  public void preventNextLayouts (int limit) {
    if (USE_LAYOUT_LIMITS) {
      rootView.preventNextLayouts(limit);
      headerView.preventNextLayouts(limit);
      contentWrapper.preventNextLayouts(limit);
    }
  }

  public void completeNextLayout () {
    if (USE_LAYOUT_LIMITS) {
      rootView.completeNextLayout();
      headerView.completeNextLayout();
      contentWrapper.completeNextLayout();
    }
  }

  public void preventLayout () {
    rootView.preventLayout();
    headerView.preventLayout();
    contentWrapper.preventLayout();
  }

  public void setLayerType (final int type, final ViewController<?> left, final ViewController<?> right) {
    if (shadowView != null) {
      Views.setLayerType(shadowView, type);
    }
    if (fadeView != null/* && (left == null || left.allowHardwareMode() || type != View.LAYER_TYPE_HARDWARE) && (right == null || right.allowHardwareMode() || type != View.LAYER_TYPE_HARDWARE)*/) {
      Views.setLayerType(fadeView, type);
    }
    if (shadowTop != null) {
      Views.setLayerType(shadowTop, type);
    }
    if (floatingButton != null && !SimpleShapeDrawable.USE_SOFTWARE_SHADOW) {
      Views.setLayerType(floatingButton, type == View.LAYER_TYPE_HARDWARE && SimpleShapeDrawable.USE_SOFTWARE_SHADOW ? View.LAYER_TYPE_SOFTWARE : type);
    }
    if (left != null && left.allowLayerTypeChanges()) {
      Views.setLayerType(left.getValue(), type);
    }
    if (right != null && right.allowLayerTypeChanges()) {
      Views.setLayerType(right.getValue(), type);
    }
  }

  public void layoutIfRequested () {
    rootView.layoutIfRequested();
    headerView.layoutIfRequested();
    contentWrapper.layoutIfRequested();
  }

  public void cancelLayout () {
    rootView.cancelLayout();
    headerView.cancelLayout();
    contentWrapper.cancelLayout();
  }

  private static final int FINISH_NAVIGATION = 1;
  private static final int FINISH_PREVIEW = 2;
  private static final int FINISH_PREVIEW_FORCE = 3;

  private void finishTransaction (int mode) {
    completeNextLayout();

    preventLayout();
    ViewController<?> current = getStack().getPrevious();
    removeFadeView();

    if (Views.HARDWARE_LAYER_ENABLED) {
      setLayerType(View.LAYER_TYPE_NONE, currentLeft, currentRight);
    }

    switch (mode) {
      case FINISH_NAVIGATION: {
        headerView.applyPreview(current);
        if (current != null && current.usePopupMode()) {
          applyPopupMode(current);
        }
        processor.setPrevAsCurrent();
        break;
      }
      case FINISH_PREVIEW: {
        headerView.clearPreview();
        final ViewController<?> previous = getStack().getCurrent();
        if (previous != null) {
          previous.onFocus();
        }
        processor.removePrevious(current);
        break;
      }
      case FINISH_PREVIEW_FORCE: {
        clearPreview();
        processor.removePrevious(current);
        break;
      }
    }

    layoutIfRequested();

    currentLeft = null;
    currentRight = null;

    leftWrap = null;
    rightWrap = null;

    updateHackyViews();
  }

  void applyPreview (float velocity) {
    if (isAnimating && getStack().getCurrentIndex() > 0) {
      if (translationMode == TRANSLATION_VERTICAL) {
        translationFactor = lastTranslation / currentHeight;
      } else {
        translationFactor = lastTranslation / currentWidth;
      }

      ValueAnimator animator;

      final float startFactor = getFactor();
      final float diffFactor = 1f - startFactor;
      animator = AnimatorUtils.simpleValueAnimator();
      animator.addUpdateListener(animation -> setFactor(startFactor + diffFactor * AnimatorUtils.getFraction(animation)));
      animator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
      animator.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd (Animator animation) {
          finishTransaction(FINISH_NAVIGATION);
        }
      });

      if (translationMode == TRANSLATION_VERTICAL) {
        animator.setDuration(calculateDropDuration(currentHeight - lastTranslation, velocity, MAXIMUM_VERTICAL_DROP_DURATION, MINIMUM_VERTICAL_DROP_DURATION));
      } else {
        animator.setDuration(calculateDropDuration(currentWidth - lastTranslation, velocity, MAXIMUM_DROP_DURATION, MINIMUM_DROP_DURATION));
      }
      animator.start();
    }
  }

  private float translationFactor;

  public void setFactor (float factor) {
    if (this.translationFactor == factor) return;

    factor = MathUtils.clamp(factor);

    this.translationFactor = factor;

    float px;
    if (translationMode == TRANSLATION_VERTICAL) {
      px = Math.round(currentHeight * factor);
    } else {
      px = currentWidth * factor;
    }

    lastTranslation = px;

    switch (translationMode) {
      case TRANSLATION_FADE: {
        headerView.setTranslation(factor);
        if (m3FadeThrough) {
          float progress = translatingForward ? 1f - factor : factor;
          float outgoing = M3.fadeThroughOut(progress), incoming = M3.fadeThroughIn(progress);
          leftWrap.setAlpha(translatingForward ? outgoing : incoming);
          rightWrap.setAlpha(translatingForward ? incoming : outgoing);
          // MaterialFadeThrough: the incoming screen scales up from 92%.
          View incomingWrap = translatingForward ? rightWrap : leftWrap;
          View outgoingWrap = translatingForward ? leftWrap : rightWrap;
          float scale = M3.fadeThroughScale(progress);
          incomingWrap.setScaleX(scale);
          incomingWrap.setScaleY(scale);
          outgoingWrap.setScaleX(1f);
          outgoingWrap.setScaleY(1f);
        } else {
          rightWrap.setAlpha(1f - factor);
        }

        break;
      }
      case TRANSLATION_HORIZONTAL: {
        headerView.setTranslation(factor);
        if (m3Horizontal) {
          applyM3Horizontal(factor);
          break;
        }
        factor = 1f - factor;

        float px2 = factor * currentPrevWidth;

        if (needRtl()) {
          rightWrap.setTranslationX(-px);
          currentRight.onTranslationChanged(-px);

          if (USE_PREVIEW_TRANSLATION) {
            currentLeft.getValue().setTranslationX(-px2);
            currentLeft.onTranslationChanged(-px2);
          }
        } else {
          rightWrap.setTranslationX(px);
          currentRight.onTranslationChanged(px);

          if (USE_PREVIEW_TRANSLATION) {
            currentLeft.getValue().setTranslationX(px2);
            currentLeft.onTranslationChanged(px2);
          }
        }

        if (currentLeft.usePopupMode()) {
          headerView.getFilling().restorePixels(-px2);
        }

        if (USE_PREVIEW_FADE) {
          if (!currentLeft.usePopupMode()) {
            fadeView.setAlpha(factor * PREVIEW_FADE_CHANGE_FACTOR);
          }
        }

        if (DROP_SHADOW_ENABLED) {
          //noinspection ResourceType
          shadowView.setTranslationX(needRtl() ? -px + currentWidth : px - Size.getNavigationShadowSize());
          shadowView.setAlpha(.65f + .45f * factor);
        }


        break;
      }
      case TRANSLATION_VERTICAL: {
        rightWrap.setTranslationY(px);

        if (USE_PREVIEW_FADE) {
          if (!currentLeft.usePopupMode()) {
            fadeView.setAlpha((1f - factor) * PREVIEW_FADE_CHANGE_FACTOR);
          }
        }

        if (DROP_SHADOW_ENABLED) {
          shadowTop.setTranslationY(px - Size.getNavigationShadowvertSize());
          shadowTop.setAlpha(1f);
        }

        headerView.getFilling().collapseFilling(px);
        if (px < fromHeaderHeight + HeaderView.getTopOffset()) {
          if (currentRight.getPopupRestoreColor() == 0xff000000) { // FIXME: 01/09/15 find out why really this line appears
            leftWrap.setAlpha(0f);
          }
          headerView.setTranslation(lastHeaderFactor = px / (fromHeaderHeight + HeaderView.getTopOffset()));
        } else if (lastHeaderFactor != 1f) {
          leftWrap.setAlpha(1f);
          lastHeaderFactor = 1f;
          headerView.setTranslation(1f);
        }

        // currentRight.applyCustomAnimations(1f - factor);

        break;
      }
    }

    updateHackyViews();
  }

  private void updateHackyViews () {
    ((BaseActivity) context).updateHackyOverlaysPositions();
  }

  public int getTotalTranslate () {
    return rootView.getMeasuredWidth();
  }

  /**
   * M3 horizontal transition at {@code factor} (1: the right screen is gone, 0: it is in place).
   * Shared axis: both screens move {@link #sharedAxisDistance}, alphas follow the fade-through thresholds.
   * Gesture: the right screen follows the finger, the left one moves the short distance, both stay opaque.
   */
  private void applyM3Horizontal (float factor) {
    float sign = needRtl() ? -1f : 1f;
    float rightX, rightAlpha = 1f, leftAlpha = 1f;
    if (m3Gesture) {
      rightX = currentWidth * factor;
    } else {
      rightX = sharedAxisDistance * factor;
      float progress = translatingForward ? 1f - factor : factor;
      float outgoing = M3.fadeThroughOut(progress), incoming = M3.fadeThroughIn(progress);
      leftAlpha = translatingForward ? outgoing : incoming;
      rightAlpha = translatingForward ? incoming : outgoing;
    }
    float leftX = -sharedAxisDistance * (1f - factor);
    rightX *= sign;
    leftX *= sign;
    lastRightTranslationX = rightX;
    rightWrap.setTranslationX(rightX);
    rightWrap.setAlpha(rightAlpha);
    currentRight.onTranslationChanged(rightX);
    leftWrap.setTranslationX(leftX);
    leftWrap.setAlpha(leftAlpha);
    currentLeft.onTranslationChanged(leftX);
  }

  public int getHorizontalTranslate () {
    if (isAnimating && translationMode == TRANSLATION_HORIZONTAL && m3Horizontal) {
      return (int) Math.abs(lastRightTranslationX);
    }
    if (isAnimating && translationMode == TRANSLATION_HORIZONTAL) {
      return (int) ((float) rootView.getMeasuredWidth() * translationFactor);
    }
    return 0;
  }

  public float getFadeAlpha () {
    return isAnimating && translationMode == TRANSLATION_FADE ? 1f - lastTranslation : 1f;
  }

  public float getFactor () {
    return translationFactor;
  }

  final void translatePreview (float px) {
    if (isAnimating) {
      if (needRtl() && translationMode == TRANSLATION_HORIZONTAL) {
        setFactor(-px / currentWidth);
      } else {
        setFactor(px / currentWidth);
      }
    }
  }

  @Override
  public boolean onSystemBackStarted (@NonNull BackEventCompat backEvent) {
    int edge = backEvent.getSwipeEdge();
    if (M3.SHARED_AXIS_NAVIGATION && edge != BackEventCompat.EDGE_LEFT && edge != BackEventCompat.EDGE_RIGHT) {
      // Back button / key (EDGE_NONE, Android 16+): no gesture to follow, the press runs the regular
      // M3 backward (or fade-through) transition from onBackPressed.
      return false;
    }
    if (swipeNavigationEnabled()) {
      if (openPreviewImpl(backEvent.getTouchY(), false, backEvent.getSwipeEdge() == BackEventCompat.EDGE_RIGHT ? PredictiveGesture.BACK_FROM_RIGHT_EDGE : PredictiveGesture.BACK_FROM_LEFT_EDGE)) {
        setFactor(backEvent.getProgress());
        return true;
      }
    }
    return false;
  }

  @Override
  public void onSystemBackProgressed (@NonNull BackEventCompat backEvent) {
    if (isAnimating) {
      setFactor(backEvent.getProgress());
    }
  }

  @Override
  public void onSystemBackCancelled () {
    if (isAnimating) {
      closePreview(0f);
    }
  }

  @Override
  public boolean onSystemBackPressed () {
    if (isAnimating) {
      applyPreview(0f);
      return true;
    }
    return false;
  }

  public final void configurationChanged (Configuration newConfig) {
    for (ViewController<?> controller : getStack().getAll()) {
      controller.onConfigurationChanged(newConfig);
    }
  }

  private Object actionMode;

  public void setActionMode (Object mode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
      actionMode = mode;
    }
  }

  /*// Instance state

  private static final String KEY_STACK_SIZE = "_n_sz_";
  private static final String KEY_STACK_ITEM_ID = "_n_sid_";

  public void saveInstanceState (Bundle out) {
    NavigationStack stack = getStack();
    int stackSize = stack.size();

    out.putInt(KEY_STACK_SIZE, stackSize);

    if (stackSize > 1) {
      for (int i = 0; i < stackSize; i++) {
        ViewController c;
        c = stack.get(i);
        if (c == null) {
          throw new RuntimeException("Error saving stack: stack.get is null");
        }
        out.putInt(KEY_STACK_ITEM_ID + i, c.getId());
        c.onSaveInstanceState(i, out);
      }
    }
  }

  public int restoreStackSize (Bundle in) {
    return in.getInt(KEY_STACK_SIZE, 0);
  }

  public int restoreStackItemId (Bundle in, int id) {
    return in.getInt(KEY_STACK_ITEM_ID + id, 0);
  }

  public boolean restoreStackItem (int id, ViewController c, Bundle in) {
    return c.onRestoreInstanceState(id, in);
  }*/

  // Utils

  public static int calculateDropDuration (float length, float velocity, int max, int min) {
    return velocity <= 0 ? max : Math.min(Math.max(Math.round(length / (velocity / 1000f)), min), max);
  }

  // Language handling

  /*@Override
  public void onRtlChange (boolean isRtl) {
    for (ViewController controller : getStack().getAll()) {
      controller.onRtlChange(isRtl);
    }
    if (shadowView != null) {
      if (isRtl) {
        shadowView.setSimpleRightShadow(false);
      } else {
        shadowView.setSimpleLeftShadow(false);
      }
    }
    DrawerController drawer = UI.getDrawer(context);
    if (drawer != null) {
      drawer.onRtlChange(isRtl);
    }
  }*/

  @Override
  public void onLanguagePackEvent (int event, int arg1) {
    boolean directionChanged = Lang.hasDirectionChanged(event, arg1);

    if (directionChanged) {
      if (Views.setGravity(floatingButton, Gravity.TOP | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT)))
        Views.updateLayoutParams(floatingButton);
      checkShadowDirection();
    }

    if (headerView != null) {
      headerView.onLanguagePackEvent(event, arg1);
    }
    if (tabletLayout != null) {
      tabletLayout.updateColors();
    }
    // TODO more
  }

  private int bottomInset, bottomInsetWithoutIme;

  public void applyBottomInset (ViewController<?> controller) {
    if (isTabletSection(controller) && tabletLayout.getMain() != null) {
      // Left pane: the list scrolls under the floating bar of the chat list screen.
      int inset = bottomInsetWithoutIme + tabletLayout.getMain().getTabletSectionNavigationInset();
      if (controller.supportsBottomInset()) {
        controller.setBottomInset(inset, inset);
      } else {
        Views.setPaddingBottom(controller.getValue(), inset);
      }
      return;
    }
    int effectiveBottomInset = isTabletMaster(controller) ? bottomInsetWithoutIme : bottomInset;
    if (controller.supportsBottomInset()) {
      controller.setBottomInset(effectiveBottomInset, bottomInsetWithoutIme);
    } else {
      View view = controller.getValue();
      Views.setPaddingBottom(view, effectiveBottomInset);
    }
  }

  private void setBottomInset (int bottomInset, int bottomInsetWithoutIme) {
    if (this.bottomInset != bottomInset || this.bottomInsetWithoutIme != bottomInsetWithoutIme) {
      this.bottomInset = bottomInset;
      this.bottomInsetWithoutIme = bottomInsetWithoutIme;
      if (childWrappers != null) {
        for (ViewController<?> controller : childWrappers) {
          applyBottomInset(controller);
        }
      }
      if (tabletLayout != null && tabletLayout.getSection() != null) {
        applyBottomInset(tabletLayout.getSection());
      }
    }
  }

  public void dispatchSystemInsets (View child, ViewGroup.MarginLayoutParams layoutParams, Rect legacyInsets, Rect systemInsets, Rect systemInsetWithoutIme) {
    Views.setMargins(layoutParams, legacyInsets.left, legacyInsets.top, legacyInsets.right, 0);
    setBottomInset(systemInsets.bottom, systemInsetWithoutIme.bottom);
  }
}
