/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * M3 pressed state layer over optional content, clipped to {@code mask}, usable on every flavor: API 21+ draws a
 * RippleDrawable (no content, the given mask); below API 21 the mask is filled with the state layer color while
 * pressed. Subclass this instead of RippleDrawable: a class extending RippleDrawable cannot even be referenced
 * (instanceof) on Android 4.x.
 */
public class M3StateLayerDrawable extends Drawable implements Drawable.Callback {
  private final @Nullable Drawable content;
  private final @NonNull Drawable mask;
  /** RippleDrawable on API 21+, null below. */
  private final @Nullable Drawable ripple;
  private @ColorInt int color;
  private boolean pressed;

  public M3StateLayerDrawable (@Nullable Drawable content, @NonNull Drawable mask, @ColorInt int color) {
    this.content = content;
    this.mask = mask;
    this.color = color;
    if (content != null) {
      content.setCallback(this);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      ripple = new RippleDrawable(ColorStateList.valueOf(color), null, mask);
      ripple.setCallback(this);
    } else {
      ripple = null;
    }
  }

  public void setColor (@ColorInt int color) {
    if (this.color != color) {
      this.color = color;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && ripple != null) {
        ((RippleDrawable) ripple).setColor(ColorStateList.valueOf(color));
      } else if (pressed) {
        invalidateSelf();
      }
    }
  }

  @Override
  public void draw (@NonNull Canvas c) {
    if (content != null) {
      content.draw(c);
    }
    if (ripple != null) {
      ripple.draw(c);
    } else if (pressed && (color >>> 24) != 0) {
      Rect bounds = getBounds();
      //noinspection deprecation
      int saveCount = c.saveLayer(new RectF(bounds), null, Canvas.ALL_SAVE_FLAG);
      mask.draw(c);
      c.drawColor(color, PorterDuff.Mode.SRC_IN);
      c.restoreToCount(saveCount);
    }
  }

  @Override
  protected void onBoundsChange (@NonNull Rect bounds) {
    if (content != null) {
      content.setBounds(bounds);
    }
    if (ripple != null) {
      ripple.setBounds(bounds);
    } else {
      mask.setBounds(bounds);
    }
  }

  @Override
  public boolean isStateful () {
    return true;
  }

  @Override
  protected boolean onStateChange (@NonNull int[] state) {
    boolean changed = false;
    if (content != null && content.isStateful()) {
      changed = content.setState(state);
    }
    if (ripple != null) {
      changed |= ripple.setState(state);
    } else {
      boolean pressed = false;
      for (int s : state) {
        if (s == android.R.attr.state_pressed) {
          pressed = true;
          break;
        }
      }
      if (this.pressed != pressed) {
        this.pressed = pressed;
        changed = true;
      }
    }
    return changed;
  }

  @Override
  public void jumpToCurrentState () {
    if (content != null) {
      content.jumpToCurrentState();
    }
    if (ripple != null) {
      ripple.jumpToCurrentState();
    }
  }

  @Override
  public boolean setVisible (boolean visible, boolean restart) {
    boolean changed = super.setVisible(visible, restart);
    if (content != null) {
      content.setVisible(visible, restart);
    }
    if (ripple != null) {
      ripple.setVisible(visible, restart);
    }
    return changed;
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void setHotspot (float x, float y) {
    if (ripple != null) {
      ripple.setHotspot(x, y);
    }
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void setHotspotBounds (int left, int top, int right, int bottom) {
    if (ripple != null) {
      ripple.setHotspotBounds(left, top, right, bottom);
    }
  }

  /** Same as RippleDrawable: the mask defines the outline (view elevation shadow, clipping). */
  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void getOutline (@NonNull Outline outline) {
    mask.getOutline(outline);
  }

  @Override
  public void setAlpha (int alpha) {
    if (content != null) {
      content.setAlpha(alpha);
    }
  }

  @Override
  public void setColorFilter (@Nullable ColorFilter colorFilter) {
    if (content != null) {
      content.setColorFilter(colorFilter);
    }
  }

  @Override
  public int getOpacity () {
    return PixelFormat.TRANSLUCENT;
  }

  // Drawable.Callback: content and ripple animations invalidate the host view through this drawable.

  @Override
  public void invalidateDrawable (@NonNull Drawable who) {
    invalidateSelf();
  }

  @Override
  public void scheduleDrawable (@NonNull Drawable who, @NonNull Runnable what, long when) {
    scheduleSelf(what, when);
  }

  @Override
  public void unscheduleDrawable (@NonNull Drawable who, @NonNull Runnable what) {
    unscheduleSelf(what);
  }
}
