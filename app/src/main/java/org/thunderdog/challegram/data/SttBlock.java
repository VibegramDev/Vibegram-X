/*
 * This file is a part of Vibegram X
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.stt.SttManager;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.SettingsVoiceTranscriptionController;
import org.thunderdog.challegram.util.text.Text;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;

/**
 * Vibegram X: the on-device transcription of one message (voice, video note or video): the state of the run,
 * the button (transcribe / progress ring with stop / show-hide), the recognized text with the status row
 * under it, and the taps on the button. The messages only place it: they decide where the button and the text go.
 *
 * Drawing does not allocate: the transcription and the icons are cached, texts are rebuilt only on changes,
 * and the ring is animated by one reusable Runnable.
 */
public final class SttBlock implements SttManager.TranscriptionListener {
  public interface Host {
    /** The transcription changed: rebuild the texts with {@link #buildText(int)} and update the layout. UI thread. */
    void onSttStateChanged (SttBlock block);
    /** Only the button needs redrawing (the progress ring turns). */
    void onSttInvalidate (SttBlock block);
  }

  private static final long FRAME_DELAY_MS = 33; // 30 frames per second are enough for the ring and leave the CPU to the recognition
  private static final long STATUS_UPDATE_MS = 500;
  private static final int STATUS_NONE = Integer.MIN_VALUE, STATUS_QUEUED = -1, STATUS_PREPARING = -2;

  private final TGMessage message;
  private final Host host;
  private final float textGapDp, statusRowDp;

  private @Nullable String key;
  private @Nullable TdApi.File file;
  private int maxSeconds;
  private int durationSeconds;
  private @StringRes int tooLongRes;
  private boolean marksRead;
  private boolean listening;
  private boolean destroyed;

  /** Cached on every state change: drawing never looks it up. */
  private @Nullable SttManager.Transcription transcription;

  private @Nullable Text text, statusText;
  private @Nullable String textSource;
  private int statusCode = STATUS_NONE;
  private int lastWidth;

  private final RectF buttonRect = new RectF();
  private boolean caught;

  private boolean framePending;
  private long lastStatusCheck;
  private final Runnable frame = this::onFrame;

  private void onFrame () {
    framePending = false;
    if (destroyed || !isRunning()) {
      return;
    }
    long now = SystemClock.uptimeMillis();
    if (now - lastStatusCheck >= STATUS_UPDATE_MS) {
      lastStatusCheck = now;
      if (computeStatusCode() != statusCode) {
        // the percentage changed: the status row text is rebuilt
        host.onSttStateChanged(this);
        return;
      }
    }
    host.onSttInvalidate(this);
  }

  /**
   * @param textGapDp space above the text and above the status row
   * @param statusRowDp height of the status row
   */
  public SttBlock (TGMessage message, Host host, float textGapDp, float statusRowDp) {
    this.message = message;
    this.host = host;
    this.textGapDp = textGapDp;
    this.statusRowDp = statusRowDp;
  }

  /**
   * @param file the audio (or the video with the audio track), null when the message cannot be transcribed
   * @param maxSeconds longer audio is not recognized
   * @param tooLongRes toast shown instead of starting when {@code durationSeconds > maxSeconds}, 0 to let the run fail
   * @param marksRead reading the result counts as listening (voice and video notes)
   * @return true when the key changed
   */
  public boolean setFile (@Nullable TdApi.File file, int durationSeconds, int maxSeconds, @StringRes int tooLongRes, boolean marksRead) {
    String newKey = file != null && SttManager.isSupported() ? SttManager.key(file.id, file.remote != null ? file.remote.uniqueId : null) : null;
    this.file = newKey != null ? file : null;
    this.durationSeconds = durationSeconds;
    this.maxSeconds = maxSeconds;
    this.tooLongRes = tooLongRes;
    this.marksRead = marksRead;
    if (StringUtils.equalsOrBothEmpty(newKey, key)) {
      return false;
    }
    this.key = newKey;
    if (newKey != null && !listening && !destroyed) {
      listening = true;
      SttManager.instance().addTranscriptionListener(this);
    } else if (newKey == null && listening) {
      listening = false;
      SttManager.instance().removeTranscriptionListener(this);
    }
    refreshTranscription();
    return true;
  }

