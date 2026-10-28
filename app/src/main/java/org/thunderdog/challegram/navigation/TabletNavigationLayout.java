package org.thunderdog.challegram.navigation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.MainController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.widget.M3FolderRailView;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.widget.FrameLayoutFix;

/** Two viewports over the existing stack: account/deep-link/back ownership stays unchanged. */
final class TabletNavigationLayout extends FrameLayoutFix {
  private final NavigationController navigation;
  private final View detail;
  private final TextView empty;
  private final View divider;
  private NavigationLayout master;
  private HeaderView masterHeader;
  private MainController main;
  private int listWidth;
  /** Chat folders rail of the main screen, at the leading edge along the full height of the left pane. */
  private @Nullable View folderRail;
  private int railWidth;
  /**
   * Dock section (Calls, Contacts, Settings) shown in the left pane instead of the chat list. Its content is hosted
   * by the main screen (under its navigation bar), its app bar replaces the chat list one. Screens opened from it go
   * to the right pane. Stack: [main, section, right pane screens...].
   */
  private @Nullable ViewController<?> section;
  private @Nullable HeaderView sectionHeader;
  /** Whether the last touch started in the left pane: screens opened from a section replace the right pane. */
  private boolean lastTouchInMaster;

  TabletNavigationLayout (Context context, NavigationController navigation, View detail) {
    super(context);
    this.navigation = navigation;
    this.detail = detail;
    setLayoutParams(FrameLayoutFix.newParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    addView(detail);
    empty = new TextView(context);
    empty.setGravity(Gravity.CENTER);
    empty.setTextSize(16f);
    empty.setVisibility(GONE);
    addView(empty, FrameLayoutFix.newParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    divider = new View(context);
    divider.setVisibility(GONE);
    addView(divider, FrameLayoutFix.newParams(1, LayoutParams.MATCH_PARENT));
    updateColors();
  }

  boolean isSplit () {
    return main != null;
  }

  MainController getMain () {
    return main;
  }

  HeaderView getMasterHeader () {
    return masterHeader;
  }

  @Nullable ViewController<?> getSection () {
    return section;
  }

  @Nullable HeaderView getSectionHeader () {
    return sectionHeader;
  }

  boolean isLastTouchInMaster () {
    return lastTouchInMaster;
  }

  @Override
  public boolean dispatchTouchEvent (android.view.MotionEvent ev) {
    if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
      lastTouchInMaster = isSplit() && ev.getX() < listWidth;
      // A row click that opens nothing in the right pane doesn't select its row later.
      navigation.pendingTabletOpener = null;
    }
    return super.dispatchTouchEvent(ev);
  }

  /**
   * Shows the dock section in the left pane (or the chat list again, when {@code controller} is null).
   *
   * @param animated M3 fade-through from the current left pane content (dock section switch, not fold/unfold)
   */
  void setSection (@Nullable ViewController<?> controller, boolean animated) {
    if (section == controller || main == null) {
      return;
    }
    cancelPaneAnimation();
    boolean fadeThrough = animated && capturePaneGhost();
    clearSection();
    if (controller == null) {
      if (fadeThrough) {
        animatePaneEnter();
      }
      return;
    }
    section = controller;
    View view = controller.getValue();
    if (view.getParent() != null) {
      ((ViewGroup) view.getParent()).removeView(view);
    }
    resetTransition(view);
    main.setTabletSectionView(view);
    navigation.getHeaderView().detachControllerTitle(controller);
    View customTitle = controller.getCustomHeaderCell();
    if (customTitle != null) {
      resetTransition(customTitle);
    }
    sectionHeader = new HeaderView(getContext());
    // Expandable app bars (Settings photo) need the height of the single pane app bar.
    sectionHeader.setAllowExpandedHeight();
    sectionHeader.initWithSingleController(controller, true);
    // Expandable app bars (Settings photo): the filling follows the list scroll only after it moves.
    sectionHeader.setBackgroundHeight(controller.getHeaderHeight());
    addView(sectionHeader, indexOfChild(masterHeader) + 1);
    masterHeader.setVisibility(INVISIBLE);
    controller.attachNavigationController(navigation);
    navigation.applyBottomInset(controller);
    if (folderRail != null) {
      folderRail.bringToFront();
    }
    divider.bringToFront();
    requestLayout();
    if (fadeThrough) {
      animatePaneEnter();
    }
  }

  private void clearSection () {
    if (section == null) {
      return;
    }
    ViewController<?> controller = section;
    HeaderView header = sectionHeader;
    section = null;
    sectionHeader = null;
    if (header != null) {
      leaveTransformModes(header, controller);
      header.detachControllerTitle(controller);
      controller.removeDestroyListener(header);
      header.performDestroy();
      removeView(header);
    }
    if (main != null) {
      main.setTabletSectionView(null);
    }
    if (masterHeader != null) {
      masterHeader.setVisibility(VISIBLE);
    }
    requestLayout();
  }

  int getDetailWidth () {
    return getMeasuredWidth() - listWidth;
  }

  // M3 transitions in the detail pane. Split navigation swaps screens without the single-pane animation;
  // the new detail screen then enters on its own: shared axis X (slides in from the side, fades in) or
  // fade-through for top-level destinations. The outgoing screen is removed at once (its fade-out takes no time).

  private @Nullable ValueAnimator detailAnimator;
  /**
   * While the detail pane fades in, its area is filled with the screen background: the incoming screen appears
   * on the pane surface, not out of the black window behind it.
   */
  private boolean drawDetailBackdrop;
  private final android.graphics.Paint backdropPaint = new android.graphics.Paint();

  @Override
  protected boolean drawChild (android.graphics.Canvas canvas, View child, long drawingTime) {
    if ((child == detail && drawDetailBackdrop) || (child == master && paneAnimator != null)) {
      // The left pane content fades in on the pane surface as well.
      backdropPaint.setColor(Theme.backgroundColor());
      canvas.drawRect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom(), backdropPaint);
    }
    boolean result = super.drawChild(canvas, child, drawingTime);
    if (child == master && paneGhost != null && paneGhostAlpha > 0f) {
      // Under the app bars and the folder rail, which are drawn after the left pane.
      paneGhostPaint.setAlpha(Math.round(255f * paneGhostAlpha));
      canvas.drawBitmap(paneGhost, master.getLeft(), master.getTop(), paneGhostPaint);
    }
    return result;
  }

