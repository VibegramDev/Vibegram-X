/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.vkryl.core.fromToArgb
import org.thunderdog.challegram.theme.ColorState
import org.thunderdog.challegram.theme.M3
import org.thunderdog.challegram.theme.Theme
import org.thunderdog.challegram.theme.ThemeChangeListener
import org.thunderdog.challegram.theme.ThemeManager
import org.thunderdog.challegram.tool.Fonts
import org.thunderdog.challegram.tool.UI
import org.thunderdog.challegram.unsorted.Settings

/**
 * Material 3 theme for Compose UI, built from the app's runtime colour roles ([M3]).
 *
 * One M3 interface for every app theme: only the colours change. The scheme is re-read from [M3]
 * every time the composition reads it, and [VgM3ThemeObserver] bumps a snapshot state on every app
 * theme change (including temporary/animated ones), so open Compose UI follows theme switches live.
 */
@Composable
fun VgM3Theme(content: @Composable () -> Unit) {
  val version = VgM3ThemeObserver.version
  val dark = Theme.isDark()
  val colorScheme = remember(version, dark) { vgColorScheme(dark) }
  val typography = remember(VgM3Fonts.useSystemFonts()) { vgTypography() }
  MaterialTheme(
    colorScheme = colorScheme,
    typography = typography,
    content = content
  )
}

/** Snapshot-state bridge: app theme change → recomposition of every [VgM3Theme]. */
internal object VgM3ThemeObserver : ThemeChangeListener {
  private var counter by mutableIntStateOf(0)
  private var registered = false

  /** Reading this inside composition subscribes to theme changes. Must be read on the UI thread. */
  val version: Int
    get() {
      if (!registered) {
        registered = true
        // ThemeManager keeps listeners in a weak ReferenceList: this object is the strong reference.
        ThemeManager.instance().addThemeListener(this)
      }
      return counter
    }

  override fun needsTempUpdates(): Boolean = true

  override fun onThemeColorsChanged(areTemp: Boolean, state: ColorState?) {
    // M3 caches by (theme id, dark, filling, accent, text): reading it after this bump recomputes as needed.
    counter++
  }
}

/**
 * Full [ColorScheme] with every role filled. Roles that [M3] provides are taken as is; the rest are
 * derived from them (same formulas [M3] uses for its own derived roles):
 *  - onSecondary: black/white by luminance of secondary (like [M3.onPrimary]);
 *  - tertiary*: the app has no tertiary accent → mirrors the secondary roles;
 *  - errorContainer/onErrorContainer: error mixed like primaryContainer/onPrimaryContainer;
 *  - background/onBackground = surface/onSurface, surfaceVariant = surfaceContainerHighest,
 *    surfaceTint = primary, scrim = black;
 *  - surfaceDim/surfaceBright: light — dim = surface→onSurface 12%, bright = surface;
 *    dark — dim = surface, bright = surface→onSurface 17% (≈ M3 tones 87/98 and 6/24);
 *  - *Fixed roles (same in light and dark): fixed ≈ T90, fixedDim ≈ T80, onFixed ≈ T10,
 *    onFixedVariant ≈ T30 of the role colour.
 */
