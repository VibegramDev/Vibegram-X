/*
 * This file is a part of Vibegram X
 * Copyright © 2026 (Vibegram X)
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
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextStyleProvider;

import me.vkryl.android.ViewUtils;
import me.vkryl.core.ColorUtils;

/**
 * M3 common button drawn in one view: the container and the label (TGX {@link Text}, so emoji in the label are drawn
 * by the app's emoji engine). Colours are read from the roles when drawing: register the view with
 * {@code addThemeInvalidateListener} and it follows theme changes.
 *
 * <ul>
 *   <li>Size (M3 Expressive buttons): medium — 56dp, title medium 16sp, 24dp padding. Round shape.</li>
 *   <li>Colours: filled — primary / onPrimary; tonal — secondaryContainer / onSecondaryContainer; text — no container,
 *   primary label. Disabled — container onSurface 12%, label onSurface 38%. Pressed — state layer 10% of the label.</li>
 * </ul>
 */
public class M3ButtonView extends View {
  public static final int STYLE_FILLED = 0, STYLE_TONAL = 1, STYLE_TEXT = 2;
  /** Medium (56dp) is the only size in use; small buttons (40dp) were never needed. */
  public static final int SIZE_MEDIUM = 1;

  private final int style;
  private CharSequence label = "";
  private @Nullable Text text;
  private int textBuiltWidth = -1;
  private final M3StateLayerDrawable stateLayer;

  public M3ButtonView (Context context, int style, int size) {
    super(context);
    if (size != SIZE_MEDIUM) {
      throw new IllegalArgumentException("size: " + size);
    }
    this.style = style;
    this.stateLayer = new M3StateLayerDrawable(null, new RoundMask(), pressedColor());
    ViewUtils.setBackground(this, stateLayer);
    Views.setClickable(this);
  }

  /** Recolours on theme changes of the screen. */
  public M3ButtonView addThemeListeners (@Nullable ViewController<?> themeProvider) {
    if (themeProvider != null) {
      themeProvider.addThemeInvalidateListener(this);
    }
    return this;
  }

  public int getStyle () {
    return style;
  }

  public int height () {
    return Screen.dp(56f);
  }

  private int horizontalPadding () {
    return Screen.dp(24f);
  }

  // Label

  public void setText (@Nullable CharSequence label) {
    CharSequence newLabel = label != null ? label : "";
    if (!TextUtils.equals(this.label, newLabel)) {
      this.label = newLabel;
      textBuiltWidth = -1;
      requestLayout();
      invalidate();
    }
  }

  public CharSequence getText () {
    return label;
  }

  private static TextStyleProvider textStyle;

  private static TextStyleProvider textStyle () {
    if (textStyle == null) {
      textStyle = Paints.robotoStyleProvider(16f);
    }
    return textStyle;
  }

  private final TextColorSet labelColorSet = this::labelColor;

  private void buildText (int maxWidth) {
    maxWidth = Math.max(0, maxWidth);
    // A label built for a wider space is kept while it fits. Rebuilt for exactly its own width (wrap_content: the
    // measured width is the label width) it was ellipsized, as Text rounds the widths of words down and the last word
    // no longer fit; so the label is ellipsized only when it is wider than the space available to the button.
    if (textBuiltWidth == maxWidth || (textBuiltWidth > maxWidth && (text == null || text.getWidth() <= maxWidth))) {
      return;
    }
    textBuiltWidth = maxWidth;
    text = label.length() > 0 ? new Text.Builder(label.toString(), Math.max(0, maxWidth), textStyle(), labelColorSet).allBold().singleLine().build() : null;
  }

  // Colours

  private @ColorInt int containerColor () {
    if (style == STYLE_TEXT) {
      return 0;
    }
    if (!isEnabled()) {
      return ColorUtils.alphaColor(.12f, M3.onSurface());
    }
    return style == STYLE_FILLED ? M3.primary() : M3.secondaryContainer();
  }

  private @ColorInt int labelColor () {
    if (!isEnabled()) {
      return ColorUtils.alphaColor(.38f, M3.onSurface());
    }
    switch (style) {
      case STYLE_FILLED:
        return M3.onPrimary();
      case STYLE_TONAL:
        return M3.onSecondaryContainer();
      default:
        return M3.primary();
    }
  }

  private int pressedColor () {
    return ColorUtils.alphaColor(M3.STATE_PRESSED, labelColor());
  }

  @Override
  public void setEnabled (boolean enabled) {
    if (isEnabled() != enabled) {
      super.setEnabled(enabled);
      invalidate();
    }
  }

  // Layout and drawing

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int specWidth = MeasureSpec.getSize(widthMeasureSpec);
    int width;
    if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
      width = specWidth;
    } else {
      buildText(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? Screen.currentWidth() : specWidth - horizontalPadding() * 2);
      // Content width, but not less than the minimum width set with setMinimumWidth (default: the 48dp target).
      width = Math.max(Math.max(Screen.dp(48f), getSuggestedMinimumWidth()), (text != null ? text.getWidth() : 0) + horizontalPadding() * 2);
      if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
        width = Math.min(width, specWidth);
      }
    }
    setMeasuredDimension(width, height());
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    int width = getMeasuredWidth(), height = getMeasuredHeight();
    int container = containerColor();
    if (container != 0) {
      RectF rect = Paints.getRectF();
      rect.set(0, 0, width, height);
      c.drawRoundRect(rect, height / 2f, height / 2f, Paints.fillingPaint(container));
    }
    stateLayer.setColor(pressedColor());
    buildText(width - horizontalPadding() * 2);
    if (text != null) {
      int textWidth = text.getWidth();
      text.draw(c, (width - textWidth) / 2, (height - text.getHeight()) / 2);
    }
  }

  /** Full-rounded mask of the pressed state layer. */
  private static final class RoundMask extends Drawable {
    @Override
    public void draw (@NonNull Canvas c) {
      RectF rect = Paints.getRectF();
      rect.set(getBounds());
      float radius = rect.height() / 2f;
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(0xffffffff));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
