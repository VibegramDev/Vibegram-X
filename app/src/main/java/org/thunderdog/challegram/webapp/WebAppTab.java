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

import android.graphics.Bitmap;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.telegram.Tdlib;

/** A minimized mini app, kept alive while it is shown as a tab in {@link WebAppTabs}. */
public interface WebAppTab {
  Tdlib tdlib ();

  /** Bot name. */
  CharSequence getTabTitle ();

  /** Header or bottom bar color of the app, for the card of the tab. */
  @ColorInt int getTabColor ();

  /** Snapshot of the WebView taken when minimized. */
  @Nullable Bitmap getTabPreview ();

  /** Reopens the sheet; the controller removes itself from {@link WebAppTabs}. */
  void restoreFromTab ();

  /**
   * Closes the app. When {@code force} is false and the app asked for a closing confirmation,
   * the controller shows it and closes only when confirmed. It removes itself from
   * {@link WebAppTabs} when it closes.
   */
  void closeFromTab (boolean force);
}