fun vgColorScheme(dark: Boolean = Theme.isDark()): ColorScheme {
  val surface = M3.surface()
  val onSurface = M3.onSurface()
  val primary = M3.primary()
  val secondary = M3.secondary()
  val error = M3.error()
  val onSecondary = contrastOn(secondary)

  val surfaceDim = if (dark) surface else mix(surface, onSurface, .12f)
  val surfaceBright = if (dark) mix(surface, onSurface, .17f) else surface
  val errorContainer = if (dark) mix(error, BLACK, .55f) else mix(error, WHITE, .75f)
  val onErrorContainer = if (dark) mix(error, WHITE, .75f) else mix(error, BLACK, .65f)

  val c = { argb: Int -> Color(argb) }
  val args = SchemeArgs(
    primary = c(primary),
    onPrimary = c(M3.onPrimary()),
    primaryContainer = c(M3.primaryContainer()),
    onPrimaryContainer = c(M3.onPrimaryContainer()),
    inversePrimary = c(M3.inversePrimary()),
    secondary = c(secondary),
    onSecondary = c(onSecondary),
    secondaryContainer = c(M3.secondaryContainer()),
    onSecondaryContainer = c(M3.onSecondaryContainer()),
    background = c(surface),
    onBackground = c(onSurface),
    surface = c(surface),
    onSurface = c(onSurface),
    surfaceVariant = c(M3.surfaceContainerHighest()),
    onSurfaceVariant = c(M3.onSurfaceVariant()),
    inverseSurface = c(M3.inverseSurface()),
    inverseOnSurface = c(M3.inverseOnSurface()),
    error = c(error),
    onError = c(M3.onError()),
    errorContainer = c(errorContainer),
    onErrorContainer = c(onErrorContainer),
    outline = c(M3.outline()),
    outlineVariant = c(M3.outlineVariant()),
    surfaceBright = c(surfaceBright),
    surfaceContainer = c(M3.surfaceContainer()),
    surfaceContainerHigh = c(M3.surfaceContainerHigh()),
    surfaceContainerHighest = c(M3.surfaceContainerHighest()),
    surfaceContainerLow = c(M3.surfaceContainerLow()),
    surfaceContainerLowest = c(M3.surfaceContainerLowest()),
    surfaceDim = c(surfaceDim),
    primaryFixed = c(fixed(primary)),
    primaryFixedDim = c(fixedDim(primary)),
    onPrimaryFixed = c(onFixed(primary)),
    onPrimaryFixedVariant = c(onFixedVariant(primary)),
    secondaryFixed = c(fixed(secondary)),
    secondaryFixedDim = c(fixedDim(secondary)),
    onSecondaryFixed = c(onFixed(secondary)),
    onSecondaryFixedVariant = c(onFixedVariant(secondary))
  )
  return if (dark) args.dark() else args.light()
}

private class SchemeArgs(
  val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
  val inversePrimary: Color,
  val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
  val background: Color, val onBackground: Color,
  val surface: Color, val onSurface: Color, val surfaceVariant: Color, val onSurfaceVariant: Color,
  val inverseSurface: Color, val inverseOnSurface: Color,
  val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color,
  val outline: Color, val outlineVariant: Color,
  val surfaceBright: Color, val surfaceContainer: Color, val surfaceContainerHigh: Color,
  val surfaceContainerHighest: Color, val surfaceContainerLow: Color, val surfaceContainerLowest: Color,
  val surfaceDim: Color,
  val primaryFixed: Color, val primaryFixedDim: Color, val onPrimaryFixed: Color, val onPrimaryFixedVariant: Color,
  val secondaryFixed: Color, val secondaryFixedDim: Color, val onSecondaryFixed: Color, val onSecondaryFixedVariant: Color
) {
  fun light(): ColorScheme = lightColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = secondary, onTertiary = onSecondary, tertiaryContainer = secondaryContainer, onTertiaryContainer = onSecondaryContainer,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
    error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    outline = outline, outlineVariant = outlineVariant, scrim = Color.Black,
    surfaceBright = surfaceBright, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest, surfaceContainerLow = surfaceContainerLow,
    surfaceContainerLowest = surfaceContainerLowest, surfaceDim = surfaceDim,
    primaryFixed = primaryFixed, primaryFixedDim = primaryFixedDim, onPrimaryFixed = onPrimaryFixed, onPrimaryFixedVariant = onPrimaryFixedVariant,
    secondaryFixed = secondaryFixed, secondaryFixedDim = secondaryFixedDim, onSecondaryFixed = onSecondaryFixed, onSecondaryFixedVariant = onSecondaryFixedVariant,
    tertiaryFixed = secondaryFixed, tertiaryFixedDim = secondaryFixedDim, onTertiaryFixed = onSecondaryFixed, onTertiaryFixedVariant = onSecondaryFixedVariant
  )

  fun dark(): ColorScheme = darkColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = secondary, onTertiary = onSecondary, tertiaryContainer = secondaryContainer, onTertiaryContainer = onSecondaryContainer,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
    error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    outline = outline, outlineVariant = outlineVariant, scrim = Color.Black,
    surfaceBright = surfaceBright, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest, surfaceContainerLow = surfaceContainerLow,
    surfaceContainerLowest = surfaceContainerLowest, surfaceDim = surfaceDim,
    primaryFixed = primaryFixed, primaryFixedDim = primaryFixedDim, onPrimaryFixed = onPrimaryFixed, onPrimaryFixedVariant = onPrimaryFixedVariant,
    secondaryFixed = secondaryFixed, secondaryFixedDim = secondaryFixedDim, onSecondaryFixed = onSecondaryFixed, onSecondaryFixedVariant = onSecondaryFixedVariant,
    tertiaryFixed = secondaryFixed, tertiaryFixedDim = secondaryFixedDim, onTertiaryFixed = onSecondaryFixed, onTertiaryFixedVariant = onSecondaryFixedVariant
  )
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private fun mix(from: Int, to: Int, factor: Float): Int = fromToArgb(from, to, factor) or BLACK

