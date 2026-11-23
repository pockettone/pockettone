package org.thoughtcrime.securesms.flip

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.util.WeakHashMap

/**
 * Hides the phone's white soft-key label bar at the bottom of the screen.
 * On keypad phones that bar is the system navigation bar. PocketTone draws
 * its own soft-key bar, so the system one only repeats empty labels, costs
 * ~24px of a 320px screen and (because Signal draws edge to edge) covers
 * PocketTone's own bar.
 *
 * Two layers, always both: the Android 11+ insets controller, and the old
 * sticky-immersive flags on the decor view and in the window attributes,
 * since vendor keypad ROMs often honor only those. Re-applied whenever the
 * system clears them and whenever the window regains focus (after a dialog
 * or menu closes).
 *
 * Adapted from templates/SystemBars.kt in Ben-Showalter/Flip-DumbPhoneGuide
 * (MIT), as used by TurboText.
 */
object SystemBars {

  @Suppress("DEPRECATION")
  private const val LEGACY_FLAGS = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

  private val watched = WeakHashMap<View, Boolean>()

  @JvmStatic
  fun hideNavigation(window: Window) {
    val decor = window.decorView
    val hide = {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
          it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          it.hide(WindowInsets.Type.navigationBars())
        }
      }
      @Suppress("DEPRECATION")
      run {
        decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
        val lp = window.attributes
        if (lp.systemUiVisibility and LEGACY_FLAGS != LEGACY_FLAGS) {
          lp.systemUiVisibility = lp.systemUiVisibility or LEGACY_FLAGS
          window.attributes = lp
        }
      }
    }
    hide()
    // Early in a launch the decor isn't attached yet, so apply again once it is.
    decor.post { hide() }

    if (watched.put(decor, true) == null) {
      @Suppress("DEPRECATION")
      decor.setOnSystemUiVisibilityChangeListener { visibility ->
        if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
          decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
        }
      }
      decor.viewTreeObserver.addOnWindowFocusChangeListener(
        ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) hideNavigation(window) }
      )
    }
  }
}