  public void destroy () {
    destroyed = true;
    if (listening) {
      listening = false;
      SttManager.instance().removeTranscriptionListener(this);
    }
    UI.removePendingRunnable(frame);
    framePending = false;
  }

  private void refreshTranscription () {
    transcription = key != null ? SttManager.instance().peekTranscription(key) : null;
  }

  @Override
  public void onTranscriptionStateChanged (String changedKey) {
    if (destroyed || key == null || (changedKey != null && !key.equals(changedKey))) {
      return;
    }
    refreshTranscription();
    host.onSttStateChanged(this);
  }

  // State

  public boolean isEnabled () {
    return key != null;
  }

  public @Nullable String getKey () {
    return key;
  }

  public int getState () {
    SttManager.Transcription t = transcription;
    return t != null ? t.state : SttManager.STATE_NONE;
  }

  public boolean isRunning () {
    return getState() == SttManager.STATE_RUNNING;
  }

  public boolean isExpanded () {
    SttManager.Transcription t = transcription;
    return t != null && t.expanded;
  }

  /** The finished transcription shown under the message, for copying; null while running, failed, empty or hidden. */
  public @Nullable String getCopyText () {
    SttManager.Transcription t = transcription;
    if (t == null || !t.expanded || t.state != SttManager.STATE_DONE || StringUtils.isEmpty(t.text)) {
      return null;
    }
    String text = t.text.trim();
    return text.isEmpty() ? null : text;
  }

  /** The text (or the run) is shown under the message. */
  public boolean isOpen () {
    return isExpanded() && getState() != SttManager.STATE_NONE;
  }

  /** The finished result (or the error) is shown: the button collapses it. */
  private boolean isResultShown () {
    int state = getState();
    return isExpanded() && (state == SttManager.STATE_DONE || state == SttManager.STATE_FAILED);
  }

  // Text

  private @Nullable String getDisplayText () {
    SttManager.Transcription t = transcription;
    if (t == null || !t.expanded) {
      return null;
    }
    switch (t.state) {
      case SttManager.STATE_RUNNING:
        // text recognized so far; the status goes into its own row
        return StringUtils.isEmpty(t.text) ? null : t.text;
      case SttManager.STATE_DONE:
        return StringUtils.isEmpty(t.text) ? Lang.getString(R.string.vg_SttEmpty) : t.text;
      case SttManager.STATE_FAILED:
        return Lang.getString(R.string.vg_SttFailed);
    }
    return null;
  }

  private int computeStatusCode () {
    SttManager.Transcription t = transcription;
    if (t == null || !t.expanded || t.state != SttManager.STATE_RUNNING) {
      return STATUS_NONE;
    }
    float progress = t.getDisplayProgress();
    if (progress < 0) {
      return STATUS_QUEUED;
    }
    if (t.preparing) {
      return STATUS_PREPARING;
    }
    return (int) (progress * 100);
  }

  private static String statusString (int code) {
    switch (code) {
      case STATUS_QUEUED:
        return Lang.getString(R.string.vg_SttQueued);
      case STATUS_PREPARING:
        return Lang.getString(R.string.vg_SttPreparing);
    }
    return Lang.getString(R.string.vg_SttProgress, code);
  }

  /** Rebuilds the text and the status row for the width; nothing is rebuilt when they did not change. */
  public void buildText (int width) {
    lastWidth = width;
    String display = width > 0 ? getDisplayText() : null;
    if (display == null) {
      text = null;
      textSource = null;
    } else if (text == null || !display.equals(textSource) || text.getMaxWidth() != width) {
      textSource = display;
      text = new Text.Builder(display, width, TGMessage.getTextStyleProvider(), message.getTextColorSet()).build();
    }
    int code = width > 0 ? computeStatusCode() : STATUS_NONE;
    lastStatusCheck = SystemClock.uptimeMillis();
    if (code == STATUS_NONE) {
      statusText = null;
      statusCode = STATUS_NONE;
    } else if (statusText == null || code != statusCode || statusText.getMaxWidth() != width) {
      statusCode = code;
      statusText = new Text.Builder(statusString(code), width, TGMessage.getTextStyleProvider(), message.getDecentColorSet()).singleLine().build();
    }
  }

