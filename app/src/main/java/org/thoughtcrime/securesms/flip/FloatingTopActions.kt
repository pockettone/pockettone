package org.thoughtcrime.securesms.flip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PocketTone's replacement for Signal's full chat-list top bar (avatar,
 * app name, search, three dots): two small see-through "glass" buttons
 * floating in the top-right corner, so the list gets the whole screen.
 *
 *  - "+ New" starts a new chat (same as the left soft key).
 *  - "⋮" opens the Options list, which now also holds Search, Settings
 *    and everything else the old bar had (same as the right soft key).
 *
 * Up from the first chat reaches them with the D-pad.
 */
@Composable
fun FloatingTopActions(
  onNewChat: () -> Unit,
  onMenu: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.padding(top = 4.dp, end = 6.dp)
  ) {
    GlassButton(text = "+ New", description = "New chat", onClick = onNewChat)
    GlassButton(text = "⋮", description = "Options and search", onClick = onMenu)
  }
}

private val GLASS = Color(0xA6222222)
private val GLASS_EDGE = Color(0x55FFFFFF)
private val FOCUS = Color(0xFF66CCFF)

@Composable
private fun GlassButton(text: String, description: String, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val shape = RoundedCornerShape(50)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .height(26.dp)
      .widthIn(min = 26.dp)
      .clip(shape)
      .background(if (focused) Color(0xE6113344) else GLASS, shape)
      .border(if (focused) 2.dp else 1.dp, if (focused) FOCUS else GLASS_EDGE, shape)
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 10.dp)
  ) {
    Text(text = text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
  }
}
