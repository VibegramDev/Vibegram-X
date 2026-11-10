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

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.PropertyId;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;

import me.vkryl.core.ColorUtils;

/**
 * «New Thread» card under the last message of the whole chat with a bot whose users create
 * topics, as in the official app: a message sent there starts a new topic. It is drawn in the
 * bottom padding of the message list, so it scrolls with the messages without being a message.
 */
public final class BotForumThreadHint implements TextColorSet {
  private static final float SEPARATOR_HEIGHT = 34f;
  private static final float SEPARATOR_DASH_TOP = 15.5f;
  private static final float SEPARATOR_CENTER_GAP = 3.66f;
  private static final float SEPARATOR_DASH_WIDTH = 3.66f;
  private static final float SEPARATOR_DASH_HEIGHT = 2f;
  private static final float SEPARATOR_DASH_STEP = 8.33f;
  private static final float MARGIN_BOTTOM = 6f;
  private static final float RADIUS = 16f;
  private static final float PADDING_HORIZONTAL = 16f;
  private static final float PADDING_TOP = 17f;
  private static final float ICON_CIRCLE_RADIUS = 35f;
  private static final float PADDING_TEXT_TOP = 14f;
  private static final float TITLE_TEXT_GAP = 4f;
  private static final float PADDING_TEXT_BOTTOM = 2f;
  private static final float PADDING_BOTTOM = 5f;
  private static final float MAX_WIDTH_FACTOR = .95f;
  /** The description breaks into about two even lines instead of one long line. */
  private static final float TEXT_WIDTH_FACTOR = .6f;
  private static final float ICON_CIRCLE_ALPHA = .12f;
  private static final float ARROW_ALPHA = .6f;

  private final MessagesManager manager;
  private final Drawable icon, arrow;

  private Text title, text;
  private int layoutWidth;
  private int cardWidth, cardHeight;

  public BotForumThreadHint (MessagesManager manager) {
    this.manager = manager;
    this.icon = Drawables.get(R.drawable.baseline_add_comment_40);
    this.arrow = Drawables.get(R.drawable.baseline_keyboard_arrow_down_20);
  }

  @Override
  public int defaultTextColor () {
    return manager.getColor(
      ColorId.text,
      ColorId.bubble_dateText,
      ColorId.bubble_dateText_noWallpaper,
      PropertyId.WALLPAPER_OVERRIDE_DATE
    );
  }

  private int backgroundColor () {
    return manager.getOverlayColor(
      ColorId.filling,
      ColorId.bubble_date,
      ColorId.bubble_date_noWallpaper,
      PropertyId.WALLPAPER_OVERRIDE_DATE
    );
  }

  /** Space the card takes under the last message in a list of the given width. */
  public int getHeight (int listWidth) {
    layout(listWidth);
    return Screen.dp(SEPARATOR_HEIGHT) + cardHeight + Screen.dp(MARGIN_BOTTOM);
  }

  private void layout (int listWidth) {
    if (title != null && layoutWidth == listWidth) {
      return;
    }
    layoutWidth = listWidth;
    int maxCardWidth = (int) (listWidth * MAX_WIDTH_FACTOR);
    int maxTextWidth = maxCardWidth - Screen.dp(PADDING_HORIZONTAL) * 2;
    String description = Lang.getString(R.string.vg_BotForumNewThreadText);
    float singleLineWidth = U.measureText(description, Paints.getRegularTextPaint(13f));
    int textWidth = Math.min(maxTextWidth, (int) (singleLineWidth * TEXT_WIDTH_FACTOR));
    title = new Text.Builder(
      Lang.getString(R.string.vg_BotForumNewThread),
      maxTextWidth,
      Paints.robotoStyleProvider(14f),
      this
    ).allBold().singleLine().noClickable().textFlags(Text.FLAG_ALIGN_CENTER).build();
    text = new Text.Builder(description, textWidth, Paints.robotoStyleProvider(13f), this)
      .maxLineCount(4)
      .noClickable()
      .textFlags(Text.FLAG_ALIGN_CENTER)
      .build();
    int contentWidth = Math.max(title.getWidth(), text.getWidth());
    cardWidth = Math.min(contentWidth + Screen.dp(PADDING_HORIZONTAL) * 2, maxCardWidth);
    cardHeight = Screen.dp(PADDING_TOP) + Screen.dp(ICON_CIRCLE_RADIUS) * 2 +
      Screen.dp(PADDING_TEXT_TOP) + title.getHeight() + Screen.dp(TITLE_TEXT_GAP) +
      text.getHeight() + Screen.dp(PADDING_TEXT_BOTTOM) + arrow.getMinimumHeight() +
      Screen.dp(PADDING_BOTTOM);
  }

