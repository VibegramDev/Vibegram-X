/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.compose

import org.thunderdog.challegram.navigation.ViewController

/**
 * Legacy flavor (minSdk 16) stub of the Compose M3 facade: Compose needs API 21 and is not on the
 * legacy classpath. Keep every public method in sync with
 * app/src/sinceLollipop/kotlin/org/thunderdog/challegram/compose/M3Compose.kt (see README there);
 * stubs return false/null so callers fall back to the classic UI.
 */
object M3Compose {
  @JvmStatic
  fun isSupported(): Boolean = false

  @Suppress("UNUSED_PARAMETER")
  @JvmStatic
  fun showDemoAlert(controller: ViewController<*>, title: String?, text: String?): Boolean = false
}
