package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.MainController;

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

  void clearMain () {
    if (main == null) {
      return;
    }
    master.removeView(main.getValue());
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
    empty.setText(Lang.getString(R.string.SelectChat));
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
    listWidth = isSplit() ? requestedWidth : 0;
    updateVisibility();
    int detailSpec = MeasureSpec.makeMeasureSpec(width - listWidth, MeasureSpec.EXACTLY);
    int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
    detail.measure(detailSpec, heightSpec);
    empty.measure(detailSpec, heightSpec);
    divider.measure(MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY), heightSpec);
    if (master != null) {
      int masterSpec = MeasureSpec.makeMeasureSpec(listWidth, MeasureSpec.EXACTLY);
      master.measure(masterSpec, heightSpec);
      measureChild(masterHeader, masterSpec, heightSpec);
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
      master.layout(0, 0, listWidth, height);
      masterHeader.layout(0, 0, listWidth, masterHeader.getMeasuredHeight());
    }
  }
}