  /** @param direction 1: forward (enters from the trailing side), -1: backward, 0: fade-through (no movement) */
  void animateDetailEnter (int direction) {
    cancelDetailAnimation();
    if (getWindowToken() == null || detail.getVisibility() != VISIBLE) {
      return;
    }
    float sign = Lang.rtl() ? -1f : 1f;
    final float fromX = getDetailWidth() * M3.SHARED_AXIS_DISTANCE * direction * sign;
    detail.setAlpha(0f);
    detail.setTranslationX(fromX);
    drawDetailBackdrop = true;
    invalidate();
    Views.setLayerType(detail, LAYER_TYPE_HARDWARE);
    final boolean fadeThrough = direction == 0;
    detailAnimator = startTransition(progress -> {
      detail.setTranslationX(fromX * (1f - progress));
      // The outgoing screen is already gone: the incoming one fades in after the fade-out part.
      detail.setAlpha(M3.fadeThroughIn(progress));
      if (fadeThrough) {
        float scale = M3.fadeThroughScale(progress);
        detail.setScaleX(scale);
        detail.setScaleY(scale);
      }
    }, () -> {
      detailAnimator = null;
      resetDetail();
    });
  }

  private interface TransitionStep {
    void apply (float progress);
  }

  /** Starts an M3 transition: {@code step} gets the emphasized progress, {@code onEnd} runs unless it's cancelled. */
  private static ValueAnimator startTransition (TransitionStep step, Runnable onEnd) {
    ValueAnimator animator = AnimatorUtils.simpleValueAnimator();
    animator.setDuration(M3.TRANSITION_DURATION);
    // Linear fraction, eased in the listener (getFraction() is the interpolated value below API 12).
    animator.setInterpolator(new android.view.animation.LinearInterpolator());
    animator.addUpdateListener(animation -> step.apply(M3.EMPHASIZED.getInterpolation(AnimatorUtils.getFraction(animation))));
    animator.addListener(new AnimatorListenerAdapter() {
      private boolean cancelled;

      @Override
      public void onAnimationCancel (Animator animation) {
        cancelled = true;
      }

      @Override
      public void onAnimationEnd (Animator animation) {
        if (!cancelled) {
          onEnd.run();
        }
      }
    });
    animator.start();
    return animator;
  }

  void cancelDetailAnimation () {
    if (detailAnimator != null) {
      ValueAnimator animator = detailAnimator;
      detailAnimator = null;
      animator.cancel();
      resetDetail();
    }
  }

  private void resetDetail () {
    drawDetailBackdrop = false;
    invalidate();
    detail.setAlpha(1f);
    detail.setTranslationX(0f);
    detail.setScaleX(1f);
    detail.setScaleY(1f);
    Views.setLayerType(detail, LAYER_TYPE_NONE);
  }

  // M3 fade-through in the left pane when the dock section changes (Chats, Calls, Contacts, Settings), like top-level
  // destinations on the phone. The outgoing section is gone at once, so its content and app bar fade out as a snapshot
  // taken above the floating bar; then the incoming content fades in scaling up from 92% (fades only with reduced
  // motion), its app bar fades in. The folder rail and the floating bar stay in place.

