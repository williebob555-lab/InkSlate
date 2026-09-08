package com.inkslate.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Distraction-free drawing: hides the status and navigation bars so the page fills the screen.
 *
 * Uses the "sticky" swipe gesture rather than a mode the bars can steal back, because a system
 * bar reappearing under your hand mid-stroke would swallow ink.
 *
 * The state is remembered per screen and always restored on the way out, so leaving the editor
 * can never strand the rest of the app behind hidden bars.
 */
class ImmersiveController internal constructor(
    private val controller: WindowInsetsControllerCompat?
) {
    var isFullscreen by mutableStateOf(false)
        private set

    fun set(fullscreen: Boolean) {
        val c = controller ?: return
        isFullscreen = fullscreen
        if (fullscreen) {
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun toggle() = set(!isFullscreen)
}

/**
 * Provides an [ImmersiveController] scoped to the current screen. Bars are restored automatically
 * when the composable leaves, which is what keeps a crash or a back press from trapping the user.
 */
@Composable
fun rememberImmersive(): ImmersiveController {
    val view = LocalView.current
    val context = LocalContext.current
    val controller = remember(view) {
        val window = (context as? Activity)?.window
        ImmersiveController(window?.let { WindowCompat.getInsetsController(it, view) })
    }
    DisposableEffect(controller) {
        onDispose { controller.set(false) }
    }
    return controller
}
