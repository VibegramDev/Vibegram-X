/*
 * This file is a part of Vibegram X
 *
 * Backdrop of the bottom dock (navigation style "dock"): an opaque surface behind the folder chips and the floating
 * dock, so chat rows never show through between the chips or around the dock (portrait and landscape). The list
 * fades into it right below its last row: the fade lies inside the bottom scroll space of the list, so a row
 * scrolled to the end is never dimmed.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;
import org.thunderdog.challegram.tool.Screen;

public class M3DockScrimView extends View implements ThemeInvalidateListener {
  /** Height of the fade above the opaque surface (transparent -> {@code surface}). */
  public static int getFadeHeight () {
    return Screen.dp(16f);
  }

  // The fade is an eased gradient of unit height stretched by a local matrix (the opaque top follows the folder chips
  // on every frame of their show/hide animation, the shader is created once per color). No anti-aliasing, so the
  // fade and the opaque rect meet without a seam.
  private final Paint fadePaint = new Paint(), fillPaint = new Paint();
  private final Matrix fadeMatrix = new Matrix();
  private int fadeColor;
  private boolean fadeShaderReady;
  private float opaqueTopOffset;

  public M3DockScrimView (Context context) {
    super(context);
  }

  /**
   * @param opaqueTopOffset distance from the bottom of this view to the top edge of the opaque surface
   */
  public void setOpaqueTopOffset (float opaqueTopOffset) {
    if (this.opaqueTopOffset != opaqueTopOffset) {
      this.opaqueTopOffset = opaqueTopOffset;
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
    if (height <= 0 || opaqueTopOffset <= 0) {
      return;
    }
    final int surface = M3.surface();
    if (!fadeShaderReady || fadeColor != surface) {
      fadeShaderReady = true;
      fadeColor = surface;
      fadePaint.setShader(M3BottomScrimView.newEasedFade(surface, 0, 0xFF));
    }
    final int width = getWidth();
    final float top = Math.max(0, height - opaqueTopOffset);
    final int fade = getFadeHeight();
    fadeMatrix.setScale(1f, fade);
    fadeMatrix.postTranslate(0, top - fade);
    fadePaint.getShader().setLocalMatrix(fadeMatrix);
    c.drawRect(0, top - fade, width, top, fadePaint);
    fillPaint.setColor(surface);
    c.drawRect(0, top, width, height, fillPaint);
  }
}
