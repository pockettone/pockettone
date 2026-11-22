package org.thoughtcrime.securesms.flip.t9

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Keypad T9 for one focused text field, driven straight from physical key
 * events. No system keyboard (IME) is involved; the field is edited through
 * its own [InputConnection], the same interface a keyboard app would use.
 * That works for classic EditText fields and Jetpack Compose text fields
 * alike, and leaves Signal's own text handling (mentions, drafts, styling)
 * in charge of the field's contents.
 *
 * The typing rules are TurboText's T9InputController (Ben Showalter, used
 * with permission), adapted from a private text buffer to an InputConnection:
 * the word being typed is shown as composing (underlined) text, and only
 * goes into the field for real once it's confirmed.
 *
 * Controls:
 *   2-9          build a T9 word (Word mode) or cycle letters (abc mode)
 *   2-9 (hold)   type the digit itself (Word mode)
 *   1            punctuation picker; Left/Right browse, OK/1 insert
 *   0            starts a number in Word mode
 *   #            space (confirms the current word); hold for a new line
 *   * (tap)      casing: lower -> Cap -> ALL CAPS -> lower
 *   Left/Right   with a word in progress: previous/next suggestion
 *   Up/Down      with a word in progress: jump a row of suggestions
 *   OK           confirm the current word
 *   Clear        undo the last key of the word in progress, or delete
 *                the character before the cursor
 */
