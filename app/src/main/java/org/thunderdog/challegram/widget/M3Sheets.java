/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.v.CustomRecyclerView;


/**
 * M3 modal bottom sheet geometry (m3.material.io/components/bottom-sheets › Specs):
 * full window width up to 640dp; when the window is wider than 640dp the sheet gets 56dp side margins and is
 * centered; 28dp top corners (CornerExtraLargeTop); container surfaceContainerLow; Level1 elevation.
 */
public final class M3Sheets {
  private M3Sheets () { }

  public static final int MAX_WIDTH = 640, SIDE_MARGIN = 56;
  /** Drag handle area at the top of the sheet (DragHandle: 32x4dp, 22dp vertical padding = 48dp). */
  public static final float HANDLE_AREA = 48f;

  public interface TopProvider {
    /** Top edge of the visible container inside the view, in px. */
    int getContainerTop ();
  }

  /** Sheet width for the available (window) width, px. */
  public static int width (int availableWidth) {
    int maxWidth = Screen.dp(MAX_WIDTH);
    if (availableWidth <= maxWidth) {
      return availableWidth;
    }
    return Math.min(maxWidth, availableWidth - Screen.dp(SIDE_MARGIN) * 2);
  }

  /** Width measure spec clamped to {@link #width(int)}; the child must be centered by its gravity. */
  public static int measureSpec (int widthMeasureSpec) {
    int mode = View.MeasureSpec.getMode(widthMeasureSpec);
    if (mode == View.MeasureSpec.UNSPECIFIED) {
      return widthMeasureSpec;
    }
    int size = View.MeasureSpec.getSize(widthMeasureSpec);
    return View.MeasureSpec.makeMeasureSpec(width(size), View.MeasureSpec.EXACTLY);
  }

  /**
   * Shows the rows of a {@link SettingsAdapter} in an M3 bottom sheet: the Telegram X options sheet
   * ({@link ViewController#showPopup}) with a list instead of the options. The adapter's theme provider recolours the
   * rows on theme changes.
   */
  public static @Nullable PopupLayout showList (@NonNull ViewController<?> context, @NonNull SettingsAdapter adapter) {
    return context.showPopup(null, false, Text.LINE_COUNT_UNLIMITED, (popupLayout, optionsLayout) -> {
      CustomRecyclerView list = new CustomRecyclerView(optionsLayout.getContext());
      list.setLayoutManager(new LinearLayoutManager(optionsLayout.getContext(), RecyclerView.VERTICAL, false));
      list.setOverScrollMode(View.OVER_SCROLL_NEVER);
      list.setItemAnimator(null);
      list.setPadding(0, 0, 0, Screen.dp(16f));
      list.setClipToPadding(false);
      list.setAdapter(adapter);
      list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      optionsLayout.addView(list);
      int width = width(Screen.currentWidth());
      list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      return list.getMeasuredHeight();
    }, null);
  }

  public static float radius () {
    return Screen.dp(M3.CORNER_EXTRA_LARGE);
  }

  /**
   * Clips the view to the sheet shape (rounded top corners from {@code top}, square bottom) and casts the Level1
   * shadow. Call {@code view.invalidateOutline()} when the top moves.
   */
  public static void setOutline (View view, TopProvider top) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return;
    }
    view.setOutlineProvider(new ViewOutlineProvider() {
      @Override
      public void getOutline (View v, Outline outline) {
        int radius = (int) radius();
        int containerTop = Math.max(0, top.getContainerTop());
        // Extend below the bottom edge so only the top corners are rounded.
        outline.setRoundRect(0, containerTop, v.getWidth(), v.getHeight() + radius, radius);
      }
    });
    view.setClipToOutline(true);
    view.setElevation(Screen.dp(M3.ELEVATION_LEVEL1));
  }
}
