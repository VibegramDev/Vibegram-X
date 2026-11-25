/*
 * This file is a part of Vibegram X
 */
package org.thunderdog.challegram.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.stt.SttSubtitles;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.core.ColorUtils;

/**
 * Vibegram X: the phrase of the on-device transcription at the current position of a playing video, on a dark plate.
 * Used inside a playing round in the chat and over the full screen player. It fades in when the phrase changes and
 * fades out when the speaker stops; two lines are balanced so a lone word does not hang under a full line.
 * Drawing allocates only when the phrase or the width changes.
 */
public final class SubtitlePlate {
  private static final long FADE_MS = 150;
  private static final float PADDING_H = 10f, PADDING_V = 4f;

  private final float textSizeDp;
  private final TextPaint paint;

  private int index = -1;
  private long changeTime;
  private @Nullable String text;
  private @Nullable StaticLayout layout;
  private int layoutWidth;

  public SubtitlePlate (float textSizeDp) {
    this.textSizeDp = textSizeDp;
    this.paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    this.paint.setTypeface(Fonts.getRobotoMedium());
    this.paint.setColor(0xffffffff);
  }

  /**
   * @param positionMs position of the playing video, -1 when it does not play (the phrase fades out)
   * @return true when the shown phrase changed and the plate must be redrawn
   */
  public boolean update (@Nullable SttSubtitles subtitles, long positionMs) {
    final int newIndex = subtitles != null && positionMs >= 0 ? subtitles.indexAt(positionMs) : -1;
    if (newIndex == index) {
      return false;
    }
    index = newIndex;
    changeTime = SystemClock.uptimeMillis();
    if (newIndex != -1) {
      // the last phrase stays in text while it fades out
      text = subtitles.get(newIndex).text;
      layout = null;
    }
    return true;
  }

  public void reset () {
    index = -1;
    text = null;
    layout = null;
  }

  public boolean isEmpty () {
    return text == null;
  }

  /**
   * Draws the plate centered at {@code centerX} with its bottom edge at {@code bottom}.
   *
   * @return true while a fade is going on: the caller must draw again on the next frame
   */
  public boolean draw (Canvas c, float centerX, float bottom, int maxWidth, float alpha) {
    if (text == null) {
      return false;
    }
    final long elapsed = SystemClock.uptimeMillis() - changeTime;
    float fade = Math.min(1f, elapsed / (float) FADE_MS);
    if (index == -1) {
      fade = 1f - fade;
    }
    final boolean animating = fade < 1f && (index != -1 || fade > 0f);
    alpha *= fade;
    if (alpha <= 0f) {
      if (index == -1 && elapsed >= FADE_MS) {
        text = null;
        layout = null;
      }
      return animating;
    }
    maxWidth -= Screen.dp(PADDING_H) * 2;
    if (maxWidth <= Screen.dp(40f)) {
      return animating;
    }
    paint.setTextSize(Screen.dp(textSizeDp));
    if (layout == null || layoutWidth != maxWidth) {
      layoutWidth = maxWidth;
      // two lines of about the same length instead of a full line and a lone word under it
      final float fullWidth = paint.measureText(text);
      int width = maxWidth;
      if (fullWidth > maxWidth && fullWidth < maxWidth * 2) {
        width = Math.min(maxWidth, (int) Math.ceil(fullWidth / 2f) + Screen.dp(24f));
      }
      StaticLayout newLayout = new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
      if (newLayout.getLineCount() > 2 && width < maxWidth) {
        newLayout = new StaticLayout(text, paint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
      }
      layout = newLayout;
    }
    final StaticLayout layout = this.layout;
    float textWidth = 0f;
    for (int i = 0; i < layout.getLineCount(); i++) {
      textWidth = Math.max(textWidth, layout.getLineWidth(i));
    }
    final float plateHeight = layout.getHeight() + Screen.dp(PADDING_V) * 2;
    final RectF plate = Paints.getRectF();
    plate.set(centerX - textWidth / 2f - Screen.dp(PADDING_H), bottom - plateHeight, centerX + textWidth / 2f + Screen.dp(PADDING_H), bottom);
    final float plateRadius = Math.min(Screen.dp(12f), plateHeight / 2f);
    c.drawRoundRect(plate, plateRadius, plateRadius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, 0x99000000)));
    final int saveCount = Views.save(c);
    c.translate(centerX - layout.getWidth() / 2f, bottom - plateHeight + Screen.dp(PADDING_V));
    paint.setAlpha((int) (255f * alpha));
    layout.draw(c);
    paint.setAlpha(255);
    Views.restore(c, saveCount);
    return animating;
  }
}
