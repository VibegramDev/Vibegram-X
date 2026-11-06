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
import android.graphics.Paint;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.dialogs.ChatView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGChat;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.core.StringUtils;

/**
 * Personal channel of a user profile (the "Channel" card of the official app): one segmented card (the background is
 * given by SettingsAdapter.setSegmented) with the "Channel" section header and the subscriber count (secondary text,
 * onSurfaceVariant) on top and the channel's chat list row below. The row keeps its own tap and long-press (chat
 * preview); its pressed state is clipped to the card corners on API 21+.
 */
public class ProfileChannelCardView extends ViewGroup {
  private static final float HEADER_HEIGHT = 36f, HEADER_CENTER_Y = 22f, PADDING_HORIZONTAL = 16f, SUBTITLE_GAP = 8f;

  private final ChatView chatView;
  private final String label;
  private @Nullable String subtitle;
  private @Nullable String trimmedSubtitle;
  private int labelWidth, trimmedSubtitleWidth, trimmedForWidth = -1;

  public ProfileChannelCardView (Context context, Tdlib tdlib) {
    super(context);
    setWillNotDraw(false);
    setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    chatView = new ChatView(context, tdlib);
    addView(chatView);
    label = Lang.getString(R.string.PersonalChannel);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Outline of the segment background: the row's pressed state stays inside the rounded card.
      setClipToOutline(true);
    }
  }

  public ChatView getChatView () {
    return chatView;
  }

  public static int getViewHeight () {
    return Screen.dp(HEADER_HEIGHT) + ChatView.getViewHeight(Settings.instance().getChatListMode());
  }

  public void setChat (TGChat chat, @Nullable String subtitle) {
    chatView.setChat(chat);
    if (!StringUtils.equalsOrBothEmpty(this.subtitle, subtitle)) {
      this.subtitle = subtitle;
      trimmedForWidth = -1;
      invalidate();
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    chatView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    setMeasuredDimension(width, Screen.dp(HEADER_HEIGHT) + chatView.getMeasuredHeight());
  }

  @Override
  protected void onLayout (boolean changed, int l, int t, int r, int b) {
    int top = Screen.dp(HEADER_HEIGHT);
    chatView.layout(0, top, r - l, top + chatView.getMeasuredHeight());
  }

  // Header: the section header style of the settings cards (TYPE_HEADER: 15dp medium, textAccent2) and body medium
  // secondary text.

  private static TextPaint labelPaint, subtitlePaint;

  private static TextPaint labelPaint () {
    if (labelPaint == null) {
      labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      labelPaint.setTypeface(Fonts.getRobotoMedium());
      labelPaint.setTextSize(Screen.dp(15f));
    }
    return labelPaint;
  }

  private static TextPaint subtitlePaint () {
    if (subtitlePaint == null) {
      subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      subtitlePaint.setTypeface(Fonts.getRobotoRegular());
      subtitlePaint.setTextSize(Screen.dp(14f));
    }
    return subtitlePaint;
  }

  private void layoutHeader (int width) {
    if (trimmedForWidth == width) {
      return;
    }
    trimmedForWidth = width;
    labelWidth = (int) Math.ceil(labelPaint().measureText(label));
    int availWidth = width - Screen.dp(PADDING_HORIZONTAL) * 2 - labelWidth - Screen.dp(SUBTITLE_GAP);
    if (!StringUtils.isEmpty(subtitle) && availWidth > 0) {
      trimmedSubtitle = TextUtils.ellipsize(subtitle, subtitlePaint(), availWidth, TextUtils.TruncateAt.END).toString();
      trimmedSubtitleWidth = (int) Math.ceil(subtitlePaint().measureText(trimmedSubtitle));
    } else {
      trimmedSubtitle = null;
      trimmedSubtitleWidth = 0;
    }
  }

  private static float baseline (TextPaint paint, float centerY) {
    Paint.FontMetrics fm = paint.getFontMetrics();
    return centerY - (fm.descent + fm.ascent) / 2f;
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    int width = getMeasuredWidth();
    layoutHeader(width);
    boolean rtl = Lang.rtl();
    float centerY = Screen.dp(HEADER_CENTER_Y);
    int padding = Screen.dp(PADDING_HORIZONTAL);

    TextPaint paint = labelPaint();
    paint.setColor(Theme.textAccent2Color());
    float labelLeft = rtl ? width - padding - labelWidth : padding;
    c.drawText(label, labelLeft, baseline(paint, centerY), paint);

    if (trimmedSubtitle != null) {
      paint = subtitlePaint();
      paint.setColor(M3.onSurfaceVariant());
      float left = rtl ? labelLeft - Screen.dp(SUBTITLE_GAP) - trimmedSubtitleWidth : labelLeft + labelWidth + Screen.dp(SUBTITLE_GAP);
      c.drawText(trimmedSubtitle, left, baseline(paint, centerY), paint);
    }
  }
}
