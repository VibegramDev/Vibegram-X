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
 * File created for forum topics icon display
 */
package org.thunderdog.challegram.util;

import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.TopicIcon;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

/**
 * DrawModifier that renders a topic icon on the left side of a SettingView: the custom emoji of the topic
 * ({@link TopicIcon}), or a circle of the topic color.
 */
public class TopicIconModifier implements DrawModifier, View.OnAttachStateChangeListener {

  private static final float ICON_SIZE_DP = 20f;
  private static final float LEFT_OFFSET_DP = 18f; // Left padding area, before text

  private final TopicIcon icon;
  private @Nullable ComplexReceiver iconReceiver;
  private @Nullable View attachedView;

  public TopicIconModifier (Tdlib tdlib, TdApi.ForumTopicInfo info) {
    this.icon = new TopicIcon(tdlib, info, Screen.dp(ICON_SIZE_DP), () -> {
      if (iconReceiver != null) {
        icon().requestFiles(iconReceiver);
      }
      if (attachedView != null) {
        attachedView.invalidate();
      }
    });
  }

  private TopicIcon icon () {
    return icon;
  }

  /**
   * Attach the modifier to a view to enable custom emoji rendering.
   * Must be called when the view is bound.
   */
  public void attachToView (View view) {
    if (attachedView == view) {
      return;
    }
    // A recycled view: the receiver of the previous view would invalidate the wrong one
    detach();
    this.attachedView = view;
    view.addOnAttachStateChangeListener(this);
    this.iconReceiver = new ComplexReceiver(view);
    if (view.isAttachedToWindow()) {
      iconReceiver.attach();
    }
    icon.requestFiles(iconReceiver);
  }

  /**
   * Detach from the view and destroy the receivers. Called automatically when the view leaves the window.
   */
  public void detach () {
    if (attachedView != null) {
      attachedView.removeOnAttachStateChangeListener(this);
      attachedView = null;
    }
    if (iconReceiver != null) {
      iconReceiver.performDestroy();
      iconReceiver = null;
    }
  }

  @Override
  public void onViewAttachedToWindow (View v) {
    if (iconReceiver != null) {
      iconReceiver.attach();
    }
  }

  @Override
  public void onViewDetachedFromWindow (View v) {
    detach();
  }

  @Override
  public void beforeDraw (View view, Canvas c) {
    if (attachedView != view) {
      attachToView(view);
    }
    int iconSize = Screen.dp(ICON_SIZE_DP);
    int left = Screen.dp(LEFT_OFFSET_DP);
    int top = view.getHeight() / 2 - iconSize / 2;
    if (!icon.needPlaceholder() && iconReceiver != null) {
      icon.draw(c, iconReceiver, left, top, left + iconSize, top + iconSize);
    } else {
      c.drawCircle(left + iconSize / 2f, top + iconSize / 2f, iconSize / 2f, Paints.fillingPaint(TopicIcon.color(icon.getInfo().icon)));
    }
  }

  @Override
  public int getWidth () {
    // The icon is drawn in the left padding: no space taken from the text
    return 0;
  }
}
