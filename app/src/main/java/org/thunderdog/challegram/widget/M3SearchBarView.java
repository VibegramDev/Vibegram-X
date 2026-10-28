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
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Size;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * M3 search app bar (unfocused): the contained search bar takes the place of the app bar title,
 * the app bar actions stay on its trailing side. Selecting the bar opens the focused search of the screen.
 *
 * Specs (m3.material.io/components/search): fully rounded container, surface container high,
 * leading non-functional search icon in a 48dp tap target, label 4dp after it, no shadow.
 * Height is 48dp instead of 56dp to fit the 56dp app bar; the focused bar drawn by
 * {@code MainController#genSearchHeader} has the same height, so the bar keeps its size when focused.
 */
public class M3SearchBarView extends FrameLayoutFix {
  public static final float BAR_HEIGHT = 48f;
  /** Leading margin: app bar horizontal padding. */
  public static final float START_MARGIN = 16f;
  /** Gap between the bar and the app bar actions. */
  public static final float END_MARGIN = 4f;

  public interface ColorProvider {
    /** Search bar container color. */
    int getBarColor ();
  }

  private final ColorProvider colorProvider;
  private final BarView barView;
  private final @IdRes int menuId;

  /**
   * @param label text shown in the bar
   * @param menuId id of the app bar menu (actions on the trailing side of the bar)
   */
  public M3SearchBarView (Context context, ViewController<?> themeProvider, ColorProvider colorProvider, CharSequence label, CharSequence contentDescription, @IdRes int menuId) {
    super(context);
    this.colorProvider = colorProvider;
    this.menuId = menuId;
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Size.getHeaderPortraitSize(), Gravity.TOP | (Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT)));

    barView = new BarView(context, label);
    barView.setContentDescription(contentDescription);
    int barHeight = Screen.dp(BAR_HEIGHT);
    FrameLayoutFix.LayoutParams barParams = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.CENTER_VERTICAL);
    barParams.leftMargin = Screen.dp(Lang.rtl() ? END_MARGIN : START_MARGIN);
    barParams.rightMargin = Screen.dp(Lang.rtl() ? START_MARGIN : END_MARGIN);
    barView.setLayoutParams(barParams);
    RippleSupport.setTransparentSelector(barView, barHeight / 2f, themeProvider);
    // Ripple above the filled container.
    Drawable ripple = barView.getBackground();
    barView.setBackground(null);
    ForegroundCompat.set(barView, ripple);
    addView(barView);

    themeProvider.addThemeInvalidateListener(barView);
  }

  /** Width of the visible app bar actions (lock, search, compose buttons), they change visibility at runtime. */
  private int getActionsWidth () {
    ViewParent parent = getParent();
    View menu = parent instanceof View ? ((View) parent).findViewById(menuId) : null;
    if (!(menu instanceof ViewGroup) || menu.getVisibility() == View.GONE) {
      return 0;
    }
    ViewGroup group = (ViewGroup) menu;
    int width = 0;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() != View.GONE && child.getLayoutParams() != null) {
        width += Math.max(0, child.getLayoutParams().width);
      }
    }
    return width;
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - getActionsWidth());
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
  }

  public void setOnBarClickListener (View.OnClickListener listener) {
    barView.setOnClickListener(listener);
  }

  /** Call when colors returned by the provider change (e.g. app bar on scroll). */
  public void invalidateColors () {
    barView.invalidate();
  }

  private class BarView extends View implements ForegroundCompat.Host {
    private final ForegroundCompat foregroundCompat = new ForegroundCompat(this);

    @Nullable
    @Override
    public ForegroundCompat foregroundCompat () {
      return foregroundCompat;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      super.draw(c);
      if (foregroundCompat != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        foregroundCompat.draw(c);
      }
    }

    @Override
    protected boolean verifyDrawable (@NonNull Drawable who) {
      return super.verifyDrawable(who) || (foregroundCompat != null && foregroundCompat.verifyDrawable(who));
    }

    @Override
    protected void drawableStateChanged () {
      super.drawableStateChanged();
      if (foregroundCompat != null) {
        foregroundCompat.drawableStateChanged();
      }
    }

    @Override
    public void drawableHotspotChanged (float x, float y) {
      super.drawableHotspotChanged(x, y);
      if (foregroundCompat != null) {
        foregroundCompat.drawableHotspotChanged(x, y);
      }
    }

    @Override
    public void jumpDrawablesToCurrentState () {
      super.jumpDrawablesToCurrentState();
      if (foregroundCompat != null) {
        foregroundCompat.jumpDrawablesToCurrentState();
      }
    }
    private final Drawable icon;
    private final String label;

    public BarView (Context context, CharSequence label) {
      super(context);
      this.label = label.toString();
      this.icon = Drawables.get(getResources(), R.drawable.baseline_search_24);
      setAccessibilityDelegate(new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo (@NonNull View host, @NonNull AccessibilityNodeInfo info) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          info.setClassName(Button.class.getName());
        }
      });
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      int width = getWidth();
      int height = getHeight();
      RectF rect = Paints.getRectF();
      rect.set(0, 0, width, height);
      c.drawRoundRect(rect, height / 2f, height / 2f, Paints.fillingPaint(colorProvider.getBarColor()));

      boolean rtl = Lang.rtl();
      int contentColor = M3.onSurfaceVariant();
      // Leading icon in a 48dp tap target 4dp from the container edge, label 4dp after the tap target.
      int iconCenterX = Screen.dp(4f) + Screen.dp(48f) / 2;
      if (rtl) {
        iconCenterX = width - iconCenterX;
      }
      int cy = height / 2;
      Drawables.draw(c, icon, iconCenterX - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(contentColor));

      // Same style as the hinted search text: body large.
      TextPaint paint = Paints.getRegularTextPaint(16f, contentColor);
      int labelStart = Screen.dp(4f) + Screen.dp(48f) + Screen.dp(4f);
      float maxWidth = width - labelStart - Screen.dp(16f);
      if (maxWidth <= 0) {
        return;
      }
      CharSequence text = TextUtils.ellipsize(label, paint, maxWidth, TextUtils.TruncateAt.END);
      float textWidth = paint.measureText(text, 0, text.length());
      float baseline = cy - (paint.ascent() + paint.descent()) / 2f;
      float x = rtl ? width - labelStart - textWidth : labelStart;
      c.drawText(text, 0, text.length(), x, baseline, paint);
    }
  }
}