  public void clearText () {
    text = null;
    textSource = null;
    statusText = null;
    statusCode = STATUS_NONE;
  }

  public boolean hasText () {
    return text != null || statusText != null;
  }

  public int getTextHeight () {
    int height = 0;
    if (text != null) {
      height += Screen.dp(textGapDp) + text.getHeight();
    }
    if (statusText != null) {
      height += Screen.dp(textGapDp + statusRowDp);
    }
    return height;
  }

  /** Width of the last line under the message, for the time placement; -1 when there is no text. */
  public int getLastLineWidth () {
    if (statusText != null) {
      return statusText.getWidth();
    }
    if (text != null) {
      return text.getLastLineWidth();
    }
    return -1;
  }

  /** Draws the text and the status row, {@code top} being the bottom of the content above them. */
  public void drawText (Canvas c, int x, int top, float alpha) {
    if (text != null) {
      top += Screen.dp(textGapDp);
      text.draw(c, x, x + text.getWidth(), 0, top, null, alpha);
      top += text.getHeight();
    }
    if (statusText != null) {
      // text only: progress and stop are shown by the button
      float cy = top + Screen.dp(textGapDp) + Screen.dp(statusRowDp) / 2f;
      statusText.draw(c, x, x + statusText.getWidth(), 0, (int) (cy - statusText.getHeight() / 2f), null, alpha);
    }
  }

  // Button

  private static Drawable sttIcon, collapseIcon;

  private static Drawable sttIcon () {
    if (sttIcon == null) {
      sttIcon = Drawables.get(R.drawable.vg_speech_to_text_24);
    }
    return sttIcon;
  }

  private static Drawable collapseIcon () {
    if (collapseIcon == null) {
      collapseIcon = Drawables.get(R.drawable.baseline_keyboard_arrow_up_24);
    }
    return collapseIcon;
  }

  public void clearButton () {
    buttonRect.setEmpty();
  }

