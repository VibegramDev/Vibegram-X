/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;

import me.vkryl.android.ViewUtils;
import me.vkryl.core.ColorUtils;

/**
 * The menu button of a bot with a mini app at the start of the composer: the pill of the
 * official app without the text of the button, which is often too long for the composer.
 * Colours are read when drawing: register the view with {@code addThemeInvalidateListener}.
 */
public class BotMenuButtonView extends View {
  private static final float WIDTH = 40f, HEIGHT = 32f;

  private final Drawable icon;
  private final M3StateLayerDrawable stateLayer;

  public BotMenuButtonView (Context context) {
    super(context);
    this.icon = Drawables.get(getResources(), R.drawable.vg_bot_webview_20);
    this.stateLayer = new M3StateLayerDrawable(null, new PillMask(), pressedColor());
    ViewUtils.setBackground(this, stateLayer);
    Views.setClickable(this);
    setContentDescription(Lang.getString(R.string.vg_AccDescrBotMenu));
  }

  public static int getButtonWidth () {
    return Screen.dp(WIDTH);
  }

  private static int pressedColor () {
    return ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onPrimary());
  }

  private static void getPillRect (RectF rect, float width, float height) {
    final float pillHeight = Screen.dp(HEIGHT);
    final float top = (height - pillHeight) / 2f;
    rect.set(0, top, width, top + pillHeight);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(MeasureSpec.makeMeasureSpec(getButtonWidth(), MeasureSpec.EXACTLY),
      heightMeasureSpec);
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    RectF rect = Paints.getRectF();
    getPillRect(rect, getMeasuredWidth(), getMeasuredHeight());
    float radius = rect.height() / 2f;
    c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(M3.primary()));
    stateLayer.setColor(pressedColor());
    Drawables.draw(c, icon, (getMeasuredWidth() - icon.getMinimumWidth()) / 2f,
      rect.centerY() - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(M3.onPrimary()));
  }

  /** Mask of the pressed state layer: the pill, centered in the view. */
  private static final class PillMask extends Drawable {
    @Override
    public void draw (@NonNull Canvas c) {
      RectF rect = Paints.getRectF();
      getPillRect(rect, getBounds().width(), getBounds().height());
      rect.offset(getBounds().left, getBounds().top);
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
