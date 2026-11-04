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
 * File created on 10/11/2017
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.lambda.CancellableRunnable;
import me.vkryl.core.lambda.Destroyable;

public class VoiceVideoButtonView extends View implements FactorAnimator.Target, Settings.VideoModePreferenceListener, Destroyable, TooltipOverlayView.LocationProvider {
  private boolean hasTouchControls;

  private final Drawable sendIcon, micIcon, videoIcon, dictationIcon, doneIcon, searchIcon;
  /** Record mode icons in the order the tap cycles them: index = {@link Settings#RECORD_MODE_VOICE} etc. */
  private final Drawable[] modeIcons;

  public VoiceVideoButtonView (Context context) {
    super(context);
    sendIcon = Drawables.get(getResources(), R.drawable.deproko_baseline_send_24);
    micIcon = Drawables.get(getResources(), R.drawable.deproko_baseline_msg_voice_24);
    videoIcon = Drawables.get(getResources(), R.drawable.deproko_baseline_msg_video_24);
    dictationIcon = Drawables.get(getResources(), R.drawable.vg_dictation_24);
    doneIcon = Drawables.get(getResources(), R.drawable.baseline_check_24);
    searchIcon = Drawables.get(getResources(), R.drawable.baseline_search_24);
    modeIcons = new Drawable[] {micIcon, videoIcon, dictationIcon};
    setRecordMode(Settings.instance().getPreferredRecordMode(), false);
  }

  @Override
  public void getTargetBounds (View targetView, Rect outRect) {
    outRect.top += Screen.dp(8f);
    outRect.bottom -= Screen.dp(8f);
  }

  public void setHasTouchControls (boolean hasTouchControls) {
    if (this.hasTouchControls != hasTouchControls) {
      this.hasTouchControls = hasTouchControls;
      if (hasTouchControls) {
        Settings.instance().addVideoPreferenceChangeListener(this);
      } else {
        Settings.instance().removeVideoPreferenceChangeListener(this);
      }
      invalidate();
    }
  }

