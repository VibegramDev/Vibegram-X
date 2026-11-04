/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import java.util.Arrays;
import java.util.Locale;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * Vibegram: zoom controls under the round video preview, like a camera app.
 * A pill with the lens stops (.6, 1, 2, 3): the selected stop is a filled circle that shows the current ratio.
 * Bigger stops (4, 10...) appear as separate circles to the right of the pill once the zoom reaches 2×.
 * Tap a stop: smooth zoom to it. Hold or drag horizontally: a ruler for precise zoom (log scale, the whole bar ≈ the whole range).
 */
public class RoundZoomBar extends View implements FactorAnimator.Target {
  public interface Delegate {
    /**
     * UI thread, may be called every frame: apply the ratio to the camera.
     * @param target the stop a tap is animating to, the ratio itself otherwise
     */
    void onZoomRatioRequested (float ratio, float target);
  }

  private static final float VIEW_HEIGHT = 48f;
  private static final float PILL_HEIGHT = 40f;
  private static final float CELL_WIDTH = 40f;
  private static final float MIN_CELL_WIDTH = 32f;
  private static final float PILL_PADDING = 4f;
  private static final float SELECTED_RADIUS = 17f;
  private static final float MAX_WIDTH = 300f;
  private static final float TICK_SPACING = 7f;
  private static final float EXTRA_GAP = 6f;
  /** Stops up to this value live in the pill, bigger ones are the extra circles. */
  private static final float PILL_MAX_STOP = 3.05f;
  /** The extra circles show once the zoom reaches this ratio (2×). */
  private static final float EXTRAS_THRESHOLD = 1.95f;

  private static final long ZOOM_DURATION = 280L;
  private static final long COLLAPSE_DELAY = 1000L;
  private static final long LONG_PRESS_DELAY = 250L;
  private static final long EXTERNAL_IGNORE_MS = 350L;
  private static final float EPSILON = .005f;

  private static final int ANIMATOR_AVAILABLE = 0;
  private static final int ANIMATOR_DIAL = 1;
  private static final int ANIMATOR_SELECTION = 2;
  private static final int ANIMATOR_EXTRAS = 3;

  private final BoolAnimator availableAnimator = new BoolAnimator(ANIMATOR_AVAILABLE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180L);
  private final BoolAnimator dialAnimator = new BoolAnimator(ANIMATOR_DIAL, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 200L);
  private final FactorAnimator selectionAnimator = new FactorAnimator(ANIMATOR_SELECTION, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 200L);
  private final BoolAnimator extrasAnimator = new BoolAnimator(ANIMATOR_EXTRAS, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 220L);

  private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();

  private @Nullable Delegate delegate;

  private float minRatio = 1f, maxRatio = 1f;
  private float[] stops = new float[] {1f};
  private String[] stopLabels = new String[] {"1"};
  /** Number of stops inside the pill, the rest are the extra circles. */
  private int pillCount = 1;
  private float ratio = 1f;
  private boolean available;
  private boolean touchEnabled;
  private int maxWidth;

  private @Nullable ValueAnimator zoomAnimator;
  private int zoomTargetIndex = -1;
  private long lastLocalRequestTime;

  public RoundZoomBar (Context context) {
    super(context);
    textPaint.setTypeface(Fonts.getRobotoMedium());
    textPaint.setTextSize(Screen.dp(12f));
    textPaint.setTextAlign(Paint.Align.CENTER);
    tickPaint.setStyle(Paint.Style.STROKE);
    tickPaint.setStrokeCap(Paint.Cap.ROUND);
    tickPaint.setStrokeWidth(Screen.dp(1.5f));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    updateContentDescription();
  }

  public void setDelegate (@Nullable Delegate delegate) {
    this.delegate = delegate;
  }

  /** Max width of the bar (0 = default). The controller narrows it when the bar has to sit beside the circle. */
  public void setMaxWidth (int maxWidth) {
    if (this.maxWidth != maxWidth) {
      this.maxWidth = maxWidth;
      requestLayout();
    }
  }

