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

import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.tool.UI;

/**
 * {@code web_app_trigger_haptic_feedback} through the haptics of the app, so the system haptic
 * feedback setting and the Custom Vibrations setting apply to mini apps too.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class WebAppHaptics {
  private WebAppHaptics () { }

  /**
   * @param view a view of the mini app attached to the window
   * @param type {@code impact}, {@code notification} or {@code selection_change}
   * @param impactStyle {@code light}, {@code medium}, {@code heavy}, {@code rigid} or {@code soft}
   * @param notificationType {@code error}, {@code success} or {@code warning}
   */
  public static void perform (
    View view,
    String type,
    @Nullable String impactStyle,
    @Nullable String notificationType
  ) {
    switch (type) {
      case "impact": {
        if (impactStyle == null) {
          return;
        }
        switch (impactStyle) {
          case "light":
          case "soft":
            UI.hapticVibrate(view, false);
            break;
          case "medium":
          case "heavy":
          case "rigid":
            UI.hapticVibrate(view, true);
            break;
        }
        break;
      }
      case "notification": {
        if (notificationType == null) {
          return;
        }
        switch (notificationType) {
          case "error":
            UI.forceVibrateError(view);
            break;
          case "success":
          case "warning":
            UI.hapticVibrate(view, true);
            break;
        }
        break;
      }
      case "selection_change":
        UI.hapticVibrate(view, false);
        break;
    }
  }
}
