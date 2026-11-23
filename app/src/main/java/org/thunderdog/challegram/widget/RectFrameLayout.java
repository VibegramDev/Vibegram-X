/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 09/01/2017
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.android.widget.FrameLayoutFix;

public class RectFrameLayout extends FrameLayoutFix {
  private int top, bottom;

  public RectFrameLayout (@NonNull Context context) {
    super(context);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !Config.DEBUG_CLIPPING) {
      setOutlineProvider(new android.view.ViewOutlineProvider() {
        @Override
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        public void getOutline (View view, android.graphics.Outline outline) {
          outline.setRect(0, top, view.getMeasuredWidth(), view.getMeasuredHeight() - bottom);
        }
      });
      setClipToOutline(true);
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    checkOutline();
  }

  private void checkOutline () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !Config.DEBUG_CLIPPING) {
      invalidateOutline();
    }
  }

  public void setClip (int top, int bottom) {
    if (this.top != top || this.bottom != bottom) {
      this.top = top;
      this.bottom = bottom;
      checkOutline();
    }
  }

  private final Path excludedPath = new Path();
  private boolean hasExcludedPath;

  /** Area the children must not draw over (floating buttons of the chat above the round video), or an empty path. */
  public void setExcludedPath (Path path, boolean isEmpty) {
    if (isEmpty && !hasExcludedPath) {
      return;
    }
    hasExcludedPath = !isEmpty;
    excludedPath.set(path);
    invalidate();
  }

  /** Vibegram X: drawing over the children (the dates of the list the round video covers). */
  public interface OverDrawer {
    void drawOver (RectFrameLayout view, Canvas c);
  }

  private @Nullable OverDrawer overDrawer;

  public void setOverDrawer (@Nullable OverDrawer overDrawer) {
    this.overDrawer = overDrawer;
  }

  /**
   * Vibegram X: the list goes under the floating composer (or the bottom button) and fades out there
   * (M3BottomScrimView). The round video floats above the list: it gets the same fade and the same shadow of the
   * composer, drawn only over what the children have drawn.
   */
  public static final class BottomFade {
    public float fadeTop, alpha, radius;
    public final RectF shape = new RectF();
    public boolean hasShape;
  }

  private final BottomFade bottomFade = new BottomFade();
  private boolean hasBottomFade;
  private final M3BottomScrimView.Fade fade = new M3BottomScrimView.Fade();
  private Paint shadowPaint;

  public void setBottomFade (@Nullable BottomFade value) {
    boolean has = value != null && value.alpha > 0f;
    if (!has && !hasBottomFade) {
      return;
    }
    if (has && hasBottomFade && bottomFade.fadeTop == value.fadeTop && bottomFade.alpha == value.alpha && bottomFade.radius == value.radius && bottomFade.hasShape == value.hasShape && (!value.hasShape || bottomFade.shape.equals(value.shape))) {
      return;
    }
    hasBottomFade = has;
    if (has) {
      bottomFade.fadeTop = value.fadeTop;
      bottomFade.alpha = value.alpha;
      bottomFade.radius = value.radius;
      bottomFade.hasShape = value.hasShape;
      bottomFade.shape.set(value.shape);
    }
    invalidate();
  }

  private boolean needBottomFade () {
    if (!hasBottomFade || bottomFade.fadeTop >= getMeasuredHeight() - bottom) {
      return false;
    }
    // Only while a child reaches the band
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == View.VISIBLE && child.getAlpha() > 0f && child.getWidth() > 0) {
        float childBottom = child.getTop() + child.getTranslationY() + child.getPivotY() + (child.getHeight() - child.getPivotY()) * child.getScaleY();
        if (childBottom > bottomFade.fadeTop) {
          return true;
        }
      }
    }
    return false;
  }

  private void drawChildren (Canvas c) {
    final boolean needFade = needBottomFade();
    final int layer = needFade ? c.saveLayer(0, 0, getMeasuredWidth(), getMeasuredHeight(), null) : -1;
    super.dispatchDraw(c);
    if (overDrawer != null) {
      overDrawer.drawOver(this, c);
    }
    if (needFade) {
      fade.setXfermode(SRC_ATOP);
      fade.draw(c, getMeasuredWidth(), bottomFade.fadeTop, getMeasuredHeight(), bottomFade.alpha);
      fade.setXfermode(null);
      if (bottomFade.hasShape) {
        if (shadowPaint == null) {
          shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
          shadowPaint.setXfermode(SRC_ATOP);
        }
        M3BottomScrimView.drawShadow(c, shadowPaint, bottomFade.shape, bottomFade.radius, bottomFade.alpha);
      }
      c.restoreToCount(layer);
    }
  }

  private static final android.graphics.PorterDuffXfermode SRC_ATOP = new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP);

  @Override
  protected void dispatchDraw(Canvas c) {
    if (hasExcludedPath) {
      final int saveCount = Views.save(c);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        c.clipOutPath(excludedPath);
      } else {
        //noinspection deprecation
        c.clipPath(excludedPath, Region.Op.DIFFERENCE);
      }
      dispatchDrawClipped(c);
      Views.restore(c, saveCount);
    } else {
      dispatchDrawClipped(c);
    }
  }

  private void dispatchDrawClipped (Canvas c) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !Config.DEBUG_CLIPPING) {
      drawChildren(c);
    } else {
      final int saveCount = Views.save(c);
      c.clipRect(0, top, getMeasuredWidth(), getMeasuredHeight() - bottom);
      drawChildren(c);
      Views.restore(c, saveCount);
    }
  }
}