  /** Whether the user may touch the bar now (recording a round video with a camera). */
  public void setTouchEnabled (boolean enabled) {
    if (this.touchEnabled != enabled) {
      this.touchEnabled = enabled;
      if (!enabled) {
        cancelTouch();
      }
    }
  }

  public boolean isZoomAvailable () {
    return available;
  }

  /** Starts over for a new recording: 1×, buttons collapsed, hidden until the camera reports zoom. */
  public void reset () {
    cancelTouch();
    cancelZoomAnimation();
    removeCallbacks(collapseRunnable);
    dialAnimator.setValue(false, false);
    extrasAnimator.setValue(false, false);
    setCapabilities(1f, 1f, null, 1f, false);
  }

  /** The open camera changed (opened, switched front/rear, closed). */
  public void setCapabilities (float minRatio, float maxRatio, @Nullable float[] stops, float currentRatio, boolean animated) {
    float[] newStops = normalizeStops(minRatio, maxRatio, stops);
    boolean newAvailable = maxRatio > minRatio + EPSILON && newStops.length > 1;
    if (!newAvailable && this.available && animated && getAlpha() > 0f) {
      // Camera closed or switching: fade out with the old stops instead of morphing into a single "1"
      setAvailable(false, true);
      return;
    }
    boolean changed = this.minRatio != minRatio || this.maxRatio != maxRatio || !Arrays.equals(this.stops, newStops);
    if (!changed && newAvailable == this.available && isLocallyControlled()) {
      // Same camera, the user is zooming right now: keep the local value
      return;
    }
    this.minRatio = minRatio;
    this.maxRatio = Math.max(minRatio, maxRatio);
    if (changed) {
      this.stops = newStops;
      this.stopLabels = new String[newStops.length];
      for (int i = 0; i < newStops.length; i++) {
        stopLabels[i] = formatStop(newStops[i]);
      }
      int count = 0;
      while (count < newStops.length && (count == 0 || newStops[count] <= PILL_MAX_STOP)) {
        count++;
      }
      this.pillCount = count;
      requestLayout();
      cancelTouch();
      cancelZoomAnimation();
    }
    this.ratio = clampRatio(currentRatio);
    setAvailable(newAvailable, animated);
    updateExtras(animated && newAvailable);
    selectionAnimator.forceFactor(findSelectedIndex(ratio));
    updateContentDescription();
    invalidate();
  }

  /** The camera applied a ratio (our own request or a volume key). */
  public void setCurrentRatio (float ratio) {
    if (isLocallyControlled() || SystemClock.uptimeMillis() - lastLocalRequestTime < EXTERNAL_IGNORE_MS) {
      return;
    }
    setRatioImpl(clampRatio(ratio), true);
  }

  public float getRatio () {
    return ratio;
  }

