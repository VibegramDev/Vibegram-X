package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.MainController;
import org.thunderdog.challegram.widget.M3FolderRailView;

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

  int getDetailWidth () {
    return getMeasuredWidth() - listWidth;
  }

  void setMain (MainController controller) {
    if (main == controller) {
      return;
    }
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
  }

  void updateVisibility () {
    boolean showEmpty = isSplit() && navigation.getCurrentStackItem() == main;
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
    }
    if (folderRail != null) {
      folderRail.layout(0, 0, railWidth, railWidth > 0 ? height : 0);
    }
  }
}