  /** Height of a topic separator: dashes across the list with a topic pill in the middle. */
  public static int getSeparatorHeight () {
    return Screen.dp(SEPARATOR_HEIGHT);
  }

  private static Paint separatorPaint;
  private static final Path separatorPath = new Path();

  private static Paint separatorPaint (int color) {
    int dashHeight = Screen.dp(SEPARATOR_DASH_HEIGHT);
    if (separatorPaint == null || separatorPaint.getStrokeWidth() != dashHeight) {
      // Round caps make the dashes as long as in the official app
      int dash = Screen.dp(SEPARATOR_DASH_WIDTH) - dashHeight;
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeWidth(dashHeight);
      paint.setPathEffect(new DashPathEffect(new float[] {dash, Screen.dp(SEPARATOR_DASH_STEP) - dash}, 0));
      separatorPaint = paint;
    }
    separatorPaint.setColor(color);
    return separatorPaint;
  }

  /** Dashed line of a topic separator, from the center to both sides, as in the official app. */
  public static void drawSeparatorDashes (Canvas c, int width, int top, int color) {
    Paint paint = separatorPaint(color);
    float y = top + Screen.dp(SEPARATOR_DASH_TOP) + paint.getStrokeWidth() / 2f;
    float start = width / 2f + Screen.dp(SEPARATOR_CENTER_GAP) / 2f + paint.getStrokeWidth() / 2f;
    if (start >= width) {
      return;
    }
    separatorPath.rewind();
    separatorPath.moveTo(start, y);
    separatorPath.lineTo(width, y);
    separatorPath.moveTo(width - start, y);
    separatorPath.lineTo(0, y);
    c.drawPath(separatorPath, paint);
  }

  /** Draws the card with its top at {@code top}, centered in the list. */
  public void draw (Canvas c, int listWidth, int top) {
    layout(listWidth);
    final int backgroundColor = backgroundColor();
    final int contentColor = defaultTextColor();
    final float centerX = listWidth / 2f;

    drawSeparatorDashes(c, listWidth, top, backgroundColor);

    RectF rect = Paints.getRectF();
    int cardTop = top + Screen.dp(SEPARATOR_HEIGHT);
    int cardLeft = (int) (centerX - cardWidth / 2f);
    int cardRight = cardLeft + cardWidth;
    float radius = Screen.dp(RADIUS);
    rect.set(cardLeft, cardTop, cardRight, cardTop + cardHeight);
    c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(backgroundColor));

    int y = cardTop + Screen.dp(PADDING_TOP);
    int circleRadius = Screen.dp(ICON_CIRCLE_RADIUS);
    int circleColor = ColorUtils.alphaColor(ICON_CIRCLE_ALPHA, contentColor);
    c.drawCircle(centerX, y + circleRadius, circleRadius, Paints.fillingPaint(circleColor));
    Drawables.draw(c, icon,
      centerX - icon.getMinimumWidth() / 2f,
      y + circleRadius - icon.getMinimumHeight() / 2f,
      Paints.getPorterDuffPaint(contentColor)
    );
    y += circleRadius * 2 + Screen.dp(PADDING_TEXT_TOP);

    title.draw(c, cardLeft, cardRight, 0, y, this);
    y += title.getHeight() + Screen.dp(TITLE_TEXT_GAP);
    text.draw(c, cardLeft, cardRight, 0, y, this);
    y += text.getHeight() + Screen.dp(PADDING_TEXT_BOTTOM);

    Drawables.draw(c, arrow,
      centerX - arrow.getMinimumWidth() / 2f,
      y,
      Paints.getPorterDuffPaint(ColorUtils.alphaColor(ARROW_ALPHA, contentColor))
    );
  }
}