  private void setAvailable (boolean available, boolean animated) {
    if (this.available != available) {
      this.available = available;
      if (!available) {
        cancelTouch();
        removeCallbacks(collapseRunnable);
        dialAnimator.setValue(false, animated);
      }
      setImportantForAccessibility(available ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    availableAnimator.setValue(available, animated && getAlpha() > 0f);
  }

  private static float[] normalizeStops (float min, float max, @Nullable float[] stops) {
    if (stops == null || stops.length == 0) {
      return new float[] {MathUtils.clamp(1f, min, Math.max(min, max))};
    }
    float[] sorted = stops.clone();
    Arrays.sort(sorted);
    float[] result = new float[sorted.length];
    int count = 0;
    for (float stop : sorted) {
      if (stop <= 0f || stop < min - EPSILON || stop > max + EPSILON) {
        continue;
      }
      if (count > 0 && Math.abs(result[count - 1] - stop) < .05f) {
        continue;
      }
      result[count++] = stop;
    }
    if (count == 0) {
      return new float[] {MathUtils.clamp(1f, min, Math.max(min, max))};
    }
    return count == result.length ? result : Arrays.copyOf(result, count);
  }

  private float clampRatio (float ratio) {
    return MathUtils.clamp(ratio, minRatio, maxRatio);
  }

  private int findSelectedIndex (float ratio) {
    int index = 0;
    for (int i = 0; i < stops.length; i++) {
      if (stops[i] <= ratio * (1f + EPSILON)) {
        index = i;
      }
    }
    return index;
  }

  private void setRatioImpl (float ratio, boolean animateSelection) {
    if (this.ratio != ratio) {
      this.ratio = ratio;
      int index = zoomTargetIndex != -1 ? zoomTargetIndex : findSelectedIndex(ratio);
      if (selectionAnimator.getToFactor() != index) {
        if (animateSelection && dialAnimator.getFloatValue() < 1f) {
          selectionAnimator.animateTo(index);
        } else {
          selectionAnimator.forceFactor(index);
        }
      }
      updateExtras(true);
      updateContentDescription();
      invalidate();
    }
  }

  private boolean hasExtras () {
    return pillCount < stops.length;
  }

  /** Shows the extra circles from 2× (or while one of them is the target of the zoom animation). */
  private void updateExtras (boolean animated) {
    boolean show = hasExtras() && (ratio >= EXTRAS_THRESHOLD || zoomTargetIndex >= pillCount);
    extrasAnimator.setValue(show, animated && getAlpha() > 0f);
  }

  private void requestRatio (float ratio, boolean animateSelection) {
    ratio = clampRatio(ratio);
    setRatioImpl(ratio, animateSelection);
    lastLocalRequestTime = SystemClock.uptimeMillis();
    if (delegate != null) {
      float target = zoomAnimator != null && zoomTargetIndex >= 0 && zoomTargetIndex < stops.length ? clampRatio(stops[zoomTargetIndex]) : ratio;
      delegate.onZoomRatioRequested(ratio, target);
    }
  }

  // Labels

  private static String formatStop (float stop) {
    float rounded = Math.round(stop * 10f) / 10f;
    if (rounded < 1f) {
      // ".6" like camera apps
      return String.format(Locale.US, "%.1f", rounded).substring(1);
    }
    if (Math.abs(rounded - Math.round(rounded)) < .01f) {
      return String.valueOf(Math.round(rounded));
    }
    return String.format(Locale.US, "%.1f", rounded);
  }

  /** "1.4×", "2×", ".6×" */
  public static String formatRatio (float ratio) {
    if (ratio >= 10f) {
      return Math.round(ratio) + "×";
    }
    return formatStop(ratio) + "×";
  }

  private void updateContentDescription () {
    setContentDescription(Lang.getString(R.string.vg_RoundZoomA11y, formatRatio(ratio)));
  }

  // Animation

  /** Tap on a stop: animate the ratio in log space, so every step feels the same. */
  private void animateToStop (int index) {
    if (index < 0 || index >= stops.length) {
      return;
    }
    final float target = clampRatio(stops[index]);
    cancelZoomAnimation();
    if (Math.abs(target - ratio) < EPSILON) {
      requestRatio(target, true);
      return;
    }
    final float fromLog = (float) Math.log(ratio);
    final float toLog = (float) Math.log(target);
    zoomTargetIndex = index;
    selectionAnimator.animateTo(index);
    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    animator.setDuration(ZOOM_DURATION);
    animator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    animator.addUpdateListener(animation -> {
      float t = (float) animation.getAnimatedValue();
      float value = t >= 1f ? target : (float) Math.exp(fromLog + (toLog - fromLog) * t);
      requestRatio(value, true);
    });
    animator.addListener(new AnimatorListenerAdapter() {
      private boolean cancelled;

      @Override
      public void onAnimationCancel (Animator animation) {
        cancelled = true;
      }

      @Override
      public void onAnimationEnd (Animator animation) {
        if (zoomAnimator == animation) {
          zoomAnimator = null;
          zoomTargetIndex = -1;
          if (!cancelled) {
            requestRatio(target, true);
          }
        }
      }
    });
    zoomAnimator = animator;
    animator.start();
  }

  private void cancelZoomAnimation () {
    ValueAnimator animator = this.zoomAnimator;
    if (animator != null) {
      this.zoomAnimator = null;
      animator.cancel();
    }
    zoomTargetIndex = -1;
  }

  private boolean isLocallyControlled () {
    return zoomAnimator != null || isDragging;
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    invalidate();
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) { }

  // Layout

  public static int getDesiredHeight () {
    return Screen.dp(VIEW_HEIGHT);
  }

  @Override
  protected void onSizeChanged (int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (onSizeChanged != null && w != oldw) {
      post(onSizeChanged);
    }
  }

  private @Nullable Runnable onSizeChanged;

  /** Called after the width changes, so the owner can re-center the bar. */
  public void setOnSizeChangedListener (@Nullable Runnable listener) {
    this.onSizeChanged = listener;
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int available = MeasureSpec.getSize(widthMeasureSpec);
    int width = Math.min(Screen.dp(MAX_WIDTH), available - Screen.dp(120f));
    if (maxWidth > 0) {
      width = Math.min(width, maxWidth);
    }
    width = Math.max(width, Screen.dp(MIN_CELL_WIDTH) * 2 + Screen.dp(PILL_PADDING) * 2);
    // Room for the extra circles on the right of the centered pill
    int extras = getExtraCount();
    if (extras > 0) {
      int needed = (int) Math.ceil(Screen.dp(CELL_WIDTH) * pillCount + Screen.dp(PILL_PADDING) * 2 + 2 * extras * (Screen.dp(CELL_WIDTH) + Screen.dp(EXTRA_GAP)));
      int limit = Math.max(width, Math.min(available, maxWidth > 0 ? maxWidth : available));
      width = Math.max(width, Math.min(needed, limit));
    }
    setMeasuredDimension(width, Screen.dp(VIEW_HEIGHT));
  }

  private int getExtraCount () {
    return stops.length - pillCount;
  }

  /** Cell = one stop in the pill, also the diameter of an extra circle. The pill stays centered, extras hang on its right. */
  private float getCellWidth () {
    float halfWidth = getMeasuredWidth() / 2f - Screen.dp(PILL_PADDING);
    int extras = getExtraCount();
    float cell = (halfWidth - extras * Screen.dp(EXTRA_GAP)) / (pillCount / 2f + extras);
    return Math.max(Screen.dp(MIN_CELL_WIDTH), Math.min(Screen.dp(CELL_WIDTH), cell));
  }

  private float getPillWidth () {
    return getCellWidth() * pillCount + Screen.dp(PILL_PADDING) * 2;
  }

  private float getPillLeft () {
    return (getMeasuredWidth() - getPillWidth()) / 2f;
  }

  private float getExtraCenterX (int extraIndex) {
    float cell = getCellWidth();
    float gap = Screen.dp(EXTRA_GAP);
    return getPillLeft() + getPillWidth() + gap + cell / 2f + (cell + gap) * extraIndex;
  }

  private float getStopCenterX (int index) {
    if (index >= pillCount) {
      return getExtraCenterX(index - pillCount);
    }
    float cell = getCellWidth();
    return getPillLeft() + Screen.dp(PILL_PADDING) + cell * index + cell / 2f;
  }

  /** Fractional index while the selection slides between stops. */
  private float getStopCenterX (float index) {
    int from = MathUtils.clamp((int) Math.floor(index), 0, stops.length - 1);
    int to = MathUtils.clamp(from + 1, 0, stops.length - 1);
    return MathUtils.fromTo(getStopCenterX(from), getStopCenterX(to), MathUtils.clamp(index - from));
  }

  private int indexAt (float x) {
    float pillRight = getPillLeft() + getPillWidth();
    if (hasExtras() && extrasAnimator.getFloatValue() > .5f && x > pillRight) {
      float cell = getCellWidth();
      float gap = Screen.dp(EXTRA_GAP);
      int extra = (int) Math.floor((x - pillRight - gap / 2f) / (cell + gap));
      return pillCount + MathUtils.clamp(extra, 0, getExtraCount() - 1);
    }
    float cell = getCellWidth();
    int index = (int) Math.floor((x - getPillLeft() - Screen.dp(PILL_PADDING)) / cell);
    return MathUtils.clamp(index, 0, pillCount - 1);
  }

  /** Right edge of the touchable controls: the pill, plus the extra circles when shown. */
  private float getControlsRight () {
    float pillRight = getPillLeft() + getPillWidth();
    if (hasExtras() && extrasAnimator.getFloatValue() > 0f) {
      return getExtraCenterX(getExtraCount() - 1) + getCellWidth() / 2f;
    }
    return pillRight;
  }

  /** Pixels per one unit of ln(ratio) in the ruler: the whole bar covers the whole range. */
  private float getDialScale () {
    float range = (float) (Math.log(maxRatio) - Math.log(minRatio));
    float width = getMeasuredWidth() - Screen.dp(PILL_PADDING) * 2;
    return range > 0f ? width / range : 1f;
  }

  private void getBarRect (RectF rect, float dial) {
    float pillLeft = getPillLeft();
    float pillRight = pillLeft + getPillWidth();
    float left = MathUtils.fromTo(pillLeft, 0f, dial);
    float right = MathUtils.fromTo(pillRight, getMeasuredWidth(), dial);
    float top = (getMeasuredHeight() - Screen.dp(PILL_HEIGHT)) / 2f;
    rect.set(left, top, right, top + Screen.dp(PILL_HEIGHT));
  }

  // Drawing

  @Override
  protected void onDraw (@NonNull Canvas c) {
    final float alpha = availableAnimator.getFloatValue();
    if (alpha <= 0f) {
      return;
    }
    final float dial = dialAnimator.getFloatValue();
    final float cy = getMeasuredHeight() / 2f;

    getBarRect(rect, dial);
    float radius = rect.height() / 2f;
    c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, Theme.getColor(ColorId.filling))));

