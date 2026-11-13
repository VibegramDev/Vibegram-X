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
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.core.ColorUtils;

/**
 * The menu button of a bot with a mini app at the start of the composer: the pill of the
 * official app without the text of the button, which is often too long for the composer.
 */
public class BotMenuButtonView extends View {
  private static final float WIDTH = 40f, HEIGHT = 32f, RADIUS = 16f;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Drawable icon;

  public BotMenuButtonView (Context context) {
    super(context);
    this.icon = Drawables.get(getResources(), R.drawable.vg_bot_webview_20);
  }

  public static int getButtonWidth () {
    return Screen.dp(WIDTH);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(MeasureSpec.makeMeasureSpec(getButtonWidth(), MeasureSpec.EXACTLY),
      heightMeasureSpec);
  }

  @Override
  protected void drawableStateChanged () {
    super.drawableStateChanged();
    invalidate();
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    final float height = Screen.dp(HEIGHT), radius = Screen.dp(RADIUS);
    final float top = (getMeasuredHeight() - height) / 2f;
    rect.set(0, top, getMeasuredWidth(), top + height);
    paint.setColor(M3.primary());
    c.drawRoundRect(rect, radius, radius, paint);
    if (isPressed()) {
      paint.setColor(ColorUtils.alphaColor(.12f, M3.onPrimary()));
      c.drawRoundRect(rect, radius, radius, paint);
    }
    Drawables.draw(c, icon, (getMeasuredWidth() - icon.getMinimumWidth()) / 2f,
      top + (height - icon.getMinimumHeight()) / 2f, Paints.getPorterDuffPaint(M3.onPrimary()));
  }
}