  private @Nullable ValueAnimator paneAnimator;
  private @Nullable Bitmap paneGhost;
  private float paneGhostAlpha;
  private @Nullable View paneContent, paneHeader;
  private final Paint paneGhostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

  private boolean capturePaneGhost () {
    if (master == null || masterHeader == null || getWindowToken() == null || !isShown()) {
      return false;
    }
    int width = master.getWidth();
    int height = master.getHeight();
    View bar = main.getTabletNavigationBar();
    if (bar != null && bar.getVisibility() == VISIBLE) {
      int[] location = new int[2];
      bar.getLocationInWindow(location);
      int barTop = location[1];
      master.getLocationInWindow(location);
      height = Math.min(height, barTop - location[1]);
    }
    if (width <= 0 || height <= 0) {
      return false;
    }
    try {
      paneGhost = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    } catch (OutOfMemoryError e) {
      paneGhost = null;
      return false;
    }
    Canvas c = new Canvas(paneGhost);
    // Without the floating bar: the snapshot ends above it.
    master.draw(c);
    View header = sectionHeader != null ? sectionHeader : masterHeader;
    c.translate(header.getLeft() - master.getLeft(), header.getTop() - master.getTop());
    header.draw(c);
    return true;
  }

  private void animatePaneEnter () {
    if (main == null || masterHeader == null) {
      paneGhost = null;
      return;
    }
    paneContent = section != null ? section.getValue() : main.getTabletChatList();
    paneHeader = section != null ? sectionHeader : masterHeader;
    final boolean scale = !Settings.instance().needReduceMotion();
    applyPaneTransition(0f, scale);
    Views.setLayerType(paneContent, LAYER_TYPE_HARDWARE);
    paneAnimator = startTransition(progress -> applyPaneTransition(progress, scale), () -> {
      paneAnimator = null;
      resetPane();
    });
  }

  private void applyPaneTransition (float progress, boolean scale) {
    paneGhostAlpha = M3.fadeThroughOut(progress);
    float alpha = M3.fadeThroughIn(progress);
    if (paneContent != null) {
      paneContent.setAlpha(alpha);
      if (scale) {
        float factor = M3.fadeThroughScale(progress);
        paneContent.setScaleX(factor);
        paneContent.setScaleY(factor);
      }
    }
    if (paneHeader != null) {
      paneHeader.setAlpha(alpha);
    }
    invalidate();
  }

  private void cancelPaneAnimation () {
    if (paneAnimator != null) {
      ValueAnimator animator = paneAnimator;
      paneAnimator = null;
      animator.cancel();
    }
    resetPane();
  }

  private void resetPane () {
    if (paneContent != null) {
      paneContent.setAlpha(1f);
      paneContent.setScaleX(1f);
      paneContent.setScaleY(1f);
      Views.setLayerType(paneContent, LAYER_TYPE_NONE);
      paneContent = null;
    }
    if (paneHeader != null) {
      paneHeader.setAlpha(1f);
      paneHeader = null;
    }
    if (paneGhost != null) {
      // Not recycled: the last frame may still reference it.
      paneGhost = null;
      paneGhostAlpha = 0f;
      invalidate();
    }
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    cancelDetailAnimation();
    cancelPaneAnimation();
  }

