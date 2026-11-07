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
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;

/**
 * The menu button of a bot with a mini app at the start of the composer, like in the official
 * app: a pill with the text of the button while the field is empty, only the icon otherwise.
 */
public class BotMenuButtonView extends View implements FactorAnimator.Target, TextColorSet {
  public interface Callback {
    /** The visible width of the pill changed: the field and the emoji button follow it. */
    void onBotMenuButtonWidthChanged (BotMenuButtonView view);
  }

  private static final float HEIGHT = 32f, RADIUS = 16f, ICON_AREA = 40f, ICON_LEFT = 9.5f;
  private static final float TEXT_LEFT = 34f, TEXT_END = 4f, TEXT_SIZE = 15f;
  private static final float MAX_TEXT_WIDTH_FACTOR = .6f;

  private final Callback callback;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Drawable icon;
  private final BoolAnimator expandAnimator = new BoolAnimator(0, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 150L);

  private @Nullable String text;
  private @Nullable Text label;

  public BotMenuButtonView (Context context, Callback callback) {
    super(context);
    this.callback = callback;
    this.icon = Drawables.get(getResources(), R.drawable.vg_bot_webview_20);
  }

  public void setText (@Nullable String text) {
    if (StringUtils.equalsOrBothEmpty(this.text, text)) {
      return;
    }
    this.text = text;
    int maxWidth = (int) (Screen.currentWidth() * MAX_TEXT_WIDTH_FACTOR);
    label = StringUtils.isEmpty(text) ? null :
      new Text.Builder(text, maxWidth, Paints.robotoStyleProvider(TEXT_SIZE), this)
        .singleLine()
        .allBold()
        .build();
    requestLayout();
    invalidate();
    callback.onBotMenuButtonWidthChanged(this);
  }

  public void setExpanded (boolean isExpanded, boolean animated) {
    expandAnimator.setValue(isExpanded, animated);
  }

  public boolean isExpanded () {
    return expandAnimator.getValue();
  }

  private float getTextWidth () {
    return label != null ? label.getWidth() + Screen.dp(TEXT_END) : 0;
  }

  /** Width of the pill in the current state of the expand animation. */
  public float getVisibleWidth () {
    return Screen.dp(ICON_AREA) + getTextWidth() * expandAnimator.getFloatValue();
  }

  /** Width of the pill once the expand animation ends. */
  public int getTargetWidth () {
    return Screen.dp(ICON_AREA) + (expandAnimator.getValue() ? (int) getTextWidth() : 0);
  }

  /** The pill grows from the start of the composer: from the right edge in RTL. */
  private float getVisibleLeft () {
    return Lang.rtl() ? getMeasuredWidth() - getVisibleWidth() : 0;
  }

  @Override
  public int defaultTextColor () {
    return M3.onPrimary();
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = Screen.dp(ICON_AREA) + (int) Math.ceil(getTextWidth());
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN &&
      (e.getX() < getVisibleLeft() || e.getX() > getVisibleLeft() + getVisibleWidth())) {
      return false;
    }
    return super.onTouchEvent(e);
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
    final float left = getVisibleLeft();
    rect.set(left, top, left + getVisibleWidth(), top + height);
    paint.setColor(M3.primary());
    c.drawRoundRect(rect, radius, radius, paint);
    if (isPressed()) {
      paint.setColor(ColorUtils.alphaColor(.12f, M3.onPrimary()));
      c.drawRoundRect(rect, radius, radius, paint);
    }
    final float iconTop = top + (height - icon.getMinimumHeight()) / 2f;
    Drawables.draw(c, icon, left + Screen.dp(ICON_LEFT), iconTop,
      Paints.getPorterDuffPaint(M3.onPrimary()));
    final float factor = expandAnimator.getFloatValue();
    if (label != null && factor > 0f) {
      c.save();
      c.clipRect(rect);
      int textTop = (int) (top + (height - label.getHeight()) / 2f);
      label.draw(c, (int) (left + Screen.dp(TEXT_LEFT)), textTop, null, factor);
      c.restore();
    }
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    invalidate();
    callback.onBotMenuButtonWidthChanged(this);
  }
}
