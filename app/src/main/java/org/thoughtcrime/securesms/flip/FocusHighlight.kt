package org.thoughtcrime.securesms.flip

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

/**
 * TurboText-style selection, drawn on every screen: the focused row is
 * shaded and gets a 6dp accent strip down its right edge, and a list that
 * scrolls gets a scroll bar whose thumb follows the selected row rather
 * than the scroll offset.
 *
 * Signal's own focus state is a faint ripple tint that's close to invisible
 * on a 240x320 screen. This draws in the window overlay (above all content)
 * and tracks the focused view each frame, so it needs no per-screen changes.
 * Covers Activity windows and DialogFragment / bottom-sheet windows.
 */
object FocusHighlight {

  /** TurboText's accent (#66CCFF) and its #222222 bar/track grey. */
  const val ACCENT = 0xFF66CCFF.toInt()
  const val SURFACE = 0xFF222222.toInt()

  private val attached = WeakHashMap<View, Highlighter>()

  @JvmStatic
  fun attach(window: Window) {
    val decor = window.decorView as? ViewGroup ?: return
    if (attached.containsKey(decor)) return
    attached[decor] = Highlighter(decor)
  }

  /** Also highlight inside dialogs and bottom sheets the activity shows. */
  @JvmStatic
  fun watchDialogs(activity: Activity) {
    val fa = activity as? FragmentActivity ?: return
    fa.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
      override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
        val window = (f as? DialogFragment)?.dialog?.window ?: return
        attach(window)
      }
    }, true)
  }

  private class Highlighter(private val decor: ViewGroup) :
    ViewTreeObserver.OnGlobalFocusChangeListener,
    ViewTreeObserver.OnPreDrawListener {

    private val density = decor.resources.displayMetrics.density
    private val stripWidth = (6 * density).toInt()
    private val barWidth = (5 * density).toInt()
    private val minThumb = (24 * density).toInt()

    private val isDark =
      (decor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Drawn over the row's content, so the shade is translucent: on the
    // dark theme it lifts black to about TurboText's #333333 and leaves
    // white text white.
    private val shade = ColorDrawable(if (isDark) Color.argb(0x3A, 0xFF, 0xFF, 0xFF) else Color.argb(0x1F, 0, 0, 0))
    private val strip = ColorDrawable(ACCENT)
    private val track = ColorDrawable(if (isDark) SURFACE else Color.rgb(0xE0, 0xE0, 0xE0))
    private val thumb = ColorDrawable(ACCENT)
    private val all = listOf(shade, strip, track, thumb)
    private val shown = HashSet<Drawable>()

    private val rect = Rect()
    private val listRect = Rect()
    private val tmp = Rect()
    private var dirty = false

    init {
      decor.viewTreeObserver.addOnGlobalFocusChangeListener(this)
      decor.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) = update()

    // Runs every frame so the highlight tracks scrolling lists; it only
    // invalidates when something actually moved.
    override fun onPreDraw(): Boolean {
      update()
      return true
    }

    private fun update() {
      val focus = decor.findFocus()
      if (focus == null || !focus.isShown || !focus.getGlobalVisibleRect(rect) || coversScreen(rect)) {
        hideAll()
        return
      }

      dirty = false
      val list = enclosingList(focus)
      val scrollbar = list != null && layoutScrollbar(list, focus)
      if (scrollbar) listRect.set(tmp) else place(thumb, false, null)
      place(track, scrollbar, listRect)

      // Leave room for the scroll bar so the strip sits just left of it.
      val stripRight = if (scrollbar) minOf(rect.right, listRect.left) else rect.right
      // A text field keeps its own look; it only gets the strip.
      place(shade, focus !is EditText, rect)
      tmp.set(stripRight - stripWidth, rect.top, stripRight, rect.bottom)
      place(strip, true, tmp)

      if (dirty) decor.invalidate()
    }

    /** Sets [listRect] (via tmp) to the track and places the thumb. False
     *  when the whole list already fits on screen. */
    private fun layoutScrollbar(list: RecyclerView, focus: View): Boolean {
      val count = list.adapter?.itemCount ?: return false
      if (count < 2 || list.childCount >= count) return false
      val row = rowOf(list, focus) ?: return false
      val pos = list.getChildAdapterPosition(row).takeIf { it != RecyclerView.NO_POSITION } ?: return false
      if (!list.getGlobalVisibleRect(tmp)) return false
      tmp.left = tmp.right - barWidth
      val height = tmp.height()
      val thumbH = maxOf(minThumb, height * list.childCount / count).coerceAtMost(height)
      val top = tmp.top + (height - thumbH) * pos.coerceIn(0, count - 1) / (count - 1)
      val thumbRect = Rect(tmp.left, top, tmp.right, top + thumbH)
      place(thumb, true, thumbRect)
      return true
    }

    private fun enclosingList(view: View): RecyclerView? {
      var p = view.parent
      while (p is View) {
        if (p is RecyclerView) return p
        p = p.parent
      }
      return null
    }

    private fun rowOf(list: RecyclerView, view: View): View? {
      var v: View = view
      while (v.parent !== list) v = v.parent as? View ?: return null
      return v
    }

    /** Shows/hides [d] at [bounds], noting in [dirty] if anything moved. */
    private fun place(d: Drawable, visible: Boolean, bounds: Rect?) {
      if (!visible || bounds == null) {
        if (shown.remove(d)) decor.overlay.remove(d)
        return
      }
      if (d.bounds != bounds) {
        d.bounds = Rect(bounds)
        dirty = true
      }
      if (shown.add(d)) {
        decor.overlay.add(d)
        dirty = true
      }
    }

    /** A focused list, Compose host or web view that fills the screen isn't
     *  a useful thing to box (the captcha has its own cursor). */
    private fun coversScreen(r: Rect): Boolean =
      r.width() >= decor.width * 0.95f && r.height() >= decor.height * 0.6f

    private fun hideAll() {
      for (d in all) place(d, false, null)
    }
  }
}