private fun fixed(color: Int) = mix(color, WHITE, .75f)
private fun fixedDim(color: Int) = mix(color, WHITE, .5f)
private fun onFixed(color: Int) = mix(color, BLACK, .8f)
private fun onFixedVariant(color: Int) = mix(color, BLACK, .55f)

/** Same rule as M3.contrast(): relative luminance > .6 → black content, otherwise white. */
private fun contrastOn(color: Int): Int {
  val l = (0.2126 * android.graphics.Color.red(color) +
    0.7152 * android.graphics.Color.green(color) +
    0.0722 * android.graphics.Color.blue(color)) / 255.0
  return if (l > .6) BLACK else WHITE
}

/**
 * M3 baseline type scale. Font family follows the app: bundled Roboto from assets
 * (the same files [Fonts] loads), or the system sans-serif when "Use system fonts" is enabled.
 */
fun vgTypography(): Typography {
  val base = Typography()
  val family = VgM3Fonts.family()
  if (family == FontFamily.Default) return base
  return Typography(
    displayLarge = base.displayLarge.copy(fontFamily = family),
    displayMedium = base.displayMedium.copy(fontFamily = family),
    displaySmall = base.displaySmall.copy(fontFamily = family),
    headlineLarge = base.headlineLarge.copy(fontFamily = family),
    headlineMedium = base.headlineMedium.copy(fontFamily = family),
    headlineSmall = base.headlineSmall.copy(fontFamily = family),
    titleLarge = base.titleLarge.copy(fontFamily = family),
    titleMedium = base.titleMedium.copy(fontFamily = family),
    titleSmall = base.titleSmall.copy(fontFamily = family),
    bodyLarge = base.bodyLarge.copy(fontFamily = family),
    bodyMedium = base.bodyMedium.copy(fontFamily = family),
    bodySmall = base.bodySmall.copy(fontFamily = family),
    labelLarge = base.labelLarge.copy(fontFamily = family),
    labelMedium = base.labelMedium.copy(fontFamily = family),
    labelSmall = base.labelSmall.copy(fontFamily = family)
  )
}

internal object VgM3Fonts {
  private var roboto: FontFamily? = null

  fun useSystemFonts(): Boolean = Fonts.areUsingSystemFonts() ?: Settings.instance().useSystemFonts()

  fun family(): FontFamily {
    if (useSystemFonts()) return FontFamily.Default
    return roboto ?: run {
      val assets = UI.getContext().assets
      FontFamily(
        Font("fonts/Roboto-Regular.ttf", assets, FontWeight.Normal),
        Font("fonts/Roboto-Medium.ttf", assets, FontWeight.Medium),
        Font("fonts/Roboto-Bold.ttf", assets, FontWeight.Bold),
        Font("fonts/Roboto-Italic.ttf", assets, FontWeight.Normal, FontStyle.Italic)
      ).also { roboto = it }
    }
  }
}
