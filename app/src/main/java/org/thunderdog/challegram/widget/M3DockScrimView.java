/*
 * This file is a part of Vibegram X
 *
 * Fade behind the bottom dock (navigation style "dock"): the chat list washes out towards the bottom of
 * the screen, so the floating dock, the search FAB and the folder chips stay readable over any content.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;

import me.vkryl.core.MathUtils;

public class M3DockScrimView extends View implements ThemeInvalidateListener {
  /** Opacity of the surface at the top edge of the toolbar and at the bottom of the screen. */
  private static final int ALPHA_AT_DOCK = 0xB3, ALPHA_AT_BOTTOM = 0xEB;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int dockTopOffset;
  private int shaderColor, shaderHeight, shaderDockTop;

  public M3DockScrimView (Context context) {
    super(context);
  }

  /**
   * @param dockTopOffset distance from the bottom of this view to the top edge of the toolbar
   */
  public void setDockTopOffset (int dockTopOffset) {
    if (this.dockTopOffset != dockTopOffset) {
      this.dockTopOffset = dockTopOffset;
      invalidate();
    }
  }

  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    invalidate();
  }

  @Override
  protected void onDraw (Canvas c) {
    final int height = getHeight();
    if (height <= 0) {
      return;
    }
    final int surface = M3.surface();
    final int dockTop = Math.max(0, height - dockTopOffset);
    if (shaderColor != surface || shaderHeight != height || shaderDockTop != dockTop) {
      shaderColor = surface;
      shaderHeight = height;
      shaderDockTop = dockTop;
      float dockFraction = MathUtils.clamp((float) dockTop / height, .01f, .99f);
      // Eased, not linear: no visible line where the fade starts (see M3BottomScrimView.newSmoothFade).
      paint.setShader(M3BottomScrimView.newSmoothFade(height, surface, dockFraction, ALPHA_AT_DOCK, ALPHA_AT_BOTTOM));
    }
    c.drawRect(0, 0, getWidth(), height, paint);
  }
}
