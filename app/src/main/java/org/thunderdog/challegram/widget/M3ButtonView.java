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
 *   <li>Sizes (M3 Expressive buttons): small — 40dp, label large 14sp, 16dp padding; medium — 56dp, title medium
 *   16sp, 24dp padding. Round shape.</li>
 *   <li>Colours: filled — primary / onPrimary; tonal — secondaryContainer / onSecondaryContainer; text — no container,
 *   primary label. Disabled — container onSurface 12%, label onSurface 38%. Pressed — state layer 10% of the label.</li>
 * </ul>
 */
public class M3ButtonView extends android.view.View {
  public static final int STYLE_FILLED = 0, STYLE_TONAL = 1, STYLE_TEXT = 2;
  public static final int SIZE_SMALL = 0, SIZE_MEDIUM = 1;

  private final int style, size;
  private CharSequence label = "";
  private @Nullable Text text;
  private int textBuiltWidth = -1;
  private final M3StateLayerDrawable stateLayer;

  public M3ButtonView (Context context, int style, int size) {
    super(context);
    this.style = style;
    this.size = size;
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
    return Screen.dp(size == SIZE_MEDIUM ? 56f : 40f);
  }

  private int horizontalPadding () {
    return Screen.dp(size == SIZE_MEDIUM ? 24f : 16f);
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

  private static TextStyleProvider smallStyle, mediumStyle;

  private TextStyleProvider textStyle () {
    if (size == SIZE_MEDIUM) {
      if (mediumStyle == null) {
        mediumStyle = Paints.robotoStyleProvider(16f);
      }
      return mediumStyle;
    }
    if (smallStyle == null) {
      smallStyle = Paints.robotoStyleProvider(14f);
    }
    return smallStyle;
  }

  private final TextColorSet labelColorSet = this::labelColor;

  private void buildText (int maxWidth) {
    if (textBuiltWidth == maxWidth) {
      return;
    }
    textBuiltWidth = maxWidth;
    text = label.length() > 0 ? new Text.Builder(label.toString(), Math.max(0, maxWidth), textStyle(), labelColorSet).allBold().singleLine().build() : null;
  }

  // Colours

  private @androidx.annotation.ColorInt int containerColor () {
    if (style == STYLE_TEXT) {
      return 0;
    }
    if (!isEnabled()) {
      return ColorUtils.alphaColor(.12f, M3.onSurface());
    }
    return style == STYLE_FILLED ? M3.primary() : M3.secondaryContainer();
  }

  private @androidx.annotation.ColorInt int labelColor () {
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

  // Countdown

  private @Nullable Runnable lockTick;

  /**
   * Keeps the button disabled for {@code seconds} with a countdown after the label, so that a stray tap right after
   * the sheet opens can't confirm an operation (Vibegram X: operations with Stars).
   */
  public void lock (int seconds) {
    if (lockTick != null) {
      removeCallbacks(lockTick);
    }
    final CharSequence label = this.label;
    lockTick = new Runnable() {
      int secondsLeft = seconds;

      @Override
      public void run () {
        if (secondsLeft <= 0) {
          setText(label);
          setEnabled(true);
          lockTick = null;
          return;
        }
        setEnabled(false);
        setText(TextUtils.concat(label, " · ", Integer.toString(secondsLeft)));
        secondsLeft--;
        postDelayed(this, 1000L);
      }
    };
    lockTick.run();
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
      width = Math.max(Screen.dp(48f), (text != null ? text.getWidth() : 0) + horizontalPadding() * 2);
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
