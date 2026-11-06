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
 * File created on 19/08/2023
 */
package org.thunderdog.challegram.widget.emoji.section;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeId;
import org.thunderdog.challegram.theme.ThemeSet;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.emoji.EmojiLayoutRecyclerController;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

public class EmojiSection implements FactorAnimator.Target {
  public static final int SECTION_EMOJI_RECENT = 0;
  public static final int SECTION_EMOJI_SMILEYS = 1;
  public static final int SECTION_EMOJI_ANIMALS = 2;
  public static final int SECTION_EMOJI_FOOD = 3;
  public static final int SECTION_EMOJI_TRAVEL = 4;
  public static final int SECTION_EMOJI_SYMBOLS = 5;
  public static final int SECTION_EMOJI_FLAGS = 6;

  public static final int SECTION_SWITCH_TO_MEDIA = -11;
  public static final int SECTION_EMOJI_TRENDING = -12;

  public final int index;
  public float selectionFactor;

  private int iconRes;
  public Drawable icon;
  public @Nullable Drawable activeIcon;

  private boolean activeDisabled;
  private boolean isTrending;

  private @Nullable View view;
  private final EmojiLayoutRecyclerController.Callback callback;

  private int activeIconRes;

  public EmojiSection (EmojiLayoutRecyclerController.Callback callback, int sectionIndex, @DrawableRes int iconRes, @DrawableRes int activeIconRes) {
    this.callback = callback;
    this.index = sectionIndex;
    this.activeIconRes = activeIconRes;
    this.activeIcon = Drawables.get(callback.getContext().getResources(), activeIconRes);
    changeIcon(iconRes);
  }

  public void setIsTrending () {
    this.isTrending = true;
  }

  public boolean isTrending () {
    return isTrending;
  }

  @Nullable public View getView () {
    return view;
  }

  public EmojiSection setActiveDisabled () {
    activeDisabled = true;
    return this;
  }

  public void changeIcon (final int iconRes, final int activeIconRes) {
    changeIcon(iconRes);
    if (this.activeIconRes != activeIconRes) {
      this.activeIcon = Drawables.get(callback.getContext().getResources(), this.activeIconRes = activeIconRes);
      if (view != null) {
        view.invalidate();
      }
    }
  }