    final int textColor = Theme.getColor(ColorId.text);
    final int selectedColor = Theme.getColor(ColorId.fillingPositive);
    final int selectedTextColor = Theme.getColor(ColorId.fillingPositiveContent);
    final float textOffset = -(textPaint.descent() + textPaint.ascent()) / 2f;

    // Ruler
    if (dial > 0f) {
      drawRuler(c, rect, cy, alpha * dial, textColor);
    }

    // Stops
    final float selection = selectionAnimator.getFactor();
    final float extras = extrasAnimator.getFloatValue() * (1f - dial);
    if (dial < 1f) {
      final int fillingColor = Theme.getColor(ColorId.filling);
      final float extraRadius = getCellWidth() / 2f;
      for (int i = 0; i < stops.length; i++) {
        float itemAlpha = alpha * (1f - dial);
        float x = getStopCenterX(i);
        boolean isExtra = i >= pillCount;
        if (isExtra) {
          // Pop in one by one from the pill
          float appear = MathUtils.clamp(extras * getExtraCount() - (i - pillCount));
          if (appear <= 0f) {
            continue;
          }
          itemAlpha *= appear;
          float scale = .6f + .4f * appear;
          c.drawCircle(x, cy, extraRadius * scale, Paints.fillingPaint(ColorUtils.alphaColor(alpha * appear, fillingColor)));
        }
        float distance = Math.abs(selection - i);
        float textAlpha = itemAlpha * MathUtils.clamp(distance);
        if (textAlpha <= 0f) {
          continue;
        }
        textPaint.setColor(ColorUtils.alphaColor(textAlpha, textColor));
        c.drawText(stopLabels[i], x, cy + textOffset, textPaint);
      }
    }

