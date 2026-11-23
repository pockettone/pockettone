package org.thoughtcrime.securesms.flip

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.emoji.EmojiUtil

/**
 * TurboText-style message options for the D-pad: OK on a message opens a
 * plain list (React, Reply, Copy text, ...), and React opens a list of
 * named reactions with a check on the one already sent. Replaces Signal's
 * touch-only long-press overlay in chats.
 */
object KeypadMessageMenu {

  /** Emoji and the name shown beside it. */
  val REACTIONS = listOf(
    "👍" to "Thumbs up",
    "👎" to "Thumbs down",
    "😀" to "Smile",
    "😂" to "Laughing",
    "❤️" to "Heart",
    "😮" to "Surprised",
    "😢" to "Sad",
    "😡" to "Angry",
    "🙏" to "Thank you"
  )

  @JvmStatic
  fun show(context: Context, rows: List<Pair<CharSequence, () -> Unit>>) {
    if (rows.isEmpty()) return
    MaterialAlertDialogBuilder(context)
      .setItems(rows.map { it.first }.toTypedArray()) { dialog, which ->
        dialog.dismiss()
        rows[which].second()
      }
      .show()
  }

  /** [applied] is the reaction we already sent, if any; picking it again takes it back. */
  @JvmStatic
  fun showReactions(context: Context, applied: String?, onPick: (String) -> Unit) {
    val current = applied?.let { EmojiUtil.getCanonicalRepresentation(it) }
    val labels = REACTIONS.map { (emoji, name) ->
      if (current != null && EmojiUtil.getCanonicalRepresentation(emoji) == current) "$emoji  $name ✓" else "$emoji  $name"
    }
    MaterialAlertDialogBuilder(context)
      .setItems(labels.toTypedArray()) { dialog, which ->
        dialog.dismiss()
        onPick(REACTIONS[which].first)
      }
      .show()
  }
}