  /** Rounded button next to a waveform (voice messages, transcribed video notes). */
  public void drawPillButton (Canvas c, float left, float cy, float widthDp, float heightDp, int activeColor) {
    if (key == null) {
      buttonRect.setEmpty();
      return;
    }
    float width = Screen.dp(widthDp), height = Screen.dp(heightDp);
    buttonRect.set(left, cy - height / 2f, left + width, cy + height / 2f);
    float radius = Screen.dp(8f);
    c.drawRoundRect(buttonRect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(.16f, activeColor)));
    drawButtonContent(c, buttonRect.centerX(), cy, activeColor, .8f, 1f);
  }

  /** Round button over media (videos, video notes). */
  public void drawCircleButton (Canvas c, float cx, float cy, float sizeDp, int backgroundColor, int iconColor, float idleIconScale, float alpha) {
    if (key == null || alpha <= 0f) {
      buttonRect.setEmpty();
      return;
    }
    float radius = Screen.dp(sizeDp) / 2f;
    buttonRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
    c.drawCircle(cx, cy, radius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, backgroundColor)));
    drawButtonContent(c, cx, cy, iconColor, idleIconScale, alpha);
  }

  private void drawButtonContent (Canvas c, float cx, float cy, int color, float idleIconScale, float alpha) {
    SttManager.Transcription t = transcription;
    if (t != null && t.state == SttManager.STATE_RUNNING) {
      drawRing(c, t, cx, cy, ColorUtils.alphaColor(alpha, color));
      if (!framePending) {
        framePending = true;
        UI.post(frame, FRAME_DELAY_MS);
      }
      return;
    }
    boolean shown = isResultShown();
    Drawable icon = shown ? collapseIcon() : sttIcon();
    float scale = shown ? 1f : idleIconScale;
    Paint paint = Paints.getPorterDuffPaint(color);
    int oldAlpha = paint.getAlpha();
    if (alpha < 1f) {
      paint.setAlpha((int) (oldAlpha * alpha));
    }
    if (scale != 1f) {
      c.save();
      c.scale(scale, scale, cx, cy);
    }
    Drawables.drawCentered(c, icon, cx, cy, paint);
    if (scale != 1f) {
      c.restore();
    }
    paint.setAlpha(oldAlpha);
  }

  /** Progress ring with a stop square: a tap cancels. */
  private static void drawRing (Canvas c, SttManager.Transcription t, float cx, float cy, int color) {
    float r = Screen.dp(8f);
    RectF arc = Paints.getRectF(cx - r, cy - r, cx + r, cy + r);
    float progress = t.getDisplayProgress();
    // the arc always turns, so the work is visibly going on even when the progress moves slowly
    float angle = (SystemClock.uptimeMillis() % 1500L) / 1500f * 360f;
    if (progress < 0) {
      // queued: indeterminate spinner
      c.drawArc(arc, angle, 270f, false, Paints.getProgressPaint(color, Screen.dp(2f)));
    } else {
      c.drawArc(arc, 0, 360f, false, Paints.getProgressPaint(ColorUtils.alphaColor(.25f, color), Screen.dp(2f)));
      c.drawArc(arc, angle - 90f, Math.max(36f, 360f * progress), false, Paints.getProgressPaint(color, Screen.dp(2f)));
    }
    float half = Screen.dp(3f);
    c.drawRect(cx - half, cy - half, cx + half, cy + half, Paints.fillingPaint(color));
  }

  // Touch

  /** @return true when the event belongs to the button */
  public boolean onTouchEvent (MotionEvent e, float slopDp) {
    if (key == null) {
      return false;
    }
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        float slop = Screen.dp(slopDp);
        caught = !buttonRect.isEmpty() &&
          e.getX() >= buttonRect.left - slop && e.getX() <= buttonRect.right + slop &&
          e.getY() >= buttonRect.top - slop && e.getY() <= buttonRect.bottom + slop;
        return caught;
      }
      case MotionEvent.ACTION_UP: {
        if (caught) {
          caught = false;
          onClick();
          message.performClickSoundFeedback();
          return true;
        }
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (caught) {
          caught = false;
          return true;
        }
        break;
      }
      default: {
        return caught;
      }
    }
    return false;
  }

  private void onClick () {
    if (key == null) {
      return;
    }
    refreshTranscription();
    SttManager manager = SttManager.instance();
    int state = getState();
    boolean expanded = isExpanded();
    if (state == SttManager.STATE_RUNNING) {
      manager.cancelTranscription(key);
      return;
    }
    if (state == SttManager.STATE_DONE || state == SttManager.STATE_FAILED) {
      if (state == SttManager.STATE_FAILED && !expanded) {
        start();
      } else {
        if (state == SttManager.STATE_DONE && !expanded && marksRead) {
          markRead(message);
        }
        manager.setExpanded(key, !expanded);
      }
      return;
    }
    start();
  }

  /**
   * Reading the transcription counts as listening (watching): the message gets marked as read,
   * for the sender too, the same as after playing it.
   */
  private static void markRead (TGMessage message) {
    if (!message.isOutgoing() && !message.isContentRead() && !TD.isSelfDestructTypeImmediately(message.getMessage())) {
      message.readContent();
    }
  }

  private void start () {
    final TdApi.File file = this.file;
    final String key = this.key;
    if (file == null || key == null) {
      return;
    }
    if (tooLongRes != 0 && durationSeconds > maxSeconds) {
      UI.showToast(tooLongRes, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    // the message object outlives its views, so the mark is applied even after scrolling away
    final TGMessage message = this.message;
    final boolean marksRead = this.marksRead;
    boolean started = SttManager.instance().transcribe(message.tdlib(), file, key, maxSeconds, marksRead ? () -> markRead(message) : null);
    if (!started) {
      UI.showToast(R.string.vg_SttNoModel, android.widget.Toast.LENGTH_SHORT);
      message.controller().navigateTo(new SettingsVoiceTranscriptionController(message.controller().context(), message.tdlib()));
    } else {
      refreshTranscription();
    }
  }
}