class T9Session(
  private val engine: T9Engine,
  private var ic: InputConnection,
  editorInfo: EditorInfo,
  private val listener: Listener
) {

  enum class Mode { WORD, ABC, NUMBER }

  private enum class Case { LOWER, CAP_NEXT, ALL_CAPS }

  interface Listener {
    /** Called whenever the mode label or the suggestion list changes. */
    fun onStateChanged(session: T9Session)
  }

  val inputType: Int = editorInfo.inputType
  private val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
  private val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
  private val isPassword = inputClass == InputType.TYPE_CLASS_TEXT &&
    (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
      variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
      variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
  private val isNoSuggestions = isPassword ||
    (inputClass == InputType.TYPE_CLASS_TEXT &&
      (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
        variation == InputType.TYPE_TEXT_VARIATION_URI ||
        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))

  /** Numbers-only fields (phone number, verification code, numeric PIN). */
  val isNumericField: Boolean = inputClass == InputType.TYPE_CLASS_NUMBER ||
    inputClass == InputType.TYPE_CLASS_PHONE ||
    inputClass == InputType.TYPE_CLASS_DATETIME

  /** Holding # makes a new line only where the field allows one. */
  private val allowNewLines = inputClass == InputType.TYPE_CLASS_TEXT &&
    (editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

  /** Don't learn word frequencies from passwords. */
  private val learnsWords = !isNoSuggestions

  var mode: Mode = when {
    isNumericField -> Mode.NUMBER
    isNoSuggestions -> Mode.ABC
    else -> Mode.WORD
  }
    private set

  private var case = Case.LOWER
  private var pendingDigits = ""
  private var candidateIndex = 0

  private var multiTapKey: Char? = null
  private var multiTapIndex = 0

  private val punctuationList = listOf(
    ".", ",", "'", "?", "!", "-", ":", ";", "@", "(", ")", "\"", "/",
    "#", "$", "%", "&", "*", "+", "=", "_", "~", "<", ">"
  )
  private var punctuationActive = false
  private var punctuationIndex = 0

  private val handler = Handler(Looper.getMainLooper())
  private val multiTapTimeout = Runnable { commitMultiTap() }
  private var holdRunnable: Runnable? = null
  private var holdKeyCode: Int? = null

  companion object {
    private const val LONG_PRESS_MS = 700L
    private const val MULTITAP_MS = 700L
    const val CANDIDATE_ROW = 4
  }

  // ---------------------------------------------------------------------
  // State the host reads to draw the suggestion bar / decide key routing
  // ---------------------------------------------------------------------

  /** "t9word" / "T9Word" / "T9WORD", "abc" / "Abc" / "ABC", or "123". */
  fun modeLabel(): String = when (mode) {
    Mode.WORD -> when (case) {
      Case.LOWER -> "t9word"
      Case.CAP_NEXT -> "T9Word"
      Case.ALL_CAPS -> "T9WORD"
    }
    Mode.ABC -> when (case) {
      Case.LOWER -> "abc"
      Case.CAP_NEXT -> "Abc"
      Case.ALL_CAPS -> "ABC"
    }
    Mode.NUMBER -> "123"
  }

  /** Something is being composed (a T9 word, a multi-tap letter, or the
   *  punctuation picker) — arrows/OK/Clear belong to this session. */
  fun isComposing(): Boolean = punctuationActive || pendingDigits.isNotEmpty() || multiTapKey != null

  /** A word or the punctuation picker is up — arrows browse suggestions. */
  fun isChoosing(): Boolean = punctuationActive || (mode == Mode.WORD && pendingDigits.isNotEmpty())

  /** Suggestions currently on offer, with the highlighted one. */
  fun suggestions(): Pair<List<String>, Int> = when {
    punctuationActive -> punctuationList to punctuationIndex
    mode == Mode.WORD && pendingDigits.isNotEmpty() -> lookup(pendingDigits).map { applyCase(it) } to candidateIndex
    else -> emptyList<String>() to -1
  }

  /** True when there's nothing before the cursor and nothing composing —
   *  the cue for Clear to act as Back instead of backspace. */
  fun isFieldEmptyBeforeCursor(): Boolean =
    !isComposing() && ic.getTextBeforeCursor(1, 0).isNullOrEmpty()

  // ---------------------------------------------------------------------
  // Mode switching (left soft key menu)
  // ---------------------------------------------------------------------

  fun selectMode(newMode: Mode) {
    confirmPending()
    mode = newMode
    case = Case.LOWER
    notifyChanged()
  }

  /** Flushes anything still composing into the field. Call before the
   *  field loses focus or before something reads/sends its text. */
  fun confirmPending() {
    if (punctuationActive) {
      punctuationActive = false
      commit(punctuationList[punctuationIndex])
    }
    when {
      multiTapKey != null -> commitMultiTap()
      pendingDigits.isNotEmpty() -> confirmWord()
    }
    cancelHold()
  }

  /** Points this session at a fresh connection to the same kind of field
   *  (Compose hands out a new one as focus moves), keeping mode and case. */
  fun rebind(newIc: InputConnection) {
    cancelHold()
    ic = newIc
  }

  fun release() {
    confirmPending()
    handler.removeCallbacks(multiTapTimeout)
    ic.finishComposingText()
  }

  // ---------------------------------------------------------------------
  // Key handling. Returns true if the key was used.
  // ---------------------------------------------------------------------

  fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val first = event.repeatCount == 0

    if (punctuationActive) return onPunctuationKey(keyCode, first)

    val digit = digitFor(keyCode)
    when {
      keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> {
        // Acts on key-up (see onKeyUp), like TurboText.
        return mode != Mode.NUMBER || !isNumericField
      }
      keyCode == KeyEvent.KEYCODE_POUND -> {
        if (first) {
          cancelHold()
          if (mode == Mode.NUMBER && isNumericField) {
            commit("#")
          } else {
            insertSpace()
            if (allowNewLines) startHold(keyCode) { onPoundHeld() }
          }
        }
        return true
      }
      isChoosing() && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { stepCandidate(1); return true }
      isChoosing() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { stepCandidate(-1); return true }
      isChoosing() && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { jumpCandidate(1); return true }
      isChoosing() && keyCode == KeyEvent.KEYCODE_DPAD_UP -> { jumpCandidate(-1); return true }
      isComposing() && isCenterKey(keyCode) -> {
        if (first) confirmPending()
        return true
      }
      digit != null -> {
        if (first) {
          cancelHold()
          handleDigit(digit)
          if (mode == Mode.WORD) startHold(keyCode) { onDigitHeld(digit) }
        }
        return true
      }
    }
    return false
  }

  fun onKeyUp(keyCode: Int): Boolean {
    if (keyCode == holdKeyCode) cancelHold()
    if (keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY) {
      if (mode == Mode.NUMBER) {
        if (isNumericField) return false
        commit("*")
      } else {
        cycleCase()
      }
      return true
    }
    return false
  }

  /** Clear / DEL. Returns false when there was nothing to delete. */
  fun backspace(): Boolean {
    when {
      punctuationActive -> {
        punctuationActive = false
        ic.setComposingText("", 1)
      }
      pendingDigits.isNotEmpty() -> {
        pendingDigits = pendingDigits.dropLast(1)
        if (pendingDigits.isEmpty()) {
          ic.setComposingText("", 1)
          if (case == Case.CAP_NEXT) case = Case.LOWER
        } else {
          candidateIndex = defaultCandidateIndex(pendingDigits)
          renderPreview()
        }
      }
      multiTapKey != null -> {
        handler.removeCallbacks(multiTapTimeout)
        multiTapKey = null
        ic.setComposingText("", 1)
      }
      else -> {
        val before = ic.getTextBeforeCursor(2, 0)
        if (before.isNullOrEmpty()) return false
        // Selected text: delete the selection, like a normal keyboard.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
          ic.commitText("", 1)
        } else {
          val count = if (before.length == 2 &&
            Character.isHighSurrogate(before[0]) && Character.isLowSurrogate(before[1])
          ) 2 else 1
          if (Build.VERSION.SDK_INT >= 24) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
          } else {
            ic.deleteSurroundingText(count, 0)
          }
        }
      }
    }
    notifyChanged()
    return true
  }

  // ---------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------

  private fun onPunctuationKey(keyCode: Int, first: Boolean): Boolean {
    when {
      keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
        punctuationIndex = (punctuationIndex - 1 + punctuationList.size) % punctuationList.size
        renderPreview()
      }
      keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
        punctuationIndex = (punctuationIndex + 1) % punctuationList.size
        renderPreview()
      }
      isCenterKey(keyCode) || keyCode == KeyEvent.KEYCODE_1 -> {
        // repeatCount guard: a held 1 auto-repeats and would otherwise
        // confirm the mark before the hold timer types a literal "1".
        if (first) {
          punctuationActive = false
          commit(punctuationList[punctuationIndex])
        }
      }
      keyCode == KeyEvent.KEYCODE_POUND -> {
        if (first) {
          punctuationActive = false
          commit(punctuationList[punctuationIndex] + " ")
        }
      }
      else -> {
        // Any other digit: take the mark and carry on typing.
        val digit = digitFor(keyCode)
        if (digit != null && first) {
          punctuationActive = false
          commit(punctuationList[punctuationIndex])
          return onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
      }
    }
    notifyChanged()
    return true
  }

  private fun handleDigit(digit: Char) {
    if (mode == Mode.NUMBER) {
      commit(digit.toString())
      return
    }
    // Mid-word, 0 and 1 extend the sequence so raw numbers like "401"
    // stay reachable (the raw digits are always a candidate).
    if (mode == Mode.WORD && pendingDigits.isNotEmpty() && (digit == '0' || digit == '1')) {
      pendingDigits += digit
      candidateIndex = defaultCandidateIndex(pendingDigits)
      renderPreview()
      return
    }
    if (digit == '1') {
      if (multiTapKey != null) commitMultiTap()
      punctuationActive = true
      punctuationIndex = guessPunctuationIndex()
      renderPreview()
      return
    }
    when (mode) {
      Mode.ABC -> handleMultiTap(digit)
      Mode.WORD -> if (engine.isLetterKey(digit) || digit == '0') {
        autoCapitalizeIfSentenceStart()
        pendingDigits += digit
        candidateIndex = defaultCandidateIndex(pendingDigits)
        renderPreview()
      }
      Mode.NUMBER -> Unit
    }
  }

  private fun handleMultiTap(digit: Char) {
    val chars = engine.multiTapCharsFor(digit)
    if (chars.isEmpty()) return
    if (multiTapKey == digit) {
      multiTapIndex = (multiTapIndex + 1) % chars.size
    } else {
      commitMultiTap()
      autoCapitalizeIfSentenceStart()
      multiTapKey = digit
      multiTapIndex = 0
    }
    handler.removeCallbacks(multiTapTimeout)
    handler.postDelayed(multiTapTimeout, MULTITAP_MS)
    ic.setComposingText(applyCase(chars[multiTapIndex]), 1)
    notifyChanged()
  }

  private fun commitMultiTap() {
    handler.removeCallbacks(multiTapTimeout)
    val key = multiTapKey ?: return
    val chars = engine.multiTapCharsFor(key)
    multiTapKey = null
    if (chars.isNotEmpty()) {
      commit(applyCase(chars[multiTapIndex]))
      if (case == Case.CAP_NEXT) case = Case.LOWER
    }
    multiTapIndex = 0
    notifyChanged()
  }

  private fun insertSpace() {
    when {
      mode == Mode.WORD && pendingDigits.isNotEmpty() -> confirmWord()
      mode == Mode.ABC && multiTapKey != null -> commitMultiTap()
    }
    commit(" ")
  }

  private fun confirmWord() {
    if (mode == Mode.ABC) { commitMultiTap(); return }
    if (pendingDigits.isEmpty()) return
    val candidates = lookup(pendingDigits)
    val base = candidates.getOrNull(candidateIndex)
    val raw = base ?: pendingDigits
    if (base != null && learnsWords) engine.recordConfirmed(pendingDigits, base)
    val word = if (case == Case.LOWER && raw.equals("i", ignoreCase = true) && isAtWordBoundary()) "I" else applyCase(raw)
    pendingDigits = ""
    candidateIndex = 0
    commit(word)
    if (case == Case.CAP_NEXT) case = Case.LOWER
    notifyChanged()
  }

  private fun onDigitHeld(digit: Char) {
    // The tap already added a letter key to the word; swap it for the digit.
    if (pendingDigits.isNotEmpty() && pendingDigits.last() == digit) {
      pendingDigits = pendingDigits.dropLast(1)
    }
    if (digit == '1') punctuationActive = false
    if (pendingDigits.isEmpty()) {
      ic.setComposingText("", 1)
      if (case == Case.CAP_NEXT) case = Case.LOWER
    } else {
      candidateIndex = defaultCandidateIndex(pendingDigits)
      confirmWord()
    }
    commit(digit.toString())
  }

  private fun onPoundHeld() {
    val before = ic.getTextBeforeCursor(1, 0)
    if (before != null && before.toString() == " ") ic.deleteSurroundingText(1, 0)
    commit("\n")
  }

  private fun startHold(keyCode: Int, action: () -> Unit) {
    val r = Runnable {
      holdRunnable = null
      holdKeyCode = null
      action()
    }
    holdRunnable = r
    holdKeyCode = keyCode
    handler.postDelayed(r, LONG_PRESS_MS)
  }

  private fun cancelHold() {
    holdRunnable?.let { handler.removeCallbacks(it) }
    holdRunnable = null
    holdKeyCode = null
  }

  private fun cycleCase() {
    case = when (case) {
      Case.LOWER -> Case.CAP_NEXT
      Case.CAP_NEXT -> Case.ALL_CAPS
      Case.ALL_CAPS -> Case.LOWER
    }
    if (pendingDigits.isNotEmpty()) renderPreview()
    multiTapKey?.let { key ->
      engine.multiTapCharsFor(key).getOrNull(multiTapIndex)?.let { ic.setComposingText(applyCase(it), 1) }
    }
    notifyChanged()
  }

  private fun stepCandidate(delta: Int) {
    if (punctuationActive) return
    val size = lookup(pendingDigits).size
    if (size == 0) return
    candidateIndex = (candidateIndex + delta + size) % size
    renderPreview()
  }

  private fun jumpCandidate(direction: Int) {
    val size = lookup(pendingDigits).size
    if (size == 0) return
    candidateIndex = (candidateIndex + direction * CANDIDATE_ROW).coerceIn(0, size - 1)
    renderPreview()
  }

  /** The raw digits are listed first so they're one Left press away;
   *  start the highlight on the first real word instead. */
  private fun defaultCandidateIndex(digits: String): Int {
    val c = lookup(digits)
    return if (c.size > 1 && c[0] == digits) 1 else 0
  }

  private fun renderPreview() {
    val preview = when {
      punctuationActive -> punctuationList[punctuationIndex]
      pendingDigits.isNotEmpty() -> {
        val c = lookup(pendingDigits)
        applyCase(c.getOrNull(candidateIndex) ?: pendingDigits)
      }
      else -> ""
    }
    ic.setComposingText(preview, 1)
    notifyChanged()
  }

  private fun commit(text: String) {
    ic.commitText(text, 1)
    notifyChanged()
  }

  private fun lookup(digits: String): List<String> {
    if (digits.isEmpty()) return emptyList()
    if (learnsWords) engine.setPreviousWord(previousWord())
    return engine.candidatesFor(digits)
  }

  /** The whole word right before the cursor (for TT9 word-pair ranking). */
  private fun previousWord(): String? {
    val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return null
    if (before.isEmpty() || before.last().isLetterOrDigit()) return null
    val trimmed = before.trimEnd()
    var start = trimmed.length
    while (start > 0 && (trimmed[start - 1].isLetter() || trimmed[start - 1] == '\'')) start--
    return trimmed.substring(start).takeIf { it.isNotEmpty() }
  }

  private fun isAtWordBoundary(): Boolean {
    val before = ic.getTextBeforeCursor(1, 0)
    return before.isNullOrEmpty() || before[0].isWhitespace()
  }

  private fun autoCapitalizeIfSentenceStart() {
    if (case != Case.LOWER || isPassword || isNoSuggestions) return
    val before = (ic.getTextBeforeCursor(80, 0)?.toString() ?: "").trimEnd(' ')
    if (before.isEmpty() || before.last() == '\n' || before.last() in ".!?") {
      case = Case.CAP_NEXT
    }
  }

  private fun applyCase(text: String): String = when (case) {
    Case.LOWER -> text
    Case.CAP_NEXT -> text.replaceFirstChar { it.uppercase() }
    Case.ALL_CAPS -> text.uppercase()
  }

  private fun guessPunctuationIndex(): Int {
    val text = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
    val lastEnd = text.indexOfLast { it == '.' || it == '!' || it == '?' }
    val words = text.substring(lastEnd + 1).trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val questionStarters = setOf(
      "who", "what", "when", "where", "why", "how", "is", "are", "am", "was", "were",
      "do", "does", "did", "can", "could", "would", "will", "should", "has", "have", "had"
    )
    val guess = when {
      words.isEmpty() -> "."
      words.first() in questionStarters -> "?"
      words.size <= 2 -> ","
      else -> "."
    }
    return punctuationList.indexOf(guess).coerceAtLeast(0)
  }

  private fun notifyChanged() = listener.onStateChanged(this)

  private fun isCenterKey(keyCode: Int) =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

  private fun digitFor(keyCode: Int): Char? =
    if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) '0' + (keyCode - KeyEvent.KEYCODE_0) else null
}
