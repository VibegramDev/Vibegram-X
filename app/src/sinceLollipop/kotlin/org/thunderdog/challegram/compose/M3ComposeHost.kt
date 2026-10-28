/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.vkryl.core.lambda.Destroyable
import org.thunderdog.challegram.navigation.ViewController

/**
 * Shows Compose Material 3 UI on top of a classic (View-based) screen.
 *
 * How it works: an invisible (0×0) [ComposeView] is attached to the activity's content frame and
 * composes `content`, which is expected to be a *window-based* M3 component: `AlertDialog`,
 * `BasicAlertDialog`/`Dialog`, `ModalBottomSheet`, `DatePickerDialog`, … Those components create
 * their own window (`ComponentDialog`), so Google's implementation provides back/predictive back
 * (→ `onDismissRequest`), scrim, focus, IME handling and edge-to-edge for us; nothing of the app's
 * view hierarchy (BaseActivity root, navigation, its own back callback) is touched.
 * Why not a hand-made `android.app.Dialog` + `setContent`: it would nest a second window for every
 * M3 dialog/sheet and re-implement scrim/back/insets that the M3 components already do correctly.
 *
 * Lifecycle: the ComposeView gets the activity as ViewTree Lifecycle/ViewModelStore/SavedState/
 * OnBackPressedDispatcher owner (the dialog windows inherit them). The host is dismissed when
 * [dismiss] is called, when the owning [ViewController] is destroyed, or when the activity is destroyed.
 *
 * IME: inside `Dialog`/`AlertDialog` text fields work with the default properties (floating window,
 * system pans/resizes it). For a full-width dialog with text fields use
 * `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)` and
 * `Modifier.imePadding()`: Compose then sets adjustResize/adjustNothing on the dialog window itself.
 * `ModalBottomSheet` handles IME/system bar insets on its own.
 *
 * Closing with animation: call [dismiss] after the component's exit animation, e.g. for a sheet
 * `scope.launch { sheetState.hide() }.invokeOnCompletion { host.dismiss() }`.
 *
 * Must be used on the UI thread (calls from other threads are posted to it).
 */
class M3ComposeHost private constructor(
  private val activity: ComponentActivity,
  private val controller: ViewController<*>?
) {
  private var view: ComposeView? = null
  private var dismissed = false
  private var visible by mutableStateOf(true)
  private var onDismissListener: Runnable? = null

  private val destroyListener = object : Destroyable {
    override fun performDestroy() = dismiss()
  }
  private val lifecycleObserver = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_DESTROY) dismiss()
  }

  /** True until [dismiss] is called. */
  val isShowing: Boolean
    get() = !dismissed

  /** Called once on the UI thread after the host is dismissed (by any reason). */
  fun setOnDismissListener(listener: Runnable?): M3ComposeHost {
    onDismissListener = listener
    return this
  }

  /** Removes the composition (and so every dialog/sheet window it shows). Safe to call repeatedly. */
  fun dismiss() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { dismiss() }
      return
    }
    if (dismissed) return
    dismissed = true
    // Stop composing the content first (closes its windows), then drop the anchor view on the
    // next loop: dismiss() is usually called from a click handler inside that very composition.
    visible = false
    controller?.removeDestroyListener(destroyListener)
    activity.lifecycle.removeObserver(lifecycleObserver)
    val anchor = view
    view = null
    mainHandler.post {
      if (anchor != null) {
        anchor.disposeComposition()
        (anchor.parent as? ViewGroup)?.removeView(anchor)
      }
    }
    onDismissListener?.run()
  }

  private fun attach(content: @Composable (M3ComposeHost) -> Unit) {
    if (dismissed) return
    if (activity.isFinishing || activity.lifecycle.currentState == Lifecycle.State.DESTROYED || controller?.isDestroyed == true) {
      dismiss()
      return
    }
    val container = activity.findViewById<ViewGroup>(android.R.id.content)
      ?: activity.window.decorView as ViewGroup
    val composeView = ComposeView(activity).apply {
      setViewTreeLifecycleOwner(activity)
      setViewTreeViewModelStoreOwner(activity)
      setViewTreeSavedStateRegistryOwner(activity)
      setViewTreeOnBackPressedDispatcherOwner(activity)
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
      isFocusable = false
      importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
      setContent {
        if (visible) {
          VgM3Theme {
            content(this@M3ComposeHost)
          }
        }
      }
    }
    view = composeView
    controller?.addDestroyListener(destroyListener)
    activity.lifecycle.addObserver(lifecycleObserver)
    container.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  companion object {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Shows [content] above [controller]'s screen; dismissed automatically when the controller is destroyed. */
    @JvmStatic
    fun show(controller: ViewController<*>, content: @Composable (host: M3ComposeHost) -> Unit): M3ComposeHost? {
      if (controller.isDestroyed) return null
      return show(controller.context(), controller, content)
    }

    /** Shows [content] in the activity found from [context] (any Activity/ContextWrapper of a ComponentActivity). */
    @JvmStatic
    fun show(context: Context, content: @Composable (host: M3ComposeHost) -> Unit): M3ComposeHost? =
      show(context, null, content)

    private fun show(context: Context, controller: ViewController<*>?, content: @Composable (M3ComposeHost) -> Unit): M3ComposeHost? {
      val activity = findActivity(context) ?: return null
      if (activity.isFinishing) return null
      val host = M3ComposeHost(activity, controller)
      if (Looper.myLooper() == Looper.getMainLooper()) {
        host.attach(content)
      } else {
        mainHandler.post { host.attach(content) }
      }
      return host
    }

    /** Unwraps [context] to the hosting [ComponentActivity] (BaseActivity is a FragmentActivity). */
    @JvmStatic
    fun findActivity(context: Context?): ComponentActivity? {
      var c = context
      while (c != null) {
        if (c is ComponentActivity) return c
        if (c is Activity) return null
        c = (c as? ContextWrapper)?.baseContext
      }
      return null
    }
  }
}
