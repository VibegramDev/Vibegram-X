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
   *   <li>the chat background without a wallpaper is surface too. Dark presets whose incoming bubble is the surface
   *   color (Night Blue: TGX drew it on a darker chatBackground) get surfaceContainerHigh for it, as Monet does:
   *   otherwise the bubbles merge with the chat.</li>
   *   <li>the waveform of a voice message keeps its shape readable on its bubble ({@link M3#waveformColor}).</li>
   * </ul>
   * Custom themes keep the colors set in the theme editor over these roles ({@link ThemeCustom#getColor}).
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
      case ColorId.bubbleIn_background: {
        int color = getRawColor(colorId);
        if (isDark() && (color | 0xFF000000) == getRoleColor(M3.SURFACE)) {
          return getRoleColor(M3.SURFACE_CONTAINER_HIGH);
        }
        return color;
      }
      case ColorId.waveformActive:
      case ColorId.waveformInactive:
        return M3.waveformColor(getColor(ColorId.bubbleIn_background), getRawColor(ColorId.waveformActive), getRawColor(ColorId.waveformInactive), colorId == ColorId.waveformActive);
      case ColorId.bubbleOut_waveformActive:
      case ColorId.bubbleOut_waveformInactive:
        return M3.waveformColor(getColor(ColorId.bubbleOut_background), getRawColor(ColorId.bubbleOut_waveformActive), getRawColor(ColorId.bubbleOut_waveformInactive), colorId == ColorId.bubbleOut_waveformActive);
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
