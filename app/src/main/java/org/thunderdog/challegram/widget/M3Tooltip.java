/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.tool.UI;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

/**
 * M3 plain tooltip on tap and hold (m3.material.io/components/tooltips), shown by the tooltip manager
 * ({@link TooltipOverlayView.TooltipBuilder#showPlain}). Disappears 1.5s after the finger leaves the element
 * (BasicTooltipDefaults.TooltipDuration); a new tooltip closes the previous one.
 */
public final class M3Tooltip {
  /** Below the element: "If the element is in an app bar, the plain tooltip appears below the element". */
  public static final int PLACEMENT_BELOW = 1;
  /** Above the element: the floating navigation bar at the bottom of the screen. */
  public static final int PLACEMENT_ABOVE = 2;

  private static final long HIDE_DELAY = 1500L;

  public interface TextProvider {
    @Nullable CharSequence getTooltipText (View anchor);
  }

  /** Visual boundary of the element in its own coordinates (e.g. the dock container instead of the touch target). */
  public interface BoundsProvider {
    void getTooltipBounds (View anchor, Rect outRect);
  }

  private static @Nullable WeakReference<TooltipOverlayView.TooltipInfo> current;

  private M3Tooltip () { }

  /**
   * Shows the tooltip when the element is held. Takes over the long click and the touch listener of the element
   * (the touch listener never consumes events).
   */
  @SuppressLint("ClickableViewAccessibility")
  public static void bind (@NonNull View anchor, int placement, @Nullable BoundsProvider bounds, @NonNull TextProvider text) {
    anchor.setOnLongClickListener(v -> {
      CharSequence value = text.getTooltipText(v);
      if (TextUtils.isEmpty(value) || !show(v, placement, bounds, value)) {
        return false;
      }
      v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      return true;
    });
    anchor.setOnTouchListener((v, e) -> {
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        TooltipOverlayView.TooltipInfo tooltip = current != null ? current.get() : null;
        if (tooltip != null) {
          tooltip.hideDelayed(HIDE_DELAY, TimeUnit.MILLISECONDS);
        }
      }
      return false;
    });
  }

  /** Shows the tooltip now; it stays until {@link #hideCurrent} or a touch outside. */
  public static boolean show (@NonNull View anchor, int placement, @Nullable BoundsProvider bounds, @NonNull CharSequence text) {
    BaseActivity context = UI.getContext(anchor.getContext());
    if (context == null || anchor.getWindowToken() == null) {
      return false;
    }
    hideCurrent();
    TooltipOverlayView.TooltipBuilder builder = context.tooltipManager().builder(anchor).alignBottom(placement == PLACEMENT_BELOW);
    if (bounds != null) {
      builder.locate(bounds::getTooltipBounds);
    }
    current = new WeakReference<>(builder.showPlain(text));
    return true;
  }

  /** The tooltip manager always animates the hiding; kept for existing callers. */
  public static void hideCurrent (boolean animated) {
    hideCurrent();
  }

  public static void hideCurrent () {
    TooltipOverlayView.TooltipInfo tooltip = current != null ? current.get() : null;
    current = null;
    if (tooltip != null) {
      tooltip.hideNow();
    }
  }
}
