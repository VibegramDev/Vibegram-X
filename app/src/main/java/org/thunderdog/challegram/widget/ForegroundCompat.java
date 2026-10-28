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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * {@link View#setForeground} (pressed state layer above the content) for every flavor: View#setForeground is
 * API 23, FrameLayout has its own since API 1, other views below API 23 implement {@link Host} and forward
 * draw / state / hotspot calls here (fields may still be null while the View constructor runs).
 */
public final class ForegroundCompat {
  public interface Host {
    @Nullable ForegroundCompat foregroundCompat ();
  }

  public static void set (@NonNull View view, @Nullable Drawable drawable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      view.setForeground(drawable);
    } else if (view instanceof FrameLayout) {
      ((FrameLayout) view).setForeground(drawable);
    } else if (view instanceof Host && ((Host) view).foregroundCompat() != null) {
      //noinspection ConstantConditions
      ((Host) view).foregroundCompat().setDrawable(drawable);
    } else {
      throw new IllegalArgumentException(view.getClass().getName() + " must implement ForegroundCompat.Host");
    }
  }

  private final View view;
  private @Nullable Drawable drawable;

  public ForegroundCompat (@NonNull View view) {
    this.view = view;
  }

  private void setDrawable (@Nullable Drawable drawable) {
    if (this.drawable == drawable) {
      return;
    }
    if (this.drawable != null) {
      this.drawable.setCallback(null);
    }
    this.drawable = drawable;
    if (drawable != null) {
      drawable.setCallback(view);
      if (drawable.isStateful()) {
        drawable.setState(view.getDrawableState());
      }
    }
    view.invalidate();
  }

  /** Call after super.draw(). */
  public void draw (@NonNull Canvas c) {
    if (drawable != null) {
      drawable.setBounds(0, 0, view.getWidth(), view.getHeight());
      drawable.draw(c);
    }
  }

  public boolean verifyDrawable (@NonNull Drawable who) {
    return who == drawable;
  }

  public void drawableStateChanged () {
    if (drawable != null && drawable.isStateful()) {
      drawable.setState(view.getDrawableState());
    }
  }

  public void drawableHotspotChanged (float x, float y) {
    if (drawable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      drawable.setHotspot(x, y);
    }
  }

  public void jumpDrawablesToCurrentState () {
    if (drawable != null) {
      drawable.jumpToCurrentState();
    }
  }
}