  void setMain (MainController controller) {
    if (main == controller) {
      return;
    }
    cancelDetailAnimation();
    clearMain();
    main = controller;
    if (main == null) {
      return;
    }
    if (navigation.getCurrentStackItem() == main) {
      leaveTransformModes(navigation.getHeaderView(), main);
    }
    master = new NavigationLayout(getContext());
    master.setLayoutParams(FrameLayoutFix.newParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    addView(master, 0);
    View view = main.getValue();
    if (view.getParent() != null) {
      ((ViewGroup) view.getParent()).removeView(view);
    }
    // The controller may come from a finished single-pane transition with a stale offset.
    resetTransition(view);
    master.addView(view);
    navigation.getHeaderView().detachControllerTitle(main);
    View customTitle = main.getCustomHeaderCell();
    if (customTitle != null) {
      resetTransition(customTitle);
    }
    masterHeader = new HeaderView(getContext());
    masterHeader.initWithSingleController(main, true);
    addView(masterHeader);
    main.attachNavigationController(navigation);
    divider.bringToFront();
    updateFolderRail();
  }

  /** Attaches or removes the folder rail provided by the main screen. */
  void updateFolderRail () {
    View rail = main != null ? main.getTabletFolderRail() : null;
    if (rail != folderRail) {
      if (folderRail != null) {
        removeView(folderRail);
      }
      folderRail = rail;
      if (rail != null) {
        if (rail.getParent() != null) {
          ((ViewGroup) rail.getParent()).removeView(rail);
        }
        resetTransition(rail);
        addView(rail);
        divider.bringToFront();
      }
    }
    requestLayout();
  }

  // Chat cells draw the selection themselves; hardware-accelerated parents don't redraw children on invalidate().
  void invalidateMaster () {
    if (master != null) {
      invalidateTree(master);
    }
  }

  private static void invalidateTree (View view) {
    view.invalidate();
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        invalidateTree(group.getChildAt(i));
      }
    }
  }

  static void resetTransition (View view) {
    view.setTranslationX(0f);
    view.setTranslationY(0f);
    view.setAlpha(1f);
    view.setVisibility(VISIBLE);
  }

  /**
   * Search and selection modes can't move to another app bar: the search field (or the selection counter) stays
   * in the app bar it was opened in, while the new one shows the regular title. Leave them, without animation,
   * before the main screen changes its app bar (entering or leaving the split layout).
   */
  private static void leaveTransformModes (HeaderView header, ViewController<?> controller) {
    if (controller.inSearchMode()) {
      header.closeSearchMode(false, null);
    }
    if (controller.inSelectMode()) {
      header.closeSelectMode(false, false);
    }
  }

  void clearMain () {
    if (main == null) {
      return;
    }
    cancelDetailAnimation();
    cancelPaneAnimation();
    clearSection();
    leaveTransformModes(masterHeader, main);
    master.removeView(main.getValue());
    if (folderRail != null) {
      removeView(folderRail);
      folderRail = null;
    }
    masterHeader.detachControllerTitle(main);
    main.removeDestroyListener(masterHeader);
    masterHeader.performDestroy();
    master.performDestroy();
    removeView(masterHeader);
    removeView(master);
    main = null;
    masterHeader = null;
    master = null;
    listWidth = 0;
  }

  void updateColors () {
    empty.setBackgroundColor(Theme.backgroundColor());
    empty.setTextColor(Theme.textDecentColor());
    divider.setBackgroundColor(Theme.getColor(ColorId.separator));
    if (main != null) {
      masterHeader.resetColors(main, null);
      main.getValue().invalidate();
    }
    if (section != null && sectionHeader != null) {
      sectionHeader.resetColors(section, null);
    }
  }

  void updateVisibility () {
    ViewController<?> current = navigation.getCurrentStackItem();
    boolean showEmpty = isSplit() && (current == main || (section != null && current == section));
    empty.setVisibility(showEmpty ? VISIBLE : GONE);
    detail.setVisibility(showEmpty ? INVISIBLE : VISIBLE);
    divider.setVisibility(isSplit() ? VISIBLE : GONE);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);
    int requestedWidth = TabletLayoutPolicy.chatListWidth(width, Screen.density(), getResources().getConfiguration().smallestScreenWidthDp);
    navigation.updateTabletLayout(requestedWidth);
    railWidth = isSplit() && folderRail != null ? M3FolderRailView.getRailWidth() : 0;
    listWidth = isSplit() ? TabletLayoutPolicy.masterWidth(width, Screen.density(), requestedWidth, railWidth) : 0;
    updateVisibility();
    int detailSpec = MeasureSpec.makeMeasureSpec(width - listWidth, MeasureSpec.EXACTLY);
    int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
    detail.measure(detailSpec, heightSpec);
    empty.measure(detailSpec, heightSpec);
    divider.measure(MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY), heightSpec);
    if (master != null) {
      int masterSpec = MeasureSpec.makeMeasureSpec(listWidth - railWidth, MeasureSpec.EXACTLY);
      master.measure(masterSpec, heightSpec);
      measureChild(masterHeader, masterSpec, heightSpec);
      if (sectionHeader != null) {
        measureChild(sectionHeader, masterSpec, heightSpec);
      }
    }
    if (folderRail != null) {
      folderRail.measure(MeasureSpec.makeMeasureSpec(railWidth, MeasureSpec.EXACTLY), heightSpec);
    }
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onLayout (boolean changed, int l, int t, int r, int b) {
    int width = r - l;
    int height = b - t;
    detail.layout(listWidth, 0, width, height);
    empty.layout(listWidth, 0, width, height);
    divider.layout(listWidth - 1, 0, listWidth, height);
    if (master != null) {
      master.layout(railWidth, 0, listWidth, height);
      masterHeader.layout(railWidth, 0, listWidth, masterHeader.getMeasuredHeight());
      if (sectionHeader != null) {
        sectionHeader.layout(railWidth, 0, listWidth, sectionHeader.getMeasuredHeight());
      }
    }
    if (folderRail != null) {
      folderRail.layout(0, 0, railWidth, railWidth > 0 ? height : 0);
    }
  }
}
