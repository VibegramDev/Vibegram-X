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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thunderdog.challegram.navigation.ViewController
import kotlin.math.roundToInt

/**
 * Material 3 list of actions for a tapped row (replaces `ViewController.showOptions` popups, opt-in per call site).
 *
 * Presentation follows the window width (m3.material.io bottom-sheets › Overview, menus › Adaptive design):
 *  - compact (< 600dp, phone portrait, folded Fold): [ModalBottomSheet] with drag handle, optional
 *    non-interactive header (value + supporting text) and one-line 56dp [ListItem]s with leading icons;
 *  - medium/expanded (≥ 600dp, unfolded Fold, phone landscape): [DropdownMenu] anchored to the tapped row
 *    (falls back to the sheet when the row is not attached).
 * The variant is chosen once when shown; the menu closes itself if the window width changes (fold/unfold),
 * because its anchor position would be stale.
 *
 * Selection: sheet — the hide animation runs first, then [Listener.onItemSelected]; menu — the item fires
 * immediately while the menu fades out (platform menu behaviour). Always on the UI thread. Dismissing
 * without a choice calls nothing.
 */
object M3ActionSheet {
  /** One action. [id] is passed back to [Listener]; [icon] is a drawable resource (vector or bitmap). */
  class Item(@IdRes @JvmField val id: Int, @DrawableRes @JvmField val icon: Int, @JvmField val title: CharSequence)

  fun interface Listener {
    fun onItemSelected(@IdRes id: Int)
  }

  /**
   * @param anchorView the tapped row; used to place the menu on wide windows (nullable → sheet).
   * @param title optional header value (phone number, @username, ID), titleMedium.
   * @param subtitle optional header supporting text (e.g. privacy hint), bodyMedium onSurfaceVariant.
   * @return false when nothing could be shown (caller falls back to the classic popup).
   */
  @JvmStatic
  fun show(
    controller: ViewController<*>,
    anchorView: View?,
    title: CharSequence?,
    subtitle: CharSequence?,
    items: Array<Item>,
    listener: Listener
  ): Boolean {
    if (items.isEmpty()) return false
    val widthDp = controller.context().resources.configuration.screenWidthDp
    val useMenu = widthDp >= MEDIUM_WIDTH_DP && anchorView != null && anchorView.isAttachedToWindow &&
      anchorView.width > 0 && anchorView.height > 0
    val titleText = title?.toString()?.takeIf { it.isNotEmpty() }
    val subtitleText = subtitle?.toString()?.takeIf { it.isNotEmpty() }
    val host = M3ComposeHost.show(controller) { host ->
      if (useMenu) {
        ActionSheetMenu(host, anchorView!!, widthDp, titleText, subtitleText, items, listener)
      } else {
        ActionSheetBottom(host, titleText, subtitleText, items, listener)
      }
    }
    return host != null
  }

  /** M3 window size classes: compact < 600dp ≤ medium < 840dp ≤ expanded. */
  private const val MEDIUM_WIDTH_DP = 600
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheetBottom(
  host: M3ComposeHost,
  title: String?,
  subtitle: String?,
  items: Array<M3ActionSheet.Item>,
  listener: M3ActionSheet.Listener
) {
  // 2–5 short items never reach 50% of the screen, so the half-expanded state is not needed.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var chosen by remember { mutableStateOf(false) }
  val choose: (Int) -> Unit = { id ->
    if (!chosen) {
      chosen = true
      scope.launch { sheetState.hide() }.invokeOnCompletion {
        host.dismiss()
        listener.onItemSelected(id)
      }
    }
  }
  ModalBottomSheet(
    onDismissRequest = { host.dismiss() },
    sheetState = sheetState
  ) {
    ActionSheetHeader(title, subtitle, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    for (item in items) {
      ListItem(
        headlineContent = { Text(item.title.toString()) },
        leadingContent = { Icon(painterResource(item.icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { choose(item.id) }
      )
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun ActionSheetMenu(
  host: M3ComposeHost,
  anchorView: View,
  shownWidthDp: Int,
  title: String?,
  subtitle: String?,
  items: Array<M3ActionSheet.Item>,
  listener: M3ActionSheet.Listener
) {
  var expanded by remember { mutableStateOf(true) }
  var chosen by remember { mutableStateOf(false) }
  // Window resized (fold/unfold, multi-window): the anchor moved, close instead of pointing at a stale place.
  val widthDp = LocalConfiguration.current.screenWidthDp
  LaunchedEffect(widthDp) {
    if (widthDp != shownWidthDp) expanded = false
  }
  LaunchedEffect(expanded) {
    if (!expanded) {
      // DropdownMenu removes its popup itself once the (spring) exit animation settles; the empty host
      // view is dropped afterwards. The action itself runs right away on click, like a platform menu.
      delay(ActionSheetMenuExitMs)
      host.dismiss()
    }
  }
  // The host ComposeView sits at the top-left of the activity content; its window position is read
  // once it is placed, then a 0×0-reporting box is placed exactly over the tapped row (window coordinates
  // of both are compared, so status bar / pane offsets / RTL do not matter) and the menu anchors to it.
  var hostInWindow by remember { mutableStateOf<Offset?>(null) }
  Box(Modifier.onGloballyPositioned { hostInWindow = it.positionInWindow() }) {
    val origin = hostInWindow
    if (origin != null) {
      val location = remember(origin) { IntArray(2).also { anchorView.getLocationInWindow(it) } }
      val x = location[0] - origin.x.roundToInt()
      val y = location[1] - origin.y.roundToInt()
      val w = anchorView.width
      val h = anchorView.height
      Box(
        Modifier.layout { measurable, _ ->
          val placeable = measurable.measure(Constraints.fixed(w, h))
          layout(0, 0) { placeable.place(x, y) }
        }
      ) {
        DropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
          // [моё] start the menu at the row's leading icon inset instead of the very edge of the pane.
          offset = DpOffset(16.dp, 0.dp)
        ) {
          // The menu opens next to the tapped row, which already shows the value (phone, @username, ID):
          // repeating it as a title only makes the menu taller, so that DropdownMenu can no longer fit it
          // below/above the row and centres it over the row instead. Only the explanatory subtitle
          // (e.g. the birthday privacy hint) is kept; the bottom sheet keeps the full header.
          ActionSheetHeader(
            null,
            subtitle,
            Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp)
          )
          for (item in items) {
            DropdownMenuItem(
              text = { Text(item.title.toString()) },
              leadingIcon = { Icon(painterResource(item.icon), contentDescription = null) },
              onClick = {
                if (!chosen) {
                  chosen = true
                  expanded = false
                  listener.onItemSelected(item.id)
                }
              }
            )
          }
        }
      }
    }
  }
}

/** Non-interactive header: the value the actions apply to and/or a supporting line. */
@Composable
private fun ActionSheetHeader(title: String?, subtitle: String?, modifier: Modifier) {
  if (title == null && subtitle == null) return
  Column(modifier.semantics(mergeDescendants = true) { heading() }) {
    if (title != null) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    if (subtitle != null) {
      Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (title != null) Modifier.padding(top = 4.dp) else Modifier
      )
    }
  }
}

/** Upper bound of the DropdownMenu exit (MotionScheme FastSpatial/FastEffects springs settle well before it). */
private const val ActionSheetMenuExitMs = 400L