    // The selected stop: slides between stops, moves to the center of the ruler
    float selectedX = MathUtils.fromTo(getStopCenterX(selection), getMeasuredWidth() / 2f, dial);
    float selectedRadius = Screen.dp(SELECTED_RADIUS);
    c.drawCircle(selectedX, cy, selectedRadius, Paints.fillingPaint(ColorUtils.alphaColor(alpha, selectedColor)));
    textPaint.setColor(ColorUtils.alphaColor(alpha, selectedTextColor));
    String label = formatRatio(ratio);
    float maxTextWidth = selectedRadius * 2f - Screen.dp(4f);
    float textWidth = textPaint.measureText(label);
    if (textWidth > maxTextWidth) {
      c.save();
      float scale = maxTextWidth / textWidth;
      c.scale(scale, scale, selectedX, cy);
      c.drawText(label, selectedX, cy + textOffset, textPaint);
      c.restore();
    } else {
      c.drawText(label, selectedX, cy + textOffset, textPaint);
    }
  }

  private void drawRuler (Canvas c, RectF rect, float cy, float alpha, int textColor) {
    final float scale = getDialScale();
    final float minLog = (float) Math.log(minRatio);
    final float maxLog = (float) Math.log(maxRatio);
    final float currentLog = (float) Math.log(ratio);
    final float cx = getMeasuredWidth() / 2f;
    final float inset = rect.height() / 2f;
    final float left = rect.left + inset, right = rect.right - inset;
    final float halfWidth = Math.max(1f, (right - left) / 2f);
    final float step = Screen.dp(TICK_SPACING) / scale;
    final int minorColor = Theme.getColor(ColorId.icon);

    // Minor ticks, anchored to the start of the range so they scroll with the ratio
    int first = (int) Math.ceil(((currentLog - (cx - left) / scale) - minLog) / step);
    int last = (int) Math.floor(((currentLog + (right - cx) / scale) - minLog) / step);
    first = Math.max(first, 0);
    tickPaint.setStrokeWidth(Screen.dp(1.5f));
    for (int k = first; k <= last; k++) {
      float log = minLog + k * step;
      if (log > maxLog + 1e-4f) {
        break;
      }
      float x = cx + (log - currentLog) * scale;
      float fade = alpha * MathUtils.clamp(1.15f - Math.abs(x - cx) / halfWidth);
      if (fade <= 0f) {
        continue;
      }
      float h = Screen.dp(4f);
      tickPaint.setColor(ColorUtils.alphaColor(fade * .7f, minorColor));
      c.drawLine(x, cy - h, x, cy + h, tickPaint);
    }

    // Lens stops: taller ticks
    tickPaint.setStrokeWidth(Screen.dp(2f));
    for (float stop : stops) {
      float x = cx + ((float) Math.log(stop) - currentLog) * scale;
      if (x < left - 1f || x > right + 1f) {
        continue;
      }
      float fade = alpha * MathUtils.clamp(1.15f - Math.abs(x - cx) / halfWidth);
      if (fade <= 0f) {
        continue;
      }
      float h = Screen.dp(9f);
      tickPaint.setColor(ColorUtils.alphaColor(fade, textColor));
      c.drawLine(x, cy - h, x, cy + h, tickPaint);
    }
  }

  // Touch

  private boolean isTracking, isDragging;
  private int activePointerId = -1;
  private float downX, lastTouchX;
  private float dragStartLog, dragStartX;

  private final Runnable collapseRunnable = () -> {
    if (!isDragging) {
      dialAnimator.setValue(false, true);
    }
  };

  private final Runnable longPressRunnable = () -> {
    if (isTracking && !isDragging) {
      startDragging(lastTouchX);
    }
  };

  private boolean canTouch () {
    return touchEnabled && available && Views.isValid(this) && availableAnimator.getFloatValue() > 0f;
  }

  private void startDragging (float x) {
    removeCallbacks(longPressRunnable);
    removeCallbacks(collapseRunnable);
    cancelZoomAnimation();
    isDragging = true;
    dragStartX = x;
    dragStartLog = (float) Math.log(ratio);
    selectionAnimator.forceFactor(findSelectedIndex(ratio));
    if (!dialAnimator.getValue()) {
      dialAnimator.setValue(true, true);
      performTick();
    }
  }

  private void dragTo (float x) {
    float minLog = (float) Math.log(minRatio);
    float maxLog = (float) Math.log(maxRatio);
    float prevLog = (float) Math.log(ratio);
    // Finger to the left brings bigger values to the center, like a camera ruler
    float newLog = MathUtils.clamp(dragStartLog - (x - dragStartX) / getDialScale(), minLog, maxLog);
    if (newLog == prevLog) {
      return;
    }
    boolean crossed = false;
    for (float stop : stops) {
      float stopLog = (float) Math.log(stop);
      if ((prevLog < stopLog && newLog >= stopLog) || (prevLog > stopLog && newLog <= stopLog)) {
        crossed = true;
        break;
      }
    }
    if (!crossed && (newLog == minLog || newLog == maxLog)) {
      crossed = true;
    }
    float value = (float) Math.exp(newLog);
    if (newLog == minLog) {
      value = minRatio;
    } else if (newLog == maxLog) {
      value = maxRatio;
    }
    requestRatio(value, false);
    if (crossed) {
      performTick();
    }
  }

  private void finishTouch (boolean isTap, float x) {
    removeCallbacks(longPressRunnable);
    boolean wasDragging = isDragging;
    isTracking = false;
    isDragging = false;
    activePointerId = -1;
    if (wasDragging || dialAnimator.getValue()) {
      selectionAnimator.forceFactor(findSelectedIndex(ratio));
      removeCallbacks(collapseRunnable);
      postDelayed(collapseRunnable, COLLAPSE_DELAY);
    } else if (isTap) {
      animateToStop(indexAt(x));
    }
  }

  private void cancelTouch () {
    if (isTracking) {
      finishTouch(false, 0f);
    }
  }

  private void performTick () {
    int constant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 ? HapticFeedbackConstants.TEXT_HANDLE_MOVE :
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.KEYBOARD_TAP;
    performHapticFeedback(constant);
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    // Note: the record button may be held by another finger at the same time,
    // the parent splits motion events, so this view sees only its own pointer.
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        if (!canTouch()) {
          return false;
        }
        float x = e.getX(), y = e.getY();
        getBarRect(rect, dialAnimator.getFloatValue());
        if (!dialAnimator.getValue()) {
          // Small slop around the pill
          if (x < rect.left - Screen.dp(8f) || x > Math.max(rect.right, getControlsRight()) + Screen.dp(8f) || y < 0 || y > getMeasuredHeight()) {
            return false;
          }
        }
        ViewParent parent = getParent();
        if (parent != null) {
          parent.requestDisallowInterceptTouchEvent(true);
        }
        isTracking = true;
        activePointerId = e.getPointerId(0);
        downX = lastTouchX = x;
        if (dialAnimator.getValue()) {
          // The ruler is still open: keep adjusting right away
          startDragging(x);
        } else {
          postDelayed(longPressRunnable, LONG_PRESS_DELAY);
        }
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        if (!isTracking) {
          return false;
        }
        int index = e.findPointerIndex(activePointerId);
        if (index == -1) {
          return true;
        }
        float x = e.getX(index);
        lastTouchX = x;
        if (!canTouch()) {
          cancelTouch();
          return true;
        }
        if (!isDragging && Math.abs(x - downX) > Screen.getTouchSlop()) {
          startDragging(x);
        }
        if (isDragging) {
          dragTo(x);
        }
        return true;
      }
      case MotionEvent.ACTION_POINTER_UP: {
        if (isTracking && e.getPointerId(e.getActionIndex()) == activePointerId) {
          finishTouch(false, e.getX(e.getActionIndex()));
        }
        return true;
      }
      case MotionEvent.ACTION_UP: {
        if (isTracking) {
          finishTouch(canTouch(), e.getX());
        }
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (isTracking) {
          finishTouch(false, e.getX());
        }
        return true;
      }
    }
    return isTracking;
  }

  // Accessibility: click goes to the next stop, scroll actions step through the stops

  @Override
  public void onInitializeAccessibilityNodeInfo (AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (available) {
      info.setClickable(true);
      info.setScrollable(true);
      info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
      info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
      info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }
  }

  @Override
  public boolean performAccessibilityAction (int action, @Nullable Bundle arguments) {
    if (available && touchEnabled) {
      int selected = findSelectedIndex(ratio);
      switch (action) {
        case AccessibilityNodeInfo.ACTION_CLICK:
          animateToStop((selected + 1) % stops.length);
          return true;
        case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
          animateToStop(Math.min(selected + 1, stops.length - 1));
          return true;
        case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
          // From 1.4× go back to 1×, from 1× to .6×
          int target = Math.abs(ratio - stops[selected]) > EPSILON ? selected : selected - 1;
          animateToStop(Math.max(target, 0));
          return true;
        }
      }
    }
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    removeCallbacks(collapseRunnable);
    removeCallbacks(longPressRunnable);
    cancelZoomAnimation();
  }
}
