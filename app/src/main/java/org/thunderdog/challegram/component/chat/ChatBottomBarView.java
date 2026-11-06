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
 * File created on 21/12/2019
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.widget.BaseView;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * Action of a chat without the composer (subscribe to the channel, discuss, mute, unpin all...): M3 medium button that
 * floats in place of the composer, as the join button of groups ({@link org.thunderdog.challegram.widget.M3ButtonView}):
 * centred, fully rounded, 56dp tall, as wide as its content with 24dp padding, at least 120dp. Subscribing is the
 * «final or unblocking» action — filled (primary / onPrimary), label only; the other actions are tonal
 * (secondaryContainer / onSecondaryContainer) with their icon (24dp, 8dp before the label).
 *
 * While the chat is scrolled up it collapses into a floating chat button stacked above the scroll-to-bottom one: tonal
 * medium square icon button, 56dp with 16dp corners.
 *
 * The owner sets the paddings: the button is centred between them, the rest of the view is transparent and lets
 * touches through.
 */
public class ChatBottomBarView extends BaseView {
  private static final float HEIGHT = 56f, PADDING = 24f, MIN_WIDTH = 120f, ICON_SIZE = 24f, ICON_GAP = 8f;
  /** Collapsed: the floating chat buttons of {@code MessagesController} (FLOATING_BUTTON_*). */
  private static final float COLLAPSED_SIZE = 56f, COLLAPSED_CORNER = 16f, COLLAPSED_ELEVATION = 1.5f;

  private final ButtonShape buttonMask;
  private final M3StateLayerDrawable stateLayer;
  private final RectF rect = new RectF();

