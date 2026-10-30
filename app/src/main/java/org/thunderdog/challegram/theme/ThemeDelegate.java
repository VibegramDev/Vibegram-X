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
 * File created on 06/11/2018
 */
package org.thunderdog.challegram.theme;

import androidx.annotation.ColorInt;

public interface ThemeDelegate {
  @ThemeId int getId ();

  /**
   * Surfaces and app bars are the Material 3 color roles of this theme ({@link #getRoleColor}), every other color is
   * the theme's own ({@link #getRawColor}). m3.material.io/styles/color/roles: "surface for background, surface
   * container for navigation areas"; app bars: container surface, surface container on scroll.
   *
   * <ul>
   *   <li>page backgrounds ({@code filling}: chat list and rows, {@code background}: settings pages, empty pane) and
   *   the app bar ({@code headerBackground}) are surface; segmented settings cards are surfaceContainer
   *   ({@code SettingsAdapter.setSegmented}), navigation (rail, dock, folder tabs) surfaceContainer;</li>
   *   <li>app bar title and icons onSurface, tabs primary / onSurfaceVariant;</li>
   *   <li>the status bar gets no tint and dark icons in light themes;</li>
   *   <li>floating chat buttons (scroll down, mentions, reactions, collapsed mute bell, camera): secondaryContainer
   *   with an onSecondaryContainer icon (FAB: "Ensure the icon has a minimum 3:1 contrast ratio with the container").</li>
   * </ul>
   */
  @ColorInt
  default int getColor (@ColorId int colorId) {
    switch (colorId) {
      case ColorId.filling:
      case ColorId.background:
      case ColorId.headerBackground:
      case ColorId.chatBackground:
        return getRoleColor(M3.SURFACE);
      case ColorId.headerText:
      case ColorId.headerIcon:
        return getRoleColor(M3.ON_SURFACE);
      case ColorId.headerTabActive:
      case ColorId.headerTabActiveText:
        return getRoleColor(M3.PRIMARY);
      case ColorId.headerTabInactiveText:
        return getRoleColor(M3.ON_SURFACE_VARIANT);
      case ColorId.statusBar:
        return 0;
      case ColorId.circleButtonChat:
        return getRoleColor(M3.SECONDARY_CONTAINER);
      case ColorId.circleButtonChatIcon:
        return getRoleColor(M3.ON_SECONDARY_CONTAINER);
      case ColorId.textLink:
        // Links in descriptions and info rows on surface: primary (the raw Monet link color gave 4.3:1, «4.5:1 for small text»).
        return getRoleColor(M3.PRIMARY);
    }
    return getRawColor(colorId);
  }

  /** Color defined by the theme itself (built-in preset, Monet template, user edits), before the Material 3 roles. */
  @ColorInt int getRawColor (@ColorId int colorId);

  /** Material 3 color role ({@link M3#SURFACE}, ...) of this theme, computed once from its raw colors ({@link M3.Roles}). */
  @ColorInt int getRoleColor (int role);

  String getDefaultWallpaper ();
  float getProperty (@PropertyId int propertyId);
  default boolean isDark () {
    return getProperty(PropertyId.DARK) == 1f;
  }
  default boolean needLightStatusBar () {
    return getProperty(PropertyId.LIGHT_STATUS_BAR) == 1f;
  }
}
