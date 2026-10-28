/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.core.ColorUtils;

/**
 * M3 Expressive loading indicator (m3.material.io/components/loading-indicator, compose LoadingIndicator):
 * uncontained, 48dp container, 38dp active indicator in primary. Morphs SoftBurst → Cookie9 → Pentagon → Pill →
 * Sunny → Cookie4 → Oval → … every 650ms on a spring (damping 0.6, stiffness 200), each morph adds a 90° turn,
 * plus a linear global rotation of 360° per 4666ms. Shapes come from androidx.graphics.shapes
 * ({@link M3LoadingShapes}, absent in the legacy flavor — check {@link #isSupported()}).
 */
public final class M3LoadingIndicator {
  public interface Shapes {
    int count ();
    /** Writes the morph {@code index} at {@code progress} into {@code out}, in normalized [0..1] coordinates. */
    void toPath (int index, float progress, Path out);
    /** Scale that keeps every rotating shape inside the container, multiplied by ActiveSize / ContainerSize. */
    float scaleFactor ();
  }

  public static final float CONTAINER_SIZE = 48f, ACTIVE_SIZE = 38f;
  private static final long MORPH_INTERVAL_MS = 650, GLOBAL_ROTATION_MS = 4666;
  private static final double STIFFNESS = 200, DAMPING = .6;
  /** Time when compose would stop the spring (visibilityThreshold 0.1, velocity threshold 0.1 * 62.5). */
  private static final float SPRING_END;

  static {
    float end = MORPH_INTERVAL_MS / 1000f;
    for (int ms = 1; ms < MORPH_INTERVAL_MS; ms++) {
      float t = ms / 1000f;
      if (Math.abs(1f - spring(t)) < .1f && Math.abs(springVelocity(t)) < .1f * 62.5f) {
        end = t;
        break;
      }
    }
    SPRING_END = end;
  }

  private static float spring (float t) {
    double w0 = Math.sqrt(STIFFNESS), wd = w0 * Math.sqrt(1 - DAMPING * DAMPING);
    double decay = Math.exp(-DAMPING * w0 * t);
    return (float) (1 - decay * (Math.cos(wd * t) + DAMPING * w0 / wd * Math.sin(wd * t)));
  }

  private static float springVelocity (float t) {
    double w0 = Math.sqrt(STIFFNESS), wd = w0 * Math.sqrt(1 - DAMPING * DAMPING);
    return (float) (w0 * w0 / wd * Math.exp(-DAMPING * w0 * t) * Math.sin(wd * t));
  }

  private static Shapes shapes;
  private static boolean shapesLoaded;

  public static @Nullable Shapes shapes () {
    if (!shapesLoaded) {
      shapesLoaded = true;
      try {
        shapes = M3LoadingShapes.create();
      } catch (Throwable t) {
        Log.e("Cannot build M3 loading indicator shapes", t);
        shapes = null;
      }
    }
    return shapes;
  }

  public static boolean isSupported () {
    return shapes() != null;
  }

  private final Path path = new Path();
  private final Matrix matrix = new Matrix();
  private final RectF bounds = new RectF();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private @Nullable View view;
  private long startTime;
  private float alpha = 1f;
  private boolean hasForcedColor;
  private @ColorInt int forcedColor;

  public void attachToView (View view) {
    if (this.view != view) {
      this.view = view;
      if (startTime == 0) {
        startTime = SystemClock.uptimeMillis();
      }
      view.postInvalidateOnAnimation();
    }
  }

  public void detachFromView (View view) {
    if (this.view == view) {
      this.view = null;
    }
  }

  public void setAlpha (float alpha) {
    this.alpha = alpha;
  }

  public void forceColor (@ColorInt int color) {
    this.hasForcedColor = true;
    this.forcedColor = color;
  }

  public void draw (Canvas c, int left, int top, int right, int bottom) {
    Shapes shapes = shapes();
    if (shapes == null || alpha <= 0f) {
      return;
    }
    long elapsed = startTime != 0 ? SystemClock.uptimeMillis() - startTime : 0;
    long cycle = elapsed / MORPH_INTERVAL_MS;
    float t = (elapsed % MORPH_INTERVAL_MS) / 1000f;
    // After the spring settles compose snaps to the next shape at progress 0: the same picture as progress 1.
    float progress = t < SPRING_END ? spring(t) : 1f;
    int index = (int) (cycle % shapes.count());
    float rotation = progress * 90f + 90f * ((cycle + 1) % 4) + 360f * (elapsed % GLOBAL_ROTATION_MS) / GLOBAL_ROTATION_MS;

    float size = Math.min(Screen.dp(CONTAINER_SIZE), Math.min(right - left, bottom - top));
    float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
    shapes.toPath(index, progress, path);
    float scale = size * shapes.scaleFactor();
    matrix.setScale(scale, scale);
    path.transform(matrix);
    path.computeBounds(bounds, true);
    path.offset(cx - bounds.centerX(), cy - bounds.centerY());

    int color = hasForcedColor ? forcedColor : M3.primary();
    paint.setColor(ColorUtils.alphaColor(alpha, color));
    c.save();
    c.rotate(rotation, cx, cy);
    c.drawPath(path, paint);
    c.restore();

    if (view != null) {
      view.postInvalidateOnAnimation();
    }
  }
}
