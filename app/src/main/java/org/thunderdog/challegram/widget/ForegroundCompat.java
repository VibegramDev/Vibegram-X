/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
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
 * draw / state / hotspot calls to the static methods here (the field may still be null while the View
 * constructor runs). {@link HostView} does this for a plain View.
 */
public final class ForegroundCompat {
  public interface Host {
    @Nullable ForegroundCompat foregroundCompat ();
  }

  public static class HostView extends View implements Host {
    private final ForegroundCompat foregroundCompat = new ForegroundCompat(this);

    public HostView (Context context) {
      super(context);
    }

    @Nullable
    @Override
    public final ForegroundCompat foregroundCompat () {
      return foregroundCompat;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      super.draw(c);
      ForegroundCompat.draw(foregroundCompat, c);
    }

    @Override
    protected boolean verifyDrawable (@NonNull Drawable who) {
      return super.verifyDrawable(who) || ForegroundCompat.verifyDrawable(foregroundCompat, who);
    }

    @Override
    protected void drawableStateChanged () {
      super.drawableStateChanged();
      ForegroundCompat.drawableStateChanged(foregroundCompat);
    }

    @Override
    public void drawableHotspotChanged (float x, float y) {
      super.drawableHotspotChanged(x, y);
      ForegroundCompat.drawableHotspotChanged(foregroundCompat, x, y);
    }

    @Override
    public void jumpDrawablesToCurrentState () {
      super.jumpDrawablesToCurrentState();
      ForegroundCompat.jumpDrawablesToCurrentState(foregroundCompat);
    }
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

  /** Call after super.draw(). */
  public static void draw (@Nullable ForegroundCompat foreground, @NonNull Canvas c) {
    if (foreground != null && foreground.drawable != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      foreground.drawable.setBounds(0, 0, foreground.view.getWidth(), foreground.view.getHeight());
      foreground.drawable.draw(c);
    }
  }

  public static boolean verifyDrawable (@Nullable ForegroundCompat foreground, @NonNull Drawable who) {
    return foreground != null && who == foreground.drawable;
  }

  public static void drawableStateChanged (@Nullable ForegroundCompat foreground) {
    if (foreground != null && foreground.drawable != null && foreground.drawable.isStateful()) {
      foreground.drawable.setState(foreground.view.getDrawableState());
    }
  }

  public static void drawableHotspotChanged (@Nullable ForegroundCompat foreground, float x, float y) {
    if (foreground != null && foreground.drawable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      foreground.drawable.setHotspot(x, y);
    }
  }

  public static void jumpDrawablesToCurrentState (@Nullable ForegroundCompat foreground) {
    if (foreground != null && foreground.drawable != null) {
      foreground.drawable.jumpToCurrentState();
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
}