  public ChatBottomBarView (Context context, Tdlib tdlib) {
    super(context, tdlib);
    Views.setClickable(this);
    buttonMask = new ButtonShape(false);
    stateLayer = new M3StateLayerDrawable(new ButtonShape(true), buttonMask, pressedColor());
    ViewUtils.setBackground(this, stateLayer);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new android.view.ViewOutlineProvider() {
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void getOutline (View view, android.graphics.Outline outline) {
          RectF rectF = buildRect();
          outline.setRoundRect(Math.round(rectF.left), Math.round(rectF.top), Math.round(rectF.right), Math.round(rectF.bottom), calculateRadius());
        }
      });
    }
  }

  // Shape

  private float buttonWidth () {
    float width = state.width;
    if (state.prevState != null) {
      width = MathUtils.fromTo(state.prevState.width, state.width, state.factor);
    }
    return MathUtils.fromTo(width, Screen.dp(COLLAPSED_SIZE), collapseFactor);
  }

  /** Current bounds of the button (shared rect: copy it before the next call). */
  private RectF buildRect () {
    float cx = (getPaddingLeft() + getMeasuredWidth() - getPaddingRight()) / 2f;
    float cy = (getPaddingTop() + getMeasuredHeight() - getPaddingBottom()) / 2f;
    float width = state != null ? buttonWidth() : 0f;
    float height = MathUtils.fromTo(Screen.dp(HEIGHT), Screen.dp(COLLAPSED_SIZE), collapseFactor);
    rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
    return rect;
  }

  /** Full rounding of the pill, 16dp corners of the collapsed square button. */
  private float calculateRadius () {
    return MathUtils.fromTo(Screen.dp(HEIGHT) / 2f, Screen.dp(COLLAPSED_CORNER), collapseFactor);
  }

  /** Bounds of the button in the coordinates of this view (the composer scrim fades the messages under it). */
  public boolean getButtonRect (RectF out) {
    if (state == null || getMeasuredWidth() == 0) {
      return false;
    }
    out.set(buildRect());
    return true;
  }

  // Colours

  private static @ColorInt int containerColor (boolean filled) {
    return filled ? M3.primary() : M3.secondaryContainer();
  }

  private static @ColorInt int contentColor (boolean filled) {
    return filled ? M3.onPrimary() : M3.onSecondaryContainer();
  }

  private @ColorInt int containerColor () {
    int color = containerColor(state.filled);
    if (state.prevState != null) {
      color = ColorUtils.fromToArgb(containerColor(state.prevState.filled), color, state.factor);
    }
    // Collapsed: tonal, as the scroll-to-bottom button (ColorId.circleButtonChat).
    return ColorUtils.fromToArgb(color, M3.secondaryContainer(), collapseFactor);
  }

  private @ColorInt int pressedColor () {
    boolean filled = state != null && state.filled && collapseFactor < .5f;
    return ColorUtils.alphaColor(M3.STATE_PRESSED, contentColor(filled));
  }

  /** Container of the button, or the mask of its pressed state layer, in the current shape. */
  private final class ButtonShape extends Drawable {
    private final boolean isContainer;

    ButtonShape (boolean isContainer) {
      this.isContainer = isContainer;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      if (state == null) {
        return;
      }
      RectF rectF = buildRect();
      float radius = calculateRadius();
      c.drawRoundRect(rectF, radius, radius, Paints.fillingPaint(isContainer ? containerColor() : 0xffffffff));
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  // Touch

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      if (state == null) {
        return false;
      }
      RectF rectF = buildRect();
      float x = e.getX();
      float y = e.getY();
      if (x < rectF.left || x > rectF.right || y < rectF.top || y > rectF.bottom) {
        return false;
      }
    }
    return super.onTouchEvent(e);
  }

  // Content

  private static class State {
    private final String text;
    private final @DrawableRes int iconRes;
    private final boolean filled;
    private final @Nullable Drawable icon;
    private final TextColorSet colorSet;

    private @Nullable Text label;
    private int layoutWidth = -1;
    /** Width of the expanded button. */
    private int width;

    private float factor;
    private @Nullable State prevState;

    public State (String text, @DrawableRes int iconRes, boolean filled) {
      this.text = text;
      this.iconRes = iconRes;
      this.filled = filled;
      this.icon = iconRes != 0 ? Drawables.get(iconRes) : null;
      this.colorSet = filled ? M3::onPrimary : M3::onSecondaryContainer;
    }

    /** Filled button shows only its label (as the join button), tonal ones their icon before it. */
    private boolean showsIconExpanded () {
      return !filled && icon != null;
    }

    private int iconSpace () {
      return showsIconExpanded() ? Screen.dp(ICON_SIZE) + Screen.dp(ICON_GAP) : 0;
    }

    /** @param availableWidth the widest the button may be */
    public void layout (int availableWidth) {
      if (layoutWidth == availableWidth) {
        return;
      }
      layoutWidth = availableWidth;
      final int padding = Screen.dp(PADDING);
      final int iconSpace = iconSpace();
      // Built for all the available space, so the label is ellipsized only when it really does not fit: built for
      // exactly its own width it was cut (Text rounds the widths of words down).
      int maxTextWidth = availableWidth - padding * 2 - iconSpace;
      this.label = maxTextWidth > 0 && !text.isEmpty() ? new Text.Builder(text, maxTextWidth, Paints.robotoStyleProvider(16f), colorSet).allBold().singleLine().build() : null;
      int contentWidth = (label != null ? label.getWidth() : 0) + iconSpace;
      this.width = Math.max(0, Math.min(availableWidth, Math.max(Screen.dp(MIN_WIDTH), contentWidth + padding * 2)));
    }

    private static final float SCALE = .8f;

    public void draw (Canvas c, float collapseFactor, float factor, float cx, float cy) {
      if (prevState != null) {
        c.save();
        float displayFactor = 1f - this.factor;
        float scale = SCALE + (1f - SCALE) * displayFactor;
        c.scale(scale, scale, cx, cy);
        prevState.draw(c, collapseFactor, displayFactor, cx, cy);
        c.restore();
      }
      factor *= this.factor;
      final int saveCount;
      final boolean needScale = factor != 1f;
      if (needScale) {
        saveCount = Views.save(c);
        float scale = SCALE + (1f - SCALE) * factor;
        c.scale(scale, scale, cx, cy);
      } else {
        saveCount = -1;
      }
      final boolean rtl = Lang.rtl();
      final int iconSpace = iconSpace();
      final int labelWidth = label != null ? label.getWidth() : 0;
      final float contentLeft = cx - (iconSpace + labelWidth) / 2f;
      final float labelAlpha = factor * (1f - collapseFactor);
      if (label != null && labelAlpha > 0f) {
        float labelLeft = rtl ? contentLeft : contentLeft + iconSpace;
        label.draw(c, Math.round(labelLeft), Math.round(cy - label.getHeight() / 2f), colorSet, labelAlpha);
      }
      if (icon != null) {
        // Tonal: the icon slides from its place before the label to the centre of the collapsed button.
        // Filled: the label fades out, the icon fades in at the centre.
        final boolean expandedIcon = showsIconExpanded();
        float iconAlpha = expandedIcon ? factor : factor * collapseFactor;
        if (iconAlpha > 0f) {
          float iconCx = cx;
          if (expandedIcon) {
            float halfIcon = Screen.dp(ICON_SIZE) / 2f;
            float fromCx = rtl ? contentLeft + iconSpace + labelWidth - halfIcon : contentLeft + halfIcon;
            iconCx = MathUtils.fromTo(fromCx, cx, collapseFactor);
          }
          int color = ColorUtils.fromToArgb(colorSet.defaultTextColor(), M3.onSecondaryContainer(), collapseFactor);
          Drawables.drawCentered(c, icon, iconCx, cy, Paints.getPorterDuffPaint(ColorUtils.alphaColor(iconAlpha, color)));
        }
      }
      if (needScale) {
        Views.restore(c, saveCount);
      }
    }
  }

  private State state;
  private final BoolAnimator replaceAnimator = new BoolAnimator(0, (id, factor, fraction, callee) -> {
    if (this.state != null) {
      this.state.factor = factor;
      if (factor == 1f) {
        this.state.prevState = null;
      }
      update();
    }
  }, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  private int availableWidth () {
    return Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
  }

  private void layoutStates () {
    final int availableWidth = availableWidth();
    for (State state = this.state; state != null; state = state.prevState) {
      state.layout(availableWidth);
    }
  }

  /**
   * @param filled the final action of the chat (subscribe): filled button with the label only; otherwise tonal with
   * the icon. The icon is shown alone when the button is collapsed.
   */
  public void setAction (int id, String text, @DrawableRes int iconRes, boolean filled, boolean animated) {
    if (this.state != null && this.state.text.equals(text) && this.state.iconRes == iconRes && this.state.filled == filled) {
      return;
    }
    setId(id);
    setContentDescription(text);

    State newState = new State(text, iconRes, filled);
    newState.layout(availableWidth());

    if (!animated || this.state == null) {
      replaceAnimator.setValue(false, false);
      this.state = newState;
      this.state.factor = 1f;
      update();
      return;
    }

    State prevState = this.state;
    // A replacement in progress jumps to its end: only two states are drawn.
    prevState.prevState = null;
    prevState.factor = 1f;
    this.state = null;
    replaceAnimator.setValue(false, false);
    newState.prevState = prevState;
    this.state = newState;
    replaceAnimator.setValue(true, true);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    layoutStates();
  }

  private float collapseFactor;

  public void setCollapseFactor (float collapseFactor) {
    if (this.collapseFactor != collapseFactor) {
      this.collapseFactor = collapseFactor;
      update();
    }
  }

  @Override
  public void setPadding (int left, int top, int right, int bottom) {
    boolean updated = left != getPaddingLeft() || top != getPaddingTop() || right != getPaddingRight() || bottom != getPaddingBottom();
    super.setPadding(left, top, right, bottom);
    if (updated && stateLayer != null) {
      layoutStates();
      update();
    }
  }

  public void update () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      invalidateOutline();
      // The pill casts the shadow of the composer scrim; the collapsed button the one of the floating chat buttons.
      setElevation(Screen.dp(COLLAPSED_ELEVATION) * collapseFactor);
    }
    if (stateLayer != null) {
      // The mask changes with the shape: the ripple caches it.
      buttonMask.invalidateSelf();
      stateLayer.invalidateSelf();
    }
    invalidate();
  }

  @Override
  protected void onDraw (Canvas c) {
    if (state == null) {
      return;
    }
    stateLayer.setColor(pressedColor());
    RectF rectF = buildRect();
    float cx = rectF.centerX(), cy = rectF.centerY();
    int saveCount = Views.save(c);
    c.clipRect(rectF.left, rectF.top, rectF.right, rectF.bottom);
    state.draw(c, collapseFactor, 1f, cx, cy);
    Views.restore(c, saveCount);
  }
}
