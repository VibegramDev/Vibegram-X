/*
 * This file is a part of Vibegram X
 *
 * Fade behind the bottom dock (navigation style "dock"): the chat list washes out behind the floating dock and the
 * folder chips. This view is as tall as the band the list keeps free for them (its bottom padding), so the fade never
 * covers a chat resting above the band (see M3BottomScrimView).
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import org.thunderdog.challegram.theme.ThemeInvalidateListener;

public class M3DockScrimView extends View implements ThemeInvalidateListener {
  private final M3BottomScrimView.Fade fade = new M3BottomScrimView.Fade();

  public M3DockScrimView (Context context) {
    super(context);
  }

  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    invalidate();
  }

  @Override
  protected void onDraw (Canvas c) {
    fade.draw(c, getWidth(), 0, getHeight(), 1f);
  }
}
