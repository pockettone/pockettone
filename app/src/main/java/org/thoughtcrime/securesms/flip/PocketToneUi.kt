package org.thoughtcrime.securesms.flip

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.stories.settings.story.StoriesPrivacySettingsRepository

/**
 * PocketTone's "simple by default" switches. Everything Signal shows that a
 * flip-phone user rarely needs is hidden until it's turned back on in
 * PocketTone settings (Options > PocketTone settings on the chat list).
 */
object PocketToneUi {

  private val TAG = Log.tag(PocketToneUi::class.java)

  private const val SHOW_TAB_BAR = "show_tab_bar"
  private const val SHOW_ROUND_BUTTONS = "show_tabs"
  private const val SHOW_CHAT_EXTRAS = "show_chat_extras"
  private const val SHOW_TIPS = "show_tips"
  private const val SHOW_CALL_BUTTONS = "show_call_buttons"
  private const val STORIES_DEFAULTED = "stories_defaulted"

  private val prefs: SharedPreferences
    get() = AppDependencies.application.getSharedPreferences("pockettone_ui", Context.MODE_PRIVATE)

  /** The Chats / Calls tab bar at the bottom of the chat list. Stories
   *  only gets a tab while Stories is turned on. */
  @JvmStatic
  var showTabBar: Boolean
    get() = prefs.getBoolean(SHOW_TAB_BAR, true)
    set(value) = prefs.edit().putBoolean(SHOW_TAB_BAR, value).apply()

  /** The round new-chat and camera buttons floating over the chat list.
   *  Off by default: the left soft key ("New") does the same job without
   *  covering the list. */
  @JvmStatic
  var showRoundButtons: Boolean
    get() = prefs.getBoolean(SHOW_ROUND_BUTTONS, false)
    set(value) = prefs.edit().putBoolean(SHOW_ROUND_BUTTONS, value).apply()

  /** Emoji/sticker, quick-camera and voice-note buttons beside the
   *  message field. */
  @JvmStatic
  var showChatExtras: Boolean
    get() = prefs.getBoolean(SHOW_CHAT_EXTRAS, false)
    set(value) = prefs.edit().putBoolean(SHOW_CHAT_EXTRAS, value).apply()

  /** Signal's tip / promo banners at the bottom of the chat list. */
  @JvmStatic
  var showTips: Boolean
    get() = prefs.getBoolean(SHOW_TIPS, false)
    set(value) = prefs.edit().putBoolean(SHOW_TIPS, value).apply()

  /** Voice and video call buttons in a chat's top bar. When off they're
   *  still in the chat's Options menu. */
  @JvmStatic
  var showCallButtons: Boolean
    get() = prefs.getBoolean(SHOW_CALL_BUTTONS, false)
    set(value) = prefs.edit().putBoolean(SHOW_CALL_BUTTONS, value).apply()

  @JvmStatic
  val storiesEnabled: Boolean
    get() = !SignalStore.story.isFeatureDisabled

  @JvmStatic
  fun setStoriesEnabled(enabled: Boolean) {
    StoriesPrivacySettingsRepository().setStoriesEnabled(enabled)
      .subscribe({}, { Log.w(TAG, "couldn't change stories", it) })
  }

  /** Turns Stories off once, the first time the chat list opens after
   *  registration. Afterwards it's the user's choice. */
  @JvmStatic
  fun applyFirstRunDefaults() {
    if (prefs.getBoolean(STORIES_DEFAULTED, false)) return
    prefs.edit().putBoolean(STORIES_DEFAULTED, true).apply()
    if (storiesEnabled) setStoriesEnabled(false)
  }
}
