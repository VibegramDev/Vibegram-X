/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.thunderdog.challegram.R
import org.thunderdog.challegram.core.Lang
import org.thunderdog.challegram.navigation.ViewController

/**
 * Java entry point to Jetpack Compose Material 3 UI.
 *
 * README — flavor split (same idea as widget/M3LoadingShapes):
 *  - Compose is compiled only into the sinceLollipop flavors (minSdk 21+). This file
 *    (app/src/sinceLollipop/kotlin/.../compose/M3Compose.kt) is the real implementation;
 *    app/src/onlyLegacy/kotlin/.../compose/M3Compose.kt is a stub with the SAME public API
 *    for the legacy flavor (minSdk 16, no Compose on the classpath).
 *  - Java code in `main` calls only this facade: `if (!M3Compose.showX(this, ...)) { old dialog }`.
 *    Never reference Compose classes, [M3ComposeHost] or [VgM3Theme] from `main`.
 *  - To add a feature: add a `@JvmStatic` method here AND the same signature to the legacy stub
 *    (returning `false`/`null`). Use only types visible in `main` (String, boolean, Runnable,
 *    ViewController, app callbacks …) in the signature. Return `false` when nothing was shown
 *    so the caller can fall back to the classic UI.
 *  - Inside, use `M3ComposeHost.show(controller) { host -> … }` with a window-based M3 component
 *    (AlertDialog / ModalBottomSheet / DatePickerDialog …) and call `host.dismiss()` from
 *    `onDismissRequest` / buttons. Colours come from the app theme via [VgM3Theme] automatically.
 *  - Put bigger screens in their own files in this package; keep this file a thin list of entry points.
 */
object M3Compose {
  /** true when Compose M3 UI is available in this build flavor. */
  @JvmStatic
  fun isSupported(): Boolean = true

  /** Demo: M3 AlertDialog with a single OK button. Returns false if it could not be shown. */
  @JvmStatic
  fun showDemoAlert(controller: ViewController<*>, title: String?, text: String?): Boolean {
    val host = M3ComposeHost.show(controller) { host ->
      AlertDialog(
        onDismissRequest = { host.dismiss() },
        confirmButton = {
          TextButton(onClick = { host.dismiss() }) {
            Text(Lang.getString(R.string.OK))
          }
        },
        title = title?.let { { Text(it) } },
        text = text?.let { { Text(it) } }
      )
    }
    return host != null
  }
}
