package org.thoughtcrime.securesms.flip

import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Predictable up / down through lists, TurboText style.
 *
 * Android's own focus search in a RecyclerView jumps around on a 240x320
 * screen: in a chat (a reversed list) Up can leap to the toolbar and snap
 * the list to the top, a message taller than the screen can't be read to
 * the end, and rows with a clickable avatar stop on the avatar first.
 *
 * With this, Up / Down inside a vertical list:
 *  1. scroll the selected row first if part of it is still off screen,
 *     so long messages can be read line by line;
 *  2. otherwise select the next row on screen in that direction;
 *  3. otherwise scroll to bring more rows in.
 * Only at the very start or end of the list does the key fall through to
 * normal focus search (e.g. down to the message field or the tab bar).
 */
object DpadListScroller {

  @JvmStatic
  fun onKeyDown(focused: View?, keyCode: Int): Boolean {
    val down = when (keyCode) {
      KeyEvent.KEYCODE_DPAD_DOWN -> true
      KeyEvent.KEYCODE_DPAD_UP -> false
      else -> return false
    }
    if (focused == null || focused.onCheckIsTextEditor()) return false

    // The nearest vertical list holding the focus, and its row that does.
    var item: View = focused
    var parent = focused.parent
    while (parent is View) {
      if (parent is RecyclerView) {
        val lm = parent.layoutManager as? LinearLayoutManager
        if (lm != null && lm.orientation == RecyclerView.VERTICAL) break
      }
      item = parent
      parent = parent.parent
    }
    val rv = parent as? RecyclerView ?: return false

    val dir = if (down) 1 else -1
    val focusDir = if (down) View.FOCUS_DOWN else View.FOCUS_UP
    val viewTop = rv.paddingTop
    val viewBottom = rv.height - rv.paddingBottom
    val step = maxOf((viewBottom - viewTop) * 2 / 3, 1)
    val top = item.top + item.translationY.toInt()
    val bottom = item.bottom + item.translationY.toInt()

    // 1. Finish showing the selected row.
    if (down && bottom > viewBottom + 1 && rv.canScrollVertically(1)) {
      rv.scrollBy(0, minOf(step, bottom - viewBottom))
      return true
    }
    if (!down && top < viewTop - 1 && rv.canScrollVertically(-1)) {
      rv.scrollBy(0, -minOf(step, viewTop - top))
      return true
    }

    // 2. Another button inside the same row (e.g. "Block or Report").
    if (item is ViewGroup && item !== focused) {
      val inner = FocusFinder.getInstance().findNextFocus(item, focused, focusDir)
      if (inner != null && inner.requestFocus(focusDir)) return true
    }

    // 3. The next row on screen in that direction that can take focus.
    val rows = (0 until rv.childCount).map { rv.getChildAt(it) }
      .filter { it !== item && it.visibility == View.VISIBLE }
      .filter { if (down) it.top >= bottom - 2 else it.bottom <= top + 2 }
      .sortedBy { if (down) it.top else -it.bottom }
    for (row in rows) {
      if (row.top >= viewBottom && down) break
      if (row.bottom <= viewTop && !down) break
      if (row.requestFocus(focusDir)) return true
    }

    // 4. Bring more rows on screen; the next press selects one.
    if (rv.canScrollVertically(dir)) {
      rv.scrollBy(0, dir * step)
      return true
    }
    return false
  }
}
