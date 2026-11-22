package org.thoughtcrime.securesms.flip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Wraps one child (normally a [WebView]) and gives it an on-screen pointer
 * driven by the keypad, so touch-only web pages such as Signal's captcha can
 * be used on a phone without a touchscreen.
 *
 *  - D-pad (or 2/4/6/8) moves the pointer; holding a key speeds it up.
 *    Pushing past an edge scrolls the page.
 *  - OK (or 5) taps where the pointer is.
 *  - 1 zooms out, 3 zooms in.
 *
 * The pointer stays hidden until the first keypad key, so touch phones see
 * the page unchanged.
 */
class KeypadCursorLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  private val density = resources.displayMetrics.density

  /** Pointer tip, in the child's coordinates. */
  private var cursorX = 0f
  private var cursorY = 0f
  private var cursorShown = false
  private var hintShown = false

  private val arrow = Path().apply {
    val d = density
    moveTo(0f, 0f)
    lineTo(0f, 17f * d)
    lineTo(4.5f * d, 13f * d)
    lineTo(7.5f * d, 19.5f * d)
    lineTo(10.5f * d, 18f * d)
    lineTo(7.5f * d, 11.5f * d)
    lineTo(13f * d, 11.5f * d)
    close()
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.FILL
  }
  private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.BLACK
    style = Paint.Style.STROKE
    strokeWidth = 1.5f * density
    strokeJoin = Paint.Join.ROUND
  }

  init {
    isFocusable = true
    setWillNotDraw(false)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // Without focus somewhere inside, the first arrow press would only move
    // focus here instead of moving the pointer.
    post { if (!hasFocus()) requestFocus() }
  }

  private val target: View? get() = if (childCount > 0) getChildAt(0) else null

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val child = target ?: return super.dispatchKeyEvent(event)
    if (!isCursorKey(event.keyCode)) return super.dispatchKeyEvent(event)
    if (event.action == KeyEvent.ACTION_DOWN) onCursorKey(event, child)
    return true
  }

  private fun isCursorKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8,
    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_3 -> true
    else -> false
  }

  private fun onCursorKey(event: KeyEvent, child: View) {
    if (!cursorShown) {
      cursorShown = true
      cursorX = child.width / 2f
      cursorY = child.height / 2f
      if (!hintShown) {
        hintShown = true
        Toast.makeText(context, "Arrows move the pointer, OK taps, 1 and 3 zoom", Toast.LENGTH_LONG).show()
      }
      invalidate()
      // The first press only brings the pointer up, so nothing is tapped
      // or moved blindly.
      return
    }

    // Small steps for a single press, faster while the key is held.
    val step = minOf(3f + event.repeatCount * 3f, 30f) * density

    when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_2 -> move(child, 0f, -step)
      KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_8 -> move(child, 0f, step)
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4 -> move(child, -step, 0f)
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_6 -> move(child, step, 0f)
      KeyEvent.KEYCODE_1 -> if (event.repeatCount == 0) (child as? WebView)?.zoomOut()
      KeyEvent.KEYCODE_3 -> if (event.repeatCount == 0) (child as? WebView)?.zoomIn()
      else -> if (event.repeatCount == 0) tap(child)
    }
  }

  private fun move(child: View, dx: Float, dy: Float) {
    val maxX = (child.width - 1).coerceAtLeast(0).toFloat()
    val maxY = (child.height - 1).coerceAtLeast(0).toFloat()
    val x = cursorX + dx
    val y = cursorY + dy

    // Past an edge: scroll the page by the overshoot instead.
    val overX = when {
      x < 0f -> x
      x > maxX -> x - maxX
      else -> 0f
    }
    val overY = when {
      y < 0f -> y
      y > maxY -> y - maxY
      else -> 0f
    }
    if (overX != 0f || overY != 0f) {
      val sx = if (overX != 0f && child.canScrollHorizontally(if (overX > 0) 1 else -1)) overX.toInt() else 0
      val sy = if (overY != 0f && child.canScrollVertically(if (overY > 0) 1 else -1)) overY.toInt() else 0
      if (sx != 0 || sy != 0) child.scrollBy(sx, sy)
    }

    cursorX = x.coerceIn(0f, maxX)
    cursorY = y.coerceIn(0f, maxY)
    invalidate()
  }

  /** Sends a finger press and release at the pointer, as a touchscreen would. */
  private fun tap(child: View) {
    val x = cursorX
    val y = cursorY
    val downTime = SystemClock.uptimeMillis()
    child.requestFocus()
    send(child, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
    postDelayed({ send(child, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y) }, 80)
  }

  private fun send(child: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
    val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    ev.source = InputDevice.SOURCE_TOUCHSCREEN
    child.dispatchTouchEvent(ev)
    ev.recycle()
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    val child = target ?: return
    if (!cursorShown) return
    val save = canvas.save()
    canvas.translate(child.left + cursorX, child.top + cursorY)
    canvas.drawPath(arrow, fill)
    canvas.drawPath(arrow, outline)
    canvas.restoreToCount(save)
  }
}
