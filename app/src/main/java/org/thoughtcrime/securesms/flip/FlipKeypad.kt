package org.thoughtcrime.securesms.flip

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.Toolbar
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.flip.t9.T9EngineHolder
import org.thoughtcrime.securesms.flip.t9.T9Session
import java.util.WeakHashMap

/**
 * PocketTone's keypad layer for keypad flip phones (Kyocera E4810).
 *
 * Installed once from ApplicationContext; it hooks every Activity's window:
 *  - Marks the window as not using an input method, so the phone's own
 *    keyboard never attaches and can't swallow keys.
 *  - Routes keys to [T9Session] whenever a text field has focus.
 *  - Soft keys: left = T9/abc/123 menu in a text field, otherwise Back;
 *    right = the screen's options menu. Call = send. Clear = backspace,
 *    or Back once the field is empty.
 */
object FlipKeypad {

  private val TAG = Log.tag(FlipKeypad::class.java)

  private val controllers = WeakHashMap<Activity, Controller>()

  @JvmStatic
  fun install(app: Application) {
    // Parse the 2 MB word list off the main thread so the first keypress
    // doesn't stall.
    Thread({ runCatching { T9EngineHolder.get(app) } }, "flip-t9-preload").start()

    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = attach(activity)
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) {
        controllers[activity]?.endSession()
      }
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) {
        controllers.remove(activity)?.endSession()
      }
    })
  }

  private fun attach(activity: Activity) {
    if (controllers.containsKey(activity)) return
    val window = activity.window ?: return
    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    val controller = Controller(activity)
    controllers[activity] = controller
    val base = window.callback
    window.callback = KeyInterceptingCallback(base, controller)
  }

  private class KeyInterceptingCallback(
    private val base: Window.Callback,
    private val controller: Controller
  ) : Window.Callback by base {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
      controller.dispatchKeyEvent(event) { base.dispatchKeyEvent(it) }
  }

  // -----------------------------------------------------------------------

  private class Controller(private val activity: Activity) : T9Session.Listener {

    private var session: T9Session? = null
    private var sessionView: View? = null
    private var chosenMode: T9Session.Mode? = null

    /** Keys whose DOWN we used — their UP must be eaten too, or e.g. a
     *  Clear that backspaced would also fire Back on release. */
    private val consumed = HashSet<Int>()

    private var barView: TextView? = null
    private val bar: TextView get() = barView ?: createBar().also { barView = it }

    fun dispatchKeyEvent(event: KeyEvent, superDispatch: (KeyEvent) -> Boolean): Boolean {
      val code = event.keyCode
      when (event.action) {
        KeyEvent.ACTION_DOWN -> {
          val used = try {
            onKeyDown(event)
          } catch (e: Exception) {
            Log.w(TAG, "keypad handling failed for $code", e)
            false
          }
          if (used) {
            consumed.add(code)
            return true
          }
          consumed.remove(code)
          return superDispatch(event)
        }
        KeyEvent.ACTION_UP -> {
          if (consumed.remove(code)) {
            session?.onKeyUp(code)
            return true
          }
          return superDispatch(event)
        }
        else -> return superDispatch(event)
      }
    }

    private fun onKeyDown(event: KeyEvent): Boolean {
      val code = event.keyCode
      val s = currentSession()

      when (code) {
        KeyEvent.KEYCODE_SOFT_LEFT -> {
          if (event.repeatCount == 0) {
            if (s != null) showModeMenu(s) else goBack()
          }
          return true
        }
        KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU -> {
          if (event.repeatCount == 0) {
            s?.confirmPending()
            openOptionsMenu()
          }
          return true
        }
        KeyEvent.KEYCODE_CALL -> {
          s?.confirmPending()
          return event.repeatCount > 0 || clickSend()
        }
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> {
          if (s == null) return false
          // Clear deletes; once there's nothing left before the cursor it
          // falls through to Back. A held Clear keeps deleting but never
          // backs out of the screen by itself.
          if (s.backspace()) return true
          return event.repeatCount > 0
        }
      }

      if (s == null) return false
      if (s.onKeyDown(code, event)) return true

      // Anything the session didn't use (arrows, OK) goes to the app — but
      // finish the word first so it isn't left half-composed.
      if (s.isComposing()) s.confirmPending()
      return false
    }

    /** The T9 session for the focused text field, (re)created as focus
     *  moves; null when no text field has focus. */
    private fun currentSession(): T9Session? {
      val focus = activity.currentFocus
      if (focus == null || !focus.isShown || !focus.isEnabled || !focus.onCheckIsTextEditor()) {
        endSession()
        return null
      }
      val existing = session
      if (focus === sessionView && existing != null) {
        // Compose reuses one host View for every text field on screen, so
        // its connection goes stale when focus moves between fields.
        // Rebind whenever nothing is mid-composition.
        if (focus is EditText || existing.isComposing()) return existing
        val info = EditorInfo()
        val ic = focus.onCreateInputConnection(info)
        if (ic == null) {
          endSession()
          return null
        }
        if (info.inputType == existing.inputType) {
          existing.rebind(ic)
          return existing
        }
        startSession(focus, info, ic, keepMode = true)
        return session
      }
      endSession()
      chosenMode = null
      val info = EditorInfo()
      val ic = focus.onCreateInputConnection(info) ?: return null
      startSession(focus, info, ic, keepMode = false)
      return session
    }

    private fun startSession(view: View, info: EditorInfo, ic: android.view.inputmethod.InputConnection, keepMode: Boolean) {
      if (view is EditText) view.showSoftInputOnFocus = false
      val engine = T9EngineHolder.get(activity)
      val s = T9Session(engine, ic, info, this)
      if (keepMode && !s.isNumericField) chosenMode?.let { if (it != s.mode) s.selectMode(it) }
      session = s
      sessionView = view
      onStateChanged(s)
    }

    fun endSession() {
      session?.let { runCatching { it.release() } }
      session = null
      sessionView = null
      consumed.clear()
      hideBar()
    }

    // --- soft keys ---------------------------------------------------------

    private fun showModeMenu(s: T9Session) {
      s.confirmPending()
      val modes = arrayOf(T9Session.Mode.WORD, T9Session.Mode.ABC, T9Session.Mode.NUMBER)
      val labels = arrayOf("T9 word", "abc (multi-tap)", "123")
      AlertDialog.Builder(activity)
        .setTitle("Input mode")
        .setSingleChoiceItems(labels, modes.indexOf(s.mode)) { dialog, which ->
          chosenMode = modes[which]
          s.selectMode(modes[which])
          dialog.dismiss()
        }
        .show()
    }

    private fun openOptionsMenu() {
      val toolbar = findVisible(activity.window.decorView) { it is Toolbar && it.menu.hasVisibleItems() } as Toolbar?
      if (toolbar == null || !toolbar.showOverflowMenu()) activity.openOptionsMenu()
    }

    private fun goBack() {
      val component = activity as? ComponentActivity
      if (component != null) component.onBackPressedDispatcher.onBackPressed() else activity.finish()
    }

    private fun clickSend(): Boolean {
      val send = activity.findViewById<View>(R.id.send_button) ?: return false
      if (!send.isShown || !send.isEnabled) return false
      return send.performClick()
    }

    private fun findVisible(view: View, predicate: (View) -> Boolean): View? {
      if (!view.isShown) return null
      if (predicate(view)) return view
      if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
          findVisible(view.getChildAt(i), predicate)?.let { return it }
        }
      }
      return null
    }

    // --- suggestion bar ----------------------------------------------------

    override fun onStateChanged(session: T9Session) {
      if (session !== this.session && this.session != null) return
      val (candidates, index) = session.suggestions()
      val text = SpannableStringBuilder()
      val label = " ${session.modeLabel()} "
      text.append(label)
      text.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      text.setSpan(ForegroundColorSpan(Color.rgb(0x9C, 0xC3, 0xFF)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      if (candidates.isNotEmpty() && index >= 0) {
        val row = T9Session.CANDIDATE_ROW
        val start = (index / row) * row
        val end = minOf(candidates.size, start + row)
        if (start > 0) text.append(" ‹")
        for (i in start until end) {
          text.append("  ")
          val s = text.length
          text.append(candidates[i])
          if (i == index) {
            text.setSpan(BackgroundColorSpan(Color.rgb(0x2C, 0x6B, 0xED)), s, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), s, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
        }
        if (end < candidates.size) text.append("  ›")
      }
      bar.text = text
      bar.visibility = View.VISIBLE
      positionBar()
    }

    private fun createBar(): TextView {
      val tv = TextView(activity)
      tv.setBackgroundColor(Color.argb(0xEE, 0x1B, 0x1B, 0x1D))
      tv.setTextColor(Color.WHITE)
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      tv.maxLines = 2
      val pad = (4 * activity.resources.displayMetrics.density).toInt()
      tv.setPadding(pad, pad, pad, pad)
      tv.elevation = 24 * activity.resources.displayMetrics.density
      tv.isFocusable = false
      tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      tv.visibility = View.GONE
      val decor = activity.window.decorView as ViewGroup
      decor.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
      tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBar() }
      return tv
    }

    /** Sits just above the focused field, or just below it if there's no
     *  room above (e.g. a search box at the top of the screen). */
    private fun positionBar() {
      val field = sessionView ?: return
      val bar = barView ?: return
      if (bar.visibility != View.VISIBLE) return
      val loc = IntArray(2)
      field.getLocationInWindow(loc)
      val above = loc[1] - bar.height
      bar.translationY = if (above >= 0) above.toFloat() else (loc[1] + field.height).toFloat()
    }

    private fun hideBar() {
      barView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }
  }
}