  public void changeIcon (final int iconRes) {
    if (this.iconRes != iconRes) {
      this.icon = Drawables.get(callback.getContext().getResources(), this.iconRes = iconRes);
      if (view != null) {
        view.invalidate();
      }
    }
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    setFactor(factor);
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {}

  private @Nullable FactorAnimator animator;

  public EmojiSection setFactor (float toFactor, boolean animated) {
    if (selectionFactor != toFactor && animated && view != null) {
      if (animator == null) {
        animator = new FactorAnimator(0, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180, selectionFactor);
      }
      animator.animateTo(toFactor);
    } else {
      if (animator != null) {
        animator.forceFactor(toFactor);
      }
      setFactor(toFactor);
    }
    return this;
  }

  private void setFactor (float factor) {
    if (this.selectionFactor != factor) {
      this.selectionFactor = factor;

      if (isPanda) {
        if (factor == 1f) {
          startPandaTimer();
        } else {
          cancelPandaTimer();
        }
      }

      if (view != null) {
        view.invalidate();
      }
    }
  }

  public void setCurrentView (View view) {
    this.view = view;
  }

  private boolean makeFirstTransparent;

  public EmojiSection setMakeFirstTransparent () {
    this.makeFirstTransparent = true;
    return this;
  }

  private int offsetHalf;

  public EmojiSection setOffsetHalf (boolean fromRight) {
    this.offsetHalf = fromRight ? 1 : -1;
    return this;
  }

  private boolean isPanda, doesPandaBlink, isPandaBlinking;
  private Runnable pandaBlink;

  public EmojiSection setIsPanda (boolean isPanda) {
    this.isPanda = isPanda;
    return this;
  }

  private void setPandaBlink (boolean inBlink) {
    if (this.doesPandaBlink != inBlink) {
      this.doesPandaBlink = inBlink;
      this.activeIcon = Drawables.get(callback.getContext().getResources(), inBlink ? R.drawable.deproko_baseline_animals_filled_blink_24 : activeIconRes);
      if (view != null) {
        view.invalidate();
      }
    }
  }

  private void startPandaTimer () {
    if (!isPandaBlinking) {
      this.isPandaBlinking = true;
      if (pandaBlink == null) {
        this.pandaBlink = () -> {
          if (isPandaBlinking || doesPandaBlink) {
            setPandaBlink(!doesPandaBlink);
            if (isPandaBlinking) {
              scheduleBlink(false);
            }
          }
        };
      }
      blinkNum = 0;
      scheduleBlink(true);
    }
  }

  private int blinkNum;

  private void scheduleBlink (boolean firstTime) {
    if (view != null) {
      long delay;
      switch (blinkNum++) {
        case 0: {
          setPandaBlink(false);
          delay = firstTime ? 6000 : 1000;
          break;
        }
        case 1:
        case 3:
        case 5: {
          delay = 140;
          break;
        }
        case 2:
        case 4: {
          delay = 4000;
          break;
        }
        case 6: {
          delay = 370;
          break;
        }
        case 7: {
          delay = 130;
          break;
        }
        case 8: {
          delay = 4000;
          blinkNum = 0;
          break;
        }
        default: {
          delay = 1000;
          blinkNum = 0;
          break;
        }
      }
      view.postDelayed(pandaBlink, delay);
    }

  }

  private void cancelPandaTimer () {
    if (isPandaBlinking) {
      isPandaBlinking = false;
      setPandaBlink(false);
      if (view != null) {
        view.removeCallbacks(pandaBlink);
      }
    }
  }

  // M3 selection of a section icon (m3.material.io/components/icon-buttons, "Color"): the selected section is a tonal
  // icon button ("Tonal container / Tonal icon | Secondary container / On secondary container"), the others are
  // standard icon buttons ("Standard icon | On surface variant"). Container: "Small - 40dp (default)", "Round button |
  // Full". Navigation items (m3.material.io/components/navigation-bar): "When selected, the icon fills, darkens, and is
  // backed by an active indicator shape"; "The active indicator animation should only apply on one axis".

  /** Diameter of the active indicator behind a section icon or a sticker set thumbnail. */
  public static final float INDICATOR_SIZE = 40f;

  public static @Nullable ThemeDelegate forcedTheme (boolean useDarkMode) {
    return useDarkMode ? ThemeSet.getBuiltinTheme(ThemeId.NIGHT_BLACK) : null;
  }

  /**
   * Active indicator of a selected section: secondary container circle, expands horizontally from the centre.
   * {@code maxSize}: the item size, the indicator never gets clipped by the item bounds.
   */
  public static void drawIndicator (Canvas c, float cx, float cy, float maxSize, float selectionFactor, @Nullable ThemeDelegate forcedTheme) {
    if (selectionFactor <= 0f) {
      return;
    }
    float height = Math.min(Screen.dp(INDICATOR_SIZE), maxSize);
    float width = MathUtils.fromTo(height / 2f, height, selectionFactor);
    RectF rect = Paints.getRectF();
    rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
    int color = ColorUtils.alphaColor(selectionFactor, M3.get(M3.SECONDARY_CONTAINER, forcedTheme));
    c.drawRoundRect(rect, height / 2f, height / 2f, Paints.fillingPaint(color));
  }

  public void draw (Canvas c, int cx, int cy) {
    final ThemeDelegate forcedTheme = forcedTheme(callback.isUseDarkMode());
    final int inactiveColor = M3.get(M3.ON_SURFACE_VARIANT, forcedTheme);
    final int activeColor = M3.get(M3.ON_SECONDARY_CONTAINER, forcedTheme);

    if (selectionFactor == 0f || activeDisabled) {
      Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2, cy - icon.getMinimumHeight() / 2, Paints.getPorterDuffPaint(inactiveColor));
    } else if (selectionFactor == 1f) {
      drawIndicator(c, cx, cy, Math.min(cx, cy) * 2, 1f, forcedTheme);
      final Drawable icon = this.activeIcon != null ? activeIcon : this.icon;
      Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2, cy - icon.getMinimumHeight() / 2, Paints.getPorterDuffPaint(activeColor));
    } else {
      drawIndicator(c, cx, cy, Math.min(cx, cy) * 2, selectionFactor, forcedTheme);

      float iconAlpha = 1f;
      if (makeFirstTransparent) {
        iconAlpha = 1f - selectionFactor;
      } else if (isPanda) {
        iconAlpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(1f - selectionFactor);
      }
      int iconColor = ColorUtils.fromToArgb(inactiveColor, activeColor, selectionFactor);
      Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2, cy - icon.getMinimumHeight() / 2, Paints.getPorterDuffPaint(ColorUtils.alphaColor(iconAlpha, iconColor)));

      final Drawable icon = this.activeIcon != null ? activeIcon : this.icon;
      Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2, cy - icon.getMinimumHeight() / 2, Paints.getPorterDuffPaint(ColorUtils.alphaColor(selectionFactor, activeColor)));
    }
  }
}