  private static final int ANIMATOR_RECORD_MODE = 0, ANIMATOR_SEARCH_MODE = 1;
  /**
   * Vibegram: position in the voice → round → dictation cycle (0..2). Going on from dictation back to voice
   * animates to 3 (voice again, below dictation) and snaps to 0, so the icons always roll the same way.
   */
  private final FactorAnimator recordModeAnimator = new FactorAnimator(ANIMATOR_RECORD_MODE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 120l);
  private int recordMode = Settings.RECORD_MODE_VOICE;
  private final BoolAnimator inSearchMode = new BoolAnimator(ANIMATOR_SEARCH_MODE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  @Override
  public void onPreferVideoModeChanged (boolean preferVideoMode) {
    setRecordMode(preferVideoMode ? Settings.RECORD_MODE_ROUND : Settings.RECORD_MODE_VOICE, hasTouchControls);
  }

  @Override
  public void onPreferRecordModeChanged (int recordMode) {
    setRecordMode(recordMode, hasTouchControls);
  }

  public void setInVideoMode (boolean inVideoMode, boolean animated) {
    setRecordMode(inVideoMode ? Settings.RECORD_MODE_ROUND : Settings.RECORD_MODE_VOICE, animated);
  }

  public void setRecordMode (int recordMode, boolean animated) {
    if (this.recordMode == recordMode && (animated || !recordModeAnimator.isAnimating())) {
      return;
    }
    this.recordMode = recordMode;
    if (animated) {
      float target = recordMode;
      if (target < recordModeAnimator.getFactor()) {
        target += modeIcons.length;
      }
      recordModeAnimator.animateTo(target);
    } else {
      recordModeAnimator.forceFactor(recordMode);
      invalidate();
    }
  }

  private boolean doneInsteadOfSend;

  /** The big recording circle finishes dictation with a check mark instead of the send arrow. */
  public void setDoneInsteadOfSend (boolean doneInsteadOfSend) {
    if (this.doneInsteadOfSend != doneInsteadOfSend) {
      this.doneInsteadOfSend = doneInsteadOfSend;
      invalidate();
    }
  }

  public void setInSearchMode (boolean inSearchMode, boolean animated) {
    this.inSearchMode.setValue(inSearchMode, animated);
  }

  private float sendFactor;

  public void setSendFactor (float factor) {
    if (this.sendFactor != factor) {
      this.sendFactor = factor;
      invalidate();
    }
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    invalidate();
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (id == ANIMATOR_RECORD_MODE && finalFactor >= modeIcons.length) {
      recordModeAnimator.forceFactor(finalFactor - modeIcons.length);
      invalidate();
    }
  }

  @Override
  public void performDestroy () {
    Settings.instance().removeVideoPreferenceChangeListener(this);
  }

  private Paint paint;

  private int iconColor;

  /** Icon color of the composer (M3 content color of its container), 0 for the default theme color. */
  public void setIconColor (int color) {
    if (this.iconColor != color) {
      this.iconColor = color;
      invalidate();
    }
  }

  private Paint getIconPaint () {
    int color = iconColor != 0 ? iconColor : Theme.getColor(ColorId.circleButtonRegularIcon);
    if (paint == null || paint.getColor() != color)
      paint = Paints.createPorterDuffPaint(paint, color);
    return paint;
  }

  @Override
  protected void onDraw (Canvas c) {
    final int viewWidth = getMeasuredWidth();
    final int viewHeight = getMeasuredHeight();
    final int cx = getPaddingLeft() + (viewWidth - getPaddingLeft() - getPaddingRight()) / 2;
    final int cy = viewHeight / 2;
    final float modeFactor = recordModeAnimator.getFactor();
    final float searchFactor = inSearchMode.getFloatValue();

    final Paint paint = hasTouchControls && iconColor == 0 ? Paints.getIconGrayPorterDuffPaint() : getIconPaint();
    final int savedAlpha = paint.getAlpha();
    final float generalFactor = (1f - sendFactor);
    if (generalFactor > 0f) {
      float alpha = generalFactor * (1f - searchFactor);
      if (alpha > 0f) {
        final float scale = Config.DEFAULT_ICON_SWITCH_SCALE + (1f - Config.DEFAULT_ICON_SWITCH_SCALE) * (1f - searchFactor);
        if (scale != 1f) {
          c.save();
          c.scale(scale, scale, cx, cy);
        }

        // The current mode icon leaves downwards while the next one comes in from above.
        final int span = cy + videoIcon.getMinimumHeight() / 2;
        for (int i = 0; i <= modeIcons.length; i++) {
          float distance = modeFactor - i;
          if (Math.abs(distance) >= 1f) {
            continue;
          }
          int y = (int) (cy + span * distance);
          paint.setAlpha((int) ((float) savedAlpha * (1f - Math.abs(distance)) * alpha));
          Drawables.drawCentered(c, modeIcons[i % modeIcons.length], cx, y, paint);
        }

        if (scale != 1f) {
          c.restore();
        }
      }
      alpha = generalFactor * searchFactor;
      if (alpha > 0f) {
        final float scale = Config.DEFAULT_ICON_SWITCH_SCALE + (1f - Config.DEFAULT_ICON_SWITCH_SCALE) * searchFactor;
        if (scale != 1f) {
          c.save();
          c.scale(scale, scale, cx, cy);
        }

        paint.setAlpha((int) ((float) savedAlpha * alpha));
        Drawables.drawCentered(c, searchIcon, cx, cy, paint);

        if (scale != 1f) {
          c.restore();
        }
      }
    }

    paint.setAlpha((int) ((float) savedAlpha * sendFactor));
    Drawables.drawCentered(c, doneInsteadOfSend ? doneIcon : sendIcon, cx, cy, paint);

    paint.setAlpha(savedAlpha);
  }

  private float trackDownX, trackDownY;

  private boolean inLongTap;

  private boolean draggingUp, draggingLeft;

  private boolean isDown;

  private void setIsDown (boolean isDown) {
    if (this.isDown != isDown) {
      this.isDown = isDown;
      if (isDown) {
        scheduleLongTap();
      } else {
        cancelLongTap();
      }
    }
  }

  private CancellableRunnable scheduledLongTap;

  private void scheduleLongTap () {
    cancelLongTap();
    scheduledLongTap = new CancellableRunnable() {
      @Override
      public void act () {
        if (scheduledLongTap == this) {
          setInLongTap(true, false);
          scheduledLongTap = null;
        }
      }
    };
    postDelayed(scheduledLongTap, 120l);
  }

  private void cancelLongTap () {
    if (scheduledLongTap != null) {
      scheduledLongTap.cancel();
      scheduledLongTap = null;
    }
  }

  private void setInLongTap (boolean inLongTap, boolean byCancel) {
    if (this.inLongTap != inLongTap) {
      boolean success;
      if (inLongTap) {
        success = UI.getContext(getContext()).getRecordAudioVideoController().startRecording(this, false);
      } else {
        if (!cancelLongTap) {
          UI.getContext(getContext()).getRecordAudioVideoController().finishRecording(byCancel);
        }
        success = true;
      }
      if (success) {
        this.inLongTap = inLongTap;
      } else if (inLongTap) {
        setIsDown(false);
      }
    }
  }

  private void performTap () {
    ViewUtils.onClick(this);
    Settings.instance().setPreferredRecordMode(Settings.instance().getNextRecordMode());
  }

  private void checkDraggingCircle () {
    boolean isDragging = draggingUp && isDown;
    UI.getContext(getContext()).getRecordAudioVideoController().setApplyVerticalDrag(isDragging);
  }

  private boolean cancelLongTap;

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (!hasTouchControls) {
      return false;// e.getAction() != MotionEvent.ACTION_DOWN || (getAlpha() > 0f && getVisibility() == View.VISIBLE);
    }

    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        trackDownX = e.getX();
        trackDownY = e.getY();
        draggingLeft = draggingUp = false;
        cancelLongTap = false;

        setIsDown(true);
        break;
      }
      case MotionEvent.ACTION_MOVE: {
        if (isDown) {
          float x = e.getX();
          float y = e.getY();
          if (inLongTap && !(cancelLongTap || (cancelLongTap = !UI.getContext(getContext()).getRecordAudioVideoController().isOpen()))) {
            float diffX = Math.max(0f, (trackDownX - x));
            float diffY = Math.max(0f, (trackDownY - y));
            boolean draggingUp = diffY >= Screen.getTouchSlop() && diffY >= diffX - Screen.getTouchSlop();
            boolean draggingLeft = diffX >= Screen.getTouchSlop() && !draggingUp;
            if (!draggingLeft && !draggingUp) {
              draggingLeft = this.draggingLeft;
              draggingUp = this.draggingUp;
            }
            if (draggingLeft || draggingUp) {
              if (!this.draggingLeft && !this.draggingUp) {
                trackDownX = x;
                trackDownY = y;
                diffX = diffY = 0;
              }
              this.draggingLeft = draggingLeft;
              this.draggingUp = draggingUp;
              checkDraggingCircle();
              if (!UI.getContext(getContext()).getRecordAudioVideoController().setTranslations(-diffX, -diffY)) {
                cancelLongTap = true;
              }
            }
          } else {
            trackDownX = x;
            trackDownY = y;
          }
        }
        break;
      }
      case MotionEvent.ACTION_UP: {
        if (isDown) {
          if (inLongTap) {
            setInLongTap(false, false);
          } else {
            performTap();
          }
          setIsDown(false);
        }
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (isDown) {
          setInLongTap(false, true);
          setIsDown(false);
        }
        break;
      }
    }

    return true;
  }
}
