/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.compose

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import org.thunderdog.challegram.navigation.ViewController

/**
 * Legacy flavor stub (no Compose): same public API as
 * app/src/sinceLollipop/kotlin/org/thunderdog/challegram/compose/M3ActionSheet.kt; [show] returns false so the
 * caller keeps the classic `showOptions` popup.
 */
object M3ActionSheet {
  class Item(@IdRes @JvmField val id: Int, @DrawableRes @JvmField val icon: Int, @JvmField val title: CharSequence)

  fun interface Listener {
    fun onItemSelected(@IdRes id: Int)
  }

  @Suppress("UNUSED_PARAMETER")
  @JvmStatic
  fun show(
    controller: ViewController<*>,
    anchorView: View?,
    title: CharSequence?,
    subtitle: CharSequence?,
    items: Array<Item>,
    listener: Listener
  ): Boolean = false
}
