package org.thoughtcrime.securesms.flip

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PocketTone settings: switch Signal's extra features back on. Plain
 * TurboText-style list — OK toggles the selected row.
 */
class PocketToneSettingsActivity : AppCompatActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, PocketToneSettingsActivity::class.java)
  }

  private class Option(val title: String, val summary: String, val get: () -> Boolean, val set: (Boolean) -> Unit)

  private val options = listOf(
    Option("Stories", "Share and view 24-hour stories", { PocketToneUi.storiesEnabled }, { PocketToneUi.setStoriesEnabled(it) }),
    Option("Full top bar", "Signal's top bar with your photo, search and the three dots. When off, small floating New and ⋮ buttons are used", { PocketToneUi.showFullTopBar }, { PocketToneUi.showFullTopBar = it }),
    Option("Chats and Calls tabs", "Tab bar at the bottom of the chat list (Stories gets a tab only while Stories is on)", { PocketToneUi.showTabBar }, { PocketToneUi.showTabBar = it }),
    Option("Round buttons", "New-chat and camera buttons floating over the chat list", { PocketToneUi.showRoundButtons }, { PocketToneUi.showRoundButtons = it }),
    Option("Call buttons in chats", "Voice and video call buttons at the top of a chat. When off, calls are under Options", { PocketToneUi.showCallButtons }, { PocketToneUi.showCallButtons = it }),
    Option("Extra message buttons", "Emoji, quick camera and voice note buttons in chats", { PocketToneUi.showChatExtras }, { PocketToneUi.showChatExtras = it }),
    Option("Tips and banners", "Signal's tips and reminders on the chat list", { PocketToneUi.showTips }, { PocketToneUi.showTips = it })
  )

  // Stories changes apply in the background, so remember what was chosen.
  private val state = BooleanArray(options.size)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val density = resources.displayMetrics.density
    val pad = (8 * density).toInt()
    options.forEachIndexed { i, o -> state[i] = o.get() }

    val title = TextView(this).apply {
      text = "PocketTone settings"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setBackgroundColor(FocusHighlight.SURFACE)
      setPadding(pad, pad, pad, pad)
    }

    val list = ListView(this).apply {
      choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
      divider = null
      itemsCanFocus = false
      adapter = OptionAdapter()
      options.indices.forEach { setItemChecked(it, state[it]) }
      setOnItemClickListener { _, _, position, _ ->
        val checked = isItemChecked(position)
        state[position] = checked
        options[position].set(checked)
        (adapter as BaseAdapter).notifyDataSetChanged()
      }
    }

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.BLACK)
      addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    setContentView(root)
    list.requestFocus()
  }

  private inner class OptionAdapter : BaseAdapter() {
    override fun getCount() = options.size
    override fun getItem(position: Int) = options[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val density = resources.displayMetrics.density
      val row = convertView as? CheckedTextView ?: CheckedTextView(this@PocketToneSettingsActivity).apply {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorMultiple))
        checkMarkDrawable = ta.getDrawable(0)
        ta.recycle()
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        val p = (10 * density).toInt()
        setPadding(p, p, p, p)
        minHeight = (56 * density).toInt()
      }
      val o = options[position]
      val text = android.text.SpannableStringBuilder(o.title).append('\n')
      val s = text.length
      text.append(o.summary)
      text.setSpan(android.text.style.ForegroundColorSpan(0xFFAAAAAA.toInt()), s, text.length, 0)
      text.setSpan(android.text.style.RelativeSizeSpan(0.8f), s, text.length, 0)
      row.text = text
      row.isChecked = state[position]
      return row
    }
  }
}
