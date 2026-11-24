package org.thoughtcrime.securesms.flip

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.emoji.EmojiUtil

/**
 * TurboText-style message options for the D-pad. OK on a message opens a
 * plain list (Reply, Copy text, ...). When the message can take a
 * reaction, a row of emoji sits at the top of that list and has focus:
 *
 *  - OK sends the selected one (so OK, OK = thumbs up),
 *  - Left / Right move along the row, Down goes to the list,
 *  - 1 to 9 send that reaction straight away.
 *
 * Picking the reaction you already sent takes it back. Replaces Signal's
 * touch-only long-press overlay in chats.
 */
object KeypadMessageMenu {

  /** Emoji and its name. The order is the number key (1 = first). */
  val REACTIONS = listOf(
    "👍" to "Thumbs up",
    "❤️" to "Heart",
    "😂" to "Laughing",
    "😮" to "Surprised",
    "😢" to "Sad",
    "🙏" to "Thank you",
    "😀" to "Smile",
    "👎" to "Thumbs down",
    "😡" to "Angry"
  )

  private const val FOCUS = 0xFF66CCFF.toInt()
  private const val FOCUS_FILL = 0xFF113344.toInt()
  private const val APPLIED_FILL = 0xFF335566.toInt()
  private const val HINT = 0xFFAAAAAA.toInt()

  /** The emoji row shown above the options. [applied] is the reaction
   *  already sent, if any. */
  class Reactions(val applied: String?, val onPick: (String) -> Unit)

  @JvmStatic
  @JvmOverloads
  fun show(context: Context, rows: List<Pair<CharSequence, () -> Unit>>, reactions: Reactions? = null) {
    if (reactions == null) {
      if (rows.isEmpty()) return
      MaterialAlertDialogBuilder(context)
        .setItems(rows.map { it.first }.toTypedArray()) { dialog, which ->
          dialog.dismiss()
          rows[which].second()
        }
        .show()
      return
    }

    val builder = MaterialAlertDialogBuilder(context)
    val themed = builder.context
    val density = themed.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    var dialog: AlertDialog? = null
    val pick = { index: Int ->
      dialog?.dismiss()
      reactions.onPick(REACTIONS[index].first)
    }

    val appliedIndex = indexOfApplied(reactions.applied)
    val startIndex = maxOf(appliedIndex, 0)

    val strip = LinearLayout(themed).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(dp(4), dp(2), dp(4), dp(4))
    }
    val cells = REACTIONS.mapIndexed { i, (emoji, name) ->
      val label = SpannableStringBuilder(emoji).append('\n')
      val s = label.length
      label.append((i + 1).toString())
      label.setSpan(AbsoluteSizeSpan(11, true), s, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      label.setSpan(ForegroundColorSpan(HINT), s, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      val cell = TextView(themed).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        contentDescription = if (i == appliedIndex) "$name, sent. Press to take it back" else name
        isFocusable = true
        isClickable = true
        background = cellBackground(density, applied = i == appliedIndex)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setOnClickListener { pick(i) }
      }
      strip.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) })
      cell
    }
    val scroller = HorizontalScrollView(themed).apply {
      isHorizontalScrollBarEnabled = false
      isFocusable = false
      addView(strip)
    }

    val hint = TextView(themed).apply {
      text = if (appliedIndex >= 0) "React: OK or 1-9 (same one takes it back)" else "React: OK or press 1-9"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setTextColor(HINT)
      setPadding(dp(8), dp(6), dp(8), 0)
    }

    val list = ListView(themed).apply {
      adapter = ArrayAdapter(themed, android.R.layout.simple_list_item_1, rows.map { it.first })
      divider = null
      setOnItemClickListener { _, _, position, _ ->
        dialog?.dismiss()
        rows[position].second()
      }
      // Up from the first option goes back to the emoji row.
      setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && selectedItemPosition <= 0) {
          if (event.action == KeyEvent.ACTION_DOWN) cells[startIndex].requestFocus()
          true
        } else {
          false
        }
      }
    }

    val root = LinearLayout(themed).apply {
      orientation = LinearLayout.VERTICAL
      addView(hint)
      addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      if (rows.isNotEmpty()) addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val d = builder
      .setView(root)
      .setOnKeyListener(numberKeys { pick(it) })
      .create()
    d.setOnShowListener { cells[startIndex].requestFocus() }
    dialog = d
    d.show()
  }

  /** Just the reactions, as a plain list: "1  👍  Thumbs up". */
  @JvmStatic
  fun showReactions(context: Context, applied: String?, onPick: (String) -> Unit) {
    val appliedIndex = indexOfApplied(applied)
    val labels = REACTIONS.mapIndexed { i, (emoji, name) ->
      "${i + 1}  $emoji  $name" + if (i == appliedIndex) " ✓" else ""
    }
    var dialog: AlertDialog? = null
    dialog = MaterialAlertDialogBuilder(context)
      .setItems(labels.toTypedArray()) { d, which ->
        d.dismiss()
        onPick(REACTIONS[which].first)
      }
      .setOnKeyListener(
        numberKeys {
          dialog?.dismiss()
          onPick(REACTIONS[it].first)
        }
      )
      .show()
  }

  private fun indexOfApplied(applied: String?): Int {
    val current = applied?.let { EmojiUtil.getCanonicalRepresentation(it) } ?: return -1
    return REACTIONS.indexOfFirst { EmojiUtil.getCanonicalRepresentation(it.first) == current }
  }

  /** 1-9 pick that reaction (on key up, so the press doesn't leak into
   *  whatever gets focus after the dialog closes). */
  private fun numberKeys(onNumber: (Int) -> Unit) = android.content.DialogInterface.OnKeyListener { _, keyCode, event ->
    if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
      if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) onNumber(keyCode - KeyEvent.KEYCODE_1)
      true
    } else {
      false
    }
  }

  /** Rounded cell: light-blue ring when selected, tinted when it's the
   *  reaction already sent. */
  private fun cellBackground(density: Float, applied: Boolean): StateListDrawable {
    val r = 8 * density
    val selected = GradientDrawable().apply {
      cornerRadius = r
      setColor(FOCUS_FILL)
      setStroke((2 * density).toInt(), FOCUS)
    }
    val normal = GradientDrawable().apply {
      cornerRadius = r
      setColor(if (applied) APPLIED_FILL else Color.TRANSPARENT)
    }
    return StateListDrawable().apply {
      addState(intArrayOf(android.R.attr.state_focused), selected)
      addState(intArrayOf(android.R.attr.state_pressed), selected)
      addState(intArrayOf(), normal)
    }
  }
}
