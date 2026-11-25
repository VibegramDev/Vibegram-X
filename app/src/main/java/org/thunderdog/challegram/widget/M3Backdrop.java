/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;

/**
 * Vibegram X: blur of the content behind the floating bottom containers (dock, message composer) on Android 12+, on by
 * default, «Blur under bars» in the interface settings turns it off (owner). As the official app does on Android 12+
 * ({@code SizeNotifierFrameLayout}: {@code RenderNode} + {@code RenderEffect}), all on the GPU:
 * <ul>
 *   <li>a content view (chat wallpaper, message list, chat list) records what it draws into the node of its
 *   {@link Source} and draws that node in place;</li>
 *   <li>the scrim of the container draws the same nodes once more through a blur effect inside its band ({@link Blur}),
 *   over an opaque surface, and an eased ramp from the top of the band blends them into the sharp content.</li>
 * </ul>
 * The blur references the source nodes: when the content redraws (scroll, animated stickers), the blur follows without
 * redrawing the scrim. Older Android: no blur (a CPU blur of the official app is too heavy for a scroll).
 */
public final class M3Backdrop {
  private M3Backdrop () { }

  /** A small blur: the content behind stays recognizable, only its details go. */
  private static final float BLUR_RADIUS_DP = 12f;

  public static boolean isSupported () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
  }

  public static boolean isEnabled () {
    return isSupported() && !Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_BACKDROP_BLUR);
  }

  /**
   * Content behind a floating container. The owner view draws through it:
   * <pre>
   * public void draw (Canvas c) {
   *   Canvas content = backdrop.beginDraw(c);
   *   if (content != null) { super.draw(content); backdrop.endDraw(c); } else { super.draw(c); }
   * }</pre>
   * The owner must not scroll itself ({@code getScrollX/Y} == 0): its node is recorded without the scroll translation.
   */
  public static final class Source {
    private final View owner;
    private @Nullable Object node; // RenderNode

    public Source (@NonNull View owner) {
      this.owner = owner;
    }

    /** The canvas to draw the content into (then {@link #endDraw}), or null to draw as usual. */
    public @Nullable Canvas beginDraw (@NonNull Canvas c) {
      if (!c.isHardwareAccelerated() || !isEnabled()) {
        return null;
      }
      return Api31.beginDraw(this);
    }

    public void endDraw (@NonNull Canvas c) {
      Api31.endDraw(this, c);
    }
  }

  /** Draws the blurred sources in the band of a scrim. */
  public static final class Blur {
    private @Nullable Object node; // RenderNode
    private float radius;
    private final Paint fillPaint = new Paint();
    private final Paint maskPaint = new Paint();
    private final Matrix matrix = new Matrix();

    public Blur () {
      maskPaint.setShader(M3BottomScrimView.newEasedFade(0xFF000000, 0, 0xFF));
      maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /**
     * In the coordinates of {@code view}: from {@code top} to {@code bottom}, the sources blurred over the surface,
     * transparent at {@code top}, eased to opaque at {@code rampBottom}. Nothing when blur is off or no source is ready.
     */
    public void draw (@NonNull Canvas c, @NonNull View view, float top, float rampBottom, float bottom, @Nullable Source[] sources) {
      if (sources == null || bottom <= top || rampBottom <= top || !c.isHardwareAccelerated() || !isEnabled()) {
        return;
      }
      Api31.drawBlur(this, c, view, top, rampBottom, bottom, sources);
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private static final class Api31 {
    static Canvas beginDraw (Source source) {
      RenderNode node = (RenderNode) source.node;
      if (node == null) {
        node = new RenderNode("vgBackdrop");
        // As the view itself: children of a parent without clipChildren may draw past its bounds.
        node.setClipToBounds(false);
        source.node = node;
      }
      node.setPosition(0, 0, source.owner.getWidth(), source.owner.getHeight());
      return node.beginRecording();
    }

    static void endDraw (Source source, Canvas c) {
      RenderNode node = (RenderNode) source.node;
      node.endRecording();
      c.drawRenderNode(node);
    }

    static void drawBlur (Blur blur, Canvas c, View view, float top, float rampBottom, float bottom, Source[] sources) {
      final float radius = Screen.dp(BLUR_RADIUS_DP);
      RenderNode node = (RenderNode) blur.node;
      if (node == null) {
        node = new RenderNode("vgBackdropBlur");
        blur.node = node;
      }
      if (blur.radius != radius) {
        blur.radius = radius;
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
      }
      // Views.getLocationInWindow returns one shared array: copy the values.
      int[] location = Views.getLocationInWindow(view);
      final int viewX = location[0], viewY = location[1];
      // The content right above the band is sampled too: the blur at its top is not clamped to an edge.
      final int nodeTop = (int) Math.floor(top - radius * 1.5f);
      final int width = view.getWidth();
      node.setPosition(0, nodeTop, width, (int) Math.ceil(bottom));
      RecordingCanvas rc = node.beginRecording();
      rc.translate(0, -nodeTop);
      boolean hasContent = false;
      for (Source source : sources) {
        if (source == null || !source.owner.isShown()) {
          continue;
        }
        RenderNode sourceNode = (RenderNode) source.node;
        if (sourceNode == null || !sourceNode.hasDisplayList()) {
          // Blur was just turned on: the source records itself on its next draw.
          source.owner.invalidate();
          continue;
        }
        location = Views.getLocationInWindow(source.owner);
        rc.save();
        rc.translate(location[0] - viewX, location[1] - viewY);
        rc.drawRenderNode(sourceNode);
        rc.restore();
        hasContent = true;
      }
      node.endRecording();
      if (!hasContent) {
        return;
      }
      final int save = c.saveLayer(0, top, width, bottom, null);
      // Opaque: transparent parts of the content must not let the sharp content through.
      blur.fillPaint.setColor(M3.surface());
      c.drawRect(0, top, width, bottom, blur.fillPaint);
      c.drawRenderNode(node);
      blur.matrix.setScale(1f, rampBottom - top);
      blur.matrix.postTranslate(0, top);
      blur.maskPaint.getShader().setLocalMatrix(blur.matrix);
      c.drawRect(0, top, width, bottom, blur.maskPaint);
      c.restoreToCount(save);
    }
  }
}
