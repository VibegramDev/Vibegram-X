/*
 * This file is a part of Vibegram X, a fork of Telegram X
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
package org.thunderdog.challegram.webapp;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.unsorted.AppContext;

/** {@code web_app_trigger_haptic_feedback}: the waveforms of the official client. */
public final class WebAppHaptics {
  private WebAppHaptics () { }

  private static final int
    EFFECT_NONE = -1,
    EFFECT_IMPACT_LIGHT = 0,
    EFFECT_IMPACT_MEDIUM = 1,
    EFFECT_IMPACT_HEAVY = 2,
    EFFECT_IMPACT_RIGID = 3,
    EFFECT_IMPACT_SOFT = 4,
    EFFECT_NOTIFICATION_ERROR = 5,
    EFFECT_NOTIFICATION_SUCCESS = 6,
    EFFECT_NOTIFICATION_WARNING = 7,
    EFFECT_SELECTION_CHANGE = 8;

  private static final long[][] TIMINGS = {
    {7},
    {7},
    {7},
    {3},
    {10},
    {14, 48, 14, 48, 14, 48, 20},
    {14, 65, 14},
    {14, 64, 14},
    {1}
  };

  private static final int[][] AMPLITUDES = {
    {65},
    {145},
    {255},
    {225},
    {175},
    {200, 0, 200, 0, 255, 0, 145},
    {175, 0, 255},
    {225, 0, 175},
    {65}
  };

  // Patterns without amplitudes start with an off period: the leading 0 makes the first value of
  // the official fallback a vibration, as its waveform with amplitudes is (the official one only
  // waits on single values).
  private static final long[][] FALLBACK_PATTERNS = {
    {0, 60},
    {0, 70},
    {0, 80},
    {0, 50},
    {0, 55},
    {0, 40, 60, 40, 60, 65, 60, 40},
    {0, 50, 60, 65},
    {0, 65, 60, 40},
    {0, 30}
  };

  /**
   * @param type {@code impact}, {@code notification} or {@code selection_change}
   * @param impactStyle {@code light}, {@code medium}, {@code heavy}, {@code rigid} or {@code soft}
   * @param notificationType {@code error}, {@code success} or {@code warning}
   */
  public static void perform (
    String type,
    @Nullable String impactStyle,
    @Nullable String notificationType
  ) {
    int effect = findEffect(type, impactStyle, notificationType);
    if (effect == EFFECT_NONE) {
      return;
    }
    Vibrator vibrator = getVibrator();
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (vibrator.hasAmplitudeControl()) {
          vibrator.vibrate(VibrationEffect.createWaveform(TIMINGS[effect], AMPLITUDES[effect], -1));
        } else {
          vibrator.vibrate(VibrationEffect.createWaveform(FALLBACK_PATTERNS[effect], -1));
        }
      } else {
        vibrateLegacy(vibrator, FALLBACK_PATTERNS[effect]);
      }
    } catch (Throwable t) {
      Log.w("Cannot play haptic feedback", t);
    }
  }

  private static int findEffect (
    @Nullable String type,
    @Nullable String impactStyle,
    @Nullable String notificationType
  ) {
    if (type == null) {
      return EFFECT_NONE;
    }
    switch (type) {
      case "impact": {
        if (impactStyle == null) {
          return EFFECT_NONE;
        }
        switch (impactStyle) {
          case "light":
            return EFFECT_IMPACT_LIGHT;
          case "medium":
            return EFFECT_IMPACT_MEDIUM;
          case "heavy":
            return EFFECT_IMPACT_HEAVY;
          case "rigid":
            return EFFECT_IMPACT_RIGID;
          case "soft":
            return EFFECT_IMPACT_SOFT;
        }
        return EFFECT_NONE;
      }
      case "notification": {
        if (notificationType == null) {
          return EFFECT_NONE;
        }
        switch (notificationType) {
          case "error":
            return EFFECT_NOTIFICATION_ERROR;
          case "success":
            return EFFECT_NOTIFICATION_SUCCESS;
          case "warning":
            return EFFECT_NOTIFICATION_WARNING;
        }
        return EFFECT_NONE;
      }
      case "selection_change":
        return EFFECT_SELECTION_CHANGE;
    }
    return EFFECT_NONE;
  }

  private static @Nullable Vibrator getVibrator () {
    Context context = AppContext.get();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      VibratorManager manager = context.getSystemService(VibratorManager.class);
      return manager != null ? manager.getDefaultVibrator() : null;
    } else {
      return getVibratorLegacy(context);
    }
  }

  @SuppressWarnings("deprecation")
  private static @Nullable Vibrator getVibratorLegacy (Context context) {
    return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
  }

  @SuppressWarnings("deprecation")
  private static void vibrateLegacy (Vibrator vibrator, long[] pattern) {
    vibrator.vibrate(pattern, -1);
  }
}
